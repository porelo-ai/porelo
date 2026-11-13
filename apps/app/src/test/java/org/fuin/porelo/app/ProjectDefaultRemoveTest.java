package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** {@code porelo project default remove} keeps a repository a task of {@code default} works on. */
class ProjectDefaultRemoveTest {

    @TempDir Path dir;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private PoreloContext context;

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    @BeforeEach
    void machine() throws Exception {
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
        context = new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), args -> 0);
        new DefaultProject(context).add("https://forge.example/acme/shop.git", "shop", "");
    }

    private int remove(final String name) {
        final ProjectDefaultCommand command = new ProjectDefaultCommand();
        command.setContext(context);
        return new CommandLine(command)
                .setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute("remove", name);
    }

    private java.util.List<String> names() {
        return new DefaultProject(context)
                .entries().stream().map(DefaultProject.Entry::name).toList();
    }

    @Test
    void aRepositoryATaskOfDefaultWorksOnIsKeptNamingTheTask() {
        runner.answering(
                "ps",
                "porelo-default-shop\tUp 4 minutes\t1700000000\t0\tdefault\tguarded\tshop\t\n");

        final int code = remove("shop");

        assertThat(code).as("refused").isNotZero();
        assertThat(err.toString())
                .as("the refusal names the repository and the task working on it")
                .contains("'shop'")
                .contains("porelo-default-shop");
        assertThat(out.toString()).as("nothing is said removed").doesNotContain("removed");
        assertThat(names()).as("the repository stays in 'default'").containsExactly("shop");
    }

    @Test
    void onceNoTaskWorksOnItTheRepositoryIsRemovedAsBefore() {
        runner.answering("ps", "");

        assertThat(remove("shop")).isZero();
        assertThat(out.toString()).contains("removed  shop from 'default'");
        assertThat(names()).isEmpty();
    }

    @Test
    void aNameDefaultDoesNotHaveIsRefusedAsBefore() {
        runner.answering("ps", "");

        assertThat(remove("notes")).isEqualTo(70);
        assertThat(err.toString()).contains("'default' has no repository called 'notes'");
    }
}
