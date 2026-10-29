package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for {@link ProjectFollowingCommand}'s answer for a program.
 *
 * <p>A playbook follows a project only where its URL, key or verification differs from what this
 * account follows, so the names of the JSON answer are pinned here.
 */
class ProjectFollowingCommandTest {

    private final StringWriter out = new StringWriter();

    private PoreloContext context(Path dir) {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(
                new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private int execute(PoreloContext context, String... args) {
        final CommandLine cmd = PoreloCli.commandLine(context);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(new StringWriter()));
        return cmd.execute(args);
    }

    @Test
    void anAccountFollowingNothingAnswersAnEmptyList(@TempDir Path dir) {

        assertThat(execute(context(dir), "project", "following", "--json"))
                .as("exit code")
                .isEqualTo(0);
        assertThat(out.toString().strip()).as("one JSON object").isEqualTo("{\"follows\":[]}");
    }

    @Test
    void eachFollowCarriesTheNamesAPlaybookCompares(@TempDir Path dir) throws Exception {

        final PoreloContext context = context(dir);
        final FollowedProjects followed =
                new FollowedProjects(context.paths().projects().followed());
        followed.follow("porelo", "git@github.com:porelo-ai/porelo-project.git", true);

        assertThat(execute(context, "project", "following", "--json")).as("exit code").isEqualTo(0);
        assertThat(out.toString().strip())
                .as("one JSON object")
                .isEqualTo(
                        "{\"follows\":[{\"name\":\"porelo\","
                                + "\"url\":\"git@github.com:porelo-ai/porelo-project.git\","
                                + "\"commit\":\"\",\"outcome\":\"\",\"verified\":false,"
                                + "\"signed_by\":[],\"detail\":\"\",\"refused\":\"\","
                                + "\"access_commit\":\"\",\"access_outcome\":\"\"}]}");
    }
}
