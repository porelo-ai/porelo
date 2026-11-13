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

    private static final String NO_ANSWER = "no answer";

    private static final String RATE_LIMITED = "rate limited";

    private static final String ANOTHER_STATUS = "another status";

    private static final String UNREADABLE = "an answer that does not read";

    private static final String NO_TOKEN = "no token for the organisation's members";

    private static final String BLIND_TOKEN = "the token cannot see the organisation's members";

    /** Every kind {@link #cannotSay()} answers: Porelo's own words, never the forge's. */
    static final Set<String> KINDS =
            Set.of(NO_ANSWER, RATE_LIMITED, ANOTHER_STATUS, UNREADABLE, NO_TOKEN, BLIND_TOKEN);

    /** Why the last question on this thread went unanswered; one walk asks on one thread. */
    private final ThreadLocal<@Nullable String> unanswered = new ThreadLocal<>();

    /** Whether the last answer on this thread that came back as none was over the bound. */
    private final ThreadLocal<@Nullable Boolean> tooLong = new ThreadLocal<>();

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

    /** Public profile fields, absent when the forge cannot answer. */
    public record Profile(@Nullable String login, @Nullable String name, @Nullable String email) {}

    /** The account the configured token belongs to. */
    public Optional<Profile> account() {
        return token == null
                ? Optional.empty()
                : profileGet(apiBase() + "/user", (api == null ? "token " : "Bearer ") + token);
    }

    /**
     * What the forge says of the configured token, without saying it anywhere.
     *
     * @param state How it stands.
     * @param login Whose it is, when the forge said.
     */
    public record TokenAccount(TokenState state, @Nullable String login) {}

    /** How a token stands at the forge. */
    public enum TokenState {
        /** No token was configured. */
        NONE,
        /** The forge took it, and says whose it is. */
        ACCEPTED,
        /** The forge refused it. */
        REFUSED,
        /** The forge took it and will not say whose, as for an app's installation token. */
        CANNOT_SAY,
        /** The forge did not answer. */
        NOT_REACHED
    }

    /**
     * Asks whose the configured token is, telling refused from not reached.
     *
     * @return The answer.
     */
    public TokenAccount tokenAccount() {
        if (token == null) {
            return new TokenAccount(TokenState.NONE, null);
        }
        final Answer answer =
                get(apiBase() + "/user", (api == null ? "token " : "Bearer ") + token);
        if (answer == null) {
            return new TokenAccount(TokenState.NOT_REACHED, null);
        }
        if (answer.status() == 401) {
            return new TokenAccount(TokenState.REFUSED, null);
        }
        final @Nullable String login =
                answer.status() == 200 && json(answer.body()) instanceof Map<?, ?> fields
                        ? field(fields, "login")
                        : null;
        return login == null
                ? new TokenAccount(TokenState.CANNOT_SAY, null)
                : new TokenAccount(TokenState.ACCEPTED, login);
    }

    /** How far a token reaches for one thing a move asks of it. */
    public enum ReachState {
        /** The forge says the token may. */
        READY,
        /** The forge says the token may not. */
        MISSING,
        /** The forge does not say whether the token may. */
        CANNOT_TELL
    }

    /**
     * One thing a move asks of a token, and how the token stands for it.
     *
     * @param need What the move does with it: {@code members}, {@code administration}, {@code
     *     contents} or {@code workflows}.
     * @param permission The forge's own name for the permission it takes.
     * @param state How the token stands.
     */
    public record Reach(String need, String permission, ReachState state) {}

    /**
     * Asks how far the configured token reaches in an organization, changing nothing.
     *
     * <p>A move reads who owns the organization (members), makes a project's repository and adds a
     * host's read key (administration), pushes and fetches (contents) and changes a workflow file
     * (workflows). Each is asked by a read the move itself makes, or the nearest one to it. GitHub
     * names a classic token's scopes in every answer, so a lacking scope is told; a fine-grained
     * token's and a Forgejo token's permissions are named nowhere, so a refused read is missing and
     * a granted one leaves a write untold. Only a refusal for want of the permission is missing: a
     * rate limit's or a single sign-on's 403 is untold.
     *
     * @param organisation The organization.
     * @param repository A repository of it the token should reach, its organization's own.
     * @return One reach per need, in that order; none without a token.
     */
    public List<Reach> tokenReach(final String organisation, final String repository) {
        final String org = name(organisation, "organisation");
        final String repo = name(repository, "repository");
        if (token == null) {
            return List.of();
        }
        return api == null ? forgejoReach(token, org, repo) : gitHubReach(api, token, org, repo);
    }

    private List<Reach> gitHubReach(
            final String api, final String token, final String org, final String repo) {
        final String authorization = "Bearer " + token;
        final Answer user = get(api + "/user", authorization);
        if (user == null || user.status() != 200) {
            return untold(
                    "Members: Read",
                    "Administration: Read and write",
                    "Contents: Read and write",
                    "Workflows: Read and write");
        }
        final String at = api + "/repos/" + org + "/" + repo;
        // The read a founding makes first to learn who owns the organization.
        final ReachState members =
                read(get(api + "/user/memberships/orgs/" + org, authorization), true, false);
        final @Nullable Answer keys = get(at + "/keys?per_page=1", authorization);
        final @Nullable Answer commits = get(at + "/commits?per_page=1", authorization);
        if (user.scopes().isEmpty()) {
            return List.of(
                    new Reach("members", "Members: Read", members),
                    new Reach(
                            "administration",
                            "Administration: Read and write",
                            read(keys, false, false)),
                    new Reach("contents", "Contents: Read and write", read(commits, false, false)),
                    // Workflows is a write alone; no read shows it.
                    new Reach("workflows", "Workflows: Read and write", ReachState.CANNOT_TELL));
        }
        final Set<String> scopes = new LinkedHashSet<>();
        for (final String header : user.scopes()) {
            for (final String scope : header.split(",")) {
                scopes.add(scope.strip());
            }
        }
        final boolean readsMembers =
                scopes.contains("read:org")
                        || scopes.contains("write:org")
                        || scopes.contains("admin:org");
        final boolean repoScope = scopes.contains("repo");
        return List.of(
                new Reach("members", "read:org", scoped(readsMembers, members)),
                // A classic scope covers the writing too, once a read shows the organization lets
                // the token in at all.
                new Reach("administration", "repo", scoped(repoScope, read(keys, true, false))),
                new Reach("contents", "repo", scoped(repoScope, read(commits, true, false))),
                new Reach(
                        "workflows",
                        "workflow",
                        scoped(scopes.contains("workflow"), read(commits, true, false))));
    }

    private List<Reach> forgejoReach(final String token, final String org, final String repo) {
        final String authorization = "token " + token;
        final String base = apiBase();
        final Answer user = get(base + "/user", authorization);
        final @Nullable String login =
                user != null && user.status() == 200 && json(user.body()) instanceof Map<?, ?> f
                        ? field(f, "login")
                        : null;
        if (login == null || !NAME.matcher(login).matches() || login.contains("..")) {
            return untold(
                    "read:organization",
                    "write:organization",
                    "write:repository",
                    "write:repository");
        }
        // The read a founding makes to learn who owns the organization.
        final Answer owner =
                get(base + "/users/" + login + "/orgs/" + org + "/permissions", authorization);
        // Making a repository in the organization takes its write; reading it shows only the read.
        final Answer organization = get(base + "/orgs/" + org, authorization);
        final Answer repository = get(base + "/repos/" + org + "/" + repo, authorization);
        final ReachState contents = read(repository, false, true);
        return List.of(
                new Reach(
                        "members", scopeNamed(owner, "read:organization"), read(owner, true, true)),
                new Reach("administration", "write:organization", read(organization, false, true)),
                new Reach("contents", "write:repository", contents),
                new Reach("workflows", "write:repository", contents));
    }

    private static List<Reach> untold(
            final String members,
            final String administration,
            final String contents,
            final String workflows) {
        return List.of(
                new Reach("members", members, ReachState.CANNOT_TELL),
                new Reach("administration", administration, ReachState.CANNOT_TELL),
                new Reach("contents", contents, ReachState.CANNOT_TELL),
                new Reach("workflows", workflows, ReachState.CANNOT_TELL));
    }

    /** A classic scope lacking is missing; one held stands as the read shows. */
    private static ReachState scoped(final boolean held, final ReachState read) {
        return held ? read : ReachState.MISSING;
    }

    /**
     * What a read says of a permission: refused for want of it is missing; granted is the need met
     * when reading is all it asks, and otherwise leaves the writing untold. A 409 is GitHub's empty
     * repository, read.
     */
    private static ReachState read(
            final @Nullable Answer answer, final boolean readIsAll, final boolean forgejo) {
        if (answer == null) {
            return ReachState.CANNOT_TELL;
        }
        if (lacking(answer, forgejo)) {
            return ReachState.MISSING;
        }
        final boolean granted = answer.status() == 200 || answer.status() == 409;
        return granted && readIsAll ? ReachState.READY : ReachState.CANNOT_TELL;
    }

    /**
     * Whether a 403 says the token lacks the permission: not a rate limit's nor a single sign-on's,
     * and on Forgejo only one that names a scope.
     */
    private static boolean lacking(final Answer answer, final boolean forgejo) {
        if (answer.status() != 403 || answer.notThePermission()) {
            return false;
        }
        return !forgejo || new String(answer.body(), StandardCharsets.UTF_8).contains("scope");
    }

    /** The scope a Forgejo refusal names, as {@code required scope(s): [read:user]} says it. */
    private static String scopeNamed(final @Nullable Answer answer, final String otherwise) {
        if (answer == null || answer.status() != 403) {
            return otherwise;
        }
        final java.util.regex.Matcher named =
                Pattern.compile("scope\\(s\\): \\[([a-z:, ]{1,80})\\]")
                        .matcher(new String(answer.body(), StandardCharsets.UTF_8));
        return named.find() ? named.group(1).strip() : otherwise;
    }

    /** The public profile of a login. */
    public Optional<Profile> profile(final String login) {
        return profileGet(apiBase() + "/users/" + name(login, "login"), null);
    }

    /** Signing keys alone, without merging authentication keys into them. */
    public Optional<List<String>> signingKeys(final String login) {
        return keyList(
                get(apiBase() + "/users/" + name(login, "login") + "/ssh_signing_keys", null),
                true);
    }

    /**
     * Signing keys as a founding counts them: only GitHub's, since {@link #keys} asks no other
     * forge for its signing keys.
     *
     * @param login The login.
     * @return The keys, none on another forge, or empty when the forge did not say.
     */
    public Optional<List<String>> foundingSigningKeys(final String login) {
        return api == null ? Optional.of(List.of()) : signingKeys(login);
    }

    /** Authentication keys alone, as the forge's public page lists them. */
    public Optional<List<String>> authenticationKeys(final String login) {
        return keyList(get(forge + "/" + name(login, "login") + ".keys", null), false);
    }

    private String apiBase() {
        return api == null ? forge + "/api/v1" : api;
    }

    private Optional<Profile> profileGet(final String url, final @Nullable String authorization) {
        final Answer answer = get(url, authorization);
        if (answer == null
                || answer.status() != 200
                || !(json(answer.body()) instanceof Map<?, ?> fields)) {
            return Optional.empty();
        }
        return Optional.of(
                new Profile(
                        field(fields, "login"),
                        field(fields, api == null ? "full_name" : "name"),
                        field(fields, "email")));
    }

    private static @Nullable String field(final Map<?, ?> fields, final String name) {
        return fields.get(name) instanceof String value && !value.isBlank() ? value : null;
    }

    private Optional<Keys> lists(final String login, final @Nullable String wanted) {
        unanswered.remove();
        final String name = name(login, "login");
        final Answer page = get(forge + "/" + name + ".keys", null);
        if (page != null && page.status() == 404) {
            // The forge has no such account, so it lists no key at all: nothing else to ask.
            return Optional.of(new Keys(List.of(), true));
        }
        final Optional<List<String>> listed = keyList(page, false);
        if (listed.isEmpty()) {
            unanswered.set(kind(page));
        }
        if (listed.isEmpty() || api == null) {
            return listed.map(keys -> new Keys(keys, true));
        }
        final Keys onPage = new Keys(listed.get(), false);
        if (wanted != null && org.fuin.porelo.vault.PersonCard.lists(onPage.keys(), wanted)) {
            // The page vouches for the key already; the API's rate limit is not spent on it.
            return Optional.of(onPage);
        }
        final Answer signingPage = get(api + "/users/" + name + "/ssh_signing_keys", null);
        final Optional<List<String>> signing = keyList(signingPage, true);
        if (signing.isEmpty()) {
            unanswered.set(kind(signingPage));
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
        unanswered.remove();
        final String org = name(organisation, "organisation");
        final String name = name(login, "login");
        if (token == null) {
            unanswered.set(NO_TOKEN);
            return Optional.empty();
        }
        return api != null ? gitHubOwns(api, token, org, name) : forgejoOwns(token, org, name);
    }

    @Override
    public Optional<String> cannotSay() {
        return Optional.ofNullable(unanswered.get());
    }

    /** Why an answer, or its absence, says nothing: from its status and headers alone. */
    private String kind(final @Nullable Answer answer) {
        if (answer == null) {
            return Boolean.TRUE.equals(tooLong.get()) ? UNREADABLE : NO_ANSWER;
        }
        if (answer.status() == 429 || answer.status() == 403 && answer.rateLimited()) {
            return RATE_LIMITED;
        }
        return answer.status() == 200 ? UNREADABLE : ANOTHER_STATUS;
    }

    /** Says why, and that it cannot say. */
    private Optional<Boolean> cannot(final String kind) {
        unanswered.set(kind);
        return Optional.empty();
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
            return cannot(kind(null));
        }
        if (answer.status() == 404 && (seesAll || installedOn(api, authorization, org))) {
            return Optional.of(false);
        }
        if (answer.status() == 404) {
            // The token's own membership could not be read: that, not the token, is why.
            if (own == null) {
                return cannot(NO_ANSWER);
            }
            if (own.status() == 429 || own.rateLimited()) {
                return cannot(RATE_LIMITED);
            }
            return cannot(BLIND_TOKEN);
        }
        if (answer.status() != 200 || !(json(answer.body()) instanceof Map<?, ?>)) {
            return cannot(kind(answer));
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
        if (answer != null
                && (answer.status() == 404 || answer.status() == 403 && !answer.rateLimited())) {
            return cannot(BLIND_TOKEN);
        }
        if (answer == null
                || answer.status() != 200
                || !(json(answer.body()) instanceof Map<?, ?> permissions)
                || !(permissions.get("is_owner") instanceof Boolean owner)) {
            return cannot(kind(answer));
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
        tooLong.remove();
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
            final java.net.http.HttpHeaders headers = response.headers();
            final boolean rateLimited =
                    headers.firstValue("X-RateLimit-Remaining").filter("0"::equals).isPresent()
                            || headers.firstValue("Retry-After").isPresent();
            return new Answer(
                    response.statusCode(),
                    response.body(),
                    headers.allValues("X-OAuth-Scopes"),
                    // A rate limit or an organization's single sign-on refuses too, with a 403.
                    rateLimited || headers.firstValue("X-GitHub-SSO").isPresent(),
                    rateLimited);
        } catch (TimeoutException ex) {
            pending.cancel(true);
            return null;
        } catch (ExecutionException ex) {
            // The client may wrap what the body's reader gave up with.
            for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof OverTheBound) {
                    tooLong.set(Boolean.TRUE);
                }
            }
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

    /** An answer longer than {@link #LIMIT}, which is not read. */
    private static final class OverTheBound extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        OverTheBound() {
            super("the answer is over " + LIMIT + " bytes");
        }
    }

    /** A status and the whole body that came with it. */
    private record Answer(
            int status,
            byte[] body,
            List<String> scopes,
            boolean notThePermission,
            boolean rateLimited) {
        Answer(final int status, final byte[] body) {
            this(status, body, List.of(), false, false);
        }
    }

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
                    result.completeExceptionally(new OverTheBound());
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
