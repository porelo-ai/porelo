package org.fuin.porelo.app;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.Repository;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.LimitFactors;
import org.fuin.porelo.wire.RootOwnedFile;

/** Reads a person's host forge reviews and confirms the end of previously observed reviews. */
public final class HostPullRequestReader {
    private static final int MAXIMUM_RESPONSE = 1024 * 1024;
    private static final int MAXIMUM_PULLS = 500;
    private final PoreloContext context;
    private final Path conf;
    private final RootOwnedFile.Attributes attributes;
    private final Exchange exchange;

    @FunctionalInterface
    interface Exchange {
        Answer get(URI address, String token);
    }

    /** What the forge answered: its status and its body, which the reader judges. */
    record Answer(int status, byte[] body) {}

    /**
     * A repository whose reviews could not be read this round, while the others' were.
     *
     * @param repository The repository's name in the project.
     * @param reason The machine-readable cause.
     * @param status The forge's status, or {@code null} when the forge was not asked.
     * @param path The path asked, without the host's address or the query, or {@code null}.
     * @param message Porelo's words: status, path and what to do, never the forge's.
     */
    public record Refusal(
            String repository,
            String reason,
            @org.jspecify.annotations.Nullable Integer status,
            @org.jspecify.annotations.Nullable String path,
            String message) {
        /** The fields sent over Tasks1. */
        public Map<String, Object> asMap() {
            final Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("repository", repository);
            fields.put("reason", reason);
            if (status != null) fields.put("status", status.longValue());
            if (path != null) fields.put("path", path);
            fields.put("message", message);
            return fields;
        }
    }

    /** A bounded observation of a review, with makers read from its commit. */
    public record Pull(
            String repository,
            long number,
            String title,
            String head,
            String author,
            String committer,
            List<String> trailers,
            String state) {
        /** Keeps immutable maker data for a polling round. */
        public Pull {
            trailers = List.copyOf(trailers);
        }

        /** The fields sent over Tasks1. */
        public Map<String, Object> asMap() {
            return Map.of(
                    "repository",
                    repository,
                    "number",
                    number,
                    "title",
                    title,
                    "head",
                    head,
                    "author",
                    author,
                    "committer",
                    committer,
                    "trailers",
                    trailers,
                    "state",
                    state);
        }
    }

    /**
     * A current open list and confirmed terminal events for one polling round, with the
     * repositories refused this round and the earlier rows of those repositories, kept for the next
     * round since a refusal never ends a review.
     */
    public record Round(List<Pull> open, List<Pull> ended, List<Refusal> refused, List<Pull> kept) {
        /** Keeps immutable snapshots rather than a live watch's collections. */
        public Round {
            open = List.copyOf(open);
            ended = List.copyOf(ended);
            refused = List.copyOf(refused);
            kept = List.copyOf(kept);
        }

        /** A round in which every repository answered. */
        public Round(List<Pull> open, List<Pull> ended) {
            this(open, ended, List.of(), List.of());
        }

        /** A bounded Tasks1 reply, fitting below the wire's four MiB message limit. */
        public Map<String, Object> asMap() {
            final Map<String, Object> reply = new LinkedHashMap<>();
            reply.put("pullRequests", open.stream().map(Pull::asMap).toList());
            reply.put("ended", ended.stream().map(Pull::asMap).toList());
            // Absent when every repository answered, so a reply keeps its earlier shape.
            if (!refused.isEmpty())
                reply.put("refused", refused.stream().map(Refusal::asMap).toList());
            if (Json.write(reply).getBytes(StandardCharsets.UTF_8).length > MAXIMUM_RESPONSE) {
                throw new Refused("bounds", "the review reply exceeds its size bound");
            }
            return reply;
        }
    }

    /** No usable person token was stored; the caller can offer the token command. */
    public static final class MissingToken extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String credential;

        /** Names the missing entry, never its value. */
        public MissingToken(String credential) {
            super("no usable person token in " + credential + "; run porelo host forge-token");
            this.credential = credential;
        }

        /** The exact entry the token command writes. */
        public String credential() {
            return credential;
        }
    }

    /** A safe, named refusal; no forge or process error text reaches this exception. */
    public static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String reason;

        Refused(String reason, String message) {
            super(message);
            this.reason = reason;
        }

        /** The machine-readable reason for refusing the observation. */
        public String reason() {
            return reason;
        }
    }

    /** One repository's error answer; the round goes on with the others. */
    private static final class RepositoryRefused extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String reason;
        private final int status;
        private final String path;

        RepositoryRefused(String reason, int status, String path, String message) {
            super(message, null, false, false);
            this.reason = reason;
            this.status = status;
            this.path = path;
        }

        Refusal of(String repository) {
            return new Refusal(repository, reason, status, path, String.valueOf(getMessage()));
        }
    }

    /** Uses the host's checked configuration and a direct loopback-only HTTP client. */
    public HostPullRequestReader(PoreloContext context) {
        this(
                context,
                context.paths().hostDirectory().resolve("forge.conf"),
                HostCentral.ATTRIBUTES,
                (address, token) -> http(address, token, context.limits()));
    }

    HostPullRequestReader(
            PoreloContext context,
            Path conf,
            RootOwnedFile.Attributes attributes,
            Exchange exchange) {
        this.context = context;
        this.conf = conf;
        this.attributes = attributes;
        this.exchange = exchange;
    }

    HostPullRequestReader(PoreloContext context, Path conf, RootOwnedFile.Attributes attributes) {
        this(context, conf, attributes, (address, token) -> http(address, token, context.limits()));
    }

    /** Reads a named project's open reviews, including one not yet placed by a start. */
    public List<Pull> open(String project) {
        return poll(project, List.of()).open();
    }

    /**
     * Says whether a read must take the project first: one no start placed here, which the host
     * carries for this person now. Any other name is left to the read's own refusal, so a read
     * never fetches a project the host does not carry for this person.
     *
     * @param project The project's name.
     * @return Whether to take it before reading.
     * @throws Refused {@code not-carried} when the host withholds it or its records do not read.
     */
    public boolean takesFirst(String project) {
        if (!project.matches(Project.NAME_PATTERN)
                || ProjectSource.resolve(context, project).file() != null) {
            return false;
        }
        final OrganizationProjects.Placed carried;
        try {
            carried =
                    OrganizationProjects.of(context).host(System.getProperty("user.name"), project);
        } catch (IOException ex) {
            throw new Refused(
                    "not-carried",
                    "this person's list of carried projects or the host's record of this project does not read; repair it before reading reviews");
        }
        if (carried == null || !carried.project().equals(project)) {
            return false;
        }
        status(project);
        return true;
    }

    /** Resolves a file first, then the host's record for this person, on every polling round. */
    public Round poll(String project, List<Pull> previous) {
        if (project.matches(Project.NAME_PATTERN)
                && ProjectSource.resolve(context, project).file() == null) {
            try {
                final OrganizationProjects.Placed carried =
                        OrganizationProjects.of(context)
                                .host(System.getProperty("user.name"), project);
                if (carried != null && carried.project().equals(project)) {
                    status(project);
                    throw new Refused(
                            "not-carried",
                            "the carried project's verified file is not placed; take the project before reading reviews");
                }
            } catch (IOException ex) {
                throw new Refused(
                        "not-carried",
                        "this person's list of carried projects or the host's record of this project does not read; repair it before reading reviews");
            }
        }
        // Keep the normal file checks and the refusal naming this person's known projects.
        return poll(GateSupport.byName(context, project), previous);
    }

    /** Reads open reviews, fetching missing heads into the reader's own store. */
    public List<Pull> open(Project project) {
        return poll(project, List.of()).open();
    }

    /** Confirms the state of reviews absent from a subsequent open list. */
    public Round poll(Project project, List<Pull> previous) {
        return poll(project.name(), previous, settings -> repositories(project, settings));
    }

    private Round poll(
            String project,
            List<Pull> previous,
            java.util.function.Function<HostForgeSettings, Map<String, URI>> sources) {
        final HostForgeSettings read = HostForgeSettings.read(conf, attributes);
        if (HostForgeSettings.refusal(read, conf, true) != null) {
            throw new Refused(
                    "settings",
                    "repair the host's root-owned forge settings before reading reviews");
        }
        final HostForgeSettings settings =
                java.util.Objects.requireNonNull(read, "absent forge settings are refused");
        final var entries =
                context.readableCredentials()
                        .orElseThrow(
                                () ->
                                        new Refused(
                                                "vault-unreadable",
                                                "the vault is locked or unreadable; unlock or repair it before reading reviews"));
        final var entry = entries.get(settings.personEntry());
        if (entry == null || !entry.value().matches("[0-9a-f]{40}"))
            throw new MissingToken(settings.personEntry());
        final String token = entry.value();
        final Map<String, URI> repositories = sources.apply(settings);
        final Map<String, Pull> current = new LinkedHashMap<>();
        final Map<String, Refusal> refused = new LinkedHashMap<>();
        for (var repository : repositories.entrySet()) {
            final Map<String, Pull> rows = new LinkedHashMap<>();
            try {
                read(project, repository.getKey(), repository.getValue(), token, rows, current);
            } catch (RepositoryRefused ex) {
                refused.put(repository.getKey(), ex.of(repository.getKey()));
                continue;
            }
            current.putAll(rows);
        }
        final List<Pull> ended = new ArrayList<>();
        final List<Pull> kept = new ArrayList<>();
        if (previous.size() > MAXIMUM_PULLS) throw tooMany();
        for (Pull old : previous) {
            if (current.containsKey(key(old))) continue;
            if (refused.containsKey(old.repository())) {
                kept.add(old);
                continue;
            }
            final URI repository = repositories.get(old.repository());
            if (repository == null) {
                refused.put(
                        old.repository(),
                        new Refusal(
                                old.repository(),
                                "not-carried",
                                null,
                                null,
                                "the host no longer carries this repository; its reviews are kept until it is carried again"));
                kept.add(old);
                continue;
            }
            final Pull now;
            try {
                now =
                        row(
                                project,
                                old.repository(),
                                repository,
                                request(URI.create(repository + "/pulls/" + old.number()), token),
                                token);
            } catch (RepositoryRefused ex) {
                refused.put(old.repository(), ex.of(old.repository()));
                kept.add(old);
                continue;
            }
            if (now.number() != old.number()) throw malformed();
            if (now.state().equals("open")) current.put(key(now), now);
            else ended.add(now);
        }
        // Kept rows go to the next round too, so they count against the same bound.
        if (current.size() + kept.size() > MAXIMUM_PULLS) throw tooMany();
        final List<Refusal> ordered = new ArrayList<>();
        for (String name : repositories.keySet())
            if (refused.containsKey(name)) ordered.add(refused.remove(name));
        // What remains is no longer carried, in the order its rows were watched.
        ordered.addAll(refused.values());
        final Round result = new Round(List.copyOf(current.values()), ended, ordered, kept);
        result.asMap();
        return result;
    }

    /** Reads one repository's open list into rows, counting those already answered by others. */
    private void read(
            String project,
            String name,
            URI repository,
            String token,
            Map<String, Pull> rows,
            Map<String, Pull> answered) {
        for (int page = 1; page <= 100; page++) {
            final Object answer =
                    request(
                            URI.create(repository + "/pulls?state=open&limit=50&page=" + page),
                            token);
            if (!(answer instanceof List<?> pulls) || pulls.size() > 50) throw malformed();
            for (Object object : pulls) {
                final Pull pull = row(project, name, repository, object, token);
                if (!pull.state().equals("open") || rows.putIfAbsent(key(pull), pull) != null)
                    throw malformed();
                if (answered.size() + rows.size() > MAXIMUM_PULLS) throw tooMany();
            }
            if (pulls.size() < 50) break;
            if (page == 100) throw tooMany();
        }
    }

    private Map<String, URI> repositories(Project project, HostForgeSettings settings) {
        if (!project.name().matches(Project.NAME_PATTERN))
            throw new Refused("not-carried", "the host carries no such project for this person");
        final OrganizationProjects.Placed carried;
        try {
            carried =
                    OrganizationProjects.host(
                            conf.resolveSibling("organization/projects"), project.name());
        } catch (IOException ex) {
            throw new Refused(
                    "not-carried",
                    "the host's record of this project does not read; repair it before reading reviews");
        }
        if (carried == null
                || !project.name().matches(Project.NAME_PATTERN)
                || !carried.project().equals(project.name())) {
            throw new Refused("not-carried", "the host carries no such project for this person");
        }
        final HostCentral.Status status = status(project.name());
        final Map<String, URI> result = new LinkedHashMap<>();
        for (Repository repository : project.allRepositories()) {
            final boolean own = repository.name().equals(project.name());
            if (!(own ? status.permitsCarrying() : status.permitsRepository(repository.name())))
                continue;
            // The record's list is a second bound; the project's own goes with the project.
            if (!own
                    && !carried.repositories().isEmpty()
                    && !carried.repositories().contains(repository.name())) continue;
            final String central =
                    repository.name().equals(project.name())
                            ? project.upstream()
                            : repository.central();
            final String copy = HostForgeAddress.copyOf(project.name(), central);
            if (copy == null) continue;
            result.put(repository.name(), api(settings, copy));
            if (result.size() > 100) throw tooMany();
        }
        return result;
    }

    private static URI api(HostForgeSettings settings, String copy) {
        return URI.create("http://127.0.0.1:" + settings.port() + "/api/v1/repos/" + copy);
    }

    private HostCentral.Status status(String project) {
        // The host's published carry state, the same checked file the window reads: a repository
        // it does not show as carried here has no copy on the forge to ask.
        final HostCentral.Status status =
                new HostCentral(conf.resolveSibling("organization/central"), attributes)
                        .status(project);
        if ("cannot-say".equals(status.state()))
            throw new Refused(
                    "not-carried",
                    "the host's carry state of this project does not read; ask for reviews again once the host's follow has published it");
        if (!status.permitsCarrying())
            throw new Refused(
                    "not-carried",
                    "this host does not carry the project now ("
                            + status.state()
                            + (status.why() == null || status.why().isBlank()
                                    ? ""
                                    : ": " + status.why())
                            + ")");
        return status;
    }

    private Object request(URI address, String token) {
        final Answer answer = exchange.get(address, token);
        if (answer.status() / 100 != 2) {
            // The path is built from the checked copy name alone: no address, port, query or token.
            final String path = address.getPath();
            final String said = "the host forge answers " + answer.status() + " to " + path + ": ";
            switch (answer.status()) {
                case 401 ->
                        throw new Refused(
                                "forge-token",
                                said
                                        + "it refuses the person token; renew it with porelo host forge-token");
                case 403, 404 ->
                        throw new RepositoryRefused(
                                "forge-access",
                                answer.status(),
                                path,
                                said
                                        + "the repository is not there for this person - its first copy is not made yet or the person is not admitted; an administrator of the host makes it or admits them");
                default ->
                        throw new RepositoryRefused(
                                "forge",
                                answer.status(),
                                path,
                                said
                                        + (answer.status() / 100 == 5
                                                ? "the forge failed; ask for reviews again later"
                                                : "the forge does not answer a review list there; check the host's forge settings"));
            }
        }
        final byte[] bytes = answer.body();
        if (bytes.length > MAXIMUM_RESPONSE)
            throw new Refused("bounds", "the forge answer exceeds its size bound");
        try {
            final Object value = Json.parseExact(new String(bytes, StandardCharsets.UTF_8));
            if (value == null) throw malformed();
            return value;
        } catch (org.fuin.porelo.wire.JsonException ex) {
            throw malformed();
        }
    }

    private Pull row(String project, String repository, URI source, Object value, String token) {
        if (!(value instanceof Map<?, ?> fields)
                || !(fields.get("number") instanceof Number number)
                || !(number instanceof Long || number instanceof Integer)
                || number.longValue() <= 0
                || !(fields.get("title") instanceof String title)
                || !(fields.get("head") instanceof Map<?, ?> head)
                || !(head.get("sha") instanceof String commit)
                || !commit.matches("[0-9a-f]{40}|[0-9a-f]{64}")
                || commit.contains(token)
                || !(fields.get("state") instanceof String state)
                || !(fields.get("merged") instanceof Boolean merged)
                || !(state.equals("open") || state.equals("closed"))
                || state.equals("open") && merged) throw malformed();
        final String[] makers =
                reviewMakers(project, repository, source, number.longValue(), commit, token);
        final List<String> trailers =
                java.util.Arrays.stream(makers)
                        .skip(3)
                        .filter(line -> line.matches("(?i)(Assisted-by|Co-Authored-By):.*"))
                        .limit(50)
                        .map(line -> shown(line, token))
                        .toList();
        return new Pull(
                repository,
                number.longValue(),
                shown(title, token),
                commit,
                shown(makers[1], token),
                shown(makers[2], token),
                trailers,
                merged ? "merged" : state);
    }

    private String[] reviewMakers(
            String project,
            String repository,
            URI source,
            long number,
            String commit,
            String token) {
        final LimitFactors limits = context.limits();
        try {
            return makers(GateSupport.mirror(context, project, repository), commit, limits);
        } catch (Refused ex) {
            // The gate alone writes its mirror. Review reads keep missing heads in their own store.
        }
        final Path store =
                context.paths()
                        .xdg()
                        .state()
                        .resolve("reviews")
                        .resolve(project)
                        .resolve(repository + ".git");
        try {
            return FileLocks.holdingWithin(
                            store.resolveSibling(repository + ".lock"),
                            // Long enough to wait out another read's fetch of three bounded steps,
                            // which follow services, so the wait does too.
                            limits.services(Duration.ofSeconds(75)),
                            context.limits(),
                            () -> {
                                try {
                                    return makers(store, commit, limits);
                                } catch (Refused absent) {
                                    // Not fetched yet: a head kept from an earlier round is read
                                    // again, never fetched again.
                                }
                                HostReviewHeads.fetch(store, source, number, commit, token, limits);
                                return makers(store, commit, limits);
                            })
                    .orElseThrow(HostPullRequestReader::noMakers);
        } catch (IOException ex) {
            throw noMakers();
        }
    }

    /**
     * Returns how long reading a commit's makers from a mirror may take.
     *
     * @param limits The factors.
     * @return Ten seconds under {@code machines}: a local {@code git log}, as {@code MirrorTree}'s.
     */
    static Duration makersBound(final LimitFactors limits) {
        return limits.machines(Duration.ofSeconds(10));
    }

    private static String[] makers(Path mirror, String commit, LimitFactors limits) {
        final Process process;
        try {
            final ProcessBuilder builder =
                    new ProcessBuilder(
                            "git",
                            "--no-pager",
                            "--no-replace-objects",
                            "--git-dir=" + mirror,
                            "-c",
                            "log.showSignature=false",
                            "log",
                            "-1",
                            "--no-walk",
                            "--no-notes",
                            "--no-show-signature",
                            "--format=%H%n%an <%ae>%n%cn <%ce>%n%(trailers:only,unfold)",
                            commit,
                            "--");
            builder.environment().clear();
            builder.environment()
                    .putAll(
                            Map.of(
                                    "PATH",
                                    System.getenv().getOrDefault("PATH", "/usr/bin:/bin"),
                                    "LC_ALL",
                                    "C",
                                    "GIT_CONFIG_GLOBAL",
                                    "/dev/null",
                                    "GIT_CONFIG_NOSYSTEM",
                                    "1",
                                    "GIT_CONFIG_COUNT",
                                    "0",
                                    "GIT_NO_REPLACE_OBJECTS",
                                    "1"));
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
            process.getOutputStream().close();
        } catch (IOException ex) {
            throw noMakers();
        }
        // Reading and waiting share a deadline: neither a full pipe nor a stuck git may hold the
        // daemon.
        try (var readers = Executors.newVirtualThreadPerTaskExecutor()) {
            final Duration bound = makersBound(limits);
            final long deadline = System.nanoTime() + bound.toNanos();
            try {
                final var read =
                        readers.submit(() -> process.getInputStream().readNBytes(64 * 1024 + 1));
                final byte[] bytes = read.get(bound.toNanos(), TimeUnit.NANOSECONDS);
                if (bytes.length > 64 * 1024
                        || !process.waitFor(
                                Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
                        || process.exitValue() != 0) throw noMakers();
                final String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
                if (lines.length < 3 || !lines[0].equals(commit)) throw noMakers();
                return lines;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw noMakers();
            } catch (ExecutionException | TimeoutException ex) {
                throw noMakers();
            } finally {
                process.destroyForcibly();
                try {
                    process.getInputStream().close();
                } catch (IOException ex) {
                    /* The process is already stopped. */
                }
            }
        }
    }

    private static Answer http(URI address, String token, LimitFactors limits) {
        try (HttpClient client =
                HttpClient.newBuilder()
                        .proxy(HttpClient.Builder.NO_PROXY)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(limits.services(Duration.ofSeconds(10)))
                        .build()) {
            final var response =
                    client.send(
                            HttpRequest.newBuilder(address)
                                    .timeout(limits.services(Duration.ofSeconds(20)))
                                    .header("Authorization", "token " + token)
                                    .header("Accept", "application/json")
                                    .build(),
                            HttpResponse.BodyHandlers.limiting(
                                    HttpResponse.BodyHandlers.ofByteArray(), MAXIMUM_RESPONSE));
            return new Answer(response.statusCode(), response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new Refused(
                    "forge", "the host forge request was interrupted; ask for reviews again");
        } catch (IOException | IllegalArgumentException ex) {
            throw new Refused(
                    "forge",
                    "the host forge could not supply a bounded review answer; check the forge and ask again");
        }
    }

    private static String shown(String value, String token) {
        final String safe =
                value.replace(token, "<token>")
                        .replaceAll("[\\p{Cntrl}\\p{Cf}\\p{Zl}\\p{Zp}]", " ");
        return safe.codePointCount(0, safe.length()) <= 1000
                ? safe
                : safe.substring(0, safe.offsetByCodePoints(0, 1000));
    }

    private static String key(Pull pull) {
        return pull.repository() + ":" + pull.number();
    }

    private static Refused malformed() {
        return new Refused(
                "forge-answer",
                "the host forge returned an unusable review answer; repair the forge and ask again");
    }

    private static Refused tooMany() {
        return new Refused(
                "bounds",
                "the host forge returned too many reviews or repositories; narrow the project before asking again");
    }

    private static Refused noMakers() {
        return new Refused(
                "makers",
                "the mirror or bounded host forge fetch could not supply makers for the exact review head; check the forge and ask again");
    }
}
