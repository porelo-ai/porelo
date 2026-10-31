package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

/** Guards binary share input against loss, echo and accidental passphrase enrollment. */
class VaultShareStdinTest {
    @Test
    void shareInitializationAnswersOneJsonStatusAfterKeepingTheShare(@TempDir Path dir) {
        final var context = context(dir, share(), false);
        try {
            assertThat(execute(context, "vault", "init", "--share-stdin", "--json")).isZero();
            assertThat(out.toString()).isEqualTo("{\"vault\":\"open\"}" + System.lineSeparator());
            assertThat(context.hostVault().isOpen()).isTrue();
        } finally {
            context.hostVault().forget();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"terminal", "no-option", "passphrase", "mixed", "device"})
    void rekeyRefusesUnsafeInputAndOtherVaultKinds(String state, @TempDir Path dir)
            throws Exception {
        final boolean wrongKind = !state.equals("terminal") && !state.equals("no-option");
        final var context = context(dir, new byte[64], state.equals("terminal"));
        if (wrongKind) HostVaultKindTest.make(context.vault(), state, new byte[32]);
        else context.vault().write(Map.of(), new byte[32]);
        final byte[] before = Files.readAllBytes(context.vault().path());
        try {
            final String[] args =
                    state.equals("no-option")
                            ? new String[] {"vault", "rekey", "--json"}
                            : new String[] {"vault", "rekey", "--share-stdin", "--json"};
            assertThat(execute(context, args)).isEqualTo(wrongKind ? 65 : 64);
            if (wrongKind)
                assertThat(Json.parse(out.toString()))
                        .isEqualTo(Map.of("vault", "not-a-host-vault"));
            assertThat(Files.readAllBytes(context.vault().path())).isEqualTo(before);
        } finally {
            context.hostVault().forget();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rekeyUsesThePreviousShareAndPreservesEntries(boolean wrong, @TempDir Path dir)
            throws Exception {
        final byte[] old = share();
        final byte[] fresh = share();
        final byte[] input = new byte[64];
        System.arraycopy(wrong ? share() : old, 0, input, 0, 32);
        System.arraycopy(fresh, 0, input, 32, 32);
        final PoreloContext context = context(dir, input, false);
        context.vault().write(Map.of("service", VaultEntry.of("example-value")), old);
        context.hostVault().keep(old);
        final byte[] before = Files.readAllBytes(context.vault().path());
        try {
            assertThat(execute(context, "vault", "rekey", "--share-stdin", "--json"))
                    .isEqualTo(wrong ? 77 : 0);
            assertThat(Json.parse(out.toString()))
                    .isEqualTo(Map.of("vault", wrong ? "other-key" : "open"));
            if (wrong) assertThat(Files.readAllBytes(context.vault().path())).isEqualTo(before);
            else {
                assertThat(context.vault().read(fresh).get("service").value())
                        .isEqualTo("example-value");
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> context.vault().read(old))
                        .isInstanceOf(org.fuin.porelo.vault.OtherVaultKeyException.class);
                assertThat(context.vault().slots()).hasSize(1);
                assertThat(context.vault().read(context.requireOpener())).containsKey("service");
            }
        } finally {
            context.hostVault().forget();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 32, 63, 65})
    void rekeyRequiresExactlyTwoShares(int size, @TempDir Path dir) throws Exception {
        final var context = context(dir, new byte[size], false);
        final byte[] old = share();
        context.vault().write(Map.of(), old);
        final byte[] before = Files.readAllBytes(context.vault().path());
        assertThat(execute(context, "vault", "rekey", "--share-stdin", "--json")).isEqualTo(64);
        assertThat(Files.readAllBytes(context.vault().path())).isEqualTo(before);
    }

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private PoreloContext context(final Path dir, final byte[] input, final boolean terminal) {
        final XdgPaths xdg = XdgPaths.of(name -> null, dir);
        return new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(xdg, dir.resolve("bin")),
                        arguments -> 0)
                .withHostVault(new HostVaultOwner())
                .reading(new StandardInput(new ByteArrayInputStream(input), terminal));
    }

    private int execute(final PoreloContext context, final String... args) {
        final CommandLine cmd = PoreloCli.commandLine(context);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd.execute(args);
    }

    private byte[] share() {
        final byte[] share = new byte[32];
        new SecureRandom().nextBytes(share);
        share[0] = 0;
        share[1] = 10;
        share[2] = 13;
        share[3] = (byte) 0xff;
        return share;
    }

    @Test
    void anAbsentVaultHasAMachineReadableOutcome(@TempDir Path dir) {
        final PoreloContext context = context(dir, share(), false);
        assertThat(execute(context, "vault", "unlock", "--share-stdin", "--json"))
                .as("host unlock can select init without parsing a diagnostic")
                .isEqualTo(66);
        assertThat(Json.parse(out.toString()))
                .as("absence is distinct from another key")
                .isEqualTo(Map.of("vault", "absent"));
        assertThat(context.vault().exists()).as("checking absence cannot create a vault").isFalse();
    }

    @Test
    void anotherShareIsItsOwnOutcomeAndNeverClearsTheVault(@TempDir final Path dir)
            throws Exception {
        final byte[] original = share();
        final PoreloContext context = context(dir, share(), false);
        context.vault().write(Map.of("service", VaultEntry.of("example-value")), original);
        final byte[] before = Files.readAllBytes(context.vault().path());

        assertThat(execute(context, "vault", "unlock", "--share-stdin", "--json"))
                .as("a share no slot takes has a distinct exit code")
                .isEqualTo(77);
        assertThat(Json.parse(out.toString()))
                .as("machine-readable refusal")
                .isEqualTo(Map.of("vault", "other-key"));
        assertThat(Files.readAllBytes(context.vault().path()))
                .as("rejection authorizes no reset")
                .isEqualTo(before);
        assertThat(context.vault().read(original)).as("all entries remain").containsKey("service");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 31, 33, 4096})
    void refusesAnythingButExactlyThirtyTwoBytes(final int length, @TempDir final Path dir) {
        final PoreloContext context = context(dir, new byte[length], false);
        assertThat(execute(context, "vault", "init", "--share-stdin"))
                .as("the binary framing must be exact")
                .isEqualTo(64);
        assertThat(context.vault().exists()).as("bad input creates nothing").isFalse();
    }

    @Test
    void refusesATerminalBeforeReadingIt(@TempDir final Path dir) {
        final PoreloContext context = context(dir, share(), true);
        assertThat(execute(context, "vault", "init", "--share-stdin"))
                .as("a terminal would echo the share")
                .isEqualTo(64);
        assertThat(err.toString()).as("the reason is actionable").contains("terminal");
        assertThat(context.vault().exists()).as("nothing was created").isFalse();
    }

    @Test
    void refusesPassphraseSourcesBesideAShare(@TempDir final Path dir) {
        final PoreloContext context = context(dir, share(), false);
        assertThat(execute(context, "vault", "init", "--share-stdin", "--passphrase-stdin"))
                .as("only one input format can own stdin")
                .isEqualTo(64);
        assertThat(context.vault().exists()).as("no ambiguous enrollment").isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"header", "content", "truncated"})
    void damageIsNotReportedAsAnotherKey(final String damage, @TempDir final Path dir)
            throws Exception {
        final byte[] share = share();
        final PoreloContext context = context(dir, share, false);
        context.vault().write(Map.of(), share);
        final byte[] bytes = Files.readAllBytes(context.vault().path());
        if (damage.equals("truncated")) {
            Files.write(context.vault().path(), Arrays.copyOf(bytes, bytes.length - 1));
        } else {
            bytes[damage.equals("header") ? 0 : bytes.length - 1] ^= 1;
            Files.write(context.vault().path(), bytes);
        }

        assertThat(execute(context, "vault", "unlock", "--share-stdin", "--json"))
                .as("a damaged vault is an error")
                .isEqualTo(70);
        assertThat(out.toString())
                .as("no invitation to reset for another key")
                .doesNotContain("other-key");
    }

    @ParameterizedTest
    @ValueSource(strings = {"list", "passphrase", "unlock", "login"})
    void aPassphraseCommandNamesHostUnlockWithoutPrompting(
            final String command, @TempDir final Path dir) {
        final PoreloContext context =
                context(dir, new byte[0], false)
                        .asking(
                                prompt -> {
                                    throw new AssertionError(
                                            "a share-only vault must never prompt");
                                });
        context.vault().write(Map.of(), share());

        assertThat(execute(context, "vault", command))
                .as("no passphrase can open this vault")
                .isNotZero();
        assertThat(err.toString())
                .as("the person's key is needed")
                .contains("porelo host unlock <host>");
    }

    @Test
    void unlockKeepsTheBinaryShareForTheCommonOpeningPath(@TempDir final Path dir) {
        final byte[] share = share();
        final PoreloContext context = context(dir, share, false);
        context.vault().write(Map.of("service", VaultEntry.of("example-value")), share);
        try {
            assertThat(execute(context, "vault", "unlock", "--share-stdin"))
                    .as("unlock; " + err)
                    .isZero();
            assertThat(execute(context, "vault", "list"))
                    .as("later commands use the held share; " + err)
                    .isZero();
            assertThat(out.toString())
                    .as("names only")
                    .contains("service")
                    .doesNotContain("example-value");
        } finally {
            context.hostVault().forget();
        }
    }

    @Test
    void createsAVaultWhoseOnlyWayInIsTheBinaryShare(@TempDir final Path dir) {
        final byte[] share = new byte[32];
        new SecureRandom().nextBytes(share);
        final PoreloContext context = context(dir, share, false);
        try {
            assertThat(execute(context, "vault", "init", "--share-stdin"))
                    .as("init accepts binary input; " + err)
                    .isZero();
            assertThat(context.vault().read(share)).as("the supplied bytes open it").isEmpty();
            assertThat(context.vault().slots())
                    .as("there is no passphrase fallback")
                    .singleElement()
                    .matches(slot -> !slot.recovery());
        } finally {
            context.hostVault().forget();
        }
    }
}
