package org.fuin.porelo.supervisor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * A client Porelo registers itself with a service that offers it (RFC 7591), and deletes there
 * again where the service offers that (RFC 7592).
 *
 * <p>Registered on the host, once per vault entry, never in a task: a registration made in a
 * container would die with it and be made again for every task, which services rate-limit. A public
 * client, since Porelo ships no secret; where the service issues one all the same, it is kept.
 */
public final class ClientRegistration {

    /** The name a service shows the person for the client it registered. */
    static final String CLIENT_NAME = "Porelo";

    /**
     * What a service registered.
     *
     * @param clientId The client's id.
     * @param clientSecret The client's secret, or {@code null} for a public client.
     * @param accessToken The registration access token, or {@code null} where the service offers no
     *     management of the registration.
     * @param clientUri The registration's own URL, or {@code null} where the service offers no
     *     management of the registration.
     */
    public record Registered(
            String clientId,
            @Nullable String clientSecret,
            @Nullable String accessToken,
            @Nullable String clientUri) {

        /**
         * Says what was registered, never its secrets: a record would print every component.
         *
         * @return The client id and whether the secrets are there.
         */
        @Override
        public String toString() {
            return "Registered[clientId="
                    + clientId
                    + ", clientSecret="
                    + (clientSecret == null ? "none" : "<held>")
                    + ", accessToken="
                    + (accessToken == null ? "none" : "<held>")
                    + ", clientUri="
                    + clientUri
                    + "]";
        }

        /**
         * Returns whether the service can be asked to delete this registration.
         *
         * @return true when both its URL and its access token were issued.
         */
        public boolean managed() {
            return accessToken != null && clientUri != null;
        }
    }

    private ClientRegistration() {}

    /**
     * Returns what Porelo asks to be registered as, for a flow.
     *
     * @param device Whether the person grants by a device code; otherwise by a redirect.
     * @param redirectUri Where the redirect comes back to, for the redirect flow.
     * @param scopes The scopes, space-separated, or empty.
     * @return The client metadata (RFC 7591 section 2).
     */
    public static Map<String, Object> metadata(
            boolean device, @Nullable String redirectUri, String scopes) {
        final Map<String, Object> asked = new LinkedHashMap<>();
        asked.put("client_name", CLIENT_NAME);
        asked.put("token_endpoint_auth_method", "none");
        if (device) {
            asked.put(
                    "grant_types",
                    List.of("urn:ietf:params:oauth:grant-type:device_code", "refresh_token"));
        } else {
            asked.put("grant_types", List.of("authorization_code", "refresh_token"));
            asked.put("response_types", List.of("code"));
            asked.put("redirect_uris", List.of(java.util.Objects.requireNonNull(redirectUri)));
        }
        if (!scopes.isBlank()) {
            asked.put("scope", scopes);
        }
        return asked;
    }

    /**
     * Registers a client.
     *
     * @param http How the service is reached.
     * @param registrationUrl The registration endpoint, https or http on loopback.
     * @param metadata What to register, from {@link #metadata}.
     * @return What the service registered.
     * @throws TokenPurchase.Refused If the service refuses, answers without a client id, names a
     *     registration URL that is not https, or cannot be reached.
     */
    public static Registered register(
            HttpClient http, String registrationUrl, Map<String, Object> metadata)
            throws TokenPurchase.Refused {
        if (!Grants.secure(registrationUrl)) {
            throw new TokenPurchase.Refused(
                    "the registration endpoint must be https, or http on loopback, not '"
                            + registrationUrl
                            + "'");
        }
        final HttpResponse<String> answer =
                send(
                        http,
                        HttpRequest.newBuilder(URI.create(registrationUrl))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(Json.write(metadata))),
                        registrationUrl);
        if (answer.statusCode() / 100 != 2) {
            throw new TokenPurchase.Refused(
                    "the service at "
                            + Grants.host(registrationUrl)
                            + " refused to register a client ("
                            + answer.statusCode()
                            + ", "
                            + Grants.error(answer.body())
                            + ")");
        }
        final Map<?, ?> document;
        try {
            document = Json.parse(answer.body()) instanceof Map<?, ?> parsed ? parsed : Map.of();
        } catch (RuntimeException ex) {
            throw new TokenPurchase.Refused(
                    "the service at "
                            + Grants.host(registrationUrl)
                            + " registered a client and answered something that is not JSON",
                    ex);
        }
        final String clientId = text(document, "client_id");
        if (clientId == null) {
            throw new TokenPurchase.Refused(
                    "the service at "
                            + Grants.host(registrationUrl)
                            + " answered the registration without a client id");
        }
        final String clientUri = text(document, "registration_client_uri");
        if (clientUri != null && !Grants.secure(clientUri)) {
            throw new TokenPurchase.Refused(
                    "the service at "
                            + Grants.host(registrationUrl)
                            + " named a registration URL that is not https, so its token is not sent there");
        }
        return new Registered(
                clientId,
                text(document, "client_secret"),
                text(document, "registration_access_token"),
                clientUri);
    }

    /**
     * Deletes a registration at the service (RFC 7592 section 2.3).
     *
     * <p>A registration the service no longer knows (404 or 410) is the wanted state: deleting
     * twice is not a failure.
     *
     * @param http How the service is reached.
     * @param clientUri The registration's own URL.
     * @param accessToken The registration access token.
     * @throws TokenPurchase.Refused If the service refuses or cannot be reached.
     */
    public static void delete(HttpClient http, String clientUri, String accessToken)
            throws TokenPurchase.Refused {
        if (!Grants.secure(clientUri)) {
            throw new TokenPurchase.Refused(
                    "the registration URL is not https, nor http on loopback, so its token is not sent"
                            + " there");
        }
        final HttpResponse<String> answer =
                send(
                        http,
                        HttpRequest.newBuilder(URI.create(clientUri))
                                .header("Authorization", "Bearer " + accessToken)
                                .DELETE(),
                        clientUri);
        final int status = answer.statusCode();
        if (status / 100 != 2 && status != 404 && status != 410) {
            throw new TokenPurchase.Refused(
                    "the service at "
                            + Grants.host(clientUri)
                            + " refused to delete the client registration ("
                            + status
                            + ", "
                            + Grants.error(answer.body())
                            + ")");
        }
    }

    private static HttpResponse<String> send(
            HttpClient http, HttpRequest.Builder request, String url) throws TokenPurchase.Refused {
        try {
            return http.send(
                    request.timeout(Duration.ofSeconds(30))
                            .header("Accept", "application/json")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new TokenPurchase.Refused(
                    "the service at "
                            + Grants.host(url)
                            + " could not be reached: "
                            + Grants.cause(ex),
                    ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new TokenPurchase.Refused("interrupted while asking " + Grants.host(url), ex);
        }
    }

    private static @Nullable String text(Map<?, ?> document, String key) {
        return document.get(key) instanceof String value && !value.isBlank() ? value : null;
    }
}
