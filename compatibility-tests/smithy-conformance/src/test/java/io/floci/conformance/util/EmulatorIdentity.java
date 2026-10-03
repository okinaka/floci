package io.floci.conformance.util;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks the target emulator which account it believes it is, via
 * {@code sts:GetCallerIdentity}.
 *
 * <p>Emulators do not agree: floci and ministack answer {@code 000000000000}
 * while fakecloud answers {@code 123456789012}. The harness synthesizes ARNs
 * for ~190 members across the six models, and an ARN in the wrong account is
 * cross-account input, which an implementation that checks the account
 * correctly rejects. Pinning one literal therefore favours whichever emulator
 * happens to share it, so the account is read from the target instead.
 */
public final class EmulatorIdentity {

    /** The AWS-local convention, used when the target does not answer. */
    public static final String DEFAULT_ACCOUNT = "000000000000";

    private static final Pattern ACCOUNT = Pattern.compile("<Account>([0-9]{12})</Account>");

    private EmulatorIdentity() {
    }

    /** The target's own account id, or {@link #DEFAULT_ACCOUNT} if it cannot be read. */
    public static String accountOf(String baseUrl) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", "AWS4-HMAC-SHA256 "
                        + "Credential=test/20260101/us-east-1/sts/aws4_request, "
                        + "SignedHeaders=host, Signature=cov-probe")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "Action=GetCallerIdentity&Version=2011-06-15"))
                .build();
        try {
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            Matcher m = ACCOUNT.matcher(response.body() == null ? "" : response.body());
            return m.find() ? m.group(1) : DEFAULT_ACCOUNT;
        } catch (java.io.IOException e) {
            return DEFAULT_ACCOUNT;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return DEFAULT_ACCOUNT;
        }
    }
}
