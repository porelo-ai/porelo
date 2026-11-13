package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.ecosystem.api.*;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Prevents detection from changing local work or leaking a checkout's credentials. */
class ProjectInitCommandTest {
    @TempDir Path directory;
    boolean localDetection;
    final ProcessCommandRunner runner = new ProcessCommandRunner();
    java.util.function.UnaryOperator<String> environment = key -> null;
    java.util.function.ToIntFunction<List<String>> exec = args -> 0;

    PoreloContext context() {
        return new PoreloContext(
                runner,
                new PoreloPaths(XdgPaths.of(key -> null, directory), directory.resolve("bin")),
                exec,
                environment);
    }

    Path checkout(String origin) throws Exception {
        Path checkout = Files.createDirectory(directory.resolve("checkout"));
        git(checkout, "init", "-q");
        git(checkout, "remote", "add", "origin", origin);
        Files.writeString(
                checkout.resolve("settings.xml"),
                "<settings><servers><server><password>password-fixture</password></server></servers></settings>\n");
        git(checkout, "add", "settings.xml");
        Files.writeString(checkout.resolve("untracked"), "unfinished work\n");
        return checkout;
    }

    void git(Path checkout, String... args) {
        var all = new java.util.ArrayList<>(List.of("git"));
        all.addAll(List.of(args));
        runner.runOrFail(
                Command.of(all)
                        .in(checkout)
                        .withEnvironment(
                                Map.of(
                                        "GIT_CONFIG_GLOBAL",
                                        "/dev/null",
                                        "GIT_CONFIG_NOSYSTEM",
                                        "1")));
    }

    Map<String, String> snapshot(Path root) throws Exception {
        Map<String, String> bytes = new TreeMap<>();
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList())
                bytes.put(
                        root.relativize(file).toString(),
                        java.util.Base64.getEncoder().encodeToString(Files.readAllBytes(file)));
        }
        return bytes;
    }

    Map<?, ?> answer(Path checkout, String... options) {
        var line = PoreloCli.commandLine(context());
        if (localDetection) {
            ProjectInitCommand init =
                    (ProjectInitCommand)
                            line.getSubcommands()
                                    .get("project")
                                    .getSubcommands()
                                    .get("init")
                                    .getCommand();
            init.detector =
                    (plugin, directory, home) ->
                            new FakePlugin(plugin.name()).detect(directory, home);
        }
        var out = new StringWriter();
        line.setOut(new PrintWriter(out));
        line.setErr(new PrintWriter(new StringWriter()));
        var args =
                new java.util.ArrayList<>(
                        List.of("project", "init", checkout.toString(), "--json"));
        args.addAll(List.of(options));
        assertThat(line.execute(args.toArray(String[]::new)))
                .as("detection answers successfully: %s", out)
                .isZero();
        return (Map<?, ?>) Json.parse(out.toString());
    }

    @Test
    void thePlainAnswerPrintsTheDraftAsYamlLines() throws Exception {
        final Path checkout = checkout("https://example.org/sample.git");
        final var line = PoreloCli.commandLine(context());
        final var out = new StringWriter();
        line.setOut(new PrintWriter(out));
        assertThat(line.execute("project", "init", checkout.toString()))
                .as("the plain proposal succeeds")
                .isZero();
        assertThat(out.toString())
                .as("the printed draft keeps its YAML line breaks")
                .startsWith("project:\n  name: sample-project\n");
    }

    @Test
    void forAProjectItAnswersTheEntryAPersonWritesAndChangesNothing() throws Exception {
        final Path checkout = checkout("https://example.org/sample.git");
        final var before = snapshot(directory);
        final var line = PoreloCli.commandLine(context());
        final var out = new StringWriter();
        line.setOut(new PrintWriter(out));
        line.setErr(new PrintWriter(new StringWriter()));

        assertThat(
                        line.execute(
                                "project",
                                "init",
                                checkout.toString(),
                                "--project",
                                "demo",
                                "--json"))
                .as("the proposal answers without asking the organization")
                .isZero();

        assertThat(out.toString())
                .as("the row to add, as the file holds it")
                .contains("\"project\":\"demo\"")
                .contains(
                        "\"entry\":\"repositories:\\n  sample:\\n"
                                + "    central: https://example.org/sample.git\\n\"");
        assertThat(snapshot(directory))
                .as("neither checkout nor account files change")
                .isEqualTo(before);
    }

    @Test
    void aProjectWhoseNameLeavesATaskNoRoomIsRefusedWithTheRoomNamed() throws Exception {

        // 'porelo-' + 51 + '-' leaves a task 5 characters of a container name's 64: not even
        // 'work-2', the name a second unnamed task gets, fits beside it.
        final Path checkout = checkout("https://example.org/sample.git");
        final var line = PoreloCli.commandLine(context());
        final var err = new StringWriter();
        line.setOut(new PrintWriter(new StringWriter()));
        line.setErr(new PrintWriter(err));

        assertThat(
                        line.execute(
                                "project",
                                "init",
                                checkout.toString(),
                                "--project",
                                "p".repeat(51)))
                .as("a usage error: %s", err)
                .isEqualTo(64);
        assertThat(err.toString())
                .as("the room left, and how long a name may be")
                .contains("5 characters")
                .contains("at most 50");

        final var fits = PoreloCli.commandLine(context());
        fits.setOut(new PrintWriter(new StringWriter()));
        fits.setErr(new PrintWriter(new StringWriter()));
        assertThat(
                        fits.execute(
                                "project",
                                "init",
                                checkout.toString(),
                                "--project",
                                "p".repeat(50),
                                "--json"))
                .as("a name that leaves 'work-2' its room is taken")
                .isZero();
    }

    @Test
    void filingIntoAProjectsFileIsRefusedWithTheEntry() throws Exception {
        final Path checkout = checkout("https://example.org/sample.git");
        final var line = PoreloCli.commandLine(context());
        final var err = new StringWriter();
        line.setOut(new PrintWriter(new StringWriter()));
        line.setErr(new PrintWriter(err));

        assertThat(
                        line.execute(
                                "project",
                                "init",
                                checkout.toString(),
                                "--project",
                                "demo",
                                "--yes"))
                .as("Porelo changes no project file that exists")
                .isEqualTo(65);
        assertThat(err.toString())
                .contains("Porelo changes no project file")
                .contains(
                        "add this entry:\nrepositories:\n  sample:\n    central: https://example.org/sample.git\n");
    }

    @Test
    void discoveryListsUsableAndRefusedPlugins() throws Exception {
        install("healthy");
        var line = PoreloCli.commandLine(context());
        var out = new StringWriter();
        line.setOut(new PrintWriter(out));
        assertThat(line.execute("ecosystems", "--json"))
                .as("discovery succeeds without starting a server")
                .isZero();
        assertThat(out.toString())
                .as("the discovery row includes name, path and nullable refusal")
                .contains("\"name\":\"healthy\"", "\"path\":", "\"refusal\":null");
    }

    @Test
    void theJsonAnswerKeepsItsSourceAndDiscoveryFieldNames() throws Exception {
        localDetection = true;
        install("healthy");
        final var answer = answer(checkout("https://example.org/sample.git"));

        assertThat(answer.get("plugins"))
                .as("existing readers still find the discovery outcomes")
                .isEqualTo(List.of(Map.of("name", "healthy", "state", "recognized", "why", "")));
        final var findings = (List<?>) answer.get("values");
        assertThat(findings).as("the adapter contributes a noncredential finding").hasSize(1);
        assertThat(((Map<?, ?>) findings.getFirst()).get("plugin"))
                .as("existing readers still attribute a finding to its source adapter")
                .isEqualTo("healthy");
    }

    /** What a test's proxy setting holds: a credential in its address, as a proxy may have. */
    static final String PROXY = "http://someone:not-a-real-one@proxy.example:3128";

    @Test
    void initLooksUpThroughThePersonsProxyAndNeverSaysIt() throws Exception {
        environment = key -> key.equals("HTTPS_PROXY") ? PROXY : null;
        install("resolving");

        final var answer = answer(checkout("https://example.org/sample.git"));

        assertThat(answer.get("draft").toString())
                .as("the plugin's own process looked up through the person's proxy")
                .contains("RUN true # looked up through the proxy given");
        assertThat(Json.write(answer))
                .as("a proxy's address may hold a credential")
                .doesNotContain("not-a-real-one");
    }

    @Test
    void aRefusedYesLooksNothingUp() throws Exception {
        environment = key -> key.equals("HTTPS_PROXY") ? PROXY : null;
        install("resolving");
        final Path checkout = checkout("https://example.org/sample.git");
        final var line = PoreloCli.commandLine(context());
        line.setOut(new PrintWriter(new StringWriter()));
        line.setErr(new PrintWriter(new StringWriter()));

        assertThat(
                        line.execute(
                                "project",
                                "init",
                                checkout.toString(),
                                "--project",
                                "demo",
                                "--yes"))
                .isEqualTo(65);
        assertThat(context().paths().xdg().home().resolve("looked-up"))
                .as("nothing is made, so nothing is looked up through the proxy")
                .doesNotExist();
    }

    @Test
    void aPluginThatCannotSayLeavesWhatItFoundAndANote() throws Exception {
        install("healthy");

        final var answer = answer(checkout("https://example.org/sample.git"));

        assertThat(answer.get("draft").toString()).contains("repo.example.org");
        assertThat((List<Object>) answer.get("notes"))
                .as("a plugin built before the lookup cannot say, which is never nothing found")
                .containsExactly(
                        "healthy could not look up what it read: it cannot say; what it read"
                                + " stands");
    }

    @Test
    void detectionLeavesTrackedUntrackedAndAccountFilesByteForByteUnchanged() throws Exception {
        final var checkout = checkout("https://example.org/sample.git");
        final var before = snapshot(directory);
        answer(checkout);
        assertThat(snapshot(directory))
                .as("detection may not change any tracked, untracked or account file")
                .isEqualTo(before);
    }

    @Test
    void aTokenInOriginIsRefusedAndEveryCheckoutByteStaysUntouched() throws Exception {
        var checkout = checkout("https://user:password-fixture@example.org/sample.git");
        var before = snapshot(checkout);
        var answer = answer(checkout);
        assertThat(answer.get("repository"))
                .as("the name comes from the remote path")
                .isEqualTo("sample");
        assertThat(answer.get("upstream"))
                .as("a credential-bearing address is never copied")
                .isNull();
        assertThat(answer.toString())
                .as("the refusal describes the problem without exposing the password")
                .contains("holds a password")
                .doesNotContain("password-fixture");
        assertThat(snapshot(checkout))
                .as("tracked, untracked and git files keep their exact bytes")
                .isEqualTo(before);
        assertThat(
                        ProjectReader.read(
                                        new StringReader(answer.get("draft").toString()), "draft")
                                .repositories())
                .as("an unrecognized checkout still gets a readable draft")
                .hasSize(1);
    }

    @Test
    void onAHostATaskReadsItsForgeAndTheCheckoutsAddressIsWhereItComesFrom() throws Exception {
        var checkout = checkout("git@example.org:acme/sample.git");

        var draft =
                String.valueOf(
                        answer(checkout, "--upstream", "http://127.0.0.1:3001/demo/sample.git")
                                .get("draft"));

        assertThat(draft)
                .as("the row names its central forge; the host derives its own address from it")
                .contains("  sample:\n    central: git@example.org:acme/sample.git\n")
                .doesNotContain("demo/sample.git")
                .contains("security_class: online");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"git@example.org:acme/sample.git", "ssh://git@example.org/acme/sample.git"})
    void theUsualSshAddressIsAnUpstreamForItsUserIsNoSecret(String origin) throws Exception {
        assertThat(answer(checkout(origin)).get("upstream")).isEqualTo(origin);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "https://private-user@example.org/sample.git",
                "private-user@example.org:sample.git",
                "https://example.org/sample.git?token=token-fixture"
            })
    void userAndTokenPartsAreNeverCopiedFromOrigin(String origin) throws Exception {
        var answer = answer(checkout(origin));
        assertThat(answer.get("upstream")).as("user and token parts are refused").isNull();
        assertThat(answer.toString())
                .as("neither the draft nor diagnostics disclose authentication parts")
                .doesNotContain("private-user", "token-fixture");
    }

    @Test
    void aMissingOriginUsesTheDirectoryNameAndTheNameOptionWins() throws Exception {
        final var checkout = checkout("https://example.org/sample.git");
        git(checkout, "remote", "remove", "origin");
        assertThat(answer(checkout).get("repository"))
                .as("without origin the directory names the repository")
                .isEqualTo("checkout");
        assertThat(answer(checkout, "--name", "chosen").get("repository"))
                .as("an explicit name wins")
                .isEqualTo("chosen");
    }

    @Test
    void pluginRefusalsDoNotDiscardOtherPluginsFindings() throws Exception {
        localDetection = true;
        checkPluginOutcomes();
    }

    @Test
    void usableUnreadableAndRefusedPluginsAreAllNamedWithoutChangingTheCheckout() throws Exception {
        checkPluginOutcomes();
    }

    private void checkPluginOutcomes() throws Exception {
        var checkout = checkout("https://example.org/sample.git");
        install("healthy");
        install("unreadable");
        install("unrecognized");
        final Path broken = context().paths().ecosystemDirectories().getFirst().resolve("broken");
        Files.writeString(broken, "#!/bin/sh\nexit 7\n");
        Files.setPosixFilePermissions(broken, PosixFilePermissions.fromString("rwx------"));
        var before = snapshot(checkout);
        var answer = answer(checkout);
        assertThat(answer.toString())
                .as("all plugin outcomes remain visible without the password")
                .contains(
                        "recognized",
                        "Unreadable",
                        "NotRecognized",
                        "broken",
                        "private",
                        "vault put")
                .doesNotContain("password-fixture");
        assertThat(answer.get("draft").toString())
                .as("the recognized plugin contributes its host")
                .contains("repo.example.org");
        assertThat(snapshot(checkout))
                .as("detection leaves every checkout byte alone")
                .isEqualTo(before);
    }

    /** A made-up value typed for the credential the stand-in adapter names. */
    static final String TYPED = "typed-sentinel-not-a-secret";

    static final char[] VAULT_PASSPHRASE = "correct horse".toCharArray();

    final List<List<String>> ran = new java.util.ArrayList<>();
    final List<String> asked = new java.util.ArrayList<>();
    final StringWriter commandSaid = new StringWriter();

    @org.junit.jupiter.api.AfterEach
    void forgetPassphrase() {
        PoreloContext.forgetAsked();
    }

    /** A vault holding {@code held}, opened for this process as a typed passphrase opens it. */
    void vault(boolean open, String... held) {
        final Map<String, org.fuin.porelo.vault.VaultEntry> entries = new java.util.HashMap<>();
        for (String each : held) entries.put(each, org.fuin.porelo.vault.VaultEntry.of("held"));
        context().vault().write(entries, VAULT_PASSPHRASE);
        if (open)
            assertThat(
                            context()
                                    .asking(prompt -> VAULT_PASSPHRASE.clone())
                                    .openerAsking(false, new PrintWriter(new StringWriter())))
                    .isPresent();
    }

    /** Runs a command as this terminal would: the person types {@link #TYPED} into it. */
    void typing() {
        exec =
                command -> {
                    ran.add(List.copyOf(command));
                    final var line =
                            PoreloCli.commandLine(
                                    context()
                                            .reading(
                                                    new StandardInput(
                                                            new java.io.ByteArrayInputStream(
                                                                    (TYPED + "\n")
                                                                            .getBytes(
                                                                                    java.nio.charset
                                                                                            .StandardCharsets
                                                                                            .UTF_8)),
                                                            false)));
                    line.setOut(new PrintWriter(commandSaid, true));
                    line.setErr(new PrintWriter(commandSaid, true));
                    return line.execute(command.subList(1, command.size()).toArray(String[]::new));
                };
    }

    /** Runs an init at a terminal whose person answers every question with {@code answer}. */
    int initAnswering(Path checkout, StringWriter said, String answer, String... args) {
        localDetection = true;
        final var line = PoreloCli.commandLine(context());
        final ProjectInitCommand init =
                (ProjectInitCommand)
                        line.getSubcommands()
                                .get("project")
                                .getSubcommands()
                                .get("init")
                                .getCommand();
        init.detector =
                (plugin, directory, home) -> new FakePlugin(plugin.name()).detect(directory, home);
        init.asker =
                question -> {
                    asked.add(question);
                    return answer;
                };
        line.setOut(new PrintWriter(said, true));
        line.setErr(new PrintWriter(said, true));
        final var all = new java.util.ArrayList<>(List.of("project", "init", checkout.toString()));
        all.addAll(List.of(args));
        return line.execute(all.toArray(String[]::new));
    }

    Map<String, org.fuin.porelo.vault.VaultEntry> vaultHolds() {
        return context()
                .vault()
                .read(org.fuin.porelo.vault.VaultFile.Opener.passphrase(VAULT_PASSPHRASE.clone()));
    }

    @Test
    void aNamedCredentialAcceptedIsStoredAsVaultPutStoresItAndItsValueIsSaidNowhere()
            throws Exception {
        install("healthy");
        final Path checkout = checkout("https://example.org/sample.git");
        vault(true, "a");
        typing();
        final StringWriter said = new StringWriter();

        assertThat(initAnswering(checkout, said, "y"))
                .as("the proposal succeeds: %s", said)
                .isZero();

        assertThat(asked)
                .as("the credential the draft names is offered once")
                .singleElement()
                .satisfies(
                        question -> assertThat(question).contains("'private'").endsWith("[y/N]"));
        assertThat(vaultHolds().get("private"))
                .as("stored under its id, as vault put stores it")
                .isNotNull()
                .extracting(org.fuin.porelo.vault.VaultEntry::value)
                .isEqualTo(TYPED);
        assertThat(ran)
                .as("vault put itself, run on this terminal")
                .singleElement()
                .satisfies(
                        command ->
                                assertThat(command.subList(1, command.size()))
                                        .containsExactly("vault", "put", "private"));
        assertThat(String.join(" ", ran.getFirst()))
                .as("the value is on no command line")
                .doesNotContain(TYPED);
        assertThat(said.toString())
                .as("the draft and every line said hold the id only")
                .contains("private")
                .doesNotContain(TYPED);
        assertThat(commandSaid.toString()).as("vault put says nothing of it").doesNotContain(TYPED);
        assertThat(Json.write(answer(checkout)))
                .as("the answer holds the id only")
                .contains("private")
                .doesNotContain(TYPED);
    }

    @Test
    void aDeclaredCredentialIsStoredByItsOwnStoreCommand() throws Exception {
        install("healthy");
        final Path checkout = checkout("https://example.org/sample.git");
        vault(true, "a");
        new CredentialDeclarations(context())
                .declare(
                        new org.fuin.porelo.core.credential.Credential(
                                "private",
                                org.fuin.porelo.core.credential.Credential.Kind.TOKEN,
                                "https://repo.example.org",
                                null,
                                "git",
                                org.fuin.porelo.core.credential.Credential.Source.VAULT));
        typing();

        assertThat(initAnswering(checkout, new StringWriter(), "y")).isZero();

        assertThat(ran)
                .singleElement()
                .satisfies(
                        command ->
                                assertThat(command.subList(1, command.size()))
                                        .containsExactly(
                                                "vault", "put", "private", "--type", "token"));
        assertThat(vaultHolds().get("private"))
                .isNotNull()
                .extracting(org.fuin.porelo.vault.VaultEntry::type)
                .isEqualTo("token");
    }

    @Test
    void aDeclinedOfferStoresNothing() throws Exception {
        install("healthy");
        final Path checkout = checkout("https://example.org/sample.git");
        vault(true, "a");
        typing();

        assertThat(initAnswering(checkout, new StringWriter(), "n")).isZero();

        assertThat(asked).as("offered once").hasSize(1);
        assertThat(ran).as("nothing run").isEmpty();
        assertThat(vaultHolds()).as("nothing stored").containsOnlyKeys("a");
    }

    @Test
    void aCredentialTheVaultHoldsIsNotOffered() throws Exception {
        install("healthy");
        final Path checkout = checkout("https://example.org/sample.git");
        vault(true, "private");
        typing();

        assertThat(initAnswering(checkout, new StringWriter(), "y")).isZero();

        assertThat(asked).as("nothing to offer").isEmpty();
        assertThat(ran).isEmpty();
    }

    @Test
    void aLockedVaultIsSaidAndNothingIsOffered() throws Exception {
        install("healthy");
        final Path checkout = checkout("https://example.org/sample.git");
        vault(false, "a");
        typing();
        final StringWriter said = new StringWriter();

        assertThat(initAnswering(checkout, said, "y")).as("a locked vault is no error").isZero();

        assertThat(asked).as("nothing offered").isEmpty();
        assertThat(ran).isEmpty();
        assertThat(said.toString()).contains("vault is locked");
    }

    @Test
    void withJsonNothingIsOffered() throws Exception {
        install("healthy");
        final Path checkout = checkout("https://example.org/sample.git");
        vault(true, "a");
        typing();

        assertThat(initAnswering(checkout, new StringWriter(), "y", "--json")).isZero();

        assertThat(asked).as("a program reads the answer; nobody is asked").isEmpty();
        assertThat(ran).isEmpty();
    }

    void install(String name) throws Exception {
        Path plugin =
                Files.createDirectories(context().paths().ecosystemDirectories().getFirst())
                        .resolve(name);
        Files.writeString(
                plugin,
                "#!/bin/sh\nexec '"
                        + Path.of(System.getProperty("java.home"), "bin/java")
                        + "' -cp '"
                        + System.getProperty("java.class.path")
                        + "' '"
                        + FakePlugin.class.getName()
                        + "' '"
                        + name
                        + "' \"$@\"\n");
        Files.setPosixFilePermissions(plugin, PosixFilePermissions.fromString("rwx------"));
    }

    /** A plugin fixture that reads only settings and returns credential identifiers. */
    public static final class FakePlugin implements Ecosystem {
        private final String name;

        FakePlugin(String name) {
            this.name = name;
        }

        /** Starts the fixture's protocol server. */
        public static void main(String[] args) {
            EcosystemMain.run(
                    new FakePlugin(args[0]), java.util.Arrays.copyOfRange(args, 1, args.length));
        }

        @Override
        public String ecosystem() {
            return name;
        }

        @Override
        public Detection detect(Path checkout, Path home) {
            if (name.equals("unreadable"))
                throw new EcosystemRefused(
                        EcosystemRefused.Reason.UNREADABLE, "settings.xml cannot be read");
            if (name.equals("unrecognized"))
                throw new EcosystemRefused(EcosystemRefused.Reason.NOT_RECOGNIZED, "");
            try {
                if (!Files.readString(checkout.resolve("settings.xml")).contains("<server>"))
                    throw new IllegalStateException("fixture needs server settings");
            } catch (java.io.IOException ex) {
                throw new IllegalStateException(ex);
            }
            return new Detection(
                    List.of(
                            new Finding(
                                    Finding.Part.RULES,
                                    "host.repo.example.org",
                                    "yes",
                                    "settings.xml",
                                    1,
                                    Finding.Origin.CHECKOUT,
                                    false,
                                    ""),
                            new Finding(
                                    Finding.Part.RULES,
                                    "credential.private",
                                    "",
                                    "settings.xml",
                                    1,
                                    Finding.Origin.CHECKOUT,
                                    false,
                                    "")),
                    List.of());
        }

        @Override
        public java.util.Optional<List<Finding>> resolve(
                Path checkout, Path home, List<Finding> findings) {
            if (!name.equals("resolving")) return java.util.Optional.empty();
            try {
                // Seen by a test that no lookup was made.
                Files.writeString(home.resolve("looked-up"), "");
            } catch (java.io.IOException ex) {
                throw new IllegalStateException(ex);
            }
            final List<Finding> resolved = new java.util.ArrayList<>(findings);
            resolved.add(
                    new Finding(
                            Finding.Part.TOOLS,
                            "run.proxy",
                            PROXY.equals(System.getenv("HTTPS_PROXY"))
                                    ? "RUN true # looked up through the proxy given"
                                    : "RUN true # looked up without it",
                            "settings.xml",
                            1,
                            Finding.Origin.CHECKOUT,
                            false,
                            ""));
            return java.util.Optional.of(resolved);
        }

        @Override
        public List<org.fuin.porelo.ecosystem.api.File> configure(
                List<org.fuin.porelo.ecosystem.api.Repository> repositories) {
            return List.of();
        }
    }
}
