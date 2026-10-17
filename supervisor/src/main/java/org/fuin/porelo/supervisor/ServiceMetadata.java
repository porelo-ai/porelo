package org.fuin.porelo.supervisor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * Where a service's authorization is granted and spent, read from what the service publishes about
 * itself rather than typed in by a person.
 *
 * <p>The protected resource's metadata (RFC 9728) names its authorization server; that server's
 * metadata (RFC 8414) names the registration, device, authorization, token and revocation
 * endpoints. Each document must name the URL it was asked for, so a document published for another
 * resource or server is refused, and every endpoint is held to {@link Grants#secure}.
 */
public final class ServiceMetadata {

    /** Where the protected resource's metadata is, inserted between its host and its path. */
    static final String RESOURCE_SUFFIX = "/.well-known/oauth-protected-resource";

    /** Where an authorization server's metadata is, inserted between its host and its path. */
    static final String SERVER_SUFFIX = "/.well-known/oauth-authorization-server";

    /**
     * What a service's metadata names.
     *
     * @param issuer The authorization server's identifier.
     * @param registrationUrl The registration endpoint (RFC 7591), or {@code null} where the server
     *     registers no client itself.
     * @param deviceUrl The device authorization endpoint (RFC 8628), or {@code null}.
     * @param authorizationUrl The authorization endpoint, or {@code null}.
     * @param tokenUrl The token endpoint.
     * @param revocationUrl The revocation endpoint (RFC 7009), or {@code null}.
     */
    public record Endpoints(
            String issuer,
            @Nullable String registrationUrl,
            @Nullable String deviceUrl,
            @Nullable String authorizationUrl,
            String tokenUrl,
            @Nullable String revocationUrl) {}

    private ServiceMetadata() {}

    /**
     * Reads where a protected resource's authorization is granted and spent.
     *
     * @param http How the service is reached; it must not follow redirects, since a redirect would
     *     carry the question to a host the resource did not name.
     * @param resource The protected resource's URL, https or http on loopback.
     * @return The endpoints.
     * @throws TokenPurchase.Refused If a document is missing, names another resource or server,
     *     names no authorization server or token endpoint, names an endpoint that is not https, or
     *     the service cannot be reached.
     */
    public static Endpoints discover(HttpClient http, String resource)
            throws TokenPurchase.Refused {
        if (!Grants.secure(resource)) {
            throw new TokenPurchase.Refused(
                    "the resource URL must be https, or http on loopback, not '" + resource + "'");
        }
        final Map<?, ?> described = resourceDocument(http, resource);
        if (!(described.get("authorization_servers") instanceof List<?> servers)
                || servers.isEmpty()
                || !(servers.getFirst() instanceof String issuer)) {
            throw new TokenPurchase.Refused(
                    "the service at "
                            + Grants.host(resource)
                            + " names no authorization server in its metadata");
        }
        final String issuerUrl = secure(issuer, "authorization server");
        final Map<?, ?> server = fetch(http, wellKnown(issuerUrl, SERVER_SUFFIX));
        if (server == null) {
            throw new TokenPurchase.Refused(
                    "the authorization server at "
                            + Grants.host(issuerUrl)
                            + " publishes no metadata at "
                            + SERVER_SUFFIX);
        }
        if (!same(issuerUrl, server.get("issuer"))) {
            throw new TokenPurchase.Refused(
                    "the authorization server's metadata at "
                            + Grants.host(issuerUrl)
                            + " names another issuer, so it is not used");
        }
        final String token = optional(server, "token_endpoint");
        if (token == null) {
            throw new TokenPurchase.Refused(
                    "the authorization server at "
                            + Grants.host(issuerUrl)
                            + " names no token endpoint in its metadata");
        }
        return new Endpoints(
                issuerUrl,
                optional(server, "registration_endpoint"),
                optional(server, "device_authorization_endpoint"),
                optional(server, "authorization_endpoint"),
                token,
                optional(server, "revocation_endpoint"));
    }

    /**
     * Reads the resource's own document: at its path first, then at its host's root, as a resource
     * served below a path may publish it either way.
     */
    private static Map<?, ?> resourceDocument(HttpClient http, String resource)
            throws TokenPurchase.Refused {
        final String atPath = wellKnown(resource, RESOURCE_SUFFIX);
        Map<?, ?> described = fetch(http, atPath);
        String identifier = resource;
        if (described == null) {
            final URI uri = URI.create(resource);
            identifier = uri.getScheme() + "://" + uri.getRawAuthority();
            final String atRoot = identifier + RESOURCE_SUFFIX;
            described = atRoot.equals(atPath) ? null : fetch(http, atRoot);
        }
        if (described == null) {
            throw new TokenPurchase.Refused(
                    "the service at "
                            + Grants.host(resource)
                            + " publishes no metadata at "
                            + RESOURCE_SUFFIX
                            + "; set the endpoints on the entry instead");
        }
        final Object said = described.get("resource");
        if (!same(resource, said) && !same(identifier, said)) {
            throw new TokenPurchase.Refused(
                    "the metadata at "
                            + Grants.host(resource)
                            + " describes another resource, so it is not used");
        }
        return described;
    }

    /**
     * Returns where a well-known document is for an identifier: the suffix between its host and its
     * path (RFC 8414 section 3.1, RFC 9728 section 3.1).
     *
     * @param identifier The resource or the issuer.
     * @param suffix The well-known path.
     * @return The document's URL.
     */
    static String wellKnown(String identifier, String suffix) {
        final URI uri = URI.create(identifier);
        final String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        return uri.getScheme()
                + "://"
                + uri.getRawAuthority()
                + suffix
                + ("/".equals(path) ? "" : path);
    }

    private static @Nullable String optional(Map<?, ?> document, String key)
            throws TokenPurchase.Refused {
        return document.get(key) instanceof String value && !value.isBlank()
                ? secure(value, key.replace('_', ' '))
                : null;
    }

    private static String secure(String url, String what) throws TokenPurchase.Refused {
        if (!Grants.secure(url)) {
            throw new TokenPurchase.Refused(
                    "the service's "
                            + what
                            + " is not https, nor http on loopback, so it is not used: '"
                            + url
                            + "'");
        }
        return url;
    }

    private static boolean same(String expected, @Nullable Object said) {
        return said instanceof String text && strip(text).equals(strip(expected));
    }

    private static String strip(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * Fetches a JSON document.
     *
     * @return The document, or {@code null} where the service has none there (404).
     */
    private static @Nullable Map<?, ?> fetch(HttpClient http, String url)
            throws TokenPurchase.Refused {
        final HttpResponse<String> answer;
        try {
            answer =
                    http.send(
                            HttpRequest.newBuilder(URI.create(url))
                                    .timeout(Duration.ofSeconds(30))
                                    .header("Accept", "application/json")
                                    .GET()
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
        if (answer.statusCode() == 404) {
            return null;
        }
        if (answer.statusCode() != 200) {
            throw new TokenPurchase.Refused(
                    "the service at "
                            + Grants.host(url)
                            + " answered "
                            + answer.statusCode()
                            + " for its metadata");
        }
        try {
            if (Json.parse(answer.body()) instanceof Map<?, ?> document) {
                return document;
            }
        } catch (RuntimeException ex) {
            // Said below, without the body.
        }
        throw new TokenPurchase.Refused(
                "the service at "
                        + Grants.host(url)
                        + " answered metadata that is not a JSON object");
    }
}
