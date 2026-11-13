package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A review larger than one varlink message, through the daemon: it shows the whole diff or says
 * where it was cut, every changed file stays listed and ranked from the whole change, and what
 * cannot be listed whole is refused by name.
 */
class GateReviewCutTest {

    @TempDir Path dir;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private final ProcessCommandRunner git = new ProcessCommandRunner();

    private static final int MIB = 1024 * 1024;

    /** The largest message a varlink peer accepts, as the wire bounds it. */
    private static final int LIMIT = 4 * MIB;

    @Test
    void aPushOfManyFilesIsCutAtAFileBoundaryAndListsEveryFile() throws Exception {
        // The reply was one message over the limit: the client refused it, and the person saw no
        // review at all.
        final Push push = push();
        final List<String> paths = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            final String path = "src/file-%02d.txt".formatted(i);
            paths.add(path);
            push.write(path, ("line " + i + " of an ordinary change\n").repeat(2500));
        }
        push.commit("many files");
        push.send();

        final Map<String, Object> review = push.review(Map.of());

        assertThat(bytes(review))
                .as("the reply fits the bound a peer accepts")
                .isLessThanOrEqualTo(LIMIT);
        assertThat(pathsOf(review))
                .as("every changed file is listed")
                .containsExactlyInAnyOrderElementsOf(paths);
        final Map<?, ?> cut = (Map<?, ?>) review.get("cut");
        assertThat(cut).as("the cut is said").isNotNull();
        final String diff = String.valueOf(review.get("diff"));
        final List<String> shown = shownPaths(diff);
        assertThat(shown).as("something is shown").isNotEmpty();
        assertThat(((Number) cut.get("shownFiles")).intValue()).isEqualTo(shown.size());
        assertThat(((Number) cut.get("omittedFiles")).intValue()).isEqualTo(80 - shown.size());
        final List<String> both = new ArrayList<>(shown);
        both.addAll(strings(cut.get("omitted")));
        assertThat(both)
                .as("shown and omitted are every file's patch, each once")
                .containsExactlyInAnyOrderElementsOf(paths);
        final String whole = push.wholePatch();
        assertThat(whole).as("the shown diff is the whole patch's start").startsWith(diff);
        assertThat(whole.substring(diff.length()))
                .as("and it ends where a file ends")
                .startsWith("diff --git ");
        assertThat(strings(cut.get("command")))
                .as("the full view is a command, every name resolved")
                .containsExactly(
                        "porelo",
                        "gate",
                        "review",
                        "-p",
                        "default",
                        "-r",
                        "app",
                        "--against",
                        push.base(),
                        "--",
                        "t");
    }

    @Test
    void aPatchThatEscapesLargeIsMeasuredAsItIsSent() throws Exception {
        // Under the limit as text, several times over it as JSON: control characters go as \\u0001.
        final Push push = push();
        for (int i = 0; i < 6; i++) {
            push.write(
                    "escaped-%d.txt".formatted(i), ("\u0001\"\\".repeat(40) + "\n").repeat(3000));
        }
        push.commit("escapes");
        push.send();
        assertThat(push.wholePatch().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .as("the raw patch alone would fit")
                .isLessThan(LIMIT);

        final Map<String, Object> review = push.review(Map.of());

        assertThat(bytes(review)).isLessThanOrEqualTo(LIMIT);
        final Map<?, ?> cut = (Map<?, ?>) review.get("cut");
        assertThat(cut).as("the escaped size is what is cut by").isNotNull();
        assertThat(shownPaths(String.valueOf(review.get("diff"))))
                .hasSize(((Number) cut.get("shownFiles")).intValue());
        assertThat(pathsOf(review)).hasSize(6);
    }

    @Test
    void oneDangerousFileLargerThanTheLimitIsListedRankedAndCutWhole() throws Exception {
        // A lockfile is the finding however large; leaving it out of the diff must not leave it out
        // of the review or lower what it is.
        final Push push = push();
        push.write(
                "package-lock.json",
                "{\"dependency\": \"some-package-1.0.0\"}\n".repeat(5 * MIB / 38));
        push.write("README.md", "app, changed\n");
        push.commit("a large lockfile");
        push.send();

        final Map<String, Object> review = push.review(Map.of("ignoreLineEndings", true));

        assertThat(bytes(review)).isLessThanOrEqualTo(LIMIT);
        final Map<?, ?> lock =
                files(review).stream()
                        .filter(file -> "package-lock.json".equals(file.get("path")))
                        .findFirst()
                        .orElseThrow();
        assertThat(lock.get("rank")).as("ranked from the whole change").isEqualTo("DANGEROUS");
        assertThat(String.valueOf(lock.get("reason"))).contains("dependency");
        final Map<?, ?> cut = (Map<?, ?>) review.get("cut");
        assertThat(((Number) cut.get("shownFiles")).intValue())
                .as("the first file does not fit, so none is shown: never part of one")
                .isZero();
        assertThat(strings(cut.get("omitted"))).containsExactly("package-lock.json", "README.md");
        assertThat(String.valueOf(review.get("diff"))).doesNotContain("diff --git");
        assertThat(strings(cut.get("command")))
                .as("the full view keeps the line-ending switch the review was asked with")
                .containsSubsequence("--ignore-line-endings", "--", "t");
    }

    @Test
    void aReviewWhoseListAloneDoesNotFitIsRefusedByName() throws Exception {
        // Commits whose messages alone are over the limit: a cut patch would still not fit, and a
        // shortened list would read as the whole one.
        final Push push = push();
        final String body = "\"".repeat(16_000);
        for (int i = 0; i < 140; i++) {
            push.write("commit-%03d.txt".formatted(i), i + "\n");
            push.commit("commit " + i + "\n\n" + body);
        }
        push.send();

        assertThatThrownBy(() -> push.review(Map.of()))
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex -> {
                            assertThat(ex.getErrorName())
                                    .isEqualTo(PoreloDaemon.INTERFACE + ".ReviewTooLarge");
                            assertThat(ex.getParameters())
                                    .containsEntry("name", "t")
                                    .containsEntry("limit", (long) LIMIT);
                            assertThat(((Number) ex.getParameters().get("bytes")).longValue())
                                    .isGreaterThan(LIMIT);
                            assertThat(strings(ex.getParameters().get("command")))
                                    .startsWith("porelo", "gate", "review")
                                    .endsWith("--", "t");
                        });
    }

    @Test
    void aReviewThatFitsIsWholeAndSaysNoCut() throws Exception {
        final Push push = push();
        push.write("small.txt", "small\n");
        push.commit("small");
        push.send();

        final Map<String, Object> review = push.review(Map.of());

        assertThat(review).doesNotContainKey("cut");
        assertThat(String.valueOf(review.get("diff"))).isEqualTo(push.wholePatch());
    }

    // ------------------------------------------------------------------------------- fixture

    /** A repository with a gate, and work on its way to a pending push named {@code t}. */
    private final class Push {

        private final PoreloContext context;

        private final org.fuin.porelo.gate.GitGate gate;

        private final Path work;

        Push(PoreloContext context, org.fuin.porelo.gate.GitGate gate, Path work) {
            this.context = context;
            this.gate = gate;
            this.work = work;
        }

        void write(String path, String content) throws Exception {
            final Path file = work.resolve(path);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
        }

        void commit(String message) throws Exception {
            final Path text = dir.resolve("message.txt");
            Files.writeString(text, message);
            in(work, "add", ".");
            in(work, "commit", "-q", "--cleanup=verbatim", "-F", text.toString());
        }

        void send() {
            in(work, "push", "-q", "-f", gate.mirror().toString(), "HEAD:refs/porelo/incoming/t");
        }

        String base() {
            return org.fuin.porelo.app.TaskBase.reviewAgainst(
                    context, org.fuin.porelo.app.GateSupport.byName(context, "default"), "t", gate);
        }

        String wholePatch() {
            return gate.rankedReview("t", base()).patch();
        }

        Map<String, Object> review(Map<String, Object> more) throws Exception {
            final Map<String, Object> parameters =
                    new java.util.LinkedHashMap<>(
                            Map.of("project", "default", "repository", "app", "name", "t"));
            parameters.putAll(more);
            final Path socket = dir.resolve("porelod.sock");
            try (VarlinkServer server = PoreloDaemon.serving(context, socket)) {
                final Thread thread = Thread.ofVirtual().start(server);
                try (VarlinkClient client = new VarlinkClient(socket)) {
                    return client.call(
                            PoreloDaemon.INTERFACE + ".Review",
                            parameters,
                            java.time.Duration.ofMinutes(2));
                } finally {
                    server.close();
                    thread.join(java.time.Duration.ofSeconds(5));
                }
            }
        }
    }

    private Push push() throws Exception {
        final PoreloContext context = context();
        final Path checkout = Files.createDirectories(dir.resolve("app"));
        in(checkout, "init", "-q", "-b", "main");
        Files.writeString(checkout.resolve("README.md"), "app\n");
        in(checkout, "add", ".");
        in(checkout, "commit", "-q", "-m", "start");
        new org.fuin.porelo.app.DefaultProject(context)
                .add(checkout.toString(), "app", checkout.toString(), "");
        final org.fuin.porelo.core.project.Project project =
                org.fuin.porelo.app.GateSupport.byName(context, "default");
        final org.fuin.porelo.gate.GitGate gate =
                org.fuin.porelo.app.GateSupport.gate(
                        context, project, project.repository("app"), null, null);
        gate.initialize();
        final Path work = dir.resolve("work");
        git.runOrFail(Command.of("git", "clone", "-q", gate.mirror().toString(), work.toString()));
        return new Push(context, gate, work);
    }

    private void in(Path where, String... arguments) {
        final List<String> all =
                new ArrayList<>(
                        List.of(
                                "git",
                                "-C",
                                where.toString(),
                                "-c",
                                "user.email=t@example.com",
                                "-c",
                                "user.name=T",
                                "-c",
                                "core.autocrlf=false"));
        all.addAll(List.of(arguments));
        git.runOrFail(
                new Command(
                        all,
                        null,
                        Map.of("GIT_CONFIG_GLOBAL", "/dev/null", "GIT_CONFIG_NOSYSTEM", "1"),
                        null));
    }

    private PoreloContext context() {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        final PoreloPaths paths = new PoreloPaths(xdg, dir.resolve("bin"));
        return new PoreloContext(routed(paths), paths, arguments -> 0);
    }

    /** The fake runner, except for the gate's own Git, which reaches its real mirror. */
    private CommandRunner routed(PoreloPaths paths) {
        final String mirrors = paths.xdg().data().resolve("mirrors") + "/";
        return command ->
                command.program().equals("git")
                                && command.arguments().contains("credential.helper=")
                                && command.arguments().stream()
                                        .anyMatch(argument -> argument.startsWith(mirrors))
                        ? git.run(command)
                        : runner.run(command);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> files(Map<String, Object> review) {
        return (List<Map<?, ?>>) review.get("files");
    }

    private static List<String> pathsOf(Map<String, Object> review) {
        return files(review).stream().map(file -> String.valueOf(file.get("path"))).toList();
    }

    private static List<String> strings(Object list) {
        return ((List<?>) list).stream().map(String::valueOf).toList();
    }

    /** The paths whose patch a diff holds, by their headers: the fixture's paths are plain. */
    private static List<String> shownPaths(String diff) {
        return diff.lines()
                .filter(line -> line.startsWith("diff --git a/"))
                .map(line -> line.substring(line.lastIndexOf(" b/") + 3))
                .toList();
    }

    /** What a reply takes on the wire, as the daemon frames it, without the NUL after it. */
    private static int bytes(Map<String, Object> reply) {
        return org.fuin.porelo.wire.Json.write(Map.of("parameters", reply))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                .length;
    }
}
