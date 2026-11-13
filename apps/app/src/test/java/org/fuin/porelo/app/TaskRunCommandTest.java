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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    @Test
    void aHelperPlantedInTheContextMakesTaskStartRefuseCertification(@TempDir Path dir)
            throws IOException {
        final PoreloContext context = context(dir, true);
        final Path program = context.paths().hostProgram();
        Files.createDirectories(program.getParent());
        Files.writeString(program, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(
                program, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));

        assertThat(context.paths().hostDirectory())
                .as("the helper alone must identify the host")
                .doesNotExist();
        assertThat(new HostPerson(context).present())
                .as("host detection reads the helper planted in this context")
                .isTrue();
        final int code =
                execute(
                        context,
                        "task",
                        "start",
                        "--attach",
                        "shell",
                        "-p",
                        projectFile(dir, MINIMAL));

        assertThat(code).as("a host refuses an uncertifiable task: %s", err).isEqualTo(65);
        assertThat(err.toString())
                .as("the refusal is the host's certification rule")
                .contains("this host starts a task only with a key of its own that you certify");
        assertThat(runner.lines())
                .as("a refused host start creates no container")
                .noneMatch(line -> line.contains("podman create"));
    }

    /** Plants a host helper in the context; the fake runner answers what it is asked. */
    private PoreloContext hostInTestMode(Path dir, String switches) throws IOException {
        final PoreloContext context = context(dir, true);
        final Path program = context.paths().hostProgram();
        Files.createDirectories(program.getParent());
        Files.writeString(program, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(
                program, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        runner.answering("test-switches --json", switches);
        return context;
    }

    private static final String TEST_MODE = "{\"switches\":[\"start-uncertified\"]}\n";

    private static final String WARNING =
            "this host is in test mode, so the task starts WITHOUT a certificate";

    @Test
    void aHostInTestModeStartsAnUncertifiedTaskWithYesAndNoInput(@TempDir Path dir)
            throws IOException {
        final PoreloContext context = hostInTestMode(dir, TEST_MODE);

        final int code =
                startAt(
                        context,
                        false,
                        "--attach",
                        "shell",
                        "--no-input",
                        "--yes",
                        "-p",
                        projectFile(dir, MINIMAL));

        assertThat(code).as("--yes answers the question: %s", err).isZero();
        assertThat(err.toString())
                .as("the warning is said on standard error")
                .contains(WARNING)
                .contains("what it passes on is vouched for by nobody");
        assertThat(runner.only(" task start shell").input())
                .as("the host is asked for an uncertified start by an empty input")
                .isEmpty();
        assertThat(runner.lines())
                .as("the switches are asked of the host before")
                .anyMatch(line -> line.endsWith("porelo-host test-switches --json"))
                .anyMatch(line -> line.contains("podman create"));
    }

    @Test
    void aHostInTestModeRefusesAnUncertifiedStartWithoutTerminalOrYes(@TempDir Path dir)
            throws IOException {
        final PoreloContext context = hostInTestMode(dir, TEST_MODE);

        final int code =
                startAt(context, false, "--attach", "shell", "-p", projectFile(dir, MINIMAL));

        assertThat(code).as("nobody answered: %s", err).isEqualTo(65);
        assertThat(err.toString())
                .contains(WARNING)
                .contains("this host starts a task only with a key of its own that you certify");
        assertThat(runner.lines())
                .noneMatch(line -> line.contains(" task start shell"))
                .noneMatch(line -> line.contains("podman create"));
    }

    @Test
    void aHostInTestModeRefusesAnUncertifiedStartAnsweredNo(@TempDir Path dir) throws IOException {
        final PoreloContext context = hostInTestMode(dir, TEST_MODE);
        final List<String> asked = new ArrayList<>();
        uncertifiedAnswer =
                question -> {
                    asked.add(question);
                    return "n";
                };

        final int code =
                startAt(context, true, "--attach", "shell", "-p", projectFile(dir, MINIMAL));

        assertThat(code).as("answered no: %s", err).isEqualTo(65);
        assertThat(asked).containsExactly("Start the task without the check? y/N");
        assertThat(err.toString()).contains(WARNING);
        assertThat(runner.lines())
                .noneMatch(line -> line.contains(" task start shell"))
                .noneMatch(line -> line.contains("podman create"));
    }

    @Test
    void aHostInTestModeStartsAnUncertifiedTaskAnsweredYesAtATerminal(@TempDir Path dir)
            throws IOException {
        final PoreloContext context = hostInTestMode(dir, TEST_MODE);
        uncertifiedAnswer = question -> "y";

        final int code =
                startAt(context, true, "--attach", "shell", "-p", projectFile(dir, MINIMAL));

        assertThat(code).as("answered yes: %s", err).isZero();
        assertThat(runner.only(" task start shell").input()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"switches\":[]}\n",
                "{\"switches\":[\"start-uncertified-later\"]}\n",
                "",
                "start-uncertified\n"
            })
    void aHostNotInTestModeRefusesAsTodayWhateverTheFlags(String switches, @TempDir Path dir)
            throws IOException {
        final PoreloContext context = hostInTestMode(dir, switches);

        final int code =
                startAt(
                        context,
                        false,
                        "--attach",
                        "shell",
                        "--no-input",
                        "--yes",
                        "-p",
                        projectFile(dir, MINIMAL));

        assertThat(code).as("no switch, no uncertified start: %s", err).isEqualTo(65);
        assertThat(err.toString())
                .doesNotContain(WARNING)
                .contains("this host starts a task only with a key of its own that you certify");
        assertThat(runner.lines())
                .noneMatch(line -> line.contains(" task start shell"))
                .noneMatch(line -> line.contains("podman create"));
    }

    @Test
    void aHostThatDoesNotKnowTheQuestionHasNoSwitches(@TempDir Path dir) throws IOException {
        final PoreloContext context = hostInTestMode(dir, "");
        runner.failing("test-switches --json", 64, "Unmatched argument at index 0\n");

        final int code =
                startAt(
                        context,
                        false,
                        "--attach",
                        "shell",
                        "--no-input",
                        "--yes",
                        "-p",
                        projectFile(dir, MINIMAL));

        assertThat(code).as("%s", err).isEqualTo(65);
        assertThat(err.toString()).doesNotContain(WARNING);
    }

    @Test
    void aTaskStartsWhenItsContextHasNoHostHelperOrDirectory(@TempDir Path dir) throws IOException {
        final PoreloContext context = context(dir, true);

        assertThat(context.paths().hostProgram())
                .as("this context has no host helper")
                .doesNotExist();
        assertThat(context.paths().hostDirectory())
                .as("this context has no host configuration")
                .doesNotExist();
        assertThat(new HostPerson(context).present())
                .as("host detection depends only on this context")
                .isFalse();
        final int code =
                execute(
                        context,
                        "task",
                        "start",
                        "--attach",
                        "shell",
                        "-p",
                        projectFile(dir, MINIMAL));

        assertThat(code).as("a machine without a host starts the task: %s", err).isZero();
        assertThat(err.toString())
                .as("the machine's installed host package cannot impose certification")
                .doesNotContain(
                        "this host starts a task only with a key of its own that you certify");
        assertThat(runner.lines())
                .as("the start reaches container creation")
                .anyMatch(line -> line.contains("podman create"));
    }

    @Test
    void aStartByAnEncodedActualNameResumesItsRecordedLogicalTask(@TempDir Path dir)
            throws Exception {
        // The actual spelling must not become a different logical owner at the reservation.
        final PoreloContext context = context(dir, true);
        final String actual = "porelo-uc_shell";
        final String id = "a".repeat(64);
        TaskIdentityFixture.record(context.paths(), actual, "uc", "shell", id);
        new TaskHelpers(List.of()).writeTo(context.paths().tasks().taskRecord(actual));
        runner.answering("ps", actual + "\tExited (0)\t\t\tuc\tguarded\tuc\t\t" + id + "\tshell\n");
        runner.answering("{{.Id}}", id);
        final int code =
                execute(
                        context,
                        "task",
                        "start",
                        actual,
                        "-p",
                        projectFile(dir, MINIMAL),
                        "--detach");

        assertThat(code).as("resume uses the verified logical owner: %s", err).isZero();
        assertThat(runner.lines())
                .as("resume never creates a container with a rebuilt name")
                .noneMatch(line -> line.contains("podman create"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(
            value = {
                "porelo, NULL, porelo-pi, pi",
                "porelo, NULL, porelo_pi, pi",
                "uc, ucshort, porelo-ucshort_shell, shell",
                "uc, u, porelo-u_shell, shell"
            },
            nullValues = "NULL")
    void outsideACheckoutAStoppedTaskIsBroughtBackByItsContainersNameWhateverItsForm(
            String project, String projectShort, String actual, String task, @TempDir Path dir)
            throws Exception {
        // Cut out of its name, the task was another one, or the cut ran past the name's end.
        final PoreloContext context = context(dir, true);
        projectFile(
                dir,
                MINIMAL.replace(
                        "name: \"uc\"",
                        "name: \""
                                + project
                                + "\""
                                + (projectShort == null ? "" : "\n  short: " + projectShort)));
        final String id = "a".repeat(64);
        final Path state = context.paths().tasks().taskRecord(actual);
        org.fuin.porelo.wire.Sidecar.recordId(state, id);
        Files.setPosixFilePermissions(
                state, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        new org.fuin.porelo.wire.Sidecar(
                        org.fuin.porelo.wire.Sidecar.VERSION,
                        project,
                        "guarded",
                        "ruleset.nft",
                        "dns.conf",
                        "porelo",
                        context.paths().tasks().containerState(actual).toString(),
                        task,
                        projectShort,
                        null)
                .writeTo(state.resolve("sidecar.json"));
        new TaskHelpers(List.of()).writeTo(state);
        runner.answering(
                "ps",
                actual
                        + "\tExited (0)\t\t\t"
                        + project
                        + "\tguarded\t"
                        + project
                        + "\t\t"
                        + id
                        + "\t"
                        + task
                        + "\n");
        runner.answering("{{.Id}}", id);

        final int code =
                execute(context, "task", "start", actual, "--repository", project, "--detach");

        assertThat(code).as("its own task is brought back: %s", err).isZero();
        assertThat(out.toString())
                .as("the project its identity names")
                .contains("project        " + project + " - that of the task " + actual);
        assertThat(runner.lines())
                .as("the stopped container is started, and nothing is made beside it")
                .anyMatch(
                        line ->
                                line.contains("podman start")
                                        && (line.contains(actual) || line.contains(id)))
                .noneMatch(line -> line.contains("podman create"));
    }

    @Test
    void aRebootResumeRestoresOnlyTheOldOwnersPathsAndRouteTokens(@TempDir Path dir)
            throws Exception {
        // A resume must restore the mounted old paths and tokens without borrowing a sibling's
        // scope.
        final PoreloContext context =
                context(dir, true).asking(shown -> "fixture-passphrase".toCharArray());
        final String actual = "porelo-uc-shell";
        final String sibling = "porelo-uc_other";
        final String id = "a".repeat(64);
        TaskIdentityFixture.record(context.paths(), actual, "uc", "shell", id);
        TaskIdentityFixture.record(context.paths(), sibling, "uc", "other", "b".repeat(64));
        final Path durable = context.paths().tasks().taskRecord(actual);
        final Path siblingFile = context.paths().tasks().taskRecord(sibling).resolve("sentinel");
        Files.writeString(siblingFile, "sibling's file");
        Files.writeString(durable.resolve("ruleset.nft"), "original rules");
        new TaskHelpers(
                        List.of(
                                new TaskHelpers.Helper(
                                        "gate",
                                        List.of("unused-helper"),
                                        java.util.Map.of(),
                                        TaskHelpers.AFTER)))
                .writeTo(durable);
        runner.answering("ps", actual + "\tExited (0)\t\t\tuc\tguarded\tuc\t\t" + id + "\n");
        runner.answering("{{.Id}}", id);
        context.vault()
                .write(
                        java.util.Map.of(
                                TaskSecrets.PREFIX + actual + "/gate-token",
                                        org.fuin.porelo.vault.VaultEntry.of("old-gate"),
                                TaskSecrets.PREFIX + actual + "/provider-token",
                                        org.fuin.porelo.vault.VaultEntry.of("old-provider"),
                                TaskSecrets.PREFIX + actual + "/route/forge",
                                        org.fuin.porelo.vault.VaultEntry.of("old-route"),
                                TaskSecrets.PREFIX + sibling + "/route/forge",
                                        org.fuin.porelo.vault.VaultEntry.of("sibling-route")),
                        "fixture-passphrase".toCharArray());
        try {
            context.openerAsking(false, new PrintWriter(err));
            new TaskControl(context).resume(actual);
            final Path runtime = context.paths().tasks().containerState(actual);
            assertThat(runtime.resolve("ruleset.nft"))
                    .as("the original mount's policy returns")
                    .hasContent("original rules");
            assertThat(
                            "old-provider"
                                    .equals(
                                            Files.readString(
                                                    runtime.resolve(TaskSecrets.PROVIDER_FILE))))
                    .as("the original provider token returns to its original path")
                    .isTrue();
            assertThat("old-route".equals(Files.readString(runtime.resolve("routes/forge.token"))))
                    .as("the original route token returns to its original path")
                    .isTrue();
            assertThat(context.paths().tasks().containerState(sibling))
                    .as("resume creates no sibling runtime state")
                    .doesNotExist();
            assertThat(siblingFile)
                    .as("the sibling's durable files stay untouched")
                    .hasContent("sibling's file");
        } finally {
            context.forgetTyped();
        }
    }

    @Test
    void removalRefusesAReplacementBeforeDeletingAnyOwnersFiles(@TempDir Path dir)
            throws Exception {
        // A stale identity must never delete files or secrets after a different container takes its
        // name.
        final PoreloContext context = context(dir, true);
        final String actual = "porelo-uc-shell";
        TaskIdentityFixture.record(context.paths(), actual, "uc", "shell", "a".repeat(64));
        runner.answering(
                "ps", actual + "\tExited (0)\t\t\tuc\tguarded\tuc\t\t" + "b".repeat(64) + "\n");
        final Path sentinel = context.paths().tasks().taskRecord(actual).resolve("sentinel");
        Files.writeString(sentinel, "owner's file");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new TaskControl(context).remove(actual, false, true))
                .as("replacement ownership refuses removal")
                .isInstanceOf(TaskReservations.ContainerNameTaken.class);
        assertThat(sentinel).as("the original owner's files remain").hasContent("owner's file");
        assertThat(runner.lines())
                .as("no container is removed on contradictory ownership")
                .noneMatch(line -> line.contains("podman rm"));
    }

    @Test
    void aClaimOfAnotherProjectRefusesALegacyOverlapBeforeTaskFiles(@TempDir Path dir)
            throws Exception {
        final PoreloContext context = context(dir, true);
        try (var original =
                new TaskReservations(context)
                        .reserve("web", "app-x", "porelo-web-app-x", "original")) {}
        final String file = projectFile(dir, MINIMAL.replace("\"uc\"", "\"web-app\""));
        final int code =
                execute(
                        context,
                        "task",
                        "start",
                        "x",
                        "-p",
                        file,
                        "--no-gate",
                        "--attach",
                        "shell");
        assertThat(code)
                .as("a collision must not create another owner's task: %s", err)
                .isEqualTo(64);
        assertThat(context.paths().tasks().containerState("porelo-web-app-x")).doesNotExist();
        assertThat(context.paths().messaging().mailbox("porelo-web-app-x")).doesNotExist();
        assertThat(runner.lines())
                .noneMatch(line -> line.contains("podman create") || line.contains("podman build"));
    }

    @Test
    void aNewStartOfARetiredNameSeesNoOldMailOrRefusedOriginals(@TempDir final Path dir)
            throws IOException {
        final PoreloContext context = context(dir, true);
        final String projectName = projectFile(dir, MINIMAL);
        final var project =
                org.fuin.porelo.core.project.ProjectReader.read(
                        ProjectSource.require(context, projectName));
        final String container = context.tasks().containerName(project, "shell");
        final TaskReservations reservations = new TaskReservations(context);
        try (final var original = reservations.reserve("uc", "shell", container, "first")) {}
        reservations.retire(container);
        final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox(container));
        mailbox.create();
        final Path message =
                Files.writeString(mailbox.inboxCur().resolve("old-message.json"), "{}");
        final Path original =
                Files.writeString(mailbox.rejected().resolve("old-original.json"), "{}");
        new MessageRecord(mailbox).append(MessageRecord.TAKEN, "old-message.json", "old", "signed");

        final int code =
                startAt(context, true, "--attach", "shell", "--no-gate", "-p", projectName);

        assertThat(code).as("the retired task's name can start anew: %s", err).isZero();
        assertThat(mailbox.root()).as("the new task has its own mailbox").isDirectory();
        assertThat(message).as("the new task cannot read the old task's mail").doesNotExist();
        assertThat(original)
                .as("the removed task's refused originals do not reach the new task")
                .doesNotExist();
        assertThat(mailbox.record().resolve(MessageRecord.FILE))
                .as("the old conversation record is not inherited either")
                .doesNotExist();
        assertThat(runner.lines())
                .as("a successful reservation reaches container creation")
                .anyMatch(line -> line.contains("podman create"));
    }

    private static final String MINIMAL =
            """
            project:
              name: "uc"
              security_class: "guarded"
            image:
              from: "ubuntu:24.04"
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
                    .answering("stty -g", "saved-settings\n")
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
                // Says what it was told, as the command it would run and the file it would write;
                // a model goes by its declared flag, as a real agent's command carries it.
                server.method(
                        "BuildCommand",
                        (parameters, replies) -> {
                            final List<String> command =
                                    new ArrayList<>(
                                            List.of(
                                                    "preview",
                                                    String.valueOf(parameters.get("prompt")),
                                                    "subagents=" + told(parameters)));
                            if (parameters.get("model") != null
                                    && definition.headless().modelFlag() != null) {
                                command.add(definition.headless().modelFlag());
                                command.add(String.valueOf(parameters.get("model")));
                            }
                            replies.last(java.util.Map.of("command", command));
                        });
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
        return previewContext(dir, false);
    }

    private PoreloContext previewContext(Path dir, boolean hooksInstalled) throws IOException {
        final PoreloContext context = context(dir, hooksInstalled);
        final XdgPaths xdg = context.paths().xdg();
        // The shorter way to the sockets, so they fit the Unix limit wherever the checkout lies.
        final XdgPaths shortSockets =
                new XdgPaths(
                        xdg.config(),
                        xdg.data(),
                        xdg.state(),
                        SocketPaths.shortest(xdg.runtime()),
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void anExistingTaskRefusesAnotherShortSpellingWithoutChangingIt(
            boolean running, @TempDir Path dir) throws Exception {
        // An ignored immutable option must not start work under a spelling the caller did not
        // request.
        final PoreloContext context = context(dir, true);
        runner.answering(
                "ps",
                running
                        ? RUNNING_SHELL
                        : RUNNING_SHELL.replace("Up 4 minutes", "Exited (0) 4 minutes ago"));
        assertThat(startAt(context, true, "-p", projectFile(dir, MINIMAL), "--short", "x"))
                .as("an existing task cannot change its spelling: %s", err)
                .isEqualTo(64);
        assertThat(err.toString())
                .as("the remedy is a new task, since stop cannot change the spelling")
                .contains("--short", "new task")
                .doesNotContain("effect only after");
        assertThat(runner.lines())
                .as("an immutable-name refusal must not resume or recreate the task")
                .noneMatch(line -> line.contains("podman start") || line.contains("podman create"));
    }

    @Test
    void recordsBothShortSpellingsAndCreatesTheNameTheyMake(@TempDir Path dir) throws Exception {
        // A later project edit must not lose the actual name or its original spelling.
        final PoreloContext context = context(dir, true);
        final String file =
                projectFile(dir, MINIMAL.replace("name: \"uc\"", "name: \"uc\"\n  short: ws"));
        assertThat(
                        execute(
                                context,
                                "task",
                                "start",
                                "shell",
                                "-p",
                                file,
                                "--attach",
                                "shell",
                                "--short",
                                "x"))
                .as("a short spelling reaches the ordinary start: %s", err)
                .isZero();
        for (Path state :
                List.of(
                        context.paths().tasks().containerState("porelo-ws_x"),
                        context.paths().tasks().taskRecord("porelo-ws_x"))) {
            final var sidecar =
                    org.fuin.porelo.wire.Sidecar.readFrom(state.resolve("sidecar.json"));
            assertThat(sidecar.task()).as("the logical owner is recorded").isEqualTo("shell");
            assertThat(sidecar.projectShort())
                    .as("the original project spelling is recorded")
                    .isEqualTo("ws");
            assertThat(sidecar.taskShort())
                    .as("the requested task spelling is recorded")
                    .isEqualTo("x");
        }
        assertThat(runner.lines())
                .as("a new task is made under its short spellings")
                .anyMatch(
                        line ->
                                line.contains("podman create")
                                        && line.contains("--name porelo-ws_x "));
    }

    @Test
    void aNewTaskOfAProjectIsMadeUnderTheProjectAndTaskJoinedByAnUnderscore(@TempDir Path dir)
            throws Exception {
        // 'porelo-web-app-x' was both web's app-x and web-app's x; the underscore is in neither.
        final PoreloContext context = context(dir, true);

        assertThat(
                        execute(
                                context,
                                "task",
                                "start",
                                "-p",
                                projectFile(dir, MINIMAL),
                                "--attach",
                                "shell"))
                .as("%s", err)
                .isZero();

        assertThat(runner.lines())
                .as("the new name, and never the old one")
                .anyMatch(
                        line ->
                                line.contains("podman create")
                                        && line.contains("--name porelo-uc_shell "))
                .noneMatch(line -> line.contains("porelo-uc-shell"));
        assertThat(context.paths().tasks().taskRecord("porelo-uc_shell")).isDirectory();
    }

    @Test
    void aNewTaskOfProjectPoreloIsMadeUnderItsTaskBehindAnUnderscore(@TempDir Path dir)
            throws Exception {
        // Repository porelo-pi's task is pi; 'porelo-porelo-pi' said the project twice, and
        // 'porelo-pi' was project pi's legacy task as well.
        final PoreloContext context = context(dir, true);
        final String file = projectFile(dir, MINIMAL.replace("name: \"uc\"", "name: \"porelo\""));

        assertThat(
                        execute(
                                context,
                                "task",
                                "start",
                                "pi",
                                "-p",
                                file,
                                "--repository",
                                "porelo",
                                "--attach",
                                "shell"))
                .as("%s", err)
                .isZero();

        assertThat(runner.lines())
                .anyMatch(
                        line ->
                                line.contains("podman create")
                                        && line.contains("--name porelo_pi "))
                .noneMatch(line -> line.contains("porelo-porelo-pi"));
        assertThat(context.paths().tasks().taskRecord("porelo_pi")).isDirectory();
    }

    @Test
    void aTaskMadeUnderTheOldNameIsBroughtBackUnderItAndNeverMadeAgain(@TempDir Path dir)
            throws Exception {
        // Its gate, mail, keys and workspace are all under the name it was made with.
        final PoreloContext context = context(dir, true);
        final String file = projectFile(dir, MINIMAL.replace("name: \"uc\"", "name: \"porelo\""));
        final String id = "c0ffee".repeat(10) + "abcd";
        // Listed as podman lists a task made before: no task label, its project's label.
        runner.answering(
                "ps",
                "porelo-porelo-pi\tExited (0) 4 minutes ago\t1700000000\t1700000100\tporelo\tguarded"
                        + "\t\t\t"
                        + id
                        + "\n");

        // What its stop saved, as a reboot leaves it.
        new TaskHelpers(List.of())
                .writeTo(
                        Files.createDirectories(
                                context.paths().tasks().taskRecord("porelo-porelo-pi")));
        runner.answering("{{.Id}}", id);

        execute(context, "task", "start", "pi", "-p", file, "--repository", "porelo", "--detach");

        assertThat(runner.lines())
                .as("the old task is brought back: %s", err)
                .anyMatch(
                        line -> line.contains("podman start") && line.contains("porelo-porelo-pi"))
                .noneMatch(line -> line.contains("podman create"));
        assertThat(context.paths().tasks().taskRecord("porelo_pi"))
                .as("nothing is made under the new name")
                .doesNotExist();
    }

    @Test
    void aDryRunNamesNoNewTaskAndClaimsNoName(@TempDir Path dir) throws Exception {
        // A preview that held the name would refuse the start it previewed.
        final PoreloContext context = context(dir, true);

        assertThat(execute(context, "task", "start", "-p", projectFile(dir, MINIMAL), "--dry-run"))
                .isZero();

        assertThat(context.paths().tasks().identity("porelo-uc_shell")).doesNotExist();
        assertThat(context.paths().tasks().identity("porelo-uc-shell")).doesNotExist();
        assertThat(runner.invocations()).as("a dry run must touch nothing").isEmpty();
    }

    @Test
    void refusesAnInvalidShortNameBeforeMakingTaskState(@TempDir Path dir) throws Exception {
        // Invalid names must not leave state for a task that can never be created.
        final PoreloContext context = context(dir, true);
        assertThat(execute(context, "task", "start", "shell", "--short", "Upper"))
                .as("a wrong short spelling is a usage refusal")
                .isEqualTo(64);
        assertThat(err.toString())
                .as("the refusal identifies the option and rule")
                .contains("--short", "lowercase");
        assertThat(Files.exists(context.paths().tasks().containerState("porelo-uc-shell")))
                .as("the refused start makes no task")
                .isFalse();
    }

    @Test
    void refusesNowAsAnOptionItDoesNotHave(@TempDir Path dir) throws Exception {
        // An option a person can type that changes nothing is a promise the start does not keep.
        final PoreloContext context = context(dir, true);
        assertThat(
                        execute(
                                context,
                                "task",
                                "start",
                                "shell",
                                "-p",
                                projectFile(dir, MINIMAL),
                                "--now",
                                "--detach"))
                .as("an option the start does not have is picocli's usage refusal")
                .isEqualTo(CommandLine.ExitCode.USAGE);
        assertThat(err.toString())
                .as("the refusal names the option as unknown to the start")
                .contains("'--now' is not an option of 'porelo task start'");
        assertThat(Files.exists(context.paths().tasks().containerState("porelo-uc-shell")))
                .as("the refused start makes no task")
                .isFalse();
    }

    @Test
    void refusesAResultFileOutsideItsBoxBeforeMakingTaskState(@TempDir Path dir) throws Exception {
        // A directory part in the name would have the host take a file from outside the box.
        final PoreloContext context = context(dir, true);
        assertThat(execute(context, "task", "start", "shell", "--result", "../idea.json"))
                .as("a name that is no single file's is a usage refusal")
                .isEqualTo(64);
        assertThat(err.toString()).as("the refusal names the option").contains("--result");
        assertThat(Files.exists(context.paths().tasks().containerState("porelo-uc-shell")))
                .as("the refused start makes no task")
                .isFalse();
        assertThat(context.paths().tasks().result("porelo-uc-shell"))
                .as("and no box")
                .doesNotExist();
    }

    @Test
    void aHostRefusesAnUncertifiableStartBeforeStartingItsBroker(@TempDir Path dir)
            throws IOException {
        // A broker started for a task that cannot be certified needlessly holds a real credential.
        final PoreloContext context = previewContext(dir, true);
        previewAgent(context, "preview");
        Files.writeString(
                context.paths().xdg().data().resolve("providers/preview.yaml"),
                "token_env: { _default: PREVIEW_TOKEN }\n",
                java.nio.file.StandardOpenOption.APPEND);
        final Path projectFile =
                PlacedFixture.place(
                        context, "uc", MINIMAL, PlacedFixture.key("alice", "ssh-ed25519 AAAA"));
        context.vault()
                .write(
                        java.util.Map.of(
                                "preview",
                                new org.fuin.porelo.vault.VaultEntry("test-key", "api-key")),
                        "test-passphrase".toCharArray());
        final Path events = Files.writeString(dir.resolve("start-events"), "");
        final Path helper = Files.writeString(dir.resolve("host-person"), "");
        final String ownPorelo = System.getProperty(PoreloBinary.NAMED);
        final Path broker = dir.resolve("recording-porelo");
        Files.writeString(
                broker,
                """
                #!/bin/sh
                if [ "$1" = vault ] && [ "$2" = serve ]; then
                  echo broker >> %s
                  while [ "$#" -gt 1 ]; do
                    case "$1" in
                      --socket) : > "$2" ;;
                      --token-file) echo stand-in-token > "$2" ;;
                    esac
                    shift
                  done
                  exit 0
                fi
                exec %s "$@"
                """
                        .formatted(shellArgument(events.toString()), shellArgument(ownPorelo)));
        Files.setPosixFilePermissions(
                broker, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        System.setProperty(PoreloBinary.NAMED, broker.toString());
        final int code;
        try {
            assertThat(
                            context.asking(shown -> "test-passphrase".toCharArray())
                                    .openerAsking(false, new PrintWriter(err)))
                    .as("the fixture opens only its own vault, without an account keyring")
                    .isPresent();
            code =
                    new TaskLaunch(
                                    context,
                                    new TaskLaunch.Request(
                                            "first",
                                            projectFile,
                                            "preview",
                                            null,
                                            null,
                                            8,
                                            null,
                                            false,
                                            false,
                                            "deny",
                                            false,
                                            org.fuin.porelo.wire.TaskMode.SHELL,
                                            null,
                                            null,
                                            null,
                                            60,
                                            "uc",
                                            java.util.Map.of(),
                                            java.util.Map.of()),
                                    new StartSteps(step -> {}, new PrintWriter(err)),
                                    new HostPerson(context, helper),
                                    null)
                            .launch(
                                    new PrintWriter(out, true),
                                    new PrintWriter(err, true),
                                    running -> running.cleanUp().applyAsInt(0));
            Files.writeString(events, "refused\n", java.nio.file.StandardOpenOption.APPEND);
        } finally {
            System.setProperty(PoreloBinary.NAMED, ownPorelo);
            PoreloContext.forgetAsked();
        }

        assertThat(code)
                .as("the host refuses a task with no way to certify it: %s", err)
                .isEqualTo(65);
        assertThat(Files.readAllLines(events))
                .as("certification must refuse before any broker holds the task's credential")
                .containsExactly("refused");
        assertThat(runner.lines())
                .as("the refused task must prepare no image or container")
                .noneMatch(line -> line.contains("podman build") || line.contains("podman create"));
        assertThat(context.paths().tasks().containerState("porelo-uc-first"))
                .as("the refused task must leave no runtime state or helpers")
                .doesNotExist();
        assertThat(context.paths().messaging().mailbox("porelo-uc-first"))
                .as("the refused task must leave no mailbox")
                .doesNotExist();
    }

    @Test
    void aTaskMountsNeitherTheDaemonsSocketNorADirectoryAboveIt(@TempDir Path dir)
            throws IOException {
        // The daemon answers a credential's length to whoever reaches its socket. In a rootless
        // container the root user is the account itself, so its peer check is not what keeps a task
        // out: not being mounted is.
        final PoreloContext context = context(dir, true);

        final int code =
                execute(
                        context,
                        "task",
                        "start",
                        "-p",
                        projectFile(dir, MINIMAL),
                        "--attach",
                        "shell");

        assertThat(code).as(out + "\n" + err).isZero();
        final String create =
                runner.lines().stream()
                        .filter(line -> line.contains("podman create"))
                        .findFirst()
                        .orElseThrow();
        final List<Path> mounted =
                java.util.regex.Pattern.compile("--volume (/[^:]+):")
                        .matcher(create)
                        .results()
                        .map(found -> Path.of(found.group(1)))
                        .toList();
        assertThat(mounted).as("the mounts this reads: %s", create).isNotEmpty();
        assertThat(mounted).noneMatch(context.paths().daemonSocket()::startsWith);
    }

    @Test
    void anOpenSshBelowTheFloorRefusesAStartBeforeAnImageOrAContainer(@TempDir Path dir)
            throws IOException {
        runner.answering("ssh -V", "", "OpenSSH_8.8p1 Ubuntu-1, OpenSSL 3.0.2\n");
        final PoreloContext context = context(dir, true);

        final int code = execute(context, "task", "start", "-p", projectFile(dir, MINIMAL));

        assertThat(code).as(out + "\n" + err).isEqualTo(69);
        assertThat(err.toString()).contains("OpenSSH 8.8p1").contains("OpenSSH 8.9 or newer");
        assertThat(runner.lines())
                .noneMatch(line -> line.contains("podman build") || line.contains("podman create"));
        assertThat(execCalls).isEmpty();
    }

    @Test
    void anOpenSshBelowTheFloorStillGoesIntoARunningTask(@TempDir Path dir) throws IOException {
        runner.answering("ssh -V", "", "OpenSSH_8.8p1 Ubuntu-1, OpenSSL 3.0.2\n");
        final PoreloContext context = context(dir, true);
        runner.answering("ps", RUNNING_SHELL);

        assertThat(startAt(context, true, "-p", projectFile(dir, MINIMAL)))
                .as("said: %s", err)
                .isZero();

        assertThat(err.toString()).doesNotContain("OpenSSH 8.9");
        assertThat(execCalls).as("the session it went into").hasSize(1);
    }

    @Test
    void anOpenSshBelowTheFloorRefusesToBringAStoppedTaskBack(@TempDir Path dir)
            throws IOException {
        runner.answering("ssh -V", "", "OpenSSH_8.8p1 Ubuntu-1, OpenSSL 3.0.2\n");
        final PoreloContext context = context(dir, true);
        runner.answering("ps", RUNNING_SHELL.replace("Up 4 minutes", "Exited (0) 4 minutes ago"));

        final int code =
                execute(
                        context,
                        "task",
                        "start",
                        "shell",
                        "-p",
                        projectFile(dir, MINIMAL),
                        "--detach");

        assertThat(code).as(out + "\n" + err).isEqualTo(69);
        assertThat(err.toString()).contains("OpenSSH 8.8p1").contains("OpenSSH 8.9 or newer");
        assertThat(runner.lines()).noneMatch(line -> line.contains("podman start"));
    }

    @Test
    void anOpenSshThatCannotBeReadRefusesNoStart(@TempDir Path dir) throws IOException {
        runner.answering("ssh -V", "", "Sun_SSH_1.1.5\n");
        final PoreloContext context = context(dir, true);

        final int code =
                execute(
                        context,
                        "task",
                        "start",
                        "-p",
                        projectFile(dir, MINIMAL),
                        "--attach",
                        "shell");

        assertThat(code).as(out + "\n" + err).isZero();
        assertThat(runner.lines()).anyMatch(line -> line.equals("ssh -V"));
        assertThat(runner.lines()).anyMatch(line -> line.contains("podman create"));
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
        // Workspace execution succeeds unless a test supplies a specific failure; other direct
        // podman calls still see no container. The checkout script itself has real-git tests.
        final Path workspacePodman = dir.resolve("workspace-podman");
        Files.writeString(
                workspacePodman,
                "#!/bin/sh\ncase \"$*\" in *'git fetch -q --multiple porelo origin'*) exit 0 ;; esac\nexit 125\n");
        workspacePodman.toFile().setExecutable(true);
        System.setProperty(org.fuin.porelo.runtime.Podman.NAMED, workspacePodman.toString());
        final var git = new org.fuin.porelo.core.process.ProcessCommandRunner();
        return new PoreloContext(
                command ->
                        command.program().equals("git")
                                        && command.arguments().stream()
                                                .anyMatch(
                                                        argument ->
                                                                argument.startsWith(
                                                                        paths.xdg()
                                                                                        .data()
                                                                                        .resolve(
                                                                                                "mirrors")
                                                                                + "/"))
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

    @Test
    void aGuardedHostTaskIsToldItsBuildsWithOnlyFailedJobLogs(@TempDir Path dir) throws Exception {
        // Missing build failures leave the task unable to repair the work the host's forge judged.
        final List<String> called = startWithBuilds(dir, "guarded", true, true, "");

        assertThat(called)
                .as("the host's builds helper must actually start for a guarded task")
                .containsSequence("task", "watch-builds", "porelo-uc_shell")
                .containsSequence("--forge", "host-forge")
                .containsSequence("--api", "http://127.0.0.1:3000/api/v1")
                .containsSequence("--credential", "host-builds")
                .containsSequence("--branch", "porelo/shell")
                .containsSequence("--upstream", "file://" + dir.resolve("upstream.git"))
                .containsSequence("--logs", "failure")
                .contains("--builds-only")
                .as(
                        "told its builds come from the host's file, so a missing entry names its command")
                .contains("--from-host");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"true,false", "false,true", "false,false"})
    void aGuardedTaskWithoutBuildsFromItsHostStartsNoBuildsHelper(
            boolean onHost, boolean hostBuilds, @TempDir Path dir) throws Exception {
        // A file left on an ordinary machine must grant no new host-side forge access.
        assertThat(startWithBuilds(dir, "guarded", onHost, hostBuilds, ""))
                .as("both a host and its builds declaration are required")
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"guarded", "online"})
    void aHostTaskWithoutItsBuildsCredentialStillStartsAndNamesTheHostCommand(
            String securityClass, @TempDir Path dir) throws Exception {
        // A missing host credential must not leave a useless helper or stop the task's work.
        final List<String> called =
                startWithBuilds(
                        dir,
                        securityClass,
                        true,
                        true,
                        "",
                        java.util.Map.of(
                                "other-builds",
                                new org.fuin.porelo.vault.VaultEntry(
                                        "unrelated-fixture-secret", "api-key")));

        assertThat(err.toString()).contains("host-builds", "porelo host forge-token");
        assertThat(called)
                .as("a missing host builds credential must start no watch-builds helper")
                .isEmpty();
        assertThat(err.toString())
                .as("the diagnostic must never reveal any credential value")
                .doesNotContain("unrelated-fixture-secret");
    }

    @Test
    void anOnlineProjectsOwnBuildsWinOverTheHosts(@TempDir Path dir) throws Exception {
        final List<String> called =
                startWithBuilds(
                        dir,
                        "online",
                        true,
                        true,
                        """
                        builds:
                          forge: project-forge
                          credential: project-builds
                          api: https://forge.example/api
                          logs: all
                        """,
                        java.util.Map.of());

        assertThat(called)
                .as("a host must not replace the project's chosen forge or credential")
                .containsSequence("--forge", "project-forge")
                .containsSequence("--credential", "project-builds")
                .containsSequence("--api", "https://forge.example/api")
                .containsSequence("--logs", "all")
                .doesNotContain("host-forge", "host-builds", "--builds-only", "--from-host");
    }

    @Test
    void aHostTaskWithoutAGateIsNotSentForATokenItsBuildsCouldNotUse(@TempDir Path dir)
            throws Exception {
        // A task with no branch at the forge is told no builds, so making the token would not help.
        final List<String> called =
                startWithBuilds(
                        dir,
                        "guarded",
                        true,
                        true,
                        "",
                        java.util.Map.of(
                                "other-builds",
                                new org.fuin.porelo.vault.VaultEntry(
                                        "unrelated-fixture-secret", "api-key")),
                        true);

        assertThat(err.toString())
                .as("only a task the forge builds may be sent to porelo host forge-token")
                .doesNotContain("porelo host forge-token")
                .contains("the task has no upstream branch");
        assertThat(called).as("a task without a gate starts no watch-builds helper").isEmpty();
    }

    private List<String> startWithBuilds(
            Path dir, String securityClass, boolean onHost, boolean hostBuilds, String ownBuilds)
            throws Exception {
        return startWithBuilds(
                dir,
                securityClass,
                onHost,
                hostBuilds,
                ownBuilds,
                java.util.Map.of(
                        "host-builds",
                        new org.fuin.porelo.vault.VaultEntry("fixture-forge-value", "api-key")));
    }

    private List<String> startWithBuilds(
            Path dir,
            String securityClass,
            boolean onHost,
            boolean hostBuilds,
            String ownBuilds,
            java.util.Map<String, org.fuin.porelo.vault.VaultEntry> stored)
            throws Exception {
        return startWithBuilds(dir, securityClass, onHost, hostBuilds, ownBuilds, stored, false);
    }

    private List<String> startWithBuilds(
            Path dir,
            String securityClass,
            boolean onHost,
            boolean hostBuilds,
            String ownBuilds,
            java.util.Map<String, org.fuin.porelo.vault.VaultEntry> stored,
            boolean noGate)
            throws Exception {
        final PoreloContext context =
                context(dir, true).asking(shown -> "test-passphrase".toCharArray());
        context.vault().write(stored, "test-passphrase".toCharArray());
        final Path upstream = dir.resolve("upstream.git");
        git(dir, "init", "-q", "--bare", "--initial-branch=main", upstream.toString());
        final Path seed = Files.createDirectory(dir.resolve("seed"));
        git(seed, "init", "-q");
        git(seed, "commit", "-q", "--allow-empty", "-m", "initial");
        git(seed, "push", "-q", upstream.toString(), "HEAD:refs/heads/main");
        final String file =
                """
                project:
                  name: uc
                  security_class: %s
                  upstream: "file://%s"
                image:
                  from: ubuntu:24.04
                """
                                .formatted(securityClass, upstream)
                        + ownBuilds;
        final Path person = dir.resolve("person");
        final Process keygen =
                new ProcessBuilder(
                                "ssh-keygen",
                                "-q",
                                "-t",
                                "ed25519",
                                "-N",
                                "",
                                "-f",
                                person.toString())
                        .start();
        assertThat(keygen.waitFor()).as("the fixture creates its own certifier's key").isZero();
        final String[] key = Files.readString(dir.resolve("person.pub")).strip().split(" ");
        final Path projectFile =
                PlacedFixture.place(
                        context, "uc", file, PlacedFixture.key("alice", key[0] + " " + key[1]));
        final Path hostFiles = Files.createDirectory(dir.resolve("hosted"));
        final OrganizationProjects projects =
                new OrganizationProjects(
                        context.paths().projects().organizationProjects(), hostFiles);
        final var document = new java.util.LinkedHashMap<String, Object>();
        document.put("project", "uc");
        document.put("revision", "host-revision");
        document.put("address", "https://forge.example/uc.git");
        document.put(
                "signing_keys",
                List.of(
                        java.util.Map.of(
                                "login", "alice",
                                "role", "member",
                                "key", key[0] + " " + key[1],
                                "valid_from", "2000-01-01",
                                "valid_until", "2999-01-01")));
        document.put("host", "build");
        if (hostBuilds) {
            document.put(
                    "builds",
                    java.util.Map.of(
                            "forge", "host-forge",
                            "credential", "host-builds",
                            "api", "http://127.0.0.1:3000/api/v1",
                            "logs", "all"));
        }
        Files.writeString(hostFiles.resolve("uc.json"), org.fuin.porelo.wire.Json.write(document));
        final Path hostHelper = dir.resolve("host-person");
        if (onHost) {
            Files.writeString(hostHelper, "");
        }
        final Path readers =
                Files.createDirectories(context.paths().xdg().data().resolve("builds"));
        for (final String forge : List.of("host-forge", "project-forge")) {
            final Path reader = Files.writeString(readers.resolve(forge), "#!/bin/sh\nexit 0\n");
            reader.toFile().setExecutable(true);
        }
        final Path called = dir.resolve("builds-called");
        final String ownPorelo = System.getProperty(PoreloBinary.NAMED);
        final Path recorder = dir.resolve("recording-porelo");
        Files.writeString(
                recorder,
                """
                #!/bin/sh
                if [ "$1" = task ] && [ "$2" = watch-builds ]; then
                  called=%s
                  printf '%%s\\n' "$@" > "$called.staged"
                  mv -- "$called.staged" "$called"
                  exit 0
                fi
                exec %s "$@"
                """
                        .formatted(shellArgument(called.toString()), shellArgument(ownPorelo)));
        recorder.toFile().setExecutable(true);
        final Path socket = dir.resolve("person.sock");
        try (TestSshAgent agents = new TestSshAgent()) {
            agents.start(socket);
            final ProcessBuilder add = new ProcessBuilder("ssh-add", "-q", person.toString());
            add.environment().put("SSH_AUTH_SOCK", socket.toString());
            assertThat(add.start().waitFor())
                    .as("the fixture's agent holds its certifier")
                    .isZero();
            assertThat(context.openerAsking(false, new PrintWriter(err)))
                    .as("the fixture unlocks only its own vault")
                    .isPresent();
            System.setProperty(PoreloBinary.NAMED, recorder.toString());
            final java.util.concurrent.atomic.AtomicBoolean started =
                    new java.util.concurrent.atomic.AtomicBoolean();
            final int code =
                    new TaskLaunch(
                                    context,
                                    new TaskLaunch.Request(
                                            "shell",
                                            projectFile,
                                            null,
                                            null,
                                            null,
                                            8,
                                            null,
                                            noGate,
                                            false,
                                            "deny",
                                            true,
                                            org.fuin.porelo.wire.TaskMode.SHELL,
                                            null,
                                            null,
                                            null,
                                            60,
                                            "uc"),
                                    new StartSteps(step -> {}),
                                    new HostPerson(context, hostHelper),
                                    socket,
                                    projects)
                            .launch(
                                    new PrintWriter(out, true),
                                    new PrintWriter(err, true),
                                    running -> {
                                        started.set(true);
                                        return 0;
                                    });
            assertThat(code).as("the task must reach its helpers: %s", err).isZero();
            assertThat(started)
                    .as("missing build information must not prevent the task from starting")
                    .isTrue();
            // The launch records each helper it started before it returns; only then is the
            // helper's own record of its arguments worth waiting for.
            final boolean watching =
                    TaskHelpers.readFrom(shellState(context)).helpers().stream()
                            .anyMatch(helper -> helper.command().contains("watch-builds"));
            for (int attempt = 0; watching && attempt < 250 && !Files.exists(called); attempt++) {
                Thread.sleep(20);
            }
            return watching ? Files.readAllLines(called) : List.of();
        } finally {
            TaskLifecycle.stopHelpers(shellState(context));
            System.setProperty(PoreloBinary.NAMED, ownPorelo);
            PoreloContext.forgetAsked();
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
    /** Supplies the id and sidecar a real start left for the old abbreviated runtime rows. */
    private void recordedTasks(PoreloContext context) {
        final String rows = ((StoppingCommandRunner) runner).listedTasks();
        if (rows.isBlank()) return;
        final List<String> complete = new ArrayList<>();
        for (String row : rows.lines().toList()) {
            final String[] original = row.split("\t", -1);
            if (!org.fuin.porelo.runtime.ContainerName.isTask(original[0])) {
                complete.add(row);
                continue;
            }
            final String[] fields = java.util.Arrays.copyOf(original, Math.max(9, original.length));
            java.util.Arrays.fill(fields, original.length, fields.length, "");
            if (fields[8].isEmpty())
                fields[8] =
                        java.util.Objects.requireNonNullElse(
                                context.recordedId(fields[0]), "0123456789abcdef");
            if (!fields[4].isEmpty()
                    && !Files.exists(
                            context.paths()
                                    .tasks()
                                    .taskRecord(fields[0])
                                    .resolve("sidecar.json"))) {
                try {
                    LegacyTaskFixture.record(context, fields[0], fields[4], fields[8]);
                } catch (IOException ex) {
                    throw new java.io.UncheckedIOException(ex);
                }
            }
            complete.add(String.join("\t", fields));
        }
        runner.answering("ps", String.join("\n", complete) + "\n");
    }

    private int execute(PoreloContext context, String... args) {
        if (args.length > 1 && args[0].equals("task") && args[1].equals("start"))
            recordedTasks(context);
        final CommandLine cmd = PoreloCli.commandLine(context);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        final List<String> all = new java.util.ArrayList<>(List.of(args));
        if (all.size() > 1
                && "task".equals(all.get(0))
                && "start".equals(all.get(1))
                && !all.contains("--repository")
                && !all.contains("-r")
                // A task that reads every repository names none.
                && !all.contains("--all-repositories")) {
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
        recordedTasks(context);
        final CommandLine cmd = PoreloCli.commandLine(context);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        final TaskRunCommand start =
                (TaskRunCommand)
                        cmd.getSubcommands().get("task").getSubcommands().get("start").getCommand();
        start.terminal = () -> terminal;
        start.uncertifiedAsker = uncertifiedAnswer;
        final List<String> all =
                new ArrayList<>(List.of("task", "start", "shell", "--repository", "uc"));
        all.addAll(List.of(args));
        return cmd.execute(all.toArray(new String[0]));
    }

    /** What the person answers about a start without a certificate on a host in test mode. */
    private Offer.Asker uncertifiedAnswer = Offer.Asker.NOBODY;

    private boolean firstStartDetected;

    /** The checkout's origin, or {@code null} for a checkout that has none. */
    private @org.jspecify.annotations.Nullable String firstStartOrigin =
            "https://forge.example/acme/shop.git";

    private final List<String> firstStartAsked = new ArrayList<>();

    /** What the start asked about taking its repository out of 'default'. */
    private final List<String> leaveDefaultAsked = new ArrayList<>();

    /** What the start asked about proposing a repository's tools, and what the person answers. */
    private final List<String> proposalAsked = new ArrayList<>();

    private @org.jspecify.annotations.Nullable String proposalAnswer;

    /** What the start asked about a task already there, and what the person answers. */
    private final List<String> taskAsked = new ArrayList<>();

    private @org.jspecify.annotations.Nullable String taskAnswer;

    /** The proxy settings each lookup a first start made was given. */
    private final List<java.util.Map<String, String>> firstStartLookedUp = new ArrayList<>();

    private static final String PROXY = "http://someone:not-a-real-one@proxy.example:3128";

    @Test
    void aFirstStartAtATerminalLooksUpThroughTheShellsProxyAndNeverSaysIt(@TempDir Path dir)
            throws IOException {
        final Path checkout = Files.createDirectories(dir.resolve("shop")).toRealPath();
        final PoreloContext context =
                context(
                        dir,
                        true,
                        name ->
                                switch (name) {
                                    case "HTTPS_PROXY", "no_proxy" -> PROXY;
                                    case "PORELO_OTHER" -> "kept-out";
                                    default -> null;
                                });

        firstStartAt(context, checkout, true, "n", "--attach", "shell", "--detach");

        assertThat(firstStartLookedUp)
                .as("one lookup, with the proxy settings of the shell and nothing else")
                .containsExactly(java.util.Map.of("HTTPS_PROXY", PROXY, "no_proxy", PROXY));
        assertThat(err.toString())
                .as("the fetched line is shown whole before the question")
                .contains("RUN echo fetched-maven");
        assertThat(out.toString() + err.toString())
                .as("a proxy's address may hold a credential")
                .doesNotContain("not-a-real-one");
    }

    @Test
    void aDryRunAndAStartWithNoInputLookNothingUp(@TempDir Path dir) throws IOException {
        final Path checkout = Files.createDirectories(dir.resolve("shop")).toRealPath();

        firstStartAt(context(dir, true), checkout, true, "y", "--dry-run");
        firstStartAt(
                context(dir, true),
                checkout,
                true,
                "y",
                "--no-input",
                "--attach",
                "shell",
                "--detach");

        assertThat(firstStartDetected).as("both still say what was found").isTrue();
        assertThat(firstStartLookedUp).as("a dry run promises nothing opened").isEmpty();
        assertThat(err.toString()).doesNotContain("fetched-maven");
    }

    @Test
    void aStartWithAFileLooksNothingUp(@TempDir Path dir) throws IOException {
        final Path checkout = Files.createDirectories(dir.resolve("shop")).toRealPath();
        Files.writeString(checkout.resolve("project.yml"), APPROVED);

        firstStartAt(context(dir, true), checkout, true, "n", "--attach", "shell", "--detach");

        assertThat(firstStartAsked).as("the file is asked about").hasSize(1);
        assertThat(firstStartLookedUp).isEmpty();
    }

    /** Runs {@code task start} with no project named, standing in a checkout with a plugin. */
    private int firstStartAt(
            PoreloContext context, Path checkout, boolean terminal, String answer, String... args)
            throws IOException {
        recordedTasks(context);
        runner.answering("--show-toplevel", checkout.toString());
        if (firstStartOrigin == null) {
            runner.failing("get-url origin", 2, "error: No such remote 'origin'");
        } else {
            runner.answering("get-url origin", firstStartOrigin);
        }
        final Path plugin =
                Files.createDirectories(context.paths().ecosystemDirectories().getFirst())
                        .resolve("maven");
        Files.writeString(
                plugin, "#!/bin/sh\nprintf '{\"protocolVersion\":1,\"ecosystem\":\"maven\"}'\n");
        plugin.toFile().setExecutable(true);
        final CommandLine cmd = PoreloCli.commandLine(context);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        final TaskRunCommand start =
                (TaskRunCommand)
                        cmd.getSubcommands().get("task").getSubcommands().get("start").getCommand();
        start.terminal = () -> terminal;
        start.standing = checkout;
        start.firstStartAsker =
                question -> {
                    firstStartAsked.add(question);
                    return answer;
                };
        start.taskAsker =
                question -> {
                    taskAsked.add(question);
                    return taskAnswer;
                };
        start.leaveDefaultAsker =
                question -> {
                    leaveDefaultAsked.add(question);
                    return answer;
                };
        start.proposalAsker =
                question -> {
                    proposalAsked.add(question);
                    return proposalAnswer;
                };
        start.detector =
                new EcosystemDetector() {
                    @Override
                    public org.fuin.porelo.ecosystem.api.Detection detect(
                            final org.fuin.porelo.ecosystem.api.InstalledEcosystems.Description
                                    described,
                            final Path directory,
                            final Path home) {
                        firstStartDetected = true;
                        return new org.fuin.porelo.ecosystem.api.Detection(
                                List.of(
                                        new org.fuin.porelo.ecosystem.api.Finding(
                                                org.fuin.porelo.ecosystem.api.Finding.Part.TOOLS,
                                                "package.maven",
                                                "",
                                                "pom.xml",
                                                1,
                                                org.fuin.porelo.ecosystem.api.Finding.Origin
                                                        .CHECKOUT,
                                                false,
                                                "")),
                                List.of());
                    }

                    @Override
                    public java.util.Optional<List<org.fuin.porelo.ecosystem.api.Finding>> resolve(
                            final org.fuin.porelo.ecosystem.api.InstalledEcosystems.Description
                                    described,
                            final Path directory,
                            final Path home,
                            final List<org.fuin.porelo.ecosystem.api.Finding> detected,
                            final java.util.Map<String, String> proxies) {
                        firstStartLookedUp.add(proxies);
                        return java.util.Optional.of(
                                List.of(
                                        new org.fuin.porelo.ecosystem.api.Finding(
                                                org.fuin.porelo.ecosystem.api.Finding.Part.TOOLS,
                                                "run.maven",
                                                "RUN echo fetched-maven",
                                                "pom.xml",
                                                1,
                                                org.fuin.porelo.ecosystem.api.Finding.Origin
                                                        .CHECKOUT,
                                                false,
                                                ""),
                                        new org.fuin.porelo.ecosystem.api.Finding(
                                                org.fuin.porelo.ecosystem.api.Finding.Part.RULES,
                                                "host.repo.example.org",
                                                "yes",
                                                "pom.xml",
                                                1,
                                                org.fuin.porelo.ecosystem.api.Finding.Origin.YOURS,
                                                false,
                                                "")));
                    }
                };
        final List<String> all = new ArrayList<>(List.of("task", "start"));
        all.addAll(List.of(args));
        return cmd.execute(all.toArray(new String[0]));
    }

    @Test
    void aClonedRepositorysImageLineIsNotBuiltWithNoInputUntilAllowed(@TempDir Path dir)
            throws IOException {
        // Its lines run as root while the image is built: nobody answered, so nothing is built.
        final Path checkout = Files.createDirectories(dir.resolve("shop")).toRealPath();
        Files.writeString(
                checkout.resolve("porelo.yml"), "image:\n  run: \"RUN echo cloned-line\"\n");

        final int refused =
                firstStartAt(
                        context(dir, true),
                        checkout,
                        true,
                        "y",
                        "--no-input",
                        "--attach",
                        "shell",
                        "--detach");

        assertThat(refused).isNotZero();
        assertThat(firstStartAsked).as("a file already there is no first start's").isEmpty();
        assertThat(err.toString())
                .contains("has not allowed", "+ image.run[1] = RUN echo cloned-line");
        assertThat(runner.lines())
                .noneMatch(line -> line.contains("podman build") || line.contains("podman create"));

        final PoreloContext context = context(dir, true);
        final Path file = new DefaultProject(context).file();
        final String hash =
                RepositoryOwnSettings.of(
                                context,
                                file,
                                org.fuin.porelo.core.project.ProjectReader.read(file)
                                        .repository("shop"))
                        .hash();
        assertThat(RepositoryOwnSettings.allowIfUnchanged(context, "shop", hash)).isTrue();
        err.getBuffer().setLength(0);

        assertThat(
                        firstStartAt(
                                context,
                                checkout,
                                true,
                                "y",
                                "--no-input",
                                "--attach",
                                "shell",
                                "--detach"))
                .as("allowed, it starts unasked: %s", err)
                .isZero();
        assertThat(err.toString()).doesNotContain("has not allowed");
    }

    @Test
    void aDryRunInDefaultNamesEachLineNotAllowedOnce(@TempDir Path dir) throws IOException {
        // The checkout's settings are said once, the image among them: no second list for it.
        final Path checkout = Files.createDirectories(dir.resolve("shop")).toRealPath();
        Files.writeString(
                checkout.resolve("porelo.yml"), "image:\n  run: \"RUN echo cloned-line\"\n");

        final int code =
                firstStartAt(
                        context(dir, true),
                        checkout,
                        true,
                        "y",
                        "--no-input",
                        "--dry-run",
                        "--attach",
                        "shell",
                        "--detach");

        assertThat(code).as("%s%s", out, err).isZero();
        assertThat(out.toString().lines().filter(line -> line.contains("RUN echo cloned-line")))
                .as("%s", out)
                .hasSize(1);
        assertThat(out.toString())
                .contains("not allowed on this machine yet")
                .doesNotContain("image settings not allowed");
    }

    @Test
    void aNoAtTheFirstStartBuildsNoLineOfThePersonsOwnFile(@TempDir Path dir) throws IOException {
        final Path checkout = Files.createDirectories(dir.resolve("shop")).toRealPath();
        Files.writeString(
                checkout.resolve(".porelo.yml"), "image:\n  run: \"RUN echo own-line\"\n");

        final int code =
                firstStartAt(
                        context(dir, true), checkout, true, "n", "--attach", "shell", "--detach");

        assertThat(firstStartAsked).hasSize(1);
        assertThat(checkout.resolve("porelo.yml")).doesNotExist();
        assertThat(code).as("the start is refused: %s", out).isNotZero();
        assertThat(err.toString()).contains("+ image.run[1] = RUN echo own-line");
        assertThat(runner.lines())
                .noneMatch(line -> line.contains("podman build") || line.contains("podman create"));
    }

    @Test
    void aYesAtTheFirstStartStartsFromTheFileItWrote(@TempDir Path dir) throws IOException {
        final Path checkout = Files.createDirectories(dir.resolve("shop")).toRealPath();

        firstStartAt(context(dir, true), checkout, true, "y", "--attach", "shell", "--detach");

        assertThat(firstStartAsked).hasSize(1);
        assertThat(checkout.resolve("porelo.yml")).exists();
        assertThat(checkout.resolve("project.yml")).doesNotExist();
        assertThat(Files.readString(checkout.resolve("porelo.yml"))).contains("repo.example.org");
        assertThat(err.toString()).doesNotContain("has not allowed", "Allow it for");
        assertThat(out.toString())
                .as("the launch passed the settings check without another question")
                .contains("base image", "egress.domains[repo.example.org]");
        assertThat(out.toString()).contains("project        'default'");
        assertThat(out.toString()).contains("task           shop");
        assertThat(String.join("\n", runner.lines())).contains("porelo-default_shop");
    }

    @Test
    void aSecondStartWithNoNameAndNoTerminalStartsNothingAndNamesTheWayIn(@TempDir Path dir)
            throws IOException {

        // Each start with no name in a checkout with its own project file and no origin made one
        // more task, 'shop-2', 'shop-3', unasked; with nobody to ask, nothing starts at all.
        firstStartOrigin = null;
        final Path checkout = Files.createDirectories(dir.resolve("shop")).toRealPath();
        firstStartAt(context(dir, true), checkout, true, "y", "--attach", "shell", "--detach");
        out.getBuffer().setLength(0);
        runner.answering("ps", SHOP_TASK);

        final int code =
                firstStartAt(
                        context(dir, true), checkout, false, "y", "--attach", "shell", "--detach");

        assertThat(code).as("try again, by name").isEqualTo(75);
        assertThat(err.toString())
                .as("the stopped task and how to start it again")
                .contains("nothing started", "  porelo task start shop\n");
        assertThat(out.toString()).as("no task named, none made").doesNotContain("task     ");
    }

    @Test
    void aSecondStartWithNoNameAtATerminalAsksAndNMakesANewTask(@TempDir Path dir)
            throws IOException {
        final Path checkout = Files.createDirectories(dir.resolve("shop")).toRealPath();
        firstStartAt(context(dir, true), checkout, true, "y", "--attach", "shell", "--detach");
        out.getBuffer().setLength(0);
        runner.answering("ps", SHOP_TASK);

        taskAnswer = "n";
        firstStartAt(context(dir, true), checkout, true, "y", "--attach", "shell", "--detach");

        assertThat(taskAsked)
                .as("asked whether to go into the task there")
                .singleElement()
                .asString()
                .isEqualTo("shop is stopped. Start it again? [Y/n]")
                .as("the task's own name, not its container's")
                .doesNotContain("porelo-");
        assertThat(out.toString()).as("n makes a new one").contains("task           shop-2");
    }

    @Test
    void aSecondStartWithNoNameAtATerminalAndAYesStartsTheTaskThereAgain(@TempDir Path dir)
            throws IOException {
        final Path checkout = Files.createDirectories(dir.resolve("shop")).toRealPath();
        firstStartAt(context(dir, true), checkout, true, "y", "--attach", "shell", "--detach");
        out.getBuffer().setLength(0);
        runner.answering("ps", SHOP_TASK);

        taskAnswer = "y";
        firstStartAt(context(dir, true), checkout, true, "y", "--attach", "shell", "--detach");

        assertThat(taskAsked).as("asked once").hasSize(1);
        assertThat(out.toString())
                .as("y takes the stopped task there")
                .contains("task           shop - the one started from this checkout")
                .doesNotContain("shop-2");
    }

    @Test
    void aSecondStartTakesTheTaskOfACheckoutWhoseRepositoryHasANumberedName(@TempDir Path dir)
            throws IOException {

        // A second checkout of a repository 'default' already holds is named 'shop-2'; on
        // snapshot.512 a start with no name there missed its task and made 'shop-2-2'.
        final Path earlier = Files.createDirectories(dir.resolve("earlier/shop")).toRealPath();
        try {
            new DefaultProject(context(dir, true))
                    .add(earlier.toString(), null, earlier.toString(), firstStartOrigin);
        } catch (DefaultProject.Refused ex) {
            throw new AssertionError(ex);
        }
        final Path checkout = Files.createDirectories(dir.resolve("shop")).toRealPath();
        firstStartAt(context(dir, true), checkout, true, "y", "--attach", "shell", "--detach");
        assertThat(out.toString()).as("the first start's task").contains("task           shop-2");
        out.getBuffer().setLength(0);
        // A container a current start makes is named 'porelo-default_shop-2', not
        // 'porelo-default-shop-2'.
        TaskIdentityFixture.record(
                context(dir, true).paths(),
                "porelo-default_shop-2",
                "default",
                "shop-2",
                "0123456789abcdef");
        runner.answering("ps", encodedShopTask("shop-2", "Up 5 minutes"));

        taskAnswer = "";
        firstStartAt(context(dir, true), checkout, true, "y", "--attach", "shell", "--detach");

        assertThat(taskAsked)
                .as("asked about the task there, by its own name")
                .containsExactly("shop-2 is running. Attach to it instead? [Y/n]");
        assertThat(out.toString())
                .as("Enter takes the checkout's own task, not another")
                .contains("task           shop-2 - the one started from this checkout")
                .doesNotContain("shop-2-2");
    }

    @Test
    void enterStartsTheCheckoutsStoppedTaskAgainAndAYesAloneAnswersNothing(@TempDir Path dir)
            throws IOException {
        final Path checkout = Files.createDirectories(dir.resolve("shop")).toRealPath();
        firstStartAt(context(dir, true), checkout, true, "y", "--attach", "shell", "--detach");
        TaskIdentityFixture.record(
                context(dir, true).paths(),
                "porelo-default_shop",
                "default",
                "shop",
                "0123456789abcdef");
        runner.answering("ps", encodedShopTask("shop", "Exited (0)"));
        out.getBuffer().setLength(0);

        final int refused =
                firstStartAt(
                        context(dir, true),
                        checkout,
                        true,
                        "y",
                        "--yes",
                        "--attach",
                        "shell",
                        "--detach");

        assertThat(taskAsked).as("--yes does not answer which task").isEmpty();
        assertThat(refused).isEqualTo(75);
        assertThat(err.toString()).contains("  porelo task start shop\n");

        out.getBuffer().setLength(0);
        taskAnswer = "";
        firstStartAt(context(dir, true), checkout, true, "y", "--attach", "shell", "--detach");

        assertThat(taskAsked).containsExactly("shop is stopped. Start it again? [Y/n]");
        assertThat(out.toString())
                .as("Enter starts that task again, and no second one is made")
                .contains("task           shop - the one started from this checkout")
                .doesNotContain("shop-2");
    }

    @Test
    void noAnswerStartsNothingAndAnAnswerThatChoosesNothingIsRefused(@TempDir Path dir)
            throws IOException {
        final Path checkout = Files.createDirectories(dir.resolve("shop")).toRealPath();
        firstStartAt(context(dir, true), checkout, true, "y", "--attach", "shell", "--detach");
        TaskIdentityFixture.record(
                context(dir, true).paths(),
                "porelo-default_shop",
                "default",
                "shop",
                "0123456789abcdef");
        runner.answering("ps", encodedShopTask("shop", "Up 5 minutes"));
        out.getBuffer().setLength(0);

        taskAnswer = null;
        final int unanswered =
                firstStartAt(
                        context(dir, true), checkout, true, "y", "--attach", "shell", "--detach");

        assertThat(unanswered).as("the end of the input: nobody to ask").isEqualTo(75);
        assertThat(err.toString()).contains("  porelo task attach shop\n");

        err.getBuffer().setLength(0);
        taskAnswer = "2";
        final int refused =
                firstStartAt(
                        context(dir, true), checkout, true, "y", "--attach", "shell", "--detach");

        assertThat(refused).isEqualTo(64);
        assertThat(err.toString()).contains("nothing started: answer y or n");
        assertThat(out.toString()).as("no task named, none made").doesNotContain("task     ");
    }

    /** A row of 'podman ps' for a task of 'default' a current start made, named as it names it. */
    private static String encodedShopTask(final String task, final String status) {
        return "porelo-default_"
                + task
                + "\t"
                + status
                + "\t1700000000\t1700000100\tdefault\tguarded\t"
                + task
                + "\t\t0123456789abcdef\t"
                + task
                + "\n";
    }

    @Test
    void aCheckoutOnceInDefaultAndThenGivenAnApprovedFileTakesItsTaskAgain(@TempDir Path dir)
            throws IOException {

        // A start that asked nothing put the checkout in 'default'; the later approved file made
        // the task, and each start after it saw only 'default' and made another.
        // The approved file names its repository by this upstream, as a checkout cloned from it.
        firstStartOrigin = "https://forge.example/acme/shop.git";
        final Path checkout = Files.createDirectories(dir.resolve("shop")).toRealPath();
        firstStartAt(context(dir, true), checkout, false, "y", "--attach", "shell", "--detach");
        Files.writeString(
                checkout.resolve("project.yml"), APPROVED.replace("strangers", "shop-project"));
        firstStartAt(context(dir, true), checkout, true, "y", "--attach", "shell", "--detach");
        out.getBuffer().setLength(0);
        runner.answering(
                "ps",
                SHOP_TASK
                        .replace("porelo-default-shop", "porelo-shop-project-shop")
                        .replace("\tdefault\t", "\tshop-project\t"));

        taskAnswer = "";
        firstStartAt(context(dir, true), checkout, true, "y", "--attach", "shell", "--detach");

        assertThat(out.toString()).contains("task           shop - ").doesNotContain("shop-2");
    }

    @Test
    void aCheckoutsFileGivesOnlyTheNameAndTheApprovedCopyTheRest(@TempDir Path dir)
            throws IOException {
        // The approved file names its repository by this upstream, as a checkout cloned from it.
        firstStartOrigin = "https://forge.example/acme/shop.git";
        final Path checkout = Files.createDirectories(dir.resolve("shop")).toRealPath();
        Files.writeString(
                checkout.resolve("project.yml"), APPROVED.replace("strangers", "shop-project"));
        firstStartAt(context(dir, true), checkout, true, "y", "--attach", "shell", "--detach");
        runner.answering(
                "ps",
                SHOP_TASK
                        .replace("porelo-default-shop", "porelo-shop-project_shop")
                        .replace("\tdefault\t", "\tshop-project\t")
                        .replace("abcdef\n", "abcdef\tshop\n"));
        final Path file = checkout.resolve("project.yml");
        final String text = Files.readString(file);
        assertThat(text).contains("\n  shop:\n");

        // An edit nobody approved names another repository for this checkout.
        Files.writeString(file, text.replace("\n  shop:\n", "\n  other:\n"));

        assertThat(TaskTarget.fromCheckout(context(dir, true), checkout))
                .as("the approved copy says which repository this checkout is")
                .containsExactly("porelo-shop-project_shop");
    }

    @Test
    void aLinkInPlaceOfTheCheckoutsFileNamesNoProject(@TempDir Path dir) throws IOException {
        firstStartOrigin = null;
        final Path checkout = Files.createDirectories(dir.resolve("shop")).toRealPath();
        Files.writeString(
                checkout.resolve("project.yml"), APPROVED.replace("strangers", "shop-project"));
        firstStartAt(context(dir, true), checkout, true, "y", "--attach", "shell", "--detach");
        runner.answering(
                "ps",
                SHOP_TASK
                        .replace("porelo-default-shop", "porelo-shop-project-shop")
                        .replace("\tdefault\t", "\tshop-project\t"));
        final Path file = checkout.resolve("project.yml");
        final Path elsewhere =
                Files.writeString(
                        Files.createDirectories(dir.resolve("elsewhere")).resolve("project.yml"),
                        Files.readString(file));
        Files.delete(file);
        Files.createSymbolicLink(file, elsewhere);

        assertThat(TaskTarget.fromCheckout(context(dir, true), checkout))
                .as("a start refuses a linked file, so nothing else follows it")
                .isNull();
    }

    @Test
    void severalTasksAreChosenAtATerminalAndRefusedWithNoInput(@TempDir Path dir)
            throws IOException {
        final Path checkout = Files.createDirectories(dir.resolve("shop")).toRealPath();
        firstStartAt(context(dir, true), checkout, true, "y", "--attach", "shell", "--detach");
        LegacyTaskFixture.record(
                context(dir, true), "porelo-default-shop-2", "default", "0123456789abcdef");
        runner.answering(
                "ps",
                SHOP_TASK + SHOP_TASK.replace("porelo-default-shop\t", "porelo-default-shop-2\t"));
        out.getBuffer().setLength(0);

        taskAnswer = "a";
        final int refused =
                firstStartAt(
                        context(dir, true),
                        checkout,
                        true,
                        "y",
                        "--no-input",
                        "--attach",
                        "shell",
                        "--detach");

        assertThat(taskAsked).as("--no-input asks nothing, even at a terminal").isEmpty();
        assertThat(refused).as("try again, by name").isEqualTo(75);
        assertThat(err.toString())
                .contains("  porelo task start shop\n", "  porelo task start shop-2\n");

        taskAnswer = "2";
        firstStartAt(context(dir, true), checkout, true, "y", "--attach", "shell", "--detach");

        assertThat(taskAsked)
                .as("asked, at a terminal, which of them, by number")
                .containsExactly(
                        "This checkout has 1) shop (stopped), 2) shop-2 (stopped). Which one,"
                                + " by number, or n for a new task? [1/n]");
        assertThat(out.toString()).contains("task           shop-2 - the one started");
    }

    /** The task a first start in 'shop' made, as podman lists it. */
    private static final String SHOP_TASK =
            "porelo-default-shop\tExited (0)\t1700000000\t1700000100\tdefault\tguarded"
                    + "\tshop\t\t0123456789abcdef\n";

    @Test
    void aNoAtTheFirstStartGoesIntoDefaultAndKeepsNothing(@TempDir Path dir) throws IOException {
        final Path checkout = Files.createDirectories(dir.resolve("shop")).toRealPath();

        firstStartAt(context(dir, true), checkout, true, "n", "--attach", "shell", "--detach");

        assertThat(firstStartAsked).hasSize(1);
        assertThat(proposalAsked).as("one question about the adapters' findings").isEmpty();
        assertThat(checkout.resolve("project.yml")).doesNotExist();
        assertThat(checkout.resolve("porelo.yml")).doesNotExist();
        assertThat(out.toString()).contains("'default'");
    }

    /** Adds the checkout to 'default' as 'shop', its proposal never answered. */
    private DefaultProject.Entry shopInDefault(final Path dir, final Path checkout)
            throws Exception {
        return new DefaultProject(context(dir, true))
                .add(
                        checkout.toString(),
                        "shop",
                        checkout.toString(),
                        String.valueOf(firstStartOrigin));
    }

    private DefaultProject.Proposal shopsProposal(final Path dir) throws IOException {
        final DefaultProject defaults = new DefaultProject(context(dir, true));
        return defaults.proposal(
                defaults.entries().stream()
                        .filter(each -> each.name().equals("shop"))
                        .findFirst()
                        .orElseThrow());
    }

    @Test
    void anUnaskedStartAtATerminalAsksOnceAndTheNextStartDoesNot(@TempDir Path dir)
            throws Exception {
        final Path checkout = Files.createDirectories(dir.resolve("shop")).toRealPath();
        shopInDefault(dir, checkout);
        proposalAnswer = "n";

        firstStartAt(
                context(dir, true),
                checkout,
                true,
                "y",
                "-p",
                "default",
                "-r",
                "shop",
                "--attach",
                "shell",
                "--detach");

        assertThat(proposalAsked)
                .as("asked once, naming what a yes writes and where: %s", err)
                .singleElement()
                .asString()
                .contains("Propose them from its build files?", ".porelo.yml", "[y/N]");
        assertThat(shopsProposal(dir))
                .as("a no is recorded")
                .isEqualTo(DefaultProject.Proposal.ASKED);
        assertThat(checkout.resolve(".porelo.yml")).as("a no writes nothing").doesNotExist();

        firstStartAt(
                context(dir, true),
                checkout,
                true,
                "y",
                "-p",
                "default",
                "-r",
                "shop",
                "--attach",
                "shell",
                "--detach");

        assertThat(proposalAsked).as("the second start asks nothing").hasSize(1);
        assertThat(err.toString()).doesNotContain("porelo project init");
    }

    @Test
    void aYesWritesTheToolsDraftWhoseLinesTheStartReads(@TempDir Path dir) throws Exception {
        final Path checkout = Files.createDirectories(dir.resolve("shop")).toRealPath();
        shopInDefault(dir, checkout);
        proposalAnswer = "y";

        firstStartAt(
                context(dir, true),
                checkout,
                true,
                "y",
                "-p",
                "default",
                "-r",
                "shop",
                "--attach",
                "shell",
                "--detach");

        assertThat(proposalAsked).hasSize(1);
        assertThat(checkout.resolve(".porelo.yml")).exists();
        assertThat(Files.readString(checkout.resolve(".porelo.yml")))
                .contains("RUN echo fetched-maven");
        assertThat(shopsProposal(dir))
                .as("a draft written is configured")
                .isEqualTo(DefaultProject.Proposal.CONFIGURED);
        assertThat(err.toString())
                .as("the image order reads the draft and asks for its line before building it")
                .contains("tools of shop written to", "+ image.run[1] = RUN echo fetched-maven");
        assertThat(runner.lines()).noneMatch(line -> line.contains("podman build"));
    }

    @Test
    void yesWithoutATerminalAsksNothingAndNamesProjectInit(@TempDir Path dir) throws Exception {
        final Path checkout = Files.createDirectories(dir.resolve("shop")).toRealPath();
        shopInDefault(dir, checkout);
        proposalAnswer = "y";

        firstStartAt(
                context(dir, true),
                checkout,
                false,
                "y",
                "--yes",
                "-p",
                "default",
                "-r",
                "shop",
                "--attach",
                "shell",
                "--detach");

        assertThat(proposalAsked).as("--yes never answers it").isEmpty();
        assertThat(err.toString().lines().filter(line -> line.contains("porelo project init")))
                .as("one line: %s", err)
                .singleElement()
                .asString()
                .contains("porelo project init " + checkout);
        assertThat(checkout.resolve(".porelo.yml")).doesNotExist();
        assertThat(shopsProposal(dir)).isEqualTo(DefaultProject.Proposal.UNASKED);
    }

    @Test
    void yesAtATerminalAsksNothingEither(@TempDir Path dir) throws Exception {
        final Path checkout = Files.createDirectories(dir.resolve("shop")).toRealPath();
        shopInDefault(dir, checkout);
        proposalAnswer = "y";

        firstStartAt(
                context(dir, true),
                checkout,
                true,
                "y",
                "--yes",
                "-p",
                "default",
                "-r",
                "shop",
                "--attach",
                "shell",
                "--detach");

        assertThat(proposalAsked).isEmpty();
        assertThat(err.toString()).contains("porelo project init");
        assertThat(shopsProposal(dir)).isEqualTo(DefaultProject.Proposal.UNASKED);
    }

    @Test
    void yesAsksNothingWritesNothingAndSaysWhatItFound(@TempDir Path dir) throws IOException {
        final Path checkout = Files.createDirectories(dir.resolve("shop")).toRealPath();

        firstStartAt(
                context(dir, true), checkout, true, "y", "--yes", "--attach", "shell", "--detach");

        assertThat(firstStartAsked).isEmpty();
        assertThat(checkout.resolve("project.yml")).doesNotExist();
        assertThat(checkout.resolve("porelo.yml")).doesNotExist();
        assertThat(err.toString()).contains("package.maven", "nothing written", "without --yes");
        assertThat(out.toString()).contains("'default'");
    }

    @Test
    void noTerminalAsksNothingAndWritesNothing(@TempDir Path dir) throws IOException {
        final Path checkout = Files.createDirectories(dir.resolve("shop")).toRealPath();

        firstStartAt(context(dir, true), checkout, false, "y", "--attach", "shell", "--detach");

        assertThat(firstStartAsked).isEmpty();
        assertThat(checkout.resolve("project.yml")).doesNotExist();
        assertThat(checkout.resolve("porelo.yml")).doesNotExist();
        assertThat(err.toString()).contains("package.maven", "nothing written");
    }

    @Test
    void aDryRunShowsWhatItFoundAndWritesNothing(@TempDir Path dir) throws IOException {
        final Path checkout = Files.createDirectories(dir.resolve("shop")).toRealPath();

        firstStartAt(context(dir, true), checkout, true, "y", "--dry-run");

        assertThat(firstStartAsked).isEmpty();
        assertThat(checkout.resolve("project.yml")).doesNotExist();
        assertThat(checkout.resolve("porelo.yml")).doesNotExist();
        assertThat(err.toString()).contains("package.maven", "A dry run writes nothing");
    }

    @Test
    void aProjectNamedAsksNoPlugin(@TempDir Path dir) throws IOException {
        final Path checkout = Files.createDirectories(dir.resolve("shop")).toRealPath();

        firstStartAt(context(dir, true), checkout, true, "y", "-p", "default", "--dry-run");

        assertThat(firstStartDetected).isFalse();
        assertThat(firstStartAsked).isEmpty();
    }

    @Test
    void aStartOnAHostAsksNoPlugin(@TempDir Path dir) throws IOException {
        final Path checkout = Files.createDirectories(dir.resolve("shop")).toRealPath();

        firstStartAt(context(dir, true), checkout, true, "y", "--host", "h1", "--dry-run");

        assertThat(firstStartDetected).isFalse();
        assertThat(firstStartAsked).isEmpty();
        assertThat(checkout.resolve("project.yml")).doesNotExist();
        assertThat(checkout.resolve("porelo.yml")).doesNotExist();
    }

    /**
     * Starts in 'default' on 'shop', a checkout whose origin the organization's 'acme' names as
     * 'storefront'; 'notes' is in 'default' too and named by nobody.
     */
    private PoreloContext startInDefaultNamedByTheOrganization(
            final Path dir, final boolean terminal, final String answer) throws IOException {
        final PoreloContext context = context(dir, true);
        final Path checkout = Files.createDirectories(dir.resolve("shop")).toRealPath();
        PlacedFixture.place(
                context,
                "acme",
                "project:\n  name: acme\n  security_class: guarded\n"
                        + "image:\n  from: ubuntu:24.04\n"
                        + "repositories:\n  storefront:\n    upstream: \""
                        + firstStartOrigin
                        + "\"\n");
        final DefaultProject fallback = new DefaultProject(context);
        try {
            fallback.add(
                    checkout.toString(),
                    "shop",
                    checkout.toString(),
                    java.util.Objects.requireNonNull(firstStartOrigin));
            fallback.add("https://forge.example/someone/notes.git", "notes", "");
        } catch (DefaultProject.Refused ex) {
            throw new IllegalStateException(ex);
        }
        firstStartAt(
                context,
                checkout,
                terminal,
                answer,
                "-p",
                "default",
                "-r",
                "shop",
                "--attach",
                "shell",
                "--detach");
        return context;
    }

    private static List<String> inDefault(final PoreloContext context) {
        return new DefaultProject(context)
                .entries().stream().map(DefaultProject.Entry::name).toList();
    }

    // Harm: a repository the organization's project took keeps its work in 'default', unsaid.
    @Test
    void aStartInDefaultOnARepositoryTheOrganizationNamesSaysSoAndStartsThere(@TempDir Path dir)
            throws IOException {
        final PoreloContext context = startInDefaultNamedByTheOrganization(dir, true, "n");

        assertThat(out.toString())
                .contains("'acme' names this repository as 'storefront'")
                .contains("porelo project default remove shop");
        assertThat(leaveDefaultAsked).hasSize(1);
        assertThat(String.join("\n", runner.lines())).contains("porelo-default_shop");
        assertThat(inDefault(context)).containsExactly("shop", "notes");
    }

    // Harm: the person's yes leaves the repository in 'default', or takes the start out of it.
    @Test
    void aYesAtTheTerminalTakesItOutOfDefaultAndTheStartStaysThere(@TempDir Path dir)
            throws IOException {
        final PoreloContext context = startInDefaultNamedByTheOrganization(dir, true, "y");

        assertThat(String.join("\n", runner.lines())).contains("porelo-default_shop");
        assertThat(inDefault(context)).containsExactly("notes");
    }

    // Harm: a start nobody can answer removes a repository nobody said yes to.
    @Test
    void anUnattendedStartOnlySaysIt(@TempDir Path dir) throws IOException {
        final PoreloContext context = startInDefaultNamedByTheOrganization(dir, false, "y");

        assertThat(out.toString()).contains("porelo project default remove shop");
        assertThat(leaveDefaultAsked).isEmpty();
        assertThat(inDefault(context)).containsExactly("shop", "notes");
    }

    private static final String APPROVED =
            """
            project:
              name: "strangers"
              security_class: "guarded"
            image:
              from: "ubuntu:24.04"
            egress:
              domains:
                - "approved.example"
            repositories:
              shop:
                upstream: "https://forge.example/acme/shop.git"
            """;

    @Test
    void namingAnApprovedProjectReadsTheCopyNotTheCheckout(@TempDir Path dir) throws IOException {
        final Path checkout = Files.createDirectories(dir.resolve("shop")).toRealPath();
        final Path file = Files.writeString(checkout.resolve("project.yml"), APPROVED);
        final PoreloContext context = context(dir, true);
        firstStartAt(context, checkout, true, "y", "--attach", "shell", "--detach");
        Files.writeString(file, APPROVED.replace("\"guarded\"", "\"offline\""));
        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);

        firstStartAt(context, checkout, true, "n", "-p", "strangers", "--dry-run", "-r", "shop");

        assertThat(out.toString()).contains("security class guarded");
        assertThat(out.toString()).doesNotContain("offline");
    }

    @Test
    void aNoToAChangedFileStartsInDefaultAndNotInTheCopy(@TempDir Path dir) throws IOException {
        final Path checkout = Files.createDirectories(dir.resolve("shop")).toRealPath();
        final Path file = Files.writeString(checkout.resolve("project.yml"), APPROVED);
        final PoreloContext context = context(dir, true);
        firstStartAt(context, checkout, true, "y", "--attach", "shell", "--detach");
        Files.writeString(file, APPROVED.replace("approved.example", "edited.example"));
        out.getBuffer().setLength(0);
        firstStartAsked.clear();

        firstStartAt(context, checkout, true, "n", "--attach", "shell", "--detach");

        assertThat(firstStartAsked).hasSize(1);
        assertThat(out.toString()).contains("project        ").contains("'default'");
        assertThat(out.toString()).doesNotContain("'strangers'");
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
        runner.answering("stty -g", "saved-settings\n");

        assertThat(startAt(context, true, "-p", projectFile(dir, MINIMAL)))
                .as("said: %s", err)
                .isZero();

        assertThat(err.toString()).doesNotContain("already running");
        assertThat(execCalls).as("the session it went into").hasSize(1);
        assertThat(String.join(" ", execCalls.get(0))).contains("porelo-uc-shell");
        assertThat(out.toString())
                .as("entering an already running task also restores the terminal")
                .endsWith(TerminalAttachTest.RESETS);
        assertThat(runner.only("stty \"$1\"").arguments())
                .as("the shared attach restores the settings it saved")
                .endsWith("saved-settings");
    }

    @Test
    void aStoppedTaskBroughtBackAtATerminalNamesItsOwnTaskInThePrompt(@TempDir Path dir)
            throws IOException {
        // Cut out of porelo-uc_shell, the prompt named no task: 'uc/'.
        final PoreloContext context = context(dir, true);
        final String id = "a".repeat(64);
        TaskIdentityFixture.record(context.paths(), "porelo-uc_shell", "uc", "shell", id);
        new TaskHelpers(List.of()).writeTo(context.paths().tasks().taskRecord("porelo-uc_shell"));
        runner.answering(
                "ps", "porelo-uc_shell\tExited (0)\t\t\tuc\tguarded\tuc\t\t" + id + "\tshell\n");
        runner.answering("{{.Id}}", id);
        runner.answering("stty -g", "saved-settings\n");

        assertThat(startAt(context, true, "-p", projectFile(dir, MINIMAL)))
                .as("said: %s", err)
                .isZero();

        assertThat(execCalls).as("the session it went into").hasSize(1);
        assertThat(String.join(" ", execCalls.get(0)))
                .as("the prompt names the project and its task")
                .contains("uc/shell");
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
                .contains("porelo-uc_uc")
                .doesNotContain("porelo-uc_shell");
    }

    private PoreloContext twoAgentsOneSignedIn(Path dir) throws IOException {
        final PoreloContext context = previewContext(dir, true);
        AgentChoiceTest.provider(context, "alpha", "spoken");
        AgentChoiceTest.provider(context, "beta", "spoken");
        AgentChoiceTest.installed(context, "first", AgentChoiceTest.NOT_SIGNED_IN);
        AgentChoiceTest.installed(context, "second", AgentChoiceTest.SIGNED_IN_BETA);
        return context;
    }

    /**
     * A person with no organization and nothing to follow still starts an offline project: the file
     * written in its place is the whole registration, so losing this would leave them no way in.
     */
    @Test
    void anOfflineProjectWrittenInItsPlaceStartsByItsName(@TempDir Path dir) throws IOException {

        final PoreloContext context = previewContext(dir);
        final Path file = context.paths().projects().defaultProjectFile("off");
        Files.createDirectories(file.getParent());
        Files.writeString(
                file,
                """
                project:
                  name: "off"
                  security_class: "offline"
                image:
                  from: "ubuntu:24.04"
                """);

        final int code =
                execute(
                        context,
                        "task",
                        "start",
                        "shell",
                        "-p",
                        "off",
                        "--repository",
                        "off",
                        "--dry-run");

        assertThat(code).as(err.toString()).isZero();
        assertThat(out.toString()).contains("security class offline");
    }

    @Test
    void aDryRunNamesTheAgentFoundSignedInAndWhere(@TempDir Path dir) throws IOException {

        final PoreloContext context = twoAgentsOneSignedIn(dir);

        final int code =
                execute(context, "task", "start", "-p", projectFile(dir, MINIMAL), "--dry-run");

        assertThat(code).as(err.toString()).isZero();
        assertThat(out.toString())
                .contains("agent second, provider beta (subscription), found in /home/x/.tool/in");
    }

    @Test
    void aDryRunSettlesTheModelForTheProviderTheAgentWasFoundSignedInTo(@TempDir Path dir)
            throws IOException {

        // Settled for the agent's default provider instead, a dry run would promise a start the
        // start then refuses.
        final PoreloContext context = twoAgentsOneSignedIn(dir);

        final int code =
                execute(
                        context,
                        "task",
                        "start",
                        "-p",
                        projectFile(dir, MINIMAL + "providers:\n  beta:\n    model: \"model-a\"\n"),
                        "--dry-run");

        assertThat(code)
                .as("beta's model, for an agent that takes none, is refused: %s", err)
                .isEqualTo(2);
    }

    @Test
    void aStartWithoutTheCredentialNamesTheImportForTheProviderFound(@TempDir Path dir)
            throws IOException {

        // Named under another provider, the import would store what the task then does not find.
        final PoreloContext context = twoAgentsOneSignedIn(dir);

        final int code =
                execute(
                        context,
                        "task",
                        "start",
                        "-p",
                        projectFile(dir, MINIMAL),
                        "--prompt",
                        "work");

        assertThat(code).as(out + "\n" + err).isEqualTo(69);
        assertThat(out.toString()).contains("agent second, provider beta");
        assertThat(err.toString()).contains("porelo vault import second --provider beta");
    }

    @Test
    void anAgentAPersonPicksIsSaidWithTheProviderFoundForIt(@TempDir Path dir) throws IOException {

        // They picked the agent; the provider its finding brings may not be its default.
        final PoreloContext context = previewContext(dir, true);
        AgentChoiceTest.provider(context, "alpha", "spoken");
        AgentChoiceTest.provider(context, "beta", "spoken");
        AgentChoiceTest.installed(context, "first", AgentChoiceTest.SIGNED_IN_BETA);
        AgentChoiceTest.installed(context, "second", "SIGNED_IN,true,alpha,API_KEY,/home/x/a");
        projectFile(dir, MINIMAL);
        final PrintWriter said = new PrintWriter(err, true);

        new TaskLaunch(
                        context,
                        new TaskLaunch.Request(
                                "t",
                                dir.resolve("project.yml"),
                                null,
                                null,
                                null,
                                8,
                                null,
                                false,
                                false,
                                "prompt",
                                true,
                                org.fuin.porelo.wire.TaskMode.AGENT,
                                "work",
                                null,
                                null,
                                60,
                                "uc",
                                java.util.Map.of(),
                                java.util.Map.of(),
                                null,
                                null),
                        new StartSteps(step -> {}, said))
                .offering(new Offer(question -> "1", false, false, said))
                .launch(new PrintWriter(out, true), said, running -> 0);

        assertThat(out.toString()).as(err.toString()).contains("agent first, provider beta");
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
     * judged clone of a placed project, then where a task last read one. A fixture that only wrote
     * a file into a temporary directory would be a project this machine has never heard of - which
     * is exactly what the refusal is for, and not what these tests are about.
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
                                dir.resolve("state/porelo/tasks/porelo-uc_shell")
                                        .resolve(org.fuin.porelo.wire.TaskProfile.FILE)))
                .contains("\"forgeBranch\":\"bugfix/porelo/shell\"");

        runner.answering(
                "ps", "porelo-uc_shell\tUp 4 minutes\t1700000000\t1700000100\tuc\tguarded\tuc\t\n");
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
        assertThat(err.toString()).contains("porelo-uc_shell's already");
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
                                        context.paths().tasks().containerState("porelo-uc_shell"))
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
    void aTaskStartedByAnotherRecordsItsParentAndTheLogSaysWhoStartedIt(@TempDir Path dir)
            throws IOException {

        // A start through a task's MCP endpoint names that task: its stop takes only its own, and
        // the event log shows who started what.
        final PoreloContext context = countingContext(dir);
        projectFile(dir, MINIMAL);
        final PrintWriter said = new PrintWriter(err, true);

        final int code =
                new TaskLaunch(
                                context,
                                new TaskLaunch.Request(
                                        "shell",
                                        dir.resolve("project.yml"),
                                        "preview",
                                        null,
                                        null,
                                        8,
                                        null,
                                        true,
                                        false,
                                        "prompt",
                                        true,
                                        org.fuin.porelo.wire.TaskMode.SHELL,
                                        null,
                                        null,
                                        null,
                                        60,
                                        null,
                                        java.util.Map.of(),
                                        java.util.Map.of(),
                                        null,
                                        null,
                                        null,
                                        "porelo-uc-lead"),
                                new StartSteps(step -> {}, said))
                        .launch(new PrintWriter(out, true), said, running -> 0);

        assertThat(code).as("started: %s", err).isZero();
        assertThat(
                        org.fuin.porelo.wire.TaskProfile.readFrom(
                                        context.paths().tasks().containerState("porelo-uc_shell"))
                                .parent())
                .as("its record names the task that started it")
                .isEqualTo("porelo-uc-lead");
        assertThat(EventLog.of(context).entries(null))
                .as("the log says who started it")
                .anySatisfy(
                        event -> {
                            assertThat(event.get("kind")).isEqualTo(EventLog.STARTED);
                            assertThat(event.get("task")).isEqualTo("porelo-uc_shell");
                            assertThat(event.get("project")).isEqualTo("uc");
                            assertThat(String.valueOf(event.get("detail")))
                                    .contains("porelo-uc-lead");
                        });
    }

    @Test
    void aStartGivenNoNameRecordsNoneSoItIsShownByItsShortName(@TempDir Path dir)
            throws IOException {
        // A default recorded as a person's name would show every task as chosen by somebody.
        final PoreloContext context = countingContext(dir);
        final CommandLine cmd = PoreloCli.commandLine(context);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));

        // Not through execute, which names every start 'shell'.
        final int started =
                cmd.execute(
                        "task",
                        "start",
                        "-p",
                        projectFile(dir, MINIMAL),
                        "--repository",
                        "uc",
                        "--agent",
                        "preview",
                        "--no-gate");

        assertThat(started).as("started: %s", err).isZero();
        assertThat(
                        org.fuin.porelo.wire.TaskProfile.readFrom(
                                        context.paths().tasks().containerState("porelo-uc_uc"))
                                .named())
                .as("named after its repository by nobody, so its record names nothing")
                .isNull();
    }

    @Test
    void aStartGivenANameRecordsItAsThePersonGaveIt(@TempDir Path dir) throws IOException {
        // The name a person typed is what every client shows; lost, the row falls back to another.
        final PoreloContext context = countingContext(dir);

        final int started =
                execute(
                        context,
                        "task",
                        "start",
                        "fix",
                        "-p",
                        projectFile(dir, MINIMAL),
                        "--agent",
                        "preview",
                        "--no-gate");

        assertThat(started).as("started: %s", err).isZero();
        assertThat(
                        org.fuin.porelo.wire.TaskProfile.readFrom(
                                        context.paths().tasks().containerState("porelo-uc_fix"))
                                .named())
                .as("its record keeps the name the person gave")
                .isEqualTo("fix");
    }

    @Test
    void aTaskAPersonStartsRecordsNoParentAndNoStartedEvent(@TempDir Path dir) throws IOException {
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
                        "--no-gate");

        assertThat(started).as("started: %s", err).isZero();
        assertThat(
                        org.fuin.porelo.wire.TaskProfile.readFrom(
                                        context.paths().tasks().containerState("porelo-uc_shell"))
                                .parent())
                .isNull();
        assertThat(EventLog.of(context).entries(null))
                .noneSatisfy(event -> assertThat(event.get("kind")).isEqualTo(EventLog.STARTED));
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
        anEarlierAttemptOfTheTask(context);
        final Path state =
                Files.createDirectories(context.paths().tasks().containerState("porelo-uc_shell"));
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
                    .contains("kept      porelo-uc_shell - it failed");
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
                .contains("discard with 'porelo task remove porelo-uc_shell'");
        runner.answering("{{.State.Pid}}", "0");
        runner.answering("{{.State.Status}}", "exited");

        assertThat(new TaskControl(context).remove("porelo-uc_shell", false, false).outcome())
                .as("it is removed as it was named, held nothing")
                .isNotEqualTo(TaskControl.Outcome.NOTHING_KNOWS);
    }

    @Test
    void aStartThatFailedAfterItsStateWasMadeStillListsItsTaskWithTheCallersKey(@TempDir Path dir)
            throws IOException {

        // The caller that started it may die before it keeps what it started: listed again, the
        // task carries the key it was given, failed or not, and so does the copy a reboot reads.
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
                        "--no-gate",
                        "--caller-key",
                        "offer-17");

        assertThat(started).as("failed: %s", err).isEqualTo(70);
        runner.answering(
                "ps",
                "porelo-uc_shell\tExited (0) 1 minute ago\t1788500000\t0\tuc\tguarded\tuc\t\t\n");
        final TaskInventory inventory = new TaskInventory(context, null);
        assertThat(inventory.rows(inventory.tasks()))
                .singleElement()
                .satisfies(row -> assertThat(row).containsEntry("callerKey", "offer-17"));
        assertThat(
                        (java.util.Map<?, ?>)
                                org.fuin.porelo.wire.Json.parse(
                                        Files.readString(
                                                context.paths()
                                                        .tasks()
                                                        .taskRecord("porelo-uc_shell")
                                                        .resolve(
                                                                org.fuin.porelo.wire.TaskProfile
                                                                        .FILE))))
                .as("saved where a reboot does not reach")
                .extracting(document -> document.get("callerKey"))
                .isEqualTo("offer-17");
    }

    @Test
    void aTaskKeptStoppedWhenItsContainerNeverRanCarriesTheCallersKeyAfterAReboot(@TempDir Path dir)
            throws IOException {

        // Made, but podman could not start it: the task is kept, stopped, and a caller that comes
        // back after the machine restarted must still find it by its key, or it starts another.
        final PoreloContext context = countingContext(dir);
        runner.failing("podman start ", 125, "Error: unable to start container: crun failed");

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
                        "--caller-key",
                        "offer-17");

        assertThat(started).as("failed: %s", err).isNotZero();
        assertThat(out.toString()).as("kept: %s", out).contains("kept      porelo-uc_shell");
        // The reboot empties the runtime directory.
        try (java.util.stream.Stream<Path> paths =
                Files.walk(context.paths().tasks().containerState("porelo-uc_shell"))) {
            for (final Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
        runner.answering("ps", "porelo-uc_shell\tCreated\t1788500000\t0\tuc\tguarded\tuc\t\t\n");
        final TaskInventory inventory = new TaskInventory(context, null);
        assertThat(inventory.rows(inventory.tasks()))
                .singleElement()
                .satisfies(row -> assertThat(row).containsEntry("callerKey", "offer-17"));
    }

    @ParameterizedTest(name = "{0} characters")
    @ValueSource(ints = {201, 3})
    void aCallerKeyTooLongOrWithAControlCharacterIsRefusedBeforeAnythingIsMade(
            int length, @TempDir Path dir) throws IOException {

        // 3 stands for a short key with a line break in it, which no listing can show as one line.
        final PoreloContext context = countingContext(dir);
        final String key = length == 3 ? "a\nb" : "k".repeat(length);

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
                        "--caller-key",
                        key);

        assertThat(started).as("refused: %s", err).isEqualTo(64);
        assertThat(err.toString()).contains("--caller-key");
        assertThat(runner.lines())
                .as("nothing was made")
                .noneMatch(line -> line.contains("podman create"));
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

        // Existing state without a proven owner prevents reservation. This attempt cannot stop
        // the container or helper that another attempt may own.
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
            assertThat(helper.waitFor(100, java.util.concurrent.TimeUnit.MILLISECONDS))
                    .as("a refusal before reservation cannot stop an unowned helper: %s", err)
                    .isFalse();
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
        final Path helpers = helperStandIns(dir);
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
            HelperStandIn.stop(helpers.getParent());
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
        final Path helpers = helperStandIns(dir);
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
            // As podman lists it: the id the start recorded and the task's label.
            final String recorded =
                    org.fuin.porelo.wire.Sidecar.recordedId(
                            context.paths().tasks().containerState("porelo-uc_shell"));
            assertThat(recorded).as("the kept start recorded its container's id").isNotNull();
            runner.answering("{{.State.Status}}", "exited");
            runner.answering("{{.Id}}", recorded);
            runner.answering(
                    "ps",
                    RUNNING_SHELL
                            .replace("porelo-uc-shell", "porelo-uc_shell")
                            .replace("Up 4 minutes", "Exited (0) 4 minutes ago")
                            .replace("\t\n", "\t\t" + recorded + "\tshell\n"));

            assertThat(new TaskControl(context).resume("porelo-uc_shell").outcome())
                    .as("it comes back with what it had: %s", err)
                    .isNotEqualTo(TaskControl.Outcome.NO_HELPERS_RECORDED)
                    .isNotEqualTo(TaskControl.Outcome.NO_CONTAINER);
        } finally {
            System.setProperty(org.fuin.porelo.runtime.Podman.NAMED, standIn);
            System.setProperty(PoreloBinary.NAMED, ownPorelo);
            HelperStandIn.stop(helpers.getParent());
        }
    }

    @Test
    void aStartWhoseGateNeverRecordsItsPidFailsAndLeavesNoHelperRunning(@TempDir Path dir)
            throws IOException {

        // Answered 0, such a start left a task with no gate, and the gate it had started was named
        // by no pid file, so nothing that cleans up ever found it.
        final PoreloContext context = countingContext(dir);
        final Path directory = Files.createDirectories(dir.resolve("helpers"));
        final Path helpers = directory.resolve("porelo");
        Files.writeString(
                helpers,
                "#!/bin/sh\n"
                        + OwnPorelo.watcher(HelperStandIn.registry(directory), true)
                        + "if [ \"$1\" = gate ] && [ \"$2\" = serve ]; then\n"
                        + HelperStandIn.lines(HelperStandIn.registry(directory), false)
                        + "fi\n"
                        + HelperStandIn.lines(HelperStandIn.registry(directory), true));
        Files.setPosixFilePermissions(
                helpers, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        final String ownPorelo = System.getProperty(PoreloBinary.NAMED);
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

            assertThat(started)
                    .as("a gate that never came up fails the start: %s", err)
                    .isEqualTo(70);
            assertThat(HelperStandIn.started(directory))
                    .as("its gate was started: %s", out)
                    .isPositive();
            assertThat(HelperStandIn.anyRunning(directory))
                    .as("and nothing the start made is left running")
                    .isFalse();
        } finally {
            System.setProperty(PoreloBinary.NAMED, ownPorelo);
            HelperStandIn.stop(directory);
        }
    }

    @Test
    void aStartThatLosesTheNameToAnotherStartStopsNothingOfThatOne(@TempDir Path dir)
            throws IOException {

        // Two starts of one name: the one whose create was refused for the taken name cleaned up by
        // the record on disk, which by then was the other's, and stopped the other's task.
        final PoreloContext context = context(dir, true);
        final String other = "f".repeat(64);
        final java.util.concurrent.atomic.AtomicReference<String> named =
                new java.util.concurrent.atomic.AtomicReference<>();
        ((StoppingCommandRunner) runner)
                .beforeCreate(
                        command -> {
                            final String container =
                                    command.arguments()
                                            .get(command.arguments().indexOf("--name") + 1);
                            named.set(container);
                            try {
                                // What the other start recorded, the moment before this create.
                                org.fuin.porelo.wire.Sidecar.recordId(
                                        context.paths().tasks().containerState(container), other);
                                TaskIdentityFixture.record(
                                        context.paths(), container, "uc", "shell", other);
                            } catch (IOException ex) {
                                throw new java.io.UncheckedIOException(ex);
                            }
                            runner.answering(
                                    "ps",
                                    container
                                            + "\tUp 1 second\t\t\tuc\tguarded\tuc\t\t"
                                            + other
                                            + "\tshell\n");
                        });
        runner.failing(
                " create ",
                125,
                "Error: creating container storage: the container name is already in use");

        final int code =
                execute(
                        context,
                        "task",
                        "start",
                        "--attach",
                        "shell",
                        "-p",
                        projectFile(dir, MINIMAL));

        assertThat(code).as("the second start fails: %s", err).isNotZero();
        assertThat(named.get()).as("it reached its create: %s", err).isNotNull();
        assertThat(runner.lines())
                .as("nothing is stopped or removed of the other start's task")
                .noneMatch(
                        line ->
                                (line.startsWith("podman stop") || line.startsWith("podman rm"))
                                        && line.contains(other));
        assertThat(context.recordedId(named.get()))
                .as("whose record stays as that start wrote it")
                .isEqualTo(other);
    }

    @Test
    void aFailedStartForgetsTheNamesRecordOfAContainerThatIsGone(@TempDir Path dir)
            throws IOException {

        // A start that made nothing left the name's record of a container podman no longer
        // knows: it touched nothing, as for a live one, and the record named a task that was
        // gone.
        final PoreloContext context = context(dir, true);
        final String gone = "e".repeat(64);
        final java.util.concurrent.atomic.AtomicReference<String> named =
                new java.util.concurrent.atomic.AtomicReference<>();
        ((StoppingCommandRunner) runner)
                .beforeCreate(
                        command -> {
                            final String container =
                                    command.arguments()
                                            .get(command.arguments().indexOf("--name") + 1);
                            named.set(container);
                            try {
                                // A record of a container that is gone, the moment before this
                                // create.
                                org.fuin.porelo.wire.Sidecar.recordId(
                                        context.paths().tasks().containerState(container), gone);
                                TaskIdentityFixture.record(
                                        context.paths(), container, "uc", "shell", gone);
                            } catch (IOException ex) {
                                throw new java.io.UncheckedIOException(ex);
                            }
                        });
        runner.failing(
                " create ",
                125,
                "Error: creating container storage: the container name is already in use");

        final int code =
                execute(
                        context,
                        "task",
                        "start",
                        "--attach",
                        "shell",
                        "-p",
                        projectFile(dir, MINIMAL));

        assertThat(code).as("the start fails: %s", err).isNotZero();
        assertThat(named.get()).as("it reached its create: %s", err).isNotNull();
        assertThat(context.recordedId(named.get()))
                .as("the record of the container that is gone is forgotten: %s", err)
                .isNull();
    }

    @Test
    void aStartWhoseBrokerNeverGivesItsTokenFailsAndLeavesItNotRunning(@TempDir Path dir)
            throws IOException {

        // Taken for "nothing brokered", a broker that never wrote its token let the start go on and
        // answer 0 for an agent with no credential, while the broker it started ran on.
        final PoreloContext context = brokeredContext(dir);
        final Path helpers = Files.createDirectories(dir.resolve("helpers"));
        final String ownPorelo = System.getProperty(PoreloBinary.NAMED);
        System.setProperty(PoreloBinary.NAMED, broker(helpers, false, ownPorelo).toString());
        try {
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

            assertThat(started)
                    .as("a broker that never came up fails the start: %s", err)
                    .isEqualTo(70);
            assertThat(err.toString()).contains("the credential proxy did not come up");
            assertThat(HelperStandIn.started(helpers)).as("its broker was started").isEqualTo(1);
            assertThat(HelperStandIn.anyRunning(helpers))
                    .as("and is not left running: %s", out)
                    .isFalse();
        } finally {
            System.setProperty(PoreloBinary.NAMED, ownPorelo);
            HelperStandIn.stop(helpers);
            PoreloContext.forgetAsked();
            // What the start's opening cached, as the account's keyring would have kept it.
            if (org.fuin.porelo.vault.KernelKeyring.available()) {
                new org.fuin.porelo.vault.KernelKeyring(context.paths().vault().vaultKeyringKey())
                        .forget();
            }
        }
    }

    @Test
    void aStartWhoseBrokerNeverRecordsItsPidFailsAndLeavesItNotRunning(@TempDir Path dir)
            throws IOException {

        // Taken as up once its token was there, a broker that never recorded its pid let the start
        // answer 0, and nothing that cleans up by the pid files ever found it.
        final PoreloContext context = brokeredContext(dir);
        final Path helpers = Files.createDirectories(dir.resolve("helpers"));
        final String ownPorelo = System.getProperty(PoreloBinary.NAMED);
        System.setProperty(
                PoreloBinary.NAMED, broker(helpers, true, ownPorelo, false, false).toString());
        try {
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

            assertThat(started)
                    .as("a broker that never recorded its pid fails the start: %s", err)
                    .isEqualTo(70);
            assertThat(err.toString()).contains("the credential proxy did not come up");
            assertThat(HelperStandIn.started(helpers)).as("its broker was started").isEqualTo(1);
            assertThat(HelperStandIn.anyRunning(helpers))
                    .as("and is not left running: %s", out)
                    .isFalse();
        } finally {
            System.setProperty(PoreloBinary.NAMED, ownPorelo);
            HelperStandIn.stop(helpers);
            PoreloContext.forgetAsked();
            // What the start's opening cached, as the account's keyring would have kept it.
            if (org.fuin.porelo.vault.KernelKeyring.available()) {
                new org.fuin.porelo.vault.KernelKeyring(context.paths().vault().vaultKeyringKey())
                        .forget();
            }
        }
    }

    @Test
    void aStartWhoseRepositoryBrokerNeverRecordsItsPidFailsAndLeavesItNotRunning(@TempDir Path dir)
            throws IOException {

        // Taken as up once its socket was there, and said and gone on without when it was not, a
        // repository broker that never recorded its pid let the start answer 0, and nothing that
        // cleans up by the pid files ever found it.
        final PoreloContext context = brokeredContext(dir);
        context.vault()
                .write(
                        java.util.Map.of(
                                "preview",
                                new org.fuin.porelo.vault.VaultEntry("test-key", "api-key"),
                                "corp-nexus",
                                new org.fuin.porelo.vault.VaultEntry("test-secret", "api-key")),
                        "test-passphrase".toCharArray());
        final Path helpers = Files.createDirectories(dir.resolve("helpers"));
        final String ownPorelo = System.getProperty(PoreloBinary.NAMED);
        System.setProperty(PoreloBinary.NAMED, repositoryBroker(helpers, ownPorelo).toString());
        installedAdapter(context, "maven");
        final EcosystemDetector adapters =
                new EcosystemDetector() {
                    @Override
                    public org.fuin.porelo.ecosystem.api.Detection detect(
                            final org.fuin.porelo.ecosystem.api.InstalledEcosystems.Description
                                    plugin,
                            final Path checkout,
                            final Path home) {
                        throw new AssertionError("a start with a project file detects nothing");
                    }

                    @Override
                    public List<org.fuin.porelo.ecosystem.api.File> configure(
                            final org.fuin.porelo.ecosystem.api.InstalledEcosystems.Description
                                    plugin,
                            final List<org.fuin.porelo.ecosystem.api.Repository> repositories,
                            final org.fuin.porelo.ecosystem.api.Extension extension) {
                        return List.of();
                    }
                };
        try {
            projectFile(
                    dir,
                    MINIMAL
                            + """
                            tool_repositories:
                              - ecosystem: maven
                                name: corp
                                url: "https://repo.example.org/maven/"
                                credential: corp-nexus
                            """);
            final int started =
                    startWithAdapters(
                            context, adapters, "-p", "uc", "--agent", "preview", "--no-gate");

            assertThat(started)
                    .as("a repository broker that never recorded its pid fails the start: %s", err)
                    .isEqualTo(70);
            assertThat(err.toString()).contains("the repository broker did not come up");
            assertThat(HelperStandIn.started(helpers))
                    .as("the credential broker and the repository broker were started")
                    .isEqualTo(2);
            assertThat(HelperStandIn.anyRunning(helpers))
                    .as("and neither is left running: %s", out)
                    .isFalse();
        } finally {
            System.setProperty(PoreloBinary.NAMED, ownPorelo);
            HelperStandIn.stop(helpers);
            PoreloContext.forgetAsked();
            // What the start's opening cached, as the account's keyring would have kept it.
            if (org.fuin.porelo.vault.KernelKeyring.available()) {
                new org.fuin.porelo.vault.KernelKeyring(context.paths().vault().vaultKeyringKey())
                        .forget();
            }
        }
    }

    /**
     * A porelo whose {@code vault serve} is a stand-in that comes up, as {@link #broker(Path,
     * boolean, String)} makes it, and whose {@code vault repositories} binds its socket and never
     * records its pid; everything else is this build's.
     */
    private static Path repositoryBroker(Path helpers, String ownPorelo) throws IOException {
        final Path program = helpers.resolve("repository-porelo");
        Files.writeString(
                program,
                """
                #!/bin/sh
                if [ "$1" = vault ] && [ "$2" = serve ]; then
                  previous=
                  for argument; do
                    case "$previous" in
                      --socket) : > "$argument" ;;
                      --token-file) echo stand-in-token > "$argument" ;;
                    esac
                    previous=$argument
                  done
                %1$s
                fi
                if [ "$1" = vault ] && [ "$2" = repositories ]; then
                  previous=
                  for argument; do
                    [ "$previous" = --socket ] && : > "$argument"
                    previous=$argument
                  done
                %2$s
                fi
                exec %3$s "$@"
                """
                        .formatted(
                                HelperStandIn.lines(HelperStandIn.registry(helpers), true),
                                HelperStandIn.lines(HelperStandIn.registry(helpers), false),
                                shellArgument(ownPorelo)));
        Files.setPosixFilePermissions(
                program, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        return program;
    }

    @Test
    void aStartWhoseWatcherNeverRecordsItsPidFailsAndLeavesNoHelperRunning(@TempDir Path dir)
            throws IOException {

        // Taken as up once it listened, a watcher that never recorded its pid let the start answer
        // 0, and nothing that cleans up by the pid files ever found it.
        final PoreloContext context = countingContext(dir);
        final Path directory = Files.createDirectories(dir.resolve("helpers"));
        final Path helpers = directory.resolve("porelo");
        // Every other helper comes up; the watcher listens and never records its pid.
        Files.writeString(
                helpers,
                "#!/bin/sh\n"
                        + OwnPorelo.watcher(HelperStandIn.registry(directory), false)
                        + HelperStandIn.lines(HelperStandIn.registry(directory), true));
        Files.setPosixFilePermissions(
                helpers, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        runner.answering("{{.State.Pid}}", "4242");
        final String ownPorelo = System.getProperty(PoreloBinary.NAMED);
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
                            "preview",
                            "--clearance",
                            "prompt");

            assertThat(started)
                    .as("a watcher that never recorded its pid fails the start: %s", err)
                    .isEqualTo(70);
            assertThat(err.toString()).contains("the clearance watcher did not come up");
            assertThat(HelperStandIn.started(directory))
                    .as("its helpers were started: %s", out)
                    .isGreaterThanOrEqualTo(2);
            assertThat(HelperStandIn.anyRunning(directory))
                    .as("and nothing the start made is left running")
                    .isFalse();
        } finally {
            System.setProperty(PoreloBinary.NAMED, ownPorelo);
            HelperStandIn.stop(directory);
        }
    }

    @Test
    void aStartWhoseWatcherNeverListensFailsAndLeavesNoHelperRunning(@TempDir Path dir)
            throws IOException {

        // Only warned about, a watcher that never listened left the start answering 0 with no one
        // to ask about a blocked connection, and the watcher running.
        final PoreloContext context = countingContext(dir);
        final Path directory = Files.createDirectories(dir.resolve("helpers"));
        final Path helpers = directory.resolve("porelo");
        // Every helper comes up, recording its pid; the watcher never makes its socket.
        Files.writeString(
                helpers,
                "#!/bin/sh\n" + HelperStandIn.lines(HelperStandIn.registry(directory), true));
        Files.setPosixFilePermissions(
                helpers, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        runner.answering("{{.State.Pid}}", "4242");
        final String ownPorelo = System.getProperty(PoreloBinary.NAMED);
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
                            "preview",
                            "--clearance",
                            "prompt");

            assertThat(started)
                    .as("a watcher that never listened fails the start: %s", err)
                    .isEqualTo(70);
            assertThat(err.toString()).contains("the clearance watcher");
            assertThat(HelperStandIn.started(directory))
                    .as("its helpers were started: %s", out)
                    .isGreaterThanOrEqualTo(2);
            assertThat(HelperStandIn.anyRunning(directory))
                    .as("and nothing the start made is left running")
                    .isFalse();
        } finally {
            System.setProperty(PoreloBinary.NAMED, ownPorelo);
            HelperStandIn.stop(directory);
        }
    }

    @Test
    void aStartThatLosesTheNameEndsTheBrokerItStartedAndNoHelperOfTheOther(@TempDir Path dir)
            throws Exception {

        // Returned at once, the start that lost the name left the broker it had started before its
        // create running; swept by the pid files instead, it would end the winner's helpers, which
        // write theirs in the same directory.
        final PoreloContext context = brokeredContext(dir);
        final Path helpers = Files.createDirectories(dir.resolve("helpers"));
        final String ownPorelo = System.getProperty(PoreloBinary.NAMED);
        System.setProperty(PoreloBinary.NAMED, broker(helpers, true, ownPorelo).toString());
        final String other = "f".repeat(64);
        final Process theirs = new ProcessBuilder("sleep", "60").start();
        ((StoppingCommandRunner) runner)
                .beforeCreate(
                        command -> {
                            final String container =
                                    command.arguments()
                                            .get(command.arguments().indexOf("--name") + 1);
                            try {
                                final Path state =
                                        context.paths().tasks().containerState(container);
                                org.fuin.porelo.wire.Sidecar.recordId(state, other);
                                TaskIdentityFixture.record(
                                        context.paths(), container, "uc", "shell", other);
                                // The other start's gate, recorded where every start of the name
                                // records its helpers.
                                org.fuin.porelo.wire.HelperPid.record(
                                        state.resolve("gate.pid"), theirs.toHandle());
                            } catch (IOException ex) {
                                throw new java.io.UncheckedIOException(ex);
                            }
                            runner.answering(
                                    "ps",
                                    container
                                            + "\tUp 1 second\t\t\tuc\tguarded\tuc\t\t"
                                            + other
                                            + "\tshell\n");
                        });
        runner.failing(
                " create ",
                125,
                "Error: creating container storage: the container name is already in use");
        try {
            final int code =
                    execute(
                            context,
                            "task",
                            "start",
                            "-p",
                            projectFile(dir, MINIMAL),
                            "--agent",
                            "preview",
                            "--no-gate");

            assertThat(code).as("the second start fails: %s", err).isNotZero();
            assertThat(HelperStandIn.started(helpers))
                    .as("it had started its broker: %s", err)
                    .isEqualTo(1);
            assertThat(HelperStandIn.anyRunning(helpers)).as("which it ends, as its own").isFalse();
            assertThat(theirs.isAlive()).as("and the other start's gate runs on").isTrue();
        } finally {
            theirs.destroyForcibly();
            System.setProperty(PoreloBinary.NAMED, ownPorelo);
            HelperStandIn.stop(helpers);
            PoreloContext.forgetAsked();
            // What the start's opening cached, as the account's keyring would have kept it.
            if (org.fuin.porelo.vault.KernelKeyring.available()) {
                new org.fuin.porelo.vault.KernelKeyring(context.paths().vault().vaultKeyringKey())
                        .forget();
            }
        }
    }

    @Test
    void anUnnamedStartBesideAProjectNameThatLeavesNoRoomSaysTheProjectsNameIsTooLong(
            @TempDir Path dir) throws Exception {

        // Written by hand, a project's name of 56 characters leaves no task any room: even the
        // default name does not fit, and the start says what has to change instead of choosing.
        final String long56 = "p".repeat(56);
        final String project =
                projectFile(dir, MINIMAL.replace("name: \"uc\"", "name: \"" + long56 + "\""));

        final int code =
                execute(
                        context(dir, true),
                        "task",
                        "start",
                        "-p",
                        project,
                        "-r",
                        long56,
                        "--dry-run");

        assertThat(code).as("%s", err).isEqualTo(64);
        assertThat(err.toString())
                .as("the project's name is what must change")
                .contains("the project's name leaves no room for any task")
                .contains("project.yml");
        assertThat(runner.invocations())
                .as("nothing made")
                .noneMatch(command -> command.arguments().contains("create"));
    }

    @Test
    void aStartWrongTwiceAnswersOneCodeWhicheverCheckComesFirst(@TempDir Path dir)
            throws Exception {

        // An unknown -r exited 64 and a --set the project cannot read exited 2, so a start wrong
        // in both was answered by whichever check came first. Both are what was typed.
        final String project = projectFile(dir, WITH_PARAMETERS);

        final int setAlone =
                execute(context(dir, true), "task", "start", "-p", project, "--set", "nope");
        final String setSaid = err.toString();
        err.getBuffer().setLength(0);
        final int repositoryAlone =
                execute(context(dir, true), "task", "start", "-p", project, "-r", "absent");
        err.getBuffer().setLength(0);
        final int both =
                execute(
                        context(dir, true),
                        "task",
                        "start",
                        "-p",
                        project,
                        "-r",
                        "absent",
                        "--set",
                        "nope");

        assertThat(setSaid).contains("--set nope");
        assertThat(List.of(setAlone, repositoryAlone, both))
                .as("one code for what was typed wrong: %s", setSaid)
                .containsOnly(64);
        assertThat(runner.invocations())
                .as("both refuse before anything is made")
                .noneMatch(command -> command.arguments().contains("create"));
    }

    @Test
    void aStartWhoseFirstIdRecordFailsRemovesTheContainerItMadeAndEndsItsBroker(@TempDir Path dir)
            throws Exception {

        // Rolled back through the stop of a recorded task, a start whose first id record failed
        // was refused for the record it had not written: its container stayed, recorded nowhere,
        // and the broker it had started ran on.
        final PoreloContext context = brokeredContext(dir);
        final Path helpers = Files.createDirectories(dir.resolve("helpers"));
        final String ownPorelo = System.getProperty(PoreloBinary.NAMED);
        System.setProperty(PoreloBinary.NAMED, broker(helpers, true, ownPorelo).toString());
        ((StoppingCommandRunner) runner)
                .beforeCreate(
                        command -> {
                            final String container =
                                    command.arguments()
                                            .get(command.arguments().indexOf("--name") + 1);
                            try {
                                // Where the id goes, a directory with something in it: as a full
                                // filesystem, the first record fails.
                                Files.createDirectories(
                                        context.paths()
                                                .tasks()
                                                .containerState(container)
                                                .resolve(org.fuin.porelo.wire.Sidecar.RECORDED_ID)
                                                .resolve("taken"));
                            } catch (IOException ex) {
                                throw new java.io.UncheckedIOException(ex);
                            }
                        });
        try {
            final int code =
                    execute(
                            context,
                            "task",
                            "start",
                            "-p",
                            projectFile(dir, MINIMAL),
                            "--agent",
                            "preview",
                            "--no-gate");

            assertThat(code).as("the start fails: %s", err).isNotZero();
            assertThat(HelperStandIn.started(helpers))
                    .as("it had started its broker: %s", err)
                    .isEqualTo(1);
            assertThat(runner.lines())
                    .as("the container it made is removed by the id its create gave back")
                    .anyMatch(
                            line ->
                                    line.startsWith("podman rm")
                                            && line.contains("0123456789abcdef"));
            assertThat(HelperStandIn.anyRunning(helpers))
                    .as("and its broker is not left running: %s", err)
                    .isFalse();
        } finally {
            System.setProperty(PoreloBinary.NAMED, ownPorelo);
            HelperStandIn.stop(helpers);
            PoreloContext.forgetAsked();
            if (org.fuin.porelo.vault.KernelKeyring.available()) {
                new org.fuin.porelo.vault.KernelKeyring(context.paths().vault().vaultKeyringKey())
                        .forget();
            }
        }
    }

    @Test
    void aStartThatLosesTheNameToAMadeTaskTakesNoneOfItsBrokerFiles(@TempDir Path dir)
            throws Exception {

        // Found nothing to bring back while the other start was still making the task, a start
        // took that start's finished claim as its own attempt and went on to its broker: it
        // deleted the other's token and started a broker over its pid file and its socket, in the
        // other's state directory, before its own create failed on the taken name.
        final PoreloContext context = brokeredContext(dir);
        final Path helpers = Files.createDirectories(dir.resolve("helpers"));
        final String ownPorelo = System.getProperty(PoreloBinary.NAMED);
        System.setProperty(PoreloBinary.NAMED, broker(helpers, true, ownPorelo).toString());
        projectFile(dir, MINIMAL);
        final Path projectFile = dir.resolve("project.yml");
        final Process theirs = new ProcessBuilder("sleep", "60").start();
        try {
            assertThat(context.openerAsking(false, new PrintWriter(err)))
                    .as("the fixture opens only its own vault")
                    .isPresent();
            final TaskLaunch losing =
                    new TaskLaunch(
                            context,
                            new TaskLaunch.Request(
                                    "shell",
                                    projectFile,
                                    "preview",
                                    null,
                                    null,
                                    8,
                                    null,
                                    true,
                                    false,
                                    "deny",
                                    false,
                                    org.fuin.porelo.wire.TaskMode.AGENT,
                                    null,
                                    null,
                                    null,
                                    60,
                                    "uc"));
            assertThat(losing.startExisting(new PrintWriter(out, true), new PrintWriter(err, true)))
                    .as("nothing to bring back yet: %s", err)
                    .isNull();

            // The other start, finished meanwhile: its claim made, its container up, its broker's
            // files where every start of the name writes them.
            final String container =
                    context.tasks()
                            .containerName(
                                    org.fuin.porelo.core.project.ProjectReader.read(projectFile),
                                    "shell");
            final String other = "f".repeat(64);
            try (var made = new TaskReservations(context).start("uc", "shell", container)) {
                TaskIdentityFixture.running(context.paths(), container, "uc", "shell", other);
                made.created(other);
            }
            final Path state = context.paths().tasks().containerState(container);
            org.fuin.porelo.wire.HelperPid.record(state.resolve("vault.pid"), theirs.toHandle());
            Files.writeString(state.resolve("vault.token"), "theirs\n");
            Files.writeString(state.resolve("vault.sock"), "theirs\n");
            final java.util.Map<String, String> written = new java.util.TreeMap<>();
            for (String file : List.of("vault.pid", "vault.token", "vault.sock")) {
                written.put(file, Files.readString(state.resolve(file)));
            }
            runner.answering(
                    "ps",
                    container + "\tUp 1 second\t\t\tuc\tguarded\tuc\t\t" + other + "\tshell\n");

            final int code =
                    losing.launch(
                            new PrintWriter(out, true), new PrintWriter(err, true), running -> 0);

            assertThat(code).as("the losing start fails: %s", err).isNotZero();
            final java.util.Map<String, String> after = new java.util.TreeMap<>();
            for (String file : written.keySet()) {
                after.put(
                        file,
                        Files.exists(state.resolve(file))
                                ? Files.readString(state.resolve(file))
                                : "(gone)");
            }
            assertThat(after)
                    .as("the other start's broker files stay as it wrote them: %s", out)
                    .isEqualTo(written);
            assertThat(HelperStandIn.started(helpers))
                    .as("and the losing start started no broker of its own there")
                    .isZero();
            assertThat(theirs.isAlive()).as("the other start's broker runs on").isTrue();
        } finally {
            theirs.destroyForcibly();
            System.setProperty(PoreloBinary.NAMED, ownPorelo);
            HelperStandIn.stop(helpers);
            PoreloContext.forgetAsked();
            if (org.fuin.porelo.vault.KernelKeyring.available()) {
                new org.fuin.porelo.vault.KernelKeyring(context.paths().vault().vaultKeyringKey())
                        .forget();
            }
        }
    }

    @Test
    void aStartThatFailsAfterItsBrokerCameUpKillsABrokerThatIgnoresTheSignal(@TempDir Path dir)
            throws Exception {

        // Its rollback only asked: a broker that came up and ignores SIGTERM outlived the start
        // whose container then failed to start.
        final PoreloContext context = brokeredContext(dir);
        final Path helpers = Files.createDirectories(dir.resolve("helpers"));
        final String ownPorelo = System.getProperty(PoreloBinary.NAMED);
        System.setProperty(PoreloBinary.NAMED, broker(helpers, true, ownPorelo, true).toString());
        runner.failing("podman start", 125, "Error: the hook refused the start");
        try {
            final int code =
                    execute(
                            context,
                            "task",
                            "start",
                            "-p",
                            projectFile(dir, MINIMAL),
                            "--agent",
                            "preview",
                            "--no-gate");

            assertThat(code).as("the start fails: %s", err).isNotZero();
            assertThat(HelperStandIn.started(helpers))
                    .as("its broker came up: %s", err)
                    .isEqualTo(1);
            assertThat(HelperStandIn.anyRunning(helpers))
                    .as("and is ended, though it ignores SIGTERM: %s", err)
                    .isFalse();
        } finally {
            System.setProperty(PoreloBinary.NAMED, ownPorelo);
            HelperStandIn.stop(helpers);
            PoreloContext.forgetAsked();
            if (org.fuin.porelo.vault.KernelKeyring.available()) {
                new org.fuin.porelo.vault.KernelKeyring(context.paths().vault().vaultKeyringKey())
                        .forget();
            }
        }
    }

    /** A context whose preview agent is brokered, its credential in a vault the start opens. */
    private PoreloContext brokeredContext(Path dir) throws IOException {
        final PoreloContext context =
                previewContext(dir, true).asking(shown -> "test-passphrase".toCharArray());
        previewAgent(context, "preview");
        Files.writeString(
                context.paths().xdg().data().resolve("providers/preview.yaml"),
                "token_env: { _default: PREVIEW_TOKEN }\n",
                java.nio.file.StandardOpenOption.APPEND);
        context.vault()
                .write(
                        java.util.Map.of(
                                "preview",
                                new org.fuin.porelo.vault.VaultEntry("test-key", "api-key")),
                        "test-passphrase".toCharArray());
        return context;
    }

    /**
     * A porelo whose {@code vault serve} is a stand-in that binds its socket, records its pid and
     * blocks, writing its token only when asked to; everything else is this build's.
     */
    private static Path broker(Path helpers, boolean givesToken, String ownPorelo)
            throws IOException {
        return broker(helpers, givesToken, ownPorelo, false);
    }

    /**
     * A porelo whose {@code vault serve} is a stand-in, as {@link #broker(Path, boolean, String)}
     * makes it, that ignores {@code SIGTERM} when {@code stubborn}.
     */
    private static Path broker(Path helpers, boolean givesToken, String ownPorelo, boolean stubborn)
            throws IOException {
        return broker(helpers, givesToken, ownPorelo, stubborn, true);
    }

    /**
     * A porelo whose {@code vault serve} is a stand-in, as {@link #broker(Path, boolean, String,
     * boolean)} makes it, that writes its {@code --pid-file} only when {@code recordsItsPid}.
     */
    private static Path broker(
            Path helpers,
            boolean givesToken,
            String ownPorelo,
            boolean stubborn,
            boolean recordsItsPid)
            throws IOException {
        final Path program = helpers.resolve("brokering-porelo");
        Files.writeString(
                program,
                """
                #!/bin/sh
                if [ "$1" = vault ] && [ "$2" = serve ]; then
                  previous=
                  for argument; do
                    case "$previous" in
                      --socket) : > "$argument" ;;
                      --token-file) %s ;;
                    esac
                    previous=$argument
                  done
                %s
                %s
                fi
                exec %s "$@"
                """
                        .formatted(
                                givesToken ? "echo stand-in-token > \"$argument\"" : ":",
                                stubborn ? "trap '' TERM" : "",
                                HelperStandIn.lines(HelperStandIn.registry(helpers), recordsItsPid),
                                shellArgument(ownPorelo)));
        Files.setPosixFilePermissions(
                program, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        return program;
    }

    @Test
    void aHostStartRefusedForItsKeyIsRefusedAgainRatherThanResumed(@TempDir Path dir)
            throws Exception {

        // Refused once its resume record was saved and its container kept, the next start of the
        // name brought it back, without the key the host refused it for and without its token.
        final PoreloContext context = context(dir, true);
        final Path upstream = dir.resolve("upstream.git");
        git(dir, "init", "-q", "--bare", "--initial-branch=main", upstream.toString());
        final Path seed = Files.createDirectory(dir.resolve("seed"));
        git(seed, "init", "-q");
        git(seed, "commit", "-q", "--allow-empty", "-m", "initial");
        git(seed, "push", "-q", upstream.toString(), "HEAD:refs/heads/main");
        final Path person = dir.resolve("person");
        final Process keygen =
                new ProcessBuilder(
                                "ssh-keygen",
                                "-q",
                                "-t",
                                "ed25519",
                                "-N",
                                "",
                                "-f",
                                person.toString())
                        .start();
        assertThat(keygen.waitFor()).as("the fixture creates its own certifier's key").isZero();
        final String[] key = Files.readString(dir.resolve("person.pub")).strip().split(" ");
        final Path projectFile =
                PlacedFixture.place(
                        context,
                        "uc",
                        """
                        project:
                          name: uc
                          security_class: guarded
                          upstream: "file://%s"
                        image:
                          from: ubuntu:24.04
                        """
                                .formatted(upstream),
                        PlacedFixture.key("alice", key[0] + " " + key[1]));
        final Path hostFiles = Files.createDirectory(dir.resolve("hosted"));
        final OrganizationProjects projects =
                new OrganizationProjects(
                        context.paths().projects().organizationProjects(), hostFiles);
        final var document = new java.util.LinkedHashMap<String, Object>();
        document.put("project", "uc");
        document.put("revision", "host-revision");
        document.put("address", "https://forge.example/uc.git");
        document.put(
                "signing_keys",
                List.of(
                        java.util.Map.of(
                                "login", "alice",
                                "role", "member",
                                "key", key[0] + " " + key[1],
                                "valid_from", "2000-01-01",
                                "valid_until", "2999-01-01")));
        document.put("host", "build");
        Files.writeString(hostFiles.resolve("uc.json"), org.fuin.porelo.wire.Json.write(document));
        final Path hostHelper = Files.writeString(dir.resolve("host-person"), "");
        assertThat(context.vault().exists())
                .as("no vault here, so no task's key can be kept")
                .isFalse();
        final java.util.concurrent.atomic.AtomicReference<String> named =
                new java.util.concurrent.atomic.AtomicReference<>();
        ((StoppingCommandRunner) runner)
                .beforeCreate(
                        command ->
                                named.set(
                                        command.arguments()
                                                .get(command.arguments().indexOf("--name") + 1)));
        final Path socket = dir.resolve("person.sock");
        try (TestSshAgent agents = new TestSshAgent()) {
            agents.start(socket);
            final ProcessBuilder add = new ProcessBuilder("ssh-add", "-q", person.toString());
            add.environment().put("SSH_AUTH_SOCK", socket.toString());
            assertThat(add.start().waitFor())
                    .as("the fixture's agent holds its certifier")
                    .isZero();
            final java.util.function.Supplier<TaskLaunch> launch =
                    () ->
                            new TaskLaunch(
                                    context,
                                    new TaskLaunch.Request(
                                            "shell",
                                            projectFile,
                                            null,
                                            null,
                                            null,
                                            8,
                                            null,
                                            false,
                                            false,
                                            "deny",
                                            false,
                                            org.fuin.porelo.wire.TaskMode.SHELL,
                                            null,
                                            null,
                                            null,
                                            60,
                                            "uc"),
                                    new StartSteps(step -> {}),
                                    new HostPerson(context, hostHelper),
                                    socket,
                                    projects);

            final int first =
                    launch.get()
                            .launch(
                                    new PrintWriter(out, true),
                                    new PrintWriter(err, true),
                                    running -> 0);
            assertThat(first)
                    .as("the host refuses a task whose key is not kept: %s", err)
                    .isEqualTo(65);
            // As podman lists it now, made by that start under the id the fake's create gives:
            // kept, it is there to bring back; removed, podman no longer lists it.
            assertThat(named.get()).as("the refused start had made its container").isNotNull();
            runner.answering(
                    "ps",
                    named.get()
                            + "\tExited (0) 1 second ago\t\t\tuc\tguarded\tuc\t\t"
                            + "0123456789abcdef"
                            + "\tshell\n");

            final TaskLaunch again = launch.get();
            final TaskLaunch.Existing existing =
                    again.startExisting(new PrintWriter(out, true), new PrintWriter(err, true));
            final int second =
                    existing != null
                            ? existing.code()
                            : again.launch(
                                    new PrintWriter(out, true),
                                    new PrintWriter(err, true),
                                    running -> 0);

            assertThat(second)
                    .as("the second start is refused as the first was, not resumed: %s", err)
                    .isEqualTo(65);
            assertThat(runner.lines().stream().filter(line -> line.startsWith("podman create")))
                    .as("each start made its own container, and none brought the refused one back")
                    .hasSize(2);
        } finally {
            TaskLifecycle.stopHelpers(shellState(context));
        }
    }

    /**
     * Writes a {@code porelo} whose helpers are stand-ins: a watcher that makes its socket, and
     * every helper one that records its pid and blocks until the test stops it.
     */
    private static Path helperStandIns(Path dir) throws IOException {
        final Path directory = Files.createDirectories(dir.resolve("helpers"));
        final Path helpers = directory.resolve("porelo");
        Files.writeString(
                helpers,
                "#!/bin/sh\n"
                        + OwnPorelo.watcher(HelperStandIn.registry(directory), true)
                        + HelperStandIn.lines(HelperStandIn.registry(directory), true));
        Files.setPosixFilePermissions(
                helpers, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        return helpers;
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

    /** A project file whose conversation is a room on a transport that shows a task's name. */
    private String roomProject(final PoreloContext context, final Path dir) throws IOException {
        final char[] passphrase = "test-passphrase".toCharArray();
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
                "{\"scheme\":\"room\",\"display\":true,"
                        + "\"lifecycle\":[\"setup\",\"enroll\",\"retire\"]}");
        runner.answering(
                " setup ", "{\"conversation\":\"!room\",\"reaches\":[\"127.0.0.1:18008\"]}");
        runner.answering(" enroll ", "{\"secrets\":{\"TASK\":\"t-1\"}}");
        runner.answering(" retire ", "{}");
        return projectFile(
                dir,
                MINIMAL
                        + "mail:\n  transports:\n    room: {}\n"
                        + "  peers:\n    reviewer: { address: 'room:', trust: vouched }\n");
    }

    /** Asserts the task was enrolled once, under the given name in the room. */
    private void enrolledAs(final String display) {
        assertThat(runner.invocations())
                .as("enrolled in the project's room: %s", err)
                .filteredOn(command -> command.arguments().contains("enroll"))
                .singleElement()
                .satisfies(
                        command ->
                                assertThat(command.arguments())
                                        .as("under the task's own name")
                                        .containsSubsequence("--display", display));
    }

    @Test
    void aNewTaskIsEnrolledInTheRoomUnderItsOwnNameWhateverAgentRunsIt(@TempDir Path dir)
            throws IOException {
        // Enrolled by its agent's name, a task named fix is "Agent Preview", and "Agent Fix:"
        // reaches nobody.
        final PoreloContext context =
                countingContext(dir).asking(shown -> "test-passphrase".toCharArray());
        final String project = roomProject(context, dir);

        execute(context, "task", "start", "fix", "-p", project, "--agent", "preview", "--no-gate");

        enrolledAs("Agent Fix");
    }

    @Test
    void aStoppedTaskStartedAgainIsEnrolledInTheRoomUnderItsOwnName(@TempDir Path dir)
            throws IOException {
        // Enrolled by its recorded agent's name on the way back, the task would no longer answer
        // to its own name in the room.
        final PoreloContext context =
                context(dir, true).asking(shown -> "test-passphrase".toCharArray());
        final String project = roomProject(context, dir);
        runner.answering(
                "ps",
                "porelo-uc-shell\tExited (0) 4 minutes ago\t1700000000\t1700000100\tuc\tguarded\tuc\t\n");
        recordSubagents(context, "preview", 0);

        execute(context, "task", "start", "shell", "-p", project, "--detach");

        enrolledAs("Agent Shell");
    }

    @Test
    void aTaskBroughtBackAfterARebootIsEnrolledUnderItsOwnName(@TempDir Path dir)
            throws IOException {
        // A reboot empties the runtime directory before the resume puts the record back: the task
        // must still come back into its room under its own name, not its agent's.
        final PoreloContext context =
                context(dir, true).asking(shown -> "test-passphrase".toCharArray());
        final String project = roomProject(context, dir);
        runner.answering(
                "ps",
                "porelo-uc-shell\tExited (0) 4 minutes ago\t1700000000\t1700000100\tuc\tguarded\tuc\t\n");
        recordSubagents(context.paths().tasks().taskRecord("porelo-uc-shell"), "preview", 0);
        org.fuin.porelo.wire.Sidecar.recordId(
                context.paths().tasks().taskRecord("porelo-uc-shell"), "0123456789abcdef");
        runner.answering("{{.Id}}", "0123456789abcdef");

        execute(context, "task", "start", "shell", "-p", project, "--detach");

        enrolledAs("Agent Shell");
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
    void aTaskBroughtBackAfterARebootWithAnotherModelIsRefusedAndNotBroughtBack(@TempDir Path dir)
            throws IOException {

        // Its runtime record is gone until the resume: read from there alone, the task came back on
        // its old model while the start was asked for another and said nothing.
        final PoreloContext context = context(dir, true);
        runner.answering(
                "ps",
                "porelo-uc-shell\tExited (0) 4 minutes ago\t1700000000\t1700000100\tuc\tguarded\tuc\t\n");
        recordSubagents(context.paths().tasks().taskRecord("porelo-uc-shell"), "preview", 0);
        org.fuin.porelo.wire.Sidecar.recordId(
                context.paths().tasks().taskRecord("porelo-uc-shell"), "0123456789abcdef");
        runner.answering("{{.Id}}", "0123456789abcdef");

        final int code =
                execute(
                        context,
                        "task",
                        "start",
                        "shell",
                        "-p",
                        projectFile(dir, MINIMAL),
                        "--model",
                        "model-a",
                        "--detach");

        assertThat(code).as("refused: %s", err).isEqualTo(64);
        assertThat(runner.lines())
                .as("and not brought back: %s", runner.lines())
                .noneMatch(line -> line.contains("podman start"));
    }

    @Test
    void anArtifactItsAdapterGivesToRootIsFetchedAsRootInTheImageBuiltForIt(@TempDir Path dir)
            throws IOException {

        // Every hop: the adapter's definition, its Describe over the agent protocol, the
        // definition the start reads back, the layers it builds and the Containerfile podman gets.
        // Measured on 2026-11-13: claude's root artifact was fetched after 'USER agent', and its
        // install into /usr/local/share was refused.
        final PoreloContext context = previewContext(dir);
        previewAgent(
                context,
                "preview",
                """
                install:
                  version: "1.0.0"
                  artifacts:
                    - url: https://example.com/preview.tar.gz
                      sha256: "%s"
                      target: /usr/local/share/preview/preview.tar.gz
                      mode: "0644"
                      owner: root
                    - url: https://example.com/preview-helper
                      sha256: "%s"
                      target: /home/agent/.local/bin/preview-helper
                  as_root:
                    - "RUN tar -xzf /usr/local/share/preview/preview.tar.gz -C /usr/local/bin"
                """
                        .formatted("a".repeat(64), "b".repeat(64)));

        final int code =
                execute(
                        context,
                        "task",
                        "prepare",
                        "-p",
                        projectFile(dir, MINIMAL),
                        "--agent",
                        "preview");

        assertThat(code).as("prepared: %s", err).isZero();
        final Path containerfile;
        try (var tree = Files.walk(dir)) {
            containerfile =
                    tree.filter(path -> path.getFileName().toString().equals("Containerfile"))
                            .findFirst()
                            .orElseThrow();
        }
        final List<String> lines = Files.readAllLines(containerfile);
        final int user = lines.indexOf("USER agent");
        final int root = indexContaining(lines, "https://example.com/preview.tar.gz");
        final int unpack = indexContaining(lines, "tar -xzf /usr/local/share/preview");
        final int helper = indexContaining(lines, "https://example.com/preview-helper");
        assertThat(root)
                .as("the root artifact is fetched as root, before the root step that unpacks it")
                .isGreaterThan(-1)
                .isLessThan(unpack)
                .isLessThan(user);
        assertThat(helper).as("the agent's own, after the switch, as before").isGreaterThan(user);
    }

    private static int indexContaining(List<String> lines, String text) {
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).contains(text)) {
                return index;
            }
        }
        return -1;
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
    void aRunInAnExistingTaskNamesItsContainerOnTheAgentStep(@TempDir Path dir) throws IOException {

        // A client that takes the task as started at the agent's step and stops reading the start
        // must still be able to follow the task; without the container it waited for a reply that
        // comes only when the run ends.
        final PoreloContext context = previewContext(dir);
        previewAgent(context, "preview", "subagents: count\n");
        recordSubagents(context, "preview", 2);
        final Path podman = dir.resolve("echoing-podman");
        Files.writeString(podman, "#!/bin/sh\necho \"$@\"\n");
        Files.setPosixFilePermissions(
                podman, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        final String standIn = System.getProperty(org.fuin.porelo.runtime.Podman.NAMED);
        System.setProperty(org.fuin.porelo.runtime.Podman.NAMED, podman.toString());
        final List<StartStep> steps = new java.util.ArrayList<>();
        try {
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
                                    null),
                            new StartSteps(steps::add, new PrintWriter(err)))
                    .runAgentInExisting(
                            "porelo-uc-shell", null, new PrintWriter(out), new PrintWriter(err));
        } finally {
            System.setProperty(org.fuin.porelo.runtime.Podman.NAMED, standIn);
        }

        assertThat(steps)
                .as("the agent's step names the task's container: %s", err)
                .filteredOn(step -> step.step() == StartStep.Step.AGENT)
                .isNotEmpty()
                .allSatisfy(
                        step ->
                                assertThat(step.asMap())
                                        .containsEntry("container", "porelo-uc-shell"));
    }

    /** The minimal project, naming the model its preview provider's tasks run on. */
    private static final String WITH_MODEL =
            MINIMAL + "providers:\n  preview:\n    model: \"model-a\"\n    label: \"Model A\"\n";

    /**
     * A machine with the preview agent brokered to the preview provider, which needs no credential,
     * taking a model by its flag or declaring none, and its hooks registered.
     */
    private PoreloContext modelContext(Path dir, boolean takesAModel) throws IOException {
        final PoreloContext context = previewContext(dir, true);
        previewAgent(context, "preview");
        if (takesAModel) {
            final Path definition = context.paths().xdg().data().resolve("agents/preview.yaml");
            Files.writeString(
                    definition,
                    Files.readString(definition)
                            .replace(
                                    "headless: { prompt_flag: \"-p\" }",
                                    "headless: { prompt_flag: \"-p\", model_flag: \"--model\" }"));
        }
        return context;
    }

    /**
     * Runs a start whose agent's run writes the command it was asked to run into the task's log.
     */
    private int echoingStart(PoreloContext context, Path dir, String project, String... options)
            throws IOException {
        final Path podman = dir.resolve("echoing-podman");
        Files.writeString(podman, "#!/bin/sh\necho \"$@\"\n");
        Files.setPosixFilePermissions(
                podman, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        final List<String> args =
                new ArrayList<>(
                        List.of(
                                "task",
                                "start",
                                "-p",
                                projectFile(dir, project),
                                "--agent",
                                "preview",
                                "--prompt",
                                "do the work",
                                "--no-gate"));
        args.addAll(List.of(options));
        final String standIn = System.getProperty(org.fuin.porelo.runtime.Podman.NAMED);
        System.setProperty(org.fuin.porelo.runtime.Podman.NAMED, podman.toString());
        try {
            return execute(context, args.toArray(String[]::new));
        } finally {
            System.setProperty(org.fuin.porelo.runtime.Podman.NAMED, standIn);
        }
    }

    /**
     * The shell task's runtime state, under the name it was made with: the new name for a task this
     * test started, the old one for a task a fixture lists as made before.
     */
    private static Path shellState(PoreloContext context) {
        final Path made = context.paths().tasks().containerState("porelo-uc_shell");
        return Files.isDirectory(made)
                ? made
                : context.paths().tasks().containerState("porelo-uc-shell");
    }

    private static String taskLog(PoreloContext context) throws IOException {
        return Files.readString(shellState(context).resolve("task.log"));
    }

    private static org.fuin.porelo.wire.TaskProfile recorded(PoreloContext context) {
        return java.util.Objects.requireNonNull(
                org.fuin.porelo.wire.TaskProfile.readFrom(shellState(context)),
                "the start records its task");
    }

    @Test
    void aProjectsModelReachesItsAgentAndTheTaskSaysItCameFromTheProject(@TempDir Path dir)
            throws IOException {

        // A start that names no model runs on the file's for its provider, never on the agent's
        // own.
        final PoreloContext context = modelContext(dir, true);

        final int code = echoingStart(context, dir, WITH_MODEL);

        assertThat(code).as("started and run: %s", err).isZero();
        assertThat(taskLog(context))
                .as("the agent's command carries the project's model by its flag")
                .contains("--model model-a");
        assertThat(recorded(context).model())
                .as("the task records the model it runs on")
                .isEqualTo("model-a");
        assertThat(recorded(context).modelFrom())
                .as("the task says the project's file chose it")
                .isEqualTo("project");
        assertThat(recorded(context).modelLabel())
                .as("the label the file gave it is kept for the row")
                .isEqualTo("Model A");
    }

    @Test
    void aStartsModelWinsOverTheProjectsAndTheTaskSaysItCameFromTheStart(@TempDir Path dir)
            throws IOException {
        final PoreloContext context = modelContext(dir, true);

        final int code = echoingStart(context, dir, WITH_MODEL, "--model", "model-b");

        assertThat(code).as("started and run: %s", err).isZero();
        assertThat(taskLog(context))
                .as("the person's choice is the one the agent is given")
                .contains("--model model-b")
                .doesNotContain("model-a");
        assertThat(recorded(context).modelFrom())
                .as("the task says the start chose it")
                .isEqualTo("start");
        assertThat(recorded(context).modelLabel())
                .as("the project's label is not the start's model's")
                .isNull();
    }

    @Test
    void aTaskIsDescribedBeforeItsContainerRuns(@TempDir Path dir) throws IOException {

        // A listing asked while the start was still placing files read a running task with no
        // record and said it ran on the agent's own default model, though the start had chosen one.
        final PoreloContext context = modelContext(dir, true);
        final List<org.fuin.porelo.wire.TaskProfile> atStart = new ArrayList<>();
        ((StoppingCommandRunner) runner)
                .beforeStart(
                        command ->
                                atStart.add(
                                        org.fuin.porelo.wire.TaskProfile.readFrom(
                                                shellState(context))));

        final int code = echoingStart(context, dir, WITH_MODEL, "--model", "model-b");

        assertThat(code).as("started and run: %s", err).isZero();
        assertThat(atStart).as("the container was started").isNotEmpty();
        assertThat(atStart.getFirst())
                .as("what the task is stands recorded when its container starts")
                .isNotNull();
        assertThat(atStart.getFirst().model())
                .as("a listing then reads the model the start chose")
                .isEqualTo("model-b");
        assertThat(atStart.getFirst().modelFrom()).isEqualTo("start");
    }

    @Test
    void aStartedTasksRecordSaysItsWorkItsModelAndHowItsRunEnded(@TempDir Path dir)
            throws IOException {

        // The record is the start's, not a test's own: started, run and ended by the command.
        final PoreloContext context = modelContext(dir, true);

        final int code =
                echoingStart(
                        context,
                        dir,
                        WITH_MODEL,
                        "--model",
                        "model-b",
                        "--work",
                        "B622:Implementing"); // not-a-citation

        assertThat(code).as("started and run: %s", err).isZero();
        final List<java.util.Map<String, Object>> records =
                new TaskWorkRecords(context).since("uc", java.time.Instant.EPOCH);
        assertThat(records).as("one record of the task").hasSize(1);
        assertThat(records.getFirst())
                .containsEntry(
                        "work",
                        java.util.Map.of(
                                "issue", "B622", "phase", "Implementing")) // not-a-citation
                .containsEntry("model", "model-b")
                .containsEntry("modelFrom", "start")
                .containsEntry("end", TaskWorkRecords.DONE);
        assertThat(records.getFirst().get("startedAt")).isNotNull();
        assertThat(records.getFirst().get("endedAt")).isNotNull();
    }

    @Test
    void anAttendedStartHandsItsAgentTheProjectsModel(@TempDir Path dir) throws IOException {

        // Attended, the agent is started by the session: without the model it ran on its default.
        final PoreloContext context = modelContext(dir, true);

        final int code =
                execute(
                        context,
                        "task",
                        "start",
                        "-p",
                        projectFile(dir, WITH_MODEL),
                        "--agent",
                        "preview",
                        "--attach",
                        "agent",
                        "--no-gate");

        assertThat(code).as("started: %s", err).isZero();
        assertThat(execCalls).as("the session it went into").hasSize(1);
        assertThat(String.join(" ", execCalls.get(0)))
                .as("the session starts the agent on the project's model")
                .contains("'--model' 'model-a'");
    }

    @Test
    void aProjectsModelForAnAgentWithoutAModelFlagIsRefusedBeforeAnythingIsMade(@TempDir Path dir)
            throws IOException {

        // Dropped on the way in, the agent would answer on its own default while the file said
        // otherwise.
        final PoreloContext context = modelContext(dir, false);

        final int code = echoingStart(context, dir, WITH_MODEL);

        assertThat(code).as("refused: %s", err).isEqualTo(2);
        assertThat(runner.lines())
                .as("a refused model leaves no image or container")
                .noneMatch(line -> line.contains("podman build") || line.contains("podman create"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-x", "two words", "line\nbreak", ""})
    void aModelThatIsNotOneWordIsRefusedBeforeAnythingIsMade(String model, @TempDir Path dir)
            throws IOException {

        // It goes on the agent's command line, where it would be read as an option or as two.
        final PoreloContext context = modelContext(dir, true);

        final int code = echoingStart(context, dir, MINIMAL, "--model=" + model);

        assertThat(code).as("refused: %s", err).isEqualTo(2);
        assertThat(runner.lines())
                .as("a refused model leaves no image or container")
                .noneMatch(line -> line.contains("podman build") || line.contains("podman create"));
    }

    @Test
    void aProviderTheFileNamesThatThisMachineDoesNotKnowIsRefusedBeforeAnythingIsMade(
            @TempDir Path dir) throws IOException {

        // A typo in the provider's name would otherwise run every task on the agent's own default.
        final PoreloContext context = modelContext(dir, true);

        final int code =
                echoingStart(
                        context,
                        dir,
                        MINIMAL + "providers:\n  previews:\n    model: \"model-a\"\n");

        assertThat(code).as("refused: %s", err).isEqualTo(2);
        assertThat(runner.lines())
                .as("a refused file leaves no image or container")
                .noneMatch(line -> line.contains("podman build") || line.contains("podman create"));
    }

    private int startedThroughAnEndpoint(PoreloContext context, Path dir, String project)
            throws IOException {
        projectFile(dir, project);
        final PrintWriter said = new PrintWriter(err, true);
        return new TaskLaunch(
                        context,
                        new TaskLaunch.Request(
                                "shell",
                                dir.resolve("project.yml"),
                                "preview",
                                null,
                                null,
                                8,
                                null,
                                true,
                                false,
                                "prompt",
                                true,
                                org.fuin.porelo.wire.TaskMode.SHELL,
                                null,
                                null,
                                null,
                                60,
                                null,
                                java.util.Map.of(),
                                java.util.Map.of(),
                                null,
                                null,
                                null,
                                "porelo-uc-lead"),
                        new StartSteps(step -> {}, said))
                .launch(new PrintWriter(out, true), said, running -> 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"model-a", "model-b"})
    void aStartThroughAnEndpointRunsOnlyOnAModelItsListAllowsWhicheverFileNamedIt(
            String named, @TempDir Path dir) throws IOException {

        // The list holds for the model the start ends up with, not only for one the caller named.
        final PoreloContext context = modelContext(dir, true);

        final int code =
                startedThroughAnEndpoint(
                        context,
                        dir,
                        MINIMAL
                                + "providers:\n  preview:\n    model: \""
                                + named
                                + "\"\nmcp:\n  methods: [\"Start\"]\n  agents:\n"
                                + "    preview: [\"model-a\"]\n");

        if ("model-a".equals(named)) {
            // Here past the model, a start in this process stops at the endpoint only porelod
            // gives.
            assertThat(code)
                    .as("a model the list allows is not refused for its model: %s", err)
                    .isNotEqualTo(2);
        } else {
            assertThat(code).as("a model the list does not allow is refused: %s", err).isEqualTo(2);
            assertThat(runner.lines())
                    .as("a refused model leaves no container")
                    .noneMatch(line -> line.contains("podman create"));
        }
    }

    private StartCheck.Result canStart(PoreloContext context, Path dir) {
        return StartCheck.check(
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
    }

    @Test
    void aProviderUnknownToTheAgentIsATypedStartRefusalAndLeavesNothingBehind(
            @TempDir final Path dir) throws IOException {
        // An interface must not offer a start that the agent will reject after a container is made.
        final PoreloContext context = modelContext(dir, true);
        final Path definition = context.paths().xdg().data().resolve("agents/preview.yaml");
        Files.writeString(
                definition,
                Files.readString(definition)
                        .replace("dialect: preview", "dialect: native\n  knows: [preview]"));
        Files.writeString(
                context.paths().xdg().data().resolve("providers/elsewhere.yaml"),
                """
                name: elsewhere
                upstream: https://provider.example.org
                dialects: { preview: "" }
                """);
        final String project = projectFile(dir, MINIMAL);

        final StartCheck.Result checked =
                StartCheck.check(context, dir.resolve("project.yml"), "preview", "elsewhere", null);

        assertThat(checked.outcome())
                .as("CanStart preserves the provider's specific refusal")
                .isEqualTo(StartCheck.Outcome.UNKNOWN_TO_AGENT);
        assertThat(checked.ready()).as("the client must not offer this start").isFalse();
        assertThat(checked.asMap())
                .as("the refusal crosses the wire as a value with the chosen names")
                .containsEntry("outcome", "UNKNOWN_TO_AGENT")
                .containsEntry("agent", "preview")
                .containsEntry("provider", "elsewhere");
        assertThat(
                        execute(
                                context,
                                "task",
                                "start",
                                "-p",
                                project,
                                "--agent",
                                "preview",
                                "--provider",
                                "elsewhere",
                                "--dry-run"))
                .as("a dry run refuses the same choice: %s", err)
                .isEqualTo(2);
        assertThat(echoingStart(context, dir, MINIMAL, "--provider", "elsewhere"))
                .as("the actual start refuses before creating anything: %s", err)
                .isEqualTo(2);
        assertThat(runner.lines())
                .as("neither start builds an image or creates a container")
                .noneMatch(line -> line.contains("podman build") || line.contains("podman create"));
    }

    @Test
    void canStartRefusesAProjectsModelForAnAgentWithoutAModelFlagAsStartRefusesIt(@TempDir Path dir)
            throws IOException {

        // An interface told READY would offer a start that Start then refuses.
        final PoreloContext context = modelContext(dir, false);
        final String project = projectFile(dir, WITH_MODEL);

        final StartCheck.Result checked = canStart(context, dir);
        final int started =
                execute(context, "task", "start", "-p", project, "--agent", "preview", "--dry-run");

        assertThat(checked.outcome())
                .as("CanStart says it cannot start: %s", checked.detail())
                .isEqualTo(StartCheck.Outcome.MODEL_REFUSED);
        assertThat(started).as("and a dry run refuses it as Start does: %s", err).isEqualTo(2);
    }

    @Test
    void canStartRefusesAProviderTheFileNamesThatThisMachineDoesNotKnow(@TempDir Path dir)
            throws IOException {
        final PoreloContext context = modelContext(dir, true);
        projectFile(dir, MINIMAL + "providers:\n  previews:\n    model: \"model-a\"\n");

        assertThat(canStart(context, dir).outcome())
                .as("the start refuses the file, so CanStart may not answer READY")
                .isEqualTo(StartCheck.Outcome.UNKNOWN_PROVIDER);
    }

    @Test
    void canStartAnswersReadyForAProjectsModelTheAgentTakes(@TempDir Path dir) throws IOException {
        final PoreloContext context = modelContext(dir, true);
        projectFile(dir, WITH_MODEL);

        assertThat(canStart(context, dir).outcome())
                .as("a model the agent takes stands in the way of nothing")
                .isEqualTo(StartCheck.Outcome.READY);
    }

    @Test
    void theModelsShownBeforeAStartAreTheOnesTheStartSettles(@TempDir Path dir) throws IOException {

        // New work shows the model before the start: one the start would not use misleads.
        final PoreloContext context = modelContext(dir, true);
        projectFile(dir, WITH_MODEL);

        assertThat(ModelChoice.defaults(context, dir.resolve("project.yml"), null))
                .as("each provider this machine knows, with the project's model for it")
                .containsExactly(
                        new ModelChoice.ProviderModel("preview", "model-a", "Model A", "project"));

        projectFile(dir, MINIMAL);
        assertThat(ModelChoice.defaults(context, dir.resolve("project.yml"), "uc"))
                .as("a provider nothing names a model for runs on the agent's own")
                .containsExactly(new ModelChoice.ProviderModel("preview", "", "", "agent"));

        projectFile(dir, MINIMAL + "providers:\n  previews:\n    model: \"model-a\"\n");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> ModelChoice.defaults(context, dir.resolve("project.yml"), null))
                .as("a file the start refuses is refused here too")
                .isInstanceOf(org.fuin.porelo.core.project.ProjectException.class);
    }

    @Test
    void aStoppedTaskContinuedWithAPromptRunsOnItsRecordedModelNotTheFilesNow(@TempDir Path dir)
            throws IOException {

        // A task's model was settled when it was made; a file changed since must not move it.
        final PoreloContext context = modelContext(dir, true);
        projectFile(dir, MINIMAL + "providers:\n  preview:\n    model: \"model-b\"\n");
        final Path state =
                Files.createDirectories(context.paths().tasks().containerState("porelo-uc-shell"));
        new org.fuin.porelo.wire.TaskProfile(
                        org.fuin.porelo.wire.TaskProfile.VERSION,
                        "preview",
                        org.fuin.porelo.wire.TaskMode.UNATTENDED,
                        "do the work",
                        null,
                        "2026-10-31T00:00:00Z",
                        "deny",
                        null,
                        "preview",
                        "model-a",
                        null,
                        0,
                        null,
                        "project",
                        null)
                .writeTo(state);
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
        assertThat(taskLog(context))
                .as("the agent is asked for its run on the model it was made with")
                .contains("--model model-a")
                .doesNotContain("model-b");
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
                        dir.resolve("state/porelo/tasks/porelo-uc_shell")
                                .resolve(TaskInventory.PARAMETERS_FILE));
        assertThat(recorded).contains("AB-12").contains("command line").contains("default");
        // And its agent is told, after the text every task shares.
        final String guide =
                Files.readString(new TaskGuide(context.paths(), "porelo-uc_shell").file());
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
                        dir, MINIMAL + "limits:\n  subagents: 2\n" + "repositories:\n  backend:\n");

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
                .as("in the words Start refuses with, naming the project's key")
                .contains("limits.subagents 2");
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
                        dir, MINIMAL + "limits:\n  subagents: 1\n" + "repositories:\n  backend:\n");

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
                                + "limits:\n  subagents: 2\n"
                                + "repositories:\n  backend:\n    agent: preview\n");

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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"0", "-5", "2.5", "2147483648", "'abc'"})
    void aTimeLimitOfNoMinutesOrFewerIsWrongUse(String value, @TempDir Path dir)
            throws IOException {
        // Zero or less ended the run at once; Tasks1 Start refuses it in the same words.
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
                        "--prompt",
                        "go",
                        "--minutes=" + value.replace("'", ""),
                        "--dry-run");

        assertThat(code)
                .as("refused as wrong use, as Tasks1 Start refuses it: %s", err)
                .isEqualTo(64);
        assertThat(err.toString())
                .contains(
                        "porelo: --minutes takes a whole number of minutes from 1 to 2147483647,"
                                + " not "
                                + value);
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
    void aMissingInitIsRefusedBeforeResolvingAProject(@TempDir Path dir) throws IOException {
        // Project resolution can fetch and write organization state before the launch is called.
        final PoreloContext context = context(dir, false);
        runner.failing("porelo-init-check", 1, "");

        final int code = execute(context, "task", "start", "-p", "not-taken", "--no-input");

        assertThat(err.toString())
                .as("the init check must come before taking a project")
                .contains("catatonit");
        assertThat(code)
                .as("a missing init refuses even before a project has been taken")
                .isEqualTo(69);
        assertThat(runner.lines())
                .as("only the init may be checked")
                .allMatch(line -> line.contains("porelo-init-check"));
        assertThat(dir.resolve("data")).as("no project record is written").doesNotExist();
        assertThat(dir.resolve("state")).as("no task record is written").doesNotExist();
    }

    @Test
    void aMissingInitMakesNothing(@TempDir Path dir) throws IOException {
        // Discovering a missing init after preparation leaves unusable task state behind.
        final PoreloContext context = context(dir, false);
        runner.failing("porelo-init-check", 1, "");

        final int code = execute(context, "task", "start", "-p", projectFile(dir, MINIMAL));

        assertThat(err.toString()).as("the missing init must be the reason").contains("catatonit");
        assertThat(code).as("a missing program refuses the start").isEqualTo(69);
        assertThat(runner.lines())
                .as("no image or container may be made")
                .noneMatch(line -> line.matches("podman (build|pull|create|run|start) .+"));
        assertThat(context.paths().hooksDirectory())
                .as("not even hooks are registered")
                .doesNotExist();
        assertThat(dir.resolve("state")).as("no task record is written").doesNotExist();
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
        // Asking podman and ssh their versions comes first and reads nothing else; what must not
        // happen is a container existing without the hooks that give it a firewall.
        // Finding which checkout the start was run in, and whether the task exists, only reads.
        assertThat(runner.lines())
                .allMatch(
                        line ->
                                line.startsWith("podman version")
                                        || line.contains("porelo-init-check")
                                        || line.equals("ssh -V")
                                        || line.startsWith("podman ps ")
                                        || line.equals("git -C . rev-parse --show-toplevel"));
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
        final String file =
                projectFile(
                        dir,
                        MINIMAL.replace(
                                "  security_class: \"guarded\"",
                                "  security_class: \"guarded\"\n  upstream: \"file://%s\""
                                        .formatted(upstream)));
        // These starts are about what the files say, not about this machine's yes to their lines.
        final PoreloContext context = context(dir, true);
        allowRepositoryImage(
                context, ProjectSource.resolve(context, file).file(), "uc", before, after);
        return file;
    }

    @Test
    void aWindowAllowsAnImageLineNamingTheRepositoryByItsFullName(@TempDir Path dir)
            throws Exception {
        // CanStart takes the full name a person reads in the window; the yes takes it too.
        final String file = unallowedRepositoryImage(dir, "image:\n  run: \"RUN echo one\"\n");
        final PoreloContext context = context(dir, true);
        final var project =
                org.fuin.porelo.core.project.ProjectReader.read(
                        ProjectSource.resolve(context, file).file());
        String shown = "";
        try {
            ImageChoice.preview(context, project, project.repository("uc"));
        } catch (ImageChoice.NotAllowed ex) {
            shown = ex.hash();
        }
        assertThat(shown).as("the file asks first").isNotEmpty();

        assertThat(ImageChoice.allowIfUnchanged(context, file, "upstream", shown)).isTrue();
        assertThat(
                        ImageChoice.preview(context, project, project.repository("uc"))
                                .choice()
                                .snippet())
                .isEqualTo("RUN echo one");
    }

    /** A hand-written project whose repository's file, in its mirror, nobody allowed yet. */
    private String unallowedRepositoryImage(final Path dir, final String text) throws IOException {
        final Path upstream = Files.createDirectories(dir.resolve("upstream"));
        git(upstream, "init", "-q");
        Files.writeString(upstream.resolve("porelo.yml"), text);
        git(upstream, "add", "porelo.yml");
        git(upstream, "commit", "-q", "-m", "upstream");
        final Path mirror = dir.resolve("data/porelo/mirrors/uc.git");
        Files.createDirectories(mirror.getParent());
        git(dir, "clone", "-q", "--bare", upstream.toString(), mirror.toString());
        return projectFile(
                dir,
                MINIMAL.replace(
                        "  security_class: \"guarded\"",
                        "  security_class: \"guarded\"\n  upstream: \"file://%s\""
                                .formatted(upstream)));
    }

    @Test
    void aHandWrittenProjectsUnsignedImageLineIsBuiltOnlyOnceThisMachineAllowedIt(@TempDir Path dir)
            throws Exception {
        // Nobody signs it, and its lines run as root while the image is built.
        final String file =
                unallowedRepositoryImage(
                        dir, "image:\n  from: ubuntu:24.04\n  run: RUN echo upstream-line\n");
        final PoreloContext context = repositoryImageContext(dir);

        final int refused =
                execute(
                        context,
                        "task",
                        "start",
                        "--attach",
                        "shell",
                        "--detach",
                        "--no-gate",
                        "-p",
                        file);

        assertThat(refused).as("nobody answered: %s", out).isNotZero();
        assertThat(err.toString())
                .contains("has not allowed", "+ image.run[1] = RUN echo upstream-line");
        assertThat(runner.lines())
                .noneMatch(line -> line.contains("podman build") || line.contains("podman create"));

        out.getBuffer().setLength(0);
        assertThat(execute(context, "task", "start", "--no-gate", "--dry-run", "-p", file))
                .as("a dry run shows what a start would ask: %s", err)
                .isZero();
        assertThat(out.toString())
                .contains(
                        "setting        + image.run[1] = RUN echo upstream-line",
                        "image settings not allowed on this machine yet; a start asks first");

        final Path projectFile = ProjectSource.resolve(context, file).file();
        final var project = org.fuin.porelo.core.project.ProjectReader.read(projectFile);
        final StartCheck.Result asked =
                StartCheck.withSettings(
                        context,
                        new StartCheck.Result(StartCheck.Outcome.READY, "", "", "", ""),
                        projectFile,
                        "uc");
        assertThat(asked.outcome())
                .as("CanStart refuses it as Start does")
                .isEqualTo(StartCheck.Outcome.SETTINGS_NOT_ALLOWED);
        assertThat(asked.settingsToAllow())
                .extracting(each -> each.get("key") + " = " + each.get("value"))
                .contains("image.run[1] = RUN echo upstream-line");
        assertThat(
                        ImageChoice.allowIfUnchanged(
                                context, project, project.repository("uc"), "0".repeat(64)))
                .as("a hash nobody was shown allows nothing")
                .isFalse();
        assertThat(
                        ImageChoice.allowIfUnchanged(
                                context, project, project.repository("uc"), asked.settingsHash()))
                .isTrue();
        assertThat(
                        StartCheck.withSettings(
                                        context,
                                        new StartCheck.Result(
                                                StartCheck.Outcome.READY, "", "", "", ""),
                                        projectFile,
                                        "uc")
                                .outcome())
                .isEqualTo(StartCheck.Outcome.READY);

        err.getBuffer().setLength(0);
        assertThat(
                        execute(
                                context,
                                "task",
                                "start",
                                "--attach",
                                "shell",
                                "--detach",
                                "--no-gate",
                                "-p",
                                file))
                .as("allowed, it starts unasked: %s", err)
                .isZero();
        assertThat(err.toString()).doesNotContain("has not allowed");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"y,true", "n,false"})
    void aPersonAtATerminalIsAskedForAnUnsignedImageLineOnce(
            final String answer, final boolean started, @TempDir Path dir) throws Exception {
        final String file =
                unallowedRepositoryImage(
                        dir, "image:\n  from: ubuntu:24.04\n  run: RUN echo upstream-line\n");
        final PoreloContext context = repositoryImageContext(dir);
        final Path projectFile = ProjectSource.resolve(context, file).file();
        final PrintWriter said = new PrintWriter(err, true);
        final List<String> questions = new ArrayList<>();

        final int code =
                new TaskLaunch(
                                context,
                                new TaskLaunch.Request(
                                        "shell",
                                        projectFile,
                                        null,
                                        null,
                                        null,
                                        8,
                                        null,
                                        true,
                                        false,
                                        "deny",
                                        true,
                                        org.fuin.porelo.wire.TaskMode.SHELL,
                                        null,
                                        null,
                                        null,
                                        60,
                                        "uc",
                                        java.util.Map.of(),
                                        java.util.Map.of(),
                                        null,
                                        null),
                                new StartSteps(step -> {}, said))
                        .offering(
                                new Offer(
                                        question -> {
                                            questions.add(question);
                                            return answer;
                                        },
                                        false,
                                        false,
                                        said))
                        .launch(new PrintWriter(out, true), said, running -> 0);

        assertThat(questions)
                .as("asked once, naming the line")
                .singleElement()
                .asString()
                .contains("+ image.run[1] = RUN echo upstream-line");
        assertThat(code == 0).as("%s%s", out, err).isEqualTo(started);
        final var project = org.fuin.porelo.core.project.ProjectReader.read(projectFile);
        assertThat(
                        new RepositoryAllowance(context, project.name())
                                .notAllowed(
                                        "uc",
                                        List.of(
                                                new org.fuin.porelo.core.project.file
                                                        .RepositorySettings.Asked(
                                                        "image.run[1]",
                                                        "RUN echo upstream-line",
                                                        "",
                                                        false))))
                .as("a yes is kept, a No keeps nothing")
                .hasSize(started ? 0 : 1);
    }

    @Test
    void aFileTheFirstFetchBringsIsAskedForAfterTheFetch(@TempDir Path dir) throws Exception {
        // A new repository has no mirror before its first fetch: the question comes after it.
        final String file =
                unallowedRepositoryImage(
                        dir, "image:\n  from: ubuntu:24.04\n  run: RUN echo upstream-line\n");
        final Path mirror = dir.resolve("data/porelo/mirrors/uc.git");
        try (var all = Files.walk(mirror)) {
            all.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
        final PoreloContext context = repositoryImageContext(dir);
        final Path projectFile = ProjectSource.resolve(context, file).file();
        final PrintWriter said = new PrintWriter(err, true);
        final List<String> questions = new ArrayList<>();

        final int code =
                new TaskLaunch(
                                context,
                                new TaskLaunch.Request(
                                        "shell",
                                        projectFile,
                                        null,
                                        null,
                                        null,
                                        8,
                                        null,
                                        false,
                                        false,
                                        "deny",
                                        true,
                                        org.fuin.porelo.wire.TaskMode.SHELL,
                                        null,
                                        null,
                                        null,
                                        60,
                                        "uc",
                                        java.util.Map.of(),
                                        java.util.Map.of(),
                                        null,
                                        null),
                                new StartSteps(step -> {}, said))
                        .offering(
                                new Offer(
                                        question -> {
                                            questions.add(question);
                                            return "y";
                                        },
                                        false,
                                        false,
                                        said))
                        .launch(new PrintWriter(out, true), said, running -> 0);

        assertThat(mirror).as("the start fetched it").exists();
        assertThat(questions)
                .as("asked once, after the fetch")
                .singleElement()
                .asString()
                .contains("+ image.run[1] = RUN echo upstream-line");
        assertThat(code).as("%s%s", out, err).isZero();
        assertThat(out.toString())
                .as("shown as before a fetch, line by line")
                .contains("setting        + image.run[1] = RUN echo upstream-line")
                .contains("settings       image allowed for uc");
    }

    /** Allows the image lines each text gives the project's repository, as a person's yes does. */
    private static void allowRepositoryImage(
            final PoreloContext context,
            final Path projectFile,
            final String repository,
            final String... texts) {
        final var project = org.fuin.porelo.core.project.ProjectReader.read(projectFile);
        final List<org.fuin.porelo.core.project.file.RepositorySettings.Asked> asked =
                new ArrayList<>();
        for (final String text : texts) {
            try {
                asked.addAll(
                        RepositoryOwnSettings.image(
                                project,
                                null,
                                List.of(
                                        org.fuin.porelo.core.project.RepositoryImageReader.read(
                                                text,
                                                "porelo.yml",
                                                org.fuin.porelo.core.project.SnippetFiles.beside(
                                                        projectFile))),
                                new java.util.LinkedHashMap<>()));
            } catch (org.fuin.porelo.core.project.ProjectException ex) {
                // A file the start refuses is refused before anything is allowed.
            }
        }
        new RepositoryAllowance(context, project.name()).allow(repository, asked);
    }

    @Test
    void aStartRecordsItsChosenBranchForReviewAfterItReturns(@TempDir Path dir) throws Exception {
        // Without a durable choice, the next review silently falls back to main.
        final String file =
                changedRepositoryImage(
                        dir,
                        "image:\n  from: ubuntu:24.04\n",
                        "image:\n  from: ubuntu:24.04\n  run: RUN true\n");
        final Path mirror = dir.resolve("data/porelo/mirrors/uc.git");
        git(dir.resolve("upstream"), "branch", "develop", "main");
        final PoreloContext context = repositoryImageContext(dir);
        final int code =
                execute(
                        context,
                        "task",
                        "start",
                        "--attach",
                        "shell",
                        "--detach",
                        "-p",
                        file,
                        "--from",
                        "develop");
        assertThat(code).as("the selected branch exists: %s", err).isZero();
        assertThat(TaskBase.recorded(context, containerName()))
                .as("review retains the actual starting branch after launch")
                .isEqualTo(new TaskBase("develop", "FROM"));
        Files.delete(
                context.paths().tasks().containerState(containerName()).resolve(TaskBase.FILE));
        new TaskState(context).forget(containerName());
        final var project = GateSupport.byName(context, "uc");
        assertThat(ApprovalBranch.of(context, project, project.ownRepository(), "shell", null))
                .as("pending work keeps its starting branch after the task's state is removed")
                .isEqualTo("develop");
        assertThat(out.toString())
                .as("the person sees the choice before work starts")
                .contains("starts from    develop (named with --from)");
    }

    @Test
    void aWorkspaceThatCannotCheckOutItsStartingBranchDoesNotStartWork(@TempDir Path dir)
            throws Exception {
        // A failed checkout must not start an agent with the wrong files or an empty workspace.
        final String file =
                changedRepositoryImage(
                        dir,
                        "image:\n  from: ubuntu:24.04\n",
                        "image:\n  from: ubuntu:24.04\n  run: RUN true\n");
        final PoreloContext context = repositoryImageContext(dir);
        final Path workspace = Files.createDirectories(dir.resolve("workspace"));
        Files.writeString(workspace.resolve("porelo.yml"), "a file supplied by the image\n");
        final Path script = dir.resolve("checkout.sh");
        Files.writeString(
                script,
                TaskWorkspace.cloneScript()
                        .replace(
                                "cd " + TaskWorkspace.MOUNT + ";",
                                "cd " + shellArgument(workspace.toString()) + ";"));
        final Path refused = dir.resolve("refused-podman");
        Files.writeString(
                refused,
                "#!/bin/sh\nexport PORELO_REMOTE_URL="
                        + shellArgument(dir.resolve("data/porelo/mirrors/uc.git").toString())
                        + "\nexec sh "
                        + shellArgument(script.toString())
                        + "\n");
        refused.toFile().setExecutable(true);
        final String earlier = System.getProperty(org.fuin.porelo.runtime.Podman.NAMED);
        System.setProperty(org.fuin.porelo.runtime.Podman.NAMED, refused.toString());
        try {
            final int code =
                    execute(
                            context,
                            "task",
                            "start",
                            "--attach",
                            "shell",
                            "--detach",
                            "-p",
                            file,
                            "--from",
                            "main");
            assertThat(
                            Files.readString(
                                    context.paths()
                                            .tasks()
                                            .containerState(containerName())
                                            .resolve("workspace.log")))
                    // git's own words follow the machine's language; the file it names does not.
                    .as("the real checkout failed on an image file it would overwrite")
                    .contains("porelo.yml");
            assertThat(code)
                    .as("a selected branch that could not be checked out ends the start")
                    .isNotZero();
            assertThat(execCalls).as("no session begins in a failed checkout").isEmpty();
        } finally {
            if (earlier == null) System.clearProperty(org.fuin.porelo.runtime.Podman.NAMED);
            else System.setProperty(org.fuin.porelo.runtime.Podman.NAMED, earlier);
        }
    }

    @Test
    void anExistingTaskDoesNotTakeItsBranchFromADetachedCaller(@TempDir Path dir) throws Exception {
        // Bringing back existing work must not be changed or refused by the caller's current
        // branch.
        final PoreloContext context = context(dir, true);
        final Path checkout = Files.createDirectories(dir.resolve("checkout"));
        Files.writeString(checkout.resolve("project.yml"), MINIMAL);
        runner.answering("--show-toplevel", checkout.toString());
        runner.failing("symbolic-ref --quiet --short HEAD", 1, "detached");
        runner.answering("ps", RUNNING_SHELL);
        final int code =
                execute(
                        context,
                        "task",
                        "start",
                        "shell",
                        "-p",
                        projectFile(dir, MINIMAL),
                        "--detach");
        assertThat(code).as("the existing task answers as already running: %s", err).isEqualTo(65);
        assertThat(err.toString())
                .as("the caller's detached branch is irrelevant to existing work")
                .doesNotContain("checkout is detached");
    }

    /** Uses real local git for image reads and the fake runtime for containers. */
    private PoreloContext repositoryImageContext(Path dir) throws IOException {
        return context(dir, true);
    }

    @Test
    void aFirstStartNeverClaimsToKeepSettingsFromAnEarlierStart(@TempDir Path dir)
            throws Exception {
        // A first start claiming earlier settings misleads the person about what the task holds.
        final PoreloContext context = repositoryImageContext(dir);
        final String file = changedSignedSettings(context, dir);
        final Path projectFile = dir.resolve("project.yml");
        final var project = org.fuin.porelo.core.project.ProjectReader.read(projectFile);
        final var launch =
                new TaskLaunch(
                        context,
                        new TaskLaunch.Request(
                                "shell",
                                projectFile,
                                null,
                                null,
                                null,
                                8,
                                null,
                                false,
                                false,
                                "deny",
                                true,
                                org.fuin.porelo.wire.TaskMode.SHELL,
                                null,
                                null,
                                null,
                                60,
                                "uc",
                                java.util.Map.of()));
        final var moved =
                TaskLaunch.class.getDeclaredMethod(
                        "settingsMovedOn",
                        Path.class,
                        String.class,
                        org.fuin.porelo.core.project.Repository.class,
                        PrintWriter.class);
        moved.setAccessible(true);
        moved.invoke(
                launch, projectFile, "uc", project.repository("uc"), new PrintWriter(out, true));

        assertThat(out.toString())
                .as("new settings found with no earlier task record")
                .doesNotContain("settings it held before");

        assertThat(execute(context, "task", "start", "--attach", "shell", "--detach", "-p", file))
                .as("a first start succeeds: %s", err)
                .isZero();
        assertThat(out.toString())
                .as("no earlier task held settings")
                .doesNotContain("settings it held before");
    }

    @Test
    void aStartKeepsItsSettingsHashInTheDurableTaskRecord(@TempDir Path dir) throws Exception {
        // Without a durable hash a later start invents what settings the earlier task held.
        final PoreloContext context = repositoryImageContext(dir);
        final String file = changedSignedSettings(context, dir);
        final Path projectFile = dir.resolve("project.yml");
        final var project = org.fuin.porelo.core.project.ProjectReader.read(projectFile);
        final var before =
                RepositoryOwnSettings.ofOrganization(
                        context, projectFile, project.name(), project.repository("uc"));
        assertThat(before).as("settings exist before this start fetches").isNotNull();

        assertThat(execute(context, "task", "start", "--attach", "shell", "--detach", "-p", file))
                .as("the task starts: %s", err)
                .isZero();
        final var record =
                (java.util.Map<?, ?>)
                        org.fuin.porelo.wire.Json.parse(
                                Files.readString(
                                        context.paths()
                                                .tasks()
                                                .taskRecord(containerName())
                                                .resolve("task.json")));
        assertThat(record.get("settingsHash"))
                .as("the settings actually taken before the fetch")
                .isEqualTo(before.hash());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "different,true",
        "same,false",
        "absent,false",
        "empty,false"
    })
    void aStoppedTasksSettingsNoticeNeedsADifferentHashFromItsEarlierStart(
            String hash, boolean notice, @TempDir Path dir) throws Exception {
        // A notice without a saved hash invents history, while hiding a change misleads a restart.
        final PoreloContext context = repositoryImageContext(dir);
        final String file = changedSignedSettings(context, dir);
        final Path projectFile = dir.resolve("project.yml");
        final var project = org.fuin.porelo.core.project.ProjectReader.read(projectFile);
        final String current =
                RepositoryOwnSettings.ofOrganization(
                                context, projectFile, "uc", project.repository("uc"))
                        .hash();
        final Path durable =
                Files.createDirectories(context.paths().tasks().taskRecord("porelo-uc-shell"));
        final var record = new java.util.LinkedHashMap<String, Object>();
        record.put("version", org.fuin.porelo.wire.TaskProfile.VERSION);
        record.put("mode", "shell");
        if (!hash.equals("absent"))
            record.put(
                    "settingsHash",
                    hash.equals("same") ? current : hash.equals("empty") ? "" : "earlier-hash");
        Files.writeString(durable.resolve("task.json"), org.fuin.porelo.wire.Json.write(record));
        Files.writeString(
                durable.resolve(org.fuin.porelo.wire.Sidecar.RECORDED_ID), "0123456789abcdef");
        runner.answering("{{.Id}}", "0123456789abcdef");
        runner.answering(
                "ps", "porelo-uc-shell\tExited (0)\t1700000000\t1700000100\tuc\tguarded\tuc\t\n");

        assertThat(execute(context, "task", "start", "shell", "--detach", "-p", file))
                .as("the stopped task resumes: %s", err)
                .isZero();

        assertThat(out.toString().contains("settings it held before"))
                .as("only a changed recorded hash justifies the notice: %s %s", out, err)
                .isEqualTo(notice);
        if (notice)
            assertThat(out.toString())
                    .as("only a new task takes changed settings")
                    .contains("a new task")
                    .doesNotContain("changed with the fetch", "the next start takes");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void aLegacyTaskStillStartsWhenItsRepositoryWasRemovedFromTheCurrentProject(
            boolean recordedHash, @TempDir Path dir) throws Exception {
        // A diagnostic must not prevent an existing task from resuming its saved workspace.
        final PoreloContext context = context(dir, true);
        projectFile(dir, MINIMAL + "repositories:\n  other: {}\n");
        final Path durable =
                Files.createDirectories(context.paths().tasks().taskRecord("porelo-uc-shell"));
        Files.writeString(
                durable.resolve("task.json"),
                recordedHash
                        ? "{\"version\":10,\"mode\":\"shell\",\"settingsHash\":\"earlier-hash\"}"
                        : "{\"version\":9,\"mode\":\"shell\"}");
        Files.writeString(
                durable.resolve(org.fuin.porelo.wire.Sidecar.RECORDED_ID), "0123456789abcdef");
        runner.answering("{{.Id}}", "0123456789abcdef");
        runner.answering(
                "ps", "porelo-uc-shell\tExited (0)\t1700000000\t1700000100\tuc\tguarded\told\t\n");

        recordedTasks(context);
        final var launch =
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
                                false,
                                false,
                                "deny",
                                true,
                                org.fuin.porelo.wire.TaskMode.SHELL,
                                null,
                                null,
                                null,
                                60,
                                null,
                                java.util.Map.of()));
        final var result =
                launch.startExisting(new PrintWriter(out, true), new PrintWriter(err, true));
        assertThat(result).as("the saved task was found").isNotNull();
        assertThat(result.code()).as("a legacy task still starts: %s", err).isZero();
        assertThat(out.toString())
                .as("no earlier settings were recorded")
                .doesNotContain("settings it held before");
        assertThat(err.toString().contains("cannot compare"))
                .as("only a recorded hash calls for a comparison diagnostic")
                .isEqualTo(recordedHash);
    }

    private String changedSignedSettings(PoreloContext context, Path dir) throws Exception {
        final Path key = dir.resolve("settings-key");
        final Process generated =
                new ProcessBuilder(
                                "ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", key.toString())
                        .start();
        assertThat(generated.waitFor()).as("a signing key for this fixture only").isZero();
        final String publicKey = Files.readString(key.resolveSibling("settings-key.pub")).strip();
        OrganizationProjects.of(context)
                .write(
                        new OrganizationProjects.Placed(
                                "uc",
                                "0".repeat(40),
                                "https://repo.example.org/uc/settings",
                                null,
                                List.of(
                                        new OrganizationProjects.Key(
                                                "developer",
                                                "developer",
                                                publicKey,
                                                java.time.LocalDate.of(2000, 1, 1),
                                                java.time.LocalDate.of(2999, 1, 1),
                                                null)),
                                null,
                                null,
                                null));
        final Path upstream = Files.createDirectories(dir.resolve("upstream"));
        git(upstream, "init", "-q");
        Files.writeString(
                upstream.resolve("porelo.yml"), "egress:\n  domains: [before.example.org]\n");
        git(upstream, "add", "porelo.yml");
        git(
                upstream,
                "-c",
                "gpg.format=ssh",
                "-c",
                "user.signingkey=" + key,
                "commit",
                "-q",
                "-S",
                "-m",
                "before");
        final Path mirror = GateSupport.mirror(context, "uc", "uc");
        Files.createDirectories(mirror.getParent());
        git(dir, "clone", "-q", "--bare", upstream.toString(), mirror.toString());
        Files.writeString(
                upstream.resolve("porelo.yml"), "egress:\n  domains: [after.example.org]\n");
        git(
                upstream,
                "-c",
                "gpg.format=ssh",
                "-c",
                "user.signingkey=" + key,
                "commit",
                "-q",
                "-S",
                "-am",
                "after");
        final String name =
                projectFile(
                        dir,
                        MINIMAL.replace(
                                "  security_class: \"guarded\"",
                                "  security_class: \"guarded\"\n  upstream: \"file://%s\""
                                        .formatted(upstream)));
        final Path placed = context.paths().projects().organizationClone("uc");
        Files.createDirectories(placed);
        Files.copy(dir.resolve("project.yml"), placed.resolve("project.yml"));
        return name;
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
                changedRepositoryImage(
                        dir, "egress: {}\n", "image:\n  from: ubuntu:24.04\n  run: RUN true\n");
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
                        "image:\n  from: ubuntu:24.04\n  run: RUN true\n",
                        "image:\n  from: ubuntu:24.04\n  run: RUN true\n  env: {FROM_UPSTREAM: yes}\n");
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
                  from: "ubuntu:24.04"
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
                  from: "ubuntu:24.04"
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
                  from: "ubuntu:24.04"
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
                        "--env",
                        "BASH_ENV=",
                        "--env",
                        "ENV=",
                        containerName(),
                        "sh",
                        "-c",
                        org.fuin.porelo.runtime.Podman.SESSION_START,
                        "porelo-session");
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
                        new PoreloContext(runner, NoAccount.context().paths(), arguments -> 0)
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
    void aShellTaskWithAModelButNoAgentIsRefusedBeforeAnythingIsMade(@TempDir Path dir)
            throws IOException {

        // No agent would run on it, yet its row would say the task runs on that model.
        final int code =
                execute(
                        context(dir, true),
                        "task",
                        "start",
                        "--attach",
                        "shell",
                        "-p",
                        projectFile(dir, MINIMAL),
                        "--model",
                        "model-a");

        assertThat(code).as("refused: %s", err).isEqualTo(2);
        assertThat(runner.lines())
                .as("before a container is made: %s", runner.lines())
                .noneMatch(line -> line.contains("podman create"));
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
        assertThat(err.toString()).contains("'image' is required");
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

    /**
     * Reserves the task's name for an attempt whose holder has ended: helpers written before the
     * start stand for ones its own reservation launched, never for another's leftovers.
     */
    private static void anEarlierAttemptOfTheTask(PoreloContext context) throws IOException {
        try (var earlier = new TaskReservations(context).start("uc", "shell", "porelo-uc_shell")) {
            assertThat(earlier.attempt()).isNotBlank();
        }
    }

    private String containerName() {
        // One container per project and task, with nothing unique appended. It used to carry the
        // launching process's pid, which is why a task could never be found again by its name.
        // A new task joins project and task with an underscore, which no project or task holds.
        return "porelo-uc_shell";
    }

    @Test
    void stopsTheHelpersWhenTheContainerNeverStarts(@TempDir Path dir) throws Exception {

        // A container that ran is reaped by the poststop hook. One that never started fires no
        // hook, and the vault proxy, gate and watcher launched before it then outlive the run:
        // measured on both test machines, where each failed run stranded three processes holding
        // their sockets, and the next run failed for a reason unrelated to what changed.
        final PoreloContext context = context(dir, true);
        runner.failing("start", 125, "OCI runtime error");
        anEarlierAttemptOfTheTask(context);

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

    private org.fuin.porelo.runtime.ImageSettings recordedFor(
            final PoreloContext context, final String image) throws IOException {
        assertThat(context.paths().tasks().buildContext(image).resolve("Containerfile"))
                .as("what the start said: %s", err)
                .exists();
        final String line =
                Files.readAllLines(
                                context.paths()
                                        .tasks()
                                        .buildContext(image)
                                        .resolve("Containerfile"))
                        .stream()
                        .filter(each -> each.startsWith("LABEL org.fuin.porelo.settings="))
                        .findFirst()
                        .orElseThrow(
                                () -> new AssertionError("no record in what was built: " + err));
        return org.fuin.porelo.runtime.ImageSettings.parse(
                        line.substring(line.indexOf('"') + 1, line.length() - 1)
                                .replaceAll("\\\\(.)", "$1"))
                .orElseThrow();
    }

    @Test
    void theImageAStartBuildsRecordsItsSettings(@TempDir Path dir) throws IOException {
        // The route every task takes: a record only a prepare wrote would leave them all unknown.
        final PoreloContext context = context(dir, true);

        execute(
                context,
                "task",
                "start",
                "--attach",
                "shell",
                "-p",
                projectFile(dir, MINIMAL),
                "--no-gate");

        final var settings = recordedFor(context, "uc");
        assertThat(settings.baseImage()).isEqualTo("ubuntu:24.04");
        assertThat(settings.baseImageSource()).isEqualTo("project.yml");
        assertThat(settings.securityClass()).isEqualTo("guarded");
    }

    @Test
    void theImageAStartBuildsRecordsTheAgentItCarries(@TempDir Path dir) throws IOException {
        final char[] passphrase = "test-passphrase".toCharArray();
        final PoreloContext context = countingContext(dir).asking(shown -> passphrase.clone());
        try {
            Files.createDirectories(context.vault().path().getParent());
            context.vault().write(java.util.Map.of(), passphrase.clone());
            context.openerAsking(false, new PrintWriter(new StringWriter()));

            execute(
                    context,
                    "task",
                    "start",
                    "-p",
                    projectFile(dir, MINIMAL),
                    "--agent",
                    "preview",
                    "--no-gate");

            assertThat(recordedFor(context, "uc").agent()).isEqualTo("preview");
        } finally {
            PoreloContext.forgetAsked();
        }
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

    @ParameterizedTest
    @ValueSource(
            strings = {
                "--repository=uc",
                "--branch=porelo/reading",
                "--from=main",
                "--no-gate",
                "--upstream=https://forge.example/acme/shop.git"
            })
    void readingEveryRepositoryRefusesWhatNamesOne(String option, @TempDir Path dir)
            throws IOException {

        final int code =
                execute(
                        context(dir, true),
                        "task",
                        "start",
                        "--all-repositories",
                        option,
                        "--attach",
                        "shell",
                        "-p",
                        projectFile(dir, MINIMAL));

        assertThat(code).as("a usage error: %s", err).isEqualTo(64);
        assertThat(err.toString())
                .contains("--all-repositories reads every repository of the project")
                .contains(option.split("=")[0]);
        assertThat(runner.lines())
                .as("refused before anything is made")
                .noneMatch(line -> line.contains(" create "));
    }

    @Test
    void aTaskThatReadsItsWholeProjectIsStoppedAndKeptAtItsEndWhateverItHolds(@TempDir Path dir)
            throws Exception {

        final PoreloContext prepared = context(dir, true);
        // The project's own repository, with a main for the reader to be given.
        final String project = projectFile(dir, MINIMAL);
        final Path upstream = Files.createDirectories(dir.resolve("upstream"));
        final java.util.Map<String, String> git =
                java.util.Map.of("GIT_CONFIG_GLOBAL", "/dev/null", "GIT_CONFIG_NOSYSTEM", "1");
        final var real = new org.fuin.porelo.core.process.ProcessCommandRunner();
        real.runOrFail(
                org.fuin.porelo.core.process.Command.of(
                                "git", "init", "-q", "-b", "main", upstream.toString())
                        .withEnvironment(git));
        Files.writeString(upstream.resolve("README.md"), "the project\n");
        real.runOrFail(
                org.fuin.porelo.core.process.Command.of(
                                "git",
                                "-C",
                                upstream.toString(),
                                "-c",
                                "user.name=T",
                                "-c",
                                "user.email=t@example.com",
                                "commit",
                                "-q",
                                "--allow-empty",
                                "-m",
                                "initial")
                        .withEnvironment(git));
        final Path mirror =
                GateSupport.mirror(
                        prepared,
                        org.fuin.porelo.core.project.ProjectReader.read(dir.resolve("project.yml")),
                        org.fuin.porelo.core.project.ProjectReader.read(dir.resolve("project.yml"))
                                .ownRepository());
        real.runOrFail(
                org.fuin.porelo.core.process.Command.of(
                                "git",
                                "clone",
                                "-q",
                                "--bare",
                                upstream.toString(),
                                mirror.toString())
                        .withEnvironment(git));
        runner.answering("{{.State.Pid}}", "4711\n");
        runner.answering("container inspect", "c0ffee\n");
        // What the census says of a workspace holding work: a task that only read holds none.
        runner.answering("exec", "1 2\n");

        execute(
                prepared,
                "task",
                "start",
                "--all-repositories",
                "--attach",
                "shell",
                "-p",
                project);

        assertThat(runner.lines())
                .as("its repositories are its workspace, read only: %s", err)
                .anyMatch(line -> line.contains(":/workspace:ro,Z"));
        assertThat(runner.lines())
                .as("stopped and kept at its end, since nobody asked for --rm: %s", out)
                .anyMatch(line -> line.contains("stop --time"))
                .noneMatch(line -> line.contains("rm --force"));
        assertThat(out.toString()).doesNotContain("never reached the gate");
        assertThat(out.toString())
                .as("told so when it is attached")
                .contains("The container is kept when you leave")
                .doesNotContain("Leaving the shell removes the container");
        final Path read = prepared.paths().tasks().repositories("any").getParent();
        assertThat(read).as("it was given what it read").exists();
        try (java.util.stream.Stream<Path> left = Files.list(read)) {
            assertThat(left.toList()).as("what it read stays until it is removed").isNotEmpty();
        }
    }

    /** Gives the project's own repository a mirror whose main a reading task is given. */
    private static void ownMain(PoreloContext context, Path dir) throws IOException {
        final Path upstream = Files.createDirectories(dir.resolve("upstream"));
        git(upstream, "init", "-q", "-b", "main");
        Files.writeString(upstream.resolve("README.md"), "the project\n");
        git(upstream, "add", "README.md");
        git(
                upstream,
                "-c",
                "user.name=T",
                "-c",
                "user.email=t@example.com",
                "commit",
                "-q",
                "-m",
                "initial");
        final var project =
                org.fuin.porelo.core.project.ProjectReader.read(dir.resolve("project.yml"));
        final Path mirror = GateSupport.mirror(context, project, project.ownRepository());
        Files.createDirectories(mirror.getParent());
        git(dir, "clone", "-q", "--bare", upstream.toString(), mirror.toString());
    }

    /** Starts at a terminal or not, naming no repository, as a person brings a task back. */
    private int startNamingNoRepository(PoreloContext context, boolean terminal, String... args) {
        recordedTasks(context);
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
        final List<String> all = new ArrayList<>(List.of("task", "start", "shell"));
        all.addAll(List.of(args));
        return cmd.execute(all.toArray(new String[0]));
    }

    private static final String STOPPED_SHELL =
            RUNNING_SHELL.replace("Up 4 minutes", "Exited (0) 4 minutes ago");

    @Test
    void aTaskOnOneRepositoryIsNotBroughtBackToReadThemAll(@TempDir Path dir) throws IOException {
        // Brought back, it would read with its gate, its token and its certificate still in it.
        final PoreloContext context = context(dir, true);
        runner.answering("ps", STOPPED_SHELL);

        final int code =
                execute(
                        context,
                        "task",
                        "start",
                        "shell",
                        "--all-repositories",
                        "-p",
                        projectFile(dir, MINIMAL));

        assertThat(code).as("a usage error: %s", err).isEqualTo(64);
        assertThat(err.toString())
                .contains("porelo-uc-shell was made to work on one repository and stays so");
        assertThat(runner.lines())
                .as("nothing is brought back or made")
                .noneMatch(line -> line.contains("podman start") || line.contains("podman create"));
    }

    @Test
    void aTaskThatReadsItsProjectIsNotBroughtBackForOneRepository(@TempDir Path dir)
            throws IOException {
        final PoreloContext context = context(dir, true);
        Files.createDirectories(
                context.paths().tasks().repositories("porelo-uc-shell").resolve("uc"));
        runner.answering("ps", STOPPED_SHELL);

        final int code =
                execute(
                        context,
                        "task",
                        "start",
                        "shell",
                        "--repository",
                        "uc",
                        "-p",
                        projectFile(dir, MINIMAL));

        assertThat(code).as("a usage error: %s", err).isEqualTo(64);
        assertThat(err.toString())
                .contains("porelo-uc-shell was made to read every repository and stays so")
                .contains("without --repository");
        assertThat(runner.lines())
                .noneMatch(line -> line.contains("podman start") || line.contains("podman create"));
    }

    @Test
    void aRunningTaskThatReadsItsProjectIsNotGoneIntoWithTheOptionThatMadeIt(@TempDir Path dir)
            throws IOException {
        final PoreloContext context = context(dir, true);
        Files.createDirectories(
                context.paths().tasks().repositories("porelo-uc-shell").resolve("uc"));
        runner.answering("ps", RUNNING_SHELL);

        assertThat(
                        startNamingNoRepository(
                                context,
                                true,
                                "--all-repositories",
                                "-p",
                                projectFile(dir, MINIMAL)))
                .as("said: %s", err)
                .isEqualTo(64);
        assertThat(err.toString())
                .contains(
                        "porelo-uc-shell is already running; --all-repositories takes effect only");
        assertThat(execCalls).as("nothing gone into").isEmpty();
    }

    @Test
    void aTaskThatReadsItsProjectGoneIntoAgainIsStoppedAndKeptWhenThePersonLeaves(@TempDir Path dir)
            throws IOException {
        // Left running after its second session ended, it held a whole project's trees until
        // somebody removed it by hand; removed instead, a discussion's idea went with it.
        final PoreloContext context = context(dir, true);
        Files.createDirectories(
                context.paths().tasks().repositories("porelo-uc-shell").resolve("uc"));
        runner.answering("ps", RUNNING_SHELL);
        runner.answering("{{.State.Pid}}", "4711\n");

        final int code = startNamingNoRepository(context, true, "-p", projectFile(dir, MINIMAL));

        assertThat(code).as("said: %s / %s", err, out).isZero();
        assertThat(execCalls).as("gone into").hasSize(1);
        assertThat(runner.lines())
                .as("stopped once its session ended: %s", err)
                .anyMatch(line -> line.contains("stop --time"))
                .noneMatch(line -> line.contains("rm --force"));
        assertThat(out.toString()).contains("kept      porelo-uc-shell");
        assertThat(context.paths().tasks().repositories("porelo-uc-shell"))
                .as("what it read stays with it until it is removed")
                .exists();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 3})
    void aTaskThatReadsItsProjectIsStoppedAndKeptAtTheEndOfItsPromptRunHoweverItEnded(
            int exit, @TempDir Path dir) throws IOException {
        // Removed at its end, its Result went with it, and a discussion's idea never reached the
        // planner; left running, it held its trees with nobody watching.
        final PoreloContext context = modelContext(dir, false);
        final String project = projectFile(dir, MINIMAL);
        ownMain(context, dir);
        final Path podman = dir.resolve("echoing-podman");
        Files.writeString(podman, "#!/bin/sh\necho \"$@\"\nexit " + exit + "\n");
        Files.setPosixFilePermissions(
                podman, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        runner.answering("{{.State.Pid}}", "4711\n");
        runner.answering("container inspect", "c0ffee\n");
        final String standIn = System.getProperty(org.fuin.porelo.runtime.Podman.NAMED);
        System.setProperty(org.fuin.porelo.runtime.Podman.NAMED, podman.toString());
        final int code;
        try {
            code =
                    execute(
                            context,
                            "task",
                            "start",
                            "--all-repositories",
                            "--agent",
                            "preview",
                            "--prompt",
                            "read it",
                            "-p",
                            project);
        } finally {
            System.setProperty(org.fuin.porelo.runtime.Podman.NAMED, standIn);
        }

        assertThat(code).as("the run's own verdict: %s", err).isEqualTo(exit == 0 ? 0 : 70);
        assertThat(runner.lines())
                .as("stopped and kept at the end of its run: %s", out)
                .anyMatch(line -> line.contains("stop --time"))
                .noneMatch(line -> line.contains("rm --force"));
        final Path read = context.paths().tasks().repositories("any").getParent();
        try (java.util.stream.Stream<Path> left = Files.list(read)) {
            assertThat(left.toList()).as("what it read stays until it is removed").isNotEmpty();
        }
    }

    @Test
    void aTaskThatReadsItsProjectStartedWithRmIsRemovedAtTheEndOfItsPromptRun(@TempDir Path dir)
            throws IOException {
        // As any task started with --rm: nothing of it stays.
        final PoreloContext context = modelContext(dir, false);
        final String project = projectFile(dir, MINIMAL);
        ownMain(context, dir);
        final Path podman = dir.resolve("echoing-podman");
        Files.writeString(podman, "#!/bin/sh\necho \"$@\"\nexit 0\n");
        Files.setPosixFilePermissions(
                podman, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        runner.answering("{{.State.Pid}}", "4711\n");
        runner.answering("container inspect", "c0ffee\n");
        final String standIn = System.getProperty(org.fuin.porelo.runtime.Podman.NAMED);
        System.setProperty(org.fuin.porelo.runtime.Podman.NAMED, podman.toString());
        final int code;
        try {
            code =
                    execute(
                            context,
                            "task",
                            "start",
                            "--all-repositories",
                            "--rm",
                            "--agent",
                            "preview",
                            "--prompt",
                            "read it",
                            "-p",
                            project);
        } finally {
            System.setProperty(org.fuin.porelo.runtime.Podman.NAMED, standIn);
        }

        assertThat(code).as("the run's own verdict: %s", err).isZero();
        assertThat(runner.lines())
                .as("removed at the end of its run: %s", out)
                .anyMatch(line -> line.contains("rm --force"));
        final Path read = context.paths().tasks().repositories("any").getParent();
        try (java.util.stream.Stream<Path> left = Files.list(read)) {
            assertThat(left.toList()).as("what it read goes with it").isEmpty();
        }
    }

    @Test
    void anAgentsFileForTheWorkspaceIsNotWrittenIntoWhatATaskReads(@TempDir Path dir)
            throws IOException {
        // Copied into a workspace mounted read only, it failed, and an attended agent's start
        // with it.
        final PoreloContext context = countingContext(dir);
        Files.writeString(context.paths().xdg().data().resolve("agents/preview.yaml.places"), "");
        final String project = projectFile(dir, MINIMAL);
        ownMain(context, dir);
        final Path said = dir.resolve("podman.said");
        final Path podman = dir.resolve("logging-podman");
        Files.writeString(
                podman,
                "#!/bin/sh\nprintf '%s\\n' \"$*\" >> " + shellArgument(said.toString()) + "\n");
        Files.setPosixFilePermissions(
                podman, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
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
                            "--all-repositories",
                            "--attach",
                            "agent",
                            "-p",
                            project,
                            "--agent",
                            "preview");
        } finally {
            System.setProperty(org.fuin.porelo.runtime.Podman.NAMED, standIn);
            System.setProperty(PoreloBinary.NAMED, ownPorelo);
        }

        assertThat(started).as("started: %s", err).isZero();
        assertThat(out.toString())
                .as("said once")
                .containsOnlyOnce("skipped   1 file(s) the agent wants in /workspace");
        final List<String> lines = Files.exists(said) ? Files.readAllLines(said) : List.of();
        assertThat(indexOf(lines, "tee " + TaskWorkspace.MOUNT + "/.preview/settings.json"))
                .as("nothing is written into what it reads: %s", lines)
                .isNegative();
        assertThat(indexOf(lines, "tee /home/agent/.preview.json"))
                .as("the agent's own file still is: %s", lines)
                .isNotNegative();
    }

    @Test
    void aStartThatReadsItsProjectIsCheckedForItsOwnRepositorysSettingsAsItsStartIs(
            @TempDir Path dir) throws Exception {
        // Asked with no repository, the check skipped what the start then refused.
        final String file =
                unallowedRepositoryImage(
                        dir, "image:\n  from: ubuntu:24.04\n  run: RUN echo upstream-line\n");
        final PoreloContext context = previewContext(dir, true);
        previewAgent(context, "preview");
        final Path projectFile = ProjectSource.resolve(context, file).file();

        final StartCheck.Result asked =
                StartCheck.check(
                        context,
                        projectFile,
                        "all",
                        "preview",
                        null,
                        null,
                        null,
                        true,
                        java.util.Map.of(),
                        java.util.Map.of(),
                        null,
                        null,
                        true);

        assertThat(asked.outcome())
                .as("CanStart refuses it as Start does: %s", asked.detail())
                .isEqualTo(StartCheck.Outcome.SETTINGS_NOT_ALLOWED);
        assertThat(asked.settingsToAllow())
                .extracting(each -> each.get("key") + " = " + each.get("value"))
                .contains("image.run[1] = RUN echo upstream-line");
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

    /** Every way out of a new task's attach resets before the detached-task message. */
    @ParameterizedTest
    @ValueSource(strings = {"normal", "nonzero", "exception"})
    void resetsBeforeSayingThatTheDetachedTaskIsStillRunning(String ending, @TempDir Path dir)
            throws IOException {
        final PoreloContext prepared = context(dir, true);
        runner.failing("test -f", 1, "the session has not ended");
        runner.answering("stty -g", "saved-settings\n");
        execExit = "nonzero".equals(ending) ? 125 : 0;
        execDies = "exception".equals(ending);

        final int code =
                startAt(prepared, true, "--attach", "shell", "-p", projectFile(dir, MINIMAL));

        assertThat(code)
                .as("cleanup preserves a returned podman exit status")
                .isEqualTo(execDies ? 70 : execExit);
        assertThat(execCalls).as("the new task's session was attached").hasSize(1);
        assertThat(runner.only("stty \"$1\"").arguments())
                .as("start restores the saved settings")
                .endsWith("saved-settings");
        assertThat(out.toString())
                .as("terminal cleanup finishes before teardown reports the detached task")
                .contains(
                        TerminalAttachTest.RESETS
                                + System.lineSeparator()
                                + "detached  "
                                + containerName()
                                + " is still running");
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

    @ParameterizedTest
    @ValueSource(strings = {"backend", "porelo-backend"})
    void aStartByKeyOrFullNameRecordsTheSameRepository(String named, @TempDir Path dir)
            throws IOException {
        final int code =
                execute(
                        context(dir, true),
                        "task",
                        "start",
                        "shell",
                        "--attach",
                        "shell",
                        "--detach",
                        "-p",
                        projectFile(
                                dir,
                                TWO_REPOSITORIES
                                        + "    central:"
                                        + " https://forge.example.org/o/porelo-backend.git\n"),
                        "-r",
                        named);

        assertThat(code).as("either name must start the same task: %s", err).isZero();
        assertThat(runner.lines())
                .as("the durable label must use the repository key whichever name was given")
                .anyMatch(
                        line ->
                                line.contains("create")
                                        && line.contains(
                                                "--label org.fuin.porelo.repository=backend"));
    }

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
    void aTaskIsToldWhereItsReadingsGo(@TempDir Path dir) throws IOException {

        // So the agent and the planner read one value: a reading writes its findings there.
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
                        readings:
                          directory: "doc/readings"
                        """));

        assertThat(runner.only("create").environment())
                .containsEntry(TaskLaunch.READINGS_DIRECTORY, "doc/readings");
        assertThat(runner.lines())
                .as("the name alone on the command line")
                .anyMatch(
                        line ->
                                line.contains("create")
                                        && line.contains(
                                                "--env " + TaskLaunch.READINGS_DIRECTORY + " "));
    }

    @Test
    void aTaskWhoseProjectNamesNoReadingsDirectoryIsToldTheDefault(@TempDir Path dir)
            throws IOException {
        execute(
                context(dir, true),
                "task",
                "start",
                "--attach",
                "shell",
                "--detach",
                "-p",
                projectFile(dir, MINIMAL));

        assertThat(runner.only("create").environment())
                .containsEntry(TaskLaunch.READINGS_DIRECTORY, "readings");
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
    void aPlacedProjectsJudgedFileWinsOverTheOneInTheDirectory(@TempDir Path dir)
            throws IOException {

        // The measurement that matters: start a task from a directory holding a DIFFERENT
        // project.yml and show that the directory's file had no effect. A start judges a
        // commit's signature against the organization's keys, and reading anything else afterwards
        // throws that judging away.
        final PoreloContext context = context(dir, true);
        PlacedFixture.record(context, "uc");
        final Path clone = context.paths().projects().organizationClone("uc");
        Files.createDirectories(clone);
        Files.writeString(
                clone.resolve("project.yml"),
                MINIMAL.replace("from: \"ubuntu:24.04\"", "from: \"ubuntu:22.04\""));

        // The directory says 24.04; the verified clone says 22.04.
        execute(context, "task", "start", "-p", projectFile(dir, MINIMAL), "--dry-run");

        assertThat(out.toString()).contains("base image     ubuntu:22.04");
        // And it says where that came from, so nobody wonders why the file they are looking at is
        // not the one being used.
        assertThat(out.toString()).contains("c0").contains("the organization's, judged");
    }

    @Test
    void aPlacedProjectWithNoFileRefusesRatherThanUsingTheLocalFile(@TempDir Path dir)
            throws IOException {

        final PoreloContext context = context(dir, true);
        // Placed, and its clone gone.
        PlacedFixture.record(context, "uc");

        final int code =
                execute(context, "task", "start", "-p", projectFile(dir, MINIMAL), "--dry-run");

        // Falling back would run the very thing the machine declined to apply, and would make a
        // refusal look as though it had no effect.
        assertThat(code).isEqualTo(2);
        assertThat(err.toString()).contains("no project 'uc' here");
    }

    @Test
    void labelsTheContainerWithTheCommitItsConfigurationWasVerifiedAt(@TempDir Path dir)
            throws IOException {

        final PoreloContext context = context(dir, true);
        PlacedFixture.record(context, "uc");
        final Path clone = context.paths().projects().organizationClone("uc");
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
                                        && line.contains("--label org.fuin.porelo.commit=c0"));
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
        assertThat(onAHost(dir, "porelo-uc_fix", "-p", "uc", "--host=a@h"))
                .isEqualTo("64 porelo: --host needs the task's own name, not its container's");
        assertThat(onAHost(dir, "porelo_fix", "-p", "porelo", "--host=a@h"))
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

    /** A project whose build tool reaches one repository, kept for its adapter as written. */
    private static final String WITH_TOOL_REPOSITORY =
            """
            project:
              name: "uc"
              security_class: "guarded"
            image:
              from: "ubuntu:24.04"
            egress:
              domains: ["repo.example.org"]
            tool_repositories:
              - ecosystem: maven
                name: corp
                url: "https://repo.example.org/maven/"
            extensions:
              maven:
                mirrorOf:
                  corp: "*"
            """;

    /** Installs an adapter that describes itself, which the detector given answers for. */
    private static void installedAdapter(final PoreloContext context, final String name)
            throws IOException {
        final Path adapter =
                Files.createDirectories(context.paths().ecosystemDirectories().getFirst())
                        .resolve(name);
        Files.writeString(
                adapter,
                "#!/bin/sh\nprintf '{\"protocolVersion\":1,\"ecosystem\":\"" + name + "\"}'\n");
        adapter.toFile().setExecutable(true);
    }

    /** Starts the task {@code shell} of {@code uc} with the build-tool adapters answered so. */
    private int startWithAdapters(
            final PoreloContext context, final EcosystemDetector adapters, final String... args) {
        recordedTasks(context);
        final CommandLine cmd = PoreloCli.commandLine(context);
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        ((TaskRunCommand)
                                cmd.getSubcommands()
                                        .get("task")
                                        .getSubcommands()
                                        .get("start")
                                        .getCommand())
                        .detector =
                adapters;
        final List<String> all = new ArrayList<>(List.of("task", "start", "shell"));
        all.addAll(List.of(args));
        all.addAll(List.of("--repository", "uc"));
        return cmd.execute(all.toArray(new String[0]));
    }

    @Test
    void aStartPlacesTheSettingsTheToolsAdapterWritesInTheTaskUsersHome(@TempDir Path dir)
            throws IOException {

        // The adapter is asked with the project's repository and the block it keeps for it, and
        // what it answers lands in the task user's home, owner-only where it says so.
        final PoreloContext context = countingContext(dir);
        installedAdapter(context, "maven");
        final List<String> asked = new ArrayList<>();
        final EcosystemDetector adapters =
                new EcosystemDetector() {
                    @Override
                    public org.fuin.porelo.ecosystem.api.Detection detect(
                            final org.fuin.porelo.ecosystem.api.InstalledEcosystems.Description
                                    plugin,
                            final Path checkout,
                            final Path home) {
                        throw new AssertionError("a start with a project file detects nothing");
                    }

                    @Override
                    public List<org.fuin.porelo.ecosystem.api.File> configure(
                            final org.fuin.porelo.ecosystem.api.InstalledEcosystems.Description
                                    plugin,
                            final List<org.fuin.porelo.ecosystem.api.Repository> repositories,
                            final org.fuin.porelo.ecosystem.api.Extension extension) {
                        asked.add(plugin.name() + " " + repositories + " " + extension.values());
                        return List.of(
                                new org.fuin.porelo.ecosystem.api.File(
                                        ".m2/settings.xml", "<settings/>", true));
                    }
                };
        final Path said = dir.resolve("podman.said");
        final Path podman = dir.resolve("logging-podman");
        Files.writeString(
                podman,
                "#!/bin/sh\nprintf '%s\\n' \"$*\" >> " + shellArgument(said.toString()) + "\n");
        Files.setPosixFilePermissions(
                podman, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        // A start waits for its gate: the stand-in records its pid as the gate does.
        final Path helpers = HelperStandIn.write(dir.resolve("helpers"), true);
        final String standIn = System.getProperty(org.fuin.porelo.runtime.Podman.NAMED);
        final String ownPorelo = System.getProperty(PoreloBinary.NAMED);
        System.setProperty(org.fuin.porelo.runtime.Podman.NAMED, podman.toString());
        System.setProperty(PoreloBinary.NAMED, helpers.toString());
        final int started;
        try {
            projectFile(dir, WITH_TOOL_REPOSITORY);
            started = startWithAdapters(context, adapters, "-p", "uc", "--agent", "preview");
        } finally {
            System.setProperty(org.fuin.porelo.runtime.Podman.NAMED, standIn);
            System.setProperty(PoreloBinary.NAMED, ownPorelo);
            HelperStandIn.stop(helpers.getParent());
        }

        assertThat(started).as("started: %s", err).isZero();
        assertThat(asked)
                .as("the adapter is asked once, with the URL as written and its block")
                .containsExactly(
                        "maven [Repository[name=corp, url=https://repo.example.org/maven/]]"
                                + " {mirrorOf={corp=*}}");
        final List<String> lines = Files.readAllLines(said);
        assertThat(lines)
                .as("its file is written in the task user's home: %s", lines)
                .anyMatch(line -> line.endsWith(" tee /home/agent/.m2/settings.xml"));
        assertThat(lines)
                .as("for that user alone: %s", lines)
                .anyMatch(line -> line.endsWith(" chmod 600 /home/agent/.m2/settings.xml"));
    }

    @Test
    void anAdapterThatRefusesStopsTheStartBeforeAnythingIsMadeInPorelosOwnWords(@TempDir Path dir)
            throws IOException {

        // Its words are a stranger's: the refusal names the adapter and what to do instead.
        final PoreloContext context = countingContext(dir);
        installedAdapter(context, "maven");
        final EcosystemDetector adapters =
                new EcosystemDetector() {
                    @Override
                    public org.fuin.porelo.ecosystem.api.Detection detect(
                            final org.fuin.porelo.ecosystem.api.InstalledEcosystems.Description
                                    plugin,
                            final Path checkout,
                            final Path home) {
                        throw new AssertionError("a start with a project file detects nothing");
                    }

                    @Override
                    public List<org.fuin.porelo.ecosystem.api.File> configure(
                            final org.fuin.porelo.ecosystem.api.InstalledEcosystems.Description
                                    plugin,
                            final List<org.fuin.porelo.ecosystem.api.Repository> repositories,
                            final org.fuin.porelo.ecosystem.api.Extension extension) {
                        throw new org.fuin.porelo.ecosystem.api.EcosystemRefused(
                                org.fuin.porelo.ecosystem.api.EcosystemRefused.Reason.UNREADABLE,
                                "the adapter's own words");
                    }
                };
        projectFile(dir, WITH_TOOL_REPOSITORY);

        final int started = startWithAdapters(context, adapters, "-p", "uc", "--agent", "preview");

        assertThat(started).as("refused: %s", err).isEqualTo(69);
        assertThat(err.toString())
                .as("the refusal names the adapter and what to do, never its words")
                .contains("'maven'", "extensions.maven", "nothing was created")
                .doesNotContain("the adapter's own words");
        assertThat(runner.lines())
                .as("no container is made for it")
                .noneMatch(line -> line.contains(" create ") || line.contains(" build "));
        assertThat(context.paths().messaging().mailbox("porelo-uc-shell"))
                .as("nor is its conversation enrolled")
                .doesNotExist();
    }
}
