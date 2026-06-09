package ai.conexio.vehicle.consumer;

import io.lettuce.core.Consumer;
import io.lettuce.core.RedisBusyException;
import io.lettuce.core.RedisClient;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.XAutoClaimArgs;
import io.lettuce.core.XGroupCreateArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.XReadArgs.StreamOffset;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.models.stream.ClaimedMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;

/**
 * The real deliverable. Valkey/MemoryDB stream consumer backed by a CONSUMER GROUP
 * so no entry is missed across cold starts or downtime, and so the work can be
 * shared across several consumer instances:
 *   1. XREADGROUP the next VIN event(s) from the stream,
 *   2. GET &lt;VIN&gt; to fetch the JSON snapshot from the key/value store,
 *   3. print the JSON to its logs (-> CloudWatch on Fargate),
 *   4. XACK the entry so the server knows we are done with it.
 *
 * Scaling: run more instances in the same GROUP_NAME with DISTINCT consumer names
 * and the server load-balances entries across them (each entry to exactly one).
 * CONSUMER_NAME defaults to the task's hostname, which is unique per Fargate task,
 * so scaling is just "increase the service's desired count" - no per-task config.
 *
 * Delivery is at-least-once and nothing is lost:
 *   A) Cold start after the producer already wrote -> group is created at id "0",
 *      so the first ">" read delivers the full backlog.
 *   B) This consumer goes down and comes back -> on startup it first drains its OWN
 *      un-acked entries (reads from "0"); the group's position is server-tracked, so
 *      live reads resume without gaps.
 *   C) A DIFFERENT consumer dies mid-work -> its un-acked entries are stranded in the
 *      group's pending list. When idle, this consumer runs XAUTOCLAIM to take over any
 *      entry left un-acked longer than CLAIM_MIN_IDLE_MS and finish it.
 */
public class ConsumerMain {

    private static final Logger log = LoggerFactory.getLogger(ConsumerMain.class);

    public static void main(String[] args) {
        ValkeyConfig cfg = ValkeyConfig.fromEnv();
        String streamKey = System.getenv().getOrDefault("STREAM_KEY", "vehicle:vin-events");
        String groupName = System.getenv().getOrDefault("GROUP_NAME", "vehicle-consumers");
        String consumerName = resolveConsumerName();
        long blockMs = Long.parseLong(System.getenv().getOrDefault("BLOCK_MS", "5000"));
        // How long an entry must sit un-acked on ANOTHER consumer before we take it over.
        long claimMinIdleMs = Long.parseLong(System.getenv().getOrDefault("CLAIM_MIN_IDLE_MS", "60000"));

        RedisClient client = RedisClient.create(cfg.redisUri());
        try (StatefulRedisConnection<String, String> conn = client.connect()) {
            RedisCommands<String, String> redis = conn.sync();
            log.info("Consumer connected to {}:{} (tls={}, auth={}). Group '{}' as '{}' on stream '{}'",
                    cfg.host(), cfg.port(), cfg.tls(), cfg.authMode(), groupName, consumerName, streamKey);

            ensureGroup(redis, streamKey, groupName);

            // (B) Take back anything we were delivered but never acked before a restart.
            drainOwnPending(redis, streamKey, groupName, consumerName);

            Consumer<String> me = Consumer.from(groupName, consumerName);
            while (true) {
                List<StreamMessage<String, String>> live = redis.xreadgroup(me,
                        XReadArgs.Builder.block(blockMs).count(10),
                        StreamOffset.from(streamKey, ">")); // ">" = entries no one has been given yet

                if (live != null && !live.isEmpty()) {
                    for (StreamMessage<String, String> msg : live) {
                        handle(redis, msg);
                        redis.xack(streamKey, groupName, msg.getId());
                    }
                    continue; // keep pulling new entries while there are any
                }

                // (C) Idle (no new entries this window) -> good time to rescue entries
                // stranded on a dead/slow consumer.
                reclaimStranded(redis, streamKey, groupName, consumerName, claimMinIdleMs);
            }
        } finally {
            client.shutdown();
        }
    }

    /** CONSUMER_NAME if set, else the hostname (unique per Fargate task), else a fallback. */
    private static String resolveConsumerName() {
        String explicit = System.getenv("CONSUMER_NAME");
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        String host = System.getenv("HOSTNAME");
        if (host != null && !host.isBlank()) {
            return host;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "consumer-unknown";
        }
    }

    /** Create the group at id "0" (read full history). No-op if it already exists. */
    private static void ensureGroup(RedisCommands<String, String> redis, String streamKey, String groupName) {
        try {
            redis.xgroupCreate(StreamOffset.from(streamKey, "0"), groupName,
                    XGroupCreateArgs.Builder.mkstream());
            log.info("Created consumer group '{}' on '{}' starting at 0 (full backlog)", groupName, streamKey);
        } catch (RedisBusyException e) {
            log.info("Consumer group '{}' already exists on '{}'; resuming from its tracked position",
                    groupName, streamKey);
        }
    }

    /** Reprocess and ack any entries already delivered to THIS consumer but not yet acked. */
    private static void drainOwnPending(RedisCommands<String, String> redis, String streamKey,
                                        String groupName, String consumerName) {
        Consumer<String> me = Consumer.from(groupName, consumerName);
        String offset = "0"; // any id other than ">" reads from this consumer's pending list
        int reclaimed = 0;
        while (true) {
            List<StreamMessage<String, String>> pending = redis.xreadgroup(me,
                    XReadArgs.Builder.count(100), StreamOffset.from(streamKey, offset));
            if (pending == null || pending.isEmpty()) {
                break;
            }
            for (StreamMessage<String, String> msg : pending) {
                handle(redis, msg);
                redis.xack(streamKey, groupName, msg.getId());
                offset = msg.getId();
                reclaimed++;
            }
        }
        log.info("Recovered {} un-acked entr{} for '{}' from a previous run",
                reclaimed, reclaimed == 1 ? "y" : "ies", consumerName);
    }

    /** Take over entries left un-acked on any consumer longer than minIdleMs, and finish them. */
    private static void reclaimStranded(RedisCommands<String, String> redis, String streamKey,
                                        String groupName, String consumerName, long minIdleMs) {
        Consumer<String> me = Consumer.from(groupName, consumerName);
        String cursor = "0-0"; // scan the whole pending list from the start
        do {
            ClaimedMessages<String, String> claimed = redis.xautoclaim(streamKey,
                    XAutoClaimArgs.Builder.xautoclaim(me, Duration.ofMillis(minIdleMs), cursor).count(10));
            for (StreamMessage<String, String> msg : claimed.getMessages()) {
                log.info("Reclaimed stranded entry {} (idle > {} ms) from another consumer", msg.getId(), minIdleMs);
                handle(redis, msg);
                redis.xack(streamKey, groupName, msg.getId());
            }
            cursor = claimed.getId();
        } while (!"0-0".equals(cursor)); // XAUTOCLAIM returns "0-0" once it has scanned everything
    }

    /** Resolve a stream entry to its VIN snapshot and log it. */
    private static void handle(RedisCommands<String, String> redis, StreamMessage<String, String> msg) {
        String vin = msg.getBody().get("vin");
        if (vin == null) {
            log.warn("Stream entry {} had no 'vin' field, skipping", msg.getId());
            return;
        }
        String json = redis.get(vin);
        if (json == null) {
            log.warn("No snapshot found for VIN {} (stream id {}) - key may have expired", vin, msg.getId());
        } else {
            log.info("Snapshot for {} (stream id {}): {}", vin, msg.getId(), json);
        }
    }
}
