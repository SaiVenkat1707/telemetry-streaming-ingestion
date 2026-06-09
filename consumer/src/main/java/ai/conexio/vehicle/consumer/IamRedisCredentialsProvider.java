package ai.conexio.vehicle.consumer;

import io.lettuce.core.RedisCredentials;
import io.lettuce.core.RedisCredentialsProvider;
import reactor.core.publisher.Mono;

/**
 * Lettuce credentials provider that supplies (username, fresh-IAM-token) on every
 * resolve. Lettuce calls this on each new connection / re-AUTH, so connections that
 * outlive the 15-minute token validity transparently get a new token.
 */
public class IamRedisCredentialsProvider implements RedisCredentialsProvider {

    private final String userName;
    private final IamAuthTokenGenerator tokenGenerator;

    public IamRedisCredentialsProvider(String userName, IamAuthTokenGenerator tokenGenerator) {
        this.userName = userName;
        this.tokenGenerator = tokenGenerator;
    }

    @Override
    public Mono<RedisCredentials> resolveCredentials() {
        return Mono.fromSupplier(() -> RedisCredentials.just(userName, tokenGenerator.newToken()));
    }
}
