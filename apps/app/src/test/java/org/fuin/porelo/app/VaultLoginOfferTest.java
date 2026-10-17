package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests that {@code porelo vault login} on an agent already signed in offers what it would have told a person to type:
 * the import, a login again, or nothing - at a terminal; and refuses as before without one.
 */
class VaultLoginOfferTest {

    private static final AgentLogin.Result SIGNED_IN = new AgentLogin.Result(AgentLogin.Outcome.ALREADY_SIGNED_IN, "",
            "", 0, "'claude' is already signed in on this machine. Copy what it has with 'porelo vault import claude',"
                    + " which logs in nowhere - or pass --force to authorize again, which may or may not invalidate"
                    + " the credential already here");

    private final List<String> done = new ArrayList<>();

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private int login(final Path dir, final VaultLoginCommand.Asker asker) {
        final VaultLoginCommand command = new VaultLoginCommand();
        command.setContext(new PoreloContext(new FakeCommandRunner(),
                new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")), arguments -> 0));
        command.login = (context, agent, dryRun, force, printer) -> {
            done.add("login force=" + force);
            return force ? new AgentLogin.Result(AgentLogin.Outcome.STORED, "claude", "oauth", 42, "") : SIGNED_IN;
        };
        command.importer = agent -> {
            done.add("import " + agent);
            return 0;
        };
        command.asker = asker;
        final CommandLine cmd = new CommandLine(command);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd.execute("claude");
    }

    @Test
    void atATerminalTheImportIsOfferedFirstAndRuns(@TempDir final Path dir) {
        final List<String> asked = new ArrayList<>();

        final int code = login(dir, question -> {
            asked.add(question);
            return "";
        });

        assertThat(asked).singleElement().asString().contains("already signed in").contains("[I/a/c]");
        assertThat(done).containsExactly("login force=false", "import claude");
        assertThat(code).isZero();
    }

    @Test
    void theQuestionNamesTheLetterOfEachChoiceBesideIt(@TempDir final Path dir) {
        final List<String> asked = new ArrayList<>();

        login(dir, question -> {
            asked.add(question);
            return "c";
        });

        // '[I/a/c]' at the end alone left a person to guess which letter was which (found on 2026-10-17).
        assertThat(asked).singleElement().asString().contains("(i) import what it has")
                .contains("(a) log in again").contains("(c) cancel");
    }

    @Test
    void againLogsInWithForcesMeaning(@TempDir final Path dir) {
        final int code = login(dir, question -> "a");

        assertThat(done).containsExactly("login force=false", "login force=true");
        assertThat(code).isZero();
    }

    @Test
    void cancelDoesNothing(@TempDir final Path dir) {
        final int code = login(dir, question -> "c");

        assertThat(done).containsExactly("login force=false");
        assertThat(out.toString()).contains("nothing was done");
        assertThat(code).isZero();
    }

    @Test
    void withoutATerminalItRefusesAsBeforeNamingBothCommands(@TempDir final Path dir) {
        final int code = login(dir, question -> null);

        assertThat(done).containsExactly("login force=false");
        assertThat(err.toString()).contains("porelo vault import claude").contains("--force");
        assertThat(code).isEqualTo(69);
    }
}
