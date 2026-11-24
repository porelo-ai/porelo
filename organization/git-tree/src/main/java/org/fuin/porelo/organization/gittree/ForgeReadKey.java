package org.fuin.porelo.organization.gittree;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
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
 * Adds or removes a host's read-only deploy key on the organization's repository, with the person's
 * forge token. GitHub and Forgejo answer these paths alike.
 */
public final class ForgeReadKey {

    /** A deploy key's list is read this far: a repository does not hold more. */
    private static final int LAST_PAGE = 20;

    private static final int PAGE = 100;

    /** The most an answer may hold, in bytes. */
    private static final int LIMIT = 4 * 1024 * 1024;

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private static final String NAME = "[A-Za-z0-9][A-Za-z0-9._-]{0,99}";

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
     * Prepares to ask a forge about one repository named by its owner and name.
     *
     * @param api Where the forge's API is, without a trailing slash.
     * @param owner The repository's owner.
     * @param name The repository's name, without {@code .git}.
     * @param token The token, only ever sent as a header.
     * @param client How the forge is asked; it follows no redirect.
     * @return The forge's view of that repository.
     * @throws IllegalArgumentException When a name is none a forge takes, or the client follows
     *     redirects; the message never holds the token.
     */
    public static ForgeReadKey of(
            final URI api,
            final String owner,
            final String name,
            final String token,
            final HttpClient client) {
        if (!owner.matches(NAME) || !name.matches(NAME) || name.endsWith(".git")) {
            throw new IllegalArgumentException("no repository's owner and name");
        }
        return new ForgeReadKey(api, "git@forge:" + owner + "/" + name + ".git", token, client);
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
                // What a forge says of its refusal is not quoted: it could hold the token sent.
                throw new Refused(
                        Refused.CANNOT_SAY,
                        "the forge did not take the key, answering "
                                + answer.status()
                                + (answer.status() == 422
                                        ? "; it may stand on another repository or on a person's"
                                                + " account there"
                                        : ""),
                        null);
            }
        }
        throw new Refused(Refused.CANNOT_SAY, "the forge lists more keys than are read", null);
    }

    /**
     * Removes the key by the id the repository lists, ignoring its comment.
     *
     * @param key The public key, its type and base64.
     * @return Whether it was removed: {@code false} when it was not there.
     * @throws Refused {@code not-allowed} when the forge refuses the token; {@code cannot-say} when
     *     it cannot be reached or does not say.
     */
    public boolean remove(final String key) {
        for (int page = 1; page <= LAST_PAGE; page++) {
            final List<?> rows = keys(page);
            for (final Object row : rows) {
                if (!(row instanceof Map<?, ?> held) || !(held.get("key") instanceof String text)) {
                    throw new Refused(
                            Refused.CANNOT_SAY, "the forge did not name a listed key", null);
                }
                if (same(text, key)) {
                    final long id = keyId(held);
                    final Answer answer =
                            ask(
                                    HttpRequest.newBuilder(URI.create(repository + "/keys/" + id))
                                            .DELETE());
                    if (answer.status() == 204) {
                        return true;
                    }
                    refusedToken(answer.status());
                    // A forge's refusal could quote the token: only our words and its status leave.
                    throw new Refused(
                            Refused.CANNOT_SAY,
                            "the forge did not remove the key, answering " + answer.status(),
                            null);
                }
            }
            if (rows.isEmpty()) {
                return false;
            }
        }
        throw new Refused(Refused.CANNOT_SAY, "the forge lists more keys than are read", null);
    }

    /**
     * Removes a host's key when the repository holds it as this host's: under its title and
     * read-only. A key with the same public half under another title, or with write access, is
     * someone's own and is left as it is.
     *
     * @param key The public key, its type and base64.
     * @param title The title the host's key was added under.
     * @return Whether it was removed: {@code false} when the repository holds it no longer.
     * @throws Refused {@code not-allowed} when the forge refuses the token, including a repository
     *     it does not show this token, or holds the key otherwise than as this host's; {@code
     *     cannot-say} when it cannot be reached, does not say, or lists a page twice.
     */
    public boolean removeHosts(final String key, final String title) {
        @Nullable List<?> previous = null;
        for (int page = 1; page <= LAST_PAGE; page++) {
            final List<?> rows = keys(page);
            if (rows.isEmpty()) {
                return false;
            }
            // A forge that ignores the page answers the first again: the rest would go unread.
            if (rows.equals(previous)) {
                throw new Refused(
                        Refused.CANNOT_SAY, "the forge lists the same keys on two pages", null);
            }
            previous = rows;
            for (final Object row : rows) {
                if (!(row instanceof Map<?, ?> held) || !(held.get("key") instanceof String text)) {
                    throw new Refused(
                            Refused.CANNOT_SAY, "the forge did not name a listed key", null);
                }
                if (!same(text, key)) {
                    continue;
                }
                if (!title.equals(held.get("title"))
                        || !Boolean.TRUE.equals(held.get("read_only"))) {
                    throw new Refused(
                            Refused.NOT_ALLOWED,
                            "the repository holds this key under another title or with write"
                                    + " access; it is left as it is",
                            null);
                }
                final long id = keyId(held);
                final Answer answer =
                        ask(
                                HttpRequest.newBuilder(URI.create(repository + "/keys/" + id))
                                        .DELETE());
                if (answer.status() == 204) {
                    return true;
                }
                refusedToken(answer.status());
                throw new Refused(
                        Refused.CANNOT_SAY,
                        "the forge did not remove the key, answering " + answer.status(),
                        null);
            }
        }
        throw new Refused(Refused.CANNOT_SAY, "the forge lists more keys than are read", null);
    }

    private static long keyId(final Map<?, ?> held) {
        if (held.get("id") instanceof BigDecimal number) {
            try {
                final long id = number.longValueExact();
                if (id > 0) {
                    return id;
                }
            } catch (ArithmeticException ex) {
                // An inexact id could delete a different key.
            }
        }
        throw new Refused(Refused.CANNOT_SAY, "the forge did not name the key's id", null);
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
            // A commit the forge will not name is no failure of changing its read key.
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
                if (Json.parseDecimals(answer.body()) instanceof List<?> rows) {
                    return rows;
                }
            } catch (RuntimeException ex) {
                // Falls through: what it answered says nothing.
            }
        }
        throw new Refused(
                Refused.CANNOT_SAY,
                "the forge did not list the keys, answering " + answer.status(),
                null);
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
