package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.app.Mailbox;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.gate.review.Kept;
import org.fuin.porelo.gate.review.Origin;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.ReviewItem;
import org.fuin.porelo.gate.review.ReviewStore;
import org.fuin.porelo.gate.review.ReviewThread;
import org.fuin.porelo.gate.review.ThreadState;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.Sidecar;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The daemon's {@code Reject} reads the push's review from the repository its gate is for. */
class GateRejectThreadsTest {

    private static final String RECORDED = "a".repeat(64);

    @TempDir Path dir;

    @Test
    void aRejectInARepositoryNotTheProjectsOwnAsksTheReviewAboutThatRepositorysPush()
            throws Exception {
        // Asked about the project's own repository, the review found none of the work's threads.
        final Path origin = dir.resolve("backend.git");
        git(dir, "init", "-q", "--bare", "-b", "main", origin.toString());
        final Path file = dir.resolve("project.yml");
        Files.writeString(
                file,
                """
                project:
                  name: "uc"
                  security_class: "guarded"
                image:
                  from: "ubuntu:24.04"
                repositories:
                  backend:
                    upstream: "%s"
                """
                        .formatted(origin));
        Files.createDirectories(dir.resolve("data/porelo/projects"));
        Files.writeString(dir.resolve("data/porelo/projects/uc"), file + "\n");
        final PoreloContext context = context();
        final Project project = org.fuin.porelo.app.GateSupport.project(file);
        final Path mirror =
                org.fuin.porelo.app.GateSupport.gate(
                                context,
                                project,
                                java.util.Objects.requireNonNull(
                                        project.repository("backend"), "backend"),
                                null,
                                null)
                        .mirror();
        git(dir, "init", "-q", "--bare", "-b", "main", mirror.toString());
        final Path work = dir.resolve("work");
        git(dir, "init", "-q", "-b", "main", work.toString());
        git(
                work,
                "-c",
                "user.email=t@example.com",
                "-c",
                "user.name=T",
                "commit",
                "-q",
                "--allow-empty",
                "-m",
                "the work");
        final String head = git(work, "rev-parse", "HEAD");
        git(work, "push", "-q", mirror.toString(), "HEAD:refs/porelo/incoming/t");
        final String container = context.tasks().containerName(project, "t");
        record(context, container);
        new Mailbox(context.paths().messaging().mailbox(container)).create();
        final Store store = new Store();

        final Map<String, Object> reply =
                GateMethods.reject(
                        Map.of("project", "uc", "name", "t", "repository", "backend"),
                        context,
                        store,
                        (push, threads, cursor) -> {
                            throw new IllegalStateException("no open thread to write");
                        });

        assertThat(reply).containsEntry("rejected", "t").containsEntry("told", true);
        assertThat(store.asked)
                .singleElement()
                .satisfies(
                        push -> {
                            assertThat(push.repository()).isEqualTo("backend");
                            assertThat(push.head()).isEqualTo(head);
                            assertThat(push.task()).isEqualTo(RECORDED);
                        });
        assertThat(git(mirror, "for-each-ref", "refs/porelo/incoming/"))
                .as("the work was discarded")
                .doesNotContain("incoming/t");
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
        // Git for real, so the gate reads a real mirror; nothing else runs, podman least of all.
        final FakeCommandRunner others = new FakeCommandRunner();
        final ProcessCommandRunner real = new ProcessCommandRunner();
        return new PoreloContext(
                command ->
                        "git".equals(command.describe().split(" ", 2)[0])
                                ? real.run(command)
                                : others.run(command),
                new PoreloPaths(xdg, dir.resolve("bin")),
                arguments -> 0);
    }

    private static void record(final PoreloContext context, final String container)
            throws Exception {
        final Path state = context.paths().tasks().taskRecord(container);
        Sidecar.recordId(state, RECORDED);
        Files.setPosixFilePermissions(
                state, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        new Sidecar(
                        Sidecar.VERSION,
                        "uc",
                        "guarded",
                        "ruleset.nft",
                        "dns.conf",
                        "porelo",
                        context.paths().tasks().containerState(container).toString(),
                        "t",
                        null,
                        null,
                        org.fuin.porelo.wire.LimitFactors.DEFAULT)
                .writeTo(state.resolve("sidecar.json"));
    }

    private static String git(final Path in, final String... arguments) {
        final List<String> command = new ArrayList<>(List.of("git", "-C", in.toString()));
        command.addAll(List.of(arguments));
        final CommandResult result = new ProcessCommandRunner().run(Command.of(command));
        assertThat(result.successful())
                .as(String.join(" ", command) + ": " + result.standardError())
                .isTrue();
        return result.standardOutput().strip();
    }

    /** Holds no thread and records each push it was asked about. */
    private static final class Store implements ReviewStore {

        private final List<Push> asked = new ArrayList<>();

        @Override
        public Kept keep(
                final Push push,
                final ReviewItem item,
                final Origin origin,
                final @Nullable String id) {
            throw new UnsupportedOperationException("a reject keeps nothing");
        }

        @Override
        public List<ReviewThread> threads(final Push push) {
            asked.add(push);
            return List.of();
        }

        @Override
        public ReviewThread state(final Push push, final String thread, final ThreadState state) {
            throw new UnsupportedOperationException("a reject changes no thread");
        }
    }
}
