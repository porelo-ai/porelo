package org.fuin.porelo.organization.gittree;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * Adds a host's read-only deploy key to the organization's repository on its forge, with the token
 * of the person setting the host up. GitHub and Forgejo answer these paths alike.
 */
public final class ForgeReadKey {

    /** A deploy key's list is read this far: a repository does not hold more. */
    private static final int LAST_PAGE = 20;

    private static final int PAGE = 100;

    /** The most an answer may hold, in bytes. */
    private static final int LIMIT = 4 * 1024 * 1024;

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private static final Pattern REPOSITORY =
            Pattern.compile(
                    "(?:git@[^:/]+:|ssh://git@[^/]+/)([A-Za-z0-9][A-Za-z0-9._-]{0,99})"
                            + "/([A-Za-z0-9][A-Za-z0-9._-]{0,99}?)(?:\\.git)?/?");

    private final String repository;

    private final String token;

    private final HttpClient client;

    /**
     * Prepares to ask a forge about one repository.
     *
     * @param api Where the forge's API is, without a trailing slash.
     * @param address The repository's ssh address.
     * @param token The token, only ever sent as a header.
     * @param client How the forge is asked; it follows no redirect, so no other host gets the
     *     token.
     * @throws IllegalArgumentException When the address is none, or the client follows redirects;
     *     the message never holds the token.
     */
    public ForgeReadKey(
            final URI api, final String address, final String token, final HttpClient client) {
        if (client.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("a token is sent by a client following no redirect");
        }
        final Matcher matcher = REPOSITORY.matcher(address);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("no repository's ssh address");
        }
        this.repository = api + "/repos/" + matcher.group(1) + "/" + matcher.group(2);
        this.token = token;
        this.client = client;
    }

    /**
     * Returns where a forge's API is.
     *
     * @param forge The forge's address.
     * @return GitHub's API for {@code github.com}, otherwise the forge's own.
     */
    public static URI api(final URI forge) {
        return "github.com".equalsIgnoreCase(forge.getHost())
                ? URI.create("https://api.github.com")
                : URI.create(forge + "/api/v1");
    }

    /**
     * Adds the key unless it is there.
     *
     * @param key The public key, its type and base64.
     * @param title What the forge lists it as.
     * @return Whether it was added: {@code false} when it was there already, read-only.
     * @throws Refused {@code not-allowed} when the forge refuses the token, or holds this key with
     *     write access; {@code cannot-say} when it cannot be reached or does not say.
     */
    public boolean add(final String key, final String title) {
        for (int page = 1; page <= LAST_PAGE; page++) {
            final List<?> rows = keys(page);
            for (final Object row : rows) {
                if (row instanceof Map<?, ?> held
                        && held.get("key") instanceof String text
                        && same(text, key)) {
                    if (Boolean.TRUE.equals(held.get("read_only"))) {
                        return false;
                    }
                    // A host's key that can write would break what a host is promised: it reads.
                    throw new Refused(
                            Refused.NOT_ALLOWED,
                            "the repository holds this key with write access; remove it there",
                            null);
                }
            }
            // A forge may cap a page below what is asked: only an empty one is the end.
            if (rows.isEmpty()) {
                final Map<String, Object> body = new LinkedHashMap<>();
                body.put("title", title);
                body.put("key", key);
                body.put("read_only", true);
                final Answer answer =
                        ask(
                                HttpRequest.newBuilder(URI.create(repository + "/keys"))
                                        .header("Content-Type", "application/json")
                                        .POST(
                                                HttpRequest.BodyPublishers.ofString(
                                                        Json.write(body))));
                if (answer.status() == 201) {
                    return true;
                }
                refusedToken(answer.status());
                // GitHub's 422 is most often this key standing on another repository.
                throw new Refused(
                        Refused.CANNOT_SAY,
                        "the forge did not take the key, answering "
                                + answer.status()
                                + "; a key can stand on one repository only",
                        null);
            }
        }
        throw new Refused(Refused.CANNOT_SAY, "the forge lists more keys than are read", null);
    }

    /**
     * Returns the repository's latest commit as the forge names it: a word for the person, no
     * state.
     *
     * @return Its name, or {@code null} when none could be read.
     */
    public @Nullable String revision() {
        try {
            final Answer answer =
                    ask(
                            HttpRequest.newBuilder(
                                            URI.create(repository + "/commits?per_page=1&limit=1"))
                                    .GET());
            if (answer.status() == 200
                    && Json.parse(answer.body()) instanceof List<?> commits
                    && !commits.isEmpty()
                    && commits.get(0) instanceof Map<?, ?> commit
                    && commit.get("sha") instanceof String sha
                    && sha.matches("[0-9a-f]{40}|[0-9a-f]{64}")) {
                return sha;
            }
            return null;
        } catch (RuntimeException ex) {
            // The key stands by now: a commit the forge will not name is no failure of adding it.
            return null;
        }
    }

    private List<?> keys(final int page) {
        final Answer answer =
                ask(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                repository
                                                        + "/keys?per_page="
                                                        + PAGE
                                                        + "&limit="
                                                        + PAGE
                                                        + "&page="
                                                        + page))
                                .GET());
        refusedToken(answer.status());
        if (answer.status() == 200) {
            try {
                if (Json.parse(answer.body()) instanceof List<?> rows) {
                    return rows;
                }
            } catch (RuntimeException ex) {
                // Falls through: what it answered says nothing.
            }
        }
        throw new Refused(Refused.CANNOT_SAY, "the forge did not list the keys", null);
    }

    /** A forge answers 404 for a repository a token may not see: both are this token's refusal. */
    private static void refusedToken(final int status) {
        if (status == 401 || status == 403 || status == 404) {
            throw new Refused(
                    Refused.NOT_ALLOWED,
                    "the forge refuses this token for the repository, answering " + status,
                    null);
        }
    }

    private static boolean same(final String held, final String key) {
        final String[] one = held.strip().split("\\s+");
        final String[] other = key.strip().split("\\s+");
        return one.length >= 2
                && other.length >= 2
                && one[0].equals(other[0])
                && one[1].equals(other[1]);
    }

    private Answer ask(final HttpRequest.Builder request) {
        final HttpRequest built;
        try {
            built =
                    request.timeout(TIMEOUT)
                            .header("Accept", "application/json")
                            .header("Authorization", "token " + token)
                            .build();
        } catch (IllegalArgumentException ex) {
            // A header the client will not send: its message could quote the token.
            throw new Refused(Refused.USAGE, "the token holds what a header cannot carry", null);
        }
        // The request's own timeout ends with the headers; this bounds the body too, in time and
        // in size, so a forge cannot hold a host's set-up or fill its memory.
        final CompletableFuture<Answer> pending =
                client.sendAsync(built, HttpResponse.BodyHandlers.ofInputStream())
                        .thenApply(ForgeReadKey::whole);
        try {
            return pending.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException ex) {
            pending.cancel(true);
            throw new Refused(Refused.CANNOT_SAY, "the forge cannot be reached", null);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            pending.cancel(true);
            throw new Refused(Refused.CANNOT_SAY, "the forge cannot be reached", null);
        }
    }

    private static Answer whole(final HttpResponse<InputStream> response) {
        try (InputStream body = response.body()) {
            final byte[] read = body.readNBytes(LIMIT + 1);
            return new Answer(
                    response.statusCode(),
                    read.length > LIMIT ? "" : new String(read, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private record Answer(int status, String body) {}
}
