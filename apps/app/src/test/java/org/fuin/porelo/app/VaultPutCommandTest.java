package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import org.junit.jupiter.api.Test;

/**
 * Tests for reading a credential in.
 *
 * <p>The interesting property is not what the value comes back as - it is <em>which stream was
 * touched</em>. Reading a typed secret through the echoing path put it on the screen and into the
 * terminal's scrollback, and no assertion on the returned value could tell the two apart. So each
 * test makes the wrong source throw.
 */
class VaultPutCommandTest {

    /** Fails the test if anything reads it. */
    private static BufferedReader forbidden() {
        return new BufferedReader(new StringReader("")) {
            @Override
            public String readLine() throws IOException {
                throw new IOException("standard input was read for a secret somebody typed");
            }
        };
    }

    @Test
    void leavesStandardInputOpenForThePassphrasePromptThatFollows() throws IOException {

        // Reported on the VM: reading the value closed standard input, the vault file then took
        // descriptor 0, and the passphrase prompt switched echo off on a regular file - failing
        // with "Inappropriate ioctl for device" whenever the vault was locked.
        final boolean[] closed = {false};
        final java.io.InputStream in =
                new java.io.ByteArrayInputStream(
                        "sk-piped\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                    @Override
                    public void close() throws IOException {
                        closed[0] = true;
                        super.close();
                    }
                };

        assertThat(VaultPutCommand.valueFrom("anthropic", null, in, null)).isEqualTo("sk-piped");
        assertThat(closed[0]).as("standard input was closed").isFalse();
    }

    @Test
    void aPutOverASignInTakesItsClientSecretWithIt() {

        // Left behind, a replaced sign-in's client secret would outlive what it belonged to.
        final java.util.Map<String, org.fuin.porelo.vault.VaultEntry> entries =
                new java.util.LinkedHashMap<>();
        entries.put("google-sign-in", org.fuin.porelo.vault.VaultEntry.of("at-1"));
        entries.put(
                TaskSecrets.CLIENT_SECRET_PREFIX + "google-sign-in",
                org.fuin.porelo.vault.VaultEntry.of("client-secret-1"));

        VaultPutCommand.replace(
                entries, "google-sign-in", org.fuin.porelo.vault.VaultEntry.of("a-key"));

        assertThat(entries)
                .as("the new entry, and no client secret of the sign-in it replaced")
                .containsOnlyKeys("google-sign-in");
    }

    @Test
    void saysSoWhenTheNameIsAnAgentsRatherThanAProviders() {

        // Stored under 'claude', a key is still found by a task running claude - but only as a
        // fallback, and the old warning said nothing would find it, which was not true either.
        final String said =
                VaultPutCommand.unknownName(
                        "claude",
                        java.util.Set.of("openrouter", "anthropic"),
                        java.util.Set.of("claude"));

        assertThat(said)
                .contains("'claude' is an agent's name")
                .contains("declared: anthropic, openrouter")
                .contains("porelo providers")
                .contains("porelo vault remove claude");
    }

    @Test
    void namesTheDeclaredProvidersWhenTheNameIsNobodys() {

        final String said =
                VaultPutCommand.unknownName(
                        "antropic", java.util.Set.of("anthropic"), java.util.Set.of("claude"));

        assertThat(said)
                .contains("no provider 'antropic' is declared")
                .contains("declared: anthropic")
                .doesNotContain("agent's name");
    }

    @Test
    void aTypedSecretIsNeverReadFromTheEchoingStream() throws IOException {

        // The bug this method exists for. A terminal echoes what is typed on standard input, so
        // reading a person's credential there prints it and leaves it in the scrollback.
        final String value =
                VaultPutCommand.readValue(true, () -> "sk-typed".toCharArray(), forbidden());

        assertThat(value).isEqualTo("sk-typed");
    }

    @Test
    void aPipedSecretIsNeverAskedForAtATerminal() throws IOException {

        // 'printf %s ... | porelo vault put' is how scripts and the acceptance suite do this. There
        // is no terminal to prompt at, and prompting anyway would hang waiting for a person who is
        // not there - which reads as a stuck build.
        final String value =
                VaultPutCommand.readValue(
                        false,
                        () -> {
                            throw new IllegalStateException(
                                    "a terminal was asked for a piped secret");
                        },
                        new BufferedReader(new StringReader("sk-piped\n")));

        assertThat(value).isEqualTo("sk-piped");
    }

    @Test
    void whatWasTypedIsWipedOnceItHasBeenRead() throws IOException {

        // Only shortens one copy's life - the stored value is a String either way. It is the copy
        // that exists for no reason after this point, and leaving it is how a secret ends up in a
        // heap dump nobody expected to contain one.
        final char[] secret = "sk-typed".toCharArray();

        VaultPutCommand.readValue(true, () -> secret, forbidden());

        assertThat(secret).containsOnly('\0');
    }

    @Test
    void surroundingWhitespaceIsNotPartOfTheCredential() throws IOException {

        // A paste carries a trailing newline more often than not, and a credential with one on the
        // end fails as an authentication error that looks exactly like a wrong key.
        assertThat(
                        VaultPutCommand.readValue(
                                true, () -> "  sk-typed \n".toCharArray(), forbidden()))
                .isEqualTo("sk-typed");
        assertThat(
                        VaultPutCommand.readValue(
                                false,
                                () -> null,
                                new BufferedReader(new StringReader("  sk-piped  \n"))))
                .isEqualTo("sk-piped");
    }

    @Test
    void typingNothingIsEmptyRatherThanAFailure() throws IOException {

        // Console.readPassword answers null at end of input - somebody pressing ctrl-D. The caller
        // refuses an empty value with a sentence; a NullPointerException here would report that as
        // a fault in Porelo.
        assertThat(VaultPutCommand.readValue(true, () -> null, forbidden())).isEmpty();
        assertThat(
                        VaultPutCommand.readValue(
                                false, () -> null, new BufferedReader(new StringReader(""))))
                .isEmpty();
    }

    @Test
    void aReaderThatFailsIsNotSwallowed() {

        assertThatThrownBy(() -> VaultPutCommand.readValue(false, () -> null, forbidden()))
                .isInstanceOf(IOException.class);
    }

    @org.junit.jupiter.api.Test
    void asksForAGrantsClientSecretByNameAndTakesNoneForAPublicClient() {
        // "Value for 'github-copilot':" - nobody could tell that it meant the client secret, nor
        // that Copilot's public client has none (2026-10-09).
        assertThat(VaultPutCommand.prompt("github-copilot", "oauth-device"))
                .isEqualTo(
                        "Client secret for 'github-copilot' (none for a public client: press Enter): ");
        assertThat(VaultPutCommand.prompt("anthropic", "api-key"))
                .isEqualTo("Value for 'anthropic': ");
        assertThat(VaultPutCommand.grantValue("", "oauth-device"))
                .as("a public client")
                .isEqualTo("-");
        assertThat(VaultPutCommand.grantValue("", "oauth-code")).isEqualTo("-");
        assertThat(VaultPutCommand.grantValue("s3cret", "oauth-device")).isEqualTo("s3cret");
        assertThat(VaultPutCommand.grantValue("", "api-key")).as("a key is never empty").isEmpty();
    }

    private static org.fuin.porelo.core.credential.Credential declared(
            final String match,
            final org.fuin.porelo.core.credential.Credential.Kind kind,
            final String entry) {
        return new org.fuin.porelo.core.credential.Credential(
                entry,
                kind,
                match,
                null,
                "git",
                org.fuin.porelo.core.credential.Credential.Source.VAULT);
    }

    @Test
    void anEntryADeclarationReadsIsSaidToBeUsedThere() {

        // Right after 'credentials declare ssh://forge.example.org --kind ssh-key --vault
        // forge-work', storing the key warned that nothing would find it (found on 2026-10-13).
        final VaultPutCommand.Use use =
                VaultPutCommand.useOf(
                        "forge-work",
                        "ssh-key",
                        java.util.List.of(
                                declared(
                                        "ssh://forge.example.org",
                                        org.fuin.porelo.core.credential.Credential.Kind.SSH_KEY,
                                        "forge-work"),
                                declared(
                                        "https://forge.example.org",
                                        org.fuin.porelo.core.credential.Credential.Kind.TOKEN,
                                        "other")));

        assertThat(use.matches()).containsExactly("ssh://forge.example.org");
        assertThat(use.misfit()).isNull();
    }

    @Test
    void anEntryNoDeclarationReadsIsUsedNowhere() {

        assertThat(
                        VaultPutCommand.useOf(
                                        "forge-work",
                                        "ssh-key",
                                        java.util.List.of(
                                                declared(
                                                        "ssh://forge.example.org",
                                                        org.fuin.porelo.core.credential.Credential
                                                                .Kind.SSH_KEY,
                                                        "forge-other")))
                                .matches())
                .isEmpty();
    }

    @Test
    void whatDoesNotFitItsDeclarationIsSaidWhenItIsStored() {

        // A start refuses it days later as unusable; storing is where the mistake is made.
        final var forKey =
                java.util.List.of(
                        declared(
                                "ssh://forge.example.org",
                                org.fuin.porelo.core.credential.Credential.Kind.SSH_KEY,
                                "forge-work"));
        final var forToken =
                java.util.List.of(
                        declared(
                                "https://forge.example.org",
                                org.fuin.porelo.core.credential.Credential.Kind.TOKEN,
                                "forge-work"));

        assertThat(VaultPutCommand.useOf("forge-work", "token", forKey).misfit())
                .contains("declared to take an ssh key")
                .contains("a token");
        assertThat(VaultPutCommand.useOf("forge-work", null, forKey).misfit())
                .contains("no ssh key");
        assertThat(VaultPutCommand.useOf("forge-work", "ssh-key", forToken).misfit())
                .contains("declared to take a token")
                .contains("an ssh key");
        assertThat(VaultPutCommand.useOf("forge-work", null, forToken).misfit()).isNull();
    }

    @Test
    void aConvertedKeyThatCannotBeOverwrittenIsRemovedAllTheSame(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws IOException {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                "root".equals(System.getProperty("user.name")),
                "root writes a read-only file, so the overwrite cannot be made to fail");

        // The private key converted for the vault lies in plain text in a directory of its own:
        // overwriting it is the part that may fail, and removing it is what must happen either way.
        final java.nio.file.Path directory =
                java.nio.file.Files.createDirectory(dir.resolve("porelo-key-1"));
        final java.nio.file.Path key =
                java.nio.file.Files.writeString(directory.resolve("key"), "PRIVATE KEY");
        java.nio.file.Files.setPosixFilePermissions(
                key, java.nio.file.attribute.PosixFilePermissions.fromString("r--------"));

        VaultPutCommand.shred(directory);

        assertThat(directory).as("the key and its directory are gone").doesNotExist();
    }
}
