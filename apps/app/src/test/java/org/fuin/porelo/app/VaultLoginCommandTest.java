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

/** Tests that an unreachable sign-in is refused without starting a different credential path. */
class VaultLoginCommandTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void aSuccessfulLoginReportsTheUseOfTheEntryItStored(
            final boolean alreadySignedIn, @TempDir final Path dir) throws Exception {
        final char[] passphrase = "a passphrase for this test".toCharArray();
        final String value = "a-token-for-the-unit-test-run";
        final PoreloContext context =
                new PoreloContext(
                                new FakeCommandRunner(),
                                new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                                arguments -> 0)
                        .asking(prompt -> passphrase.clone());
        context.vault().write(java.util.Map.of(), passphrase);
        final Path file = dir.resolve("project.yml");
        java.nio.file.Files.writeString(
                file,
                "project:\n  name: shop\n  security_class: guarded\n"
                        + "image:\n  from: ubuntu:24.04\ncredentials:\n  alpha: search\n");
        new ProjectRegistry(context.paths().projects().projectRegistry()).remember("shop", file);
        final VaultLoginCommand command = new VaultLoginCommand();
        command.setContext(context);
        command.asker = question -> "again";
        command.login =
                (machine, agent, dryRun, force, printer) -> {
                    if (alreadySignedIn && !force) {
                        return new AgentLogin.Result(
                                AgentLogin.Outcome.ALREADY_SIGNED_IN, "", "", 0, "");
                    }
                    machine.vault()
                            .update(
                                    machine.requireOpener(),
                                    entries -> {
                                        entries.put(
                                                "alpha",
                                                new org.fuin.porelo.vault.VaultEntry(
                                                        value, "token"));
                                        return entries;
                                    });
                    return new AgentLogin.Result(
                            AgentLogin.Outcome.STORED, "alpha", "token", value.length(), "");
                };
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        final CommandLine line = new CommandLine(command);
        line.setOut(new PrintWriter(out));
        line.setErr(new PrintWriter(err));
        try {
            assertThat(line.execute("example"))
                    .as("the sign-in stores its provider's credential: %s", err)
                    .isZero();
            assertThat(context.vault().read(passphrase).get("alpha").value())
                    .as("the use is reported for the entry actually stored")
                    .isEqualTo(value);
            assertThat(out.toString())
                    .as("a needed credential must not be mistaken for an unused entry and removed")
                    .contains("used for  shop's search");
            assertThat(out.toString() + err)
                    .as("reporting a use never reports the credential")
                    .doesNotContain(value);
        } finally {
            PoreloContext.forgetAsked();
        }
    }

    @Test
    void rendersTheSshRefusalWithoutOfferingToStoreAKey(@TempDir Path dir) {
        final VaultLoginCommand command = new VaultLoginCommand();
        command.setContext(
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                        arguments -> {
                            throw new AssertionError("no credential command may run");
                        }));
        command.login =
                (context, agent, dryRun, force, printer) ->
                        new AgentLogin.Result(
                                AgentLogin.Outcome.LOOPBACK_OVER_SSH,
                                "",
                                "",
                                0,
                                "'example' needs a local browser redirect");
        command.asker =
                question -> {
                    throw new AssertionError("no alternative sign-in may be offered");
                };
        command.importer =
                agent -> {
                    throw new AssertionError("no credential may be imported");
                };
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        final CommandLine cli = new CommandLine(command);
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));

        final int code = cli.execute("example");

        assertThat(code).as("a sign-in that cannot reach the person is refused").isEqualTo(69);
        assertThat(err.toString())
                .as("the agent's refusal reaches the person")
                .contains("example", "local browser redirect");
        assertThat(out.toString() + err)
                .as("the refusal does not suggest storing a key")
                .doesNotContain("vault put", "Store its key");
    }

    @Test
    void aLoginEndedFromItsTerminalSaysSoAndEndsAsAnInterruptedCommand(@TempDir Path dir) {
        final VaultLoginCommand command = new VaultLoginCommand();
        command.setContext(
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                        arguments -> {
                            throw new AssertionError("no credential command may run");
                        }));
        command.login =
                (context, agent, dryRun, force, printer) ->
                        new AgentLogin.Result(AgentLogin.Outcome.CANCELLED, "", "", 0, "");
        command.asker =
                question -> {
                    throw new AssertionError("nothing may be offered");
                };
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        final CommandLine cli = new CommandLine(command);
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));

        final int code = cli.execute("example");

        assertThat(code).as("as an interrupted command ends").isEqualTo(130);
        assertThat(out.toString() + err)
                .contains(
                        "cancelled  the login was ended from this terminal; its container is"
                                + " removed and nothing was stored")
                .doesNotContain("vault put", "Store its key");
    }
}
