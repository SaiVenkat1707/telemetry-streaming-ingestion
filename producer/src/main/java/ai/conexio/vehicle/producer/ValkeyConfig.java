package ai.conexio.vehicle.producer;

import io.lettuce.core.RedisURI;

import java.time.Duration;

/**
 * Connection settings, all driven from environment variables so the exact same
 * image runs locally (plain Docker Valkey: no auth, no TLS) and on AWS
 * (MemoryDB: TLS + IAM auth) with no code changes.
 *
 * authMode:
 *   "none" (default) - local Docker Valkey, connect with no credentials.
 *   "iam"            - MemoryDB; Lettuce auto-generates a SigV4 token per connection
 *                      from the task's IAM role (see {@link IamAuthTokenGenerator}).
 *
 * For "iam": {@code host} is the cluster ENDPOINT DNS we connect to, while
 * {@code clusterName} is the MemoryDB cluster NAME used to sign the token.
 */
public record ValkeyConfig(
        String host,
        int port,
        boolean tls,
        String user,
        String authMode,
        String clusterName,
        String region) {

    public static ValkeyConfig fromEnv() {
        var env = System.getenv();
        return new ValkeyConfig(
                env.getOrDefault("VALKEY_HOST", "localhost"),
                Integer.parseInt(env.getOrDefault("VALKEY_PORT", "6379")),
                Boolean.parseBoolean(env.getOrDefault("VALKEY_TLS", "false")),
                env.get("VALKEY_USER"),
                env.getOrDefault("VALKEY_AUTH_MODE", "none"),
                env.get("VALKEY_CLUSTER_NAME"),
                env.getOrDefault("AWS_REGION", env.getOrDefault("VALKEY_REGION", "us-east-1")));
    }

    public RedisURI redisUri() {
        RedisURI.Builder b = RedisURI.builder()
                .withHost(host)
                .withPort(port)
                .withSsl(tls)
                .withTimeout(Duration.ofSeconds(10));
        if ("iam".equalsIgnoreCase(authMode)) {
            IamAuthTokenGenerator generator = new IamAuthTokenGenerator(user, clusterName, region);
            b.withAuthentication(new IamRedisCredentialsProvider(user, generator));
        }
        return b.build();
    }
}
