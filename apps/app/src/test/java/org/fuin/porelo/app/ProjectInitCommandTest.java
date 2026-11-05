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

    PoreloContext context() {
        return new PoreloContext(
                runner,
                new PoreloPaths(XdgPaths.of(key -> null, directory), directory.resolve("bin")),
                args -> 0,
                key -> null);
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
                                + "    upstream: https://example.org/sample.git\\n\"");
        assertThat(snapshot(directory))
                .as("neither checkout nor account files change")
                .isEqualTo(before);
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
                        "add this entry:\nrepositories:\n  sample:\n    upstream: https://example.org/sample.git\n");
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
                .contains("upstream: http://127.0.0.1:3001/demo/sample.git")
                .contains("central: git@example.org:acme/sample.git")
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
        public List<org.fuin.porelo.ecosystem.api.File> configure(
                List<org.fuin.porelo.ecosystem.api.Repository> repositories) {
            return List.of();
        }
    }
}
