package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.TaskMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Guards diagnostics and progress without depending on a container runtime. */
class StartProgressReviewTest {
    @TempDir Path dir;

    private PoreloContext context(CommandRunner runner) {
        return new PoreloContext(
                runner,
                new PoreloPaths(
                        XdgPaths.of(
                                name ->
                                        switch (name) {
                                            case "XDG_CONFIG_HOME" ->
                                                    dir.resolve("config").toString();
                                            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                            case "XDG_STATE_HOME" ->
                                                    dir.resolve("state").toString();
                                            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                            default -> null;
                                        },
                                dir),
                        dir.resolve("bin")),
                arguments -> 0);
    }

    private Path projectFile(boolean conversation) throws Exception {
        final Path file = dir.resolve("project.yml");
        Files.writeString(
                file,
                """
                project: {name: p, security_class: guarded}
                image: {base_image: 'ubuntu:24.04'}
                """
                        + (conversation
                                ? """
                mail:
                  transports: {room: {}}
                  peers: {reviewer: {address: 'room:', trust: vouched}}
                """
                                : ""));
        return file;
    }

    private TaskLaunch.Request request(Path project) {
        return new TaskLaunch.Request(
                "work",
                project,
                null,
                null,
                null,
                8,
                null,
                true,
                false,
                "deny",
                true,
                TaskMode.SHELL,
                null,
                null,
                null,
                1,
                "p");
    }

    private void installHooks(PoreloContext context) throws Exception {
        Files.createDirectories(context.paths().binaryDirectory());
        for (String name :
                List.of("porelo-hook-nft", "porelo-hook-supervisor", "porelo-hook-reader")) {
            final Path binary = context.paths().binaryDirectory().resolve(name);
            Files.writeString(binary, "#!/bin/sh\n");
            binary.toFile().setExecutable(true);
        }
        new org.fuin.porelo.runtime.HookInstaller(
                        context.paths().hooksDirectory(), context.paths().binaryDirectory())
                .install();
    }

    @ParameterizedTest
    @CsvSource({"setup,false", "enroll,false", "setup,true", "enroll,true"})
    void aTransportCannotExposeItsSuppliedSecretsInAnExitOrDeadlineCause(
            String verb, boolean timeout) throws Exception {
        // A transport's diagnostic must not leak the credential it was trusted to use.
        final ProcessCommandRunner process = new ProcessCommandRunner(Duration.ofMillis(100));
        final PoreloContext context =
                context(
                                command -> {
                                    if (command.arguments().contains(verb) && !timeout) {
                                        return new ProcessCommandRunner().run(command);
                                    }
                                    return process.run(command);
                                })
                        .asking(shown -> "test passphrase".toCharArray());
        final Map<String, String> secrets =
                Map.of(
                        "SHORT",
                        "abcdefgh",
                        "LONG",
                        "abcdefghijklmnop",
                        "OTHER",
                        "another-test-secret",
                        "PUBLIC",
                        "public7");
        Files.createDirectories(context.vault().path().getParent());
        context.vault()
                .write(
                        Map.of(
                                "transport/room/account",
                                new VaultEntry(Json.write(secrets), "transport-secrets")),
                        "test passphrase".toCharArray());
        context.openerAsking(false, new PrintWriter(new StringWriter()));
        final Path adapter =
                context.paths()
                        .xdg()
                        .data()
                        .resolve("transports")
                        .resolve(TransportDirectory.PREFIX + "room");
        Files.createDirectories(adapter.getParent());
        Files.writeString(
                adapter,
                """
                #!/bin/sh
                case "$1" in
                  describe) echo '{"scheme":"room","lifecycle":["setup","enroll"]}'; exit 0;;
                esac
                if [ "$1" = '%s' ]; then
                  printf 'https://example.test/?a=%%s&b=%%s&c=%%s&public=%%s\n' "$SHORT" "$LONG" "$OTHER" "$PUBLIC" >&2
                  %s
                fi
                echo '{"conversation":"room", "reaches":[]}'
                """
                        .formatted(verb, timeout ? "exec sleep 30" : "exit 75"));
        adapter.toFile().setExecutable(true);
        final List<StartStep> events = new ArrayList<>();
        final Throwable failure;
        try {
            failure =
                    catchThrowable(
                            () ->
                                    new TaskConversations(context)
                                            .enroll(
                                                    ProjectReader.read(projectFile(true)),
                                                    "porelo-p-work",
                                                    new StartSteps(events::add)));
        } finally {
            PoreloContext.forgetAsked();
        }
        assertThat(events)
                .as("the transport must finish its conversation step even on a deadline")
                .isNotEmpty();
        final String cause = events.getLast().cause();
        assertThat(cause)
                .as("transport causes must redact every supplied secret before cleaning or cutting")
                .isEqualTo(
                        "https://example.test/?a=[withheld]&b=[withheld]&c=[withheld]&public=public7");
        assertThat(cause)
                .as("no supplied credential may remain in a step cause")
                .doesNotContain(secrets.get("SHORT"), secrets.get("LONG"), secrets.get("OTHER"));
        if (timeout) {
            assertThat(failure)
                    .as("redaction must preserve the runner's deadline refusal")
                    .isInstanceOf(CommandException.class);
            assertThat(((CommandException) failure).standardError())
                    .as("the conversation layer must never receive unredacted timeout stderr")
                    .doesNotContain(
                            secrets.get("SHORT"), secrets.get("LONG"), secrets.get("OTHER"));
        } else {
            assertThat(failure)
                    .as("a nonzero transport exit must keep the existing refusal result")
                    .isNull();
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("invisibleCharacters")
    void invisibleDiagnosticCharactersCannotAlterTheVisibleCauseOrWait(int codePoint) {
        // Invisible formatting must not disguise the dependency or reverse a refusal's meaning.
        final String input = "before" + new String(Character.toChars(codePoint)) + "after";
        assertThat(StartStep.cleanCause(input))
                .as("a cause must discard format and default-ignorable characters")
                .isEqualTo("beforeafter");
        assertThat(StartStep.cleanWaitingOn(input))
                .as("a wait label must discard format and default-ignorable characters")
                .isEqualTo("beforeafter");
    }

    private static java.util.stream.IntStream invisibleCharacters() {
        return java.util.stream.IntStream.of(
                0x202a, 0x202b, 0x202c, 0x202d, 0x202e, 0x2066, 0x2067, 0x2068, 0x2069, 0x200b,
                0x200c, 0x200d, 0x200e, 0x200f, 0xfeff, 0x034f, 0x115f, 0x1160, 0x17b4, 0x17b5,
                0x180b, 0x180c, 0x180d, 0x180e, 0x180f, 0x2065, 0x3164, 0xfe00, 0xfe0f, 0xffa0,
                0xfff0, 0xfff8, 0xe0000, 0xe007f, 0xe01ef, 0xe0fff);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"\033[0m", "\u200b", "\u034f", "\t\u0000"})
    void cleanedEmptyTrailingLinesCannotHideTheLastUsefulDiagnostic(String emptyLine) {
        // A final reset or invisible line must not erase the dependency's useful error.
        assertThat(StartStep.cleanCause("connection refused\n" + emptyLine + "\n"))
                .as("cleaned-empty trailing lines must not hide the last useful error")
                .isEqualTo("connection refused");
        assertThat(StartStep.cleanWaitingOn("the server\n" + emptyLine + "\n"))
                .as("cleaned-empty trailing lines must not hide the wait label")
                .isEqualTo("the server");
    }

    @Test
    @org.junit.jupiter.api.parallel.ResourceLock(
            org.junit.jupiter.api.parallel.Resources.SYSTEM_ERR)
    void aBrokenProgressFileCannotChangeTheLaunchOrSuppressItsSteps() throws Exception {
        // An optional progress record must not prevent the work or replace its real refusal.
        final FakeCommandRunner machine =
                new FakeCommandRunner()
                        .answering("podman version", "5.8.1")
                        .failing(" build ", 125, "registry refused");
        final PoreloContext context = context(machine);
        installHooks(context);
        final Path blocked =
                Files.createDirectories(
                        context.paths()
                                .tasks()
                                .containerState("porelo-p-work")
                                .resolve("start-step.json"));
        Files.writeString(blocked.resolve("keep"), "occupied");
        final java.io.ByteArrayOutputStream warnings = new java.io.ByteArrayOutputStream();
        final java.io.PrintStream previous = System.err;
        final List<StartStep> events = new ArrayList<>();
        final java.util.concurrent.atomic.AtomicInteger result =
                new java.util.concurrent.atomic.AtomicInteger(-1);
        final Throwable failure;
        System.setErr(
                new java.io.PrintStream(warnings, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            final TaskLaunch launch =
                    new TaskLaunch(
                            context, request(projectFile(false)), new StartSteps(events::add));
            failure =
                    catchThrowable(
                            () ->
                                    result.set(
                                            launch.launch(
                                                    new PrintWriter(new StringWriter()),
                                                    new PrintWriter(new StringWriter()),
                                                    running -> 0)));
        } finally {
            System.setErr(previous);
        }
        assertThat(failure)
                .as("progress persistence failures must never change a launch's result")
                .isNull();
        assertThat(result.get())
                .as("the actual dependency must still determine the start result")
                .isEqualTo(70);
        assertThat(events.getLast())
                .as("progress must still deliver the real image failure")
                .extracting(StartStep::step, StartStep::state, StartStep::cause)
                .containsExactly(StartStep.Step.IMAGE, StartStep.State.FAILED, "registry refused");
        assertThat(warnings.toString(java.nio.charset.StandardCharsets.UTF_8).lines().toList())
                .as("repeated write and clear failures must produce only one progress warning")
                .singleElement()
                .asString()
                .startsWith("porelo: warning: cannot record the start's step: ");
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"clear", "read"})
    @org.junit.jupiter.api.parallel.ResourceLock(
            org.junit.jupiter.api.parallel.Resources.SYSTEM_ERR)
    void clearingOrReadingBrokenProgressIsBestEffortAndWarnsOnce(String operation)
            throws Exception {
        // Progress cleanup and inventory reads must not turn a healthy operation into a failure.
        final FakeCommandRunner machine =
                new FakeCommandRunner()
                        .answering(
                                "ps",
                                "porelo-p-work\tUp 1 minute\t1788500000\t0\tp\tguarded\tp\t\n");
        final PoreloContext context = context(machine);
        final Path state = context.paths().tasks().containerState("porelo-p-work");
        Files.createDirectories(state.resolve("start-step.json"));
        Files.writeString(state.resolve("start-step.json/keep"), "occupied");
        final java.io.ByteArrayOutputStream warnings = new java.io.ByteArrayOutputStream();
        final java.io.PrintStream previous = System.err;
        final Throwable failure;
        System.setErr(
                new java.io.PrintStream(warnings, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            final StartSteps steps = new StartSteps(event -> {});
            steps.recordIn(state);
            final TaskInventory inventory = new TaskInventory(context);
            failure =
                    catchThrowable(
                            () -> {
                                for (int attempt = 0; attempt < 3; attempt++) {
                                    if (operation.equals("clear")) {
                                        steps.clear();
                                    } else {
                                        assertThat(inventory.tasks().getFirst().phase())
                                                .as(
                                                        "an unreadable progress record cannot claim a phase")
                                                .isEmpty();
                                    }
                                }
                            });
        } finally {
            System.setErr(previous);
        }
        assertThat(failure).as("reading or clearing progress must never fail the caller").isNull();
        assertThat(warnings.toString(java.nio.charset.StandardCharsets.UTF_8).lines().toList())
                .as("repeated progress read or clear failures must warn only once")
                .singleElement()
                .asString()
                .startsWith("porelo: warning: cannot record the start's step: ");
    }

    private Map<String, Object> progressRecord(String state) {
        final Map<String, Object> record =
                new java.util.LinkedHashMap<>(
                        Map.of(
                                "step",
                                "IMAGE",
                                "state",
                                state,
                                "text",
                                "Preparing the image",
                                "waitingOn",
                                "podman build",
                                "cause",
                                ""));
        record.put("pid", ProcessHandle.current().pid());
        record.put(
                "startedAt",
                ProcessHandle.current().info().startInstant().orElseThrow().toString());
        return record;
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"dead", "reused", "DONE", "FAILED", "live"})
    void staleOrCompletedProgressCannotClaimThatAStartIsStillActive(String owner) throws Exception {
        // A crashed launcher or a reused pid must not leave a task looking stuck forever.
        final FakeCommandRunner machine =
                new FakeCommandRunner()
                        .answering(
                                "ps",
                                "porelo-p-work\tUp 1 minute\t1788500000\t0\tp\tguarded\tp\t\n");
        final PoreloContext context = context(machine);
        final Path state =
                Files.createDirectories(context.paths().tasks().containerState("porelo-p-work"));
        final Map<String, Object> record =
                progressRecord(owner.equals("DONE") || owner.equals("FAILED") ? owner : "STARTED");
        if (owner.equals("dead")) {
            record.put("pid", Integer.MAX_VALUE);
        }
        if (owner.equals("reused")) {
            record.put("startedAt", java.time.Instant.EPOCH.toString());
        }
        Files.writeString(state.resolve("start-step.json"), Json.write(record));
        assertThat(new TaskInventory(context).tasks().getFirst().phase())
                .as("only an unfinished step owned by the same live process may claim a phase")
                .isEqualTo(owner.equals("live") ? "Preparing the image" : "");
    }

    @Test
    void aPublishedStepCarriesItsLaunchersProcessIdentity() throws Exception {
        // A pid alone can be reused after a launcher exits, so the record must bind both values.
        final Path state = Files.createDirectories(dir.resolve("progress"));
        final StartSteps steps = new StartSteps(event -> {});
        steps.recordIn(state);
        steps.started(StartStep.Step.IMAGE);
        final Object written = Json.parse(Files.readString(state.resolve("start-step.json")));
        assertThat(written)
                .as("a phase record must identify the launcher and its exact process lifetime")
                .isInstanceOfSatisfying(
                        Map.class,
                        record -> {
                            assertThat(((Number) record.get("pid")))
                                    .as("the record must name its actual launcher")
                                    .isNotNull()
                                    .extracting(Number::longValue)
                                    .isEqualTo(ProcessHandle.current().pid());
                            assertThat(record.get("startedAt"))
                                    .as("the record must distinguish later reuse of the same pid")
                                    .isEqualTo(
                                            ProcessHandle.current()
                                                    .info()
                                                    .startInstant()
                                                    .orElseThrow()
                                                    .toString());
                        });
    }

    @Test
    void anActiveWaitingPhaseNamesTheExternalDependency() throws Exception {
        // A second window must say what holds up the start instead of showing only its stage.
        final FakeCommandRunner machine =
                new FakeCommandRunner()
                        .answering(
                                "ps",
                                "porelo-p-work\tUp 1 minute\t1788500000\t0\tp\tguarded\tp\t\n");
        final PoreloContext context = context(machine);
        final Path state =
                Files.createDirectories(context.paths().tasks().containerState("porelo-p-work"));
        Files.writeString(state.resolve("start-step.json"), Json.write(progressRecord("WAITING")));
        assertThat(new TaskInventory(context).tasks().getFirst().phase())
                .as("a waiting phase must name the dependency that holds the start")
                .isEqualTo("Preparing the image - waiting on podman build");
    }

    private FakeCommandRunner stoppedMachine() {
        return new FakeCommandRunner()
                .answering("podman version", "5.8.1")
                .answering("{{.Id}}", "0123456789abcdef")
                .answering(
                        "ps",
                        "porelo-p-work\tExited (0) 1 minute ago\t1788500000\t1788500060\tp\tguarded\tp\t\n");
    }

    @Test
    void aResumedTaskReportsItsRefusedConversationAndKeepsTheCause() throws Exception {
        // A resumed task must not silently lose its conversation while only new tasks report it.
        final FakeCommandRunner machine =
                stoppedMachine()
                        .answering(
                                "describe",
                                "{\"scheme\":\"room\",\"lifecycle\":[\"setup\",\"enroll\"],\"waitsOn\":\"the room server\"}")
                        .failing(" setup ", 75, "first line\nresume refused");
        final PoreloContext context =
                context(machine).asking(shown -> "test passphrase".toCharArray());
        installHooks(context);
        Files.createDirectories(context.paths().tasks().containerState("porelo-p-work"));
        Files.createDirectories(context.vault().path().getParent());
        context.vault().write(Map.of(), "test passphrase".toCharArray());
        context.openerAsking(false, new PrintWriter(new StringWriter()));
        final Path adapter =
                context.paths()
                        .xdg()
                        .data()
                        .resolve("transports")
                        .resolve(TransportDirectory.PREFIX + "room");
        Files.createDirectories(adapter.getParent());
        Files.writeString(adapter, "#!/bin/sh\n");
        adapter.toFile().setExecutable(true);
        final List<StartStep> events = new ArrayList<>();
        try {
            final TaskLaunch.Existing result =
                    new TaskLaunch(context, request(projectFile(true)), new StartSteps(events::add))
                            .startExisting(
                                    new PrintWriter(new StringWriter()),
                                    new PrintWriter(new StringWriter()));
            assertThat(result)
                    .as("a conversation warning must preserve the existing resume result")
                    .isNotNull();
            assertThat(result.code())
                    .as("conversation refusal must not prevent the established resume behavior")
                    .isZero();
        } finally {
            PoreloContext.forgetAsked();
        }
        assertThat(events)
                .as("resume must report its conversation wait and refusal cause")
                .filteredOn(event -> event.step() == StartStep.Step.CONVERSATION)
                .extracting(
                        event -> List.of(event.state().name(), event.waitingOn(), event.cause()))
                .containsExactly(
                        List.of("STARTED", "", ""),
                        List.of("WAITING", "the room server", ""),
                        List.of("FAILED", "", "resume refused"));
    }

    @Test
    void resumingTheContainerReportsItsHelperStageWithoutChangingWarnings() throws Exception {
        // A resume must expose helper startup even when it can only warn about missing records.
        final PoreloContext context = context(stoppedMachine());
        installHooks(context);
        Files.createDirectories(context.paths().tasks().containerState("porelo-p-work"));
        final List<StartStep> events = new ArrayList<>();
        final TaskLaunch.Existing result =
                new TaskLaunch(context, request(projectFile(false)), new StartSteps(events::add))
                        .startExisting(
                                new PrintWriter(new StringWriter()),
                                new PrintWriter(new StringWriter()));
        assertThat(result).as("a helper warning must preserve the resume answer").isNotNull();
        assertThat(result.code())
                .as("missing helper records keep their existing successful resume result")
                .isZero();
        assertThat(events)
                .as("resume must bracket helper startup without turning a warning into failure")
                .filteredOn(event -> event.step() == StartStep.Step.HELPERS)
                .extracting(StartStep::state)
                .containsExactly(StartStep.State.STARTED, StartStep.State.DONE);
    }

    @Test
    void aStartWithoutAGateDoesNotClaimToPrepareAWorkspace() throws Exception {
        // Reporting work that never happens sends a stalled-start investigation to the wrong stage.
        final FakeCommandRunner machine =
                new FakeCommandRunner()
                        .answering("podman version", "5.8.1")
                        .failing(" build ", 125, "registry refused");
        final PoreloContext context = context(machine);
        installHooks(context);
        final List<StartStep> events = new ArrayList<>();
        new TaskLaunch(context, request(projectFile(false)), new StartSteps(events::add))
                .launch(
                        new PrintWriter(new StringWriter()),
                        new PrintWriter(new StringWriter()),
                        running -> 0);
        assertThat(events)
                .as("a no-gate start must not report workspace work it never performs")
                .noneMatch(event -> event.step() == StartStep.Step.WORKSPACE);
        assertThat(events)
                .as("omitting the workspace step must not omit the actual image preparation")
                .anyMatch(
                        event ->
                                event.step() == StartStep.Step.IMAGE
                                        && event.state() == StartStep.State.WAITING);
    }

    @Test
    void aContainerCreationFailureKeepsTheLastDependencyError() throws Exception {
        // A wrapped first line hides why the container was refused.
        final FakeCommandRunner machine =
                new FakeCommandRunner()
                        .answering("podman version", "5.8.1")
                        .failing(
                                " create ",
                                125,
                                "creating container\n\033[31mcontainer creation refused\033[0m\n");
        final PoreloContext context = context(machine);
        installHooks(context);
        final List<StartStep> events = new ArrayList<>();
        final int result =
                new TaskLaunch(context, request(projectFile(false)), new StartSteps(events::add))
                        .launch(
                                new PrintWriter(new StringWriter()),
                                new PrintWriter(new StringWriter()),
                                running -> 0);
        assertThat(events)
                .as("container failures must retain the dependency's last stderr line")
                .filteredOn(
                        event ->
                                event.step() == StartStep.Step.CONTAINER
                                        && event.state() == StartStep.State.FAILED)
                .extracting(StartStep::cause)
                .containsExactly("container creation refused");
        assertThat(result)
                .as("reporting a container cause must preserve the existing failure result")
                .isEqualTo(70);
    }
}
