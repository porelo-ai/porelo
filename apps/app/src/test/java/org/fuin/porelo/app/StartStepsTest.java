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
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.TaskMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Guards progress and dependency diagnostics across start's surfaces. */
class StartStepsTest {
    @TempDir Path dir;

    private PoreloContext context(org.fuin.porelo.core.process.CommandRunner runner) {
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

    @Test
    void aConversationNamesItsWaitBeforeCallingTheTransportAndKeepsItsLastError() throws Exception {
        // A start that waits minutes and fails must say what it waited on and why it failed.
        final FakeCommandRunner machine =
                new FakeCommandRunner().answering("podman version", "5.8.1");
        final ProcessCommandRunner transport = new ProcessCommandRunner();
        final PoreloContext context =
                context(
                                command ->
                                        command.arguments()
                                                        .getFirst()
                                                        .contains(TransportDirectory.PREFIX)
                                                ? transport.run(command)
                                                : machine.run(command))
                        .asking(shown -> "test passphrase".toCharArray());
        installHooks(context);
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
        final Path called = dir.resolve("called");
        Files.writeString(
                adapter,
                """
                #!/bin/sh
                case "$1" in
                  describe) echo '{"scheme":"room","lifecycle":["setup","enroll"],"waitsOn":"the room homeserver"}';;
                  setup) touch '%s'; sleep 1; printf 'first line\n\033[31mhomeserver refused\033[0m\n' >&2; exit 75;;
                esac
                """
                        .formatted(called));
        adapter.toFile().setExecutable(true);
        final Path projectFile = dir.resolve("project.yml");
        Files.writeString(
                projectFile,
                """
                project: {name: p, security_class: guarded}
                image: {base_image: 'ubuntu:24.04'}
                mail:
                  transports: {room: {}}
                  peers: {reviewer: {address: 'room:', trust: vouched}}
                """);
        final List<StartStep> events = new ArrayList<>();
        final StartSteps steps =
                new StartSteps(
                        event -> {
                            if (event.state() == StartStep.State.WAITING) {
                                assertThat(called)
                                        .as("the wait must be reported before setup can block")
                                        .doesNotExist();
                            }
                            events.add(event);
                        });
        try {
            final TaskLaunch.Request request =
                    new TaskLaunch.Request(
                            "work",
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
                            TaskMode.SHELL,
                            null,
                            null,
                            null,
                            1,
                            "p");
            final int code =
                    new TaskLaunch(context, request, steps)
                            .launch(
                                    new PrintWriter(new StringWriter()),
                                    new PrintWriter(new StringWriter()),
                                    running -> 0);
            assertThat(events)
                    .as(
                            "a blocked conversation must expose its wait and the dependency's last error")
                    .extracting(
                            event ->
                                    List.of(
                                            event.step().name(),
                                            event.state().name(),
                                            event.waitingOn(),
                                            event.cause()))
                    .containsSubsequence(
                            List.of("CONVERSATION", "WAITING", "the room homeserver", ""),
                            List.of("CONVERSATION", "FAILED", "", "homeserver refused"));
            assertThat(code)
                    .as("a conversation refusal must stop before building or creating a task")
                    .isEqualTo(69);
        } finally {
            PoreloContext.forgetAsked();
        }
    }

    private void installHooks(PoreloContext context) throws Exception {
        Files.createDirectories(context.paths().binaryDirectory());
        for (final String name :
                List.of("porelo-hook-nft", "porelo-hook-supervisor", "porelo-hook-reader")) {
            final Path binary = context.paths().binaryDirectory().resolve(name);
            Files.writeString(binary, "#!/bin/sh\n");
            binary.toFile().setExecutable(true);
        }
        new org.fuin.porelo.runtime.HookInstaller(
                        context.paths().hooksDirectory(), context.paths().binaryDirectory())
                .install();
    }

    @Test
    void aFailedImageBuildKeepsItsLastErrorAndClearsThePublishedPhase() throws Exception {
        // A failed build must leave its cause, rather than a stale phase that looks stuck forever.
        final FakeCommandRunner machine =
                new FakeCommandRunner()
                        .answering("podman version", "5.8.1")
                        .failing(
                                " build ",
                                125,
                                "fetching image\n\033[31mregistry unavailable\033[0m\n");
        final PoreloContext context = context(machine);
        installHooks(context);
        final Path projectFile = dir.resolve("project.yml");
        Files.writeString(
                projectFile,
                """
                project: {name: p, security_class: guarded}
                image: {base_image: 'ubuntu:24.04'}
                """);
        final Path phase =
                context.paths().tasks().containerState("porelo-p-work").resolve("start-step.json");
        final List<StartStep> events = new ArrayList<>();
        final StartSteps steps =
                new StartSteps(
                        event -> {
                            events.add(event);
                            if (event.state() == StartStep.State.WAITING) {
                                assertThat(phase)
                                        .as(
                                                "another window must be able to read the image wait before podman runs")
                                        .exists();
                                try {
                                    assertThat(
                                                    org.fuin.porelo.wire.Json.parse(
                                                            Files.readString(phase)))
                                            .as(
                                                    "the atomic phase record must contain the complete transition")
                                            .isInstanceOfSatisfying(
                                                    Map.class,
                                                    recorded ->
                                                            assertThat(recorded)
                                                                    .as(
                                                                            "the phase must preserve every transition field")
                                                                    .containsAllEntriesOf(
                                                                            event.asMap()));
                                } catch (java.io.IOException ex) {
                                    throw new java.io.UncheckedIOException(ex);
                                }
                            }
                        });
        final TaskLaunch.Request request =
                new TaskLaunch.Request(
                        "work",
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
                        TaskMode.SHELL,
                        null,
                        null,
                        null,
                        1,
                        "p");
        final int code =
                new TaskLaunch(context, request, steps)
                        .launch(
                                new PrintWriter(new StringWriter()),
                                new PrintWriter(new StringWriter()),
                                running -> 0);
        assertThat(events)
                .as("a failed image must report podman's final error, without its wrapper or ANSI")
                .extracting(
                        event ->
                                List.of(
                                        event.step().name(),
                                        event.state().name(),
                                        event.waitingOn(),
                                        event.cause()))
                .containsSubsequence(
                        List.of("IMAGE", "WAITING", "podman build", ""),
                        List.of("IMAGE", "FAILED", "", "registry unavailable"));
        assertThat(code).as("a failed image must stop the start").isEqualTo(70);
        assertThat(phase)
                .as("an ended start must not leave a phase that looks active")
                .doesNotExist();
    }

    @Test
    void aCauseCannotCarryTerminalControlsOrAnUnboundedDump() {
        // A dependency's controls and multiline dump must never reach the terminal as a cause.
        assertThat(StartStep.cleanCause("first\n\033[31mlast\033[0m\033[2K\t\u0000\u007f\n \n"))
                .as("only the last nonempty line without ANSI or controls may be shown")
                .isEqualTo("last");
        assertThat(StartStep.cleanCause("old\n" + "x".repeat(350)))
                .as("a dependency cannot flood a refusal beyond 300 characters")
                .hasSize(300);
    }

    @Test
    void aDeadlineKeepsTheErrorOutputThatExplainsTheHang() {
        // Killing a hung dependency must not throw away the only evidence of its cause.
        final Throwable failure =
                catchThrowable(
                        () ->
                                new ProcessCommandRunner(Duration.ofMillis(300))
                                        .run(
                                                Command.of(
                                                        "sh",
                                                        "-c",
                                                        "echo 'waiting for the server' >&2; exec sleep 30")));
        assertThat(failure)
                .as("the deadline must still end the run")
                .isInstanceOf(CommandException.class);
        assertThat(((CommandException) failure).standardError())
                .as("a deadline must retain the stderr that explains the hang")
                .contains("waiting for the server");
    }

    @Test
    void oneLaunchFeedsBothTheTerminalAndTheStructuredSteps() {
        // Independent progress paths could leave the daemon silent during a refusal.
        final List<StartStep> events = new ArrayList<>();
        final StringWriter rendered = new StringWriter();
        final PrintWriter out = new PrintWriter(rendered, true);
        final StartSteps steps =
                new StartSteps(
                        event -> {
                            events.add(event);
                            StartSteps.render(out, event);
                        });
        final TaskLaunch.Request request =
                new TaskLaunch.Request(
                        "work",
                        dir.resolve("absent.yml"),
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
        new TaskLaunch(context(new FakeCommandRunner()), request, steps)
                .launch(out, new PrintWriter(new StringWriter()), running -> 0);
        assertThat(events)
                .as("one launch must report source entry and its refusal to both consumers")
                .extracting(StartStep::state)
                .containsExactly(StartStep.State.STARTED, StartStep.State.FAILED);
        assertThat(rendered.toString())
                .as("the CLI must render the very events available to the daemon")
                .contains(
                        "step      " + events.getFirst().text(),
                        "failed    " + events.getLast().text());
    }
}
