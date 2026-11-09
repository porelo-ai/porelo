package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.fuin.porelo.core.config.RealAccount;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * A command a test builds and runs without a context fails before it reaches the person's vault.
 *
 * <p>Picocli's factory is not asked for an instance a test passes in, so such a command keeps its
 * field's {@link PoreloContext#real()}: {@code vault put} built so reached the real vault file.
 */
class RealAccountRefusedTest {

    /**
     * Holds before anything else here runs: with the guard off, the command below is a real one.
     */
    private static void requireTheGuard() {
        assertThat(RealAccount.refused())
                .as("the build sets %s for this JVM", RealAccount.REFUSED)
                .isTrue();
        assertThatThrownBy(() -> PoreloContext.real().paths())
                .as("real() holds no path of the person's, or the commands here would use it")
                .isInstanceOf(RealAccount.Refused.class);
    }

    @Test
    void aVaultPutBuiltWithoutAContextFailsLoudlyAndKeepsNothing(@TempDir Path dir)
            throws IOException {
        requireTheGuard();
        final Path value = Files.writeString(dir.resolve("value"), "not-a-real-value");
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();

        assertThatThrownBy(
                        () ->
                                new CommandLine(new VaultPutCommand())
                                        .setOut(new PrintWriter(out))
                                        .setErr(new PrintWriter(err))
                                        .execute(
                                                "not-a-real-entry",
                                                "--from-file",
                                                value.toString()))
                .as("no exit code a test could take for the command's own refusal")
                .isInstanceOf(RealAccount.Refused.class)
                .hasMessageContaining("PoreloContext.real()");
        assertThat(out.toString()).as("no credential was said to be stored").isEmpty();
    }

    @Test
    void everyPartOfTheRealContextRefusesWhenUsed() {
        requireTheGuard();
        final PoreloContext real = PoreloContext.real();

        final List<ThrowingCallable> uses =
                List.of(
                        real::paths,
                        real::vault,
                        real::credentials,
                        real::opener,
                        real::hooks,
                        real::tasks,
                        real::podman,
                        real::agents,
                        real::providers,
                        real::credentialRegistry,
                        real::forgetTyped,
                        () -> real.recordedId("a-task"),
                        () -> real.requireOpener(),
                        () -> real.runner().run(Command.of("true")),
                        () -> real.exec().applyAsInt(List.of("true")),
                        () -> real.environment().apply("HOME"),
                        () -> real.prompt().ask("a passphrase"),
                        () -> real.input().stream().read(),
                        () -> real.machineName().get(),
                        () -> real.hostVault().isOpen(),
                        () -> real.hostVault().share(),
                        () -> real.hostVault().forget(),
                        () -> real.hostVault().keep(new byte[0]));

        for (final ThrowingCallable use : uses) {
            assertThatThrownBy(use)
                    .as("each way into the real machine refuses")
                    .isInstanceOf(RealAccount.Refused.class);
        }
    }

    @Test
    void aContextDerivedFromTheRealOneRefusesToo() {
        requireTheGuard();

        assertThatThrownBy(() -> PoreloContext.real().recognizingTasks().vault())
                .as("a wither keeps the refusal")
                .isInstanceOf(RealAccount.Refused.class);
        assertThatThrownBy(() -> PoreloContext.real().asking(VaultPrompt.NOBODY).opener())
                .as("a wither keeps the refusal")
                .isInstanceOf(RealAccount.Refused.class);
    }

    @Test
    void thePathsOfThisAccountAndAnAgentsHomeAreRefusedOnTheirOwn() {
        requireTheGuard();

        assertThatThrownBy(PoreloPaths::current)
                .as("the account's paths, asked for without a context")
                .isInstanceOf(RealAccount.Refused.class);
        assertThatThrownBy(() -> VaultImportCommand.expand("~/.an-agent"))
                .as("an agent's declared directory under the real home")
                .isInstanceOf(RealAccount.Refused.class);
    }

    @Test
    void aCommandGivenTheTestsContextStillRuns(@TempDir Path dir) throws IOException {
        requireTheGuard();
        final char[] passphrase = "a test passphrase".toCharArray();
        final Path value = Files.writeString(dir.resolve("value"), "not-a-real-value");
        final PoreloContext context =
                new PoreloContext(
                                new FakeCommandRunner(),
                                new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                                arguments -> 0)
                        .asking(prompt -> passphrase.clone())
                        .reading(new StandardInput(java.io.InputStream.nullInputStream(), true));
        context.vault().write(Map.of(), passphrase);
        final VaultPutCommand command = new VaultPutCommand();
        command.setContext(context);
        final StringWriter err = new StringWriter();
        final CommandLine cli = new CommandLine(command);
        cli.setOut(new PrintWriter(new StringWriter()));
        cli.setErr(new PrintWriter(err));

        try {
            assertThat(cli.execute("not-a-real-entry", "--from-file", value.toString()))
                    .as(err.toString())
                    .isZero();
        } finally {
            PoreloContext.forgetAsked();
        }

        assertThat(context.vault().read(passphrase))
                .as("the guard takes nothing from a command that has its context")
                .containsKey("not-a-real-entry");
    }
}
