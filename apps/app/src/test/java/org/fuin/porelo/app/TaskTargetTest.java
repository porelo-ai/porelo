package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link TaskTarget}: a task named either way, or meant by the checkout the person stands
 * in.
 */
class TaskTargetTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void anIncompleteShortenedOwnerCannotSelectAnotherProjectsTask(boolean mismatched)
            throws Exception {
        // Incomplete ownership must block unscoped commands from choosing a different task.
        listed =
                "porelo-other_work\tUp 1 minute\t\t\tother\tguarded\tr\t\t"
                        + OTHER_ID
                        + "\twork\n"
                        + "porelo-ws_x\tUp 1 minute\t\t\twebsite\tguarded\tr\t\t"
                        + ID
                        + "\twork\n";
        record("porelo-other_work", OTHER_ID);
        if (mismatched) record("porelo-ws_x", OTHER_ID);
        assertThat(new TaskIdentities(context).lookup("work", null))
                .as("a second logical claim must prevent choosing the other project")
                .isEqualTo(new TaskIdentities.Refused(TaskIdentities.Reason.AMBIGUOUS, "work"));
    }

    @Test
    void resolvesTheRecordedShortSpellingsAfterTheProjectChanges() throws Exception {
        // A changed project file must never redirect an existing task to another container.
        final String actual = "porelo-ws_x";
        listed = actual + "\tUp 1 minute\t\t\tdefault\tguarded\tutils4j\t\t" + ID + "\twork\n";
        final Path state = context.paths().tasks().taskRecord(actual);
        org.fuin.porelo.wire.Sidecar.recordId(state, ID);
        Files.writeString(
                state.resolve("sidecar.json"),
                new org.fuin.porelo.wire.Sidecar(
                                org.fuin.porelo.wire.Sidecar.VERSION,
                                "default",
                                "guarded",
                                "/rules",
                                "/dns",
                                "/porelo",
                                context.paths().tasks().containerState(actual).toString())
                        .toJson()
                        .replace(
                                "{",
                                "{\"task\":\"work\",\"projectShort\":\"ws\",\"taskShort\":\"x\","));
        final var before = new TaskIdentities(context).lookup("work", "default");
        assertThat(before)
                .as("the recorded short spelling proves the logical owner")
                .isInstanceOf(TaskIdentities.Found.class);
        final Path project = dir.resolve("changed.yml");
        Files.writeString(
                project,
                "project:\n  name: default\n  short: changed\n  security_class: guarded\nimage:\n  from: ubuntu:24.04\n");
        assertThat(org.fuin.porelo.core.project.ProjectReader.read(project).shortName())
                .as("the project now names another spelling")
                .isEqualTo("changed");
        assertThat(new TaskIdentities(context).lookup("work", "default"))
                .as("lookup uses the task's recorded spellings")
                .isEqualTo(before);
        assertThat(new TaskIdentities(context).retained(actual))
                .as("durable identity still resolves after runtime loss")
                .isEqualTo(before);
    }

    @Test
    void anAbsentActualNameRemainsAHistoryKeyOnlyForReads() {
        // Retained logs and the absent-status contract must survive container removal.
        final String actual = "porelo-default_utils4j";
        assertThat(TaskTarget.resolveRead(context, actual, dir, null).container())
                .isEqualTo(actual);
        assertThat(TaskTarget.resolve(context, actual, dir, null).reason())
                .isEqualTo(TaskIdentities.Reason.MISSING);
        assertThat(TaskTarget.resolve(context, actual, dir, null).container()).isNull();
    }

    @Test
    void resolvesAnEncodedTaskAndKeepsTheWholeNumericSuffix() throws Exception {
        // Resolving a different task can attach to or stop somebody else's container.
        listed =
                "porelo-default_app-x-123\tUp 1 minute\t\t\tdefault\tguarded\tutils4j\t\t"
                        + "a".repeat(64)
                        + "\tapp-x-123\n";
        org.fuin.porelo.wire.Sidecar.recordId(
                context.paths().tasks().taskRecord("porelo-default_app-x-123"), "a".repeat(64));
        assertThat(TaskTarget.resolve(context, "app-x-123", checkout, null).container())
                .isEqualTo("porelo-default_app-x-123");
    }

    @Test
    void ambiguousAndMissingNamesAreRefusedBeforeAnAction() {
        // Passing an unresolved spelling onward lets a mutator choose the wrong owner.
        listed =
                "porelo-web-x\tUp 1 minute\t\t\tweb\tguarded\tr\t\t"
                        + ID
                        + "\n"
                        + "porelo-other-x\tUp 1 minute\t\t\tother\tguarded\tr\t\t"
                        + OTHER_ID
                        + "\n";
        record("porelo-web-x");
        record("porelo-other-x", OTHER_ID);
        assertThat(TaskTarget.resolve(context, "x", dir, null).reason())
                .isEqualTo(TaskIdentities.Reason.AMBIGUOUS);
        assertThat(TaskTarget.resolve(context, "missing", dir, null).code())
                .isEqualTo(TaskTarget.NONE);
    }

    @Test
    void anExactActualNameWinsOverSeveralLogicalMatches() {
        // An exact target must not be redirected to a sibling in the current checkout.
        listed =
                "porelo-web-x\tUp 1 minute\t\t\tweb\tguarded\tr\t\t"
                        + ID
                        + "\n"
                        + "porelo-other_x\tUp 1 minute\t\t\tother\tguarded\tr\t\t"
                        + OTHER_ID
                        + "\tx\n";
        record("porelo-web-x");
        record("porelo-other_x", OTHER_ID);
        assertThat(TaskTarget.resolve(context, "porelo-web-x", checkout, null).container())
                .isEqualTo("porelo-web-x");
        assertThat(TaskTarget.resolve(context, "x", dir, null).reason())
                .isEqualTo(TaskIdentities.Reason.AMBIGUOUS);
    }

    @Test
    void defaultNamesCountBothFormatsAndAvoidTheLoginNamespace() {
        // An unnamed start must not take over an existing task or become login cleanup's target.
        listed =
                "porelo-default_utils4j\tUp 1 minute\t\t\tdefault\tguarded\tutils4j\t\t"
                        + ID
                        + "\tutils4j\n"
                        + "porelo-default-utils4j-2\tUp 1 minute\t\t\tdefault\tguarded\tutils4j\t\t"
                        + OTHER_ID
                        + "\n";
        record("porelo-default_utils4j");
        record("porelo-default-utils4j-2", OTHER_ID);
        assertThat(TaskNames.fromRepository(context, "default", "porelo-utils4j"))
                .isEqualTo("utils4j-3");
        assertThat(TaskNames.fromRepository(context, "porelo", "login-123"))
                .isEqualTo("login-123-work");
    }

    private static final String ID = "a".repeat(64);
    private static final String OTHER_ID = "b".repeat(64);

    @TempDir Path dir;

    private final CommandRunner real =
            command ->
                    new ProcessCommandRunner()
                            .run(
                                    command.withEnvironment(
                                            java.util.Map.of(
                                                    "GIT_CONFIG_GLOBAL",
                                                    "/dev/null",
                                                    "GIT_CONFIG_NOSYSTEM",
                                                    "1")));

    /** What podman lists: one line per task, as poreloTasks reads them. */
    private String listed = "";

    private PoreloContext context;

    private Path checkout;

    @BeforeEach
    void aCheckoutInDefault() throws Exception {
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
        final CommandRunner runner =
                command ->
                        command.arguments().size() > 1
                                        && command.arguments().get(0).endsWith("podman")
                                        && command.arguments().get(1).equals("ps")
                                ? new CommandResult(command, 0, listed, "")
                                : real.run(command);
        context =
                new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
        checkout = Files.createDirectories(dir.resolve("utils4j"));
        real.runOrFail(Command.of("git", "-C", checkout.toString(), "init", "-q"));
        new DefaultProject(context)
                .add(
                        checkout.toRealPath().toString(),
                        "utils4j",
                        checkout.toRealPath().toString(),
                        "");
    }

    private void record(String name) {
        record(name, ID);
    }

    private void record(String name, String id) {
        try {
            org.fuin.porelo.wire.Sidecar.recordId(context.paths().tasks().taskRecord(name), id);
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    private void tasks(final String... names) {
        final StringBuilder lines = new StringBuilder();
        int index = 0;
        for (final String name : names) {
            final String id = String.valueOf((char) ('a' + index++)).repeat(64);
            record(name, id);
            final String repository = name.startsWith("porelo-default-other") ? "other" : "utils4j";
            lines.append(name)
                    .append("\tUp 1 minute\t\t\tdefault\tguarded\t")
                    .append(repository)
                    .append("\t\t")
                    .append(id)
                    .append("\n");
        }
        listed = lines.toString();
    }

    @Test
    void noNameInACheckoutMeansTheTaskStartedFromIt() {

        // 'porelo task status' in a checkout wanted the container's name typed out.
        tasks("porelo-default-utils4j", "porelo-default-other");

        assertThat(TaskTarget.resolve(context, null, checkout, null).container())
                .isEqualTo("porelo-default-utils4j");
    }

    @Test
    void eitherNameOfATaskIsTaken() {
        tasks("porelo-default-utils4j");

        assertThat(TaskTarget.resolve(context, "utils4j", dir, null).container())
                .isEqualTo("porelo-default-utils4j");
        assertThat(TaskTarget.resolve(context, "porelo-default-utils4j", dir, null).container())
                .isEqualTo("porelo-default-utils4j");
        assertThat(TaskTarget.resolve(context, "nobody", dir, null).reason())
                .isEqualTo(TaskIdentities.Reason.MISSING);
    }

    @Test
    void aShortNameTasksOfTwoProjectsShareMeansTheOneOfTheCheckoutStoodIn() {
        // Two projects' tasks shown by one name: in a checkout, its project's is the one meant.
        listed =
                "porelo-elsewhere-utils4j\tUp 1 minute\t\t\telsewhere\tguarded\tutils4j\t\t"
                        + ID
                        + "\n"
                        + "porelo-default-utils4j\tUp 1 minute\t\t\tdefault\tguarded\tutils4j\t\t"
                        + OTHER_ID
                        + "\n";

        record("porelo-elsewhere-utils4j");
        record("porelo-default-utils4j", OTHER_ID);
        assertThat(TaskTarget.resolve(context, "utils4j", checkout, null).container())
                .as("the task of the project this checkout belongs to")
                .isEqualTo("porelo-default-utils4j");
        assertThat(TaskTarget.resolve(context, "utils4j", dir, null).container())
                .as("outside a checkout neither is guessed")
                .isNull();
    }

    @Test
    void severalAreNamedAndAskedForOrRefusedWhereNobodyCanBeAsked() {
        tasks("porelo-default-utils4j", "porelo-default-utils4j-2");

        final TaskTarget.Found refused = TaskTarget.resolve(context, null, checkout, null);
        assertThat(refused.container()).isNull();
        assertThat(refused.code()).isEqualTo(TaskTarget.SEVERAL);
        assertThat(refused.refusal())
                .contains("porelo-default-utils4j")
                .contains("porelo-default-utils4j-2");

        final List<String> asked = new java.util.ArrayList<>();
        assertThat(
                        TaskTarget.resolve(
                                        context,
                                        null,
                                        checkout,
                                        question -> {
                                            asked.add(question);
                                            return "utils4j-2";
                                        })
                                .container())
                .isEqualTo("porelo-default-utils4j-2");
        assertThat(asked).singleElement().asString().contains("Which one?");
    }

    @Test
    void noneStartedFromTheCheckoutIsSaidAndOutsideACheckoutANameIsAskedFor() throws Exception {
        tasks("porelo-default-other");

        final TaskTarget.Found none = TaskTarget.resolve(context, null, checkout, null);
        assertThat(none.container()).isNull();
        assertThat(none.code()).isEqualTo(TaskTarget.NONE);
        assertThat(none.refusal()).contains("no task was started from");

        final TaskTarget.Found outside =
                TaskTarget.resolve(
                        context, null, Files.createDirectories(dir.resolve("plain")), null);
        assertThat(outside.container()).isNull();
        assertThat(outside.refusal()).contains("name a task");
    }
}
