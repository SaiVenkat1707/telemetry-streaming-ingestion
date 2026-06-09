package ai.conexio.vehicle.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.lettuce.core.RedisClient;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * SIMULATED upstream. Stands in for "their software" until we get real access.
 * Every tick it:
 *   1. builds a randomized vehicle snapshot,
 *   2. SET &lt;VIN&gt; &lt;JSON&gt;        (the key/value store)
 *   3. XADD vehicle:vin-events vin=&lt;VIN&gt;   (the event carries ONLY the VIN)
 */
public class ProducerMain {

    private static final Logger log = LoggerFactory.getLogger(ProducerMain.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A small fixed fleet so the same VINs recur and their snapshots update over time. */
    private static final String[] FLEET = {
            "1HGBH41JXMN109186",
            "5YJ3E1EA7KF328170",
            "WBA8E9G50GNT12345",
            "JH4KA9650MC012345",
            "1FTFW1ET5DFC10312"
    };

    public static void main(String[] args) throws Exception {
        ValkeyConfig cfg = ValkeyConfig.fromEnv();
        long intervalMs = Long.parseLong(System.getenv().getOrDefault("PRODUCE_INTERVAL_MS", "2000"));
        long ttlSeconds = Long.parseLong(System.getenv().getOrDefault("SNAPSHOT_TTL_SECONDS", "3600"));
        String streamKey = System.getenv().getOrDefault("STREAM_KEY", "vehicle:vin-events");

        RedisClient client = RedisClient.create(cfg.redisUri());
        try (StatefulRedisConnection<String, String> conn = client.connect()) {
            RedisCommands<String, String> redis = conn.sync();
            log.info("Producer connected to {}:{} (tls={}, auth={}). Emitting every {} ms to stream '{}'",
                    cfg.host(), cfg.port(), cfg.tls(), cfg.authMode(), intervalMs, streamKey);

            while (true) {
                String vin = FLEET[ThreadLocalRandom.current().nextInt(FLEET.length)];
                String json = buildSnapshot(vin);

                redis.set(vin, json, SetArgs.Builder.ex(ttlSeconds));
                String id = redis.xadd(streamKey, Map.of("vin", vin));

                log.info("Produced snapshot for {} -> stream id {}", vin, id);
                Thread.sleep(intervalMs);
            }
        } finally {
            client.shutdown();
        }
    }

    private static String buildSnapshot(String vin) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        ObjectNode node = MAPPER.createObjectNode();
        node.put("vin", vin);
        node.put("capturedAt", Instant.now().toString());
        node.put("odometerKm", round1(r.nextDouble(0, 200_000)));
        node.put("speedKph", round1(r.nextDouble(0, 180)));
        node.put("engineRpm", r.nextInt(700, 6000));
        node.put("fuelLevelPct", round1(r.nextDouble(0, 100)));
        node.put("coolantTempC", round1(r.nextDouble(60, 110)));
        node.put("batteryVoltage", round1(r.nextDouble(11.5, 14.8)));
        node.put("gear", new String[]{"P", "R", "N", "D"}[r.nextInt(4)]);
        node.put("ignitionOn", r.nextBoolean());
        ObjectNode loc = node.putObject("location");
        loc.put("lat", round4(r.nextDouble(17.2, 17.6)));
        loc.put("lon", round4(r.nextDouble(78.2, 78.6)));
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize snapshot for " + vin, e);
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}
