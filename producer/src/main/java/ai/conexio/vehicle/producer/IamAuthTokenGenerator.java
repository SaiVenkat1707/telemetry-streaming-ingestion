package ai.conexio.vehicle.producer;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4PresignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;

import java.time.Duration;
import java.time.Instant;

/**
 * Generates a short-lived MemoryDB IAM authentication token: a SigV4 pre-signed
 * GET request to {@code http://<clusterName>/?Action=connect&User=<user>}, signed
 * for service "memorydb", with the URL scheme stripped. The result is used as the
 * password in the Valkey AUTH/HELLO command. Token is valid 15 minutes; Lettuce's
 * credentials provider calls this again on each (re)connection.
 *
 * NOTE: signing uses the MemoryDB CLUSTER NAME (e.g. "vehicle-memorydb"), which is
 * different from the cluster ENDPOINT DNS we actually connect to.
 */
public class IamAuthTokenGenerator {

    private static final String SERVICE_NAME = "memorydb";
    private static final Duration TOKEN_EXPIRY = Duration.ofSeconds(900);

    private final String userName;
    private final String clusterName;
    private final Region region;
    private final AwsCredentialsProvider credentialsProvider;
    private final Aws4Signer signer = Aws4Signer.create();

    public IamAuthTokenGenerator(String userName, String clusterName, String region) {
        this.userName = userName;
        this.clusterName = clusterName;
        this.region = Region.of(region);
        // On Fargate this resolves the task role via the ECS container credentials endpoint.
        this.credentialsProvider = DefaultCredentialsProvider.create();
    }

    public String newToken() {
        SdkHttpFullRequest request = SdkHttpFullRequest.builder()
                .method(SdkHttpMethod.GET)
                .protocol("http")
                .host(clusterName)
                .encodedPath("/")
                .appendRawQueryParameter("Action", "connect")
                .appendRawQueryParameter("User", userName)
                .build();

        Aws4PresignerParams params = Aws4PresignerParams.builder()
                .awsCredentials(credentialsProvider.resolveCredentials())
                .signingName(SERVICE_NAME)
                .signingRegion(region)
                .expirationTime(Instant.now().plus(TOKEN_EXPIRY))
                .build();

        SdkHttpFullRequest signed = signer.presign(request, params);
        return signed.getUri().toString().replaceFirst("^http://", "");
    }
}
