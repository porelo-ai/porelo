package org.fuin.porelo.agent.impl.stub;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.agent.api.AgentDefinition;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link StubAgent}.
 *
 * <p>The ones that matter check the definition and the script it installs agree. A stub whose
 * definition promises a reach the script never makes would pass every check in the acceptance suite
 * while proving nothing, which is the one way this agent can be worse than no agent.
 */
class StubAgentTest {

    private final StubAgent agent = new StubAgent();

    /** The container-build line, exactly as the image build receives it. */
    private String buildLine() {
        return String.join("\n", agent.definition().installAsRoot());
    }

    /**
     * The script that line actually writes, produced by running it.
     *
     * <p>Rendered rather than parsed: the tests below then check the file an image really gets, and
     * they keep working whatever form the line takes. An earlier version read the body out of a
     * here-document, which was both brittle and a form podman 4 cannot build at all.
     */
    private String renderedScript() throws Exception {
        final java.nio.file.Path file = java.nio.file.Files.createTempFile("stub-cli", ".sh");
        try {
            final String command =
                    buildLine()
                            .replaceFirst("^RUN ", "")
                            .replace(StubAgent.TARGET, file.toString());
            final Process process =
                    new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
            final String output = new String(process.getInputStream().readAllBytes());
            assertThat(process.waitFor())
                    .withFailMessage("the build line did not run: %s", output)
                    .isZero();
            return java.nio.file.Files.readString(file);
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    /** Every name the script asks the resolver for. */
    private List<String> lookups() throws Exception {
        return renderedScript()
                .lines()
                .map(String::strip)
                .filter(line -> line.startsWith("getent hosts "))
                .map(line -> line.split(" ")[2])
                .toList();
    }

    @Test
    void logsInByWritingAnOauthFixtureWhereItsDefinitionSaysAndItsExtractorFindsIt(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path home) throws Exception {

        // The chain a login takes, end to end on this side: the definition's login arguments run
        // the script,
        // which writes where config_dir says, and the extractor every login goes through reads it
        // as oauth.
        assertThat(agent.definition().loginArguments()).containsExactly("login");
        assertThat(agent.definition().configDirectory()).isEqualTo("~/.porelo-stub");
        final java.nio.file.Path script = home.resolve("porelo-stub-cli");
        java.nio.file.Files.writeString(script, renderedScript());
        final ProcessBuilder login =
                new ProcessBuilder("sh", script.toString(), "login").redirectErrorStream(true);
        login.environment().put("HOME", home.toString());
        login.environment().put("PORELO_STUB_LINGER", "0");
        final Process process = login.start();
        final String said = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).isZero();
        assertThat(said).contains("stub: logged in");

        final java.nio.file.Path config = home.resolve(".porelo-stub");
        assertThat(java.nio.file.Files.getPosixFilePermissions(config.resolve(".credentials.json")))
                .containsExactlyInAnyOrder(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
        final var credential = agent.credentialExtractor().extract(config).orElseThrow();
        assertThat(credential.type()).isEqualTo("oauth");
        assertThat(String.valueOf(credential.secret())).isEqualTo("porelo-stub-fixture-token");
        // What renews it is handed over too, as a subscription login's is, under the agent API's
        // names.
        assertThat(credential.attributes())
                .containsEntry("refreshToken", "porelo-stub-fixture-refresh")
                .containsEntry("tokenUrl", "https://example.com/porelo-stub/token")
                .containsEntry("clientId", "porelo-stub")
                .hasEntrySatisfying(
                        "expiresAt",
                        ends ->
                                assertThat(Long.parseLong(ends))
                                        .isGreaterThan(System.currentTimeMillis()));
    }

    @Test
    void aWorkspaceHoldingTheWorkMarkerIsWorkedOnUntilStopped(
            @org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir) throws Exception {

        // Measuring a machine under many tasks needs agents that work: a screen that changes,
        // commits, pushes.
        final java.nio.file.Path workspace =
                java.nio.file.Files.createDirectories(dir.resolve("workspace"));
        final java.nio.file.Path script = dir.resolve("porelo-stub-cli");
        java.nio.file.Files.writeString(script, renderedScript());
        for (final String[] git :
                new String[][] {
                    {"init", "-q", "."},
                    {"config", "user.email", "s@example.com"},
                    {"config", "user.name", "S"}
                }) {
            final java.util.List<String> command =
                    new java.util.ArrayList<>(java.util.List.of("git"));
            command.addAll(java.util.List.of(git));
            new ProcessBuilder(command).directory(workspace.toFile()).start().waitFor();
        }
        java.nio.file.Files.writeString(workspace.resolve(".porelo-stub-works"), "1\n");
        // A terminal, as tmux gives it in a task: the work loop runs only for an attached session.
        final java.nio.file.Path screen = dir.resolve("screen.txt");
        final ProcessBuilder attached =
                new ProcessBuilder("script", "-qec", "sh " + script, "/dev/null")
                        .redirectErrorStream(true)
                        .redirectOutput(screen.toFile());
        attached.environment().put("PORELO_STUB_WORKSPACE", workspace.toString());
        final Process process = attached.start();
        Thread.sleep(3_500);
        process.destroy();
        process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        process.destroyForcibly();
        final String said = java.nio.file.Files.readString(screen);

        final Process log =
                new ProcessBuilder("git", "log", "--format=%s")
                        .directory(workspace.toFile())
                        .start();
        final String commits =
                new String(
                        log.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        assertThat(said).contains("stub: at work, nothing asked").contains("stub: step 2");
        assertThat(commits.lines().filter(line -> line.startsWith("stub: step")).count())
                .as(commits)
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void aWorkspaceMarkedForLoadAlsoAsksTheProviderAndWritesToTheDiskAtEveryStep(
            @org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir) throws Exception {

        // The second round of measuring: agents that talk to their provider and write, not only
        // commit.
        final java.nio.file.Path workspace =
                java.nio.file.Files.createDirectories(dir.resolve("workspace"));
        final java.nio.file.Path script = dir.resolve("porelo-stub-cli");
        java.nio.file.Files.writeString(script, renderedScript());
        new ProcessBuilder("git", "init", "-q", ".")
                .directory(workspace.toFile())
                .start()
                .waitFor();
        java.nio.file.Files.writeString(workspace.resolve(".porelo-stub-works"), "1 load\n");
        final java.nio.file.Path asked = dir.resolve("asked.txt");
        final java.nio.file.Path bin = java.nio.file.Files.createDirectories(dir.resolve("bin"));
        // A curl that writes down what it was asked, standing in for the provider's socket.
        java.nio.file.Files.writeString(
                bin.resolve("curl"), "#!/bin/sh\necho \"$*\" >> " + asked + "\n");
        bin.resolve("curl").toFile().setExecutable(true);
        final java.nio.file.Path screen = dir.resolve("screen.txt");
        final ProcessBuilder attached =
                new ProcessBuilder("script", "-qec", "sh " + script, "/dev/null")
                        .redirectErrorStream(true)
                        .redirectOutput(screen.toFile());
        attached.environment().put("PORELO_STUB_WORKSPACE", workspace.toString());
        attached.environment().put("PORELO_STUB_SOCKET", dir.resolve("provider.sock").toString());
        attached.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        final Process process = attached.start();
        Thread.sleep(3_500);
        process.destroy();
        process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        process.destroyForcibly();

        assertThat(java.nio.file.Files.readString(asked))
                .as("a request at every step")
                .contains("--unix-socket " + dir.resolve("provider.sock"))
                .contains("\"stream\":true");
        assertThat(java.nio.file.Files.size(workspace.resolve(".porelo-stub-disk")))
                .isEqualTo(8L * 1024 * 1024);
    }

    /**
     * Runs the script with nobody at a terminal, as an unattended run does, in a home of its own
     * and with nothing reached on the network: the lookups and the provider's call answer from
     * fakes.
     */
    private String runUnattended(final java.nio.file.Path dir, final String... arguments)
            throws Exception {
        final java.nio.file.Path script = dir.resolve("porelo-stub-cli");
        java.nio.file.Files.writeString(script, renderedScript());
        final java.nio.file.Path bin = java.nio.file.Files.createDirectories(dir.resolve("bin"));
        for (final String fake : List.of("getent", "curl")) {
            java.nio.file.Files.writeString(bin.resolve(fake), "#!/bin/sh\nexit 2\n");
            bin.resolve(fake).toFile().setExecutable(true);
        }
        final List<String> command = new ArrayList<>(List.of("sh", script.toString()));
        command.addAll(List.of(arguments));
        final ProcessBuilder run =
                new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
        run.environment().put("HOME", dir.resolve("home").toString());
        run.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        run.environment().remove("PORELO_STUB_SOCKET");
        final Process process = run.start();
        final String said =
                new String(
                        process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as("the run exited non-zero:%n%s", said).isZero();
        return said;
    }

    /** The session a run named in its record, which is what Porelo keeps and hands back. */
    private static String sessionOf(final String said) {
        final java.util.regex.Matcher id =
                java.util.regex.Pattern.compile("\\{\"stub\":\"session\",\"id\":\"([^\"]+)\"\\}")
                        .matcher(said);
        assertThat(id.find()).as("the run named no session:%n%s", said).isTrue();
        return id.group(1);
    }

    @Test
    void anUnattendedRunContinuedSaysBackWhatOnlyItsFirstRunWasGiven(
            @org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir) throws Exception {

        // A continuation is proved by what only the earlier turn holds: a run that merely said
        // "continued" for any
        // id would read as a kept conversation where a fresh one had started.
        final String id = sessionOf(runUnattended(dir, "lantern"));

        final String continued = runUnattended(dir, "--resume", id, "hello");

        assertThat(continued)
                .as("the continued run says back the first run's word, not its own prompt")
                .contains(
                        "{\"stub\":\"result\",\"said\":\"stub: continued, remembering lantern\"}");
        assertThat(sessionOf(continued)).as("it continues the session it was given").isEqualTo(id);
    }

    @Test
    void aSessionIdItNeverKeptIsNotTakenForAContinuation(
            @org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir) throws Exception {

        // A stale id - one whose session is gone - must not make a fresh session pretend to
        // continue.
        final String said = runUnattended(dir, "--resume", "s-gone", "hello");

        assertThat(said)
                .as("what a run handed an id it never kept says")
                .doesNotContain("continued")
                .contains("stub: no session s-gone to continue")
                .contains("{\"stub\":\"result\",\"said\":\"stub: finished\"}");
        assertThat(sessionOf(said))
                .as("it names the fresh session it started, not the stale one")
                .isNotEqualTo("s-gone");
    }

    @Test
    void anAttachedSessionContinuedSaysBackTheFirstLineTypedInIt(
            @org.junit.jupiter.api.io.TempDir final java.nio.file.Path dir) throws Exception {

        // The attached half: what a person typed is the earlier turn, kept in the session file
        // Porelo reads the id
        // from, and an attached agent continued with that id shows it on its screen.
        final java.nio.file.Path script = dir.resolve("porelo-stub-cli");
        java.nio.file.Files.writeString(script, renderedScript());
        final java.nio.file.Path home = java.nio.file.Files.createDirectories(dir.resolve("home"));

        final String first =
                attached(dir, home, "sh " + script, "lantern\n", "stub: noted lantern");
        final java.nio.file.Path sessions = home.resolve(".porelo-stub-sessions");
        final List<java.nio.file.Path> files;
        try (java.util.stream.Stream<java.nio.file.Path> listed =
                java.nio.file.Files.list(sessions)) {
            files = listed.toList();
        }
        assertThat(files).as("one session file, after:%n%s", first).hasSize(1);
        final String name = files.get(0).getFileName().toString();
        final String id = name.substring(0, name.length() - ".session".length());

        final String again =
                attached(dir, home, "sh " + script + " --resume " + id, "", "stub: at work");

        assertThat(again)
                .as("the continued screen")
                .contains("stub: continuing " + id + ", remembering lantern");
    }

    /**
     * Runs the script at a terminal, types what is given, and returns the screen once it shows what
     * is awaited.
     */
    private static String attached(
            final java.nio.file.Path dir,
            final java.nio.file.Path home,
            final String command,
            final String typed,
            final String awaited)
            throws Exception {
        final java.nio.file.Path screen = java.nio.file.Files.createTempFile(dir, "screen", ".txt");
        final ProcessBuilder terminal =
                new ProcessBuilder("script", "-qec", command, "/dev/null")
                        .redirectErrorStream(true)
                        .redirectOutput(screen.toFile());
        terminal.environment().put("HOME", home.toString());
        terminal.environment().remove("PORELO_STUB_WORKSPACE");
        final Process process = terminal.start();
        try {
            final java.time.Instant ready = java.time.Instant.now().plusSeconds(10);
            while (!java.nio.file.Files.readString(screen).contains("stub: at work")
                    && java.time.Instant.now().isBefore(ready)) {
                Thread.sleep(100);
            }
            process.getOutputStream()
                    .write(typed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            process.getOutputStream().flush();
            final java.time.Instant deadline = java.time.Instant.now().plusSeconds(10);
            while (!java.nio.file.Files.readString(screen).contains(awaited)
                    && java.time.Instant.now().isBefore(deadline)) {
                Thread.sleep(100);
            }
            return java.nio.file.Files.readString(screen);
        } finally {
            process.destroy();
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            process.destroyForcibly();
        }
    }

    @Test
    void aCopyUnderTheSessionlessNameDeclaresNoSessionIdAndTheStubKeepsItsOwn() {

        // The copy is how a suite proves an agent that cannot continue starts fresh and says so;
        // still declaring an
        // id, it would continue and the proof would be of nothing - and the stub itself losing its
        // id would turn
        // every continuation the suite proves into a fresh session.
        final AgentDefinition plain = StubAgent.forExecutable("porelo-agent-stub").definition();
        final AgentDefinition sessionless =
                StubAgent.forExecutable(StubAgent.SESSIONLESS).definition();

        assertThat(plain.sessionIds()).as("the stub's own session id").isNotNull();
        assertThat(plain.name()).isEqualTo("stub");
        assertThat(sessionless.sessionIds()).as("the copy's session id").isNull();
        assertThat(sessionless.name())
                .as("the copy's name, apart from the stub's")
                .isEqualTo("stub-sessionless");
        assertThat(sessionless.installAsRoot())
                .as("the copy installs the same tool")
                .isEqualTo(plain.installAsRoot());
        assertThat(sessionless.supportsResume())
                .as("only the id is missing")
                .isEqualTo(plain.supportsResume());
    }

    @Test
    void reportsTheVersionItsDefinitionPins() throws Exception {
        final String version = agent.definition().version();

        assertThat(version).isNotNull().isNotEqualTo("${agent.cli.version}");
        assertThat(renderedScript()).contains(version + " (Porelo stub agent)");
    }

    @Test
    void looksUpEveryDomainItDeclares() throws Exception {

        // The lookup, not the name. An earlier version asserted the script merely mentioned each
        // domain and stayed green when the lookup was pointed at a third name, because the
        // domain was still in an echo beside it.
        final AgentDefinition definition = agent.definition();
        final List<String> declared = new ArrayList<>(definition.allowedDomains());
        declared.addAll(definition.refusedDomains());

        assertThat(definition.allowedDomains()).isNotEmpty();
        assertThat(definition.refusedDomains()).isNotEmpty();
        assertThat(lookups()).isNotEmpty().containsExactlyInAnyOrderElementsOf(declared);
    }

    @Test
    void declaresNoDomainAsBothGrantedAndRefused() {
        final AgentDefinition definition = agent.definition();

        assertThat(definition.allowedDomains())
                .doesNotContainAnyElementsOf(definition.refusedDomains());
    }

    @Test
    void redeemsItsTokenThroughTheSocketTheProviderSectionNames() throws Exception {
        final String socketVariable = agent.definition().provider().socketEnvironment();

        assertThat(socketVariable).isNotNull();
        assertThat(renderedScript()).contains("--unix-socket \"$" + socketVariable + "\"");
    }

    @Test
    void writesTheBinaryItsDefinitionSaysItIsInvokedAs() {
        assertThat(StubAgent.TARGET).endsWith("/" + agent.definition().binary());
        assertThat(buildLine())
                .contains("> " + StubAgent.TARGET)
                .contains("chmod 0755 " + StubAgent.TARGET);
    }

    @Test
    void usesNoConstructPodmanFourCannotParse() {

        // podman 4.9.3, which Ubuntu 24.04 ships, reads every line of a Containerfile as an
        // instruction. A here-document fails there with `Unknown instruction: "IF"` while
        // building fine on podman 5, which is how this reached an acceptance leg.
        assertThat(buildLine()).doesNotContain("<<").startsWith("RUN ");
        for (final String line : agent.definition().installAsRoot()) {
            assertThat(line.strip().isEmpty()).isFalse();
        }
    }

    @Test
    void leavesNothingForTheImageBuildToFetch() {
        assertThat(agent.definition().artifacts()).isEmpty();
        assertThat(agent.definition().packaged()).isEmpty();
        assertThat(agent.definition().installAsAgent()).isEmpty();
    }

    @Test
    void writesAShellScriptTheShellAccepts() throws Exception {
        final java.nio.file.Path file = java.nio.file.Files.createTempFile("stub-cli", ".sh");
        try {
            java.nio.file.Files.writeString(file, renderedScript());
            final Process process =
                    new ProcessBuilder("sh", "-n", file.toString())
                            .redirectErrorStream(true)
                            .start();
            final String output = new String(process.getInputStream().readAllBytes());
            assertThat(process.waitFor())
                    .withFailMessage("sh rejected the script: %s", output)
                    .isZero();
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    @org.junit.jupiter.api.Test
    void readsItsProvidersAnswerAsTheEndOfItsRun() {

        // The stub's last act is one call to its provider through the broker; what came back says
        // how its run ended.
        final StubAgent stub = new StubAgent();

        org.assertj.core.api.Assertions.assertThat(
                        stub.ended(
                                "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}"))
                .isEqualTo(
                        new org.fuin.porelo.agent.api.AgentEnd(
                                false, "invalid x-api-key", "provider", null));
        org.assertj.core.api.Assertions.assertThat(
                        stub.ended("{\"type\":\"message\",\"content\":[]}"))
                .isEqualTo(new org.fuin.porelo.agent.api.AgentEnd(true, "", "agent", null));
        org.assertj.core.api.Assertions.assertThat(stub.ended("")).isNull();
        org.assertj.core.api.Assertions.assertThat(stub.ended("stub: example.com resolved"))
                .isNull();
    }
}
