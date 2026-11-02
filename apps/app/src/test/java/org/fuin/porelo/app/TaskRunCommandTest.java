package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for {@link TaskRunCommand}.
 *
 * <p>Everything that touches the machine goes through {@link PoreloContext}. An earlier version of
 * this test had none, so running it built images, started containers and then replaced the test
 * process with a shell.
 */
@org.junit.jupiter.api.extension.ExtendWith({OwnPorelo.class, NoPodman.class})
class TaskRunCommandTest {

    private static final String MINIMAL =
            """
            project:
              name: "uc"
              security_class: "guarded"
            image:
              base_image: "ubuntu:24.04"
            """;

    /** What the attached session exits with. A shell reports its last command's status. */
    private int execExit;

    /** Whether the attached session ends without returning, as it does when it is signalled. */
    private boolean execDies;

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    /**
     * podman gives back the id of what it created, which Porelo records as what makes it its task.
     */
    private final FakeCommandRunner runner =
            new StoppingCommandRunner()
                    .answering("{{.State.Status}}", "")
                    .answering(" create ", "0123456789abcdef");

    private final List<List<String>> execCalls = new ArrayList<>();

    private Path root;

    /**
     * Serves only the definition a preview needs, without running a task or reading a credential.
     */
    public static final class PreviewAgent {

        /**
         * Serves a test agent's definition over the real agent protocol.
         *
         * @param args Definition file, serve verb and socket path.
         * @throws IOException If the definition cannot be read.
         */
        public static void main(String[] args) throws IOException {
            final var definition =
                    org.fuin.porelo.agent.api.AgentDefinitionReader.read(
                            new java.io.StringReader(Files.readString(Path.of(args[0]))), args[0]);
            try (var server =
                    new org.fuin.porelo.wire.varlink.VarlinkServer(
                            Path.of(args[2]), org.fuin.porelo.agent.api.AgentProtocol.INTERFACE)) {
                server.method(
                        "Describe",
                        (parameters, replies) ->
                                replies.last(
                                        java.util.Map.of(
                                                "protocolVersion",
                                                org.fuin.porelo.agent.api.AgentProtocol.VERSION,
                                                "definition",
                                                org.fuin.porelo.agent.api.AgentDefinitionJson.write(
                                                        definition))));
                // Says what it was told, as the command it would run and the file it would write.
                server.method(
                        "BuildCommand",
                        (parameters, replies) ->
                                replies.last(
                                        java.util.Map.of(
                                                "command",
                                                List.of(
                                                        "preview",
                                                        String.valueOf(parameters.get("prompt")),
                                                        "subagents=" + told(parameters)))));
                server.method(
                        "ContainerSetup",
                        (parameters, replies) -> {
                            if (Files.exists(Path.of(args[0] + ".setup-fails"))) {
                                throw new org.fuin.porelo.agent.api.AgentException(
                                        "the setup broke");
                            }
                            // Beside its definition rather than in a container, which a unit test
                            // has none of.
                            try {
                                Files.writeString(Path.of(args[0] + ".told"), told(parameters));
                            } catch (IOException ex) {
                                throw new java.io.UncheckedIOException(ex);
                            }
                            replies.last(
                                    java.util.Map.of(
                                            "files",
                                            Files.exists(Path.of(args[0] + ".places"))
                                                    ? List.of(
                                                            placed("/home/agent/.preview.json"),
                                                            placed(
                                                                    parameters.get("workspace")
                                                                            + "/.preview/settings.json"))
                                                    : List.of()));
                        });
                System.out.println("ready " + args[2]);
                System.out.flush();
                server.run();
            }
        }
    }

    private static java.util.Map<String, Object> placed(String path) {
        return java.util.Map.of("path", path, "content", "{}", "ownerOnly", Boolean.FALSE);
    }

    private static String told(java.util.Map<String, Object> parameters) {
        return String.valueOf(
                org.fuin.porelo.agent.api.SubagentLimit.wholeNumber(parameters.get("subagents")));
    }

    private void previewAgent(PoreloContext context, String name) throws IOException {
        previewAgent(context, name, "");
    }

    private void previewAgent(PoreloContext context, String name, String declared)
            throws IOException {
        final Path agents = Files.createDirectories(context.paths().xdg().data().resolve("agents"));
        final Path definition = agents.resolve(name + ".yaml");
        Files.writeString(
                definition,
                """
                name: %s
                binary: preview
                git_identity: { name: Preview, email: preview@example.com }
                headless: { prompt_flag: "-p" }
                allowed_domains: [agent.example.com]
                refused_domains: [raw.githubusercontent.com]
                provider:
                  dialect: preview
                  default: preview
                  endpoint: url
                  base_url_env: PREVIEW_BASE_URL
                """
                                .formatted(name)
                        + declared);
        final Path executable =
                agents.resolve(org.fuin.porelo.agent.api.AgentDirectory.PREFIX + name);
        Files.writeString(
                executable,
                "#!/bin/sh\nexec "
                        + shellArgument(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString())
                        + " -cp "
                        + shellArgument(System.getProperty("java.class.path"))
                        + " "
                        + shellArgument(PreviewAgent.class.getName())
                        + " "
                        + shellArgument(definition.toString())
                        + " \"$@\"\n");
        Files.setPosixFilePermissions(
                executable, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        final Path providers =
                Files.createDirectories(context.paths().xdg().data().resolve("providers"));
        Files.writeString(
                providers.resolve("preview.yaml"),
                """
                name: preview
                upstream: https://provider.example.com
                dialects: { preview: "" }
                auth_header: { _default: Authorization }
                auth_prefix: { _default: "Bearer " }
                """);
    }

    private static String shellArgument(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private PoreloContext previewContext(Path dir) throws IOException {
        final PoreloContext context = context(dir, false);
        final XdgPaths xdg = context.paths().xdg();
        // Relative socket paths fit the Unix limit even when the checkout's path is long.
        final XdgPaths shortSockets =
                new XdgPaths(
                        xdg.config(),
                        xdg.data(),
                        xdg.state(),
                        Path.of("").toAbsolutePath().relativize(xdg.runtime()),
                        xdg.home());
        return new PoreloContext(
                context.runner(),
                new PoreloPaths(shortSockets, dir.resolve("bin")),
                context.exec(),
                context.environment());
    }

    private PoreloContext refusingProviderContext(Path dir, boolean stored) throws IOException {
        final PoreloContext context =
                previewContext(dir).asking(shown -> "test-passphrase".toCharArray());
        previewAgent(context, "preview");
        Files.writeString(
                context.paths().xdg().data().resolve("providers/preview.yaml"),
                """
                token_env: { _default: PREVIEW_TOKEN }
                unbrokerable:
                  api-key: "This provider takes a sign-in; use the key provider for an API key"
                """,
                java.nio.file.StandardOpenOption.APPEND);
        hookBinaries(dir);
        new org.fuin.porelo.runtime.HookInstaller(
                        context.paths().hooksDirectory(), context.paths().binaryDirectory())
                .install();
        context.vault()
                .write(
                        stored
                                ? java.util.Map.of(
                                        "preview",
                                        new org.fuin.porelo.vault.VaultEntry("test-key", "api-key"))
                                : java.util.Map.of(),
                        "test-passphrase".toCharArray());
        assertThat(context.openerAsking(false, new PrintWriter(err)))
                .as("the fixture opens its own vault without depending on the machine's keyring")
                .isPresent();
        return context;
    }

    private int unattendedStart(PoreloContext context, Path dir, String... options)
            throws IOException {
        final List<String> args =
                new ArrayList<>(
                        List.of(
                                "-p",
                                projectFile(dir, MINIMAL),
                                "--agent",
                                "preview",
                                "--prompt",
                                "do the work",
                                "--no-gate"));
        args.addAll(List.of(options));
        return startAt(context, false, args.toArray(String[]::new));
    }

    private void refusesBeforeCreatingATask(int code) {
        assertThat(code)
                .as("an unauthenticated unattended start is refused: %s", err)
                .isEqualTo(69);
        assertThat(runner.lines())
                .as("a credential refusal must leave no image or container")
                .noneMatch(line -> line.contains("podman build") || line.contains("podman create"));
        assertThat(execCalls).as("a refused start must not enter a task").isEmpty();
    }

    @Test
    void anEmptyVaultDoesNotInventAStoredKindForAnUnattendedStart(@TempDir Path dir)
            throws IOException {
        // Inventing a refused kind sends the operator to a different provider instead of signing
        // in.
        final PoreloContext context = refusingProviderContext(dir, false);
        try {
            refusesBeforeCreatingATask(unattendedStart(context, dir));
            assertThat(err.toString())
                    .as("the missing credential reaches the existing availability check")
                    .contains("the vault holds no credential for 'preview'")
                    .doesNotContain("stored");
        } finally {
            PoreloContext.forgetAsked();
        }
    }

    @Test
    void anExplicitRefusedKindIsRejectedWithoutInventingAStoredEntry(@TempDir Path dir)
            throws IOException {
        // An explicit incompatible kind must be refused before a task can start without
        // authentication.
        final PoreloContext context = refusingProviderContext(dir, false);
        try {
            refusesBeforeCreatingATask(
                    unattendedStart(context, dir, "--credential-type", "api-key"));
            assertThat(err.toString())
                    .as("the refusal identifies the requested kind and the provider's remedy")
                    .contains(
                            "the 'api-key' credential asked for 'preview' cannot be used.",
                            "This provider takes a sign-in; use the key provider for an API key.")
                    .doesNotContain("stored", "the vault holds no credential");
        } finally {
            PoreloContext.forgetAsked();
        }
    }

    @Test
    void aStoredRefusedKindStillPreventsAnUnattendedStart(@TempDir Path dir) throws IOException {
        // A stored incompatible credential must never reach a container that cannot authenticate.
        final PoreloContext context = refusingProviderContext(dir, true);
        try {
            refusesBeforeCreatingATask(unattendedStart(context, dir));
            assertThat(err.toString())
                    .as("the stored-kind refusal keeps its existing explanation")
                    .contains(
                            "the 'api-key' credential stored for 'preview' cannot be used.",
                            "This provider takes a sign-in; use the key provider for an API key.")
                    .doesNotContain("asked for", "the vault holds no credential");
        } finally {
            PoreloContext.forgetAsked();
        }
    }

    @Test
    void anExplicitRefusedKindOverridesTheStoredSignInWithoutCallingItStored(@TempDir Path dir)
            throws IOException {
        // An incompatible override must be refused without blaming a usable stored sign-in.
        final PoreloContext context = refusingProviderContext(dir, true);
        try {
            context.vault()
                    .write(
                            java.util.Map.of(
                                    "preview",
                                    new org.fuin.porelo.vault.VaultEntry("test-sign-in", "oauth")),
                            "test-passphrase".toCharArray());

            refusesBeforeCreatingATask(
                    unattendedStart(context, dir, "--credential-type", "api-key"));

            assertThat(err.toString())
                    .as(
                            "both refusal lines describe the explicit override rather than the stored sign-in")
                    .contains(
                            "the 'api-key' credential asked for 'preview' cannot be used.",
                            "This provider takes a sign-in; use the key provider for an API key.",
                            "--credential-type selects the kind for one run")
                    .doesNotContain("stored");
        } finally {
            PoreloContext.forgetAsked();
        }
    }

    @Test
    void aRefusedStoredKindIsCheckedAfterTheStartOpensTheVault(@TempDir Path dir)
            throws IOException {
        // Unlocking must not let an incompatible credential bypass the guard before the build.
        final List<String> prompts = new ArrayList<>();
        final PoreloContext context =
                refusingProviderContext(dir, true)
                        .asking(
                                shown -> {
                                    prompts.add(shown);
                                    return "test-passphrase".toCharArray();
                                });
        final var keyring =
                new org.fuin.porelo.vault.KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            PoreloContext.forgetAsked();
            assertThat(context.readableCredentials())
                    .as("the vault must be shut when the start begins")
                    .isEmpty();
            runner.failing(" build ", 1, "the credential guard must refuse before building");

            final int code = unattendedStart(context, dir);

            assertThat(prompts).as("the start opens the vault exactly once").hasSize(1);
            assertThat(err.toString())
                    .as("the newly readable stored kind is refused before the build")
                    .contains(
                            "the 'api-key' credential stored for 'preview' cannot be used.",
                            "This provider takes a sign-in; use the key provider for an API key.",
                            "--credential-type overrides the stored kind for one run");
            refusesBeforeCreatingATask(code);
        } finally {
            PoreloContext.forgetAsked();
            if (org.fuin.porelo.vault.KernelKeyring.available()) {
                keyring.forget();
            }
        }
    }

    private PoreloContext context(Path dir, boolean hooksInstalled) throws IOException {
        return context(dir, hooksInstalled, name -> null);
    }

    private PoreloContext context(
            Path dir, boolean hooksInstalled, java.util.function.UnaryOperator<String> environment)
            throws IOException {
        root = dir;
        // A machine Porelo will run on. Without this the fake answers nothing to 'podman version',
        // which is indistinguishable from a podman too old to support - and every run refuses.
        runner.answering("podman version", "5.8.1");
        // No session here ever writes the marker, so the question "did the shell end" has to answer
        // yes for the teardown to run at all. Without this the fake answers every command with
        // success anyway - which happens to be the right answer here, and is pinned rather than
        // relied on.
        runner.answering("test -f", "");
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        final PoreloPaths paths = new PoreloPaths(xdg, dir.resolve("bin"));
        if (hooksInstalled) {
            Files.createDirectories(dir.resolve("bin"));
            for (final String name :
                    List.of("porelo-hook-nft", "porelo-hook-supervisor", "porelo-hook-reader")) {
                final Path binary = dir.resolve("bin").resolve(name);
                Files.writeString(binary, "#!/bin/sh\n");
                binary.toFile().setExecutable(true);
            }
            // Binaries alone are not an installation: podman has to be told to run them, which is
            // what 'porelo setup' writes and what a task run now insists on.
            new org.fuin.porelo.runtime.HookInstaller(
                            paths.hooksDirectory(), paths.binaryDirectory())
                    .install();
        }
        final var git = new org.fuin.porelo.core.process.ProcessCommandRunner();
        return new PoreloContext(
                command ->
                        command.arguments().getFirst().equals("git")
                                        && command.arguments().size() > 2
                                        && command.arguments().get(1).equals("-C")
                                        && Path.of(command.arguments().get(2))
                                                .startsWith(paths.xdg().data().resolve("mirrors"))
                                ? git.run(
                                        command.withEnvironment(
                                                java.util.Map.of(
                                                        "GIT_CONFIG_GLOBAL",
                                                        "/dev/null",
                                                        "GIT_CONFIG_NOSYSTEM",
                                                        "1")))
                                : runner.run(command),
                paths,
                arguments -> {
                    execCalls.add(arguments);
                    if (execDies) {
                        // Stands in for the session dying under the command. A signal cannot be
                        // sent in a unit test, but it lands on the same branch: the line recording
                        // a clean exit is never reached, so the teardown runs with the value it was
                        // armed with.
                        throw new IllegalStateException("the session died");
                    }
                    return execExit;
                },
                environment);
    }

    /** The hook binaries without the descriptors: what 'apt install' leaves behind. */
    private void hookBinaries(Path dir) throws IOException {
        Files.createDirectories(dir.resolve("bin"));
        for (final String name :
                List.of("porelo-hook-nft", "porelo-hook-supervisor", "porelo-hook-reader")) {
            final Path binary = dir.resolve("bin").resolve(name);
            Files.writeString(binary, "#!/bin/sh\n");
            binary.toFile().setExecutable(true);
        }
    }

    /**
     * Runs the CLI, naming the repository for a task start that did not name one itself.
     *
     * <p>Every project here is the one-repository project called {@code uc}, and starting a task
     * always names a repository - there is no default, not even then. Added by the helper rather
     * than by thirty call sites, each of which is about something else; the requirement itself is
     * measured by {@link #startingATaskWithoutNamingARepositoryIsRefused}.
     */
    private int execute(PoreloContext context, String... args) {
        final CommandLine cmd = PoreloCli.commandLine(context);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        final List<String> all = new java.util.ArrayList<>(List.of(args));
        if (all.size() > 1
                && "task".equals(all.get(0))
                && "start".equals(all.get(1))
                && !all.contains("--repository")
                && !all.contains("-r")) {
            all.add("--repository");
            all.add("uc");
        }
        // Named 'shell' unless a test names it: an unnamed start takes its repository's name, which
        // is measured on its own below, and every other test here is about something else.
        if (all.size() > 1
                && "task".equals(all.get(0))
                && "start".equals(all.get(1))
                && (all.size() == 2 || all.get(2).startsWith("-"))) {
            all.add(2, "shell");
        }
        return cmd.execute(all.toArray(new String[0]));
    }

    /**
     * Runs {@code task start} on the task {@code shell} of {@code uc}, as a person at a terminal or
     * as a script, which the test says since it has no terminal.
     */
    private int startAt(PoreloContext context, boolean terminal, String... args) {
        final CommandLine cmd = PoreloCli.commandLine(context);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        ((TaskRunCommand)
                                cmd.getSubcommands()
                                        .get("task")
                                        .getSubcommands()
                                        .get("start")
                                        .getCommand())
                        .terminal =
                () -> terminal;
        final List<String> all =
                new ArrayList<>(List.of("task", "start", "shell", "--repository", "uc"));
        all.addAll(List.of(args));
        return cmd.execute(all.toArray(new String[0]));
    }

    private static final String RUNNING_SHELL =
            "porelo-uc-shell\tUp 4 minutes\t1700000000\t0\tuc\tguarded\tuc\t\n";

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"shell", "porelo-uc-shell"})
    void aRunCannotChangeTheModelOfAnExistingTask(String taskName, @TempDir Path dir)
            throws IOException {
        // A changed model must be refused before the stopped container or its helpers start.
        final PoreloContext context = context(dir, true);
        runner.answering("ps", RUNNING_SHELL.replace("Up 4 minutes", "Exited (0) 4 minutes ago"));
        final Path state = context.paths().tasks().containerState("porelo-uc-shell");
        Files.createDirectories(state);
        Files.writeString(
                state.resolve(org.fuin.porelo.wire.TaskProfile.FILE),
                "{\"version\":5,\"agent\":\"example\",\"mode\":\"agent\",\"model\":\"original-model\"}");

        assertThat(
                        execute(
                                context,
                                "task",
                                "start",
                                taskName,
                                "-p",
                                projectFile(dir, MINIMAL),
                                "--model",
                                "other-model",
                                "--detach"))
                .as("a different model is a refused start, said: %s", err)
                .isEqualTo(64);
        assertThat(err.toString())
                .as("the refusal identifies the model fixed at start")
                .contains("original-model");
        assertThat(runner.lines().stream().filter(line -> line.contains("podman start")))
                .as("the task must stay stopped after a model mismatch")
                .isEmpty();
        assertThat(execCalls).as("a refused run cannot enter the task").isEmpty();
    }

    @Test
    void aStartAtATerminalGoesIntoARunningTaskAsAttachDoes(@TempDir Path dir) throws IOException {
        // After a reboot the container is back and its agent's session is gone; whoever typed
        // 'start' wants to work in it, and was sent to a second command instead.
        final PoreloContext context = context(dir, true);
        runner.answering("ps", RUNNING_SHELL);

        assertThat(startAt(context, true, "-p", projectFile(dir, MINIMAL)))
                .as("said: %s", err)
                .isZero();

        assertThat(err.toString()).doesNotContain("already running");
        assertThat(execCalls).as("the session it went into").hasSize(1);
        assertThat(String.join(" ", execCalls.get(0))).contains("porelo-uc-shell");
    }

    @Test
    void aStartOnARunningTaskNamesEachOptionThatWouldNoLongerTakeEffect(@TempDir Path dir)
            throws IOException {
        final PoreloContext context = context(dir, true);
        runner.answering("ps", RUNNING_SHELL);

        assertThat(
                        startAt(
                                context,
                                true,
                                "-p",
                                projectFile(dir, MINIMAL),
                                "--prompt",
                                "x",
                                "--model",
                                "m"))
                .as("said: %s", err)
                .isEqualTo(64);

        assertThat(err.toString())
                .contains("porelo-uc-shell is already running; --prompt, --model take effect only")
                .contains("'porelo task stop porelo-uc-shell'");
        assertThat(execCalls).as("nothing gone into").isEmpty();
    }

    @Test
    void aStartWithNoInputOnARunningTaskSaysItRunsEvenWithOptionsAndEvenAtATerminal(
            @TempDir Path dir) throws IOException {
        // A playbook's second run starts each task again with --detach --no-input and takes 65 as
        // "already so": the options are looked at only on the path that goes in.
        final PoreloContext context = context(dir, true);
        runner.answering("ps", RUNNING_SHELL);

        assertThat(
                        startAt(
                                context,
                                true,
                                "-p",
                                projectFile(dir, MINIMAL),
                                "--detach",
                                "--no-input"))
                .as("said: %s", err)
                .isEqualTo(65);
        assertThat(err.toString()).contains("porelo-uc-shell is already running");
        assertThat(execCalls).isEmpty();
    }

    @Test
    void aScriptsStartOnARunningTaskSaysItRunsAndEndsAsBefore(@TempDir Path dir)
            throws IOException {
        final PoreloContext context = context(dir, true);
        runner.answering("ps", RUNNING_SHELL);

        assertThat(startAt(context, false, "-p", projectFile(dir, MINIMAL)))
                .as("said: %s", err)
                .isEqualTo(65);
        assertThat(err.toString())
                .contains("porelo-uc-shell is already running")
                .contains("porelo task attach porelo-uc-shell");
        assertThat(execCalls).isEmpty();
    }

    @Test
    void aStartOnARunningTaskWithHooksAnUpgradeLeftBehindStillFindsItRunning(@TempDir Path dir)
            throws IOException {

        // Hooks to repair hid the task from the start, which went on to make it again beside the
        // one running: a second broker took over its records, and the create that failed on the
        // taken name then stopped the running task.
        final PoreloContext context = context(dir, true);
        runner.answering("ps", RUNNING_SHELL);
        final Path descriptor =
                dir.resolve("config/containers/oci/hooks.d/porelo-hook-nft-poststop.json");
        Files.writeString(descriptor, "{\"version\":\"1.0.0\",\"hook\":{}}");

        assertThat(startAt(context, false, "-p", projectFile(dir, MINIMAL)))
                .as("said: %s", err)
                .isEqualTo(65);
        assertThat(err.toString()).contains("porelo-uc-shell is already running");
        assertThat(runner.lines())
                .as("nothing is made for it again: %s", runner.lines())
                .noneMatch(line -> line.contains("podman create"))
                .noneMatch(line -> line.contains("podman stop"));
        assertThat(Files.readString(descriptor))
                .as("and its hooks are brought up to date all the same")
                .contains("porelo-hook-nft");
    }

    @Test
    void startingATaskWithoutNamingARepositoryIsRefused(@TempDir Path dir) throws IOException {

        final CommandLine cmd = PoreloCli.commandLine(context(dir, true));
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));

        // No --repository, and the project has exactly one. Porelo still does not pick: a project
        // that grew a second repository would otherwise silently change what this command does.
        final int code =
                cmd.execute(
                        "task",
                        "start",
                        "--attach",
                        "shell",
                        "--detach",
                        "-p",
                        projectFile(dir, MINIMAL));

        assertThat(code).isEqualTo(64);
        // And the refusal is one somebody can act on without opening the file.
        assertThat(err.toString()).contains("--repository").contains("uc");
        // Unnamed, the task has no name yet: a hint that put one in suggested 'shell', a name
        // nobody chose.
        assertThat(err.toString()).doesNotContain("task start shell");
    }

    @Test
    void anUnnamedStartIsNamedAfterItsRepositoryAndSaysSo(@TempDir Path dir) throws IOException {

        final CommandLine cmd = PoreloCli.commandLine(context(dir, true));
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));

        cmd.execute(
                "task",
                "start",
                "--attach",
                "shell",
                "--detach",
                "-p",
                projectFile(dir, MINIMAL),
                "--repository",
                "uc");

        assertThat(out.toString()).contains("task           uc - named after its repository");
        assertThat(String.join("\n", runner.lines()))
                .contains("porelo-uc-uc")
                .doesNotContain("porelo-uc-shell");
    }

    @Test
    void aDryRunNeedsNoRepositoryBecauseItStartsNothing(@TempDir Path dir) throws IOException {

        // The refusal protects against starting a task on a guessed repository; a dry run starts
        // nothing. What it reports without one is the project-level plan - the project's own block,
        // which is true of every repository because a repository only ever ADDS to it.
        final CommandLine cmd = PoreloCli.commandLine(context(dir, true));
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));

        final int code =
                cmd.execute("task", "start", "-p", projectFile(dir, TWO_REPOSITORIES), "--dry-run");

        assertThat(code).isZero();
        // And it says which plan this is, or a project-level answer reads as a task's.
        assertThat(out.toString()).contains("every repository");
    }

    @Test
    void namingARepositoryTheProjectDoesNotHaveIsRefusedByName(@TempDir Path dir)
            throws IOException {

        final int code =
                execute(
                        context(dir, true),
                        "task",
                        "start",
                        "-p",
                        projectFile(dir, MINIMAL),
                        "--repository",
                        "nowhere",
                        "--dry-run");

        assertThat(code).isEqualTo(64);
        assertThat(err.toString()).contains("nowhere").contains("uc");
    }

    /**
     * Writes a project file and records where it is, then answers its NAME.
     *
     * <p>Commands take a project name now, and a name resolves through what this machine knows: the
     * verified clone of a followed project, then where a task last read one. A fixture that only
     * wrote a file into a temporary directory would be a project this machine has never heard of -
     * which is exactly what the refusal is for, and not what these tests are about.
     */
    private String projectFile(Path dir, String content) throws IOException {
        final Path file = dir.resolve("project.yml");
        Files.writeString(file, content);
        // Read out of the text rather than through the reader: one of these fixtures is a file the
        // reader refuses on purpose, and it still has to be a project this machine can name.
        final java.util.regex.Matcher named =
                java.util.regex.Pattern.compile("(?m)^\\s+name:\\s*\"?([a-z0-9-]+)\"?")
                        .matcher(content);
        final String name = named.find() ? named.group(1) : "uc";
        Files.createDirectories(dir.resolve("data/porelo/projects"));
        Files.writeString(
                dir.resolve("data/porelo/projects").resolve(name), file.toAbsolutePath() + "\n");
        return name;
    }

    private static final String WITH_PARAMETERS =
            MINIMAL
                    + """
                    parameters:
                      type:
                        values: [feature, bugfix]
                        default: feature
                      ticket:
                        pattern: "[A-Z]+-[0-9]+"
                        env: "TASK_TICKET"
                      label:
                        required: false
                    """;

    @Test
    void eachValueIsSettledFromTheWaysItWasGivenAndShownWithItsOrigin(@TempDir Path dir)
            throws IOException {

        // The command line wins over a file, a file over the shell, the shell over a default.
        final Path file = dir.resolve("values.yml");
        Files.writeString(file, "ticket: FILE-1\nlabel: from-the-file\n");
        final int code =
                execute(
                        context(dir, true, name -> "TASK_TICKET".equals(name) ? "ENV-1" : null),
                        "task",
                        "start",
                        "-p",
                        projectFile(dir, WITH_PARAMETERS),
                        "--set-env",
                        "--set-file",
                        file.toString(),
                        "--set",
                        "ticket=CLI-1",
                        "--dry-run");

        assertThat(code).as(err.toString()).isZero();
        assertThat(out.toString())
                .contains("value          type=feature (default)")
                .contains("value          ticket=CLI-1 (command line)")
                .contains("value          label=from-the-file (file " + file + ")");
    }

    private static final String WITH_BRANCH =
            WITH_PARAMETERS
                    + """
                    branch:
                      template: "{type}/porelo/{task}"
                      pattern: "^(feature|bugfix)/porelo/[a-z0-9-]+$"
                    """;

    @Test
    void aTasksBranchIsMadeByTheProjectsSchemeAndShownBeforeAnythingExists(@TempDir Path dir)
            throws IOException {
        final int code =
                execute(
                        context(dir, true),
                        "task",
                        "start",
                        "-p",
                        projectFile(dir, WITH_BRANCH),
                        "--set",
                        "type=bugfix",
                        "--dry-run");

        assertThat(code).as(err.toString()).isZero();
        assertThat(out.toString()).contains("branch         bugfix/porelo/shell");
    }

    @Test
    void aProjectWithoutABlockNamesItsTasksBranchAsToday(@TempDir Path dir) throws IOException {
        final int code =
                execute(
                        context(dir, true),
                        "task",
                        "start",
                        "-p",
                        projectFile(dir, WITH_PARAMETERS),
                        "--dry-run");

        assertThat(code).as(err.toString()).isZero();
        assertThat(out.toString()).contains("branch         porelo/shell");
    }

    @Test
    void aBranchThePatternRefusesCreatesNothing(@TempDir Path dir) throws IOException {
        final int code =
                execute(
                        context(dir, true),
                        "task",
                        "start",
                        "-p",
                        projectFile(dir, WITH_BRANCH),
                        "--branch",
                        "main");

        assertThat(code).isEqualTo(64);
        assertThat(err.toString())
                .contains("'main' is not one the project's branch.pattern")
                .contains("nothing was created");
        assertThat(runner.invocations())
                .as("no container made")
                .noneMatch(command -> command.arguments().contains("create"));
    }

    @Test
    void aTaskRecordsItsBranchAndABranchAnotherTaskHoldsIsRefused(@TempDir Path dir)
            throws IOException {

        // Two tasks of one repository on one branch would pass their work on onto one, and one
        // would move the other's.
        final PoreloContext context = context(dir, true);
        execute(
                context,
                "task",
                "start",
                "--attach",
                "shell",
                "--detach",
                "-p",
                projectFile(dir, WITH_BRANCH),
                "--set",
                "type=bugfix");
        assertThat(
                        Files.readString(
                                dir.resolve("state/porelo/tasks/porelo-uc-shell")
                                        .resolve(org.fuin.porelo.wire.TaskProfile.FILE)))
                .contains("\"forgeBranch\":\"bugfix/porelo/shell\"");

        runner.answering(
                "ps", "porelo-uc-shell\tUp 4 minutes\t1700000000\t1700000100\tuc\tguarded\tuc\t\n");
        err.getBuffer().setLength(0);
        final int code =
                execute(
                        context,
                        "task",
                        "start",
                        "other",
                        "--attach",
                        "shell",
                        "--detach",
                        "-p",
                        projectFile(dir, WITH_BRANCH),
                        "--branch",
                        "bugfix/porelo/shell");

        assertThat(code).as(err.toString()).isEqualTo(64);
        assertThat(err.toString()).contains("porelo-uc-shell's already");
    }

    @Test
    void aValueTheDeclarationDoesNotAllowCreatesNothing(@TempDir Path dir) throws IOException {

        // Refused before anything exists: no container, no state, no gate.
        final int code =
                execute(
                        context(dir, true),
                        "task",
                        "start",
                        "-p",
                        projectFile(dir, WITH_PARAMETERS),
                        "--set",
                        "type=hotfix");

        assertThat(code).isEqualTo(64);
        assertThat(err.toString()).contains("[feature, bugfix]").contains("nothing was created");
        assertThat(runner.invocations())
                .as("no container made")
                .noneMatch(command -> command.arguments().contains("create"));
    }

    @Test
    void aStoppedTaskBroughtBackKeepsItsValuesAndRefusesNewOnes(@TempDir Path dir)
            throws IOException {

        // Its environment and record were fixed when it was created; a value handed in now would
        // change neither and look applied.
        final PoreloContext context = context(dir, true);
        runner.answering(
                "ps",
                "porelo-uc-shell\tExited (0) 4 minutes ago\t1700000000\t1700000100\tuc\tguarded\tuc\t\n");

        final int code =
                execute(
                        context,
                        "task",
                        "start",
                        "shell",
                        "-p",
                        projectFile(dir, WITH_PARAMETERS),
                        "--set",
                        "type=bugfix");

        assertThat(code).as(err.toString()).isEqualTo(64);
        assertThat(err.toString()).contains("stay as they were");
    }

    @Test
    void aStoppedTaskBroughtBackKeepsItsBranchAndRefusesANewOne(@TempDir Path dir)
            throws IOException {

        // Its branch was settled and recorded when it was created; a new one would change
        // nothing and look applied.
        final PoreloContext context = context(dir, true);
        runner.answering(
                "ps",
                "porelo-uc-shell\tExited (0) 4 minutes ago\t1700000000\t1700000100\tuc\tguarded\tuc\t\n");

        final int code =
                execute(
                        context,
                        "task",
                        "start",
                        "shell",
                        "-p",
                        projectFile(dir, WITH_BRANCH),
                        "--branch",
                        "fix/porelo/other");

        assertThat(code).as(err.toString()).isEqualTo(64);
        assertThat(err.toString()).contains("stays as it was");
    }

    @Test
    void aStoppedTaskBroughtBackKeepsItsSubagentsAndRefusesANewNumber(@TempDir Path dir)
            throws IOException {

        // Its agent was set up for its number when it was created; a new one would change nothing
        // and look applied.
        final PoreloContext context = context(dir, true);
        runner.answering(
                "ps",
                "porelo-uc-shell\tExited (0) 4 minutes ago\t1700000000\t1700000100\tuc\tguarded\tuc\t\n");

        final int code =
                execute(
                        context,
                        "task",
                        "start",
                        "shell",
                        "-p",
                        projectFile(dir, MINIMAL),
                        "--subagents",
                        "2");

        assertThat(code).as(err.toString()).isEqualTo(64);
        assertThat(err.toString())
                .as("a task that recorded no number was told none")
                .contains("was started with --subagents 0");
        assertThat(runner.lines())
                .as("nothing is resumed for a number it would not take")
                .noneMatch(line -> line.contains("podman start"));
    }

    private void recordSubagents(PoreloContext context, String agent, int subagents)
            throws IOException {
        recordSubagents(
                context.paths().tasks().containerState("porelo-uc-shell"), agent, subagents);
    }

    private static void recordSubagents(Path directory, String agent, int subagents)
            throws IOException {
        final Path state = Files.createDirectories(directory);
        new org.fuin.porelo.wire.TaskProfile(
                        org.fuin.porelo.wire.TaskProfile.VERSION,
                        agent,
                        org.fuin.porelo.wire.TaskMode.UNATTENDED,
                        "do the work",
                        null,
                        "2026-10-31T00:00:00Z",
                        "deny",
                        null,
                        null,
                        null,
                        null,
                        subagents)
                .writeTo(state);
    }

    /**
     * A machine with the preview agent installed, taking no brokered credential and able to bound
     * its subagents, and its hooks registered.
     */
    private PoreloContext countingContext(Path dir) throws IOException {
        final PoreloContext context = previewContext(dir);
        previewAgent(context, "preview", "subagents: count\n");
        final Path definition = context.paths().xdg().data().resolve("agents/preview.yaml");
        final String declared = Files.readString(definition);
        Files.writeString(
                definition,
                declared.substring(0, declared.indexOf("provider:"))
                        + declared.substring(declared.indexOf("subagents:")));
        hookBinaries(dir);
        new org.fuin.porelo.runtime.HookInstaller(
                        context.paths().hooksDirectory(), context.paths().binaryDirectory())
                .install();
        return context;
    }

    @Test
    void aNewTaskRecordsItsNumberForAStartThatContinuesIt(@TempDir Path dir) throws IOException {

        // A continuation hands its agent the number from the record; one never written is none.
        final PoreloContext context = countingContext(dir);

        final int started =
                execute(
                        context,
                        "task",
                        "start",
                        "-p",
                        projectFile(dir, MINIMAL),
                        "--agent",
                        "preview",
                        "--no-gate",
                        "--subagents",
                        "2");

        assertThat(started).as("started: %s", err).isZero();
        assertThat(
                        org.fuin.porelo.wire.TaskProfile.readFrom(
                                        context.paths().tasks().containerState("porelo-uc-shell"))
                                .subagents())
                .as("its record keeps the number")
                .isEqualTo(2);
        assertThat(
                        Files.readString(
                                context.paths().xdg().data().resolve("agents/preview.yaml.told")))
                .as("its agent's setup is told the number, attended as it starts")
                .isEqualTo("2");
    }

    @Test
    void anAttendedTaskWhoseAgentSetupFailsIsNotStarted(@TempDir Path dir) throws IOException {

        // Attended, its agent hears its number only from its setup: run without it, it would start
        // as many subagents as its own default allows while the task said none.
        final PoreloContext context = countingContext(dir);
        Files.writeString(
                context.paths().xdg().data().resolve("agents/preview.yaml.setup-fails"), "");

        final int started =
                execute(
                        context,
                        "task",
                        "start",
                        "-p",
                        projectFile(dir, MINIMAL),
                        "--agent",
                        "preview",
                        "--no-gate");

        assertThat(started).as("refused: %s", err).isEqualTo(70);
        assertThat(err.toString())
                .as("the refusal says why the task cannot go without its setup")
                .contains("could not prepare the agent's files", "the setup broke")
                .contains("preview is started attended")
                .contains("(none)");
        assertThat(execCalls).as("nobody is put into a task whose agent is not set up").isEmpty();
    }

    @Test
    void aKeptTaskWhoseAgentSetupFailsIsStoppedWithItsHelpers(@TempDir Path dir)
            throws IOException {

        // Kept, as every start without --rm is: left up, its container, broker, relay and gate ran
        // on while the start answered that it had failed. The helper stands for the broker, which
        // runs before the container does; its container runs too, so nothing reaps it as an
        // orphan.
        final PoreloContext context = countingContext(dir);
        Files.writeString(
                context.paths().xdg().data().resolve("agents/preview.yaml.setup-fails"), "");
        runner.answering("{{.State.Pid}}", "4242");
        final Path state =
                Files.createDirectories(context.paths().tasks().containerState("porelo-uc-shell"));
        final Process helper = new ProcessBuilder("sleep", "60").start();
        try {
            org.fuin.porelo.wire.HelperPid.record(state.resolve("vault.pid"), helper.toHandle());

            final int started =
                    execute(
                            context,
                            "task",
                            "start",
                            "-p",
                            projectFile(dir, MINIMAL),
                            "--agent",
                            "preview",
                            "--no-gate");

            assertThat(started).as("refused: %s", err).isEqualTo(70);
            assertThat(helper.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
                    .as("its helper is stopped with it: %s", out)
                    .isTrue();
            assertThat(runner.lines())
                    .as("the container it started is stopped: %s", runner.lines())
                    .anyMatch(line -> line.contains("podman stop"));
            assertThat(out.toString())
                    .as("and kept, stopped, for a look: %s", out)
                    .contains("kept      porelo-uc-shell - it failed");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        } finally {
            helper.destroyForcibly();
        }
    }

    @Test
    void aTaskKeptAfterItsStartFailedIsRemovedAsTheStartSaysWithoutForce(@TempDir Path dir)
            throws IOException {

        // Stopped before its workspace was a checkout, nothing could say what it held, and the
        // removal its start named refused without --force: but nobody had worked in it yet.
        final PoreloContext context = countingContext(dir);
        Files.writeString(
                context.paths().xdg().data().resolve("agents/preview.yaml.setup-fails"), "");
        runner.answering("{{.State.Pid}}", "4242");

        final int started =
                execute(
                        context,
                        "task",
                        "start",
                        "-p",
                        projectFile(dir, MINIMAL),
                        "--agent",
                        "preview",
                        "--no-gate");
        assertThat(started).as("refused: %s", err).isEqualTo(70);
        assertThat(out.toString())
                .as("kept, and named for removal: %s", out)
                .contains("discard with 'porelo task remove porelo-uc-shell'");
        runner.answering("{{.State.Pid}}", "0");
        runner.answering("{{.State.Status}}", "exited");

        assertThat(new TaskControl(context).remove("porelo-uc-shell", false, false).outcome())
                .as("it is removed as it was named, held nothing")
                .isNotEqualTo(TaskControl.Outcome.NOTHING_KNOWS);
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "with --rm: {0}")
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void aStartThatFailsBeforeItsContainerLeavesItsConversationAndRecords(
            boolean removed, @TempDir Path dir) throws IOException {

        // Failed before podman made the container, kept or not, the start removed a container
        // that never was: its account at the transport, the secret that retires it, its mailbox
        // and its state stayed, with no recorded task a removal could reach them by.
        final char[] passphrase = "test-passphrase".toCharArray();
        final PoreloContext context = countingContext(dir).asking(shown -> passphrase.clone());
        try {
            Files.createDirectories(context.vault().path().getParent());
            context.vault().write(java.util.Map.of(), passphrase.clone());
            context.openerAsking(false, new PrintWriter(new StringWriter()));
            final Path adapters =
                    context.paths().messaging().transportDirectory().locations().getFirst();
            Files.createDirectories(adapters);
            final Path adapter = adapters.resolve(TransportDirectory.PREFIX + "room");
            Files.writeString(adapter, "#!/bin/sh\n");
            adapter.toFile().setExecutable(true);
            runner.answering(
                    "describe",
                    "{\"scheme\":\"room\",\"lifecycle\":[\"setup\",\"enroll\",\"retire\"]}");
            runner.answering(
                    " setup ", "{\"conversation\":\"!room\",\"reaches\":[\"127.0.0.1:18008\"]}");
            runner.answering(" enroll ", "{\"secrets\":{\"TASK\":\"t-1\"}}");
            runner.answering(" retire ", "{}");
            runner.failing("podman build", 125, "the image does not build");
            final List<String> arguments =
                    new ArrayList<>(
                            List.of(
                                    "task",
                                    "start",
                                    "-p",
                                    projectFile(
                                            dir,
                                            MINIMAL
                                                    + "mail:\n  transports:\n    room: {}\n"
                                                    + "  peers:\n    reviewer: { address: 'room:',"
                                                    + " trust: vouched }\n"),
                                    "--agent",
                                    "preview",
                                    "--no-gate"));
            if (removed) {
                arguments.add("--rm");
            }

            final int started = execute(context, arguments.toArray(String[]::new));

            assertThat(started).as("it failed: %s", err).isNotZero();
            assertThat(runner.lines())
                    .as("it joined the project's conversation first: %s", runner.lines())
                    .anyMatch(line -> line.contains(" enroll "));
            assertThat(runner.lines())
                    .as("and leaves it: %s", err)
                    .anyMatch(line -> line.contains(" retire "));
            assertThat(
                            new TransportLifecycle(
                                            context,
                                            context.paths().messaging().transportDirectory())
                                    .secrets("room", "task/porelo-uc-shell"))
                    .as("nothing is kept to retire it by")
                    .isEmpty();
            assertThat(context.paths().messaging().mailbox("porelo-uc-shell"))
                    .as("its mailbox goes")
                    .doesNotExist();
            assertThat(context.paths().tasks().containerState("porelo-uc-shell"))
                    .as("and its state")
                    .doesNotExist();
        } finally {
            PoreloContext.forgetAsked();
        }
    }

    @Test
    void aStartThatFailsBeforeItsContainerLeavesAContainerOfItsNameItDidNotMake(@TempDir Path dir)
            throws IOException {

        // Somebody else's container that has the task's name is no task of Porelo's: a start that
        // failed before it made its own stopped and removed that one by name. Running, it also hid
        // the helpers the start had already made from the reaping.
        final PoreloContext context = countingContext(dir);
        runner.failing("podman build", 125, "the image does not build");
        runner.answering("{{.Id}}", "0123456789ab");
        runner.answering("{{.State.Pid}}", "4242");
        final Path state =
                Files.createDirectories(context.paths().tasks().containerState("porelo-uc-shell"));
        final Process helper = new ProcessBuilder("sleep", "60").start();
        try {
            org.fuin.porelo.wire.HelperPid.record(state.resolve("vault.pid"), helper.toHandle());

            final int started =
                    execute(
                            context,
                            "task",
                            "start",
                            "-p",
                            projectFile(dir, MINIMAL),
                            "--agent",
                            "preview",
                            "--no-gate");

            assertThat(started).as("it failed: %s", err).isNotZero();
            assertThat(runner.lines())
                    .as("the container it did not make is left alone: %s", runner.lines())
                    .noneMatch(line -> line.contains("podman stop"))
                    .noneMatch(line -> line.contains("podman rm"));
            assertThat(helper.waitFor(5, java.util.concurrent.TimeUnit.SECONDS))
                    .as("what it made is stopped all the same: %s", err)
                    .isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        } finally {
            helper.destroyForcibly();
        }
    }

    @Test
    void aKeptTaskWhoseStartFailsAfterItsContainerIsUpIsStopped(@TempDir Path dir)
            throws IOException {

        // Any start that fails once its container is up, not only one whose setup failed: kept, it
        // was left running with its helpers while the start answered that it had failed.
        final PoreloContext context = countingContext(dir);
        runner.timingOut("{{.State.Pid}}");

        final int started =
                execute(
                        context,
                        "task",
                        "start",
                        "-p",
                        projectFile(dir, MINIMAL),
                        "--agent",
                        "preview",
                        "--no-gate");

        assertThat(started).as("failed: %s", err).isEqualTo(70);
        assertThat(runner.lines())
                .as("the container it started is stopped: %s", runner.lines())
                .anyMatch(line -> line.contains("podman stop"));
    }

    @Test
    void aGatedTaskWhoseAgentSetupFailsStartsNothingAfterItsContainer(@TempDir Path dir)
            throws IOException {

        // Set up after its gate, a host's forge token had been taken for it, right before the gate,
        // and nothing recorded that a removal would retire it by. The host's helper is at a fixed
        // root-owned path no unit test has, so the gate stands for what follows the container.
        final PoreloContext context = countingContext(dir);
        Files.writeString(
                context.paths().xdg().data().resolve("agents/preview.yaml.setup-fails"), "");

        final int started =
                execute(
                        context,
                        "task",
                        "start",
                        "-p",
                        projectFile(dir, MINIMAL),
                        "--agent",
                        "preview");

        assertThat(started).as("refused: %s", err).isEqualTo(70);
        assertThat(out.toString())
                .as("its gate is never started: %s", out)
                .contains("started   yes")
                .doesNotContain("gate      http");
        assertThat(err.toString())
                .as("nor its workspace prepared: %s", err)
                .contains("could not prepare the agent's files")
                .doesNotContain("workspace");
    }

    @Test
    void anAgentsFileInTheWorkspaceIsPlacedOnlyOnceTheWorkspaceIsCheckedOut(@TempDir Path dir)
            throws IOException {

        // Placed first, a file the repository also tracks stopped the workspace's checkout, which
        // refuses to overwrite it, and the workspace was left without the push that reaches the
        // gate. What goes elsewhere is still placed before the gate.
        final PoreloContext context = countingContext(dir);
        Files.writeString(context.paths().xdg().data().resolve("agents/preview.yaml.places"), "");
        // What goes into the container, the files and the checkout alike, in the order it went.
        final Path said = dir.resolve("podman.said");
        final Path podman = dir.resolve("logging-podman");
        Files.writeString(
                podman,
                "#!/bin/sh\nprintf '%s\\n' \"$*\" >> " + shellArgument(said.toString()) + "\n");
        Files.setPosixFilePermissions(
                podman, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        // Its helpers are no part of it: run as this build's porelo, its gate served whatever
        // project of that name the account itself has, and outlived the test.
        final Path helpers = dir.resolve("helpers-porelo");
        Files.writeString(helpers, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(
                helpers, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        final String standIn = System.getProperty(org.fuin.porelo.runtime.Podman.NAMED);
        final String ownPorelo = System.getProperty(PoreloBinary.NAMED);
        System.setProperty(org.fuin.porelo.runtime.Podman.NAMED, podman.toString());
        System.setProperty(PoreloBinary.NAMED, helpers.toString());
        final int started;
        try {
            started =
                    execute(
                            context,
                            "task",
                            "start",
                            "-p",
                            projectFile(dir, MINIMAL),
                            "--agent",
                            "preview");
        } finally {
            System.setProperty(org.fuin.porelo.runtime.Podman.NAMED, standIn);
            System.setProperty(PoreloBinary.NAMED, ownPorelo);
        }

        assertThat(started).as("started: %s", err).isZero();
        assertThat(
                        ProcessHandle.allProcesses()
                                .map(process -> process.info().commandLine().orElse(""))
                                .filter(line -> line.contains(dir.toString()))
                                .filter(line -> line.contains(" gate serve ")))
                .as("no gate of this build is left serving the account's own projects")
                .isEmpty();
        final List<String> lines = Files.readAllLines(said);
        final int checkout = indexOf(lines, "git init -q");
        assertThat(checkout).as("the workspace is checked out: %s", lines).isNotNegative();
        assertThat(indexOf(lines, "tee " + TaskWorkspace.MOUNT + "/.preview/settings.json"))
                .as("the file in the workspace is placed after its checkout: %s", lines)
                .isGreaterThan(checkout);
        assertThat(indexOf(lines, "tee /home/agent/.preview.json"))
                .as("the agent's own file before it: %s", lines)
                .isNotNegative()
                .isLessThan(checkout);
    }

    @Test
    void aTaskKeptAfterItsStartFailedComesBackWithTheHelpersItHad(@TempDir Path dir)
            throws IOException {

        // Its gate was up when its agent's file in the workspace could not be placed; kept, the
        // start recorded none of it, and a start that brought it back brought no gate.
        final PoreloContext context = countingContext(dir);
        Files.writeString(context.paths().xdg().data().resolve("agents/preview.yaml.places"), "");
        final Path podman = dir.resolve("refusing-podman");
        Files.writeString(
                podman,
                "#!/bin/sh\ncase \"$*\" in *'tee "
                        + TaskWorkspace.MOUNT
                        + "/'*) exit 1 ;; esac\nexit 0\n");
        Files.setPosixFilePermissions(
                podman, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        final Path helpers = dir.resolve("helpers-porelo");
        Files.writeString(helpers, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(
                helpers, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        runner.answering("{{.State.Pid}}", "4242");
        final String standIn = System.getProperty(org.fuin.porelo.runtime.Podman.NAMED);
        final String ownPorelo = System.getProperty(PoreloBinary.NAMED);
        System.setProperty(org.fuin.porelo.runtime.Podman.NAMED, podman.toString());
        System.setProperty(PoreloBinary.NAMED, helpers.toString());
        try {
            final int started =
                    execute(
                            context,
                            "task",
                            "start",
                            "-p",
                            projectFile(dir, MINIMAL),
                            "--agent",
                            "preview");
            assertThat(started).as("refused: %s", err).isEqualTo(70);
            assertThat(out.toString())
                    .as("its gate was up, and it is kept to start again: %s", out)
                    .contains("gate      http")
                    .contains("which offers to start it again");
            runner.answering("{{.State.Status}}", "exited");
            runner.answering("{{.Id}}", "0123456789ab");
            runner.answering(
                    "ps", RUNNING_SHELL.replace("Up 4 minutes", "Exited (0) 4 minutes ago"));

            assertThat(new TaskControl(context).resume("porelo-uc-shell").outcome())
                    .as("it comes back with what it had: %s", err)
                    .isNotEqualTo(TaskControl.Outcome.NO_HELPERS_RECORDED)
                    .isNotEqualTo(TaskControl.Outcome.NO_CONTAINER);
        } finally {
            System.setProperty(org.fuin.porelo.runtime.Podman.NAMED, standIn);
            System.setProperty(PoreloBinary.NAMED, ownPorelo);
        }
    }

    private static int indexOf(List<String> lines, String part) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(part)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void aShellTaskWhoseAgentSetupFailsStillStarts(@TempDir Path dir) throws IOException {

        // Porelo starts no agent in a shell, so the failure is said and the shell comes up.
        final PoreloContext context = countingContext(dir);
        Files.writeString(
                context.paths().xdg().data().resolve("agents/preview.yaml.setup-fails"), "");

        final int started =
                execute(
                        context,
                        "task",
                        "start",
                        "-p",
                        projectFile(dir, MINIMAL),
                        "--agent",
                        "preview",
                        "--no-gate",
                        "--attach",
                        "shell");

        assertThat(started).as("started: %s", err).isZero();
        assertThat(err.toString())
                .contains("could not prepare the agent's files")
                .doesNotContain("is started attended");
        assertThat(execCalls).as("the shell it went into").hasSize(1);
    }

    @Test
    void aStoppedTaskBroughtBackTakesTheNumberItWasStartedWithAndRefusesAnother(@TempDir Path dir)
            throws IOException {

        // Its record keeps the number; saying it again changes nothing, and is no mistake.
        final PoreloContext context = context(dir, true);
        runner.answering(
                "ps",
                "porelo-uc-shell\tExited (0) 4 minutes ago\t1700000000\t1700000100\tuc\tguarded\tuc\t\n");
        recordSubagents(context, "preview", 2);

        final int other =
                execute(
                        context,
                        "task",
                        "start",
                        "shell",
                        "-p",
                        projectFile(dir, MINIMAL),
                        "--subagents",
                        "3",
                        "--detach");

        assertThat(other).as(err.toString()).isEqualTo(64);
        assertThat(err.toString())
                .as("the refusal names the number it keeps")
                .contains("was started with --subagents 2");
        assertThat(runner.lines())
                .as("nothing is resumed for a number it would not take")
                .noneMatch(line -> line.contains("podman start"));

        err.getBuffer().setLength(0);
        final int before = runner.lines().size();
        execute(
                context,
                "task",
                "start",
                "shell",
                "-p",
                projectFile(dir, MINIMAL),
                "--subagents",
                "2",
                "--detach");

        assertThat(err.toString())
                .as("its own number is taken")
                .doesNotContain("was started with --subagents");
        assertThat(runner.lines().subList(before, runner.lines().size()))
                .as("brought back with its own number: its container is asked for")
                .anyMatch(line -> line.contains("container inspect"));
    }

    @Test
    void aTaskBroughtBackAfterARebootReadsItsNumberFromItsDurableRecord(@TempDir Path dir)
            throws IOException {

        // A reboot clears the runtime directory, and its records are put back only by the resume
        // the check comes before: read from there alone, the task's own number was refused and
        // none taken, while its agent was still told the kept one.
        final PoreloContext context = context(dir, true);
        runner.answering(
                "ps",
                "porelo-uc-shell\tExited (0) 4 minutes ago\t1700000000\t1700000100\tuc\tguarded\tuc\t\n");
        recordSubagents(context.paths().tasks().taskRecord("porelo-uc-shell"), "preview", 2);
        // The container a reboot leaves, under the id its durable record keeps.
        org.fuin.porelo.wire.Sidecar.recordId(
                context.paths().tasks().taskRecord("porelo-uc-shell"), "0123456789abcdef");
        runner.answering("{{.Id}}", "0123456789abcdef");

        final int none =
                execute(
                        context,
                        "task",
                        "start",
                        "shell",
                        "-p",
                        projectFile(dir, MINIMAL),
                        "--subagents",
                        "0",
                        "--detach");

        assertThat(none).as(err.toString()).isEqualTo(64);
        assertThat(err.toString())
                .as("the refusal names the number its durable record keeps")
                .contains("was started with --subagents 2");

        err.getBuffer().setLength(0);
        final int own =
                execute(
                        context,
                        "task",
                        "start",
                        "shell",
                        "-p",
                        projectFile(dir, MINIMAL),
                        "--subagents",
                        "2",
                        "--detach");

        assertThat(own).as("brought back with its own number: %s", err).isZero();
        assertThat(err.toString())
                .as("its own number is taken")
                .doesNotContain("was started with --subagents");
        assertThat(runner.lines())
                .as("and it is brought back: %s", runner.lines())
                .anyMatch(line -> line.contains("podman start"));
    }

    @Test
    void aStoppedTaskContinuedWithAPromptHandsItsAgentTheNumberItWasStartedWith(@TempDir Path dir)
            throws IOException {

        // The run request is where an unattended agent hears its number; continued with none, it
        // would be told zero, or with the transient default, whatever its start said.
        final PoreloContext context = previewContext(dir);
        previewAgent(context, "preview", "subagents: count\n");
        recordSubagents(context, "preview", 2);
        // What runs the agent in its container says what it was asked to run, into the task's log.
        final Path podman = dir.resolve("echoing-podman");
        Files.writeString(podman, "#!/bin/sh\necho \"$@\"\n");
        Files.setPosixFilePermissions(
                podman, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        final String standIn = System.getProperty(org.fuin.porelo.runtime.Podman.NAMED);
        System.setProperty(org.fuin.porelo.runtime.Podman.NAMED, podman.toString());
        final int code;
        try {
            code =
                    new TaskLaunch(
                                    context,
                                    new TaskLaunch.Request(
                                            "shell",
                                            dir.resolve("project.yml"),
                                            null,
                                            null,
                                            null,
                                            8,
                                            null,
                                            true,
                                            false,
                                            "deny",
                                            true,
                                            org.fuin.porelo.wire.TaskMode.UNATTENDED,
                                            "go on",
                                            null,
                                            null,
                                            1,
                                            "uc",
                                            java.util.Map.of(),
                                            java.util.Map.of(),
                                            null,
                                            null))
                            .runAgentInExisting(
                                    "porelo-uc-shell",
                                    null,
                                    new PrintWriter(out),
                                    new PrintWriter(err));
        } finally {
            System.setProperty(org.fuin.porelo.runtime.Podman.NAMED, standIn);
        }

        assertThat(code).as("the run went ahead: %s", err).isZero();
        assertThat(
                        Files.readString(
                                context.paths()
                                        .tasks()
                                        .containerState("porelo-uc-shell")
                                        .resolve("task.log")))
                .as("the agent is asked for its run with the task's own number")
                .contains("go on subagents=2");
    }

    @Test
    void aTaskIsGivenItsValuesInItsEnvironmentAndRecordsWhereEachCameFrom(@TempDir Path dir)
            throws IOException {

        // The declaration's variable carries the value in; the record keeps the origin, so a branch
        // named from a value can be traced back after the shell that gave it is gone.
        final PoreloContext context = context(dir, true);
        execute(
                context,
                "task",
                "start",
                "--attach",
                "shell",
                "--detach",
                "-p",
                projectFile(dir, WITH_PARAMETERS),
                "--set",
                "ticket=AB-12");

        assertThat(runner.only("create").environment())
                .containsEntry("TASK_TICKET", "AB-12")
                .containsEntry("PORELO_PARAM_TYPE", "feature")
                .doesNotContainKey("PORELO_PARAM_LABEL");
        final String recorded =
                Files.readString(
                        dir.resolve("state/porelo/tasks/porelo-uc-shell")
                                .resolve(TaskInventory.PARAMETERS_FILE));
        assertThat(recorded).contains("AB-12").contains("command line").contains("default");
        // And its agent is told, after the text every task shares.
        final String guide =
                Files.readString(new TaskGuide(context.paths(), "porelo-uc-shell").file());
        assertThat(guide)
                .startsWith(TaskGuideText.text(false).substring(0, 40))
                .contains("- `ticket`: `AB-12`, in `$TASK_TICKET`")
                .contains("- `type`: `feature`, in `$PORELO_PARAM_TYPE`");
    }

    @Test
    void nothingIsTakenFromTheShellUnasked(@TempDir Path dir) throws IOException {

        // A variable left in a shell from yesterday must not name today's task.
        final int code =
                execute(
                        context(dir, true, name -> "TASK_TICKET".equals(name) ? "ENV-1" : null),
                        "task",
                        "start",
                        "-p",
                        projectFile(dir, WITH_PARAMETERS),
                        "--dry-run");

        assertThat(code).isZero();
        assertThat(out.toString()).doesNotContain("ticket=");
    }

    @Test
    void aDryRunNamesAHostTheProjectRefusesAsRefusedAndNotAsReachable(@TempDir Path dir)
            throws IOException {
        // A plan that promises a refused dependency sends a build down an unusable path.
        final int code =
                execute(
                        context(dir, false),
                        "task",
                        "start",
                        "-p",
                        projectFile(
                                dir,
                                MINIMAL
                                        + "egress:\n  domains: [example.com]\n  refused: [example.com]\n"),
                        "--dry-run");

        assertThat(code).as("a preview needs neither hooks nor a container runtime").isZero();
        assertThat(out.toString().lines().map(line -> line.replaceAll("\\s+", " ").strip()))
                .as("the project's refusal wins over its grant")
                .contains("reachable example.com refused on purpose by project")
                .doesNotContain("reachable example.com project", "example.com project");
        assertThat(runner.invocations()).as("a dry run must touch nothing").isEmpty();
        assertThat(execCalls).as("a preview must not attach a session").isEmpty();
    }

    @Test
    void aDryRunIncludesTheChosenAgentsRefusalsAndItsProvidersHosts(@TempDir Path dir)
            throws IOException {
        // Selecting another installed agent must not silently preview the wrong network policy.
        final PoreloContext context = previewContext(dir);
        previewAgent(context, "preview");
        previewAgent(context, "other");

        final int code =
                execute(
                        context,
                        "task",
                        "start",
                        "-p",
                        projectFile(
                                dir, MINIMAL + "egress:\n  domains: [raw.githubusercontent.com]\n"),
                        "--agent",
                        "preview",
                        "--dry-run");

        assertThat(code)
                .as("the named agent can be previewed without hooks or credentials: %s", err)
                .isZero();
        assertThat(out.toString().lines().map(line -> line.replaceAll("\\s+", " ").strip()))
                .as("the preview includes the selected agent, provider and overriding refusal")
                .contains(
                        "reachable agent.example.com agent preview",
                        "provider.example.com provider preview",
                        "raw.githubusercontent.com refused on purpose by agent preview")
                .doesNotContain("raw.githubusercontent.com project");
        assertThat(runner.invocations()).as("a dry run must touch no container runtime").isEmpty();
        assertThat(execCalls).as("a preview must not start a session").isEmpty();
        try (var sockets = Files.list(context.paths().xdg().runtime())) {
            assertThat(sockets).as("the metadata adapters leave no socket behind").isEmpty();
        }
    }

    @Test
    void aDryRunRefusesAProviderTheAgentCannotUseAsTheStartDoes(@TempDir Path dir)
            throws IOException {
        // A refusal the caller can fix is one line and 2, never a defect with a stack trace.
        final PoreloContext context = previewContext(dir);
        previewAgent(context, "preview");

        final int code =
                execute(
                        context,
                        "task",
                        "start",
                        "-p",
                        projectFile(dir, MINIMAL),
                        "--agent",
                        "preview",
                        "--provider",
                        "nosuch",
                        "--dry-run");

        assertThat(code).as("refused, not failed: %s", err).isEqualTo(2);
        assertThat(err.toString()).as("in one line naming the provider").contains("nosuch");
        assertThat(err.toString()).as("not a defect").doesNotContain("Exception");
        assertThat(runner.invocations()).as("a dry run must touch no container runtime").isEmpty();
    }

    @Test
    void anAgentThatSaysNothingOfSubagentsIsRefusedATaskAskingForOneBeforeAnythingIsMade(
            @TempDir Path dir) throws IOException {
        // Started anyway, its agent would run as many subagents as it likes while the task said
        // two: less enforced than was asked, and nobody told.
        final PoreloContext context = refusingProviderContext(dir, true);

        final int code = unattendedStart(context, dir, "--subagents", "2");

        assertThat(code).as("wrong use of this agent: %s", err).isEqualTo(64);
        assertThat(err.toString())
                .as("the refusal names the agent, what it can enforce, and what was asked")
                .contains("preview can only switch its subagents off, so it can enforce 0")
                .contains("--subagents 2");
        assertThat(runner.lines())
                .as("nothing is built or created for a task its agent cannot hold")
                .noneMatch(line -> line.contains("podman build") || line.contains("podman create"));
        assertThat(execCalls).as("a refused start enters no task").isEmpty();
    }

    @Test
    void anAgentThatCanOnlySwitchThemOffIsRefusedTheProjectFilesNumber(@TempDir Path dir)
            throws IOException {
        final PoreloContext context = previewContext(dir);
        previewAgent(context, "preview", "subagents: off\n");

        final int code =
                execute(
                        context,
                        "task",
                        "start",
                        "-p",
                        projectFile(dir, MINIMAL + "limits:\n  subagents: 1\n"),
                        "--agent",
                        "preview",
                        "--dry-run");

        assertThat(code).as("refused as wrong use: %s", err).isEqualTo(64);
        assertThat(err.toString())
                .as("the refusal names where the number came from")
                .contains("preview can only switch its subagents off")
                .contains("limits.subagents 1");
        assertThat(runner.invocations()).as("a dry run touches no container runtime").isEmpty();
    }

    @Test
    void canStartRefusesTheProjectFilesNumberAsStartRefusesIt(@TempDir Path dir)
            throws IOException {

        // An interface offered a start by CanStart would see Start refuse it, for a reason it was
        // never told.
        final PoreloContext context = previewContext(dir);
        previewAgent(context, "preview", "subagents: off\n");
        final String project =
                projectFile(
                        dir,
                        MINIMAL
                                + "limits:\n  subagents: 1\n"
                                + "repositories:\n  backend:\n    limits:\n      subagents: 2\n");

        final StartCheck.Result checked =
                StartCheck.check(
                        context,
                        dir.resolve("project.yml"),
                        "shell",
                        "preview",
                        null,
                        null,
                        "backend",
                        true,
                        java.util.Map.of(),
                        java.util.Map.of(),
                        null);
        final int started =
                execute(
                        context,
                        "task",
                        "start",
                        "-p",
                        project,
                        "--repository",
                        "backend",
                        "--agent",
                        "preview",
                        "--dry-run");

        assertThat(started).as("Start refuses it: %s", err).isEqualTo(64);
        assertThat(checked.outcome())
                .as("CanStart says it cannot start: %s", checked.detail())
                .isEqualTo(StartCheck.Outcome.SUBAGENTS_REFUSED);
        assertThat(checked.ready()).isFalse();
        assertThat(checked.detail())
                .as("in the words Start refuses with, naming the repository's key")
                .contains("repository backend's limits.subagents 2");
        assertThat(err.toString()).contains("porelo: " + checked.detail());
    }

    @Test
    void canStartRefusesTheOwnRepositoryOfAProjectWithOthersAsStartRefusesIt(@TempDir Path dir)
            throws IOException {

        // Answered for its number, an interface showed a refusal of subagents where Start refuses
        // the repository, and the person changed the number to no avail.
        final PoreloContext context = previewContext(dir);
        previewAgent(context, "preview", "subagents: off\n");
        final String project =
                projectFile(
                        dir,
                        MINIMAL
                                + "limits:\n  subagents: 1\n"
                                + "repositories:\n  backend:\n    limits:\n      subagents: 0\n");

        final StartCheck.Result checked =
                StartCheck.check(
                        context,
                        dir.resolve("project.yml"),
                        "shell",
                        "preview",
                        null,
                        null,
                        "uc",
                        true,
                        java.util.Map.of(),
                        java.util.Map.of(),
                        null);
        final int started =
                execute(
                        context,
                        "task",
                        "start",
                        "-p",
                        project,
                        "--repository",
                        "uc",
                        "--agent",
                        "preview",
                        "--dry-run");

        assertThat(started).as("Start refuses it: %s", err).isEqualTo(64);
        assertThat(err.toString())
                .as("for its repository: %s", err)
                .doesNotContain("subagents off");
        assertThat(checked.outcome())
                .as("CanStart refuses the repository as Start does: %s", checked.detail())
                .isEqualTo(StartCheck.Outcome.UNKNOWN_REPOSITORY);
        assertThat(err.toString()).contains(checked.detail());
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "repository {0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"nosuch", "uc"})
    void canStartRefusesARepositoryStartRefusesWithoutAGateToo(String named, @TempDir Path dir)
            throws IOException {

        // Answered ready, an interface offered a start that Start then refused for its repository;
        // its host services, which Start says only after the repository, were said instead.
        final PoreloContext context = countingContext(dir);
        final String project =
                projectFile(
                        dir,
                        MINIMAL
                                + "egress:\n  host_services: [\"forge.internal.example\"]\n"
                                + "repositories:\n  backend:\n    agent: preview\n");

        final StartCheck.Result checked =
                StartCheck.check(
                        context,
                        dir.resolve("project.yml"),
                        "shell",
                        "preview",
                        null,
                        null,
                        named,
                        false,
                        java.util.Map.of(),
                        java.util.Map.of(),
                        null);
        final int started =
                execute(
                        context,
                        "task",
                        "start",
                        "-p",
                        project,
                        "--repository",
                        named,
                        "--agent",
                        "preview",
                        "--no-gate",
                        "--dry-run");

        assertThat(started).as("Start refuses it: %s", err).isEqualTo(64);
        assertThat(checked.outcome())
                .as("CanStart refuses it as Start does: %s", checked.detail())
                .isEqualTo(StartCheck.Outcome.UNKNOWN_REPOSITORY);
    }

    @Test
    void canStartRefusesARepositoryBeforeItAsksWhichAgentAsStartDoes(@TempDir Path dir)
            throws IOException {

        // Told to choose an agent, a person chose one and was then refused for the repository,
        // which Start had named first.
        final PoreloContext context = countingContext(dir);
        previewAgent(context, "other");
        final String project = projectFile(dir, MINIMAL);

        final StartCheck.Result checked =
                StartCheck.check(
                        context,
                        dir.resolve("project.yml"),
                        "shell",
                        null,
                        null,
                        null,
                        "nosuch",
                        true,
                        java.util.Map.of(),
                        java.util.Map.of(),
                        null);
        final int started =
                execute(
                        context,
                        "task",
                        "start",
                        "-p",
                        project,
                        "--repository",
                        "nosuch",
                        "--dry-run");

        assertThat(started).as("Start refuses it: %s", err).isEqualTo(64);
        assertThat(err.toString()).as("for its repository").contains("has no repository");
        assertThat(checked.outcome())
                .as("CanStart refuses the repository first, as Start does: %s", checked.detail())
                .isEqualTo(StartCheck.Outcome.UNKNOWN_REPOSITORY);
    }

    @Test
    void canStartTakesTheAgentANamedRepositoryNamesWithoutAGateAsStartTakesIt(@TempDir Path dir)
            throws IOException {

        // Asked without the repository, CanStart said several agents are installed while Start
        // took the repository's own and its number.
        final PoreloContext context = countingContext(dir);
        previewAgent(context, "other", "subagents: off\n");
        final Path other = context.paths().xdg().data().resolve("agents/other.yaml");
        final String declared = Files.readString(other);
        Files.writeString(
                other,
                declared.substring(0, declared.indexOf("provider:"))
                        + declared.substring(declared.indexOf("subagents:")));
        final String project =
                projectFile(
                        dir,
                        MINIMAL
                                + "repositories:\n  backend:\n    agent: preview\n"
                                + "    limits:\n      subagents: 2\n");

        final StartCheck.Result checked =
                StartCheck.check(
                        context,
                        dir.resolve("project.yml"),
                        "shell",
                        null,
                        null,
                        null,
                        "backend",
                        false,
                        java.util.Map.of(),
                        java.util.Map.of(),
                        null);
        final int started =
                execute(
                        context,
                        "task",
                        "start",
                        "-p",
                        project,
                        "--repository",
                        "backend",
                        "--no-gate",
                        "--dry-run");

        assertThat(started).as("Start takes it: %s", err).isZero();
        assertThat(checked.outcome())
                .as("CanStart takes it too: %s", checked.detail())
                .isEqualTo(StartCheck.Outcome.READY);
        assertThat(checked.agent()).as("the repository's agent").isEqualTo("preview");
    }

    @Test
    void theStartsNumberWinsOverTheProjectFiles(@TempDir Path dir) throws IOException {
        final PoreloContext context = previewContext(dir);
        previewAgent(context, "preview", "subagents: off\n");
        final String project = projectFile(dir, MINIMAL + "limits:\n  subagents: 3\n");

        final int none =
                execute(
                        context,
                        "task",
                        "start",
                        "-p",
                        project,
                        "--agent",
                        "preview",
                        "--subagents",
                        "0",
                        "--dry-run");

        assertThat(none)
                .as("--subagents 0 over the file's 3 is what the agent can hold: %s", err)
                .isZero();

        final int more =
                execute(
                        context,
                        "task",
                        "start",
                        "-p",
                        projectFile(dir, MINIMAL),
                        "--agent",
                        "preview",
                        "--subagents",
                        "2",
                        "--dry-run");

        assertThat(more).as("--subagents 2 over the file's none is refused").isEqualTo(64);
        assertThat(err.toString()).contains("--subagents 2");
    }

    @Test
    void anAgentThatDeclaresACountIsStartedForTheNumberAsked(@TempDir Path dir) throws IOException {
        final PoreloContext context = previewContext(dir);
        previewAgent(context, "preview", "subagents: count\n");

        final int code =
                execute(
                        context,
                        "task",
                        "start",
                        "-p",
                        projectFile(dir, MINIMAL),
                        "--agent",
                        "preview",
                        "--subagents",
                        "3",
                        "--dry-run");

        assertThat(code).as("its adapter holds the task to three: %s", err).isZero();
        assertThat(err.toString()).as("nothing refused").doesNotContain("subagents");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "-1, 64, --subagents takes 0 or more",
        "two, 2, --subagents",
        "1.5, 2, --subagents"
    })
    void aNumberOfSubagentsBelowNoneOrNotWholeIsWrongUse(
            String value, int refused, String said, @TempDir Path dir) throws IOException {
        // Below none is refused by the start, as the daemon's is; what is no number at all is
        // refused by the parser, as every option's is.
        final PoreloContext context = previewContext(dir);
        previewAgent(context, "preview", "subagents: count\n");

        final int code =
                execute(
                        context,
                        "task",
                        "start",
                        "-p",
                        projectFile(dir, MINIMAL),
                        "--agent",
                        "preview",
                        "--subagents=" + value,
                        "--dry-run");

        assertThat(code).as("refused as wrong use: %s", err).isEqualTo(refused);
        assertThat(err.toString()).as("naming the option").contains(said);
        assertThat(runner.invocations()).as("nothing touched").isEmpty();
    }

    @Test
    void aDryRunWithSeveralAgentsAsksForAnAgentOptionWithoutPrompting(@TempDir Path dir)
            throws IOException {
        // A preview must not make a network-policy choice the operator never made.
        final PoreloContext context = previewContext(dir);
        previewAgent(context, "preview");
        previewAgent(context, "other");

        final int code = startAt(context, true, "-p", projectFile(dir, MINIMAL), "--dry-run");

        assertThat(code).as("ambiguity still permits a project-only preview").isZero();
        assertThat(out.toString().lines().filter(line -> line.contains("--agent")))
                .as("one line explains how to include an agent: %s %s", out, err)
                .hasSize(1);
        assertThat(out.toString())
                .as("no agent or provider is guessed")
                .doesNotContain("agent.example.com", "provider.example.com");
        assertThat(err.toString()).as("no interactive selection is offered").isEmpty();
        assertThat(runner.invocations()).as("a dry run must touch no container runtime").isEmpty();
    }

    @Test
    void reportsTheResolvedProject(@TempDir Path dir) throws IOException {

        final int code =
                execute(
                        context(dir, true),
                        "task",
                        "start",
                        "-p",
                        projectFile(dir, MINIMAL),
                        "--dry-run");

        assertThat(code).isZero();
        assertThat(out.toString())
                .contains("project        uc")
                .contains("security class guarded")
                .contains("task image     porelo/uc");
        assertThat(runner.invocations()).as("a dry run must touch nothing").isEmpty();
    }

    @Test
    void refusesToRunWhenTheHookBinariesAreNotInstalled(@TempDir Path dir) throws IOException {

        // A container started without its firewall looks completely normal, which is why this must
        // stop rather than warn. Writing descriptors would not help: they would name binaries that
        // are not there, which is a broken installation rather than a missing step.
        final int code =
                execute(context(dir, false), "task", "start", "-p", projectFile(dir, MINIMAL));

        assertThat(code).isEqualTo(69);
        assertThat(err.toString()).contains("binaries that are not installed");
        // Asking podman its version comes first and reads nothing else; what must not happen is a
        // container existing without the hooks that give it a firewall.
        assertThat(runner.lines()).allMatch(line -> line.startsWith("podman version"));
    }

    @Test
    void registersTheHooksItselfRatherThanAskingForACommand(@TempDir Path dir) throws IOException {

        // Being told to run 'porelo setup' is a thing people forget, which is how a machine ends up
        // with an installation nobody completed. The binaries are there and the descriptors are
        // not, which is the state after 'apt install' - the package deliberately does not write
        // them, because podman reads them per user and root does not know whose.
        hookBinaries(dir);

        final int code =
                execute(
                        context(dir, false),
                        "task",
                        "start",
                        "--attach",
                        "shell",
                        "--detach",
                        "-p",
                        projectFile(dir, MINIMAL));

        assertThat(code).isZero();
        // Said out loud: this writes into the operator's own podman configuration.
        assertThat(out.toString()).contains("hooks").contains("registered with podman");
        assertThat(dir.resolve("config/containers/oci/hooks.d/porelo-hook-nft-createRuntime.json"))
                .exists();
    }

    @Test
    void bringsDescriptorsAnUpgradeLeftBehindUpToDate(@TempDir Path dir) throws IOException {

        // The other repairable state. A package replaces the binaries and never touches these
        // files, so a release that changes what a descriptor says leaves the old one in place.
        final PoreloContext context = context(dir, true);
        final Path descriptor =
                dir.resolve("config/containers/oci/hooks.d/porelo-hook-nft-poststop.json");
        Files.writeString(descriptor, "{\"version\":\"1.0.0\",\"hook\":{}}");

        final int code =
                execute(
                        context,
                        "task",
                        "start",
                        "--attach",
                        "shell",
                        "--detach",
                        "-p",
                        projectFile(dir, MINIMAL));

        assertThat(code).isZero();
        assertThat(out.toString()).contains("brought up to date");
        assertThat(Files.readString(descriptor)).contains("porelo-hook-nft");
    }

    @Test
    void writesThePolicyBeforeCreatingTheContainer(@TempDir Path dir) throws IOException {

        // The nft hook reads both files while the container is being created. Writing them
        // afterwards would leave a window with a container and no firewall.
        execute(
                context(dir, true),
                "task",
                "start",
                "--attach",
                "shell",
                "-p",
                projectFile(dir, MINIMAL));

        final Path state = root.resolve("run/porelo").resolve(containerName());
        assertThat(state.resolve("ruleset.nft")).exists();
        assertThat(state.resolve("sidecar.json")).exists();
        assertThat(Files.readString(state.resolve("ruleset.nft"))).contains("policy drop;");
    }

    /**
     * The host key a person confirmed: without it a start over ssh stops before anything is
     * created.
     */
    private static void trustGithub(final PoreloContext context) throws IOException {
        final Path known = FollowCredential.knownHostsFile(context);
        Files.createDirectories(known.getParent());
        Files.writeString(known, "github.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample\n");
    }

    /** Runs git, or fails the test with what it said. */
    private static String git(final Path in, final String... arguments) throws IOException {
        final java.util.List<String> command =
                new java.util.ArrayList<>(
                        java.util.List.of(
                                "git",
                                "-c",
                                "user.name=T",
                                "-c",
                                "user.email=t@example.org",
                                "-c",
                                "init.defaultBranch=main"));
        command.addAll(java.util.List.of(arguments));
        final ProcessBuilder builder =
                new ProcessBuilder(command).directory(in.toFile()).redirectErrorStream(true);
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        final Process process = builder.start();
        final String said = new String(process.getInputStream().readAllBytes()).strip();
        try {
            assertThat(process.waitFor()).as(said).isZero();
        } catch (InterruptedException ex) {
            throw new IOException(ex);
        }
        return said;
    }

    /** Keeps an earlier file in the mirror while the upstream has moved on. */
    private String changedRepositoryImage(Path dir, String before, String after)
            throws IOException {
        final Path upstream = Files.createDirectories(dir.resolve("upstream"));
        git(upstream, "init", "-q");
        Files.writeString(upstream.resolve("porelo.yml"), before);
        git(upstream, "add", "porelo.yml");
        git(upstream, "commit", "-q", "-m", "before");
        final Path mirror = dir.resolve("data/porelo/mirrors/uc.git");
        Files.createDirectories(mirror.getParent());
        git(dir, "clone", "-q", "--bare", upstream.toString(), mirror.toString());
        Files.writeString(upstream.resolve("porelo.yml"), after);
        git(upstream, "commit", "-q", "-am", "after");
        return projectFile(
                dir,
                MINIMAL.replace(
                        "  security_class: \"guarded\"",
                        "  security_class: \"guarded\"\n  upstream: \"file://%s\""
                                .formatted(upstream)));
    }

    /** Uses real local git for image reads and the fake runtime for containers. */
    private PoreloContext repositoryImageContext(Path dir) throws IOException {
        return context(dir, true);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "--no-gate", "--dry-run"})
    void aRefusedLocalOverrideStopsBeforeHostSidePreparation(String option, @TempDir Path dir)
            throws IOException {
        // A fetch cannot repair a local file, so its refusal must precede host-side changes.
        final PoreloContext context = context(dir, false);
        hookBinaries(dir);
        final Path override = dir.resolve("config/porelo/projects/uc/uc/.porelo.yml");
        Files.createDirectories(override.getParent());
        Files.writeString(override, "egress: {}\n");
        final List<String> arguments =
                new ArrayList<>(
                        List.of(
                                "task",
                                "start",
                                "--attach",
                                "shell",
                                "-p",
                                projectFile(dir, MINIMAL)));
        if (!option.isEmpty()) {
            arguments.add(option);
        }

        final int exit = execute(context, arguments.toArray(String[]::new));

        assertThat(exit).as("a refused local source stops the start: %s", err).isEqualTo(64);
        assertThat(context.paths().hooksDirectory())
                .as("local refusal must not install hooks")
                .doesNotExist();
        assertThat(runner.lines())
                .as("local refusal must not clone or fetch a repository")
                .noneMatch(
                        line ->
                                line.startsWith("git ")
                                        && (line.contains("clone") || line.contains("fetch")));
        assertThat(err.toString())
                .as("a local refusal must name its own source")
                .contains("egress")
                .contains("(local override)")
                .contains("porelo: nothing was created")
                .doesNotContain("mirror");
    }

    @Test
    void aStartWhoseTransportBorrowsFromAShutVaultExits77AndMakesNothing(@TempDir Path dir)
            throws IOException {
        // Read as "she holds no token", the transport would refuse for the wrong reason, with the
        // one instruction that cannot help: 'vault unlock' is what it needs.
        final PoreloContext context = countingContext(dir);
        Files.createDirectories(context.vault().path().getParent());
        context.vault().write(java.util.Map.of(), "test-passphrase".toCharArray());
        final Path adapters =
                context.paths().messaging().transportDirectory().locations().getFirst();
        Files.createDirectories(adapters);
        final Path adapter = adapters.resolve(TransportDirectory.PREFIX + "room");
        Files.writeString(adapter, "#!/bin/sh\n");
        adapter.toFile().setExecutable(true);
        runner.answering(
                "describe",
                "{\"scheme\":\"room\",\"secrets\":\"input\",\"lifecycle\":[\"setup\",\"enroll\"],"
                        + "\"credentials\":[{\"name\":\"PORELO_ROOM_TOKEN\",\"lent\":[\"setup\"]}]}");

        final int started =
                execute(
                        context,
                        "task",
                        "start",
                        "-p",
                        projectFile(
                                dir,
                                MINIMAL
                                        + "mail:\n  transports:\n    room: {}\n"
                                        + "  peers:\n    reviewer: { address: 'room:',"
                                        + " trust: vouched }\n"),
                        "--agent",
                        "preview",
                        "--no-gate");

        assertThat(started).as("the vault is shut, and says so: %s", err).isEqualTo(77);
        assertThat(err.toString()).contains("vault unlock", "nothing was created");
        assertThat(runner.lines())
                .as("the transport was not run: %s", runner.lines())
                .noneMatch(line -> line.contains(" setup ") || line.contains(" enroll "));
    }

    @Test
    void aStartWithoutAGateRefusesDeclaredHostServicesBeforeAnythingIsMade(@TempDir Path dir)
            throws IOException {
        // A host service is answered with the gate's address, which a start without a gate has
        // none of: it failed late, at the image, after the task had joined its conversation.
        final PoreloContext context = context(dir, false);
        hookBinaries(dir);
        final String file =
                projectFile(
                        dir,
                        MINIMAL
                                + """
                                egress:
                                  host_services: ["forge.internal.example"]
                                """);

        final int exit =
                execute(context, "task", "start", "--attach", "shell", "--no-gate", "-p", file);

        assertThat(exit).as("the combination is refused as a usage error: %s", err).isEqualTo(64);
        assertThat(err.toString())
                .as("the refusal names the host service and that nothing exists")
                .contains("forge.internal.example")
                .contains("--no-gate")
                .contains("porelo: nothing was created")
                .doesNotContain("NullPointerException");
        assertThat(runner.lines())
                .as("a refused start must leave no image or container")
                .noneMatch(line -> line.contains("podman build") || line.contains("podman create"));
        assertThat(context.paths().tasks().containerState("porelo-uc-shell"))
                .as("nor its state, made when it joins its conversation")
                .doesNotExist();
    }

    @Test
    void aStartFetchesBeforeRefusingTheRepositorysImage(@TempDir Path dir) throws IOException {
        // A stale refusal must not prevent the fetch that can repair it.
        final String file = changedRepositoryImage(dir, "egress: {}\n", "limits: {}\n");
        final int exit =
                execute(
                        repositoryImageContext(dir),
                        "task",
                        "start",
                        "--attach",
                        "shell",
                        "-p",
                        file);

        assertThat(exit).as("the fetched file still names a forbidden key").isEqualTo(64);
        assertThat(err.toString())
                .as("the fetched file decides, not the stale mirror")
                .contains("limits")
                .doesNotContain("egress");
        assertThat(runner.invocations().stream().map(command -> command.arguments()))
                .as("a refused image must never be built")
                .noneMatch(arguments -> arguments.contains("build"));
    }

    @Test
    void aStartRecoversWhenTheFetchedRepositoryImageIsValid(@TempDir Path dir) throws IOException {
        final String file =
                changedRepositoryImage(dir, "egress: {}\n", "image:\n  snippet: RUN true\n");
        final int exit =
                execute(
                        repositoryImageContext(dir),
                        "task",
                        "start",
                        "--attach",
                        "shell",
                        "-p",
                        file);

        assertThat(exit).as("the repaired upstream permits a start: %s", err).isZero();
        assertThat(out.toString())
                .as("the deferred choice must be reported even after a refused preview")
                .contains("read after the fetch")
                .contains("image lines    from porelo.yml at refs/heads/main of uc");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"--dry-run", "--no-gate"})
    void aStartWithoutAFetchRefusesAndNamesTheMirrorsFile(String option, @TempDir Path dir)
            throws IOException {
        final String file = changedRepositoryImage(dir, "egress: {}\n", "limits: {}\n");
        final int exit =
                execute(
                        repositoryImageContext(dir),
                        "task",
                        "start",
                        "--attach",
                        "shell",
                        option,
                        "-p",
                        file);

        assertThat(exit).as("without a fetch the mirror's refusal stands").isEqualTo(64);
        assertThat(err.toString())
                .as("the refusal must identify how current its source is")
                .contains("egress")
                .contains("mirror")
                .contains("last fetch")
                .contains("porelo: nothing was created")
                .doesNotContain("limits");
        assertThat(
                        git(
                                dir,
                                "--git-dir",
                                dir.resolve("data/porelo/mirrors/uc.git").toString(),
                                "show",
                                "HEAD:porelo.yml"))
                .as("a preview or an ungated start must not fetch")
                .isEqualTo("egress: {}");
    }

    @Test
    void aStartsSnippetLineNamesTheRepositoryFile(@TempDir Path dir) throws IOException {
        final String file =
                changedRepositoryImage(
                        dir,
                        "image:\n  snippet: RUN true\n",
                        "image:\n  snippet: RUN true\n  env: {FROM_UPSTREAM: yes}\n");
        final int exit =
                execute(
                        repositoryImageContext(dir),
                        "task",
                        "start",
                        "--attach",
                        "shell",
                        "--no-gate",
                        "-p",
                        file);

        assertThat(exit).as("a valid repository image starts: %s", err).isZero();
        assertThat(out.toString())
                .as("the build must attribute its snippet to the file that supplied it: %s", err)
                .contains("snippet   1 lines from porelo.yml at refs/heads/main of uc");
    }

    /**
     * A project of the given class on a repository of this machine, whose gate holds what a removed
     * 'second' left.
     */
    private String leftByARemovedTask(final Path dir, final String securityClass)
            throws IOException {
        final Path upstream = dir.resolve("upstream.git");
        git(dir, "init", "-q", "--bare", upstream.toString());
        final Path seed = java.nio.file.Files.createDirectories(dir.resolve("seed"));
        git(seed, "init", "-q");
        git(seed, "commit", "-q", "--allow-empty", "-m", "first");
        git(seed, "push", "-q", upstream.toString(), "HEAD:refs/heads/main");
        final String file =
                projectFile(
                        dir,
                        """
                project:
                  name: "uc"
                  security_class: "%s"
                  upstream: "file://%s"
                image:
                  base_image: "ubuntu:24.04"
                """
                                .formatted(securityClass, upstream));
        // A first task makes the gate, and a task called 'second' worked there before it was
        // removed.
        assertThat(execute(context(dir, true), "task", "start", "--attach", "shell", "-p", file))
                .as("the start said:%n%s%n%s", out, err)
                .isZero();
        git(seed, "commit", "-q", "--allow-empty", "-m", "the removed task's work");
        git(
                seed,
                "push",
                "-q",
                root.resolve("data/porelo/mirrors/uc.git").toString(),
                "HEAD:refs/porelo/incoming/second");
        return file;
    }

    @Test
    void aNewGuardedTaskIsRefusedWhileWorkOfAnEarlierOneOfItsNameWaitsAtTheGate(@TempDir Path dir)
            throws IOException {
        // A removed task's work waited at the gate; a new task of the same name was refused its
        // plain push with "fetch first", and an agent that followed git's hint would have pulled
        // that work into its own.
        final String file = leftByARemovedTask(dir, "guarded");

        final int exit =
                execute(
                        context(dir, true),
                        "task",
                        "start",
                        "second",
                        "--attach",
                        "shell",
                        "-p",
                        file);

        assertThat(exit).as("the start said:%n%s%n%s", out, err).isEqualTo(65);
        assertThat(err.toString())
                .contains("work of an earlier task called second waits at the gate")
                .contains("the removed task's work")
                .contains("nothing was created");
        assertThat(runner.invocations().stream().map(command -> command.describe()))
                .noneMatch(line -> line.contains("create") && line.contains("porelo-uc-second"));
    }

    @Test
    void aNewOnlineTaskStartsCleanSinceWhatAnEarlierOneOfItsNameLeftWentOnLongAgo(@TempDir Path dir)
            throws IOException {
        final String file = leftByARemovedTask(dir, "online");

        final int exit =
                execute(
                        context(dir, true),
                        "task",
                        "start",
                        "second",
                        "--attach",
                        "shell",
                        "-p",
                        file);

        assertThat(exit).as("the start said:%n%s%n%s", out, err).isZero();
        assertThat(
                        git(
                                dir,
                                "--git-dir",
                                root.resolve("data/porelo/mirrors/uc.git").toString(),
                                "for-each-ref",
                                "refs/porelo/incoming/"))
                .as("what the removed task left")
                .doesNotContain("incoming/second");
    }

    @Test
    void anOnlineTaskReachesItsUpstreamOnlyThroughItsGateAndHoldsNoKeyOrSocketOfOne(
            @TempDir Path dir) throws IOException {

        // Its remote was the upstream, over ssh, with an ssh-agent socket that answered the forge
        // with the real key: the agent could push wherever that key reached. Now its gate passes
        // its own branch on, from the host. A repository on this machine: the host fills the gate
        // from it, which no container could reach.
        final Path upstream = dir.resolve("upstream.git");
        git(dir, "init", "-q", "--bare", "--initial-branch=main", upstream.toString());
        final PoreloContext context = context(dir, true);
        execute(
                context,
                "task",
                "start",
                "--attach",
                "shell",
                "-p",
                projectFile(
                        dir,
                        """
                project:
                  name: "uc"
                  security_class: "online"
                  upstream: "file://%s"
                image:
                  base_image: "ubuntu:24.04"
                """
                                .formatted(upstream)));

        final Path state = root.resolve("run/porelo").resolve(containerName());
        assertThat(state.resolve("ruleset.nft")).as("the start said:%n%s%n%s", out, err).exists();
        assertThat(err.toString()).as("the start said:%n%s", out).doesNotContain("no git gate");
        assertThat(Files.readString(state.resolve("ruleset.nft")))
                .doesNotContain("upstream_v4")
                .doesNotContain("dport 22");
        assertThat(runner.only("create").describe())
                .as("the start said:%n%s%n%s", out, err)
                .doesNotContain("ssh-agent.sock")
                .doesNotContain("SSH_AUTH_SOCK")
                .doesNotContain("GIT_SSH_COMMAND")
                .contains("--env PORELO_TASK_REF");
        // That its gate passes the push on is GateServingTest's: the record of it exists only where
        // a 'porelo' started.
    }

    @Test
    void aGuardedTaskReachesNoUpstreamOverSshSinceItsWorkLeavesThroughTheGate(@TempDir Path dir)
            throws IOException {

        final PoreloContext context = context(dir, true);
        trustGithub(context);
        final int exit =
                execute(
                        context,
                        "task",
                        "start",
                        "--attach",
                        "shell",
                        "-p",
                        projectFile(
                                dir,
                                """
                project:
                  name: "uc"
                  security_class: "guarded"
                  upstream: "git@github.com:example/uc.git"
                image:
                  base_image: "ubuntu:24.04"
                """));

        assertThat(exit).as("the start said:%n%s%n%s", out, err).isZero();
        final Path state = root.resolve("run/porelo").resolve(containerName());
        assertThat(Files.readString(state.resolve("ruleset.nft")))
                .doesNotContain("upstream_v4")
                .doesNotContain("dport 22");
    }

    @Test
    void annotatesTheContainerSoTheHooksFire(@TempDir Path dir) throws IOException {

        execute(
                context(dir, true),
                "task",
                "start",
                "--attach",
                "shell",
                "-p",
                projectFile(dir, MINIMAL));

        assertThat(runner.only("create").describe())
                .contains("--annotation org.fuin.porelo.sidecar=")
                .contains("--security-opt no-new-privileges");
    }

    @Test
    void handsTheTerminalToTheShell(@TempDir Path dir) throws IOException {

        execute(
                context(dir, true),
                "task",
                "start",
                "--attach",
                "shell",
                "-p",
                projectFile(dir, MINIMAL),
                "--shell",
                "/bin/sh");

        // Through the task's ONE session, not beside it. A plain exec ran the agent as a child of
        // this terminal: closing the window took it with it, and attaching from anywhere else
        // created a second, empty session and showed a bare shell in the workspace.
        assertThat(execCalls).hasSize(1);
        assertThat(execCalls.getFirst())
                .startsWith(
                        "podman",
                        "exec",
                        "--interactive",
                        "--tty",
                        containerName(),
                        "tmux",
                        "-f",
                        "/etc/porelo/tmux.conf",
                        "new-session",
                        "-A",
                        "-s",
                        "porelo");
        // One argument, because tmux joins several into one string with spaces and would take the
        // script apart at its quoting. The shell is a child, not an exec: the marker that says the
        // work ended is written after it returns, and 'exec' would leave nothing to write it.
        assertThat(execCalls.getFirst().getLast())
                .contains("/bin/sh -l; : > ")
                .doesNotContain("exec /bin/sh");
    }

    @Test
    void tellsTheContainerWhichTerminalTheOperatorIsAt(@TempDir Path dir) throws IOException {

        // End to end through the wiring, because the parts were right and the thread between them
        // was not: the context builds the runner, the runner builds podman, and each of them used
        // to fall back to this process's own environment. What that cost was not production - it
        // was a test that read the terminal of whoever ran it and asserted something different in
        // CI, which is the same class of defect as the one this feature fixes.
        final PoreloContext context =
                context(dir, true, name -> "TERM".equals(name) ? "xterm-256color" : null);
        runner.answering("podman exec " + containerName() + " infocmp xterm-256color", "");

        execute(
                context,
                "task",
                "start",
                "--attach",
                "shell",
                "-p",
                projectFile(dir, MINIMAL),
                "--shell",
                "/bin/sh");

        assertThat(execCalls.getFirst())
                .containsSubsequence("--env", "TERM=xterm-256color", containerName());
    }

    @Test
    void aTestContextReadsNoEnvironmentAtAll() {

        // The seam that keeps the test above honest. A context built the three-argument way - which
        // is how every test builds one - must read nothing from this process, or a suite passes on
        // a developer's machine and asserts something else where there is no terminal.
        assertThat(
                        new PoreloContext(runner, PoreloPaths.current(), arguments -> 0)
                                .environment()
                                .apply("TERM"))
                .isNull();
    }

    @Test
    void askingForTheAgentsSessionWithNoAgentIsRefusedBeforeAnythingIsCreated(@TempDir Path dir)
            throws IOException {

        // It used to attach a plain shell and record the mode as AGENT, so a task said it was
        // something it was not - and the person who asked for an agent got a bare prompt with
        // nothing explaining why. CanStart already answered NO_AGENT for this, so the check and the
        // launch disagreed about the same machine.
        final int code =
                execute(context(dir, true), "task", "start", "-p", projectFile(dir, MINIMAL));

        assertThat(code).isEqualTo(69);
        assertThat(err.toString()).contains("no agent to attach");
        assertThat(execCalls).as("refused before a container exists to attach to").isEmpty();
    }

    @Test
    void aShellTaskNeedsNoAgentAtAll(@TempDir Path dir) throws IOException {

        // Working inside the container by hand is exactly what a shell task is for, so refusing it
        // for want of an agent would take away the case the refusal above points people at.
        final int code =
                execute(
                        context(dir, true),
                        "task",
                        "start",
                        "--attach",
                        "shell",
                        "-p",
                        projectFile(dir, MINIMAL));

        assertThat(code).isZero();
        assertThat(execCalls).hasSize(1);
    }

    @Test
    void aPromptWithNoAgentIsRefusedRatherThanReportedAsDone(@TempDir Path dir) throws IOException {

        // The difference between "started" and "ran". A prompt asks for work to be performed, and a
        // run that answers success without an agent having done anything leaves somebody waiting
        // for output that was never going to come.
        final int code =
                execute(
                        context(dir, true),
                        "task",
                        "start",
                        "-p",
                        projectFile(dir, MINIMAL),
                        "-P",
                        "fix the parser");

        assertThat(code).isEqualTo(69);
        assertThat(err.toString()).contains("a prompt needs an agent");
    }

    @Test
    void removesAContainerThatWasNeverCreated(@TempDir Path dir) throws IOException {

        // Nothing exists to hold, so the tidy-up runs as it always did.
        runner.failing("start", 125, "hook failed");
        runner.failing("{{.State.Status}}", 125, "no such container 0123456789abcdef");

        final int code =
                execute(
                        context(dir, true),
                        "task",
                        "start",
                        "--rm",
                        "--attach",
                        "shell",
                        "-p",
                        projectFile(dir, MINIMAL));

        assertThat(code).isEqualTo(70);
        assertThat(err.toString()).contains("hook failed");
        assertThat(runner.lines()).anyMatch(line -> line.contains("rm --force"));
        assertThat(execCalls)
                .as("no shell is attached to a container that never started")
                .isEmpty();
    }

    @Test
    void holdsAFailedTaskInsteadOfRemovingIt(@TempDir Path dir) throws IOException {

        // --keep has to be decided before the run, and the run worth looking at is the one that
        // went wrong, which is known only afterwards. So a failure is held rather than swept up.
        // Built BEFORE the broad answers below: the fake takes the first key that matches, and
        // 'exec' would otherwise swallow the session check the fixture registers - reading a
        // container with no session as one somebody had detached from, so nothing would ever be
        // torn down.
        final PoreloContext prepared = context(dir, true);
        runner.answering("container inspect", "c0ffee\n");
        runner.failing("start", 125, "hook failed");

        final int code =
                execute(
                        prepared,
                        "task",
                        "start",
                        "--rm",
                        "--attach",
                        "shell",
                        "-p",
                        projectFile(dir, MINIMAL));

        assertThat(code).isEqualTo(70);
        assertThat(runner.lines()).noneMatch(line -> line.contains("rm --force"));
        // The advice has to name commands the CLI still has. It named 'task resume' and 'task stop
        // --purge' - both removed at the lifecycle cut - so somebody following a failure report was
        // sent to two commands that refuse.
        assertThat(out.toString())
                .contains("kept")
                .contains("it failed, so nothing was removed")
                .contains("task attach")
                .contains("task remove")
                .doesNotContain("task resume")
                .doesNotContain("--purge");
        // Held, not abandoned: a task nobody is watching that still holds a firewall, a gate and a
        // credential proxy is not kept.
        assertThat(runner.lines()).anyMatch(line -> line.contains("podman stop"));
    }

    @Test
    void keepsTheContainerWhenAskedTo(@TempDir Path dir) throws IOException {

        runner.failing("start", 125, "hook failed");

        execute(context(dir, true), "task", "start", "-p", projectFile(dir, MINIMAL));

        assertThat(runner.lines()).noneMatch(line -> line.contains("rm --force"));
    }

    @Test
    void failsWithoutASubcommand(@TempDir Path dir) throws IOException {

        assertThat(execute(context(dir, true))).isEqualTo(2);
        assertThat(err.toString()).contains("Usage: porelo");
    }

    @Test
    void failsOnAProjectThisMachineDoesNotHave(@TempDir Path dir) throws IOException {

        // A name, not a path - so the answer is no longer "no file there" but "this machine has no
        // such project", which is the question a person actually asked. It names what there is,
        // because which projects a machine has is something only the machine can answer.
        projectFile(dir, MINIMAL);

        assertThat(execute(context(dir, true), "task", "start", "-p", "nowhere", "--dry-run"))
                .isEqualTo(2);
        assertThat(err.toString()).contains("no project 'nowhere'").contains("uc");
    }

    @Test
    void failsOnAnUnreadableProjectFile(@TempDir Path dir) throws IOException {

        final String name = projectFile(dir, "project:\n  name: uc\n");

        assertThat(execute(context(dir, true), "task", "start", "-p", name, "--dry-run"))
                .isEqualTo(2);
        assertThat(err.toString()).contains("no 'image' section");
    }

    @Test
    void rejectsAnUnknownOption(@TempDir Path dir) throws IOException {

        assertThat(
                        execute(
                                context(dir, true),
                                "task",
                                "start",
                                "-p",
                                projectFile(dir, MINIMAL),
                                "--nope"))
                .isEqualTo(2);
    }

    @Test
    void printsTheTwoLinesOtherRepositoriesReadFromIt(@TempDir Path dir) throws IOException {

        // These two lines are a contract, not decoration. The three agent repositories parse them
        // out of a start: all three take 'container' to know what to act on, and porelo-omp's
        // broker-check.sh takes 'sidecar' to find the state directory. Nothing guarded them, so a
        // tidy-up of this output would have gone out green here and turned six acceptance legs red
        // somewhere else. Asked for by name on the agent channel on 2026-09-11.
        execute(
                context(dir, true),
                "task",
                "start",
                "--attach",
                "shell",
                "-p",
                projectFile(dir, MINIMAL));

        assertThat(out.toString())
                .containsPattern("(?m)^container " + containerName() + "$")
                .containsPattern("(?m)^sidecar   \\S+sidecar\\.json$");
    }

    private String containerName() {
        // One container per project and task, with nothing unique appended. It used to carry the
        // launching process's pid, which is why a task could never be found again by its name.
        return "porelo-uc-shell";
    }

    @Test
    void stopsTheHelpersWhenTheContainerNeverStarts(@TempDir Path dir) throws Exception {

        // A container that ran is reaped by the poststop hook. One that never started fires no
        // hook, and the vault proxy, gate and watcher launched before it then outlive the run:
        // measured on both test machines, where each failed run stranded three processes holding
        // their sockets, and the next run failed for a reason unrelated to what changed.
        final PoreloContext context = context(dir, true);
        runner.failing("start", 125, "OCI runtime error");

        final Path state = dir.resolve("run/porelo").resolve(containerName());
        Files.createDirectories(state);
        final Process helper = new ProcessBuilder("sleep", "120").start();
        // Id and start time, as a helper writes them: a bare id is refused, because by the time
        // anything reads a leftover file that number may belong to something else entirely.
        org.fuin.porelo.wire.HelperPid.record(state.resolve("vault.pid"), helper.toHandle());

        execute(
                context,
                "task",
                "start",
                "--attach",
                "shell",
                "-p",
                projectFile(dir, MINIMAL),
                "--detach");

        assertThat(helper.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
                .as("the helper must be stopped, not left holding its socket")
                .isTrue();
        assertThat(state.resolve("vault.pid")).doesNotExist();
    }

    @Test
    void leavesTheHelpersAloneWhileTheContainerRuns(@TempDir Path dir) throws Exception {

        // The mirror of the above: a running container owns its helpers, and reaping them here
        // would break every task that is working normally.
        final PoreloContext context = context(dir, true);
        runner.answering("container inspect", "4711");

        final Path state = dir.resolve("run/porelo").resolve(containerName());
        Files.createDirectories(state);
        Files.writeString(state.resolve("vault.pid"), "4711");

        execute(context, "task", "start", "-p", projectFile(dir, MINIMAL), "--detach");

        assertThat(state.resolve("vault.pid")).exists();
    }

    @Test
    void removesTheContainerWhenTheShellExits(@TempDir Path dir) throws IOException {

        // The bug this exists for: attaching used to replace this process, so nothing was left to
        // remove the container. Every interactive task leaked one while printing the opposite.
        execute(context(dir, true), "task", "start", "--rm", "-p", projectFile(dir, MINIMAL));

        assertThat(runner.invocations())
                .anySatisfy(command -> assertThat(command.describe()).contains("rm"));
    }

    @Test
    void removesTheContainerWhenTheShellsLastCommandFailed(@TempDir Path dir) throws IOException {

        // A shell exits with its last command's status, which says nothing about the task. Reported
        // from a machine where a typo at the prompt - 'bash: /exit: No such file or directory' -
        // made leaving the session print "it failed, so nothing was removed" and keep the
        // container, its firewall and its resolver running.
        execExit = 127;
        // Without this the container does not exist as far as the fake runtime is concerned, the
        // keep branch is unreachable, and the test would pass however the verdict is computed.
        runner.answering("container inspect", "c0ffee\n");

        final int code =
                execute(
                        context(dir, true),
                        "task",
                        "start",
                        "--rm",
                        "--attach",
                        "shell",
                        "-p",
                        projectFile(dir, MINIMAL));

        assertThat(out.toString()).doesNotContain("it failed");
        assertThat(runner.lines()).anyMatch(line -> line.contains("rm --force"));
        // The code still reaches the caller, the way ssh reports a remote command's status.
        assertThat(code).isEqualTo(127);
    }

    @Test
    void doesNotDiscardWorkThatNeverReachedTheGate(@TempDir Path dir) throws IOException {

        // A workspace is in the container's own writable layer, so removing the container destroys
        // it. 'task stop --purge' refuses to do that and offers --rescue; walking out of a shell
        // did it without asking, which is the same destruction by a different route. idOf and pidOf
        // both run 'container inspect' and the fake takes the first match in insertion order, so
        // the more specific key goes first - otherwise the pid query answers 'c0ffee', parses as no
        // pid, and the work check never runs. Built BEFORE the broad answers below: the fake takes
        // the first key that matches, and 'exec' would otherwise swallow the session check the
        // fixture registers - reading a container with no session as one somebody had detached
        // from, so nothing would ever be torn down.
        final PoreloContext prepared = context(dir, true);
        runner.answering("{{.State.Pid}}", "4711\n");
        runner.answering("container inspect", "c0ffee\n");
        // What the container answers when asked: one changed file, two commits nobody pushed.
        runner.answering("exec", "1 2\n");

        execute(
                prepared,
                "task",
                "start",
                "--rm",
                "--attach",
                "shell",
                "-p",
                projectFile(dir, MINIMAL));

        assertThat(runner.lines())
                .as("work that exists nowhere else must not be removed")
                .noneMatch(line -> line.contains("rm --force"));
        assertThat(out.toString())
                .contains("kept")
                .contains("never reached the gate")
                .contains("--rescue");
    }

    @Test
    void anAttachedEndingNamesASurvivorAndFails(@TempDir Path dir) throws IOException {

        final PoreloContext prepared = context(dir, true).recognizingTasks();
        runner.answering("{{.State.Status}}", "running");
        runner.failing("podman stop", 125, "runtime refused");
        runner.answering("{{.State.Pid}}", "4711\n");
        runner.answering("container inspect", "c0ffee\n");
        // What the container answers when asked: one changed file, two commits nobody pushed.
        runner.answering("exec", "1 2\n");

        final int exit =
                execute(
                        prepared,
                        "task",
                        "start",
                        "--rm",
                        "--attach",
                        "shell",
                        "-p",
                        projectFile(dir, MINIMAL));

        assertThat(exit).as("an attached ending fails when its container survives").isEqualTo(70);
        assertThat(err.toString())
                .as("the attached caller names the survivor")
                .contains("porelo: still running: container ");
    }

    @Test
    void anAttachedEndingWithAFailedIdentityLookupVerifiesTheRecordedId(@TempDir Path dir)
            throws IOException {
        final PoreloContext prepared = context(dir, true).recognizingTasks();
        runner.failing("{{.Id}}", 125, "inspect failed");
        runner.answering("{{.State.Status}}", "running");
        runner.failing("podman stop", 125, "runtime refused");
        runner.failing("podman rm", 125, "runtime refused");
        final int exit =
                execute(
                        prepared,
                        "task",
                        "start",
                        "--rm",
                        "--attach",
                        "shell",
                        "-p",
                        projectFile(dir, MINIMAL));
        assertThat(exit)
                .as("a failed lookup cannot turn a surviving container into success")
                .isEqualTo(70);
        assertThat(err.toString())
                .as("cleanup names the unconfirmed recorded identity")
                .contains("porelo: still running: container ");
        assertThat(runner.lines())
                .as("cleanup verifies the identity recorded at creation")
                .contains("podman container inspect --format {{.State.Status}} 0123456789abcdef");
    }

    @Test
    void anAttachedEndingFailsWhenCleanupThrows(@TempDir Path dir) throws IOException {
        final PoreloContext prepared = context(dir, true).recognizingTasks();
        final PoreloContext session =
                new PoreloContext(
                                prepared.runner(),
                                prepared.paths(),
                                arguments -> {
                                    runner.failing("podman ps", 125, "inventory unavailable");
                                    return 0;
                                },
                                prepared.environment())
                        .recognizingTasks();
        runner.answering("container inspect", "0123456789abcdef");
        final int exit =
                execute(
                        session,
                        "task",
                        "start",
                        "--rm",
                        "--attach",
                        "shell",
                        "-p",
                        projectFile(dir, MINIMAL));
        assertThat(exit)
                .as("a cleanup exception replaces a successful attached status")
                .isEqualTo(70);
        assertThat(err.toString())
                .as("cleanup exceptions are reported on stderr")
                .contains("cleaning up after it failed")
                .contains("inventory unavailable");
    }

    @Test
    void removesATaskThatHandedEverythingBack(@TempDir Path dir) throws IOException {

        // The negative case, and the common one: nothing uncommitted and nothing unpushed, so there
        // is nothing to protect and the container goes. Keeping it would bring back the pile of
        // dead containers this is meant to avoid. Built BEFORE the broad answers below: the fake
        // takes the first key that matches, and 'exec' would otherwise swallow the session check
        // the fixture registers - reading a container with no session as one somebody had detached
        // from, so nothing would ever be torn down.
        final PoreloContext prepared = context(dir, true);
        runner.answering("{{.State.Pid}}", "4711\n");
        runner.answering("container inspect", "c0ffee\n");
        runner.answering("exec", "0 0\n");

        execute(
                prepared,
                "task",
                "start",
                "--rm",
                "--attach",
                "shell",
                "-p",
                projectFile(dir, MINIMAL));

        assertThat(runner.lines()).anyMatch(line -> line.contains("rm --force"));
        assertThat(out.toString()).doesNotContain("never reached the gate");
    }

    @Test
    void keepsATaskWhoseSessionNeverFinished(@TempDir Path dir) throws IOException {

        // The other half of the same decision. Somebody who walked out of a shell ended the task;
        // somebody whose terminal died did not, and the workspace may hold commits that never
        // reached the gate. So this one is stopped and held rather than removed.
        execDies = true;
        runner.answering("container inspect", "c0ffee\n");

        execute(
                context(dir, true),
                "task",
                "start",
                "--attach",
                "shell",
                "-p",
                projectFile(dir, MINIMAL));

        assertThat(runner.lines())
                .as("an interrupted run must not be swept up")
                .noneMatch(line -> line.contains("rm --force"));
    }

    @Test
    void labelsTheContainerWithItsProjectAndClass(@TempDir Path dir) throws IOException {

        // So a listing can still say what a task belongs to after a reboot. The sidecar holding the
        // same facts is in $XDG_RUNTIME_DIR and does not survive one.
        execute(
                context(dir, true),
                "task",
                "start",
                "--attach",
                "shell",
                "--detach",
                "-p",
                projectFile(dir, MINIMAL));

        assertThat(runner.lines())
                .anyMatch(
                        line ->
                                line.contains("create")
                                        && line.contains("--label org.fuin.porelo.project=uc")
                                        && line.contains("--label org.fuin.porelo.class=guarded"));
    }

    /**
     * A project with a second repository, so a label can be wrong in a visible way.
     *
     * <p><strong>No upstream on it, deliberately.</strong> The gate is built with a real command
     * runner even here, so a repository that named one would have this test cloning from that
     * address - and an unreachable host does not fail, it waits. Measured: the run hung until it
     * was killed. A repository with no upstream is a legitimate one whose work stays on this
     * machine, and it exercises the label exactly as well.
     */
    private static final String TWO_REPOSITORIES =
            MINIMAL
                    + """
            repositories:
              backend:
            """;

    @Test
    void labelsTheContainerWithTheRepositoryTheTaskWorksOn(@TempDir Path dir) throws IOException {

        // A task works on exactly one repository and that is fixed when it is created, so a listing
        // has to be able to say which one after a reboot - and bringing a stopped task back has to
        // carry it over. The sidecar cannot answer: it predates repositories.
        execute(
                context(dir, true),
                "task",
                "start",
                "--attach",
                "shell",
                "--detach",
                "-p",
                projectFile(dir, TWO_REPOSITORIES),
                "--repository",
                "backend");

        assertThat(runner.lines())
                .anyMatch(
                        line ->
                                line.contains("create")
                                        && line.contains(
                                                "--label org.fuin.porelo.repository=backend"));
    }

    @Test
    void aTaskIsToldTheIssuePrefixesItsRepositoryOwns(@TempDir Path dir) throws IOException {

        // So an agent cites and names its repository's issues the way the repository does, without
        // a list of its own.
        execute(
                context(dir, true),
                "task",
                "start",
                "--attach",
                "shell",
                "--detach",
                "-p",
                projectFile(
                        dir,
                        MINIMAL
                                + """
                        repositories:
                          backend:
                            issues: ["B", "P"]
                        """),
                "--repository",
                "backend");

        assertThat(runner.only("create").environment())
                .as("the value, in the environment podman copies it from")
                .containsEntry(TaskLaunch.ISSUE_PREFIXES, "B,P");
        assertThat(runner.lines())
                .as("the name alone on the command line")
                .anyMatch(
                        line ->
                                line.contains("create")
                                        && line.contains(
                                                "--env " + TaskLaunch.ISSUE_PREFIXES + " "));
    }

    @Test
    void aTaskOnARepositoryWithoutIssuesIsToldNone(@TempDir Path dir) throws IOException {
        execute(
                context(dir, true),
                "task",
                "start",
                "--attach",
                "shell",
                "--detach",
                "-p",
                projectFile(dir, TWO_REPOSITORIES),
                "--repository",
                "backend");

        assertThat(runner.only("create").environment())
                .containsEntry(TaskLaunch.ISSUE_PREFIXES, "");
    }

    @Test
    void refusesTheOwnRepositoryOfAProjectThatNamesOthers(@TempDir Path dir) throws IOException {

        // It holds project.yml, which decides what every task of the project may reach.
        final CommandLine cmd = PoreloCli.commandLine(context(dir, true));
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));

        final int code =
                cmd.execute(
                        "task",
                        "start",
                        "--attach",
                        "shell",
                        "--detach",
                        "-p",
                        projectFile(dir, TWO_REPOSITORIES),
                        "--repository",
                        "uc");

        assertThat(code).isNotZero();
        assertThat(err.toString())
                .contains("'uc' is the project's own repository")
                .contains("works in:");
        assertThat(runner.lines()).noneMatch(line -> line.contains(" create "));
    }

    @Test
    void leavesTheContainerAloneWithKeep(@TempDir Path dir) throws IOException {

        // The negative case: --keep has to mean something, and today it did not.
        execute(context(dir, true), "task", "start", "-p", projectFile(dir, MINIMAL));

        // 'rm --force', which is how a task container is removed - not any argument containing
        // "rm". A throwaway container elsewhere in the run legitimately passes --rm, and matching
        // that made this fail for the wrong reason.
        assertThat(runner.invocations())
                .noneSatisfy(command -> assertThat(command.describe()).contains("rm --force"));
    }

    @Test
    void attachesAPlainShellWhenAsked(@TempDir Path dir) throws IOException {

        // The negative case: --attach shell must not wrap anything around the shell.
        execute(
                context(dir, true),
                "task",
                "start",
                "--attach",
                "shell",
                "-p",
                projectFile(dir, MINIMAL));

        // No agent runs, so there is no full-screen interface to clean up after.
        assertThat(String.join(" ", execCalls.getLast())).doesNotContain("stty sane");
    }

    @Test
    void noticesTheVaultCopyHasFallenBehind() {

        // An agent that refreshes its own token leaves the imported copy behind, and the task then
        // fails as an authentication error with no hint that a refresh would fix it.
        assertThat(
                        CredentialChoice.staleCredential(
                                new org.fuin.porelo.vault.VaultEntry("old-token", "oauth"),
                                org.fuin.porelo.agent.api.Credential.of("oauth", "new-token")))
                .isTrue();
    }

    @Test
    void saysNothingWhenTheCopyIsCurrentOrDeliberatelyDifferent() {

        // Three negatives, and the middle one matters most: storing a different kind on purpose is
        // not staleness, and warning about it every run would teach the operator to ignore it.
        assertThat(
                        CredentialChoice.staleCredential(
                                new org.fuin.porelo.vault.VaultEntry("same", "oauth"),
                                org.fuin.porelo.agent.api.Credential.of("oauth", "same")))
                .isFalse();
        assertThat(
                        CredentialChoice.staleCredential(
                                new org.fuin.porelo.vault.VaultEntry("a-key", "api-key"),
                                org.fuin.porelo.agent.api.Credential.of("oauth", "a-token")))
                .isFalse();
        assertThat(
                        CredentialChoice.staleCredential(
                                null, org.fuin.porelo.agent.api.Credential.of("oauth", "a-token")))
                .isFalse();
        assertThat(
                        CredentialChoice.staleCredential(
                                new org.fuin.porelo.vault.VaultEntry("a-key", "api-key"), null))
                .isFalse();
    }

    @Test
    void detachingLeavesTheTaskRunningAndTearsDownNothing(@TempDir Path dir) throws IOException {

        // The dangerous half of running in a session. Leaving the window ends the command this
        // terminal was attached to, exactly as finishing does - and until the session could be
        // asked, both read as "the work is over". Tearing down on a detach would stop the gate, the
        // credential broker and the clearance watcher under a running agent, which is the state the
        // contract warns about: a container that is up with no helpers.
        final PoreloContext prepared = context(dir, true);
        // No marker: the shell never returned, so nobody finished.
        runner.failing("test -f", 1, "");

        execute(
                prepared,
                "task",
                "start",
                "--rm",
                "--attach",
                "shell",
                "-p",
                projectFile(dir, MINIMAL));

        assertThat(runner.lines())
                .as("the container stays, --rm or not: it is still running")
                .noneMatch(line -> line.contains("rm --force"));
        assertThat(runner.lines())
                .as("and nothing stops it either")
                .noneMatch(line -> line.contains("podman stop"));
        assertThat(out.toString())
                .contains("detached")
                .contains("still running")
                .contains("task attach");
    }

    @Test
    void aMarkerFromTheWindowsShellIsTheWorkBeingOver(@TempDir Path dir) throws IOException {

        // The other half: the window's shell returned and wrote the marker, which is the only
        // unambiguous statement that the work is over.
        final PoreloContext prepared = context(dir, true);
        runner.answering("test -f", "");
        runner.answering("{{.State.Pid}}", "4711\n");
        runner.answering("container inspect", "c0ffee\n");
        runner.answering("exec", "0 0\n");

        execute(
                prepared,
                "task",
                "start",
                "--rm",
                "--attach",
                "shell",
                "-p",
                projectFile(dir, MINIMAL));

        assertThat(runner.lines()).anyMatch(line -> line.contains("rm --force"));
        assertThat(out.toString()).doesNotContain("detached");
    }

    @Test
    void aFollowedProjectsVerifiedFileWinsOverTheOneInTheDirectory(@TempDir Path dir)
            throws IOException {

        // The measurement that matters: start a task from a directory holding a DIFFERENT
        // project.yml and show that the directory's file had no effect. Following checks a
        // signature against a key pinned out of band, and reading anything else afterwards throws
        // that check away.
        final PoreloContext context = context(dir, true);
        new FollowedProjects(context.paths().projects().followed())
                .write(
                        new FollowedProjects.Followed(
                                "uc",
                                "git@example.com:x/uc.git",
                                "a1b2c3d4",
                                "2026-09-19T00:00:00Z",
                                "APPLIED",
                                ""));
        final Path clone = context.paths().projects().followedClone("uc");
        Files.createDirectories(clone);
        Files.writeString(
                clone.resolve("project.yml"),
                MINIMAL.replace("base_image: \"ubuntu:24.04\"", "base_image: \"ubuntu:22.04\""));

        // The directory says 24.04; the verified clone says 22.04.
        execute(context, "task", "start", "-p", projectFile(dir, MINIMAL), "--dry-run");

        assertThat(out.toString()).contains("base image     ubuntu:22.04");
        // And it says where that came from, so nobody wonders why the file they are looking at is
        // not the one being used.
        assertThat(out.toString()).contains("a1b2c3d4").contains("followed, verified");
    }

    @Test
    void aFollowedProjectWithNothingInForceRefusesRatherThanUsingTheLocalFile(@TempDir Path dir)
            throws IOException {

        final PoreloContext context = context(dir, true);
        // Followed and refused - no clone was written.
        new FollowedProjects(context.paths().projects().followed())
                .write(
                        new FollowedProjects.Followed(
                                "uc",
                                "git@example.com:x/uc.git",
                                "",
                                "2026-09-19T00:00:00Z",
                                "UNKNOWN_KEY",
                                "a key that is not pinned"));

        final int code =
                execute(context, "task", "start", "-p", projectFile(dir, MINIMAL), "--dry-run");

        // Falling back would run the very thing the machine declined to apply, and would make a
        // refusal look as though it had no effect.
        assertThat(code).isEqualTo(2);
        assertThat(err.toString()).contains("nothing of it is in force");
    }

    @Test
    void labelsTheContainerWithTheCommitItsConfigurationWasVerifiedAt(@TempDir Path dir)
            throws IOException {

        final PoreloContext context = context(dir, true);
        new FollowedProjects(context.paths().projects().followed())
                .write(
                        new FollowedProjects.Followed(
                                "uc",
                                "git@example.com:x/uc.git",
                                "a1b2c3d4",
                                "2026-09-19T00:00:00Z",
                                "APPLIED",
                                ""));
        final Path clone = context.paths().projects().followedClone("uc");
        Files.createDirectories(clone);
        Files.writeString(clone.resolve("project.yml"), MINIMAL);

        execute(
                context,
                "task",
                "start",
                "--attach",
                "shell",
                "--detach",
                "-p",
                projectFile(dir, MINIMAL));

        // The project moves on; this must not. "What was this task running under" is asked after
        // something has gone wrong, by which time the clone holds something newer.
        assertThat(runner.lines())
                .anyMatch(
                        line ->
                                line.contains("create")
                                        && line.contains(
                                                "--label org.fuin.porelo.commit=a1b2c3d4"));
    }

    @Test
    void aTaskNamedByItsContainerKeepsTheCredentialsItsRunNamed() {

        // A start given the container's name goes on as the task's own name, and the run's
        // credentials were dropped on the way: the task started without them, and a destination
        // nobody declared was never refused.
        final TaskLaunch.Request asked =
                new TaskLaunch.Request(
                        "porelo-uc-t",
                        Path.of("project.yml"),
                        null,
                        null,
                        null,
                        8,
                        null,
                        false,
                        false,
                        "prompt",
                        true,
                        org.fuin.porelo.wire.TaskMode.SHELL,
                        null,
                        null,
                        null,
                        TaskLaunch.DEFAULT_MINUTES,
                        "app",
                        java.util.Map.of("search", "brave-search"));

        assertThat(asked.withTask("t").credentials())
                .containsExactly(java.util.Map.entry("search", "brave-search"));
    }

    @Test
    void anAgentsOutputReachesTheTerminalWithItsControlsWrittenOut(@TempDir Path dir)
            throws IOException {

        // What the agent wrote is the task's, which anything it read can steer: an escape sequence
        // printed raw rewrites what the person reads, sets their window's title or writes their
        // clipboard.
        final Path log =
                Files.writeString(
                        dir.resolve("task.log"),
                        "done\u001b[1A\u001b[2K\n\u001b]52;c;ZWNobyBwd25lZA==\u0007all well\n");
        final StringWriter shown = new StringWriter();

        TaskRunCommand.render(
                null, log, true, new PrintWriter(shown), new PrintWriter(new StringWriter()));

        assertThat(shown.toString())
                .as("no terminal control reaches the terminal")
                .doesNotContain("\u001b")
                .doesNotContain("\u0007")
                .contains("done")
                .contains("all well");
    }

    /** What a start with these arguments would have the host run. */
    private List<String> hostArguments(Path dir, String... args) throws IOException {
        final CommandLine cmd = PoreloCli.commandLine(context(dir, false));
        cmd.parseArgs(args);
        return TaskRunCommand.hostArguments(
                cmd.getSubcommands().get("task").getSubcommands().get("start").getCommandSpec());
    }

    @Test
    void aStartOnAHostRunsTheSameStartThereLessTheHost(@TempDir Path dir) throws IOException {

        // The host runs what it is given: a word of the command path repeated, an argument dropped
        // or the host kept would start another task there, or none.
        assertThat(
                        hostArguments(
                                dir,
                                "task",
                                "start",
                                "fix",
                                "-p",
                                "uc",
                                "--host",
                                "alice@build.example",
                                "--shell=/bin/zsh",
                                "--set",
                                "NOTE=it's a b",
                                "--repository",
                                "uc"))
                .containsExactly(
                        "task",
                        "start",
                        "--project=uc",
                        "--shell=/bin/zsh",
                        "--set=NOTE=it's a b",
                        "--repository=uc",
                        "--certify-stdin",
                        "--detach",
                        "--no-input",
                        "--",
                        "fix");

        // An option given twice goes once with both values, and a flag keeps its value:
        // "--rm=false"
        // passed on as "--rm" would remove the task on the host.
        assertThat(
                        hostArguments(
                                dir,
                                "task",
                                "start",
                                "fix",
                                "--set",
                                "A=1",
                                "--host",
                                "a@h",
                                "--set",
                                "B=2",
                                "--rm=false",
                                "--yes"))
                .containsExactly(
                        "task",
                        "start",
                        "--set=A=1",
                        "--set=B=2",
                        "--rm=false",
                        "--yes=true",
                        "--certify-stdin",
                        "--detach",
                        "--no-input",
                        "--",
                        "fix");

        // The task's name stays behind a "--" the host's flags never follow.
        assertThat(hostArguments(dir, "task", "start", "-p", "uc", "--host", "a@h", "--", "fix"))
                .containsExactly(
                        "task",
                        "start",
                        "--project=uc",
                        "--certify-stdin",
                        "--detach",
                        "--no-input",
                        "--",
                        "fix");
    }

    /** What a start on a host with these arguments answers, with its exit code first. */
    private String onAHost(Path dir, String... args) throws IOException {
        final CommandLine cmd = PoreloCli.commandLine(context(dir, false));
        cmd.setOut(new PrintWriter(out));
        final StringWriter said = new StringWriter();
        cmd.setErr(new PrintWriter(said));
        final List<String> all = new ArrayList<>(List.of("task", "start"));
        all.addAll(List.of(args));
        return cmd.execute(all.toArray(new String[0])) + " " + said.toString().strip();
    }

    @Test
    void aStartOnAHostIsRefusedWhenTheHostWouldRunSomethingElse(@TempDir Path dir)
            throws IOException {

        // ssh reads a word that starts with a dash as its own option: "-oProxyCommand" as the login
        // or the host is ssh's, which can run a command on this machine.
        assertThat(onAHost(dir, "fix", "-p", "uc", "--host=-oProxyCommand@h"))
                .isEqualTo("64 porelo: --host takes <login>@<host>");
        assertThat(onAHost(dir, "fix", "-p", "uc", "--host=a@-oProxyCommand"))
                .isEqualTo("64 porelo: --host takes <login>@<host>");
        // ssh takes brackets on its command line as part of the name, and a shell its glob.
        assertThat(onAHost(dir, "fix", "-p", "uc", "--host=a@h[0-9]"))
                .isEqualTo("64 porelo: --host takes <login>@<host>");

        // A restart there would restart the host's tasks, not start this one.
        assertThat(onAHost(dir, "fix", "-p", "uc", "--host=a@h", "--restarted"))
                .isEqualTo("64 porelo: --host does not take --restarted");

        // The host picks its own name for an unnamed task, and reads "@file" as its own file.
        assertThat(onAHost(dir, "-p", "uc", "--host=a@h"))
                .isEqualTo("64 porelo: --host needs the task's name, which its certificate names");
        assertThat(onAHost(dir, "-p", "uc", "--host=a@h", "--", "@fix"))
                .isEqualTo("64 porelo: --host needs the task's name, which its certificate names");
        assertThat(onAHost(dir, "-p", "uc", "--host=a@h", "--", "-fix"))
                .isEqualTo("64 porelo: --host needs the task's name, which its certificate names");
        // The host would start "fix" for its container's name, and the certificate name the other.
        assertThat(onAHost(dir, "porelo-uc-fix", "-p", "uc", "--host=a@h"))
                .isEqualTo("64 porelo: --host needs the task's own name, not its container's");
        assertThat(onAHost(dir, "Fix", "-p", "uc", "--host=a@h"))
                .startsWith("64 porelo: 'Fix' is not");

        // The host has no checkout to find the project in.
        final String noProject =
                "64 porelo: --host needs --project: the host has no checkout to take it from";
        assertThat(onAHost(dir, "fix", "--host=a@h")).isEqualTo(noProject);
        assertThat(onAHost(dir, "fix", "--project=", "--host=a@h")).isEqualTo(noProject);
        // ssh-keygen reads "a,root" as two principals, the second root.
        assertThat(onAHost(dir, "fix", "--project=a,root", "--host=a@h"))
                .isEqualTo("64 porelo: --host needs a project's name, [a-z0-9][a-z0-9-]{0,62}");

        // The host reads a file named there from its own files.
        assertThat(onAHost(dir, "fix", "-p", "uc", "--host=a@h", "--set-file", "values.yml"))
                .isEqualTo(
                        "64 porelo: --host does not take --set-file: the host would read it from its"
                                + " own files");

        // A value taken from this shell's environment would be taken there from the host's.
        final String setOnly =
                "64 porelo: --host takes --set NAME=VALUE only: the host's environment is not this"
                        + " shell's";
        assertThat(onAHost(dir, "fix", "-p", "uc", "--host=a@h", "--set", "TOKEN"))
                .isEqualTo(setOnly);
        assertThat(onAHost(dir, "fix", "-p", "uc", "--host=a@h", "--set-env")).isEqualTo(setOnly);
        assertThat(runner.lines()).as("nothing ran").isEmpty();
    }

    @Test
    void aStartOnAHostIsQuotedSoTheHostsShellKeepsEachArgument(@TempDir Path dir)
            throws IOException {

        // ssh hands the host's shell one line: an unquoted value with a space or a quote would
        // split or run there.
        assertThat(
                        RemoteStart.remoteCommand(
                                hostArguments(
                                        dir,
                                        "tasks",
                                        "start",
                                        "fix",
                                        "--host=alice@build.example",
                                        "--set",
                                        "NOTE=it's a b")))
                .isEqualTo(
                        "porelo 'task' 'start' '--set=NOTE=it'\\''s a b'"
                                + " '--certify-stdin' '--detach' '--no-input' '--' 'fix'");
    }
}
