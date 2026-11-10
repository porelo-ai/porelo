package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.clearance.ClearanceHub;
import org.fuin.porelo.clearance.ClearanceService;
import org.fuin.porelo.clearance.Verdict;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link PoreloDaemon}.
 *
 * <p>Driven over a real unix socket with the real client rather than by calling the methods
 * directly: what is being checked is the wire, and a call that never crosses it proves nothing
 * about it.
 */
class PoreloDaemonTest {

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private final org.fuin.porelo.core.process.ProcessCommandRunner git =
            new org.fuin.porelo.core.process.ProcessCommandRunner();

    /** Written into and read back out of a vault that only exists for the length of one test. */
    private static final char[] VAULT_PASSPHRASE = "correct horse battery staple".toCharArray();

    private PoreloContext context(Path dir) {
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
        return new PoreloContext(
                routed(new PoreloPaths(xdg, dir.resolve("bin"))),
                new PoreloPaths(xdg, dir.resolve("bin")),
                arguments -> 0);
    }

    /**
     * The fake runner, except for the gate's own Git: it runs unattended through the context and
     * reaches its real mirrors, as it did through its own runner. Everything else stays fake.
     */
    private org.fuin.porelo.core.process.CommandRunner routed(PoreloPaths paths) {
        final String mirrors = paths.xdg().data().resolve("mirrors") + "/";
        return command ->
                command.program().equals("git")
                                && command.arguments().contains("credential.helper=")
                                && command.arguments().stream()
                                        .anyMatch(argument -> argument.startsWith(mirrors))
                        ? git.run(command)
                        : runner.run(command);
    }

    /** Runs the server on its own thread and hands the socket to the body. */
    private void serving(Path dir, ThrowingConsumer body) throws Exception {
        servingContext(context(dir), dir, body);
    }

    /**
     * Runs the server on a context the caller prepared, for a test that has to set up what the
     * daemon will be asked about before it starts.
     */
    private void servingContext(PoreloContext context, Path dir, ThrowingConsumer body)
            throws Exception {
        final Path socket = dir.resolve("porelod.sock");
        try (VarlinkServer server = PoreloDaemon.serving(context, socket)) {
            final Thread thread = Thread.ofVirtual().start(server);
            try {
                body.accept(socket);
            } finally {
                server.close();
                thread.join(java.time.Duration.ofSeconds(5));
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingConsumer {
        void accept(Path socket) throws Exception;
    }

    /** A context whose hooks are installed, which Start insists on before it looks at a task. */
    private PoreloContext hooked(Path dir) throws IOException {
        final PoreloContext context = context(dir);
        Files.createDirectories(dir.resolve("bin"));
        for (final String name :
                List.of("porelo-hook-nft", "porelo-hook-supervisor", "porelo-hook-reader")) {
            final Path binary = dir.resolve("bin").resolve(name);
            Files.writeString(binary, "#!/bin/sh\n");
            binary.toFile().setExecutable(true);
        }
        new org.fuin.porelo.runtime.HookInstaller(
                        context.paths().hooksDirectory(), context.paths().binaryDirectory())
                .install();
        return context;
    }

    /**
     * Writes a project file and records where it is.
     *
     * <p>Calls carry a project NAME now - a client cannot see this machine's filesystem and has no
     * path to invent - so a fixture has to make the machine able to resolve that name. {@link
     * #PROJECT} is what the calls send.
     */
    private Path projectFile(Path dir) throws IOException {
        final Path file = dir.resolve("project.yml");
        Files.writeString(
                file,
                """
                project:
                  name: "uc"
                  security_class: "guarded"
                image:
                  from: "ubuntu:24.04"
                """);
        Files.createDirectories(dir.resolve("data/porelo/projects"));
        Files.writeString(
                dir.resolve("data/porelo/projects").resolve(PROJECT), file.toAbsolutePath() + "\n");
        return file;
    }

    /** The name the fixture's project answers to. */
    private static final String PROJECT = "uc";

    /**
     * Records where a project file is, so the machine can resolve its name.
     *
     * @param dir The test's directory.
     * @param file The file that was written.
     * @return The same file, so it can be used inline.
     */
    private Path record(Path dir, Path file) throws IOException {
        Files.createDirectories(dir.resolve("data/porelo/projects"));
        Files.writeString(
                dir.resolve("data/porelo/projects").resolve(PROJECT), file.toAbsolutePath() + "\n");
        return file;
    }

    @Test
    void certifyingAStartNobodyWaitsForIsItsOwnError(@TempDir Path dir) throws Exception {
        final PoreloContext context = hooked(dir);
        servingContext(
                context,
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        assertThatThrownBy(
                                        () ->
                                                client.call(
                                                        PoreloDaemon.INTERFACE + ".Certify",
                                                        Map.of(
                                                                "start",
                                                                "no such start",
                                                                "refused",
                                                                "no")))
                                .hasMessageContaining("NoSuchStart");
                        assertThatThrownBy(
                                        () ->
                                                client.call(
                                                        PoreloDaemon.INTERFACE + ".Certify",
                                                        Map.of("start", "no such start")))
                                .hasMessageContaining("takes a certificate");
                    }
                });
    }

    @Test
    void startBringsBackAStoppedTaskRatherThanCreatingIt(@TempDir Path dir) throws Exception {

        // Reported from the interface: Start on a stopped task went down the path that creates a
        // task, asked which agent to install and answered with an exception's class name, while
        // 'porelo task start' on the same task said why it could not come back. This one has no
        // state directory, so it was started before the machine restarted.
        final PoreloContext context = hooked(dir);
        final Path project = projectFile(dir);
        runner.answering(
                "container inspect",
                "c0ffee0000000000000000000000000000000000000000000000000000000000\n");
        runner.answering(
                "ps",
                "porelo-uc-shell\tExited (143) 1 day ago\t1700000000\t1700000100\tuc\tguarded\tuc\t\tc0ffee0000000000000000000000000000000000000000000000000000000000\n");
        // As a real start leaves it: the id recorded where it is kept across a restart.
        org.fuin.porelo.wire.Sidecar.recordId(
                context.paths().tasks().taskRecord("porelo-uc-shell"),
                "c0ffee0000000000000000000000000000000000000000000000000000000000");

        servingContext(
                context,
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".Start",
                                        Map.of("project", PROJECT, "task", "shell"));

                        assertThat(((Number) reply.get("exitCode")).intValue()).isEqualTo(69);
                        assertThat(reply).containsEntry("container", "porelo-uc-shell");
                        // The refusal named on the reply, as the contract says, not only in the
                        // text.
                        assertThat(reply).containsEntry("action", "PREDATES_RESTART");
                        @SuppressWarnings("unchecked")
                        final List<String> output = (List<String>) reply.get("output");
                        assertThat(String.join("\n", output))
                                .contains("before this machine restarted")
                                .contains("podman cp porelo-uc-shell:/workspace")
                                .doesNotContain("--agent is required")
                                .doesNotContain("Exception");
                    }
                });
        assertThat(runner.lines())
                .as("nothing is built for a task that already exists")
                .noneMatch(line -> line.startsWith("podman build"));
    }

    @Test
    void startTakesAContainerNameAsTheTaskItNames(@TempDir Path dir) throws Exception {

        // Reported on the VM: 'start porelo-utils4j-shell' for a stopped task created a second
        // task, porelo-utils4j-porelo-utils4j-shell, with an empty workspace, while the stopped one
        // kept the work. The container name has to reach the task it names.
        final PoreloContext context = hooked(dir);
        final Path project = projectFile(dir);
        runner.answering(
                "container inspect",
                "c0ffee0000000000000000000000000000000000000000000000000000000000\n");
        runner.answering(
                "ps",
                "porelo-uc-shell\tExited (143) 1 day ago\t1700000000\t1700000100\tuc\tguarded\tuc\t\tc0ffee0000000000000000000000000000000000000000000000000000000000\n");
        // As a real start leaves it: the id recorded where it is kept across a restart.
        org.fuin.porelo.wire.Sidecar.recordId(
                context.paths().tasks().taskRecord("porelo-uc-shell"),
                "c0ffee0000000000000000000000000000000000000000000000000000000000");

        servingContext(
                context,
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".Start",
                                        Map.of("project", PROJECT, "task", "porelo-uc-shell"));

                        // The existing task was reached - here it predates a restart, so it is
                        // refused with that reason - and no second one was begun beside it.
                        assertThat(reply).containsEntry("container", "porelo-uc-shell");
                        @SuppressWarnings("unchecked")
                        final List<String> output = (List<String>) reply.get("output");
                        assertThat(String.join("\n", output))
                                .contains("'porelo-uc-shell' is the container of task 'shell'")
                                .contains("before this machine restarted")
                                .doesNotContain("porelo-uc-porelo-uc-shell");
                    }
                });
        assertThat(context.paths().tasks().containerState("porelo-uc-porelo-uc-shell"))
                .doesNotExist();
    }

    @Test
    void handInGoesOnlyToARunningTask(@TempDir Path dir) throws Exception {
        runner.answering(
                "ps", "porelo-uc-shell\tExited (0) 2 minutes ago\t1700000000\t0\tuc\tguarded\n");

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        assertThatThrownBy(
                                        () ->
                                                client.call(
                                                        PoreloDaemon.INTERFACE + ".HandIn",
                                                        Map.of(
                                                                "task",
                                                                "porelo-uc-shell",
                                                                "name",
                                                                "a.txt",
                                                                "bytes",
                                                                1,
                                                                "sha256",
                                                                "0".repeat(64),
                                                                "offset",
                                                                0,
                                                                "part",
                                                                "eA==")))
                                .isInstanceOf(VarlinkException.class)
                                .hasMessageContaining("NotRunning");
                        assertThatThrownBy(
                                        () ->
                                                client.call(
                                                        PoreloDaemon.INTERFACE + ".TakeBack",
                                                        Map.of(
                                                                "task",
                                                                "porelo-uc-gone",
                                                                "name",
                                                                "a.txt")))
                                .isInstanceOf(VarlinkException.class)
                                .hasMessageContaining("NoSuchTask");
                    }
                });
    }

    @Test
    void handInHoldsAPartAndSaysWhereItIsUntilTheFileIsWhole(@TempDir Path dir) throws Exception {
        runner.answering("ps", "porelo-uc-shell\tUp 4 minutes\t1700000000\t0\tuc\tguarded\n");
        final String part =
                java.util.Base64.getEncoder()
                        .encodeToString("half".getBytes(StandardCharsets.UTF_8));

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".HandIn",
                                        Map.of(
                                                "task",
                                                "porelo-uc-shell",
                                                "name",
                                                "a.txt",
                                                "bytes",
                                                8,
                                                "sha256",
                                                "0".repeat(64),
                                                "offset",
                                                0,
                                                "part",
                                                part));

                        assertThat(((Number) reply.get("received")).longValue()).isEqualTo(4L);
                        assertThat(reply).doesNotContainKey("file");
                        assertThatThrownBy(
                                        () ->
                                                client.call(
                                                        PoreloDaemon.INTERFACE + ".HandIn",
                                                        Map.of(
                                                                "task",
                                                                "porelo-uc-shell",
                                                                "name",
                                                                "a.txt",
                                                                "bytes",
                                                                8,
                                                                "sha256",
                                                                "0".repeat(64),
                                                                "offset",
                                                                6,
                                                                "part",
                                                                part)))
                                .isInstanceOf(VarlinkException.class)
                                .hasMessageContaining("PartOutOfOrder");
                        assertThat(
                                        client.call(
                                                PoreloDaemon.INTERFACE + ".HandIns",
                                                Map.of("task", "porelo-uc-shell")))
                                .as("nothing reached the task, so nothing is written down")
                                .containsEntry("record", List.of());
                    }
                });
    }

    @Test
    void handInRefusesWhatTheContractNamesUnderItsName(@TempDir Path dir) throws Exception {
        runner.answering("ps", "porelo-uc-shell\tUp 4 minutes\t1700000000\t0\tuc\tguarded\n");

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        assertThatThrownBy(
                                        () ->
                                                client.call(
                                                        PoreloDaemon.INTERFACE + ".HandIn",
                                                        Map.of(
                                                                "task",
                                                                "porelo-uc-shell",
                                                                "name",
                                                                "big.bin",
                                                                "bytes",
                                                                65L * 1024 * 1024,
                                                                "sha256",
                                                                "0".repeat(64),
                                                                "offset",
                                                                0,
                                                                "part",
                                                                "eA==")))
                                .isInstanceOf(VarlinkException.class)
                                .hasMessageContaining("FileTooLarge");
                        assertThatThrownBy(
                                        () ->
                                                client.call(
                                                        PoreloDaemon.INTERFACE + ".HandIn",
                                                        Map.of(
                                                                "task",
                                                                "porelo-uc-shell",
                                                                "name",
                                                                "../x",
                                                                "bytes",
                                                                1,
                                                                "sha256",
                                                                "0".repeat(64),
                                                                "offset",
                                                                0,
                                                                "part",
                                                                "eA==")))
                                .isInstanceOf(VarlinkException.class)
                                .hasMessageContaining("FileNameRefused");
                    }
                });
    }

    @Test
    void startRefusesATaskThatIsAlreadyRunning(@TempDir Path dir) throws Exception {

        final PoreloContext context = hooked(dir);
        final Path project = projectFile(dir);
        runner.answering(
                "ps",
                "porelo-uc-shell\tUp 4 minutes\t1700000000\t0\tuc\tguarded\tuc\t\tc0ffee0000000000000000000000000000000000000000000000000000000000\n");
        // As a real start leaves it: the id recorded where it is kept across a restart.
        org.fuin.porelo.wire.Sidecar.recordId(
                context.paths().tasks().taskRecord("porelo-uc-shell"),
                "c0ffee0000000000000000000000000000000000000000000000000000000000");

        servingContext(
                context,
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".Start",
                                        Map.of("project", PROJECT, "task", "shell"));

                        assertThat(((Number) reply.get("exitCode")).intValue()).isEqualTo(65);
                        assertThat(reply).containsEntry("action", "RUNNING");
                        @SuppressWarnings("unchecked")
                        final List<String> output = (List<String>) reply.get("output");
                        assertThat(String.join("\n", output)).contains("is already running");
                    }
                });
    }

    @Test
    void aNumberOfSubagentsThatIsNoWholeNumberIsRefusedAsWrongUseAndNeverRead(@TempDir Path dir)
            throws Exception {

        // Read as a lower whole number, or dropped for the project file's, it would bound the agent
        // to something nobody asked for.
        final PoreloContext context = hooked(dir);
        projectFile(dir);
        runner.answering("podman version", "5.8.1");

        servingContext(
                context,
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        for (final Object given : List.<Object>of(-0.5, 2.7, 1e11, -1, "2", true)) {
                            final Map<String, Object> reply =
                                    client.call(
                                            PoreloDaemon.INTERFACE + ".Start",
                                            Map.of(
                                                    "project",
                                                    PROJECT,
                                                    "task",
                                                    "shell",
                                                    "repository",
                                                    "uc",
                                                    "subagents",
                                                    given));
                            assertThat(((Number) reply.get("exitCode")).intValue())
                                    .as("%s is refused as wrong use", given)
                                    .isEqualTo(64);
                            @SuppressWarnings("unchecked")
                            final List<String> output = (List<String>) reply.get("output");
                            assertThat(String.join("\n", output))
                                    .as("the refusal names the parameter")
                                    .contains("subagents takes a whole number from 0");
                        }
                    }
                });
        assertThat(runner.lines())
                .as("nothing is asked of the runtime for a start refused as wrong use")
                .noneMatch(line -> line.contains("podman ps") || line.contains("podman create"));
    }

    @Test
    void aNumberOfSubagentsThatIsNoWholeNumberIsRefusedBeforeAHostOrATaskIsAsked(@TempDir Path dir)
            throws Exception {

        // Wrong use is answered before anything: a host never met was scanned over the network,
        // and a start naming no task listed the runtime's containers for a name, both for a start
        // that was going to be refused.
        final PoreloContext context = hooked(dir);
        projectFile(dir);
        runner.answering("podman version", "5.8.1");

        servingContext(
                context,
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".Start",
                                        Map.of(
                                                "project",
                                                PROJECT,
                                                "repository",
                                                "uc",
                                                "upstream",
                                                "ssh://git@forge.example.org/uc.git",
                                                "subagents",
                                                2.7));
                        assertThat(((Number) reply.get("exitCode")).intValue())
                                .as("refused as wrong use: %s", reply)
                                .isEqualTo(64);
                        assertThat(reply.get("action"))
                                .as("its final reply says what it refused, as the contract has it")
                                .isEqualTo("CREATE");
                        final Map<String, Object> unnamed =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".Start",
                                        Map.of(
                                                "project",
                                                PROJECT,
                                                "repository",
                                                "uc",
                                                "subagents",
                                                2.7));
                        assertThat(((Number) unnamed.get("exitCode")).intValue())
                                .as("refused as wrong use without its host: %s", unnamed)
                                .isEqualTo(64);
                    }
                });
        assertThat(runner.lines())
                .as("neither the host nor the runtime is asked: %s", runner.lines())
                .noneMatch(line -> line.contains("ssh-keyscan") || line.contains("podman ps"));
    }

    @Test
    void aValueTheProjectDoesNotAllowIsRefusedByCanStartAndStartAlike(@TempDir Path dir)
            throws Exception {

        // An interface offered a start by CanStart must not see Start refuse it, and the reverse.
        final PoreloContext context = hooked(dir);
        final Path project = projectFile(dir);
        Files.writeString(
                project,
                Files.readString(project) + "parameters:\n  type:\n    values: [feature, bugfix]\n",
                java.nio.charset.StandardCharsets.UTF_8);
        runner.answering("podman version", "5.8.1");

        servingContext(
                context,
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".Start",
                                        Map.of(
                                                "project",
                                                PROJECT,
                                                "task",
                                                "shell",
                                                "repository",
                                                "uc",
                                                "parameters",
                                                Map.of(
                                                        "type",
                                                        Map.of(
                                                                "value", "hotfix", "origin",
                                                                "form"))));
                        assertThat(((Number) reply.get("exitCode")).intValue()).isEqualTo(64);
                        @SuppressWarnings("unchecked")
                        final List<String> output = (List<String>) reply.get("output");
                        assertThat(String.join("\n", output)).contains("[feature, bugfix]");

                        assertThat(
                                        client.call(
                                                PoreloDaemon.INTERFACE + ".CanStart",
                                                Map.of(
                                                        "project",
                                                        PROJECT,
                                                        "task",
                                                        "shell",
                                                        "repository",
                                                        "uc",
                                                        "parameters",
                                                        Map.of(
                                                                "type",
                                                                Map.of(
                                                                        "value", "hotfix", "origin",
                                                                        "form")))))
                                .containsEntry("outcome", "PARAMETER_REFUSED")
                                .containsEntry("ready", false);
                        assertThat(
                                        client.call(
                                                PoreloDaemon.INTERFACE + ".CanStart",
                                                Map.of(
                                                        "project",
                                                        PROJECT,
                                                        "task",
                                                        "shell",
                                                        "repository",
                                                        "uc",
                                                        "parameters",
                                                        Map.of(
                                                                "type",
                                                                Map.of(
                                                                        "value", "bugfix", "origin",
                                                                        "form")))))
                                .doesNotContainEntry("outcome", "PARAMETER_REFUSED");

                        // The origin is the client's to say: it read the value where its person is.
                        final Map<String, Object> dry =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".Start",
                                        Map.of(
                                                "project",
                                                PROJECT,
                                                "task",
                                                "shell",
                                                "repository",
                                                "uc",
                                                "dryRun",
                                                true,
                                                "parameters",
                                                Map.of(
                                                        "type",
                                                        Map.of(
                                                                "value", "bugfix", "origin",
                                                                "form"))));
                        @SuppressWarnings("unchecked")
                        final List<String> said = (List<String>) dry.get("output");
                        assertThat(String.join("\n", said)).contains("type=bugfix (form)");
                    }
                });
        assertThat(runner.lines())
                .as("nothing is built or created for a value nothing allows")
                .noneMatch(
                        line ->
                                line.startsWith("podman build")
                                        || line.startsWith("podman create"));
    }

    @Test
    void refusesABadTaskNameBeforeAnythingIsBuiltOrWritten(@TempDir Path dir) throws Exception {

        // Reported from the interface: 'Foo Bar' built an image and wrote a policy, a resolver and
        // a sidecar before podman refused the container name, and all three stayed behind.
        final PoreloContext context = hooked(dir);
        final Path project = projectFile(dir);
        runner.answering("podman version", "5.8.1");

        servingContext(
                context,
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        for (final boolean dryRun : new boolean[] {false, true}) {
                            final Map<String, Object> reply =
                                    client.call(
                                            PoreloDaemon.INTERFACE + ".Start",
                                            Map.of(
                                                    "project", PROJECT, "task", "Foo Bar", "dryRun",
                                                    dryRun));

                            assertThat(((Number) reply.get("exitCode")).intValue())
                                    .as("dryRun " + dryRun)
                                    .isEqualTo(64);
                            @SuppressWarnings("unchecked")
                            final List<String> output = (List<String>) reply.get("output");
                            assertThat(String.join("\n", output))
                                    .contains("not a task name")
                                    .contains("'foo-bar' would do");
                        }

                        final Map<String, Object> check =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".CanStart",
                                        Map.of("project", PROJECT, "task", "Foo Bar"));
                        assertThat(check)
                                .containsEntry("outcome", "BAD_TASK_NAME")
                                .containsEntry("ready", false);
                        assertThat(
                                        client.call(
                                                PoreloDaemon.INTERFACE + ".CanStart",
                                                Map.of("project", PROJECT, "task", "shell")))
                                .doesNotContainEntry("outcome", "BAD_TASK_NAME");
                    }
                });
        assertThat(context.paths().tasks().containerState("porelo-uc-Foo Bar")).doesNotExist();
        assertThat(runner.lines())
                .as("nothing is built or created for a name that cannot be used")
                .noneMatch(
                        line ->
                                line.startsWith("podman build")
                                        || line.startsWith("podman create"));
    }

    @Test
    void logsAStartByNameAndOutcomeOnly() {
        assertThat(PoreloDaemon.startLine("shell", "porelo-uc-shell", 0))
                .isEqualTo("start porelo-uc-shell: exit 0");
        // A start that failed before a container existed still names what was asked for.
        assertThat(PoreloDaemon.startLine("build", "", 70)).isEqualTo("start build: exit 70");
        assertThat(PoreloDaemon.startLine("", "", 69)).isEqualTo("start shell: exit 69");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsTheContainersNamedLikeTasksThatAreNotTasks(@TempDir Path dir) throws Exception {

        // The same the CLI's list says under its table: a client shows them as what they are, never
        // as tasks.
        runner.answering(
                "ps",
                "porelo-uc-shell\tExited (0) 5 days ago\t1700000000\t1700000100\tuc\tguarded\n");
        final PoreloContext context = context(dir).recognizingTasks();

        servingContext(
                context,
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(PoreloDaemon.INTERFACE + ".List", Map.of());

                        assertThat((List<?>) reply.get("tasks")).isEmpty();
                        assertThat((List<Object>) reply.get("unrecognized"))
                                .containsExactly("porelo-uc-shell");
                    }
                });
    }

    @Test
    void aPanicThroughTheDaemonIsJournalledAndSaysTheLine(@TempDir Path dir) throws Exception {
        runner.answering("ps", "");
        final PoreloContext context = context(dir);

        servingContext(
                context,
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(PoreloDaemon.INTERFACE + ".Panic", Map.of());

                        assertThat((String) reply.get("journalled"))
                                .contains("via=daemon")
                                .contains("stopped=none");
                    }
                });
        assertThat(java.nio.file.Files.readAllLines(context.paths().stopJournal()))
                .singleElement()
                .asString()
                .contains("via=daemon");
    }

    @Test
    void saysWhatStartingEachTaskWouldDo(@TempDir Path dir) throws Exception {

        // Answered per task in the listing rather than by a call per row: an interface drawing
        // forty tiles would otherwise ask forty times for something the listing already knew. A
        // listed task exists by definition, so CREATE cannot appear here.
        runner.answering(
                "ps",
                "porelo-uc-shell\tUp 4 minutes\t1700000000\t0\tuc\tguarded\n"
                        + "porelo-uc-build\tExited (0) 2 minutes ago\t1700000000\t1700000100\tuc\tguarded\n"
                        + "porelo-uc-old\tExited (143) 1 day ago\t1700000000\t1700000100\tuc\tguarded\n");
        final PoreloContext context = context(dir);
        // A stopped task that still has its state directory comes back. One without it was started
        // before the machine restarted, which is what an emptied runtime directory leaves.
        Files.createDirectories(context.paths().tasks().containerState("porelo-uc-build"));

        servingContext(
                context,
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(PoreloDaemon.INTERFACE + ".List", Map.of());

                        @SuppressWarnings("unchecked")
                        final List<Map<String, Object>> tasks =
                                (List<Map<String, Object>>) reply.get("tasks");

                        // Running: starting it again is refused, and Attach is what somebody wants.
                        assertThat(tasks.get(0))
                                .containsEntry("startAction", "RUNNING")
                                // Carried, never cut off the container name by the caller.
                                .containsEntry("task", "shell")
                                .containsEntry("startDetail", "")
                                .containsEntry("phase", "");

                        // Stopped: starting it brings it back with the workspace it has.
                        assertThat(tasks.get(1))
                                .containsEntry("startAction", "RESUME")
                                .containsEntry("task", "build")
                                .containsEntry("startDetail", "");

                        // Stopped before the machine restarted: refused before anybody presses
                        // Start, with the way to keep the work.
                        assertThat(tasks.get(2))
                                .containsEntry("startAction", "PREDATES_RESTART")
                                .containsEntry("task", "old");
                        assertThat((String) tasks.get(2).get("startDetail"))
                                .contains("podman cp porelo-uc-old:/workspace");
                    }
                });
    }

    @Test
    void listsTheTasksOverTheSocket(@TempDir Path dir) throws Exception {

        runner.answering(
                "ps",
                "porelo-uc-shell-1\tUp 4 minutes\n"
                        + "porelo-uc-shell-2\tExited (0) 2 minutes ago\n");

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(PoreloDaemon.INTERFACE + ".List", Map.of());

                        @SuppressWarnings("unchecked")
                        final List<Map<String, Object>> tasks =
                                (List<Map<String, Object>>) reply.get("tasks");
                        assertThat(tasks).hasSize(2);
                        assertThat(tasks.get(0))
                                .containsEntry("name", "porelo-uc-shell-1")
                                .containsEntry("running", true);
                        assertThat(tasks.get(1))
                                .containsEntry("name", "porelo-uc-shell-2")
                                .containsEntry("running", false);
                    }
                });
    }

    @Test
    void stopsATaskOverTheSocket(@TempDir Path dir) throws Exception {

        runner.answering("ps", "porelo-uc-shell-1\tUp 4 minutes\n");
        runner.answering("git status --porcelain", "0 0");
        runner.answering("{{.State.Status}}", "exited");

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".Stop",
                                        Map.of("task", "porelo-uc-shell-1"));

                        assertThat(reply).containsEntry("outcome", "STOPPED");
                        assertThat(reply.get("surviving"))
                                .as("a confirmed stop has no survivors")
                                .isEqualTo(List.of());
                        assertThat(runner.lines()).anyMatch(line -> line.startsWith("podman stop"));
                    }
                });
    }

    @Test
    void aFailedRemovalOverTheSocketKeepsTheTaskIdentity(@TempDir Path dir) throws Exception {
        final PoreloContext context = context(dir).recognizingTasks();
        final String task = "porelo-uc-shell-1";
        org.fuin.porelo.wire.Sidecar.recordId(
                context.paths().tasks().containerState(task), "a1b2c3d4e5f6");
        new org.fuin.porelo.app.TaskState(context).save(task);
        runner.answering("ps", task + "\tUp 4 minutes\t\t\tuc\tguarded\t\t\ta1b2c3d4e5f6\n");
        runner.failing("podman stop", 125, "runtime refused");
        runner.failing("podman rm", 125, "runtime refused");
        runner.answering("{{.State.Status}}", "running");
        servingContext(
                context,
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".Remove",
                                        Map.of("task", task, "force", true));
                        assertThat(reply)
                                .as("failed purge is exposed without changing the interface")
                                .containsEntry("removed", false);
                        assertThat((String) reply.get("detail"))
                                .as("the daemon names the survivor")
                                .contains(task);
                        assertThat(context.recordedId(task))
                                .as("retry can still find the container")
                                .isEqualTo("a1b2c3d4e5f6");
                    }
                });
    }

    @Test
    void refusesOverTheSocketExactlyAsTheCliDoes(@TempDir Path dir) throws Exception {

        // The reason this shares TaskControl with the CLI rather than reimplementing it: a refusal
        // that exists in one caller and not the other is a task removed, over this socket, with
        // work that existed nowhere else.
        runner.answering("ps", "porelo-uc-shell-1\tUp 4 minutes\n");
        runner.answering("git status --porcelain", "3 2");

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".Remove",
                                        Map.of("task", "porelo-uc-shell-1"));

                        // What it holds is asked before it is refused for running, so that rescue
                        // is still possible - the container has to be up for work to be pushed out
                        // of it.
                        assertThat(reply)
                                .containsEntry("outcome", "HOLDS_WORK")
                                .containsEntry("work", "2 commits and 3 changed files");
                        assertThat(runner.lines()).noneMatch(line -> line.startsWith("podman rm"));
                    }
                });
    }

    @Test
    void removingSomethingStillUpIsAnswered(@TempDir Path dir) throws Exception {

        // Over the socket as at the terminal: stopping ends an agent's session and removing
        // destroys its work, so a running task is refused rather than stopped as a side effect.
        runner.answering("container inspect", "4711");
        runner.answering("ps", "porelo-uc-shell-1\tUp 4 minutes\n");

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".Remove",
                                        Map.of("task", "porelo-uc-shell-1"));

                        assertThat(reply).containsEntry("outcome", "STILL_RUNNING");
                        assertThat(runner.lines()).noneMatch(line -> line.startsWith("podman rm"));
                    }
                });
    }

    @Test
    void aCallWithNoTaskNameIsRefusedRatherThanGuessedAt(@TempDir Path dir) throws Exception {

        // A client is not this process: nothing it sends can be assumed to be there.
        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        assertThat(client.call(PoreloDaemon.INTERFACE + ".Stop", Map.of()))
                                .containsEntry("outcome", "NOT_A_TASK");
                        assertThat(client.call(PoreloDaemon.INTERFACE + ".Remove", Map.of()))
                                .containsEntry("outcome", "NOT_A_TASK");
                    }
                });
    }

    @Test
    void redrawsWhenTheWorkChangesWithoutTheContainerChanging(@TempDir Path dir) throws Exception {

        // The one transition that matters and the runtime cannot see: a task that starts waiting
        // for an answer is still "Up 4 minutes". A watcher comparing only the container would never
        // redraw it.
        runner.answering("ps", "porelo-uc-shell-1\tUp 4 minutes\t1788500000\t0\n");
        final Path state = dir.resolve("run/porelo/porelo-uc-shell-1");
        Files.createDirectories(state);

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final List<Map<String, Object>> answers = new CopyOnWriteArrayList<>();
                        final Thread reader =
                                Thread.ofVirtual()
                                        .start(
                                                () -> {
                                                    try {
                                                        client.callMore(
                                                                PoreloDaemon.INTERFACE + ".Watch",
                                                                Map.of(),
                                                                answers::add);
                                                    } catch (RuntimeException ex) {
                                                        // Ends with the connection.
                                                    }
                                                });

                        waitFor(() -> !answers.isEmpty());
                        Files.writeString(
                                state.resolve(org.fuin.porelo.wire.Waiting.FILE),
                                "api.example.test:443");

                        waitFor(
                                () ->
                                        answers.stream()
                                                .anyMatch(
                                                        answer ->
                                                                String.valueOf(answer)
                                                                        .contains("WAITING")));
                        assertThat(String.valueOf(answers.getLast()))
                                .contains("api.example.test:443");

                        reader.interrupt();
                    }
                });
    }

    @Test
    void widensARunningTaskWithoutStoppingIt(@TempDir Path dir) throws Exception {

        // The whole point: an agent an hour into a run needs a host nobody declared, and the
        // alternative is to throw the run away to change a line in a file.
        runner.answering("ps", "porelo-uc-shell-1\tUp 4 minutes\t1788500000\t0\n");
        final Path state = dir.resolve("run/porelo/porelo-uc-shell-1");
        Files.createDirectories(state);
        new org.fuin.porelo.wire.Sidecar(
                        org.fuin.porelo.wire.Sidecar.VERSION,
                        "uc",
                        "guarded",
                        state.resolve("r.nft").toString(),
                        state.resolve("dns.conf").toString(),
                        "/usr/bin/porelo",
                        state.toString())
                .writeTo(state.resolve("sidecar.json"));
        Files.writeString(
                state.resolve(org.fuin.porelo.shield.DnsPolicy.SERVERS_FILE),
                "server=/declared.test/192.0.2.53\n");
        // A live process stands in for the resolver: its record is verified before it is signalled.
        org.fuin.porelo.wire.HelperPid.record(state.resolve("dnsmasq.pid"));

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {

                        final Map<String, Object> preview =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".WidenTask",
                                        Map.of(
                                                "task",
                                                "porelo-uc-shell-1",
                                                "domains",
                                                List.of("docs.example.test"),
                                                "scope",
                                                "RUN",
                                                "dryRun",
                                                true));
                        assertThat(preview.get("outcome")).isEqualTo("PREVIEWED");
                        assertThat(
                                        Files.readString(
                                                state.resolve(
                                                        org.fuin.porelo.shield.DnsPolicy
                                                                .SERVERS_FILE)))
                                .doesNotContain("docs.example.test");

                        final Map<String, Object> done =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".WidenTask",
                                        Map.of(
                                                "task",
                                                "porelo-uc-shell-1",
                                                "domains",
                                                List.of("docs.example.test"),
                                                "scope",
                                                "RUN"));
                        assertThat(done.get("outcome")).isEqualTo("WIDENED");
                        assertThat(done.get("persisted")).isEqualTo(false);
                        assertThat(
                                        Files.readString(
                                                state.resolve(
                                                        org.fuin.porelo.shield.DnsPolicy
                                                                .SERVERS_FILE)))
                                .contains("server=/docs.example.test/192.0.2.53");
                        assertThat(org.fuin.porelo.wire.GrantedNames.all(state))
                                .containsExactly("docs.example.test");
                    }
                });
    }

    @Test
    void refusesToWidenWithoutBeingToldHowFar(@TempDir Path dir) throws Exception {

        // "This run only" and "this run and the project" are different intentions. A client that
        // did not say which it meant must not have one picked for it.
        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        assertThatThrownBy(
                                        () ->
                                                client.call(
                                                        PoreloDaemon.INTERFACE + ".WidenTask",
                                                        Map.of(
                                                                "task",
                                                                "porelo-uc-shell-1",
                                                                "domains",
                                                                List.of("docs.example.test"))))
                                .isInstanceOf(VarlinkException.class)
                                .hasMessageContaining("ScopeRequired");
                    }
                });
    }

    @Test
    void listsTheEgressSetsAChooserWouldOffer(@TempDir Path dir) throws Exception {

        // Without this an interface cannot offer a chooser, and an operator is back to authoring
        // host lists by hand - which is the thing sets exist to prevent.
        final Path sets = dir.resolve("data/porelo/egress");
        Files.createDirectories(sets);
        Files.writeString(
                sets.resolve("maven.yaml"),
                "name: maven\nlabel: Maven Central\ndomains:\n  - repo.maven.apache.org\n");

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> answer =
                                client.call(PoreloDaemon.INTERFACE + ".Sets", Map.of());

                        final Map<?, ?> set = (Map<?, ?>) ((List<?>) answer.get("sets")).getFirst();
                        assertThat(set.get("name")).isEqualTo("maven");
                        assertThat(set.get("label")).isEqualTo("Maven Central");
                        assertThat(String.valueOf(set.get("domains")))
                                .contains("repo.maven.apache.org");
                        // Where they come from, in search order: an operator's own file of the same
                        // name wins over a packaged one, and that is worth being able to show.
                        assertThat((List<?>) answer.get("locations")).isNotEmpty();
                    }
                });
    }

    @Test
    void answersWhatAProjectMayReachAndTheSectionToWrite(@TempDir Path dir) throws Exception {

        // The pair that makes the egress editor reachable from an interface. Driven over the wire
        // because that is the half that was missing; what the edit does to the file is covered
        // where the edit lives.
        final Path sets = dir.resolve("data/porelo/egress");
        Files.createDirectories(sets);
        Files.writeString(
                sets.resolve("maven.yaml"),
                "name: maven\nlabel: Maven\ndomains:\n  - repo.maven.apache.org\n");
        final Path projectFile =
                record(
                        dir,
                        Files.writeString(
                                dir.resolve("project.yml"),
                                """
                project:
                  name: "uc"
                  security_class: "guarded"
                image:
                  from: "ubuntu:24.04"
                """));

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {

                        assertThat(
                                        (List<?>)
                                                client.call(
                                                                PoreloDaemon.INTERFACE + ".Egress",
                                                                Map.of("project", PROJECT))
                                                        .get("hosts"))
                                .isEmpty();

                        final Map<String, Object> preview =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".SetEgress",
                                        Map.of(
                                                "project",
                                                PROJECT,
                                                "addSets",
                                                List.of("maven"),
                                                "dryRun",
                                                true));
                        assertThat(preview.get("outcome")).isEqualTo("PREVIEWED");
                        assertThat(String.valueOf(preview.get("opens")))
                                .contains("repo.maven.apache.org")
                                .contains("set maven");
                        assertThat(preview.get("entry"))
                                .as("the section a person writes")
                                .isEqualTo("egress:\n  sets:\n    - maven\n");

                        // A preview writes nothing, which is the whole of its promise.
                        assertThat(Files.readString(projectFile)).doesNotContain("egress");

                        // Porelo changes no project file: the change is refused with the section.
                        assertThatThrownBy(
                                        () ->
                                                client.call(
                                                        PoreloDaemon.INTERFACE + ".SetEgress",
                                                        Map.of(
                                                                "project",
                                                                PROJECT,
                                                                "addSets",
                                                                List.of("maven"))))
                                .isInstanceOfSatisfying(
                                        VarlinkException.class,
                                        ex -> {
                                            assertThat(ex.getErrorName())
                                                    .isEqualTo(PoreloDaemon.INTERFACE + ".Failed");
                                            assertThat(
                                                            String.valueOf(
                                                                    ex.getParameters()
                                                                            .get("message")))
                                                    .contains(
                                                            "Porelo does not change " + projectFile)
                                                    .contains("egress:\n  sets:\n    - maven");
                                        });
                        assertThat(Files.readString(projectFile)).doesNotContain("egress");

                        assertThat(
                                        (List<?>)
                                                client.call(
                                                                PoreloDaemon.INTERFACE + ".Egress",
                                                                Map.of("project", PROJECT))
                                                        .get("hosts"))
                                .isEmpty();
                    }
                });
    }

    @Test
    void aHostTheProjectRefusesIsNotAnsweredAsReachable(@TempDir Path dir) throws Exception {

        // An interface that shows a refused host among the reachable ones invites a build to
        // depend on a name the task's resolver answers NXDOMAIN; the CLI's report already leaves
        // it out.
        record(
                dir,
                Files.writeString(
                        dir.resolve("project.yml"),
                        """
                project:
                  name: "uc"
                  security_class: "guarded"
                image:
                  from: "ubuntu:24.04"
                egress:
                  domains: [example.com, files.example.com, example.org]
                  refused: [example.com]
                """));

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> answer =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".Egress",
                                        Map.of("project", PROJECT));
                        assertThat(
                                        ((List<?>) answer.get("hosts"))
                                                .stream()
                                                        .map(
                                                                host ->
                                                                        String.valueOf(
                                                                                ((Map<?, ?>) host)
                                                                                        .get(
                                                                                                "host")))
                                                        .toList())
                                .as(
                                        "the refused host is not reachable; one allowed by its own,"
                                                + " longer name under it is, as the resolver answers it")
                                .containsExactlyInAnyOrder("files.example.com", "example.org");
                        assertThat(String.valueOf(answer.get("refused"))).contains("example.com");
                    }
                });
    }

    @Test
    void refusesASetTheMachineDoesNotHaveOverTheWire(@TempDir Path dir) throws Exception {

        // Written, the file would name something no task on this machine could resolve, and every
        // run would fail on it rather than this one call.
        final Path projectFile =
                record(
                        dir,
                        Files.writeString(
                                dir.resolve("project.yml"),
                                """
                project:
                  name: "uc"
                  security_class: "guarded"
                image:
                  from: "ubuntu:24.04"
                """));

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> answer =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".SetEgress",
                                        Map.of("project", PROJECT, "addSets", List.of("nonesuch")));

                        assertThat(answer.get("outcome")).isEqualTo("NO_SUCH_SET");
                        assertThat(String.valueOf(answer.get("detail"))).contains("nonesuch");
                        assertThat(Files.readString(projectFile)).doesNotContain("egress");
                    }
                });
    }

    @Test
    void refusesToEditWithoutAProjectFile(@TempDir Path dir) throws Exception {

        // The same refusal the gate methods make: a path is the caller's to give, and one it did
        // not give cannot be guessed at.
        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        assertThatThrownBy(
                                        () ->
                                                client.call(
                                                        PoreloDaemon.INTERFACE + ".SetEgress",
                                                        Map.of("addSets", List.of("maven"))))
                                .isInstanceOf(VarlinkException.class)
                                .hasMessageContaining("ProjectRequired");
                    }
                });
    }

    @Test
    void takingANameThisMachineDoesNotCarryIsAnAnswerAndWritesNothing(@TempDir Path dir)
            throws Exception {
        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> answer =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".TakeProject",
                                        Map.of("project", "elsewhere"));
                        assertThat(answer)
                                .containsEntry("file", "")
                                .containsEntry("reason", "not-carried");
                    }
                });
        assertThat(context(dir).paths().projects().organizationProjects()).doesNotExist();
    }

    @Test
    void listsTheProjectsThisMachineKnowsAbout(@TempDir Path dir) throws Exception {

        // The method that makes the gate reachable from an interface: every gate call takes a
        // project file path, and a client on another machine has no filesystem here to find one in.
        Files.createDirectories(dir.resolve("data/porelo/mirrors/uc.git"));
        final Path projectFile = Files.writeString(dir.resolve("project.yml"), "project:\n");
        // One file per project, which is how a task start writes it: a shared document would be a
        // read-modify-write that two starts at once can lose or mix.
        Files.createDirectories(dir.resolve("data/porelo/projects"));
        Files.writeString(dir.resolve("data/porelo/projects/uc"), projectFile + "\n");

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(PoreloDaemon.INTERFACE + ".Projects", Map.of());

                        // 'default' first, which every machine has; then what was run here.
                        assertThat((List<?>) reply.get("projects")).hasSize(2);
                        final Map<?, ?> project =
                                (Map<?, ?>) ((List<?>) reply.get("projects")).get(1);
                        assertThat(project.get("name")).isEqualTo("uc");
                        assertThat(project.get("file")).isEqualTo(projectFile.toString());
                        assertThat(String.valueOf(project.get("mirror"))).endsWith("uc.git");
                    }
                });
    }

    @Test
    void listsOnlyDefaultOnAMachineThatHasRunNothing(@TempDir Path dir) throws Exception {

        // Not an error: a fresh machine is a normal state. It has 'default' and nothing else, and
        // an interface shows that project first, empty, as the place work starts without a project.
        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        assertThat(
                                        (List<?>)
                                                client.call(
                                                                PoreloDaemon.INTERFACE
                                                                        + ".Projects",
                                                                Map.of())
                                                        .get("projects"))
                                .singleElement()
                                .satisfies(
                                        project ->
                                                assertThat(((Map<?, ?>) project).get("name"))
                                                        .isEqualTo("default"));
                    }
                });
    }

    @Test
    void answersWhatAStoppedTaskHeldFromWhatItsStopWroteDown(@TempDir Path dir) throws Exception {

        // The workspace lives inside the container, so once it stops the only source is the note.
        // Counts rather than a sentence: a client can show one of the numbers on its own, or sort
        // by it, and does not inherit English or a plural rule from here.
        final Path state = dir.resolve("run/porelo/porelo-uc-shell-1");
        Files.createDirectories(state);
        org.fuin.porelo.app.UnhandedWork.note(
                state, new org.fuin.porelo.app.UnhandedWork.Held(true, 3, 2, null));

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".WorkHeld",
                                        Map.of("task", "porelo-uc-shell-1"));

                        assertThat(reply.get("readable")).isEqualTo(Boolean.TRUE);
                        assertThat(((Number) reply.get("changedFiles")).intValue()).isEqualTo(3);
                        assertThat(((Number) reply.get("unpushedCommits")).intValue()).isEqualTo(2);
                        assertThat(reply.get("asOf"))
                                .as(
                                        "a recorded answer is historical and has to say when it was true")
                                .isNotNull();
                    }
                });
    }

    @Test
    void tellsHoldingNothingApartFromNobodyHavingLooked(@TempDir Path dir) throws Exception {

        // The whole reason readable exists. An empty answer that means "nothing to lose" and one
        // that means "nobody could look" are the two sentences Credentials and Providers were both
        // caught by, and this would have been the third.
        final Path held = dir.resolve("run/porelo/porelo-uc-held-1");
        Files.createDirectories(held);
        org.fuin.porelo.app.UnhandedWork.note(
                held, new org.fuin.porelo.app.UnhandedWork.Held(true, 0, 0, null));
        Files.createDirectories(dir.resolve("run/porelo/porelo-uc-killed-1"));

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> nothing =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".WorkHeld",
                                        Map.of("task", "porelo-uc-held-1"));
                        assertThat(nothing.get("readable"))
                                .as("it held nothing, and that is an answer")
                                .isEqualTo(Boolean.TRUE);
                        assertThat(((Number) nothing.get("changedFiles")).intValue()).isZero();

                        final Map<String, Object> unknown =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".WorkHeld",
                                        Map.of("task", "porelo-uc-killed-1"));
                        assertThat(unknown.get("readable"))
                                .as("nobody looked, which is not nothing")
                                .isEqualTo(Boolean.FALSE);
                    }
                });
    }

    @Test
    void refusesANameThatIsNotATask(@TempDir Path dir) throws Exception {

        // Not "readable: false": a name that was never a task is a client's wrong argument, and
        // letting it arrive as a legitimate answer is the failure the Backups gap produced.
        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        assertThatThrownBy(
                                        () ->
                                                client.call(
                                                        PoreloDaemon.INTERFACE + ".WorkHeld",
                                                        Map.of("task", "somebody-elses-database")))
                                .hasMessageContaining("NoSuchTask");
                    }
                });
    }

    @Test
    void neverServesTheTasksOwnTokenAsALog(@TempDir Path dir) throws Exception {

        // Logs and Tail used to apply separate rules, so a listing could name a file the reader
        // refused - or worse, drift the other way. The state directory holds the live phantom token
        // beside the logs, which is why the shared rule is an allow-list.
        final Path state = dir.resolve("run/porelo/porelo-uc-shell-1");
        Files.createDirectories(state);
        Files.writeString(state.resolve("vault.token"), "porelo_pt_the_real_thing\n");
        Files.writeString(state.resolve("events.jsonl"), "{\"host\":\"example.com\"}\n");

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> listed =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".Logs",
                                        Map.of("task", "porelo-uc-shell-1"));
                        assertThat(String.valueOf(listed.get("logs")))
                                .as("the firewall's own record is a log")
                                .contains("events.jsonl")
                                .doesNotContain("vault.token");

                        // And what the listing offers is what the reader accepts.
                        assertThat(
                                        String.valueOf(
                                                client.call(
                                                        PoreloDaemon.INTERFACE + ".Tail",
                                                        Map.of(
                                                                "task",
                                                                "porelo-uc-shell-1",
                                                                "log",
                                                                "events.jsonl"))))
                                .contains("example.com");
                        assertThatThrownBy(
                                        () ->
                                                client.call(
                                                        PoreloDaemon.INTERFACE + ".Tail",
                                                        Map.of(
                                                                "task",
                                                                "porelo-uc-shell-1",
                                                                "log",
                                                                "vault.token")))
                                .hasMessageContaining("NoSuchLog");
                    }
                });
    }

    @Test
    void listsTheLogsATaskActuallyHas(@TempDir Path dir) throws Exception {

        // Which files exist depends on what the task started, so a client that held a list of names
        // would open an empty viewer for one that was never going to be there.
        final Path state = dir.resolve("run/porelo/porelo-uc-shell-1");
        Files.createDirectories(state);
        Files.writeString(state.resolve("gate.log"), "a line\n");
        Files.writeString(state.resolve("task.log"), "another\n");
        Files.writeString(state.resolve("watcher.pid"), "4711");

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".Logs",
                                        Map.of("task", "porelo-uc-shell-1"));

                        assertThat((List<?>) reply.get("logs")).hasSize(2);
                        assertThat(String.valueOf(reply.get("logs")))
                                .contains("gate.log")
                                .contains("task.log")
                                .as("only logs, and the size a viewer needs")
                                .doesNotContain("watcher.pid");
                        assertThat(
                                        ((Map<?, ?>) ((List<?>) reply.get("logs")).getFirst())
                                                .get("bytes"))
                                .isNotNull();
                    }
                });
    }

    @Test
    void listsNoLogsForATaskThatIsGone(@TempDir Path dir) throws Exception {

        // Removal takes the state directory with it. Empty rather than an error: the task existed,
        // and asking about it is not a mistake a client should have to handle.
        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        assertThat(
                                        (List<?>)
                                                client.call(
                                                                PoreloDaemon.INTERFACE + ".Logs",
                                                                Map.of("task", "porelo-uc-shell-1"))
                                                        .get("logs"))
                                .isEmpty();
                    }
                });
    }

    @Test
    void readsNoScreenOfSomethingThatIsNotATask(@TempDir Path dir) throws Exception {

        // A name Porelo did not create is somebody else's container, and Screen runs tmux inside
        // it.
        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        assertThatThrownBy(
                                        () ->
                                                client.call(
                                                        PoreloDaemon.INTERFACE + ".Screen",
                                                        Map.of(
                                                                "task",
                                                                "not-porelo-at-all",
                                                                "last",
                                                                5)))
                                .hasMessageContaining("NoSuchTask");
                        assertThatThrownBy(
                                        () ->
                                                client.call(
                                                        PoreloDaemon.INTERFACE + ".Screen",
                                                        Map.of("task", "porelo-p-gone", "last", 5)))
                                .as("a task that is not there")
                                .hasMessageContaining("NoSuchTask");
                    }
                });
    }

    @Test
    void listsNoLogsForSomethingThatIsNotATask(@TempDir Path dir) throws Exception {

        // The same refusal Tail makes: a name Porelo did not create is not something to read files
        // out of, whatever it points at.
        final Path state = dir.resolve("run/porelo/not-porelo-at-all");
        Files.createDirectories(state);
        Files.writeString(state.resolve("secrets.log"), "nothing to see\n");

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        assertThat(
                                        (List<?>)
                                                client.call(
                                                                PoreloDaemon.INTERFACE + ".Logs",
                                                                Map.of("task", "not-porelo-at-all"))
                                                        .get("logs"))
                                .isEmpty();
                    }
                });
    }

    @Test
    void tailsALogAsItIsWritten(@TempDir Path dir) throws Exception {

        // A client that polls lags a prompt that expires, which is why these exist at all.
        final Path state = dir.resolve("run/porelo/porelo-uc-shell-1");
        Files.createDirectories(state);
        final Path log = state.resolve("gate.log");
        Files.writeString(log, "first line\n");

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final List<Map<String, Object>> replies = new CopyOnWriteArrayList<>();
                        final Thread reader =
                                Thread.ofVirtual()
                                        .start(
                                                () -> {
                                                    try {
                                                        client.callMore(
                                                                PoreloDaemon.INTERFACE + ".Tail",
                                                                Map.of(
                                                                        "task",
                                                                        "porelo-uc-shell-1",
                                                                        "log",
                                                                        "gate.log"),
                                                                replies::add);
                                                    } catch (RuntimeException ex) {
                                                        // The connection closes underneath it,
                                                        // which is how a tail ends.
                                                    }
                                                });

                        waitFor(() -> !replies.isEmpty());
                        Files.writeString(
                                log, "second line\n", java.nio.file.StandardOpenOption.APPEND);
                        waitFor(
                                () ->
                                        replies.stream()
                                                .anyMatch(
                                                        reply ->
                                                                String.valueOf(reply.get("lines"))
                                                                        .contains("second line")));

                        reader.interrupt();
                        assertThat(
                                        replies.stream()
                                                .map(reply -> String.valueOf(reply.get("lines"))))
                                .anyMatch(lines -> lines.contains("first line"));
                    }
                });
    }

    @Test
    void talkSaysWhatHappensFromNowAndReplaysNothing(@TempDir Path dir) throws Exception {

        // The contract says live only; every client that connected got the machine's whole history
        // as new events.
        final PoreloContext context = context(dir);
        final org.fuin.porelo.app.Mailbox mailbox =
                new org.fuin.porelo.app.Mailbox(
                        context.paths().messaging().mailbox("porelo-uc-shell-1"));
        mailbox.create();
        new org.fuin.porelo.app.MessageRecord(mailbox).append("written", "old.json", "old", "");

        servingContext(
                context,
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final List<Map<String, Object>> replies = new CopyOnWriteArrayList<>();
                        final Thread reader =
                                Thread.ofVirtual()
                                        .start(
                                                () -> {
                                                    try {
                                                        client.callMore(
                                                                PoreloDaemon.INTERFACE + ".Talk",
                                                                Map.of(),
                                                                replies::add);
                                                    } catch (RuntimeException ex) {
                                                        // The connection closes underneath it,
                                                        // which is how a stream ends.
                                                    }
                                                });
                        Thread.sleep(1500);
                        new org.fuin.porelo.app.MessageRecord(mailbox)
                                .append("written", "new.json", "new", "");
                        waitFor(() -> !replies.isEmpty());
                        reader.interrupt();

                        assertThat(replies)
                                .allSatisfy(
                                        reply ->
                                                assertThat(reply.get("message"))
                                                        .isEqualTo("new.json"));
                    }
                });
    }

    @Test
    void aMessageToATaskThatIsNoneIsRefusedAndWritesNothing(@TempDir Path dir) throws Exception {

        // '' and '..' resolved to the mail root and the state directory, which exist, and a maildir
        // was made there.
        final PoreloContext context = context(dir);
        Files.createDirectories(context.paths().messaging().mailboxes());

        servingContext(
                context,
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        for (final String task : List.of("", "..")) {
                            assertThatThrownBy(
                                            () ->
                                                    client.call(
                                                            PoreloDaemon.INTERFACE + ".Say",
                                                            Map.of(
                                                                    "task", task, "peer", "review",
                                                                    "kind", "note", "text",
                                                                    "hello")))
                                    .as("task '%s'", task)
                                    .isInstanceOf(VarlinkException.class)
                                    .hasMessageContaining("NoSuchTask");
                        }
                    }
                });
        assertThat(context.paths().messaging().mailboxes().resolve("box")).doesNotExist();
        assertThat(context.paths().messaging().mailboxes().resolveSibling("box")).doesNotExist();
    }

    @Test
    void aTailIsNotThrownByABrokenCharacterOrALongLine(@TempDir Path dir) throws Exception {

        // The position was counted by re-encoding decoded text: one byte that was not UTF-8 put it
        // past the end, and the log was sent again every 200 ms. A line longer than a read was
        // never passed at all.
        final Path state = dir.resolve("run/porelo/porelo-uc-shell-1");
        Files.createDirectories(state);
        final Path log = state.resolve("gate.log");
        Files.write(log, new byte[] {(byte) 0xE9, 'x', '\n'});

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final List<Map<String, Object>> replies = new CopyOnWriteArrayList<>();
                        final Thread reader =
                                Thread.ofVirtual()
                                        .start(
                                                () -> {
                                                    try {
                                                        client.callMore(
                                                                PoreloDaemon.INTERFACE + ".Tail",
                                                                Map.of(
                                                                        "task",
                                                                        "porelo-uc-shell-1",
                                                                        "log",
                                                                        "gate.log"),
                                                                replies::add);
                                                    } catch (RuntimeException ex) {
                                                        // The connection closes underneath it,
                                                        // which is how a tail ends.
                                                    }
                                                });
                        waitFor(() -> !replies.isEmpty());
                        Thread.sleep(1000);
                        assertThat(replies).as("the broken line, once").hasSize(1);

                        Files.writeString(
                                log,
                                "y".repeat(70_000) + "\nnext\n",
                                java.nio.file.StandardOpenOption.APPEND);
                        waitFor(
                                () ->
                                        replies.stream()
                                                .anyMatch(
                                                        reply ->
                                                                String.valueOf(reply.get("lines"))
                                                                        .contains("next")));
                        reader.interrupt();
                    }
                });
    }

    @Test
    void refreshTaskForATaskThatIsNotHereIsNoSuchTask(@TempDir Path dir) throws Exception {
        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        assertThatThrownBy(
                                        () ->
                                                client.call(
                                                        PoreloDaemon.INTERFACE + ".RefreshTask",
                                                        Map.of("task", "porelo-default-nobody")))
                                .isInstanceOf(VarlinkException.class)
                                .hasMessageContaining("NoSuchTask");
                    }
                });
    }

    @Test
    void anAddressThatReadsAsAnOptionIsRefusedBeforeAnyProgramSeesIt(@TempDir Path dir)
            throws Exception {

        // A URL or a host reaches git and ssh-keyscan as an argument; one that starts with a dash
        // is an option there, and '--upload-pack=...' is a command git runs.
        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        assertThatThrownBy(
                                        () ->
                                                client.call(
                                                        PoreloDaemon.INTERFACE + ".HostKeys",
                                                        Map.of(
                                                                "host",
                                                                "-oProxyCommand=touch /tmp/porelo-option")))
                                .isInstanceOf(VarlinkException.class)
                                .hasMessageContaining("InvalidParameter");
                    }
                });
        assertThat(runner.invocations())
                .noneSatisfy(
                        command ->
                                assertThat(command.describe())
                                        .contains("--upload-pack")
                                        .contains("-oProxyCommand"));
    }

    @Test
    void aLogNameIsNotAPath(@TempDir Path dir) throws Exception {

        // A client is not this process, and '../../etc/passwd' is a log name until something
        // refuses it.
        Files.createDirectories(dir.resolve("run/porelo/porelo-uc-shell-1"));

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        for (final String name :
                                List.of(
                                        "../../../etc/passwd",
                                        "/etc/passwd",
                                        "gate.log/../../../etc/passwd",
                                        "")) {
                            assertThatThrownBy(
                                            () ->
                                                    client.call(
                                                            PoreloDaemon.INTERFACE + ".Tail",
                                                            Map.of(
                                                                    "task",
                                                                    "porelo-uc-shell-1",
                                                                    "log",
                                                                    name)))
                                    .as("log name '%s'", name)
                                    .isInstanceOf(VarlinkException.class);
                        }
                    }
                });
    }

    @Test
    void watchingWithoutAskingToStreamAnswersOnce(@TempDir Path dir) throws Exception {

        // One method serves both, so a client that cannot stream is not locked out of the data.
        runner.answering("ps", "porelo-uc-shell-1\tUp 4 minutes\n");

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(PoreloDaemon.INTERFACE + ".Watch", Map.of());
                        @SuppressWarnings("unchecked")
                        final List<Map<String, Object>> tasks =
                                (List<Map<String, Object>>) reply.get("tasks");
                        assertThat(tasks)
                                .singleElement()
                                .satisfies(
                                        task ->
                                                assertThat(task)
                                                        .containsEntry(
                                                                "name", "porelo-uc-shell-1"));
                    }
                });
    }

    @Test
    void watchSendsTheStateAgainOnlyWhenItChanges(@TempDir Path dir) throws Exception {

        // A fleet view redrawn every half second because nothing happened is noise.
        runner.answering("ps", "porelo-uc-shell-1\tUp 4 minutes\n");

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final List<Map<String, Object>> replies = new CopyOnWriteArrayList<>();
                        final Thread reader =
                                Thread.ofVirtual()
                                        .start(
                                                () -> {
                                                    try {
                                                        client.callMore(
                                                                PoreloDaemon.INTERFACE + ".Watch",
                                                                Map.of(),
                                                                replies::add);
                                                    } catch (RuntimeException ex) {
                                                        // Ends with the connection.
                                                    }
                                                });

                        waitFor(() -> replies.size() == 1);

                        // The age moves on its own: 'Up 3 seconds' becomes 'Up 4 seconds' a second
                        // later. Measured against a real task, this made the watch fire every
                        // second and tell a fleet view to redraw because a clock had moved.
                        runner.answering("ps", "porelo-uc-shell-1\tUp 9 minutes\n");
                        Thread.sleep(PoreloDaemon.WATCH_INTERVAL.multipliedBy(4));
                        assertThat(replies)
                                .as("only the age changed, so nothing more was sent")
                                .hasSize(1);

                        runner.answering("ps", "porelo-uc-shell-1\tExited (0) 1 second ago\n");
                        waitFor(() -> replies.size() == 2);

                        reader.interrupt();
                        assertThat(String.valueOf(replies.get(1))).contains("Exited");
                    }
                });
    }

    /** Waits for a condition the server reaches on its own schedule. */
    private static void waitFor(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the server never reached the expected state");
    }

    @Test
    void carriesAPromptFromATaskAndTheAnswerBack(@TempDir Path dir) throws Exception {

        // The one socket an interface talks to. Driven against a real watcher-side service on a
        // real socket, because what is being checked is that two processes meet - and a prompt that
        // never arrives, or an answer that goes nowhere, both leave the task blocked.
        runner.answering("ps", "porelo-uc-shell-1\tUp 4 minutes\n");
        final Path state = dir.resolve("run/porelo/porelo-uc-shell-1");
        Files.createDirectories(state);

        final List<String> decided = new CopyOnWriteArrayList<>();
        final ClearanceHub hub =
                new ClearanceHub(
                        "uc",
                        "shell",
                        request -> Verdict.TIMEOUT,
                        (address, name) -> decided.add(address));

        try (ClearanceService watcher =
                new ClearanceService(state.resolve("clearance.sock"), hub)) {
            watcher.start();

            serving(
                    dir,
                    socket -> {
                        try (VarlinkClient client = new VarlinkClient(socket)) {
                            final List<Map<String, Object>> prompts = new CopyOnWriteArrayList<>();
                            final Thread reader =
                                    Thread.ofVirtual()
                                            .start(
                                                    () -> {
                                                        try {
                                                            client.callMore(
                                                                    PoreloDaemon.INTERFACE
                                                                            + ".Prompts",
                                                                    Map.of(),
                                                                    prompts::add);
                                                        } catch (RuntimeException ex) {
                                                            // Ends with the connection.
                                                        }
                                                    });

                            // Wait until the daemon has subscribed, then report a blocked
                            // destination the way the reader hook does.
                            waitFor(() -> watcher.subscriberCount() > 0);
                            try (VarlinkClient hook =
                                    new VarlinkClient(state.resolve("clearance.sock"))) {
                                hook.call(
                                        ClearanceService.INTERFACE + ".Report",
                                        Map.of(
                                                "destination",
                                                "pypi.org",
                                                "protocol",
                                                "tcp",
                                                "port",
                                                443));
                            }

                            waitFor(() -> !prompts.isEmpty());
                            assertThat(prompts.get(0))
                                    .containsEntry("destination", "pypi.org")
                                    .as(
                                            "tagged with the task it came from, so an answer can go back")
                                    .containsEntry("task", "porelo-uc-shell-1");

                            // Nobody answered, and that arrives too. Without it an interface cannot
                            // tell a question that ran out from one still waiting, and this one
                            // will never be asked again.
                            waitFor(() -> prompts.size() > 1);
                            assertThat(prompts.get(1))
                                    .containsEntry("destination", "pypi.org")
                                    .containsEntry("verdict", "timeout")
                                    .containsEntry("task", "porelo-uc-shell-1");

                            // And the answer reaches the hub that is holding the task.
                            try (VarlinkClient answering = new VarlinkClient(socket)) {
                                assertThat(
                                                answering.call(
                                                        PoreloDaemon.INTERFACE + ".Decide",
                                                        Map.of(
                                                                "task",
                                                                "porelo-uc-shell-1",
                                                                "key",
                                                                "tcp/pypi.org/443",
                                                                "address",
                                                                "pypi.org",
                                                                "allow",
                                                                true)))
                                        .containsEntry("ok", true);
                            }
                            waitFor(() -> decided.contains("pypi.org"));

                            reader.interrupt();
                        }
                    });
        }
    }

    @Test
    void answeringATaskWithNoWatcherSaysSo(@TempDir Path dir) throws Exception {

        // An answer that goes nowhere leaves the operator believing they unblocked something.
        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        assertThatThrownBy(
                                        () ->
                                                client.call(
                                                        PoreloDaemon.INTERFACE + ".Decide",
                                                        Map.of(
                                                                "task",
                                                                "porelo-uc-shell-1",
                                                                "key",
                                                                "k",
                                                                "address",
                                                                "a",
                                                                "allow",
                                                                true)))
                                .isInstanceOf(VarlinkException.class);
                    }
                });
    }

    @Test
    void promptsWithoutStreamingIsRefusedRatherThanAnsweredOnce(@TempDir Path dir)
            throws Exception {

        // Answering a stream once would look like it worked and then deliver nothing ever again.
        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        assertThatThrownBy(
                                        () ->
                                                client.call(
                                                        PoreloDaemon.INTERFACE + ".Prompts",
                                                        Map.of()))
                                .isInstanceOf(VarlinkException.class);
                    }
                });
    }

    @Test
    void listsWhatTheVaultHoldsWithoutItsValues(@TempDir Path dir) throws Exception {

        // The whole credential promise in one assertion: what is stored can be shown without ever
        // displaying, logging or copying a value.
        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(PoreloDaemon.INTERFACE + ".Credentials", Map.of());
                        assertThat(reply)
                                .containsKey("vault")
                                .containsKey("credentials")
                                .containsEntry("exists", false);
                        assertThat(String.valueOf(reply)).doesNotContain("value");
                    }
                });
    }

    @Test
    void authorizingIsAStreamAndNamesWhatItNeeds(@TempDir Path dir) throws Exception {

        // A consent flow answered once would leave the interface polling for its end, and polling
        // one is the shape that produces two grants for one question.
        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        assertThatThrownBy(
                                        () ->
                                                client.call(
                                                        PoreloDaemon.INTERFACE + ".Authorize",
                                                        Map.of("name", "search")))
                                .isInstanceOf(VarlinkException.class)
                                .hasMessageContaining("StreamRequired");
                        assertThatThrownBy(
                                        () ->
                                                client.callMore(
                                                        PoreloDaemon.INTERFACE + ".Authorize",
                                                        Map.of("name", "search"),
                                                        answer -> true))
                                .isInstanceOf(VarlinkException.class)
                                .hasMessageContaining("oauth-device");
                    }
                });
    }

    @Test
    void aRedirectNamesThePortTheAnswerComesBackToAndEndsTheStreamWithTheAnswer(@TempDir Path dir)
            throws Exception {

        // A browser elsewhere redirects to this machine's loopback; the interface has to know which
        // port to forward before it opens the link, and learns how it ended on the same stream.
        org.junit.jupiter.api.Assumptions.assumeTrue(
                org.fuin.porelo.vault.KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");
        final int port;
        try (java.net.ServerSocket free = new java.net.ServerSocket(0)) {
            port = free.getLocalPort();
        }
        final PoreloContext context = context(dir);
        final org.fuin.porelo.vault.KernelKeyring keyring =
                new org.fuin.porelo.vault.KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            context.vault()
                    .write(
                            Map.of(
                                    "svc",
                                    new org.fuin.porelo.vault.VaultEntry(
                                            "-",
                                            "oauth-code",
                                            Map.of(
                                                    "client_id",
                                                    "porelo-client",
                                                    "authorization_url",
                                                    "https://auth.example.invalid/authorize",
                                                    "token_url",
                                                    "https://auth.example.invalid/token",
                                                    "redirect_port",
                                                    String.valueOf(port)))),
                            VAULT_PASSPHRASE);
            keyring.store(VAULT_PASSPHRASE);

            servingContext(
                    context,
                    dir,
                    socket -> {
                        try (VarlinkClient client = new VarlinkClient(socket)) {
                            final List<Map<String, Object>> replies = new java.util.ArrayList<>();
                            client.callMore(
                                    PoreloDaemon.INTERFACE + ".Authorize",
                                    Map.of("name", "svc"),
                                    answer -> {
                                        replies.add(answer);
                                        if ("needed".equals(answer.get("state"))) {
                                            final String link = String.valueOf(answer.get("link"));
                                            final String state =
                                                    link.replaceAll(".*[?&]state=([^&]*).*", "$1");
                                            try {
                                                java.net.http.HttpClient.newHttpClient()
                                                        .send(
                                                                java.net.http.HttpRequest
                                                                        .newBuilder(
                                                                                java.net.URI.create(
                                                                                        "http://127.0.0.1:"
                                                                                                + port
                                                                                                + "/callback?error=access_denied"
                                                                                                + "&state="
                                                                                                + state))
                                                                        .build(),
                                                                java.net.http.HttpResponse
                                                                        .BodyHandlers.discarding());
                                            } catch (java.io.IOException
                                                    | InterruptedException ex) {
                                                throw new IllegalStateException(ex);
                                            }
                                        }
                                        return true;
                                    });

                            assertThat(replies.getFirst())
                                    .containsEntry("state", "needed")
                                    .containsEntry("code", "");
                            assertThat(((Number) replies.getFirst().get("port")).intValue())
                                    .isEqualTo(port);
                            assertThat(String.valueOf(replies.getFirst().get("link")))
                                    .startsWith("https://auth.example.invalid/authorize?")
                                    .contains("code_challenge=");
                            assertThat(replies.getLast()).containsEntry("state", "refused");
                        }
                    });
        } finally {
            keyring.forget();
        }
    }

    @Test
    void anAuthorizationSomebodyHasToGrantIsStreamedAndSoIsItsGrant(@TempDir Path dir)
            throws Exception {

        // A start refused for want of a grant reaches only whoever started it; the stream reaches
        // every interface.
        final PoreloContext context = context(dir);
        org.fuin.porelo.app.AuthorizationsNeeded.raise(
                context,
                "search",
                "porelo-p-a",
                "p",
                org.fuin.porelo.app.AuthorizationsNeeded.NEVER);
        servingContext(
                context,
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        assertThatThrownBy(
                                        () ->
                                                client.call(
                                                        PoreloDaemon.INTERFACE + ".Authorizations",
                                                        Map.of()))
                                .isInstanceOf(VarlinkException.class)
                                .hasMessageContaining("StreamRequired");
                        final List<Map<String, Object>> seen = new java.util.ArrayList<>();
                        client.callMore(
                                PoreloDaemon.INTERFACE + ".Authorizations",
                                Map.of(),
                                answer -> {
                                    seen.add(answer);
                                    if (seen.size() == 1) {
                                        // Answered - by 'porelo vault authorize' here, or by
                                        // Authorize from anybody.
                                        org.fuin.porelo.app.AuthorizationsNeeded.clear(
                                                context, "search");
                                    }
                                    return seen.size() < 2;
                                });

                        assertThat(seen.getFirst())
                                .containsEntry("credential", "search")
                                .containsEntry("state", "never")
                                .containsEntry("task", "porelo-p-a")
                                .containsEntry("project", "p");
                        assertThat(seen.get(1))
                                .containsEntry("credential", "search")
                                .containsEntry("state", "granted");
                    }
                });
    }

    @Test
    void anInterfaceGetsThisMachinesPublicKeyTheProjectFileSchemaAndACheckOfADraft(
            @TempDir Path dir) throws Exception {

        // What an interface needs to make a project from a repository and bind this machine to it:
        // only public halves, and the editor's rules from the machine itself.
        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> key =
                                client.call(PoreloDaemon.INTERFACE + ".MessageKey", Map.of());
                        assertThat(String.valueOf(key.get("line")))
                                .startsWith(key.get("principal") + " ssh-ed25519 ");
                        assertThat(String.valueOf(key.get("fingerprint"))).startsWith("SHA256:");
                        assertThat(client.call(PoreloDaemon.INTERFACE + ".MessageKey", Map.of()))
                                .as("the same key again")
                                .isEqualTo(key);

                        final Map<String, Object> schema =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".ProjectFileSchema", Map.of());
                        assertThat(schema.get("sections"))
                                .asString()
                                .contains("security_class")
                                .contains("run_file")
                                .doesNotContain("base_image");
                        // Each key described in Porelo's own words, for a form a person new to it
                        // can fill.
                        assertThat((List<?>) schema.get("keys"))
                                .anySatisfy(
                                        described -> {
                                            final Map<?, ?> each = (Map<?, ?>) described;
                                            assertThat(each.get("name"))
                                                    .isEqualTo("security_class");
                                            assertThat(each.get("values"))
                                                    .asString()
                                                    .contains("guarded");
                                            assertThat(each.get("required")).isEqualTo(true);
                                            assertThat(String.valueOf(each.get("describe")))
                                                    .isNotBlank();
                                        });

                        final Map<String, Object> checked =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".CheckProjectFile",
                                        Map.of(
                                                "text",
                                                "project:\n  name: \"p\"\n  securty_class: \"guarded\"\n"));
                        assertThat(checked.get("refused"))
                                .asString()
                                .contains("did you mean 'security_class'");
                    }
                });
    }

    @Test
    void providerModelsSaysWhatAStartNamingNoModelWouldRunOn(@TempDir Path dir) throws Exception {

        // New work shows the model before the start; a client cannot read this machine's files.
        final Path providers = Files.createDirectories(dir.resolve("data/porelo/providers"));
        Files.writeString(
                providers.resolve("provider-a.yaml"),
                """
                name: provider-a
                upstream: https://provider.example.com
                dialects: { preview: "" }
                auth_header: { _default: Authorization }
                auth_prefix: { _default: "Bearer " }
                """);
        final Path file = projectFile(dir);
        Files.writeString(
                file,
                Files.readString(file)
                        + "providers:\n  provider-a:\n    model: \"model-a\"\n"
                        + "    label: \"Model A\"\n");

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        assertThat(
                                        client.call(
                                                PoreloDaemon.INTERFACE + ".ProviderModels",
                                                Map.of("project", PROJECT)))
                                .as("the project's model for the provider this machine knows")
                                .containsEntry(
                                        "models",
                                        List.of(
                                                Map.of(
                                                        "provider",
                                                        "provider-a",
                                                        "model",
                                                        "model-a",
                                                        "label",
                                                        "Model A",
                                                        "from",
                                                        "project")));

                        Files.writeString(
                                file, Files.readString(file).replace("provider-a:", "provider-b:"));
                        assertThatThrownBy(
                                        () ->
                                                client.call(
                                                        PoreloDaemon.INTERFACE + ".ProviderModels",
                                                        Map.of("project", PROJECT)))
                                .as("a provider this machine does not know fails, as Start does")
                                .isInstanceOfSatisfying(
                                        VarlinkException.class,
                                        failed ->
                                                assertThat(failed.getErrorName())
                                                        .isEqualTo(
                                                                PoreloDaemon.INTERFACE
                                                                        + ".Failed"));
                    }
                });
    }

    @Test
    void anInterfaceManagesDestinationsAsTheFilesAPersonWouldEdit(@TempDir Path dir)
            throws Exception {

        // Decided: the interface does all of it - list, read, write, remove.
        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> search =
                                Map.of(
                                        "name",
                                        "brave-search",
                                        "upstream",
                                        "https://api.search.brave.com",
                                        "authHeader",
                                        "X-Subscription-Token");

                        final Map<String, Object> dry =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".WriteDestination",
                                        new java.util.HashMap<>(
                                                Map.of(
                                                        "name",
                                                        "brave-search",
                                                        "upstream",
                                                        "https://api.search.brave.com",
                                                        "authHeader",
                                                        "X-Subscription-Token",
                                                        "dryRun",
                                                        true)));
                        assertThat(dry).containsEntry("written", false);
                        assertThat(
                                        client.call(
                                                        PoreloDaemon.INTERFACE + ".Destinations",
                                                        Map.of())
                                                .get("destinations"))
                                .asList()
                                .as("a dry run writes nothing")
                                .isEmpty();

                        assertThat(
                                        client.call(
                                                PoreloDaemon.INTERFACE + ".WriteDestination",
                                                search))
                                .containsEntry("written", true);
                        final Map<String, Object> read =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".Destination",
                                        Map.of("name", "brave-search"));
                        assertThat(String.valueOf(read))
                                .contains("X-Subscription-Token")
                                .contains("inForce=true")
                                .contains("packaged=false");

                        assertThatThrownBy(
                                        () ->
                                                client.call(
                                                        PoreloDaemon.INTERFACE
                                                                + ".WriteDestination",
                                                        Map.of(
                                                                "name",
                                                                "plain",
                                                                "upstream",
                                                                "http://api.example.com")))
                                .isInstanceOf(VarlinkException.class)
                                .hasMessageContaining("DestinationRefused");

                        assertThat(
                                        client.call(
                                                PoreloDaemon.INTERFACE + ".RemoveDestination",
                                                Map.of("name", "brave-search")))
                                .containsEntry("removed", true);
                        assertThatThrownBy(
                                        () ->
                                                client.call(
                                                        PoreloDaemon.INTERFACE + ".Destination",
                                                        Map.of("name", "brave-search")))
                                .isInstanceOf(VarlinkException.class)
                                .hasMessageContaining("NoSuchDestination");
                    }
                });
    }

    @Test
    void anEndpointOnThisHostIsTakenOnlyInThePersonsRuntimeDirectory(@TempDir Path dir)
            throws Exception {
        final String inside = "unix:" + dir.resolve("run/porelo/mcp/task-1.sock");
        final String outside = "unix:" + dir.resolve("elsewhere/task-1.sock");
        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        assertThat(
                                        client.call(
                                                PoreloDaemon.INTERFACE + ".WriteDestination",
                                                Map.of(
                                                        "name",
                                                        "porelo-mcp",
                                                        "upstream",
                                                        inside,
                                                        "authPrefix",
                                                        "Bearer ")))
                                .containsEntry("written", true);
                        assertThatThrownBy(
                                        () ->
                                                client.call(
                                                        PoreloDaemon.INTERFACE
                                                                + ".WriteDestination",
                                                        Map.of(
                                                                "name",
                                                                "elsewhere",
                                                                "upstream",
                                                                outside)))
                                .isInstanceOf(VarlinkException.class)
                                .hasMessageContaining("DestinationRefused");
                    }
                });
    }

    @Test
    void anUnlockedVaultHoldingNothingIsNotReportedAsLocked(@TempDir Path dir) throws Exception {

        // The whole reason 'readable' exists, and it used to answer this case wrongly: the field
        // was inferred from the credential list being empty, so a vault that was open and simply
        // empty was indistinguishable from one nobody had unlocked. A client acting on that told
        // somebody to unlock a vault that was already open.
        org.junit.jupiter.api.Assumptions.assumeTrue(
                org.fuin.porelo.vault.KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");

        final PoreloContext context = context(dir);
        final org.fuin.porelo.vault.KernelKeyring keyring =
                new org.fuin.porelo.vault.KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            context.vault().write(Map.of(), VAULT_PASSPHRASE);
            keyring.store(VAULT_PASSPHRASE);

            servingContext(
                    context,
                    dir,
                    socket -> {
                        try (VarlinkClient client = new VarlinkClient(socket)) {
                            final Map<String, Object> reply =
                                    client.call(PoreloDaemon.INTERFACE + ".Credentials", Map.of());
                            assertThat(reply)
                                    .containsEntry("exists", true)
                                    .containsEntry("readable", true)
                                    .containsEntry("credentials", List.of());
                        }
                    });
        } finally {
            keyring.forget();
        }
    }

    @Test
    void aLockedVaultIsReportedAsUnreadable(@TempDir Path dir) throws Exception {

        // The other half of the same distinction: nothing is in the keyring, so the daemon cannot
        // read it and has no terminal to ask at. "We cannot tell you until you unlock it" and "it
        // is not there" are different sentences, and only one of them is somebody's problem.
        final PoreloContext context = context(dir);
        Files.createDirectories(context.vault().path().getParent());
        context.vault()
                .write(
                        Map.of("anthropic", org.fuin.porelo.vault.VaultEntry.of("sk-test-value")),
                        VAULT_PASSPHRASE);

        servingContext(
                context,
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(PoreloDaemon.INTERFACE + ".Credentials", Map.of());
                        assertThat(reply)
                                .containsEntry("exists", true)
                                .containsEntry("readable", false)
                                .containsEntry("credentials", List.of());
                    }
                });
    }

    @Test
    void panicListsWhatItWouldStopAndStopsNothing(@TempDir Path dir) throws Exception {

        // The half somebody presses first, and the half that must not act. A preview that stopped
        // anything would be the worst possible surprise in the one command reached for when nobody
        // knows what is wrong.
        // Panic reads its candidates by recorded id and state word, in one listing.
        runner.answering("ps", "111111111111\trunning\n" + "222222222222\texited\n");

        final PoreloContext context = context(dir);
        org.fuin.porelo.wire.Sidecar.recordId(
                context.paths().tasks().containerState("porelo-uc-shell-1"), "111111111111");
        org.fuin.porelo.wire.Sidecar.recordId(
                context.paths().tasks().containerState("porelo-uc-shell-2"), "222222222222");
        runner.answering("{{.State.Status}} 111111111111", "running");
        runner.answering("{{.State.Status}} 222222222222", "exited");
        servingContext(
                context,
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".Panic", Map.of("dryRun", true));

                        assertThat(reply).containsEntry("previewed", true);
                        @SuppressWarnings("unchecked")
                        final List<Map<String, Object>> tasks =
                                (List<Map<String, Object>>) reply.get("tasks");
                        // Only the running one: a task that has exited is not something to stop.
                        assertThat(tasks).hasSize(1);
                        assertThat(tasks.getFirst()).containsEntry("name", "porelo-uc-shell-1");
                    }
                });

        assertThat(runner.lines()).noneMatch(line -> line.startsWith("podman stop"));
    }

    @Test
    void panicStopsEveryRunningTaskAndRemovesNothing(@TempDir Path dir) throws Exception {

        // It stops; it never removes. An operator who believed this cleaned up would go looking for
        // work that is still exactly where it was, which is why nothing here may run 'rm'.
        runner.answering("ps", "111111111111\trunning\n" + "222222222222\trunning\n");

        final PoreloContext base = context(dir);
        final PoreloContext context =
                new PoreloContext(
                        command -> {
                            final var result = runner.run(command);
                            if (!command.describe().contains("{{.State.Status}}")) return result;
                            final String id = command.arguments().getLast();
                            final boolean stopped =
                                    runner.lines().stream()
                                            .anyMatch(
                                                    line ->
                                                            line.startsWith("podman stop ")
                                                                    && line.endsWith(id));
                            return new org.fuin.porelo.core.process.CommandResult(
                                    command, 0, stopped ? "exited" : "running", "");
                        },
                        base.paths(),
                        base.exec());
        org.fuin.porelo.wire.Sidecar.recordId(
                context.paths().tasks().containerState("porelo-uc-shell-1"), "111111111111");
        org.fuin.porelo.wire.Sidecar.recordId(
                context.paths().tasks().containerState("porelo-uc-other-2"), "222222222222");
        servingContext(
                context,
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(PoreloDaemon.INTERFACE + ".Panic", Map.of());

                        assertThat(reply).containsEntry("previewed", false);
                        assertThat(reply.get("surviving"))
                                .as("panic confirmed every stop")
                                .isEqualTo(List.of());
                        @SuppressWarnings("unchecked")
                        final List<Map<String, Object>> tasks =
                                (List<Map<String, Object>>) reply.get("tasks");
                        assertThat(tasks).hasSize(2);
                        assertThat(tasks)
                                .as("each stopped task has no survivors")
                                .allSatisfy(
                                        task ->
                                                assertThat(task.get("surviving"))
                                                        .isEqualTo(List.of()));
                        assertThat(tasks.stream().map(task -> task.get("name")))
                                .containsExactlyInAnyOrder(
                                        "porelo-uc-shell-1", "porelo-uc-other-2");
                    }
                });

        assertThat(runner.lines()).anyMatch(line -> line.startsWith("podman stop"));
        assertThat(runner.lines()).noneMatch(line -> line.startsWith("podman rm"));
    }

    @Test
    void lockSaysWhatItDidAndWhatItCouldNotReach(@TempDir Path dir) throws Exception {

        // A running task's proxy read the credential when it started and holds it in its own
        // memory, where locking cannot reach. Reporting "locked" without saying so would claim more
        // than happened.
        runner.answering("ps", "porelo-uc-shell-1\tUp 4 minutes\n");

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(PoreloDaemon.INTERFACE + ".Lock", Map.of());

                        assertThat(reply).containsKeys("keyring", "wasCached", "holding");
                        // Compared as a number, not by type: a client reads a number exactly but
                        // need not know which Java type carries it.
                        assertThat(((Number) reply.get("holding")).longValue()).isEqualTo(1L);
                        // Nothing was ever cached for this temporary vault, so locking it changed
                        // nothing
                        // - which is not a failure: "not cached" is the wanted state either way.
                        assertThat(reply).containsEntry("wasCached", false);
                    }
                });
    }

    @Test
    void agentsNamesTheInstalledCopyThatNeverRuns(@TempDir Path dir) throws Exception {

        // A shadowed binary is never started, so it has no agent entry to carry a flag - which is
        // why it is reported separately rather than as "inUse: false" on something that is not
        // there. The interface asked for this after building a view for a state that could not
        // occur; the honest shape is the pair, so a person is told what runs instead.
        final Path own = Files.createDirectories(dir.resolve("data/porelo/agents"));
        final Path packaged = Files.createDirectories(dir.resolve("packaged"));
        for (final Path where : List.of(own, packaged)) {
            final Path binary =
                    Files.createFile(
                            where.resolve(
                                    org.fuin.porelo.agent.api.AgentDirectory.PREFIX + "alpha"));
            assertThat(binary.toFile().setExecutable(true)).isTrue();
        }

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
        final PoreloContext context =
                new PoreloContext(
                        runner,
                        new PoreloPaths(xdg, dir.resolve("bin"), dir.resolve("hooks"), packaged),
                        arguments -> 0);

        servingContext(
                context,
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(PoreloDaemon.INTERFACE + ".Agents", Map.of());

                        @SuppressWarnings("unchecked")
                        final List<Map<String, Object>> shadowed =
                                (List<Map<String, Object>>) reply.get("shadowed");
                        assertThat(shadowed).hasSize(1);
                        assertThat(shadowed.getFirst())
                                .containsEntry(
                                        "path", packaged.resolve("porelo-agent-alpha").toString())
                                .containsEntry(
                                        "usedInstead",
                                        own.resolve("porelo-agent-alpha").toString());
                    }
                });
    }

    /** Puts a provider on the machine, so a listing of them has something to list. */
    private void declareProvider(Path dir) throws Exception {
        final Path providers = Files.createDirectories(dir.resolve("data/porelo/providers"));
        Files.writeString(
                providers.resolve("anthropic.yaml"),
                """
                name: anthropic
                label: Anthropic
                upstream: https://api.anthropic.com
                dialects:
                  anthropic-messages: ""
                auth_header:
                  _default: x-api-key
                auth_prefix:
                  _default: ""
                token_env:
                  _default: ANTHROPIC_API_KEY
                """);
    }

    private static org.fuin.porelo.agent.api.ProviderDefinition anthropic() {
        return new org.fuin.porelo.agent.api.ProviderDefinition(
                "anthropic",
                "Anthropic",
                "https://api.anthropic.com",
                Map.of("anthropic-messages", ""),
                Map.of("_default", "x-api-key"),
                Map.of("_default", ""),
                Map.of(),
                Map.of("_default", "ANTHROPIC_API_KEY"));
    }

    private static org.fuin.porelo.vault.VaultEntry entry(String type) {
        return new org.fuin.porelo.vault.VaultEntry("sk-secret", type);
    }

    @Test
    void aCredentialStoredUnderAnAgentsOwnNameIsStillFound() {

        // A vault written before credentials were keyed by provider. The key stays the agent's, so
        // upgrading does not stop anybody authenticating - and an interface that recomputed this
        // from the provider list alone would report a missing credential for exactly the vault that
        // has one.
        final Map<String, Object> row =
                PoreloDaemon.provider(anthropic(), Map.of("claude", entry("oauth")), "claude");

        assertThat(row)
                .containsEntry("credentialName", "claude")
                .containsEntry("authenticated", true)
                .containsEntry("credentialType", "oauth")
                // Where it belongs, not where it was found: offering the agent's name here is how
                // an interface taught somebody to store a new key under 'claude' again.
                .containsEntry("storeCommand", "porelo vault put anthropic");
    }

    @Test
    void theProvidersOwnNameWinsWhenBothAreStored() {

        // Two agents reaching one provider must find one entry. Where both names exist the
        // provider's is the live one, and the agent's is a leftover.
        final Map<String, Object> row =
                PoreloDaemon.provider(
                        anthropic(),
                        Map.of("claude", entry("oauth"), "anthropic", entry("api-key")),
                        "claude");

        assertThat(row)
                .containsEntry("credentialName", "anthropic")
                .containsEntry("credentialType", "api-key");
    }

    @Test
    void anEmptyVaultPointsAtWhereANewCredentialBelongs() {

        final Map<String, Object> row = PoreloDaemon.provider(anthropic(), Map.of(), null);

        assertThat(row)
                .containsEntry("credentialName", "anthropic")
                .containsEntry("authenticated", false)
                .containsEntry("credentialType", "")
                .containsEntry("storeCommand", "porelo vault put anthropic");
    }

    @Test
    void aStoredCredentialOfUnstatedKindReadsAsEmptyRatherThanNull() {

        // "" and not the four characters "null", which an interface would render as a kind.
        final Map<String, Object> row =
                PoreloDaemon.provider(anthropic(), Map.of("anthropic", entry(null)), null);

        assertThat(row).containsEntry("credentialType", "").containsEntry("authenticated", true);
    }

    @Test
    void anOmittedNameIsNotTheSameAsAnEmptyOne() {

        // "no agent named" means "the only one installed"; an empty name is a name nothing matches.
        // Reading both as "" would turn the first into a refusal on a machine with exactly one
        // agent - the commonest machine there is.
        assertThat(PoreloDaemon.absent(Map.of(), "agent")).isNull();
        assertThat(PoreloDaemon.absent(Map.of("agent", ""), "agent")).isNull();
        assertThat(PoreloDaemon.absent(Map.of("agent", "   "), "agent")).isNull();
        assertThat(PoreloDaemon.absent(Map.of("agent", "claude"), "agent")).isEqualTo("claude");
    }

    @Test
    void backupsAreEmptyForAProjectNobodyHasBackedUp(@TempDir Path dir) throws Exception {

        // An ordinary answer rather than an error: it is also what a project backed up by an older
        // Porelo looks like, and neither deserves a fault on screen.
        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        assertThat(
                                        client.call(
                                                PoreloDaemon.INTERFACE + ".Backups",
                                                Map.of("project", "demo")))
                                .containsEntry("backups", List.of());
                    }
                });
    }

    @Test
    void aBackupIsListedWithWhetherItsFileIsStillThere(@TempDir Path dir) throws Exception {

        final Path bundle = dir.resolve("one.bundle");
        Files.writeString(bundle, "not really a bundle");
        new org.fuin.porelo.app.BackupRecords(dir.resolve("data/porelo/backups"))
                .add("demo", bundle, 2);

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        @SuppressWarnings("unchecked")
                        final List<Map<String, Object>> backups =
                                (List<Map<String, Object>>)
                                        client.call(
                                                        PoreloDaemon.INTERFACE + ".Backups",
                                                        Map.of("project", "demo"))
                                                .get("backups");

                        assertThat(backups)
                                .singleElement()
                                .satisfies(
                                        backup -> {
                                            assertThat(backup)
                                                    .containsEntry(
                                                            "bundle",
                                                            bundle.toAbsolutePath().toString())
                                                    .containsEntry("present", true);
                                            assertThat(((Number) backup.get("refs")).intValue())
                                                    .isEqualTo(2);
                                            assertThat(((Number) backup.get("bytes")).longValue())
                                                    .isPositive();
                                        });
                    }
                });
    }

    @Test
    void aPreviewedDeleteRemovesNothing(@TempDir Path dir) throws Exception {

        // The bundle may be somebody's only copy of work that never reached the gate. A preview
        // that removed it would be the worst thing this contract could do.
        final Path bundle = dir.resolve("one.bundle");
        Files.writeString(bundle, "not really a bundle");
        new org.fuin.porelo.app.BackupRecords(dir.resolve("data/porelo/backups"))
                .add("demo", bundle, 3);

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".DeleteBackup",
                                        Map.of(
                                                "project",
                                                "demo",
                                                "bundle",
                                                bundle.toAbsolutePath().toString(),
                                                "dryRun",
                                                true));

                        assertThat(reply).containsEntry("outcome", "PREVIEWED");
                        assertThat(((Number) reply.get("refs")).intValue())
                                .as("what a person is giving up, named before they give it up")
                                .isEqualTo(3);
                        assertThat(bundle).exists();
                    }
                });
    }

    @Test
    void deletingABackupTakesTheFileAndTheRecord(@TempDir Path dir) throws Exception {

        final Path bundle = dir.resolve("one.bundle");
        Files.writeString(bundle, "not really a bundle");
        new org.fuin.porelo.app.BackupRecords(dir.resolve("data/porelo/backups"))
                .add("demo", bundle, 1);

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        assertThat(
                                        client.call(
                                                PoreloDaemon.INTERFACE + ".DeleteBackup",
                                                Map.of(
                                                        "project",
                                                        "demo",
                                                        "bundle",
                                                        bundle.toAbsolutePath().toString())))
                                .containsEntry("outcome", "DELETED")
                                .containsEntry("fileRemoved", true);

                        assertThat(bundle).doesNotExist();
                        assertThat(
                                        client.call(
                                                PoreloDaemon.INTERFACE + ".Backups",
                                                Map.of("project", "demo")))
                                .containsEntry("backups", List.of());
                    }
                });
    }

    @Test
    void aBackupOfAProjectsOtherRepositoryCanBeDeletedAsItIsListed(@TempDir Path dir)
            throws Exception {

        // Backups lists a second repository's bundles under that repository; DeleteBackup takes the
        // project and the path Backups gave, and has to find the record where the listing found it.
        record(
                dir,
                Files.writeString(
                        dir.resolve("project.yml"),
                        """
                project:
                  name: "uc"
                  security_class: "guarded"
                image:
                  from: "ubuntu:24.04"
                repositories:
                  backend:
                    upstream: "git@forge.example.org:uc/backend.git"
                """));
        final Path bundle = dir.resolve("backend.bundle");
        Files.writeString(bundle, "not really a bundle");
        new org.fuin.porelo.app.BackupRecords(dir.resolve("data/porelo/backups"))
                .add(org.fuin.porelo.app.GateSupport.recordKey(PROJECT, "backend"), bundle, 1);

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        assertThat(
                                        (List<?>)
                                                client.call(
                                                                PoreloDaemon.INTERFACE + ".Backups",
                                                                Map.of(
                                                                        "project",
                                                                        PROJECT,
                                                                        "repository",
                                                                        "backend"))
                                                        .get("backups"))
                                .as("the listing the delete is asked from")
                                .hasSize(1);

                        assertThat(
                                        client.call(
                                                PoreloDaemon.INTERFACE + ".DeleteBackup",
                                                Map.of(
                                                        "project",
                                                        PROJECT,
                                                        "bundle",
                                                        bundle.toAbsolutePath().toString())))
                                .as("a backup the listing offered is one the delete finds")
                                .containsEntry("outcome", "DELETED")
                                .containsEntry("fileRemoved", true);
                        assertThat(bundle).doesNotExist();
                        assertThat(
                                        (List<?>)
                                                client.call(
                                                                PoreloDaemon.INTERFACE + ".Backups",
                                                                Map.of(
                                                                        "project",
                                                                        PROJECT,
                                                                        "repository",
                                                                        "backend"))
                                                        .get("backups"))
                                .as("its record goes with it")
                                .isEmpty();
                    }
                });
    }

    @Test
    void workTheUpstreamAlreadyHasIsMarkedAndRejectedOnlyWithForce(@TempDir Path dir)
            throws Exception {

        // The interface rejects through the daemon: a push somebody made upstream by hand was
        // discarded there and recorded as discarded, the opposite of what happened, and the pending
        // list never said it was upstream.
        final Path origin = dir.resolve("origin.git");
        git(dir, "init", "-q", "--bare", "-b", "main", origin.toString());
        record(
                dir,
                Files.writeString(
                        dir.resolve("project.yml"),
                        """
                project:
                  name: "uc"
                  security_class: "guarded"
                  upstream: "%s"
                image:
                  from: "ubuntu:24.04"
                """
                                .formatted(origin)));
        final Path upstreamRecords = Files.createDirectories(dir.resolve("data/porelo/upstream"));
        Files.writeString(
                upstreamRecords.resolve(PROJECT),
                "MEASURED\t0\t" + java.time.Instant.now() + "\t\twriter\n");

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Path mirror =
                                Path.of(
                                        String.valueOf(
                                                client.call(
                                                                PoreloDaemon.INTERFACE + ".Pending",
                                                                Map.of("project", PROJECT))
                                                        .get("mirror")));
                        // A task's start makes the mirror; a view only reads it.
                        git(dir, "init", "-q", "--bare", "-b", "main", mirror.toString());
                        final Path work = dir.resolve("work");
                        git(dir, "init", "-q", "-b", "main", work.toString());
                        git(
                                work,
                                "-c",
                                "user.email=t@example.com",
                                "-c",
                                "user.name=T",
                                "commit",
                                "-q",
                                "--allow-empty",
                                "-m",
                                "pushed by hand");
                        git(
                                work,
                                "push",
                                "-q",
                                mirror.toString(),
                                "HEAD:refs/porelo/incoming/writer");

                        assertThat(
                                        (List<?>)
                                                client.call(
                                                                PoreloDaemon.INTERFACE + ".Pending",
                                                                Map.of("project", PROJECT))
                                                        .get("pending"))
                                .singleElement()
                                .satisfies(
                                        push -> {
                                            final Map<?, ?> row = (Map<?, ?>) push;
                                            assertThat(row.get("name")).isEqualTo("writer");
                                            assertThat(row.get("alreadyUpstream"))
                                                    .as("marked as upstream already")
                                                    .isEqualTo(true);
                                        });

                        assertThatThrownBy(
                                        () ->
                                                client.call(
                                                        PoreloDaemon.INTERFACE + ".Reject",
                                                        Map.of(
                                                                "project", PROJECT, "name",
                                                                "writer")))
                                .isInstanceOfSatisfying(
                                        VarlinkException.class,
                                        refused ->
                                                assertThat(refused.getErrorName())
                                                        .isEqualTo(
                                                                PoreloDaemon.INTERFACE
                                                                        + ".AlreadyUpstream"));
                        assertThat(git(mirror, "for-each-ref", "refs/porelo/incoming/"))
                                .as("nothing was discarded")
                                .contains("writer");

                        assertThat(
                                        client.call(
                                                PoreloDaemon.INTERFACE + ".Reject",
                                                Map.of(
                                                        "project", PROJECT, "name", "writer",
                                                        "force", true)))
                                .containsEntry("rejected", "writer");
                        assertThat(git(mirror, "for-each-ref", "refs/porelo/incoming/"))
                                .doesNotContain("writer");
                    }
                });
    }

    private static String git(final Path in, final String... arguments) {
        final List<String> command = new java.util.ArrayList<>(List.of("git", "-C", in.toString()));
        command.addAll(List.of(arguments));
        final org.fuin.porelo.core.process.CommandResult result =
                new org.fuin.porelo.core.process.ProcessCommandRunner()
                        .run(org.fuin.porelo.core.process.Command.of(command));
        assertThat(result.successful())
                .as(String.join(" ", command) + ": " + result.standardError())
                .isTrue();
        return result.standardOutput().strip();
    }

    @Test
    void deletingSomethingNoRecordNamesIsRefusedRatherThanGuessed(@TempDir Path dir)
            throws Exception {

        // Never delete a path this machine did not write down. A method that removed whatever it
        // was handed would be a file-deletion primitive wearing a backup's name.
        final Path stranger = dir.resolve("somebody-elses.bundle");
        Files.writeString(stranger, "important");

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        assertThat(
                                        client.call(
                                                PoreloDaemon.INTERFACE + ".DeleteBackup",
                                                Map.of(
                                                        "project",
                                                        "demo",
                                                        "bundle",
                                                        stranger.toAbsolutePath().toString())))
                                .containsEntry("outcome", "NO_SUCH_BACKUP");

                        assertThat(stranger).exists();
                    }
                });
    }

    @Test
    void aBundleThatCouldNotBeDeletedKeepsItsRecord(@TempDir Path dir) throws Exception {

        // Forgetting the record when the file is still on disk would hide a bundle that exists -
        // and a backup nobody can see is worse than one somebody has to delete twice.
        //
        // Skipped as root, which ignores directory permissions: the fixture cannot create the
        // failure it needs, and a test that quietly proves nothing is worse than one that says so.
        org.junit.jupiter.api.Assumptions.assumeFalse(
                "root".equals(System.getProperty("user.name")),
                "root ignores the permissions this test relies on");

        final Path locked = Files.createDirectories(dir.resolve("locked"));
        final Path bundle = locked.resolve("one.bundle");
        Files.writeString(bundle, "not really a bundle");
        new org.fuin.porelo.app.BackupRecords(dir.resolve("data/porelo/backups"))
                .add("demo", bundle, 4);
        Files.setPosixFilePermissions(
                locked, java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"));

        try {
            serving(
                    dir,
                    socket -> {
                        try (VarlinkClient client = new VarlinkClient(socket)) {
                            assertThat(
                                            client.call(
                                                    PoreloDaemon.INTERFACE + ".DeleteBackup",
                                                    Map.of(
                                                            "project",
                                                            "demo",
                                                            "bundle",
                                                            bundle.toAbsolutePath().toString())))
                                    .containsEntry("outcome", "FAILED");

                            @SuppressWarnings("unchecked")
                            final List<Map<String, Object>> left =
                                    (List<Map<String, Object>>)
                                            client.call(
                                                            PoreloDaemon.INTERFACE + ".Backups",
                                                            Map.of("project", "demo"))
                                                    .get("backups");
                            assertThat(left)
                                    .as("the record stays while the bundle does")
                                    .hasSize(1);
                        }
                    });
        } finally {
            Files.setPosixFilePermissions(
                    locked, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void importingNeverAnswersWithTheCredentialItRead(@TempDir Path dir) throws Exception {

        // The reason this one is allowed over the socket at all is that no secret crosses it in
        // either direction. A field carrying the value would remove the whole justification.
        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(PoreloDaemon.INTERFACE + ".ImportCredential", Map.of());

                        assertThat(reply.keySet())
                                .containsExactlyInAnyOrder(
                                        "outcome", "name", "type", "length", "source", "detail");
                        assertThat(reply.get("outcome"))
                                .asString()
                                .isIn(
                                        "IMPORTED",
                                        "NO_SUCH_AGENT",
                                        "NO_CONFIG_DIRECTORY",
                                        "NOTHING_TO_IMPORT",
                                        "VAULT_LOCKED",
                                        "FAILED");
                    }
                });
    }

    @Test
    void importingWithNoAgentInstalledSaysSoRatherThanFailing(@TempDir Path dir) throws Exception {

        // A machine with no agents is an ordinary state, not a fault. Reporting it as FAILED would
        // send somebody looking for a broken daemon.
        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(PoreloDaemon.INTERFACE + ".ImportCredential", Map.of());

                        assertThat(reply)
                                .containsEntry("outcome", "NO_SUCH_AGENT")
                                .containsEntry("name", "")
                                .containsEntry("source", "");
                        assertThat(((Number) reply.get("length")).intValue()).isZero();
                        assertThat(reply.get("detail")).asString().isNotBlank();
                    }
                });
    }

    @Test
    void providersNeverAnswerWithACredentialValue(@TempDir Path dir) throws Exception {

        // The invariant the whole contract rests on. A field carrying a value would be one an
        // interface could render, log or put in a crash report without anybody deciding to.
        declareProvider(dir);
        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(PoreloDaemon.INTERFACE + ".Providers", Map.of());

                        @SuppressWarnings("unchecked")
                        final List<Map<String, Object>> providers =
                                (List<Map<String, Object>>) reply.get("providers");
                        assertThat(providers)
                                .allSatisfy(
                                        provider ->
                                                assertThat(provider.keySet())
                                                        .containsExactlyInAnyOrder(
                                                                "name",
                                                                "label",
                                                                "upstream",
                                                                "dialects",
                                                                "authenticated",
                                                                "credentialType",
                                                                "credentialName",
                                                                "storeCommand"));
                        assertThat(providers)
                                .as("a vacuous pass here would prove nothing")
                                .isNotEmpty();
                        // A vault that does not exist is readable and empty. Reporting it
                        // unreadable would send somebody to unlock a store that is not there - the
                        // one instruction that cannot help them.
                        assertThat(reply).containsEntry("readable", true);
                    }
                });
    }

    @Test
    void providersNameTheKeyAndTheCommandThatStoresOne(@TempDir Path dir) throws Exception {

        // storeCommand is the whole answer to "where do I type it", and it is rendered verbatim. A
        // wrong key name makes that advice useless in the way that is hardest to notice: the
        // command succeeds and stores the credential where nothing will look for it.
        declareProvider(dir);
        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        @SuppressWarnings("unchecked")
                        final List<Map<String, Object>> providers =
                                (List<Map<String, Object>>)
                                        client.call(PoreloDaemon.INTERFACE + ".Providers", Map.of())
                                                .get("providers");

                        assertThat(providers)
                                .singleElement()
                                .satisfies(
                                        provider -> {
                                            assertThat(provider)
                                                    .containsEntry("name", "anthropic")
                                                    .containsEntry("label", "Anthropic")
                                                    .containsEntry(
                                                            "upstream", "https://api.anthropic.com")
                                                    .containsEntry(
                                                            "dialects",
                                                            List.of("anthropic-messages"))
                                                    // Nothing is stored, so the key is where a new
                                                    // one belongs.
                                                    .containsEntry("credentialName", "anthropic")
                                                    .containsEntry("authenticated", false)
                                                    .containsEntry("credentialType", "")
                                                    .containsEntry(
                                                            "storeCommand",
                                                            "porelo vault put anthropic");
                                        });
                    }
                });
    }

    @Test
    void doctorAnswersEveryProbeWithANextActionForTheOnesThatFailed(@TempDir Path dir)
            throws Exception {

        // The whole value of this report is that each failing line ends with the one thing to do
        // about it - the dependencies it covers all fail far from their cause. A probe that came
        // back with an empty action would be a line an interface renders as a blank where the
        // answer belongs.
        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(PoreloDaemon.INTERFACE + ".Doctor", Map.of());

                        @SuppressWarnings("unchecked")
                        final List<Map<String, Object>> probes =
                                (List<Map<String, Object>>) reply.get("probes");
                        assertThat(probes).isNotEmpty();
                        assertThat(probes)
                                .allSatisfy(
                                        probe -> {
                                            assertThat(probe.get("name")).asString().isNotBlank();
                                            assertThat(probe.get("state"))
                                                    .asString()
                                                    .isIn("OK", "DEGRADED", "MISSING", "UNKNOWN");
                                            if (!"OK".equals(probe.get("state"))) {
                                                assertThat(probe.get("action"))
                                                        .asString()
                                                        .as(
                                                                "a failing probe must name the next action")
                                                        .isNotBlank();
                                            }
                                        });
                    }
                });
    }

    @Test
    void doctorIsNotReadyExactlyWhenSomethingIsMissing(@TempDir Path dir) throws Exception {

        // 'ready' must mean what the CLI's exit code means. Two summaries of one machine that can
        // disagree is worse than one, because whichever a person saw last is the one they act on.
        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(PoreloDaemon.INTERFACE + ".Doctor", Map.of());

                        @SuppressWarnings("unchecked")
                        final List<Map<String, Object>> probes =
                                (List<Map<String, Object>>) reply.get("probes");
                        final boolean anythingMissing =
                                probes.stream()
                                        .anyMatch(probe -> "MISSING".equals(probe.get("state")));

                        assertThat(reply.get("ready")).isEqualTo(!anythingMissing);
                    }
                });
    }

    @Test
    void tightenListsInADryRunWhatTheRunThenChanges(@TempDir Path dir) throws Exception {
        serving(
                dir,
                socket -> {
                    // The data root is XDG_DATA_HOME/porelo.
                    final Path registry = dir.resolve("data/porelo/projects/one");
                    Files.createDirectories(registry.getParent());
                    Files.writeString(registry, "x");
                    Files.setPosixFilePermissions(
                            registry,
                            java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw-r--"));
                    final Map<String, Object> change =
                            Map.of(
                                    "path",
                                    registry.toString(),
                                    "from",
                                    "rw-rw-r--",
                                    "to",
                                    "rw-------");
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> dry =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".Tighten",
                                        Map.of("dryRun", true));

                        assertThat(dry.get("changes"))
                                .as(
                                        "the dry run names the file, from its mode to the tightened one")
                                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                                .contains(change);
                        assertThat(Files.getPosixFilePermissions(registry))
                                .as("a dry run changes no mode")
                                .contains(PosixFilePermission.OTHERS_READ);

                        final Map<String, Object> run =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".Tighten",
                                        Map.of("dryRun", false));

                        assertThat(run.get("changes"))
                                .as("the run changes what the dry run listed")
                                .isEqualTo(dry.get("changes"));
                        assertThat(run.get("left"))
                                .as("nothing it could not change")
                                .isEqualTo(List.of());
                        assertThat(Files.getPosixFilePermissions(registry))
                                .as("the run takes what others had")
                                .containsExactlyInAnyOrder(
                                        PosixFilePermission.OWNER_READ,
                                        PosixFilePermission.OWNER_WRITE);
                    }
                });
    }

    @Test
    void nodeAnswersTheSameIdentityToEveryCaller(@TempDir Path dir) throws Exception {

        // Two clients reaching one node by different routes have to be able to tell that it is one
        // node. If this differed per call they would conclude the opposite of the truth.
        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Object first =
                                client.call(PoreloDaemon.INTERFACE + ".Node", Map.of()).get("id");
                        final Object second =
                                client.call(PoreloDaemon.INTERFACE + ".Node", Map.of()).get("id");

                        assertThat(first).asString().isNotBlank();
                        assertThat(first).isEqualTo(second);
                    }
                });
    }

    @Test
    void processNamesThisProcessAndTheExecutableTheKernelSaysItRuns(@TempDir Path dir)
            throws Exception {

        // The deploy decides from this answer whether the account's own daemon runs, since a daemon
        // that is not dumpable shows /proc/<pid>/exe to root only: a wrong answer lets it go on
        // measuring a binary nobody meant to test.
        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(PoreloDaemon.INTERFACE + ".Process", Map.of());

                        assertThat(((Number) reply.get("pid")).longValue())
                                .as("the pid of the process serving the socket")
                                .isEqualTo(ProcessHandle.current().pid());
                        assertThat(reply.get("executable"))
                                .as("where the kernel says the serving process was started from")
                                .isEqualTo(
                                        Files.readSymbolicLink(Path.of("/proc/self/exe"))
                                                .toString());
                    }
                });
    }

    @Test
    void agentsSaysNothingIsShadowedWhenNothingIs(@TempDir Path dir) throws Exception {

        // A field that always names something teaches an interface to ignore it.
        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(PoreloDaemon.INTERFACE + ".Agents", Map.of());
                        assertThat(reply).containsEntry("shadowed", List.of());
                    }
                });
    }

    @Test
    void aVerifiedArtifactCarriesItsDigestAndNoReason() {

        // Only two artifacts can exist: InstallArtifact refuses one with neither a digest nor a
        // stated reason, which is worth knowing before designing a screen for a third state. It
        // refused the one this test first asserted.
        final Map<String, Object> row =
                PoreloDaemon.artifact(
                        new org.fuin.porelo.agent.api.InstallArtifact(
                                "https://example.test/tool",
                                "a".repeat(64),
                                "/usr/local/bin/tool",
                                "0755",
                                false,
                                null));

        assertThat(row)
                .containsEntry("sha256", "a".repeat(64))
                .containsEntry("unverified", false)
                // "" and not the four characters "null", which would render as a reason.
                .containsEntry("reason", "")
                .containsEntry("url", "https://example.test/tool")
                .containsEntry("target", "/usr/local/bin/tool");
    }

    @Test
    void anArtifactThatIsUnverifiableOnPurposeKeepsItsReason() {

        // Three renderable states rather than one blank field: verified, nothing to fetch, and
        // unverifiable for a stated reason. The last is a decision somebody made, not a fault, and
        // it reads as a fault without the reason beside it.
        final Map<String, Object> row =
                PoreloDaemon.artifact(
                        new org.fuin.porelo.agent.api.InstallArtifact(
                                "https://example.test/tool",
                                null,
                                "/usr/local/bin/tool",
                                "0755",
                                true,
                                "the vendor publishes no digest"));

        assertThat(row)
                .containsEntry("unverified", true)
                .containsEntry("reason", "the vendor publishes no digest")
                // Absent as "", never as "null": an interface cannot tell that from a digest.
                .containsEntry("sha256", "");
    }

    @Test
    void canStartAnswersRatherThanThrowingWhenNothingCanRun(@TempDir Path dir) throws Exception {

        // The whole point of the method: a refusal is an ANSWER a client branches on, not an
        // exception and not an exit code with prose after it. one criterion is that a missing
        // credential is reported before anything is built or started, and reporting it as a thrown
        // error would put the client back to reading text.
        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(PoreloDaemon.INTERFACE + ".CanStart", Map.of());

                        assertThat(reply)
                                .containsEntry("ready", false)
                                .containsEntry("outcome", "NO_AGENT")
                                .containsEntry("agent", "")
                                .containsEntry("credential", "");
                        assertThat(String.valueOf(reply.get("detail"))).isNotBlank();
                    }
                });
    }

    @Test
    void canStartNamesTheMissingHostBuildsCredentialWithoutHoldingTheStart(@TempDir Path dir)
            throws Exception {
        // A client must offer the host's remedy without blocking work or parsing a diagnostic, and
        // name the forge whose builds the entry reads.
        final Map<String, Object> reply = hostBuildsCheck(dir, true, false, "host-builds");
        assertThat(reply).containsEntry("ready", true).containsEntry("outcome", "READY");
        assertThat(reply.get("missing"))
                .isEqualTo(
                        List.of(
                                Map.of(
                                        "for",
                                        "builds",
                                        "credential",
                                        "host-builds",
                                        "forge",
                                        "host-forge",
                                        "storeCommand",
                                        List.of("porelo", "host", "forge-token"),
                                        "afterStore",
                                        "ask-again")));
    }

    @Test
    void canStartStopsAskingOnceTheHostBuildsEntryIsPresent(@TempDir Path dir) throws Exception {
        // A stale missing answer would rotate a token the task could already use.
        assertThat(hostBuildsCheck(dir, true, true, "host-builds"))
                .containsEntry("ready", true)
                .containsEntry("missing", List.of());
    }

    @Test
    void canStartOffersNoHostTokenOffAHost(@TempDir Path dir) throws Exception {
        // A stray host file must not send somebody to a command unavailable on their machine.
        assertThat(hostBuildsCheck(dir, false, false, "host-builds"))
                .containsEntry("ready", true)
                .containsEntry("missing", List.of());
    }

    @Test
    void canStartPreservesTheConfiguredBuildsCredentialName(@TempDir Path dir) throws Exception {
        // Inventing the default vault key would ask for a token under the wrong name.
        assertThat(hostBuildsCheck(dir, true, false, "forge.person").get("missing"))
                .isEqualTo(
                        List.of(
                                Map.of(
                                        "for",
                                        "builds",
                                        "credential",
                                        "forge.person",
                                        "forge",
                                        "host-forge",
                                        "storeCommand",
                                        List.of("porelo", "host", "forge-token"),
                                        "afterStore",
                                        "ask-again")));
    }

    @Test
    void canStartDoesNotMistakeAShutVaultForAMissingHostToken(@TempDir Path dir) throws Exception {
        // Rotating a token cannot fix a vault the daemon cannot read.
        final PoreloContext context = context(dir);
        context.vault().write(Map.of(), VAULT_PASSPHRASE);
        final Path hosted = Files.createDirectories(dir.resolve("hosted"));
        Files.writeString(
                hosted.resolve("uc.json"),
                org.fuin.porelo.wire.Json.write(
                        Map.of(
                                "project",
                                "uc",
                                "revision",
                                "host-revision",
                                "address",
                                "https://forge.example/uc.git",
                                "signing_keys",
                                List.of(),
                                "host",
                                "build",
                                "builds",
                                Map.of("forge", "host-forge", "credential", "host-builds"))));
        final Map<String, Object> reply =
                org.fuin.porelo.app.StartCheck.withBuilds(
                                context,
                                new org.fuin.porelo.app.StartCheck.Result(
                                        org.fuin.porelo.app.StartCheck.Outcome.READY,
                                        "",
                                        "",
                                        "",
                                        ""),
                                projectFile(dir),
                                true,
                                true,
                                new org.fuin.porelo.app.OrganizationProjects(
                                        context.paths().projects().organizationProjects(), hosted))
                        .asMap();
        assertThat(reply)
                .as("unlocking is the only useful action")
                .containsEntry("outcome", "VAULT_LOCKED")
                .containsEntry("ready", false)
                .containsEntry("missing", List.of());
    }

    @Test
    void canStartAsksForNoHostTokenWithoutAProjectOrGate(@TempDir Path dir) throws Exception {
        // Asking for a token that no build reader could use would rotate it needlessly.
        final PoreloContext context = context(dir);
        final var projects =
                new org.fuin.porelo.app.OrganizationProjects(
                        context.paths().projects().organizationProjects(), dir.resolve("hosted"));
        final var ready =
                new org.fuin.porelo.app.StartCheck.Result(
                        org.fuin.porelo.app.StartCheck.Outcome.READY, "", "", "", "");
        assertThat(
                        org.fuin.porelo.app.StartCheck.withBuilds(
                                        context, ready, null, true, true, projects)
                                .asMap())
                .containsEntry("missing", List.of());
        assertThat(
                        org.fuin.porelo.app.StartCheck.withBuilds(
                                        context, ready, projectFile(dir), false, true, projects)
                                .asMap())
                .containsEntry("missing", List.of());
    }

    private Map<String, Object> hostBuildsCheck(
            Path dir, boolean onHost, boolean stored, String credential) throws Exception {
        final PoreloContext context = context(dir).asking(shown -> VAULT_PASSPHRASE.clone());
        if (stored) {
            context.vault()
                    .write(
                            Map.of(
                                    credential,
                                    new org.fuin.porelo.vault.VaultEntry(
                                            "fixture-build-token", "api-key")),
                            VAULT_PASSPHRASE);
            assertThat(context.openerAsking(false, new java.io.PrintWriter(System.err)))
                    .isPresent();
        }
        try {
            final Path project = projectFile(dir);
            final Path hosted = Files.createDirectories(dir.resolve("hosted"));
            Files.writeString(
                    hosted.resolve("uc.json"),
                    org.fuin.porelo.wire.Json.write(
                            Map.of(
                                    "project",
                                    "uc",
                                    "revision",
                                    "host-revision",
                                    "address",
                                    "https://forge.example/uc.git",
                                    "signing_keys",
                                    List.of(),
                                    "host",
                                    "build",
                                    "builds",
                                    Map.of("forge", "host-forge", "credential", credential))));
            return org.fuin.porelo.app.StartCheck.withBuilds(
                            context,
                            new org.fuin.porelo.app.StartCheck.Result(
                                    org.fuin.porelo.app.StartCheck.Outcome.READY, "", "", "", ""),
                            project,
                            true,
                            onHost,
                            new org.fuin.porelo.app.OrganizationProjects(
                                    context.paths().projects().organizationProjects(), hosted))
                    .asMap();
        } finally {
            context.forgetTyped();
        }
    }

    @Test
    void canStartNamesTheProjectItDoesNotHave(@TempDir Path dir) throws Exception {

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        // An answer, not an error. Asking whether work can start is a question a
                        // client is entitled to ask about a project that turns out not to exist,
                        // and throwing would hand it an exception for asking.
                        final Map<String, Object> reply =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".CanStart",
                                        Map.of("project", "nowhere"));

                        assertThat(reply).containsEntry("outcome", "NO_PROJECT_FILE");
                        assertThat(String.valueOf(reply.get("detail"))).contains("nowhere");
                    }
                });
    }

    @Test
    void labellingSomethingThatIsNotATaskAnswersRatherThanThrows(@TempDir Path dir)
            throws Exception {

        // An outcome a client branches on, like every other refusal here. A thrown error would put
        // the interface back to reading text to find out what happened.
        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".Label",
                                        Map.of(
                                                "task",
                                                "not-a-porelo-container",
                                                "label",
                                                "anything"));

                        assertThat(reply)
                                .containsEntry("outcome", "NOT_A_TASK")
                                .containsEntry("label", "");
                    }
                });
    }

    @Test
    void aReviewWithNoBaseNamedShowsTheWholePushAgainstTheDefaultBranch(@TempDir Path dir)
            throws Exception {

        reviewWithBase(dir, false);
    }

    @Test
    void aReviewUsesTheRecordedStartingBranchOverTheSocket(@TempDir Path dir) throws Exception {
        reviewWithBase(dir, true);
    }

    // The window checks one Assisted-by and no Co-Authored-By, and shows who made each commit.
    @Test
    @SuppressWarnings("unchecked")
    void aReviewAndAPendingPushNameEachCommitsMakersAndTrailers(@TempDir Path dir)
            throws Exception {
        final Map<String, Object> review = reviewWithBase(dir, false);

        assertThat((java.util.List<Map<String, Object>>) review.get("commits"))
                .extracting(commit -> commit.get("subject"), commit -> commit.get("author"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("second.txt", "T <t@example.com>"),
                        org.assertj.core.groups.Tuple.tuple("first.txt", "T <t@example.com>"));
        assertThat((java.util.List<Map<String, Object>>) review.get("commits"))
                .allSatisfy(
                        commit -> {
                            assertThat(commit.get("committer")).isEqualTo("T <t@example.com>");
                            assertThat(commit.get("trailers")).isEqualTo(java.util.List.of());
                        });
        // The whole message too: a Co-Authored-By outside the trailer block is only there.
        assertThat((java.util.List<Map<String, Object>>) review.get("commits"))
                .extracting(commit -> commit.get("message"))
                .containsExactly("second.txt\n", "first.txt\n");
        assertThat((Map<String, Object>) review.get("pendingForTest"))
                .containsEntry("author", "T <t@example.com>")
                .containsEntry("committer", "T <t@example.com>")
                .containsEntry("trailers", java.util.List.of())
                .containsEntry("message", "second.txt\n");
    }

    private Map<String, Object> reviewWithBase(Path dir, boolean develop) throws Exception {
        // The contract says "Default: the upstream's default branch"; the daemon showed the push's
        // last commit alone, and a client saw half of the work.
        final PoreloContext context = context(dir);
        final org.fuin.porelo.core.process.ProcessCommandRunner git =
                new org.fuin.porelo.core.process.ProcessCommandRunner();
        final java.util.function.BiConsumer<Path, String[]> in =
                (where, arguments) -> {
                    final java.util.List<String> all =
                            new java.util.ArrayList<>(
                                    java.util.List.of(
                                            "git",
                                            "-C",
                                            where.toString(),
                                            "-c",
                                            "user.email=t@example.com",
                                            "-c",
                                            "user.name=T"));
                    all.addAll(java.util.List.of(arguments));
                    git.runOrFail(org.fuin.porelo.core.process.Command.of(all));
                };
        final Path checkout = Files.createDirectories(dir.resolve("app"));
        in.accept(checkout, new String[] {"init", "-q", "-b", "main"});
        Files.writeString(checkout.resolve("README.md"), "app\n");
        in.accept(checkout, new String[] {"add", "."});
        in.accept(checkout, new String[] {"commit", "-q", "-m", "start"});
        new org.fuin.porelo.app.DefaultProject(context)
                .add(checkout.toString(), "app", checkout.toString(), "");
        final org.fuin.porelo.core.project.Project project =
                org.fuin.porelo.app.GateSupport.byName(context, "default");
        final org.fuin.porelo.gate.GitGate gate =
                org.fuin.porelo.app.GateSupport.gate(
                        context, project, project.repository("app"), null, null);
        gate.initialize();
        if (develop) {
            in.accept(checkout, new String[] {"checkout", "-q", "-b", "develop"});
            Files.writeString(checkout.resolve("earlier.txt"), "already in develop\n");
            in.accept(checkout, new String[] {"add", "."});
            in.accept(checkout, new String[] {"commit", "-q", "-m", "earlier"});
            in.accept(
                    checkout,
                    new String[] {"push", "-q", gate.mirror().toString(), "develop:develop"});
            new org.fuin.porelo.app.TaskBase("develop", "CHECKOUT")
                    .writeForGate(gate.mirror(), "t");
        }
        final Path work = dir.resolve("work");
        git.runOrFail(
                org.fuin.porelo.core.process.Command.of(
                        "git", "clone", "-q", gate.mirror().toString(), work.toString()));
        if (develop) {
            in.accept(work, new String[] {"checkout", "-q", "develop"});
        }
        for (final String file : java.util.List.of("first.txt", "second.txt")) {
            Files.writeString(work.resolve(file), file + "\n");
            in.accept(work, new String[] {"add", "."});
            in.accept(work, new String[] {"commit", "-q", "-m", file});
        }
        in.accept(
                work,
                new String[] {
                    "push", "-q", gate.mirror().toString(), "HEAD:refs/porelo/incoming/t"
                });

        final java.util.concurrent.atomic.AtomicReference<Map<String, Object>> answered =
                new java.util.concurrent.atomic.AtomicReference<>();
        servingContext(
                context,
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> review =
                                new java.util.LinkedHashMap<>(
                                        client.call(
                                                PoreloDaemon.INTERFACE + ".Review",
                                                Map.of(
                                                        "project",
                                                        "default",
                                                        "repository",
                                                        "app",
                                                        "name",
                                                        "t")));
                        answered.set(review);
                        assertThat(
                                        ((java.util.List<?>) review.get("files"))
                                                .stream()
                                                        .map(
                                                                file ->
                                                                        String.valueOf(
                                                                                ((Map<?, ?>) file)
                                                                                        .get(
                                                                                                "path")))
                                                        .toList())
                                .containsExactlyInAnyOrder("first.txt", "second.txt");
                        assertThat(String.valueOf(review.get("log")))
                                .contains("first.txt")
                                .contains("second.txt");
                        final Map<String, Object> pending =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".Pending",
                                        Map.of("project", "default", "repository", "app"));
                        review.put(
                                "pendingForTest",
                                ((java.util.List<?>) pending.get("pending")).getFirst());
                    }
                });
        return answered.get();
    }

    @Test
    void aReviewNamesTheHandInAPushedFileCameInThrough(@TempDir Path dir) throws Exception {
        // A reviewer tells what the agent was given from what it made by the content, at any path.
        final Path record = dir.resolve("state/porelo/hand-ins/porelo-default-t.jsonl");
        Files.createDirectories(record.getParent());
        Files.writeString(
                record,
                "{\"event\":\"given\",\"name\":\"handed.txt\",\"bytes\":10,"
                        + "\"sha256\":\"baba894a4c278efe2a4c8f29f960e63bdb7cf57338f20f38904e1cfeb179733c\","
                        + "\"at\":\"2026-11-08T09:00:00Z\",\"by\":\"alice\",\"run\":\"r\","
                        + "\"blobs\":{\"sha1\":\"a20a2a96ff42adf7ecb4b6424b714e9dc55249d7\","
                        + "\"sha256\":\"da22591943cc2ef8885e5d12bc9935c55f5588d7b22174e01009ee5829b2b5c6\"}}\n");

        final Map<String, Object> review = reviewWithBase(dir, false);

        final Map<String, Map<?, ?>> byPath = new java.util.LinkedHashMap<>();
        for (final Object file : (java.util.List<?>) review.get("files")) {
            byPath.put(String.valueOf(((Map<?, ?>) file).get("path")), (Map<?, ?>) file);
        }
        assertThat(byPath.get("first.txt").get("origin"))
                .as("the pushed file with the handed-in content names that hand-in")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("kind", "HAND_IN")
                .containsEntry("name", "handed.txt")
                .containsEntry("by", "alice");
        assertThat(byPath.get("second.txt").containsKey("origin"))
                .as("the agent's own file carries no origin")
                .isFalse();
    }

    @Test
    void aReviewCanIgnoreLineEndingsWithoutHidingRealEdits(@TempDir Path dir) throws Exception {

        // Guards the client contract and keeps real edits visible when line endings swamp a review.
        final PoreloContext context = context(dir);
        final org.fuin.porelo.core.process.ProcessCommandRunner git =
                new org.fuin.porelo.core.process.ProcessCommandRunner();
        final java.util.function.BiConsumer<Path, String[]> in =
                (where, arguments) -> {
                    final java.util.List<String> all =
                            new java.util.ArrayList<>(
                                    java.util.List.of(
                                            "git",
                                            "-C",
                                            where.toString(),
                                            "-c",
                                            "user.email=t@example.com",
                                            "-c",
                                            "user.name=T",
                                            "-c",
                                            "core.autocrlf=false"));
                    all.addAll(java.util.List.of(arguments));
                    git.runOrFail(
                            new org.fuin.porelo.core.process.Command(
                                    all,
                                    null,
                                    Map.of(
                                            "GIT_CONFIG_GLOBAL",
                                            "/dev/null",
                                            "GIT_CONFIG_NOSYSTEM",
                                            "1"),
                                    null));
                };
        final Path checkout = Files.createDirectories(dir.resolve("app"));
        in.accept(checkout, new String[] {"init", "-q", "-b", "main"});
        Files.writeString(checkout.resolve("mixed.txt"), "one\r\ntwo\r\nthree\r\n");
        Files.writeString(checkout.resolve("pure.txt"), "unchanged\r\n");
        Files.writeString(checkout.resolve("ordinary.txt"), "before\n");
        Files.write(checkout.resolve("binary.dat"), new byte[] {0, 1});
        in.accept(checkout, new String[] {"add", "."});
        in.accept(checkout, new String[] {"commit", "-q", "-m", "start"});
        new org.fuin.porelo.app.DefaultProject(context)
                .add(checkout.toString(), "app", checkout.toString(), "");
        final org.fuin.porelo.core.project.Project project =
                org.fuin.porelo.app.GateSupport.byName(context, "default");
        final org.fuin.porelo.gate.GitGate gate =
                org.fuin.porelo.app.GateSupport.gate(
                        context, project, project.repository("app"), null, null);
        gate.initialize();
        final Path work = dir.resolve("work");
        in.accept(dir, new String[] {"clone", "-q", gate.mirror().toString(), work.toString()});
        Files.writeString(work.resolve("mixed.txt"), "one\ntwo changed\nthree\n");
        Files.writeString(work.resolve("pure.txt"), "unchanged\n");
        in.accept(work, new String[] {"add", "."});
        in.accept(work, new String[] {"commit", "-q", "-m", "line endings and a real edit"});
        Files.writeString(work.resolve("ordinary.txt"), "after\n");
        Files.write(work.resolve("binary.dat"), new byte[] {0, 2});
        in.accept(work, new String[] {"add", "."});
        in.accept(work, new String[] {"commit", "-q", "-m", "change"});
        in.accept(
                work,
                new String[] {
                    "push", "-q", gate.mirror().toString(), "HEAD:refs/porelo/incoming/t"
                });

        servingContext(
                context,
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> parameters =
                                new java.util.LinkedHashMap<>(
                                        Map.of(
                                                "project",
                                                "default",
                                                "repository",
                                                "app",
                                                "name",
                                                "t"));
                        final Map<String, Object> raw =
                                client.call(PoreloDaemon.INTERFACE + ".Review", parameters);
                        parameters.put("ignoreLineEndings", false);
                        assertThat(client.call(PoreloDaemon.INTERFACE + ".Review", parameters))
                                .as("false preserves the default review")
                                .isEqualTo(raw);
                        parameters.put("ignoreLineEndings", true);
                        final Map<String, Object> ignored =
                                client.call(PoreloDaemon.INTERFACE + ".Review", parameters);
                        final var softly = new org.assertj.core.api.SoftAssertions();
                        softly.assertThat(String.valueOf(raw.get("diff")))
                                .as("the default patch includes line ending changes")
                                .contains("-unchanged\r", "+unchanged\n");
                        softly.assertThat(String.valueOf(ignored.get("diff")))
                                .as("the switch removes only end-of-line whitespace noise")
                                .doesNotContain("diff --git a/pure.txt", "-one\r", "+one\n")
                                .contains("-two\r", "+two changed\n", "-before\n", "+after\n");
                        softly.assertThat(ignored.get("files"))
                                .as("switch preserves counts and ranking")
                                .isEqualTo(raw.get("files"));
                        softly.assertThat(ignored.get("log"))
                                .as("switch preserves the log")
                                .isEqualTo(raw.get("log"));
                        softly.assertThat(
                                        ((List<?>) raw.get("files"))
                                                .stream()
                                                        .map(
                                                                value ->
                                                                        String.valueOf(
                                                                                ((Map<?, ?>) value)
                                                                                        .get(
                                                                                                "path")))
                                                        .toList())
                                .as("all changed files remain available to the reviewer")
                                .containsExactlyInAnyOrder(
                                        "mixed.txt", "pure.txt", "ordinary.txt", "binary.dat");
                        for (final Object value : (List<?>) raw.get("files")) {
                            final Map<?, ?> row = (Map<?, ?>) value;
                            final String path = String.valueOf(row.get("path"));
                            final int count =
                                    path.equals("binary.dat")
                                            ? -1
                                            : path.equals("pure.txt") ? 0 : 1;
                            softly.assertThat(
                                            ((Number) row.get("addedIgnoringLineEndings"))
                                                    .longValue())
                                    .as("%s normalized additions", path)
                                    .isEqualTo((long) count);
                            softly.assertThat(
                                            ((Number) row.get("removedIgnoringLineEndings"))
                                                    .longValue())
                                    .as("%s normalized removals", path)
                                    .isEqualTo((long) count);
                            final int rawCount =
                                    path.equals("mixed.txt")
                                            ? 3
                                            : path.equals("binary.dat") ? -1 : 1;
                            softly.assertThat(((Number) row.get("added")).longValue())
                                    .as("%s raw additions", path)
                                    .isEqualTo((long) rawCount);
                            softly.assertThat(((Number) row.get("removed")).longValue())
                                    .as("%s raw removals", path)
                                    .isEqualTo((long) rawCount);
                        }
                        softly.assertAll();
                    }
                });
    }

    @Test
    void approvingOntoABranchThatHoldsEarlierWorkIsRefusedByName(@TempDir Path dir)
            throws Exception {

        // A task's name came back: its upstream still holds porelo/t from earlier work. Approve
        // answered Failed with git's "! [rejected] (non-fast-forward)"; an interface could only
        // show the text.
        final PoreloContext context = context(dir);
        final org.fuin.porelo.core.process.ProcessCommandRunner git =
                new org.fuin.porelo.core.process.ProcessCommandRunner();
        final java.util.function.BiConsumer<Path, String[]> in =
                (where, arguments) -> {
                    final java.util.List<String> all =
                            new java.util.ArrayList<>(
                                    java.util.List.of(
                                            "git",
                                            "-C",
                                            where.toString(),
                                            "-c",
                                            "user.email=t@example.com",
                                            "-c",
                                            "user.name=T"));
                    all.addAll(java.util.List.of(arguments));
                    git.runOrFail(org.fuin.porelo.core.process.Command.of(all));
                };
        final Path checkout = Files.createDirectories(dir.resolve("app"));
        in.accept(checkout, new String[] {"init", "-q", "-b", "main"});
        Files.writeString(checkout.resolve("README.md"), "app\n");
        in.accept(checkout, new String[] {"add", "."});
        in.accept(checkout, new String[] {"commit", "-q", "-m", "start"});
        // The earlier task's work, on a branch of its own that has nothing in common with what
        // comes now.
        in.accept(checkout, new String[] {"checkout", "-q", "--orphan", "porelo/t"});
        Files.writeString(checkout.resolve("earlier.txt"), "earlier\n");
        in.accept(checkout, new String[] {"add", "earlier.txt"});
        in.accept(checkout, new String[] {"commit", "-q", "-m", "earlier work"});
        final String held =
                git.runOrFail(
                                org.fuin.porelo.core.process.Command.of(
                                        "git", "-C", checkout.toString(), "rev-parse", "HEAD"))
                        .standardOutput()
                        .strip();
        in.accept(checkout, new String[] {"checkout", "-q", "main"});
        new org.fuin.porelo.app.DefaultProject(context)
                .add(checkout.toString(), "app", checkout.toString(), "");
        final org.fuin.porelo.core.project.Project project =
                org.fuin.porelo.app.GateSupport.byName(context, "default");
        final org.fuin.porelo.gate.GitGate gate =
                org.fuin.porelo.app.GateSupport.gate(
                        context, project, project.repository("app"), null, null);
        gate.initialize();
        final Path work = dir.resolve("work");
        git.runOrFail(
                org.fuin.porelo.core.process.Command.of(
                        "git", "clone", "-q", gate.mirror().toString(), work.toString()));
        Files.writeString(work.resolve("new.txt"), "new\n");
        in.accept(work, new String[] {"add", "."});
        in.accept(work, new String[] {"commit", "-q", "-m", "new work"});
        in.accept(
                work,
                new String[] {
                    "push", "-q", gate.mirror().toString(), "HEAD:refs/porelo/incoming/t"
                });

        servingContext(
                context,
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        assertThatThrownBy(
                                        () ->
                                                client.call(
                                                        PoreloDaemon.INTERFACE + ".Approve",
                                                        Map.of(
                                                                "project",
                                                                "default",
                                                                "repository",
                                                                "app",
                                                                "name",
                                                                "t",
                                                                "branch",
                                                                "porelo/t")))
                                .isInstanceOfSatisfying(
                                        VarlinkException.class,
                                        refused -> {
                                            assertThat(refused.getErrorName())
                                                    .isEqualTo(
                                                            PoreloDaemon.INTERFACE
                                                                    + ".BranchExists");
                                            assertThat(refused.getParameters())
                                                    .containsEntry("branch", "porelo/t")
                                                    .containsEntry("at", held);
                                        });
                    }
                });
        assertThat(
                        git.runOrFail(
                                        org.fuin.porelo.core.process.Command.of(
                                                "git",
                                                "-C",
                                                checkout.toString(),
                                                "rev-parse",
                                                "porelo/t"))
                                .standardOutput()
                                .strip())
                .as("nothing was pushed")
                .isEqualTo(held);
    }

    @Test
    void startingATaskWhoseNamesEarlierWorkWaitsIsRefusedByName(@TempDir Path dir)
            throws Exception {

        // A guarded task's name came back while its earlier work still waits at the gate: Start
        // answered the general start failure with the daemon's words, and an interface could not
        // offer that work or another name. With the records checked, as the real daemon checks
        // them: without, it passed here while the daemon answered that the task was no task Porelo
        // made.
        final PoreloContext hooked = hooked(dir);
        final PoreloContext context =
                new PoreloContext(
                        routed(hooked.paths()), hooked.paths(), arguments -> 0, name -> null, true);
        final org.fuin.porelo.core.process.ProcessCommandRunner git =
                new org.fuin.porelo.core.process.ProcessCommandRunner();
        final java.util.function.BiConsumer<Path, String[]> in =
                (where, arguments) -> {
                    final java.util.List<String> all =
                            new java.util.ArrayList<>(
                                    java.util.List.of(
                                            "git",
                                            "-C",
                                            where.toString(),
                                            "-c",
                                            "user.email=t@example.com",
                                            "-c",
                                            "user.name=T"));
                    all.addAll(java.util.List.of(arguments));
                    git.runOrFail(org.fuin.porelo.core.process.Command.of(all));
                };
        final Path checkout = Files.createDirectories(dir.resolve("app"));
        in.accept(checkout, new String[] {"init", "-q", "-b", "main"});
        Files.writeString(checkout.resolve("README.md"), "app\n");
        in.accept(checkout, new String[] {"add", "."});
        in.accept(checkout, new String[] {"commit", "-q", "-m", "start"});
        new org.fuin.porelo.app.DefaultProject(context)
                .add(checkout.toString(), "app", checkout.toString(), "");
        final org.fuin.porelo.core.project.Project project =
                org.fuin.porelo.app.GateSupport.byName(context, "default");
        final org.fuin.porelo.gate.GitGate gate =
                org.fuin.porelo.app.GateSupport.gate(
                        context, project, project.repository("app"), null, null);
        gate.initialize();
        final Path work = dir.resolve("work");
        git.runOrFail(
                org.fuin.porelo.core.process.Command.of(
                        "git", "clone", "-q", gate.mirror().toString(), work.toString()));
        Files.writeString(work.resolve("earlier.txt"), "earlier\n");
        in.accept(work, new String[] {"add", "."});
        in.accept(work, new String[] {"commit", "-q", "-m", "the earlier task's work"});
        in.accept(
                work,
                new String[] {
                    "push", "-q", gate.mirror().toString(), "HEAD:refs/porelo/incoming/t"
                });
        final String waiting =
                git.runOrFail(
                                org.fuin.porelo.core.process.Command.of(
                                        "git", "-C", work.toString(), "rev-parse", "HEAD"))
                        .standardOutput()
                        .strip();
        runner.answering("podman version", "5.8.1");

        servingContext(
                context,
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        assertThatThrownBy(
                                        () ->
                                                client.call(
                                                        PoreloDaemon.INTERFACE + ".Start",
                                                        Map.of(
                                                                "project",
                                                                "default",
                                                                "repository",
                                                                "app",
                                                                "task",
                                                                "t")))
                                .isInstanceOfSatisfying(
                                        VarlinkException.class,
                                        refused -> {
                                            assertThat(refused.getErrorName())
                                                    .isEqualTo(
                                                            PoreloDaemon.INTERFACE
                                                                    + ".EarlierWorkWaits");
                                            assertThat(refused.getParameters())
                                                    .containsEntry("task", "t")
                                                    .containsEntry("commit", waiting)
                                                    .containsEntry(
                                                            "subject", "the earlier task's work");
                                        });
                        // As an interface starts a task: streamed, with "more".
                        assertThatThrownBy(
                                        () ->
                                                client.callMore(
                                                        PoreloDaemon.INTERFACE + ".Start",
                                                        Map.of(
                                                                "project",
                                                                "default",
                                                                "repository",
                                                                "app",
                                                                "task",
                                                                "t"),
                                                        reply -> true))
                                .isInstanceOfSatisfying(
                                        VarlinkException.class,
                                        refused ->
                                                assertThat(refused.getErrorName())
                                                        .isEqualTo(
                                                                PoreloDaemon.INTERFACE
                                                                        + ".EarlierWorkWaits"));
                    }
                });
        assertThat(runner.lines())
                .as("nothing was created")
                .noneMatch(
                        line ->
                                line.startsWith("podman create")
                                        || line.startsWith("podman run")
                                        || line.startsWith("podman build"));
    }

    @Test
    void aStartGivenAnUpstreamAsksAboutThatUpstreamsHost(@TempDir Path dir) throws Exception {

        // The gate fetches from the upstream a start is given, not from the project file's: the
        // host a person is asked to trust is that one, as at the command line, and the project
        // file's own host is not the start's question.
        record(
                dir,
                Files.writeString(
                        dir.resolve("project.yml"),
                        """
                project:
                  name: "uc"
                  security_class: "guarded"
                  upstream: "git@project-host.example.org:uc/uc.git"
                image:
                  from: "ubuntu:24.04"
                """));

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        assertThatThrownBy(
                                        () ->
                                                client.call(
                                                        PoreloDaemon.INTERFACE + ".Start",
                                                        Map.of(
                                                                "project",
                                                                PROJECT,
                                                                "task",
                                                                "t",
                                                                "upstream",
                                                                "git@given-host.example.org:uc/uc.git")))
                                .isInstanceOfSatisfying(
                                        VarlinkException.class,
                                        refused -> {
                                            assertThat(refused.getErrorName())
                                                    .isEqualTo(
                                                            PoreloDaemon.INTERFACE
                                                                    + ".UnknownHostKey");
                                            assertThat(refused.getParameters())
                                                    .as("the host the start would fetch from")
                                                    .containsEntry(
                                                            "host", "given-host.example.org");
                                        });
                    }
                });
        assertThat(runner.lines())
                .as("nothing was created")
                .noneMatch(
                        line ->
                                line.startsWith("podman create")
                                        || line.startsWith("podman run")
                                        || line.startsWith("podman build"));
    }

    @Test
    void aStartRefusedBeforeAnythingExistsAnswersItsOwnRefusal(@TempDir Path dir) throws Exception {

        // Refused for its agent before anything was made, the start cleaned up by asking podman
        // about a container that never existed; with the records checked that threw "is no task
        // Porelo made", which replaced the refusal: over the socket it arrived as Failed, on the
        // command line as exit 70. Seen on the Ubuntu VM, 2026-10-09.
        final PoreloContext hooked = hooked(dir);
        final PoreloContext context =
                new PoreloContext(
                        routed(hooked.paths()), hooked.paths(), arguments -> 0, name -> null, true);
        final Path checkout = Files.createDirectories(dir.resolve("app"));
        final org.fuin.porelo.core.process.ProcessCommandRunner git =
                new org.fuin.porelo.core.process.ProcessCommandRunner();
        git.runOrFail(
                org.fuin.porelo.core.process.Command.of(
                        "git", "-C", checkout.toString(), "init", "-q", "-b", "main"));
        git.runOrFail(
                org.fuin.porelo.core.process.Command.of(
                        "git",
                        "-C",
                        checkout.toString(),
                        "-c",
                        "user.email=t@example.com",
                        "-c",
                        "user.name=T",
                        "commit",
                        "-q",
                        "--allow-empty",
                        "-m",
                        "start"));
        new org.fuin.porelo.app.DefaultProject(context)
                .add(checkout.toString(), "app", checkout.toString(), "");
        runner.answering("podman version", "5.8.1");

        servingContext(
                context,
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".Start",
                                        Map.of(
                                                "project",
                                                "default",
                                                "repository",
                                                "app",
                                                "task",
                                                "fresh",
                                                "prompt",
                                                "hello"));

                        assertThat(((Number) reply.get("exitCode")).intValue())
                                .as("its own refusal's code")
                                .isNotZero();
                        assertThat(String.join("\n", (List<String>) reply.get("output")))
                                .doesNotContain("is no task Porelo made");
                    }
                });
    }

    @Test
    void aGateViewOfARepositoryWithNoMirrorAnswersNothingWaitingAndClonesNothing(@TempDir Path dir)
            throws Exception {

        // A view only reads: asked for every repository of a project, it cloned each one this
        // machine had no mirror of from its upstream, which on a host is out of reach by design.
        record(
                dir,
                Files.writeString(
                        dir.resolve("project.yml"),
                        """
                project:
                  name: "uc"
                  security_class: "guarded"
                  upstream: "%s"
                image:
                  from: "ubuntu:24.04"
                """
                                .formatted(dir.resolve("out-of-reach.git"))));

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".Pending",
                                        Map.of("project", PROJECT));

                        assertThat((List<?>) reply.get("pending")).isEmpty();
                        assertThat(Path.of(String.valueOf(reply.get("mirror"))))
                                .as("no mirror is made by looking")
                                .doesNotExist();
                        assertThatThrownBy(
                                        () ->
                                                client.call(
                                                        PoreloDaemon.INTERFACE + ".Review",
                                                        Map.of("project", PROJECT, "name", "x")))
                                .isInstanceOf(VarlinkException.class)
                                .hasMessageContaining("no mirror");
                    }
                });
    }

    @Test
    void aGateCallWithoutAProjectIsRefused(@TempDir Path dir) throws Exception {

        // A gate belongs to a project, and answering about the wrong one is worse than refusing.
        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        assertThatThrownBy(
                                        () ->
                                                client.call(
                                                        PoreloDaemon.INTERFACE + ".Pending",
                                                        Map.of()))
                                .isInstanceOf(VarlinkException.class);
                    }
                });
    }

    @Test
    void approvingWithoutABranchIsRefused(@TempDir Path dir) throws Exception {

        // The single call that sends anything anywhere makes the caller name where.
        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        assertThatThrownBy(
                                        () ->
                                                client.call(
                                                        PoreloDaemon.INTERFACE + ".Approve",
                                                        Map.of(
                                                                "project",
                                                                "project.yml",
                                                                "name",
                                                                "shell")))
                                .isInstanceOf(VarlinkException.class);
                    }
                });
    }

    @Test
    void theSocketIsOwnerOnly(@TempDir Path dir) throws Exception {

        // The whole access-control story: no listener on any interface, and the filesystem refusing
        // every other account on this machine. A check written in Java could be forgotten; a mode
        // the kernel enforces cannot.
        serving(
                dir,
                socket -> {
                    final Set<PosixFilePermission> mode = Files.getPosixFilePermissions(socket);
                    assertThat(mode)
                            .containsExactlyInAnyOrder(
                                    PosixFilePermission.OWNER_READ,
                                    PosixFilePermission.OWNER_WRITE,
                                    PosixFilePermission.OWNER_EXECUTE);
                    assertThat(mode)
                            .noneMatch(
                                    permission ->
                                            permission.name().startsWith("GROUP")
                                                    || permission.name().startsWith("OTHERS"));
                });
    }

    @Test
    void answersWhatItIsWhenAsked(@TempDir Path dir) throws Exception {

        // Introspection is part of the protocol, which is what lets varlinkctl - or anything else
        // that speaks it - check this service without Porelo writing the checker.
        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> info =
                                client.call("org.varlink.service.GetInfo", Map.of());
                        assertThat(info).containsEntry("product", "Porelo");
                        @SuppressWarnings("unchecked")
                        final List<String> interfaces = (List<String>) info.get("interfaces");
                        assertThat(interfaces).contains(PoreloDaemon.INTERFACE);
                    }
                });
    }

    @Test
    void aTaskThatRecordedNothingIsStillListed(@TempDir Path dir) throws Exception {

        // A sidecar that cannot be read costs detail, not the row: an operator listing tasks to
        // find one to stop needs the name most of all.
        runner.answering("ps", "porelo-uc-shell-1\tUp 4 minutes\n");

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(PoreloDaemon.INTERFACE + ".List", Map.of());
                        @SuppressWarnings("unchecked")
                        final List<Map<String, Object>> tasks =
                                (List<Map<String, Object>>) reply.get("tasks");
                        assertThat(tasks)
                                .singleElement()
                                .satisfies(
                                        task ->
                                                assertThat(task)
                                                        .containsEntry("project", "")
                                                        .containsEntry("securityClass", ""));
                    }
                });
    }

    @Test
    void aSecondDaemonTakesOverTheSocketRatherThanFailing(@TempDir Path dir) throws IOException {

        // Restarting is the common case - an upgrade, a crash - and a stale socket file from the
        // previous one must not be what stops it coming back. A server that closes cleanly removes
        // its file, so a stale one is made the way a crash leaves it: bound, and closed without
        // anybody removing the path.
        final Path socket = dir.resolve("porelod.sock");
        try (java.nio.channels.ServerSocketChannel crashed =
                java.nio.channels.ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX)) {
            crashed.bind(java.net.UnixDomainSocketAddress.of(socket));
        }
        assertThat(socket).as("the stale file a crash leaves").exists();
        try (VarlinkServer second = PoreloDaemon.serving(context(dir), socket)) {
            assertThat(socket).exists();
        }
    }

    @Test
    void aLogIsReadFromItsLastLinesWhenAClientAsksForThem(@TempDir Path dir) throws Exception {

        // The whole of a 53 MB log crossed the socket before the interface could show any of it,
        // and it closed.
        final Path state = dir.resolve("run/porelo/porelo-uc-shell-1");
        Files.createDirectories(state);
        Files.writeString(state.resolve("task.log"), "one\ntwo\nthree\nfour\n");

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> last =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".Tail",
                                        Map.of(
                                                "task",
                                                "porelo-uc-shell-1",
                                                "log",
                                                "task.log",
                                                "last",
                                                2));
                        assertThat(last.get("lines")).isEqualTo(java.util.List.of("three", "four"));
                        assertThat(((Number) last.get("offset")).longValue())
                                .isEqualTo("one\ntwo\n".length());

                        final Map<String, Object> whole =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".Tail",
                                        Map.of("task", "porelo-uc-shell-1", "log", "task.log"));
                        assertThat(whole.get("lines"))
                                .isEqualTo(java.util.List.of("one", "two", "three", "four"));
                        assertThat(((Number) whole.get("offset")).longValue()).isZero();
                    }
                });
    }

    @Test
    void aLogAskedForFormattedComesAsItIsFromATaskWhoseAgentIsNotHere(@TempDir Path dir)
            throws Exception {

        // Formatted by the task's agent where it can be; with no agent to ask, the lines are not
        // lost but shown raw.
        final Path state = dir.resolve("run/porelo/porelo-uc-shell-1");
        Files.createDirectories(state);
        Files.writeString(state.resolve("task.log"), "one\ntwo\n");

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> formatted =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".Tail",
                                        Map.of(
                                                "task",
                                                "porelo-uc-shell-1",
                                                "log",
                                                "task.log",
                                                "last",
                                                1,
                                                "formatted",
                                                true));
                        assertThat(formatted.get("lines")).isEqualTo(java.util.List.of("two"));
                    }
                });
    }

    @Test
    void aProjectTheOrganizationPlacedAndTheBuiltInOneAreNotRemovedHere(@TempDir Path dir)
            throws Exception {
        final PoreloContext context = context(dir);
        org.fuin.porelo.app.OrganizationProjects.of(context)
                .write(
                        new org.fuin.porelo.app.OrganizationProjects.Placed(
                                "placed",
                                "c0",
                                "https://forge.example/placed.git",
                                null,
                                List.of(),
                                null,
                                null,
                                null));

        servingContext(
                context,
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        for (final String name : List.of("placed", "default")) {
                            assertThatThrownBy(
                                            () ->
                                                    client.call(
                                                            PoreloDaemon.INTERFACE
                                                                    + ".RemoveProject",
                                                            Map.of("name", name)))
                                    .as(name)
                                    .isInstanceOf(VarlinkException.class)
                                    .hasMessageContaining("NotAllowed");
                        }
                    }
                });
    }

    @Test
    void aListedProjectWhoseFileIsGoneIsPreviewedOverTheSocket(@TempDir Path dir) throws Exception {
        // A window offers removal for what Projects lists, and a blind force is not an answer.
        Files.createDirectories(dir.resolve("data/porelo/mirrors/left.git/objects"));
        Files.createDirectories(dir.resolve("data/porelo/projects"));
        Files.writeString(dir.resolve("data/porelo/projects/left"), dir.resolve("gone.yml") + "\n");
        runner.answering("for-each-ref", "");

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> listed =
                                client.call(PoreloDaemon.INTERFACE + ".Projects", Map.of());
                        @SuppressWarnings("unchecked")
                        final List<Map<String, Object>> projects =
                                (List<Map<String, Object>>) listed.get("projects");
                        assertThat(projects)
                                .as("the machine lists it by its name")
                                .extracting(project -> project.get("name"))
                                .contains("left");

                        final Map<String, Object> reply =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".RemoveProject",
                                        Map.of("name", "left", "dryRun", true));

                        assertThat(reply)
                                .as("previewed with what it takes, not NoSuchProject")
                                .containsEntry("outcome", "PREVIEWED")
                                .containsKeys("removes", "keeps", "unreviewed");
                        @SuppressWarnings("unchecked")
                        final List<Map<String, Object>> removes =
                                (List<Map<String, Object>>) reply.get("removes");
                        assertThat(removes)
                                .as("what it would take is named: its mirror and its entry")
                                .contains(
                                        Map.<String, Object>of(
                                                "kind",
                                                "MIRROR",
                                                "what",
                                                dir.resolve("data/porelo/mirrors/left.git")
                                                        .toString()),
                                        Map.<String, Object>of(
                                                "kind",
                                                "REGISTRY",
                                                "what",
                                                dir.resolve("data/porelo/projects/left")
                                                        .toString()));
                        assertThat(Files.isDirectory(dir.resolve("data/porelo/mirrors/left.git")))
                                .as("a preview removes nothing")
                                .isTrue();
                    }
                });
    }

    /** A client has no path to send: by name it was refused as no such project. */
    @Test
    void prepareResolvesAProjectByName(@TempDir Path dir) throws Exception {
        projectFile(dir);

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".Prepare",
                                        Map.of("project", PROJECT, "dryRun", true));

                        assertThat(reply)
                                .as("the name resolved to the project's file")
                                .containsEntry("outcome", "PREVIEWED");
                    }
                });
    }

    /** A project not placed here yet is the organization's to judge, so "no such project" lies. */
    @Test
    void prepareBuildsNothingWhenTheOrganizationRefusesAsAStartWould(@TempDir Path dir)
            throws Exception {
        organizationOutOfReach(dir);

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".Prepare",
                                        Map.of("project", "not-here-yet"));

                        assertThat(reply).containsEntry("outcome", "FAILED");
                        assertThat((String) reply.get("detail"))
                                .as("the contract's reason, for the client to show")
                                .startsWith("cannot-say: ");
                    }
                });
        assertThat(runner.lines())
                .as("no image was built")
                .noneMatch(line -> line.contains("podman") && line.contains("build"));
    }

    /** A path is what Projects reports, so a client that sends it gets what a name gets. */
    @Test
    void prepareOfAPathOfThisMachineAnswersForTheProjectItNames(@TempDir Path dir)
            throws Exception {
        final Path file = projectFile(dir);

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".Prepare",
                                        Map.of(
                                                "project",
                                                file.toAbsolutePath().toString(),
                                                "dryRun",
                                                true));

                        assertThat(reply).containsEntry("outcome", "PREVIEWED");
                    }
                });
    }

    /** Built from the last start's clone, a revoked signer's file would go on being trusted. */
    @Test
    void prepareOfAPlacedProjectsPathReadsTheOrganizationAsItsNameDoes(@TempDir Path dir)
            throws Exception {
        final PoreloContext machine = context(dir);
        final Path clone = machine.paths().projects().organizationClone("demo");
        Files.createDirectories(clone);
        final Path file =
                Files.writeString(
                        clone.resolve("project.yml"),
                        """
                        project:
                          name: "demo"
                          security_class: "guarded"
                        image:
                          from: "ubuntu:24.04"
                        """);
        org.fuin.porelo.app.OrganizationProjects.of(machine)
                .write(
                        new org.fuin.porelo.app.OrganizationProjects.Placed(
                                "demo",
                                "0".repeat(40),
                                "https://forge.example.org/acme/demo.git",
                                null,
                                List.of(),
                                null,
                                null,
                                null));
        organizationOutOfReach(dir);

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".Prepare",
                                        Map.of("project", file.toString()));

                        assertThat(reply).containsEntry("outcome", "FAILED");
                        assertThat((String) reply.get("detail")).startsWith("cannot-say: ");
                    }
                });
        assertThat(runner.lines())
                .as("no image was built")
                .noneMatch(line -> line.contains("podman") && line.contains("build"));
    }

    /**
     * The daemon takes back only the paths it hands out: this file's snippet would run in a build.
     */
    @Test
    void prepareRefusesAPathThisMachineNeverReported(@TempDir Path dir) throws Exception {
        final Path stranger =
                Files.writeString(
                        dir.resolve("elsewhere.yml"),
                        """
                        project:
                          name: "uc"
                          security_class: "guarded"
                        image:
                          from: "ubuntu:24.04"
                        """);

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".Prepare",
                                        Map.of("project", stranger.toString()));

                        assertThat(reply).containsEntry("outcome", "NO_SUCH_PROJECT");
                    }
                });
        assertThat(runner.lines())
                .as("no image was built")
                .noneMatch(line -> line.contains("podman") && line.contains("build"));
    }

    /** It would build under the name the file declares, overwriting that project's image. */
    @Test
    void prepareRefusesAFileThatStandsInThePlaceOfAnotherName(@TempDir Path dir) throws Exception {
        final Path decoy = context(dir).paths().projects().defaultProjectFile("decoy");
        Files.createDirectories(decoy.getParent());
        Files.writeString(
                decoy,
                """
                project:
                  name: "target"
                  security_class: "guarded"
                image:
                  from: "ubuntu:24.04"
                """);

        serving(
                dir,
                socket -> {
                    try (VarlinkClient client = new VarlinkClient(socket)) {
                        final Map<String, Object> reply =
                                client.call(
                                        PoreloDaemon.INTERFACE + ".Prepare",
                                        Map.of("project", "decoy"));

                        assertThat(reply).containsEntry("outcome", "NO_SUCH_PROJECT");
                        assertThat((String) reply.get("detail")).contains("stands in the place of");
                    }
                });
    }

    private void organizationOutOfReach(final Path dir) throws IOException {
        Files.createDirectories(dir.resolve("config/porelo"));
        Files.writeString(
                dir.resolve("config/porelo/organization.yml"),
                "adapter: git\nsettings:\n  repository: https://forge.example.org/acme/organization.git\n");
        runner.failing("git", 128, "fatal: unable to access the forge");
    }
}
