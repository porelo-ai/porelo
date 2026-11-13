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
        byte[] get(URI address, String token);
    }

    /** A bounded observation of a review, with makers read from the local mirror. */
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

    /** A current open list and confirmed terminal events for one polling round. */
    public record Round(List<Pull> open, List<Pull> ended) {
        /** Keeps immutable snapshots rather than a live watch's collections. */
        public Round {
            open = List.copyOf(open);
            ended = List.copyOf(ended);
        }

        /** A bounded Tasks1 reply, fitting below the wire's four MiB message limit. */
        public Map<String, Object> asMap() {
            final Map<String, Object> reply =
                    Map.of(
                            "pullRequests",
                            open.stream().map(Pull::asMap).toList(),
                            "ended",
                            ended.stream().map(Pull::asMap).toList());
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

    /** Uses the host's checked configuration and a direct loopback-only HTTP client. */
    public HostPullRequestReader(PoreloContext context) {
        this(context, HostForgeSettings.FILE, HostCentral.ATTRIBUTES, HostPullRequestReader::http);
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
        this(context, conf, attributes, HostPullRequestReader::http);
    }

    /** Reads open reviews without minting a token or fetching a repository. */
    public List<Pull> open(Project project) {
        return poll(project, List.of()).open();
    }

    /** Confirms the state of reviews absent from a subsequent open list. */
    public Round poll(Project project, List<Pull> previous) {
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
        final Map<String, URI> repositories = repositories(project, settings);
        final Map<String, Pull> current = new LinkedHashMap<>();
        for (var repository : repositories.entrySet()) {
            for (int page = 1; page <= 100; page++) {
                final Object answer =
                        request(
                                URI.create(
                                        repository.getValue()
                                                + "/pulls?state=open&limit=50&page="
                                                + page),
                                token);
                if (!(answer instanceof List<?> pulls) || pulls.size() > 50) throw malformed();
                for (Object object : pulls) {
                    final Pull pull = row(project, repository.getKey(), object, token);
                    if (!pull.state().equals("open")
                            || current.putIfAbsent(key(pull), pull) != null) throw malformed();
                    if (current.size() > MAXIMUM_PULLS) throw tooMany();
                }
                if (pulls.size() < 50) break;
                if (page == 100) throw tooMany();
            }
        }
        final List<Pull> ended = new ArrayList<>();
        if (previous.size() > MAXIMUM_PULLS) throw tooMany();
        for (Pull old : previous) {
            if (current.containsKey(key(old))) continue;
            final URI repository = repositories.get(old.repository());
            if (repository == null)
                throw new Refused(
                        "not-carried",
                        "the host no longer carries a watched repository; ask for reviews again");
            final Pull now =
                    row(
                            project,
                            old.repository(),
                            request(URI.create(repository + "/pulls/" + old.number()), token),
                            token);
            if (now.number() != old.number()) throw malformed();
            if (now.state().equals("open")) current.put(key(now), now);
            else ended.add(now);
        }
        if (current.size() > MAXIMUM_PULLS) throw tooMany();
        final Round result = new Round(List.copyOf(current.values()), ended);
        result.asMap();
        return result;
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
        final Map<String, URI> result = new LinkedHashMap<>();
        for (Repository repository : project.allRepositories()) {
            if (!carried.repositories().isEmpty()
                    && !carried.repositories().contains(repository.name())) continue;
            final String central =
                    repository.name().equals(project.name())
                            ? project.upstream()
                            : repository.central();
            final String copy = HostForgeAddress.copyOf(project.name(), central);
            if (copy == null) continue;
            result.put(
                    repository.name(),
                    URI.create("http://127.0.0.1:" + settings.port() + "/api/v1/repos/" + copy));
            if (result.size() > 100) throw tooMany();
        }
        return result;
    }

    private Object request(URI address, String token) {
        final byte[] bytes = exchange.get(address, token);
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

    private Pull row(Project project, String repository, Object value, String token) {
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
                makers(GateSupport.mirror(context, project.name(), repository), commit);
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

    private static String[] makers(Path mirror, String commit) {
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
            builder.environment()
                    .putAll(
                            Map.of(
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
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            try {
                final var read =
                        readers.submit(() -> process.getInputStream().readNBytes(64 * 1024 + 1));
                final byte[] bytes = read.get(10, TimeUnit.SECONDS);
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

    private static byte[] http(URI address, String token) {
        try (HttpClient client =
                HttpClient.newBuilder()
                        .proxy(HttpClient.Builder.NO_PROXY)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofSeconds(10))
                        .build()) {
            final var response =
                    client.send(
                            HttpRequest.newBuilder(address)
                                    .timeout(Duration.ofSeconds(20))
                                    .header("Authorization", "token " + token)
                                    .header("Accept", "application/json")
                                    .build(),
                            HttpResponse.BodyHandlers.limiting(
                                    HttpResponse.BodyHandlers.ofByteArray(), MAXIMUM_RESPONSE));
            if (response.statusCode() / 100 != 2)
                throw new Refused(
                        "forge",
                        "the host forge refused the review request; check access and renew the person token with porelo host forge-token");
            return response.body();
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
                "the mirror could not supply bounded makers for the review head; refresh the mirror before asking again");
    }
}
