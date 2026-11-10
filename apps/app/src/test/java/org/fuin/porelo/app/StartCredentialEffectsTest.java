package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.TaskMode;
import org.fuin.porelo.wire.TaskProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Guards the ordering of the credential gate on both start paths using the stub's definition. */
class StartCredentialEffectsTest {

    private static final String AGENT = FixtureAgent.name();
    @TempDir Path dir;
    private final FakeCommandRunner machine =
            new FakeCommandRunner().answering("podman version", "5.8.1");
    private final StringWriter text = new StringWriter();
    private final PrintWriter sink = new PrintWriter(text, true);

    private PoreloContext context() throws Exception {
        final Path sockets = SocketPaths.shortest(dir.resolve("r"));
        final PoreloContext context =
                new PoreloContext(
                        machine,
                        new PoreloPaths(
                                new XdgPaths(
                                        dir.resolve("c"),
                                        dir.resolve("d"),
                                        dir.resolve("s"),
                                        sockets,
                                        dir),
                                dir.resolve("bin")),
                        args -> 0);
        final Path agents = Files.createDirectories(context.paths().xdg().data().resolve("agents"));
        final Path definition = agents.resolve(AGENT + ".yaml");
        Files.copy(FixtureAgent.definition(), definition);
        final Path executable = agents.resolve("porelo-agent-stub");
        Files.writeString(
                executable,
                "#!/bin/sh\n: > '"
                        + dir.resolve("adapter-called")
                        + "'\nexec '"
                        + Path.of(System.getProperty("java.home"), "bin", "java")
                        + "' -cp '"
                        + System.getProperty("java.class.path")
                        + "' '"
                        + TaskRunCommandTest.PreviewAgent.class.getName()
                        + "' '"
                        + definition
                        + "' \"$@\"\n");
        executable.toFile().setExecutable(true);
        final Path providers =
                Files.createDirectories(context.paths().xdg().data().resolve("providers"));
        Files.writeString(
                providers.resolve("anthropic.yaml"),
                """
                name: anthropic
                upstream: https://provider.example.org
                dialects: {anthropic-messages: ""}
                token_env: {_default: ANTHROPIC_API_KEY}
                """);
        Files.createDirectories(context.paths().binaryDirectory());
        for (String hook :
                List.of("porelo-hook-nft", "porelo-hook-supervisor", "porelo-hook-reader")) {
            final Path binary = context.paths().binaryDirectory().resolve(hook);
            Files.writeString(binary, "#!/bin/sh\nexit 0\n");
            binary.toFile().setExecutable(true);
        }
        return context;
    }

    private TaskLaunch launch(PoreloContext context) throws Exception {
        final Path project = dir.resolve("project.yml");
        Files.writeString(
                project,
                """
                project: {name: p, security_class: guarded}
                image: {from: 'ubuntu:24.04'}
                """);
        return new TaskLaunch(
                context,
                new TaskLaunch.Request(
                        "work",
                        project,
                        AGENT,
                        "anthropic",
                        null,
                        8,
                        null,
                        false,
                        false,
                        "deny",
                        true,
                        TaskMode.AGENT,
                        null,
                        null,
                        null,
                        1,
                        "p"));
    }

    private void requireStub(PoreloContext context) {
        try (var installed = context.agents()) {
            if (installed.find(AGENT).isEmpty()) {
                throw new org.fuin.porelo.agent.api.AgentException(
                        "the stub fixture could not start: " + installed.failures());
            }
        }
    }

    private void nothingMade(PoreloContext context, TaskLaunch launch) {
        assertThat(launch.credentialRefusal())
                .as("the refusal must be a credential value: %s", text)
                .isNotNull();
        assertThat(context.paths().hooksDirectory())
                .as("a refused start must not install hooks")
                .doesNotExist();
        assertThat(context.paths().messaging().mailbox("porelo-p-work"))
                .as("a refused start must not enroll its conversation")
                .doesNotExist();
        assertThat(machine.lines())
                .as("no container, hook execution or upstream probe may precede a refusal")
                .noneMatch(
                        line ->
                                line.contains(" create ")
                                        || line.contains(" build ")
                                        || line.contains(" start ")
                                        || line.contains("ls-remote")
                                        || line.contains("porelo-hook"));
    }

    @Test
    void aMissingModelCredentialRefusesBeforeHooksAProbeOrAContainer() throws Exception {
        // A refused start must not change hooks, reach an upstream or leave a task behind.
        final PoreloContext context = context();
        requireStub(context);
        final TaskLaunch launch = launch(context);
        assertThat(launch.launch(sink, sink, running -> 0))
                .as("missing credential: %s", text)
                .isEqualTo(69);
        nothingMade(context, launch);
        assertThat(launch.credentialRefusal().locked())
                .as("missing is distinct from locked")
                .isFalse();
    }

    @Test
    void aStoppedTaskChecksItsRecordedAgentBeforeRepairingHooks() throws Exception {
        // Resume must not bypass the credential gate or change hooks and conversations on refusal.
        final PoreloContext context = context();
        requireStub(context);
        final TaskLaunch launch = launch(context);
        machine.answering(
                "ps",
                "porelo-p-work\tExited (0) 4 minutes ago\t1700000000\t1700000100\tp\tguarded\tp\t\n");
        new TaskProfile(
                        TaskProfile.VERSION,
                        AGENT,
                        TaskMode.AGENT,
                        null,
                        null,
                        "",
                        "deny",
                        null,
                        "anthropic",
                        null,
                        null,
                        0)
                .writeTo(
                        Files.createDirectories(
                                context.paths().tasks().taskRecord("porelo-p-work")));
        final TaskLaunch.Existing existing = launch.startExisting(sink, sink);
        assertThat(existing).as("the stopped task is refused: %s", text).isNotNull();
        assertThat(existing.code()).as("no model credential means no resume").isEqualTo(69);
        nothingMade(context, launch);
    }

    @Test
    void aLockedVaultRefusesASavedTaskBeforeRepairingHooks() throws Exception {
        // A resume must not repair hooks or enroll conversations before learning its saved tokens
        // are locked.
        final PoreloContext context = context();
        Files.delete(context.paths().xdg().data().resolve("agents/porelo-agent-stub"));
        final TaskLaunch launch = launch(context);
        machine.answering(
                "ps",
                "porelo-p-work\tExited (0) 4 minutes ago\t1700000000\t1700000100\tp\tguarded\tp\t\n");
        final Path saved =
                Files.createDirectories(context.paths().tasks().taskRecord("porelo-p-work"));
        new TaskProfile(
                        TaskProfile.VERSION,
                        AGENT,
                        TaskMode.SHELL,
                        null,
                        null,
                        "",
                        "deny",
                        null,
                        "anthropic",
                        null,
                        null,
                        0)
                .writeTo(saved);
        new TaskHelpers(
                        List.of(
                                new TaskHelpers.Helper(
                                        "gate",
                                        List.of("porelo", "gate", "serve"),
                                        Map.of(),
                                        TaskHelpers.AFTER)))
                .writeTo(saved);
        Files.createDirectories(context.vault().path().getParent());
        context.vault().write(Map.of(), "test passphrase".toCharArray());

        final TaskLaunch.Existing existing = launch.startExisting(sink, sink);

        assertThat(existing).as("the saved task is refused").isNotNull();
        assertThat(launch.credentialRefusal())
                .as("locked saved tokens must answer VaultLocked before resume")
                .isNotNull();
        assertThat(launch.credentialRefusal().locked())
                .as("this is a locked vault, not a missing model credential")
                .isTrue();
        nothingMade(context, launch);
    }

    @Test
    void anAuthorizedOtherGrantDoesNotRefuseTheStart() throws Exception {
        // An already-authorized grant must not block work or cause another authorization.
        final PoreloContext context = context().asking(shown -> "test passphrase".toCharArray());
        Files.delete(context.paths().xdg().data().resolve("agents/porelo-agent-stub"));
        final Path project = dir.resolve("project.yml");
        Files.writeString(
                project,
                """
                project: {name: p, security_class: guarded}
                image: {from: 'ubuntu:24.04'}
                credentials: {forge: elsewhere}
                """);
        // Declared, so that only the grant is in question: an undeclared one is refused first.
        final Path destinations =
                Files.createDirectories(context.paths().xdg().data().resolve("destinations"));
        Files.writeString(
                destinations.resolve("elsewhere.yaml"),
                """
                name: elsewhere
                label: Elsewhere
                upstream: https://elsewhere.example
                auth_header: Authorization
                """);
        Files.createDirectories(context.vault().path().getParent());
        context.vault()
                .write(
                        Map.of(
                                "forge",
                                new org.fuin.porelo.vault.VaultEntry("fixture", "oauth-device"),
                                TaskSecrets.GRANT_PREFIX + "forge",
                                org.fuin.porelo.vault.VaultEntry.of("fixture")),
                        "test passphrase".toCharArray());
        try {
            assertThat(context.openerAsking(false, sink)).as("the test vault is open").isPresent();
            final TaskLaunch launch =
                    new TaskLaunch(
                            context,
                            new TaskLaunch.Request(
                                    "work",
                                    project,
                                    null,
                                    null,
                                    null,
                                    8,
                                    null,
                                    false,
                                    false,
                                    "deny",
                                    true,
                                    TaskMode.UNATTENDED,
                                    "work",
                                    null,
                                    null,
                                    1,
                                    "p"));
            assertThat(launch.credentialsReady(sink, sink))
                    .as("an authorized non-model grant must remain usable: %s", text)
                    .isTrue();
            assertThat(context.paths().hooksDirectory())
                    .as("the credential preflight changes no hooks")
                    .doesNotExist();
        } finally {
            PoreloContext.forgetAsked();
        }
    }

    @Test
    void anUndeclaredDestinationIsRefusedInTheLaunchsWordsBeforeTheVaultIsAsked() throws Exception {
        // Found on main's legs on 2026-11-10: the earlier check said the vault lacked it, where
        // the launch and CanStart name the undeclared destination.
        final PoreloContext context = context();
        Files.delete(context.paths().xdg().data().resolve("agents/porelo-agent-stub"));
        final Path project = dir.resolve("project.yml");
        Files.writeString(
                project,
                """
                project: {name: p, security_class: guarded}
                image: {from: 'ubuntu:24.04'}
                credentials: {forge: nowhere}
                """);
        final TaskLaunch launch =
                new TaskLaunch(
                        context,
                        new TaskLaunch.Request(
                                "work",
                                project,
                                null,
                                null,
                                null,
                                8,
                                null,
                                false,
                                false,
                                "deny",
                                true,
                                TaskMode.UNATTENDED,
                                "work",
                                null,
                                null,
                                1,
                                "p"));
        assertThat(launch.credentialsReady(sink, sink)).as("%s", text).isFalse();
        assertThat(launch.credentialCheckCode()).isEqualTo(69);
        assertThat(text.toString())
                .contains("the project names credential 'forge' for 'nowhere'")
                .doesNotContain("the vault holds no credential");
    }

    @Test
    void anUngrantedGrantIsRefusedInTheLaunchsWordsAndAskedOfAPerson() throws Exception {
        // Found on main's legs on 2026-11-10: the earlier check refused without raising the
        // question every interface sees.
        final PoreloContext context = context().asking(shown -> "test passphrase".toCharArray());
        Files.delete(context.paths().xdg().data().resolve("agents/porelo-agent-stub"));
        final Path project = dir.resolve("project.yml");
        Files.writeString(
                project,
                """
                project: {name: p, security_class: guarded}
                image: {from: 'ubuntu:24.04'}
                credentials: {forge: elsewhere}
                """);
        final Path destinations =
                Files.createDirectories(context.paths().xdg().data().resolve("destinations"));
        Files.writeString(
                destinations.resolve("elsewhere.yaml"),
                """
                name: elsewhere
                label: Elsewhere
                upstream: https://elsewhere.example
                auth_header: Authorization
                """);
        Files.createDirectories(context.vault().path().getParent());
        context.vault()
                .write(
                        Map.of(
                                "forge",
                                new org.fuin.porelo.vault.VaultEntry("fixture", "oauth-device")),
                        "test passphrase".toCharArray());
        try {
            assertThat(context.openerAsking(false, sink)).as("the test vault is open").isPresent();
            final TaskLaunch launch =
                    new TaskLaunch(
                            context,
                            new TaskLaunch.Request(
                                    "work",
                                    project,
                                    null,
                                    null,
                                    null,
                                    8,
                                    null,
                                    false,
                                    false,
                                    "deny",
                                    true,
                                    TaskMode.UNATTENDED,
                                    "work",
                                    null,
                                    null,
                                    1,
                                    "p"));
            assertThat(launch.credentialsReady(sink, sink)).as("%s", text).isFalse();
            assertThat(text.toString())
                    .contains("credential 'forge': nobody has granted it yet")
                    .contains("porelo vault authorize forge");
            assertThat(AuthorizationsNeeded.open(context))
                    .as("the refusal is a question every interface sees")
                    .anySatisfy(
                            asked ->
                                    assertThat(asked)
                                            .containsEntry("credential", "forge")
                                            .containsEntry("state", AuthorizationsNeeded.NEVER));
        } finally {
            PoreloContext.forgetAsked();
        }
    }

    @Test
    void repositoryCredentialsAreCheckedBeforeAnyHooks() throws Exception {
        // A repository-added credential must not bypass the early refusal or wait on an unrelated
        // login.
        final PoreloContext context = context();
        Files.delete(context.paths().xdg().data().resolve("agents/porelo-agent-stub"));
        final Path checkout = Files.createDirectories(dir.resolve("checkout"));
        Files.writeString(checkout.resolve("porelo.yml"), "credentials: {builds: elsewhere}\n");
        final DefaultProject defaults = new DefaultProject(context);
        defaults.add("https://repo.example.org/shop.git", "shop", checkout.toString());
        final var project = org.fuin.porelo.core.project.ProjectReader.read(defaults.file());
        final var own =
                RepositoryOwnSettings.of(context, defaults.file(), project.repository("shop"));
        assertThat(own).as("the repository contributes its declared credential").isNotNull();
        new RepositoryAllowance(context).allow("shop", own.asked());
        final TaskLaunch launch =
                new TaskLaunch(
                        context,
                        new TaskLaunch.Request(
                                "work",
                                defaults.file(),
                                null,
                                null,
                                null,
                                8,
                                null,
                                false,
                                false,
                                "deny",
                                true,
                                TaskMode.UNATTENDED,
                                "work",
                                null,
                                null,
                                1,
                                "shop"));
        assertThat(launch.credentialsReady(sink, sink))
                .as("a repository-added missing credential refuses in preflight")
                .isFalse();
        assertThat(launch.launch(sink, sink, running -> 0))
                .as("the actual launch applies the same credential check")
                .isEqualTo(69);
        assertThat(context.paths().hooksDirectory())
                .as("no hooks precede a repository credential refusal")
                .doesNotExist();
    }

    @Test
    void anUnknownAgentIsAnOrdinaryRefusal() throws Exception {
        // A caller error must not turn into an unexpected failure and exit 70.
        final PoreloContext context = context();
        Files.delete(context.paths().xdg().data().resolve("agents/porelo-agent-stub"));
        final TaskLaunch launch = launch(context);
        org.assertj.core.api.Assertions.assertThatCode(
                        () ->
                                assertThat(launch.launch(sink, sink, running -> 0))
                                        .as("unknown agent is refused with exit 69")
                                        .isEqualTo(69))
                .as("expected agent selection failures must be rendered, not thrown")
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(
                        () ->
                                assertThat(launch.credentialsReady(sink, sink))
                                        .as("daemon preflight refuses the unknown agent")
                                        .isFalse())
                .as("preflight keeps the same ordinary refusal")
                .doesNotThrowAnyException();
        assertThat(context.paths().hooksDirectory())
                .as("an unknown agent must not change hooks")
                .doesNotExist();
    }

    @Test
    void aMissingRecordedProviderCredentialRefusesWithoutAnInstalledAgent() throws Exception {
        // An absent adapter must not hide a recorded provider credential the resumed task still
        // needs.
        final PoreloContext context = context();
        Files.delete(context.paths().xdg().data().resolve("agents/porelo-agent-stub"));
        final TaskLaunch launch = launch(context);
        machine.answering(
                "ps",
                "porelo-p-work\tExited (0) 4 minutes ago\t1700000000\t1700000100\tp\tguarded\tp\t\n");
        new TaskProfile(
                        TaskProfile.VERSION,
                        AGENT,
                        TaskMode.AGENT,
                        null,
                        null,
                        "",
                        "deny",
                        null,
                        "anthropic",
                        null,
                        null,
                        0)
                .writeTo(
                        Files.createDirectories(
                                context.paths().tasks().taskRecord("porelo-p-work")));
        final TaskLaunch.Existing refused = launch.startExisting(sink, sink);
        assertThat(refused).as("the recorded provider credential is missing").isNotNull();
        assertThat(refused.code())
                .as("a missing recorded credential refuses the start")
                .isEqualTo(69);
        assertThat(launch.credentialRefusal())
                .as("the refusal is SignInNeeded, not an adapter installation requirement")
                .isEqualTo(new TaskLaunch.CredentialRefusal(AGENT, false, null));
        assertThat(context.paths().hooksDirectory())
                .as("a missing recorded credential must refuse before hook repair")
                .doesNotExist();
    }

    @Test
    void aStoredRecordedProviderCredentialAllowsResumeWithoutAnInstalledAgent() throws Exception {
        // Removing a host adapter must not strand an existing container whose credential is ready.
        final PoreloContext context = context().asking(shown -> "test passphrase".toCharArray());
        Files.delete(context.paths().xdg().data().resolve("agents/porelo-agent-stub"));
        final TaskLaunch launch = launch(context);
        machine.answering(
                "ps",
                "porelo-p-work\tExited (0) 4 minutes ago\t1700000000\t1700000100\tp\tguarded\tp\t\n");
        new TaskProfile(
                        TaskProfile.VERSION,
                        AGENT,
                        TaskMode.AGENT,
                        null,
                        null,
                        "",
                        "deny",
                        null,
                        "anthropic",
                        null,
                        null,
                        0)
                .writeTo(
                        Files.createDirectories(
                                context.paths().tasks().taskRecord("porelo-p-work")));
        Files.createDirectories(context.vault().path().getParent());
        context.vault()
                .write(
                        Map.of("anthropic", org.fuin.porelo.vault.VaultEntry.of("fixture")),
                        "test passphrase".toCharArray());
        try {
            assertThat(context.openerAsking(false, sink)).as("the test vault is open").isPresent();
            assertThat(launch.startExisting(sink, sink))
                    .as("the stopped task is handled")
                    .isNotNull();
            assertThat(machine.lines())
                    .as(
                            "a ready recorded credential lets resume reach the existing container: %s",
                            text)
                    .anyMatch(line -> line.contains("container inspect"));
            assertThat(launch.credentialRefusal()).as("the model credential is ready").isNull();
        } finally {
            PoreloContext.forgetAsked();
        }
    }

    @Test
    void aMissingOtherCredentialNeverWaitsForTheAgentsSignIn() throws Exception {
        // An agent sign-in cannot repair a missing forge or builds credential and must not delay
        // its refusal.
        final PoreloContext context = context();
        final Path project = dir.resolve("project.yml");
        Files.writeString(
                project,
                """
                project: {name: p, security_class: guarded}
                image: {from: 'ubuntu:24.04'}
                credentials: {builds: elsewhere}
                """);
        final TaskLaunch launch =
                new TaskLaunch(
                        context,
                        new TaskLaunch.Request(
                                "work",
                                project,
                                AGENT,
                                "anthropic",
                                null,
                                8,
                                null,
                                false,
                                false,
                                "deny",
                                true,
                                TaskMode.UNATTENDED,
                                "work",
                                null,
                                null,
                                1,
                                "p"));
        final AgentSignInRuns runs =
                new AgentSignInRuns(
                        new AgentSignInRuns.Machine() {
                            @Override
                            public AgentLogin.Result signIn(
                                    String agent,
                                    boolean force,
                                    boolean remote,
                                    java.util.function.Consumer<String> started) {
                                throw new AssertionError("a start must never initiate a sign-in");
                            }

                            @Override
                            public java.util.Optional<String> running(String agent) {
                                return java.util.Optional.of("running");
                            }

                            @Override
                            public AgentSignInRuns.State state(String agent, String provider) {
                                return AgentSignInRuns.State.NOT_SIGNED_IN;
                            }
                        },
                        Duration.ofSeconds(5),
                        Duration.ofMillis(10));
        launch.waitingForSignIn(runs, true);
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () ->
                        assertThat(launch.launch(sink, sink, running -> 0))
                                .as("the other credential refuses without waiting")
                                .isEqualTo(69));
        assertThat(launch.credentialRefusal())
                .as("the other credential is not SignInNeeded")
                .isNull();
        assertThat(dir.resolve("adapter-called"))
                .as("a missing non-model credential must refuse before starting an agent adapter")
                .doesNotExist();
        assertThat(context.paths().hooksDirectory())
                .as("nothing is changed for another missing credential")
                .doesNotExist();
    }
}
