package org.fuin.porelo.organization.gittree;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.fuin.porelo.organization.IdentitySource;
import org.fuin.porelo.vault.RegistryText;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.JsonException;
import org.jspecify.annotations.Nullable;

/**
 * The central forge asked over HTTPS: GitHub on {@code github.com}, any other host as Forgejo or
 * Gitea.
 *
 * <p>Keys come from the page {@code <forge>/<login>.keys}, which both kinds serve without a token,
 * and on GitHub also from the signing keys its API lists without one. Only Ed25519 keys are kept,
 * as {@code ssh-ed25519 <base64>} without their comment. Asked about one key, GitHub's API is asked
 * only when the page does not list it, so that a rate limit others spend cannot leave a listed key
 * unsettled. Ownership is asked with the token: on GitHub the organisation membership's role, on
 * Forgejo the login's permissions in the organisation.
 *
 * <p>Whatever the forge answers but a plain yes or no - a failure, a timeout, a server error, a
 * rate limit, another status, an answer over {@value #LIMIT} bytes or one that does not parse - is
 * "cannot say", never a no: a no refuses a person, and a forge being down must not. What a token
 * may not see is no answer either: a 404 for an organisation or membership says what the token
 * cannot see, not what the login is - unless, on GitHub, the token's own account is an active admin
 * of the organisation, or the token is a GitHub App's installation token for the organisation
 * (which must be granted Members: read), and each sees every membership.
 *
 * <p>The client is the caller's; it should trust what the account trusts ({@code
 * TrustedCertificates}) and must follow no redirects, so an answer cannot move to another host or
 * to plain HTTP. A redirect is then another status, and so "cannot say".
 */
public final class HttpForgeDirectory implements IdentitySource {

    /** The most an answer may hold, in bytes. */
    static final int LIMIT = 1024 * 1024;

    /** How long one request may take, from sending it to its last byte. */
    static final Duration TIMEOUT = Duration.ofSeconds(10);

    /** Where GitHub's API is, for a forge on {@code github.com}. */
    static final URI GITHUB_API = URI.create("https://api.github.com");

    /**
     * A login or organisation: what GitHub and Forgejo both allow, and nothing that could leave the
     * path segment it is put in.
     */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    private static final Pattern BASE64 = Pattern.compile("[A-Za-z0-9+/]+={0,2}");

    private final String forge;

    private final @Nullable String api;

    private final @Nullable String token;

    private final HttpClient client;

    private final Duration timeout;

    /**
     * Creates the directory of a forge.
     *
     * @param forge The forge's address, {@code https://<host>[/<path>]}; plain {@code http} only to
     *     127.0.0.1 or [::1].
     * @param token The token ownership is asked with, or {@code null} to not ask it.
     * @param client How the forge is asked.
     * @throws IllegalArgumentException When the address is not one, the token holds a character an
     *     HTTP header cannot carry, or the client follows redirects; the message never holds the
     *     token.
     */
    public HttpForgeDirectory(
            final URI forge, final @Nullable String token, final HttpClient client) {
        this(
                forge,
                "github.com".equalsIgnoreCase(forge.getHost()) ? GITHUB_API : null,
                token,
                client,
                TIMEOUT);
    }

    /**
     * Creates the directory of a forge, with GitHub's API at a given address.
     *
     * @param forge The forge's address.
     * @param githubApi Where GitHub's API is, or {@code null} for a Forgejo forge.
     * @param token The token ownership is asked with, or {@code null}.
     * @param client How the forge is asked.
     * @param timeout How long one request may take.
     */
    HttpForgeDirectory(
            final URI forge,
            final @Nullable URI githubApi,
            final @Nullable String token,
            final HttpClient client,
            final Duration timeout) {
        if (client.followRedirects() != HttpClient.Redirect.NEVER) {
            // A redirect would let another host, or plain HTTP, answer for the forge's key list.
            throw new IllegalArgumentException(
                    "the forge is asked by a client that follows no redirects");
        }
        this.forge = base(forge);
        this.api = githubApi == null ? null : base(githubApi);
        if (token != null
                && (token.isEmpty() || !token.chars().allMatch(c -> c > 0x20 && c < 0x7f))) {
            throw new IllegalArgumentException(
                    "the forge token is empty or holds a character an HTTP header cannot carry");
        }
        this.token = token;
        this.client = client;
        this.timeout = timeout;
    }

    @Override
    public Optional<Keys> keys(final String login) {
        return lists(login, null);
    }

    @Override
    public Optional<Keys> keys(final String login, final String fingerprint) {
        return lists(login, fingerprint);
    }

    private Optional<Keys> lists(final String login, final @Nullable String wanted) {
        final String name = name(login, "login");
        final Answer page = get(forge + "/" + name + ".keys", null);
        if (page != null && page.status() == 404) {
            // The forge has no such account, so it lists no key at all: nothing else to ask.
            return Optional.of(new Keys(List.of(), true));
        }
        final Optional<List<String>> listed = keyList(page, false);
        if (listed.isEmpty() || api == null) {
            return listed.map(keys -> new Keys(keys, true));
        }
        final Keys onPage = new Keys(listed.get(), false);
        if (wanted != null && org.fuin.porelo.vault.PersonCard.lists(onPage.keys(), wanted)) {
            // The page vouches for the key already; the API's rate limit is not spent on it.
            return Optional.of(onPage);
        }
        final Optional<List<String>> signing =
                keyList(get(api + "/users/" + name + "/ssh_signing_keys", null), true);
        if (signing.isEmpty()) {
            // The page still vouches for what it lists; what it does not list may be a signing
            // key GitHub did not say, so it proves nothing missing.
            return Optional.of(onPage);
        }
        final Set<String> merged = new LinkedHashSet<>(listed.get());
        merged.addAll(signing.get());
        return Optional.of(new Keys(List.copyOf(merged), true));
    }

    @Override
    public Optional<Boolean> owns(final String organisation, final String login) {
        final String org = name(organisation, "organisation");
        final String name = name(login, "login");
        if (token == null) {
            return Optional.empty();
        }
        return api != null ? gitHubOwns(api, token, org, name) : forgejoOwns(token, org, name);
    }

    @Override
    public String toString() {
        return "HttpForgeDirectory["
                + forge
                + (api == null ? ", forgejo" : ", github " + api)
                + (token == null ? ", no token" : ", token set")
                + "]";
    }

    private Optional<Boolean> gitHubOwns(
            final String api, final String token, final String org, final String name) {
        final String authorization = "Bearer " + token;
        // Only an admin of the organisation sees every membership; anybody else gets a 404 for
        // one they may not see, which then says nothing.
        final Answer own = get(api + "/user/memberships/orgs/" + org, authorization);
        final boolean seesAll = own != null && own.status() == 200 && activeAdmin(own.body());
        final Answer answer = get(api + "/orgs/" + org + "/memberships/" + name, authorization);
        if (answer == null) {
            return Optional.empty();
        }
        if (answer.status() == 404 && (seesAll || installedOn(api, authorization, org))) {
            return Optional.of(false);
        }
        if (answer.status() != 200 || !(json(answer.body()) instanceof Map<?, ?>)) {
            return Optional.empty();
        }
        return Optional.of(activeAdmin(answer.body()));
    }

    /**
     * Whether the token is a GitHub App's installation token for the organisation: such a token has
     * no account to ask about, and the repositories its installation reaches are the
     * organisation's. An installation that reaches none cannot say whose it is.
     */
    private boolean installedOn(final String api, final String authorization, final String org) {
        final Answer answer = get(api + "/installation/repositories?per_page=1", authorization);
        return answer != null
                && answer.status() == 200
                && json(answer.body()) instanceof Map<?, ?> installation
                && installation.get("repositories") instanceof List<?> repositories
                && !repositories.isEmpty()
                && repositories.get(0) instanceof Map<?, ?> repository
                && repository.get("owner") instanceof Map<?, ?> owner
                && "Organization".equals(owner.get("type"))
                && owner.get("login") instanceof String login
                && login.equalsIgnoreCase(org);
    }

    private static boolean activeAdmin(final byte[] body) {
        return json(body) instanceof Map<?, ?> membership
                && "admin".equals(membership.get("role"))
                && "active".equals(membership.get("state"));
    }

    private Optional<Boolean> forgejoOwns(final String token, final String org, final String name) {
        final Answer answer =
                get(
                        forge + "/api/v1/users/" + name + "/orgs/" + org + "/permissions",
                        "token " + token);
        // A 404 or 403 is also what a token answers that may not see the organisation.
        if (answer == null
                || answer.status() != 200
                || !(json(answer.body()) instanceof Map<?, ?> permissions)
                || !(permissions.get("is_owner") instanceof Boolean owner)) {
            return Optional.empty();
        }
        return Optional.of(owner);
    }

    private static Optional<List<String>> keyList(
            final @Nullable Answer answer, final boolean json) {
        if (answer == null) {
            return Optional.empty();
        }
        if (answer.status() == 404) {
            return Optional.of(List.of());
        }
        if (answer.status() != 200) {
            return Optional.empty();
        }
        final List<String> lines = new ArrayList<>();
        if (json) {
            if (!(json(answer.body()) instanceof List<?> entries)) {
                return Optional.empty();
            }
            for (Object each : entries) {
                if (each instanceof Map<?, ?> entry && entry.get("key") instanceof String key) {
                    lines.add(key);
                }
            }
        } else {
            lines.addAll(new String(answer.body(), StandardCharsets.UTF_8).lines().toList());
        }
        final Set<String> keys = new LinkedHashSet<>();
        for (String line : lines) {
            final String[] parts = line.strip().split("\\s+");
            if (parts.length >= 2
                    && "ssh-ed25519".equals(parts[0])
                    && BASE64.matcher(parts[1]).matches()) {
                keys.add("ssh-ed25519 " + parts[1]);
            }
        }
        return Optional.of(List.copyOf(keys));
    }

    private static @Nullable Object json(final byte[] body) {
        try {
            return Json.parse(new String(body, StandardCharsets.UTF_8));
        } catch (JsonException ex) {
            return null;
        }
    }

    /**
     * Asks once, bounded in time and size.
     *
     * @return The answer, or {@code null} when none came whole.
     */
    private @Nullable Answer get(final String url, final @Nullable String authorization) {
        final HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(timeout)
                        .header(
                                "Accept",
                                api != null && url.startsWith(api + "/")
                                        ? "application/vnd.github+json"
                                        : "*/*")
                        .GET();
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        // The request's own timeout ends with the headers; the body is bounded by this one.
        final CompletableFuture<HttpResponse<byte[]>> pending =
                client.sendAsync(request.build(), info -> new Bounded());
        try {
            final HttpResponse<byte[]> response =
                    pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new Answer(response.statusCode(), response.body());
        } catch (TimeoutException ex) {
            pending.cancel(true);
            return null;
        } catch (ExecutionException ex) {
            return null;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            pending.cancel(true);
            return null;
        }
    }

    private static String base(final URI uri) {
        final String host = uri.getHost();
        final String scheme = uri.getScheme();
        final boolean secure =
                "https".equals(scheme)
                        || "http".equals(scheme)
                                && ("127.0.0.1".equals(host) || "[::1]".equals(host));
        if (!secure
                || host == null
                || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "'"
                            + RegistryText.printable(uri.toString())
                            + "' is not an https address of a forge (plain http only to"
                            + " 127.0.0.1 or [::1])");
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return scheme + "://" + uri.getRawAuthority() + path;
    }

    private static String name(final String value, final String what) {
        if (!NAME.matcher(value).matches() || value.contains("..")) {
            throw new IllegalArgumentException(
                    "'" + RegistryText.printable(value) + "' is not a forge " + what);
        }
        return value;
    }

    /** A status and the whole body that came with it. */
    private record Answer(int status, byte[] body) {}

    /** Collects a body, giving up past {@link #LIMIT} bytes instead of holding more. */
    private static final class Bounded implements HttpResponse.BodySubscriber<byte[]> {

        private final CompletableFuture<byte[]> result = new CompletableFuture<>();

        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        private Flow.@Nullable Subscription subscription;

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(final Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(final List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer each : items) {
                if (out.size() + each.remaining() > LIMIT) {
                    if (subscription != null) {
                        subscription.cancel();
                    }
                    result.completeExceptionally(
                            new IllegalStateException("the answer is over " + LIMIT + " bytes"));
                    return;
                }
                final byte[] bytes = new byte[each.remaining()];
                each.get(bytes);
                out.write(bytes, 0, bytes.length);
            }
        }

        @Override
        public void onError(final Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(out.toByteArray());
        }
    }
}
