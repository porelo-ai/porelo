package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostGatherFlowTest {
    @TempDir Path dir;
    private static final String LOCAL = "local-fixture-token";
    private static final String CENTRAL = "central-fixture-token";
    private final List<Command> commands = new ArrayList<>();
    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();
    private String fail = "";
    private String failureWords = "transport refused";
    private String relation = "ahead";
    private boolean secretInUrl;

    private HostGatherConfig config() {
        return new HostGatherConfig(
                "http://127.0.0.1:3001",
                "acme",
                Path.of("/etc/central.pem"),
                List.of(
                        new HostGatherConfig.Repository(
                                "first",
                                "https://central.example/acme/"
                                        + (secretInUrl ? CENTRAL : "first")
                                        + ".git"),
                        new HostGatherConfig.Repository(
                                "second", "https://central.example/acme/second.git")));
    }

    private CommandResult run(Command command) {
        commands.add(command);
        if (command.program().endsWith("/curl"))
            return new CommandResult(command, 0, "001f# service=git-receive-pack\n0000\n200", "");
        final var args = command.arguments();
        String output = "";
        int code = 0;
        if (args.contains("rev-parse"))
            output =
                    args.contains("FETCH_HEAD")
                            ? relation.equals("equal") ? "local\n" : "central\n"
                            : "local\n";
        if (args.contains("rev-list")) output = "2\n";
        if (args.contains("log")) output = "abc123 central commit\n";
        if (args.contains("merge-base"))
            code =
                    args.indexOf("FETCH_HEAD") < args.indexOf("HEAD")
                            ? relation.equals("local-ahead") ? 0 : 1
                            : relation.equals("ahead") ? 0 : 1;
        final boolean failing =
                switch (fail) {
                    case "clone" -> args.contains("clone");
                    case "fetch" -> args.contains("fetch");
                    case "local-push" ->
                            args.contains("push")
                                    && args.stream().anyMatch(a -> a.startsWith("http://"));
                    case "central-push" ->
                            args.contains("push")
                                    && args.stream().anyMatch(a -> a.startsWith("https://"));
                    case "compare" -> args.contains("merge-base");
                    case "negative-compare" -> args.contains("merge-base");
                    default -> false;
                };
        return new CommandResult(
                command,
                failing
                        ? fail.equals("compare") ? 128 : fail.equals("negative-compare") ? -1 : 1
                        : code,
                output,
                failing ? failureWords : "");
    }

    private int transfer(boolean pull) {
        return new HostGatherFlow(this::run, Path.of("/usr/bin/git"), dir)
                .run(
                        pull,
                        config(),
                        List.of(),
                        LOCAL.toCharArray(),
                        CENTRAL.toCharArray(),
                        new PrintWriter(out, true),
                        new PrintWriter(err, true));
    }

    @Test
    void plainPushSendsAllRepositoriesInOrderAndCleansScratch() throws Exception {
        // A gathered push must preserve both mains and account for every configured repository.
        assertThat(transfer(false)).isZero();
        assertThat(
                        commands.stream()
                                .filter(c -> c.arguments().contains("push"))
                                .map(c -> c.arguments().getLast())
                                .toList())
                .containsExactly("main:main", "main:main");
        assertThat(commands)
                .noneMatch(c -> c.arguments().stream().anyMatch(a -> a.startsWith("+")));
        assertThat(out.toString()).contains("pushed: first second");
        try (var children = Files.list(dir)) {
            assertThat(children.toList()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"equal", "local-ahead", "ahead", "diverged"})
    void pullWritesOnlyCentralMainWhenNeeded(String state) {
        // Pull must never overwrite approved local main or push anything to central.
        relation = state;
        assertThat(transfer(true)).isZero();
        final var pushes = commands.stream().filter(c -> c.arguments().contains("push")).toList();
        if (state.equals("equal") || state.equals("local-ahead")) assertThat(pushes).isEmpty();
        else {
            assertThat(pushes).hasSize(2);
            assertThat(pushes)
                    .allSatisfy(
                            c ->
                                    assertThat(c.arguments())
                                            .contains("+FETCH_HEAD:refs/heads/central/main")
                                            .noneMatch(a -> a.startsWith("https://")));
        }
    }

    @Test
    void nonFastForwardStopsAfterBringingBackCentralCommits() {
        // A central rejection must not force main or continue pushing other repositories.
        fail = "central-push";
        failureWords = "[rejected] main -> main (fetch first)";
        assertThat(transfer(false)).isEqualTo(1);
        assertThat(commands).filteredOn(c -> c.arguments().contains("push")).hasSize(2);
        assertThat(commands)
                .anySatisfy(
                        c ->
                                assertThat(c.arguments())
                                        .contains("FETCH_HEAD:refs/heads/central/main"));
        assertThat(commands)
                .filteredOn(c -> c.program().endsWith("/git"))
                .noneMatch(c -> c.arguments().stream().anyMatch(a -> a.contains("second.git")));
        assertThat(err.toString()).contains("merge it in a task", "second");
    }

    @ParameterizedTest
    @ValueSource(strings = {"clone", "fetch", "local-push", "compare", "negative-compare"})
    void pullFailuresStopBeforeLaterRepositoriesAndRemoveScratch(String stage) throws Exception {
        // A failed read or write must stop the operation and leave no private clone behind.
        fail = stage;
        assertThat(transfer(true)).isEqualTo(1);
        assertThat(commands)
                .noneMatch(c -> c.arguments().stream().anyMatch(a -> a.contains("second.git")));
        try (var children = Files.list(dir)) {
            assertThat(children.toList()).isEmpty();
        }
    }

    @Test
    void credentialsReachOnlyTheGitRequestThatNeedsThem() {
        // Credentials must stay out of argv, logs and unrelated child environments.
        assertThat(transfer(false)).isZero();
        for (var command : commands) {
            if (command.program().endsWith("/curl")) continue;
            assertThat(command.arguments().toString() + command)
                    .doesNotContain(LOCAL, CENTRAL, basic(LOCAL), basic(CENTRAL));
            assertThat(command.inheritsEnvironment()).isFalse();
            assertThat(command.environment())
                    .containsEntry("GIT_CONFIG_GLOBAL", "/dev/null")
                    .containsEntry("GIT_CONFIG_NOSYSTEM", "1")
                    .containsEntry("GIT_TERMINAL_PROMPT", "0");
            final String env = command.environment().toString();
            if (command.arguments().stream().anyMatch(a -> a.startsWith("https://"))) {
                assertThat(env)
                        .contains(basic(CENTRAL), "GIT_SSL_CAINFO", "http.https://central.example/")
                        .doesNotContain(basic(LOCAL));
            } else if (command.arguments().stream().anyMatch(a -> a.startsWith("http://")))
                assertThat(env)
                        .contains(basic(LOCAL), "http.http://127.0.0.1:3001/")
                        .doesNotContain(basic(CENTRAL));
            else assertThat(env).doesNotContain(basic(LOCAL), basic(CENTRAL));
            assertThat(command.arguments())
                    .contains("core.hooksPath=/dev/null", "credential.helper=");
        }
    }

    @Test
    void everyTokenFormIsRedactedEvenInCommitLogsAndFailures() {
        // A hostile forge's git diagnostic must never reveal either raw or encoded credential.
        fail = "central-push";
        failureWords =
                LOCAL
                        + " "
                        + CENTRAL
                        + " Authorization: Basic "
                        + basic(LOCAL)
                        + " "
                        + basic(CENTRAL)
                        + "\0";
        assertThat(transfer(false)).isEqualTo(1);
        assertThat(err.toString() + out)
                .doesNotContain(LOCAL, CENTRAL, basic(LOCAL), basic(CENTRAL), "\0");
        assertThat(err.toString()).contains("redacted");
    }

    @Test
    void progressCannotRepeatACredentialInTheConfiguredAddress() {
        // A credential in an address must not reach argv or progress output.
        secretInUrl = true;
        assertThat(transfer(false)).isEqualTo(65);
        assertThat(commands).isEmpty();
        assertThat(out.toString() + err).doesNotContain(CENTRAL);
    }

    private static String basic(String token) {
        return Base64.getEncoder()
                .encodeToString(
                        ("x-access-token:" + token)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void gitCannotStartAutomaticBackgroundMaintenance() {
        // Background maintenance must not race removal of a private clone.
        assertThat(transfer(false)).isZero();
        final var git =
                commands.stream()
                        .filter(c -> c.program().endsWith("/git"))
                        .findFirst()
                        .orElseThrow();
        final var argv = git.arguments();
        final int clone = argv.indexOf("clone");
        final var configArgs = new java.util.ArrayList<>(argv.subList(0, clone));
        configArgs.addAll(java.util.List.of("config", "--get", "maintenance.auto"));
        final var result =
                new org.fuin.porelo.core.process.ProcessCommandRunner()
                        .run(
                                org.fuin.porelo.core.process.Command.of(configArgs)
                                        .withChosenEnvironment(
                                                java.util.Map.of(
                                                        "PATH",
                                                        "/usr/bin:/bin",
                                                        "GIT_CONFIG_GLOBAL",
                                                        "/dev/null",
                                                        "GIT_CONFIG_NOSYSTEM",
                                                        "1")));
        assertThat(result.successful()).isTrue();
        assertThat(result.trimmedOutput()).isEqualTo("false");
        configArgs.set(configArgs.size() - 1, "gc.auto");
        assertThat(
                        new org.fuin.porelo.core.process.ProcessCommandRunner()
                                .run(
                                        org.fuin.porelo.core.process.Command.of(configArgs)
                                                .withChosenEnvironment(git.environment()))
                                .trimmedOutput())
                .isEqualTo("0");
    }
}
