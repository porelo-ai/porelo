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

    /** Fails the test if anything changes a terminal: piped input has none. */
    private static org.fuin.porelo.core.process.CommandRunner noTerminal() {
        return command -> {
            throw new AssertionError("a terminal was set up for piped input");
        };
    }

    /**
     * Tells whether a line holds nothing but wiped characters.
     *
     * <p>A verdict, never the array: handed to an assertion, a line that was not wiped would be
     * printed, and it is part of a private key.
     */
    private static boolean wiped(final char[] line) {
        for (final char each : line) {
            if (each != '\0') {
                return false;
            }
        }
        return true;
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

        assertThat(VaultPutCommand.valueFrom("anthropic", null, in, null, noTerminal()))
                .isEqualTo("sk-piped");
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
    void aValueTheEntryAlreadyHoldsIsSaidUnchangedAndWritesNothing(
            @org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir) throws IOException {

        // A play puts its keys on every run; a second run that changed nothing must be able to
        // say so without ever reading a value to compare.
        final char[] passphrase = "correct horse battery staple".toCharArray();
        final java.nio.file.Path file = dir.resolve("vault.bin");
        final org.fuin.porelo.vault.VaultFile vault = new org.fuin.porelo.vault.VaultFile(file);
        final org.fuin.porelo.vault.VaultEntry key =
                new org.fuin.porelo.vault.VaultEntry(
                        "sk-a-key-long-enough", "api-key", java.util.Map.of("region", "eu"));

        assertThat(VaultPutCommand.put(vault, passphrase, "anthropic", key)).isTrue();
        final byte[] before = java.nio.file.Files.readAllBytes(file);
        assertThat(
                        VaultPutCommand.put(
                                vault,
                                passphrase,
                                "anthropic",
                                new org.fuin.porelo.vault.VaultEntry(
                                        "sk-a-key-long-enough",
                                        "api-key",
                                        java.util.Map.of("region", "eu"))))
                .as("the same value, kind and settings")
                .isFalse();
        assertThat(java.nio.file.Files.readAllBytes(file)).isEqualTo(before);

        assertThat(
                        VaultPutCommand.put(
                                vault,
                                passphrase,
                                "anthropic",
                                new org.fuin.porelo.vault.VaultEntry(
                                        "sk-a-key-long-enough", "api-key", java.util.Map.of())))
                .as("a setting dropped is a change")
                .isTrue();
        assertThat(
                        VaultPutCommand.put(
                                vault,
                                passphrase,
                                "anthropic",
                                new org.fuin.porelo.vault.VaultEntry(
                                        "sk-another-key-as-long", "api-key", java.util.Map.of())))
                .as("a different value is a change")
                .isTrue();
    }

    @Test
    void aFileGivesTheValueStandardInputWouldGive() throws IOException {

        // A token file an editor saved ends in a newline: read raw, it was another value than the
        // same token piped in, and a second put by file after one by pipe was a change.
        assertThat(VaultPutCommand.fileValue("sk-a-key-long-enough\n", "api-key"))
                .isEqualTo(
                        VaultPutCommand.valueFrom(
                                "anthropic",
                                null,
                                new java.io.ByteArrayInputStream(
                                        "sk-a-key-long-enough\n"
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                                "api-key",
                                noTerminal()));
        assertThat(VaultPutCommand.fileValue("", org.fuin.porelo.supervisor.DeviceGrant.KIND))
                .as("an empty grant's file is a public client, as an empty answer is")
                .isEqualTo(GrantEntry.NO_SECRET);
        final String key =
                "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaA==\n-----END OPENSSH PRIVATE KEY-----\n";
        assertThat(VaultPutCommand.fileValue(key, null))
                .as("a key is the vault's to read, whole")
                .isEqualTo(key);
    }

    @Test
    void aSameValueOverASignInWithAClientSecretIsStillAChange(
            @org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir) {

        // The put takes the sign-in's client secret with it, so the vault does change.
        final char[] passphrase = "correct horse battery staple".toCharArray();
        final org.fuin.porelo.vault.VaultFile vault =
                new org.fuin.porelo.vault.VaultFile(dir.resolve("vault.bin"));
        vault.update(
                passphrase,
                entries -> {
                    entries.put("google-sign-in", org.fuin.porelo.vault.VaultEntry.of("at-1"));
                    entries.put(
                            TaskSecrets.CLIENT_SECRET_PREFIX + "google-sign-in",
                            org.fuin.porelo.vault.VaultEntry.of("client-secret-1"));
                    return entries;
                });

        assertThat(
                        VaultPutCommand.put(
                                vault,
                                passphrase,
                                "google-sign-in",
                                org.fuin.porelo.vault.VaultEntry.of("at-1")))
                .isTrue();
        assertThat(vault.read(passphrase)).containsOnlyKeys("google-sign-in");
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
                .startsWith("until something asks for it")
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
                .startsWith("Value for 'anthropic' (");
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

    private static final char[] PASSPHRASE = "correct horse battery staple".toCharArray();

    private final java.io.StringWriter out = new java.io.StringWriter();

    private final java.io.StringWriter err = new java.io.StringWriter();

    /** A machine of the test's own with a vault, its passphrase known to this JVM. */
    private static PoreloContext machine(final java.nio.file.Path dir) {
        final PoreloContext context =
                new PoreloContext(
                                new org.fuin.porelo.testing.FakeCommandRunner(),
                                new PoreloPaths(
                                        org.fuin.porelo.core.config.XdgPaths.of(name -> null, dir),
                                        dir.resolve("bin")),
                                arguments -> 0)
                        .asking(prompt -> PASSPHRASE.clone());
        context.vault().write(java.util.Map.of(), PASSPHRASE);
        context.requireOpener();
        return context;
    }

    /** Runs 'vault put' with a token piped in, as a script does. */
    private int put(final PoreloContext context, final String name) {
        return put(context, name, "token");
    }

    /** Runs 'vault put' with a value piped in, stated as the kind given. */
    private int put(final PoreloContext context, final String name, final String type) {
        final picocli.CommandLine command =
                PoreloCli.commandLine(
                        context.reading(
                                new StandardInput(
                                        new java.io.ByteArrayInputStream(
                                                "a-token-for-the-unit-test-run"
                                                        .getBytes(
                                                                java.nio.charset.StandardCharsets
                                                                        .UTF_8)),
                                        false)));
        command.setOut(new java.io.PrintWriter(out));
        command.setErr(new java.io.PrintWriter(err));
        return command.execute("vault", "put", name, "--type", type);
    }

    @Test
    void anEntryAPlacedProjectsBuildsReadIsSaidToBeUsedThere(
            @org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir) throws IOException {

        // 'vault put github-actions' warned that nothing would find it, although the placed
        // project's builds name exactly that entry: a person told so removes it, and the task's
        // build reader fails later.
        final PoreloContext context = machine(dir);
        try {
            OrganizationProjects.of(context)
                    .write(
                            new OrganizationProjects.Placed(
                                    "sluice-dry",
                                    "0123456789abcdef0123456789abcdef01234567",
                                    "https://forge.example.org/acme/sluice-dry.git",
                                    null,
                                    java.util.List.of(),
                                    null,
                                    null,
                                    null,
                                    java.util.List.of(),
                                    new org.fuin.porelo.core.project.Builds(
                                            "github",
                                            "github-actions",
                                            "",
                                            org.fuin.porelo.core.project.Builds.FAILURE)));

            assertThat(put(context, "github-actions")).as(err.toString()).isZero();

            assertThat(out.toString()).contains("used for  sluice-dry's build reader");
            assertThat(err.toString()).doesNotContain("porelo:");
        } finally {
            PoreloContext.forgetAsked();
        }
    }

    @Test
    void anEntryAProjectsCredentialsNameIsSaidToBeUsedThere(
            @org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir) throws IOException {

        final PoreloContext context = machine(dir);
        try {
            final java.nio.file.Path file = context.paths().projects().defaultProjectFile("shop");
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(
                    file,
                    """
                    project:
                      name: shop
                      security_class: online
                      central: git@github.com:you/shop.git
                    image:
                      from: ubuntu:24.04
                    credentials:
                      search-key: brave-search
                    """);

            assertThat(put(context, "search-key")).as(err.toString()).isZero();

            assertThat(out.toString()).contains("used for  shop's brave-search");
            assertThat(err.toString()).doesNotContain("porelo:");
        } finally {
            PoreloContext.forgetAsked();
        }
    }

    @Test
    void anEntryNothingAsksForYetIsSaidWhatMayAskWithoutAWarning(
            @org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir) {

        // Stored before the project that needs it is placed, which is common: no mistake to warn
        // of, only what may ask for it.
        final PoreloContext context = machine(dir);
        try {
            assertThat(put(context, "github-actions")).as(err.toString()).isZero();

            assertThat(out.toString())
                    .contains(
                            "unused    until something asks for it: a provider of that name"
                                    + " (declared: none), a project's builds.credential or"
                                    + " credentials, or a declaration with --vault github-actions")
                    .as("said under a word of its own, never as a use")
                    .doesNotContain("used for");
            assertThat(err.toString()).doesNotContain("porelo:");
        } finally {
            PoreloContext.forgetAsked();
        }
    }

    /** Places sluice-dry, its host naming {@code credential} for its build reader. */
    private static void placed(final PoreloContext context, final String credential)
            throws IOException {
        OrganizationProjects.of(context)
                .write(
                        new OrganizationProjects.Placed(
                                "sluice-dry",
                                "0123456789abcdef0123456789abcdef01234567",
                                "https://forge.example.org/acme/sluice-dry.git",
                                null,
                                java.util.List.of(),
                                null,
                                null,
                                null,
                                java.util.List.of(),
                                new org.fuin.porelo.core.project.Builds(
                                        "github",
                                        credential,
                                        "",
                                        org.fuin.porelo.core.project.Builds.FAILURE)));
    }

    @Test
    void anSshKeyStoredForABuildReaderIsSaidNotToFit(
            @org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir) throws IOException {

        // Called used by the build reader and nothing more, an ssh key there fails only when the
        // task's builds are read, days later.
        final PoreloContext context = machine(dir);
        try {
            placed(context, "github-actions");

            assertThat(put(context, "github-actions", "ssh-key")).as(err.toString()).isZero();

            assertThat(out.toString()).contains("used for  sluice-dry's build reader");
            assertThat(err.toString())
                    .contains("porelo: sluice-dry's build reader")
                    .contains("an ssh key");
        } finally {
            PoreloContext.forgetAsked();
        }
    }

    @Test
    void whatAProjectAsksForTakesNoSshKeyAndAnyOtherKind() {

        final var asked =
                java.util.Map.of(
                        "search-key", java.util.List.of("shop's brave-search"),
                        "github-actions", java.util.List.of("sluice-dry's build reader"));

        assertThat(
                        VaultPutCommand.useOf("search-key", "ssh-key", java.util.List.of(), asked)
                                .misfit())
                .contains("shop's brave-search")
                .contains("an ssh key");
        assertThat(
                        VaultPutCommand.useOf("github-actions", "token", java.util.List.of(), asked)
                                .misfit())
                .isNull();
        assertThat(
                        VaultPutCommand.useOf("github-actions", null, java.util.List.of(), asked)
                                .misfit())
                .as("a token is not recognised, so no kind stated is no sign of a misfit")
                .isNull();
    }

    @Test
    void aProjectsOwnBuildsAreAskedForOverItsHosts(
            @org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir) throws IOException {

        // As a task wires them: the host's builds.credential is not read where the project's own
        // file names another.
        final PoreloContext context = machine(dir);
        try {
            placed(context, "host-reader");
            final java.nio.file.Path file =
                    context.paths()
                            .projects()
                            .organizationClone("sluice-dry")
                            .resolve("project.yml");
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.writeString(
                    file,
                    """
                    project:
                      name: sluice-dry
                      security_class: online
                      central: git@github.com:acme/sluice-dry.git
                    image:
                      from: ubuntu:24.04
                    builds:
                      forge: github
                      credential: own-reader
                    """);

            assertThat(VaultPutCommand.askedByProjects(context))
                    .containsEntry("own-reader", java.util.List.of("sluice-dry's build reader"))
                    .doesNotContainKey("host-reader");
        } finally {
            PoreloContext.forgetAsked();
        }
    }

    @Test
    void theDefaultProjectIsNotReadForWhatItAsks(
            @org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir) {

        // Generated from the repositories added to it, it names no credential of its own; read,
        // it would be written anew on every 'vault put' for nothing.
        final PoreloContext context = machine(dir);
        try {
            assertThat(VaultPutCommand.askedByProjects(context)).isEmpty();
            assertThat(
                            context.paths()
                                    .xdg()
                                    .state()
                                    .resolve(DefaultProject.NAME)
                                    .resolve("project.yml"))
                    .as("the built-in project's file, which reading it writes")
                    .doesNotExist();
        } finally {
            PoreloContext.forgetAsked();
        }
    }

    @Test
    void aProjectFileThatDoesNotReadHidesOnlyItself(
            @org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir) throws IOException {

        // A file that does not read is the start's to report: storing says nothing of it, and
        // what the other projects ask for is still found.
        final PoreloContext context = machine(dir);
        try {
            final java.nio.file.Path broken =
                    context.paths().projects().defaultProjectFile("broken");
            java.nio.file.Files.createDirectories(broken.getParent());
            java.nio.file.Files.writeString(broken, "project: [ not, a, project\n");
            final java.nio.file.Path shop = context.paths().projects().defaultProjectFile("shop");
            java.nio.file.Files.createDirectories(shop.getParent());
            java.nio.file.Files.writeString(
                    shop,
                    """
                    project:
                      name: shop
                      security_class: online
                      central: git@github.com:you/shop.git
                    image:
                      from: ubuntu:24.04
                    credentials:
                      search-key: brave-search
                    """);

            assertThat(put(context, "search-key")).as(err.toString()).isZero();

            assertThat(out.toString()).contains("used for  shop's brave-search");
            assertThat(err.toString()).as("nothing said of the file").isEmpty();
        } finally {
            PoreloContext.forgetAsked();
        }
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

    /** A key ssh-keygen writes into the test's own directory: never a key anybody uses. */
    private static String keygen(final java.nio.file.Path dir, final String type)
            throws IOException, InterruptedException {
        final java.nio.file.Path file = dir.resolve("id_" + type);
        final Process process =
                new ProcessBuilder(
                                "ssh-keygen",
                                "-q",
                                "-t",
                                type,
                                "-N",
                                "",
                                "-C",
                                "test@porelo",
                                "-f",
                                file.toString())
                        .redirectErrorStream(true)
                        .start();
        assertThat(process.waitFor()).as("ssh-keygen").isZero();
        return java.nio.file.Files.readString(file);
    }

    /** A terminal handing out one line per read, as Console.readPassword does with a paste. */
    private static java.util.function.Supplier<char[]> lines(final java.util.List<char[]> handed) {
        final java.util.Iterator<char[]> each = handed.iterator();
        return () -> each.hasNext() ? each.next() : null;
    }

    private static java.util.List<char[]> linesOf(final String text) {
        return text.lines().map(String::toCharArray).toList();
    }

    @Test
    void aKeyPastedAtThePromptIsStoredAsThePipedKeyIs(
            @org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir) throws Exception {

        // Read one line, a pasted key was its armour line alone, and the vault then called it a
        // key of the wrong algorithm.
        final String file = keygen(dir, "ed25519");
        final java.util.List<char[]> handed = linesOf(file);
        final boolean[] promptedAgain = {false};
        final java.util.Iterator<char[]> rest = handed.subList(1, handed.size()).iterator();

        final String typed =
                VaultPutCommand.readValue(
                        true,
                        () -> handed.get(0),
                        () -> rest.hasNext() ? rest.next() : null,
                        forbidden());
        final String piped =
                VaultPutCommand.readValue(
                        false,
                        () -> {
                            promptedAgain[0] = true;
                            return null;
                        },
                        () -> null,
                        new BufferedReader(new StringReader(file)));

        // Compared as a verdict, never as values: a failure must not print the key.
        assertThat(typed.equals(piped)).as("the same text as the file piped in").isTrue();
        assertThat(
                        org.fuin.porelo.vault.StoredKey.toStore(typed)
                                .equals(org.fuin.porelo.vault.StoredKey.toStore(piped)))
                .as("the same value stored")
                .isTrue();
        assertThat(promptedAgain[0]).as("the piped key asked the terminal").isFalse();
        assertThat(handed.stream().allMatch(VaultPutCommandTest::wiped))
                .as("every line read is wiped")
                .isTrue();
    }

    @Test
    void aPasteThatStopsBeforeItsEndLineIsRefusedAsIncomplete() {

        // A terminal that hands the armour and then nothing: storing the armour is what failed.
        final char[] begin = "-----BEGIN OPENSSH PRIVATE KEY-----".toCharArray();
        final char[] body = "b3BlbnNzaC1rZXktdjEAAAAA".toCharArray();

        assertThatThrownBy(
                        () ->
                                VaultPutCommand.readValue(
                                        true,
                                        () -> begin,
                                        lines(java.util.List.<char[]>of(body)),
                                        forbidden()))
                .isInstanceOf(org.fuin.porelo.vault.VaultException.class)
                .hasMessageContaining("incomplete")
                .hasMessageContaining("2 lines")
                .hasMessageContaining("--from-file")
                .hasMessageNotContaining("b3BlbnNz");
        assertThat(wiped(begin)).as("the armour line is wiped").isTrue();
        assertThat(wiped(body)).as("the line after it is wiped").isTrue();
    }

    @Test
    void aPasteRunningPastTheBoundIsRefusedAsIncomplete() {

        // A key is a few dozen lines; a terminal that never sends the end line is not read forever.
        final int[] reads = {0};

        assertThatThrownBy(
                        () ->
                                VaultPutCommand.readValue(
                                        true,
                                        () -> "-----BEGIN OPENSSH PRIVATE KEY-----".toCharArray(),
                                        () -> {
                                            reads[0]++;
                                            return "AAAA".toCharArray();
                                        },
                                        forbidden()))
                .isInstanceOf(org.fuin.porelo.vault.VaultException.class)
                .hasMessageContaining("incomplete")
                .hasMessageContaining("200 lines");
        assertThat(reads[0]).as("lines read after the first").isEqualTo(199);
    }

    /**
     * A person's terminal handing out a key one line per read, as {@code Console.readPassword} does
     * with a paste, and saying in {@code events} what was asked of it.
     */
    private static VaultPutCommand.Typing pasting(
            final java.util.List<char[]> handed, final java.util.List<String> events) {
        final java.util.Iterator<char[]> each = handed.iterator();
        return new VaultPutCommand.Typing() {
            @Override
            public char[] withPrompt(final String prompt) {
                events.add("prompt");
                return each.next();
            }

            @Override
            public char[] next() {
                events.add("line");
                return each.hasNext() ? each.next() : null;
            }
        };
    }

    /** A terminal whose stty calls are said in {@code events}, the save answering settings. */
    private static org.fuin.porelo.core.process.CommandRunner stty(
            final java.util.List<String> events) {
        final org.fuin.porelo.testing.FakeCommandRunner runner =
                new org.fuin.porelo.testing.FakeCommandRunner()
                        .answering("stty -g", "500:5:bf:8a3b\n");
        return command -> {
            final String line = command.describe();
            events.add(
                    line.contains("stty -g")
                            ? "save"
                            : line.contains("stty -echo")
                                    ? "echo off"
                                    : line.contains("porelo-terminal") ? "restore" : line);
            return runner.run(command);
        };
    }

    @Test
    void aKeyPastedAtTheConsoleIsStoredAsThePipedKeyIs(
            @org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir) throws Exception {

        // Through valueFrom, which wires the console's reads: a further-line reader that answers
        // nothing there would store no pasted key, and only a test that goes through it sees that.
        final String file = keygen(dir, "ed25519");
        final java.util.List<char[]> handed = linesOf(file);

        final String typed =
                VaultPutCommand.valueFrom(
                        "github-work",
                        pasting(handed, new java.util.ArrayList<>()),
                        new java.io.ByteArrayInputStream(new byte[0]),
                        null,
                        stty(new java.util.ArrayList<>()));
        final String piped =
                VaultPutCommand.valueFrom(
                        "github-work",
                        null,
                        new java.io.ByteArrayInputStream(
                                file.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        null,
                        noTerminal());

        // Compared as a verdict, never as values: a failure must not print the key.
        assertThat(typed.equals(piped)).as("the same text as the file piped in").isTrue();
        assertThat(handed.stream().allMatch(VaultPutCommandTest::wiped))
                .as("every line read is wiped")
                .isTrue();
    }

    @Test
    void echoIsOffFromThePromptToTheEndLine(
            @org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir) throws Exception {

        // readPassword turns echo back on before it returns, so a paste read one line at a time
        // echoed whatever arrived between two reads. Off once before the prompt and back after
        // the end line, the terminal never echoes during the paste.
        final java.util.List<char[]> handed = linesOf(keygen(dir, "ed25519"));
        final java.util.List<String> events = new java.util.ArrayList<>();

        VaultPutCommand.valueFrom(
                "github-work",
                pasting(handed, events),
                new java.io.ByteArrayInputStream(new byte[0]),
                null,
                stty(events));

        final java.util.List<String> expected = new java.util.ArrayList<>();
        expected.add("save");
        expected.add("echo off");
        expected.add("prompt");
        for (int line = 1; line < handed.size(); line++) {
            expected.add("line");
        }
        expected.add("restore");
        assertThat(events).isEqualTo(expected);
    }

    @Test
    void aPasteEndedWithCtrlDIsRefusedAndTheTerminalGivenBack() {

        // A paste that stops leaves the read waiting, as the terminal is still open: Ctrl-D is end
        // of input, which the prompt names, and the refusal must not leave echo off behind it.
        final java.util.List<String> events = new java.util.ArrayList<>();
        final java.util.List<char[]> handed =
                java.util.List.of(
                        "-----BEGIN OPENSSH PRIVATE KEY-----".toCharArray(),
                        "b3BlbnNzaC1rZXktdjEAAAAA".toCharArray());

        assertThat(VaultPutCommand.prompt("github-work", null))
                .as("the prompt says how a paste cut short is ended")
                .contains("Ctrl-D")
                .contains("Ctrl-C");
        assertThatThrownBy(
                        () ->
                                VaultPutCommand.valueFrom(
                                        "github-work",
                                        pasting(handed, events),
                                        new java.io.ByteArrayInputStream(new byte[0]),
                                        null,
                                        stty(events)))
                .isInstanceOf(org.fuin.porelo.vault.VaultException.class)
                .hasMessageContaining("incomplete")
                .hasMessageContaining("2 lines");
        assertThat(events)
                .as("echo held off across the reads and given back after the refusal")
                .containsExactly("save", "echo off", "prompt", "line", "line", "restore");
    }
}
