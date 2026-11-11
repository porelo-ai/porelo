package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.core.project.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests what a task that reads its whole project is given, over two stand-in upstreams. */
class WorkspaceSetupTest {

    @TempDir Path dir;

    private final ProcessCommandRunner runner = new ProcessCommandRunner();

    /** Without the machine's own configuration, as every git a test runs. */
    private static final Map<String, String> GIT =
            Map.of("GIT_CONFIG_GLOBAL", "/dev/null", "GIT_CONFIG_NOSYSTEM", "1");

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
        final var other = new org.fuin.porelo.testing.FakeCommandRunner();
        return new PoreloContext(
                command ->
                        command.program().equals("git") || command.program().equals("tar")
                                ? runner.run(command.withEnvironment(GIT))
                                : other.run(command),
                new PoreloPaths(xdg, dir.resolve("bin")),
                args -> 0);
    }

    private String git(Path in, String... args) {
        final List<String> all =
                new ArrayList<>(
                        List.of(
                                "git",
                                "-c",
                                "user.name=T",
                                "-c",
                                "user.email=t@example.com",
                                "-C",
                                in.toString()));
        all.addAll(List.of(args));
        return runner.runOrFail(new Command(all, in, GIT, null)).standardOutput().strip();
    }

    /** An upstream holding one file on main. */
    private Path upstream(String name, String file) throws Exception {
        final Path upstream = Files.createDirectories(dir.resolve("upstreams").resolve(name));
        git(upstream, "init", "-q", "-b", "main");
        Files.writeString(upstream.resolve(file), name + "\n");
        git(upstream, "add", ".");
        git(upstream, "commit", "-q", "-m", "initial");
        return upstream;
    }

    private Project project(Path front, Path back) {
        return ProjectReader.read(
                        new StringReader(
                                """
                                project:
                                  name: shop
                                  security_class: guarded
                                image:
                                  from: ubuntu:24.04
                                """),
                        "test")
                .withRepositories(
                        List.of(
                                new Repository("front", front.toString()),
                                new Repository("back", back.toString())));
    }

    private static List<String> tree(Path root) throws Exception {
        try (Stream<Path> each = Files.walk(root)) {
            return each.map(path -> root.relativize(path).toString()).sorted().toList();
        }
    }

    @Test
    void everyRepositoryIsItsMainAndNothingATaskPushed() throws Exception {
        final Path front = upstream("front", "index.html");
        final Path back = upstream("back", "server.go");
        final PoreloContext context = context();
        final Project project = project(front, back);
        // Work a task pushed to the front's gate, waiting for review: never what a reader is given.
        final Repository frontRow = project.repository("front");
        final Path mirror =
                GateSupport.mirror(context, project, java.util.Objects.requireNonNull(frontRow));
        GateSupport.gate(context, project, frontRow, null, null).initialize();
        final Path work = dir.resolve("agent-work");
        git(dir, "clone", "-q", front.toString(), work.toString());
        Files.writeString(work.resolve("unreviewed.txt"), "not approved\n");
        git(work, "add", ".");
        git(work, "commit", "-q", "-m", "pushed by a task");
        git(work, "push", "-q", mirror.toString(), "HEAD:refs/porelo/incoming/other-task");
        // And main moved on at the upstream after the mirror was made: the reader gets that too.
        Files.writeString(front.resolve("approved.txt"), "approved\n");
        git(front, "add", ".");
        git(front, "commit", "-q", "-m", "approved since");

        final Path into = dir.resolve("state").resolve("repositories").resolve("porelo-shop-all");
        final StringWriter err = new StringWriter();
        final List<String> read =
                new WorkspaceSetup(context, "all", null)
                        .readAll(
                                project,
                                into,
                                new PrintWriter(new StringWriter()),
                                new PrintWriter(err));

        assertThat(read).as("both repositories are read").containsExactly("front", "back");
        assertThat(tree(into.resolve("front")))
                .as("the front's main as it stands at its upstream, and nothing a task pushed")
                .containsExactly("", "approved.txt", "index.html");
        assertThat(tree(into.resolve("back"))).containsExactly("", "server.go");
        assertThat(tree(into))
                .as("a reading copy, never a repository the host might run git in")
                .noneMatch(path -> path.endsWith(".git") || path.contains(".git/"))
                .noneMatch(path -> path.endsWith(".tar"));
        assertThat(err.toString())
                .as("the project's own repository has nothing yet, and is named as not read")
                .contains("repository 'shop' is not read");
        assertThat(into.resolve("shop")).doesNotExist();
    }

    @Test
    void aProjectWithNothingToReadReadsNothing() throws Exception {
        final PoreloContext context = context();
        final Project project = project(dir.resolve("missing-front"), dir.resolve("missing-back"));
        final Path into = dir.resolve("state").resolve("repositories").resolve("porelo-shop-all");
        final StringWriter err = new StringWriter();

        final List<String> read =
                new WorkspaceSetup(context, "all", null)
                        .readAll(
                                project,
                                into,
                                new PrintWriter(new StringWriter()),
                                new PrintWriter(err));

        assertThat(read).as("no repository could be read").isEmpty();
        assertThat(err.toString())
                .contains("repository 'front' is not read")
                .contains("repository 'back' is not read")
                .contains("repository 'shop' is not read");
        assertThat(tree(into)).as("nothing half written is left").containsExactly("");
    }

    @Test
    void whatAnEarlierStartOfTheSameNameLeftIsReadAnew() throws Exception {
        // The trees are kept with the account's state; one a killed task never had removed must
        // not make every repository "already exist" for the next start of its name.
        final Path front = upstream("front", "index.html");
        final Path back = upstream("back", "server.go");
        final PoreloContext context = context();
        final Project project = project(front, back);
        final Path into = dir.resolve("state").resolve("repositories").resolve("porelo-shop-all");
        Files.createDirectories(into.resolve("front"));
        Files.writeString(into.resolve("front").resolve("stale.txt"), "left behind\n");

        final List<String> read =
                new WorkspaceSetup(context, "all", null)
                        .readAll(
                                project,
                                into,
                                new PrintWriter(new StringWriter()),
                                new PrintWriter(new StringWriter()));

        assertThat(read).containsExactly("front", "back");
        assertThat(tree(into.resolve("front"))).containsExactly("", "index.html");
    }
}
