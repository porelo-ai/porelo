package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * A review whose caller has gone stops: git's reading for it ends, nothing half-read is kept for
 * its pages, and the daemon's log says in one line that the caller went.
 *
 * <p>Git is answered here, except the review's first diff, which is a real program that waits: the
 * program a review would be reading from when its caller goes.
 */
@Timeout(60)
class GateReviewCallerGoneTest {

    private static final String COMMIT = "a".repeat(40);

    private static final String EMPTY_TREE = "4b825dc642cb6eb9a060e54bf8d69288fbee4904";

    @TempDir Path dir;

    /** Whether a diff waits, as the review's reading does when its caller goes. */
    private final AtomicBoolean diffWaits = new AtomicBoolean(true);

    /** How many diffs the gate asked git for. */
    private final AtomicInteger diffs = new AtomicInteger();

    /** What the daemon said in its log. */
    private final List<String> said = new CopyOnWriteArrayList<>();

    @Test
    void aReviewWhoseCallerClosesEndsItsGitReadAndKeepsNothing() throws Exception {
        callerGoes("Review", Map.of("project", "uc", "name", "t"));
    }

    @Test
    void aReviewPageWhoseCallerClosesEndsItsGitReadAndKeepsNothing() throws Exception {
        callerGoes("ReviewPage", page());
    }

    private static Map<String, Object> page() {
        return Map.of(
                "project",
                "uc",
                "name",
                "t",
                "commit",
                COMMIT,
                "part",
                "diff",
                "from",
                0,
                "against",
                "",
                "ignoreLineEndings",
                false);
    }

    private void callerGoes(final String method, final Map<String, Object> parameters)
            throws Exception {
        final Path pid = dir.resolve("diff.pid");
        final PoreloContext context = context(pid);
        final Path socket = dir.resolve("porelod.sock");
        try (VarlinkServer server = PoreloDaemon.serving(context, socket).reportingTo(said::add)) {
            final Thread serving = Thread.ofVirtual().start(server);
            try {
                try (SocketChannel raw = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
                    raw.write(
                            ByteBuffer.wrap(
                                    (Json.write(
                                                            Map.of(
                                                                    "method",
                                                                    PoreloDaemon.INTERFACE
                                                                            + "."
                                                                            + method,
                                                                    "parameters",
                                                                    parameters))
                                                    + "\0")
                                            .getBytes(StandardCharsets.UTF_8)));
                    waitFor(
                            () -> Files.exists(pid) && Files.size(pid) > 0,
                            Duration.ofSeconds(20),
                            "the review's git reading runs");
                }
                final long reading = Long.parseLong(Files.readString(pid).strip());

                waitFor(
                        () -> !ProcessHandle.of(reading).map(ProcessHandle::isAlive).orElse(false),
                        Duration.ofSeconds(2),
                        "the review's git reading ended within two seconds of its caller going");
                waitFor(() -> !said.isEmpty(), Duration.ofSeconds(2), "the daemon said so");
                assertThat(said)
                        .as("one line says the caller went, none a failure")
                        .singleElement()
                        .satisfies(
                                line ->
                                        assertThat(line)
                                                .contains(method, "caller went")
                                                .doesNotContain("failed"));

                // The next page reads git again: nothing of the reading cut short was kept.
                diffWaits.set(false);
                final int before = diffs.get();
                try (VarlinkClient client = new VarlinkClient(socket)) {
                    client.call(
                            PoreloDaemon.INTERFACE + ".ReviewPage", page(), Duration.ofSeconds(30));
                } catch (VarlinkException answered) {
                    // What the page says is not the point: whether it read git again is.
                }
                assertThat(diffs.get())
                        .as("the page read the review from git, none was kept")
                        .isGreaterThan(before);
            } finally {
                server.close();
                serving.join(Duration.ofSeconds(5));
            }
        }
    }

    @FunctionalInterface
    private interface Condition {
        boolean holds() throws Exception;
    }

    private static void waitFor(final Condition condition, final Duration within, final String what)
            throws Exception {
        final Instant until = Instant.now().plus(within);
        while (!condition.holds() && Instant.now().isBefore(until)) {
            Thread.sleep(10);
        }
        assertThat(condition.holds()).as(what).isTrue();
    }

    private PoreloContext context(final Path pid) throws Exception {
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
        final Path file = dir.resolve("project.yml");
        Files.writeString(
                file,
                "project:\n  name: \"uc\"\n  security_class: \"guarded\"\n"
                        + "image:\n  from: \"ubuntu:24.04\"\n");
        Files.createDirectories(dir.resolve("data/porelo/projects"));
        Files.writeString(dir.resolve("data/porelo/projects/uc"), file + "\n");
        final ProcessCommandRunner real = new ProcessCommandRunner(Duration.ofMinutes(1));
        final PoreloPaths paths = new PoreloPaths(xdg, dir.resolve("bin"));
        final PoreloContext context =
                new PoreloContext(command -> answer(command, real, pid), paths, arguments -> 0);
        // The gate reads only a mirror this machine holds.
        final org.fuin.porelo.core.project.Project project =
                org.fuin.porelo.app.GateSupport.byName(context, "uc");
        Files.createDirectories(
                org.fuin.porelo.app.GateSupport.gate(context, project, null)
                        .mirror()
                        .resolve("objects"));
        return context;
    }

    /** Git as one waiting push needs it, its diff a program that waits while asked to. */
    private CommandResult answer(
            final Command command, final ProcessCommandRunner real, final Path pid) {
        final List<String> arguments = command.arguments();
        if (!"git".equals(command.program())) {
            return new CommandResult(command, 0, "", "");
        }
        if (arguments.contains("diff")) {
            diffs.incrementAndGet();
            if (diffWaits.get()) {
                return real.run(
                        Command.of(
                                "sh",
                                "-c",
                                "echo $$ > \"$1\"; exec sleep 30",
                                "sh",
                                pid.toString()));
            }
            return new CommandResult(command, 0, "", "");
        }
        if (arguments.contains("for-each-ref")) {
            return new CommandResult(command, 0, "refs/porelo/incoming/t\n", "");
        }
        if (arguments.contains("log")) {
            return new CommandResult(command, 0, COMMIT + "\nthe work\n", "");
        }
        if (arguments.contains("hash-object")) {
            return new CommandResult(command, 0, EMPTY_TREE + "\n", "");
        }
        if (arguments.contains("rev-parse")) {
            return new CommandResult(command, 0, COMMIT + "\n", "");
        }
        return new CommandResult(command, 0, "", "");
    }
}
