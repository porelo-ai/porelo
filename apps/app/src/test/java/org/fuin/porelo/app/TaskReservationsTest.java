package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Protects another start's policy, mailbox and durable recovery records from replacement. */
class TaskReservationsTest {
    @TempDir Path dir;
    private final FakeCommandRunner runner = new StoppingCommandRunner();

    private PoreloContext context() {
        return new PoreloContext(
                runner,
                new PoreloPaths(
                        new XdgPaths(
                                dir.resolve("config"),
                                dir.resolve("data"),
                                dir.resolve("state"),
                                dir.resolve("run"),
                                dir),
                        dir.resolve("bin")),
                arguments -> 0);
    }

    @Test
    void aLockedVaultRefusesTheClaimInTheWordsEveryStartUses() throws Exception {
        // The start's refusal says "the vault is locked", as every other locked-vault answer does.
        final PoreloContext context = context();
        context.vault().write(java.util.Map.of(), "fixture passphrase".toCharArray());
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new TaskReservations(context).start("web", "x", "porelo-web-x"))
                .hasMessageStartingWith("the vault is locked; run 'porelo vault unlock'");
    }

    @Test
    void aReservationWithoutAContainerRefusesBeforeCredentialPreflight() throws Exception {
        final PoreloContext context = context();
        try (var claim =
                new TaskReservations(context).reserve("web", "x", "porelo-w_x", "first")) {}
        final Path project =
                Files.writeString(
                        dir.resolve("project.yml"),
                        "project: {name: web-app, short: w, security_class: guarded}\nimage: {from: 'ubuntu:24.04'}\n");
        final TaskLaunch launch =
                new TaskLaunch(
                        context,
                        new TaskLaunch.Request(
                                "x",
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
                                org.fuin.porelo.wire.TaskMode.AGENT,
                                null,
                                null,
                                null,
                                1,
                                "web-app"));
        final var sink = new java.io.PrintWriter(java.io.Writer.nullWriter());
        final TaskLaunch.Existing refused = launch.startExisting(sink, sink);
        assertThat(refused).isNotNull();
        assertThat(refused.code()).isEqualTo(64);
        assertThat(launch.containerNameTaken().ownerProject).isEqualTo("web");
        assertThat(context.paths().tasks().containerState("porelo-w_x")).doesNotExist();
    }

    @Test
    void removalCanFinishWhenOnlyItsCreatedClaimSurvived() throws Exception {
        final PoreloContext context = context().recognizingTasks();
        final String container = "porelo-web-x";
        final String id = "a".repeat(64);
        try (var claim = new TaskReservations(context).reserve("web", "x", container, "first")) {
            record(context, container, id);
            claim.created(id);
        }
        runner.answering("ps", container + "\tExited (0)\t\t\tweb\tguarded\tr\t\t" + id + "\n");
        runner.runOrFail(org.fuin.porelo.core.process.Command.of("podman", "rm", id));
        new TaskState(context).forget(container);
        try (var files = Files.walk(context.paths().tasks().containerState(container))) {
            for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList())
                Files.delete(file);
        }
        final var cli = PoreloCli.commandLine(context);
        cli.setOut(new java.io.PrintWriter(java.io.Writer.nullWriter()));
        cli.setErr(new java.io.PrintWriter(java.io.Writer.nullWriter()));
        assertThat(cli.execute("task", "remove", container, "--no-input")).isZero();
        assertThat(new VaultClearing(context).clear(false, true).refused()).isFalse();
    }

    @Test
    void removingAfterIdRecordingDoesNotRequireAnotherStartFirst() throws Exception {
        final PoreloContext context = context().recognizingTasks();
        final String container = "porelo-web-x";
        final String id = "a".repeat(64);
        try (var claim = new TaskReservations(context).reserve("web", "x", container, "first")) {
            record(context, container, id);
        }
        runner.answering("ps", container + "\tExited (0)\t\t\tweb\tguarded\tr\t\t" + id + "\n");
        runner.answering("{{.Names}}", container + "\n");
        assertThat(new TaskControl(context).remove(container, false, true).removed()).isTrue();
        assertThat(context.paths().tasks().containerState(container)).doesNotExist();
        assertThat(context.paths().tasks().taskRecord(container)).doesNotExist();
    }

    @Test
    void aRemovalWhoseMailboxStaysStillRetiresTheNameAndSaysSo() throws Exception {
        // The daemon wrote into the mailbox while it was deleted: the delete failed, the claim
        // stayed created with its container gone, and its project could no longer be removed.
        final PoreloContext context = context().recognizingTasks();
        final String container = "porelo-web-x";
        final String id = "a".repeat(64);
        final TaskReservations reservations = new TaskReservations(context);
        try (var claim = reservations.reserve("web", "x", container, "first")) {
            record(context, container, id);
        }
        runner.answering("ps", container + "\tExited (0)\t\t\tweb\tguarded\tr\t\t" + id + "\n");
        final Path stuck = undeletable(context, container);
        try {
            assertThatThrownBy(() -> new TaskControl(context).remove(container, false, true))
                    .as("the mailbox that stayed is still said")
                    .hasMessageContaining("could not delete its mailbox");
            assertThat(reservations.locked(() -> reservations.projectBusy("web")))
                    .as("the removed task's name is no unfinished start of its project")
                    .isNull();
            assertThat(reservations.protectedNames()).doesNotContain(container);
        } finally {
            Files.setPosixFilePermissions(
                    stuck, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void aRemovalOfAnUnmadeStartWhoseMailboxStaysStillRetiresTheNameAndSaysSo() throws Exception {
        final PoreloContext context = context().recognizingTasks();
        final String container = "porelo-web-x";
        final TaskReservations reservations = new TaskReservations(context);
        try (var claim = reservations.reserve("web", "x", container, "first")) {}
        final Path stuck = undeletable(context, container);
        try {
            final var cli = PoreloCli.commandLine(context);
            final java.io.StringWriter said = new java.io.StringWriter();
            cli.setOut(new java.io.PrintWriter(java.io.Writer.nullWriter()));
            cli.setErr(new java.io.PrintWriter(said, true));
            assertThat(cli.execute("task", "remove", container, "--no-input", "--force"))
                    .as("a removal that left a directory is no success: %s", said)
                    .isEqualTo(70);
            assertThat(reservations.locked(() -> reservations.projectBusy("web")))
                    .as("the removed start's name is no unfinished start of its project")
                    .isNull();
            assertThat(reservations.protectedNames()).doesNotContain(container);
            assertThat(said.toString())
                    .as("it says what stayed")
                    .contains("could not delete its mailbox: " + container);
        } finally {
            Files.setPosixFilePermissions(
                    stuck, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void aRemovalWhoseGuideCannotBeWalkedStillRetiresTheNameAndSaysSo() throws Exception {
        // A walk that meets an unreadable directory throws unchecked: caught as an IOException
        // only, it skipped the retire, the claim stayed created, and the project was busy.
        final PoreloContext context = context().recognizingTasks();
        final String container = "porelo-web-x";
        final String id = "a".repeat(64);
        final TaskReservations reservations = new TaskReservations(context);
        try (var claim = reservations.reserve("web", "x", container, "first")) {
            record(context, container, id);
        }
        runner.answering("ps", container + "\tExited (0)\t\t\tweb\tguarded\tr\t\t" + id + "\n");
        final Path stuck = unwalkable(context.paths().tasks().guide(container));
        try {
            assertThatThrownBy(() -> new TaskControl(context).remove(container, false, true))
                    .as("what stayed is still said")
                    .hasRootCauseInstanceOf(java.nio.file.AccessDeniedException.class);
            assertThat(reservations.locked(() -> reservations.projectBusy("web")))
                    .as("the removed task's name is no unfinished start of its project")
                    .isNull();
            assertThat(reservations.protectedNames()).doesNotContain(container);
        } finally {
            Files.setPosixFilePermissions(
                    stuck, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
    }

    /**
     * Makes a directory whose walk throws {@link java.io.UncheckedIOException}: a directory in it
     * that cannot be read. Root reads it all the same.
     */
    private static Path unwalkable(Path directory) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                "root".equals(System.getProperty("user.name")),
                "root reads an unreadable directory, so the walk cannot be made to fail");
        final Path closed = Files.createDirectories(directory.resolve("closed"));
        Files.setPosixFilePermissions(
                closed, java.nio.file.attribute.PosixFilePermissions.fromString("---------"));
        return closed;
    }

    @Test
    void anUnfinishedStartsRefusalNamesTheRemovalThatTakesIt() throws Exception {
        // On a VM a start refused at the gate left its claim, and project clear said
        // "retry it or remove that task first" without naming what 'task remove' takes.
        final PoreloContext context = context().recognizingTasks();
        final String container = "porelo-web-x";
        final TaskReservations reservations = new TaskReservations(context);
        try (var claim = reservations.reserve("web", "x", container, "first")) {}
        assertThat(reservations.locked(() -> reservations.projectBusy("web")))
                .as("the refusal names the removal by the name it takes")
                .contains("porelo task remove " + container);
    }

    @Test
    void anUnfinishedStartIsRemovedByTheTasksOwnName() throws Exception {
        // 'porelo task remove cnt --yes --force' found nothing: only the container's name was
        // taken for a start that never made its container.
        final PoreloContext context = context().recognizingTasks();
        final String container = "porelo-web-x";
        final TaskReservations reservations = new TaskReservations(context);
        try (var claim = reservations.reserve("web", "x", container, "first")) {}
        final var cli = PoreloCli.commandLine(context);
        final java.io.StringWriter said = new java.io.StringWriter();
        cli.setOut(new java.io.PrintWriter(said, true));
        cli.setErr(new java.io.PrintWriter(said, true));
        assertThat(cli.execute("task", "remove", "x", "--no-input", "--force"))
                .as("the task's own name takes its unfinished start: %s", said)
                .isZero();
        assertThat(said.toString()).contains("removed   " + container);
        assertThat(reservations.locked(() -> reservations.projectBusy("web")))
                .as("its project can be cleared")
                .isNull();
        assertThat(reservations.protectedNames()).doesNotContain(container);
    }

    @Test
    void unfinishedStartsOfOneNameInSeveralProjectsAreNamedNotGuessed() throws Exception {
        final PoreloContext context = context().recognizingTasks();
        final TaskReservations reservations = new TaskReservations(context);
        try (var claim = reservations.reserve("web", "x", "porelo-web-x", "first")) {}
        try (var claim = reservations.reserve("api", "x", "porelo-api-x", "first")) {}
        final var cli = PoreloCli.commandLine(context);
        final java.io.StringWriter said = new java.io.StringWriter();
        cli.setOut(new java.io.PrintWriter(said, true));
        cli.setErr(new java.io.PrintWriter(said, true));
        assertThat(cli.execute("task", "remove", "x", "--no-input", "--force"))
                .as("which one is not guessed: %s", said)
                .isEqualTo(TaskTarget.SEVERAL);
        assertThat(said.toString()).contains("porelo-web-x").contains("porelo-api-x");
        assertThat(reservations.protectedNames()).contains("porelo-web-x", "porelo-api-x");
    }

    /**
     * Makes a mailbox whose delete fails: one of its directories holds a file and is read-only. Not
     * its record, which the removal copies under a lock it takes inside it. Root deletes it all the
     * same.
     */
    private static Path undeletable(PoreloContext context, String container) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                "root".equals(System.getProperty("user.name")),
                "root deletes from a read-only directory, so the delete cannot be made to fail");
        final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox(container));
        mailbox.create();
        Files.writeString(mailbox.accepted().resolve("entry"), "kept");
        Files.setPosixFilePermissions(
                mailbox.accepted(),
                java.nio.file.attribute.PosixFilePermissions.fromString("r-x------"));
        return mailbox.accepted();
    }

    @Test
    void aRemovedTasksRecordedMailIsArchivedAndItsNameIsFree() throws Exception {
        // On the upgrade VM a removed task's record outlived it and its next start was refused.
        final PoreloContext context = context().recognizingTasks();
        final String container = "porelo-web-x";
        final String id = "a".repeat(64);
        try (var claim = new TaskReservations(context).reserve("web", "x", container, "first")) {
            record(context, container, id);
        }
        final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox(container));
        mailbox.create();
        new MessageRecord(mailbox).append(MessageRecord.TAKEN, "m-1.json", "m-1", "signed");
        final String recorded = Files.readString(mailbox.record().resolve(MessageRecord.FILE));
        runner.answering("ps", container + "\tExited (0)\t\t\tweb\tguarded\tr\t\t" + id + "\n");
        runner.answering("{{.Names}}", container + "\n");

        assertThat(new TaskControl(context).remove(container, false, true).removed()).isTrue();

        assertThat(mailbox.root()).as("the mailbox went with the task").doesNotExist();
        final List<Path> archived;
        try (var found = Files.list(context.paths().messaging().archive())) {
            archived = found.toList();
        }
        assertThat(archived)
                .as("one archive of the removed task's record")
                .singleElement()
                .satisfies(
                        kept -> {
                            assertThat(kept.getFileName().toString()).startsWith(container + "-");
                            assertThat(kept.resolve(MessageRecord.FILE)).hasContent(recorded);
                        });
        runner.answering("ps", "");
        runner.answering("{{.Names}}", "");
        try (var again = new TaskReservations(context).reserve("web", "x", container, "second")) {
            assertThat(again).as("the name is free for the next start").isNotNull();
        }
    }

    @Test
    void aRecordThatCannotBeCopiedLeavesTheTaskRemovableAgain() throws Exception {
        // The copy failed after the task's records were forgotten: the mailbox and the name stayed,
        // and a second 'task remove' answered that it was no task.
        final PoreloContext context = context().recognizingTasks();
        final String container = "porelo-web-x";
        final String id = "a".repeat(64);
        try (var claim = new TaskReservations(context).reserve("web", "x", container, "first")) {
            record(context, container, id);
        }
        final Mailbox mailbox = recorded(context, container);
        final Path unreadable = mailbox.record().resolve("zz-unreadable");
        Files.writeString(unreadable, "kept\n");
        Files.setPosixFilePermissions(
                unreadable, java.nio.file.attribute.PosixFilePermissions.fromString("---------"));
        runner.answering("ps", container + "\tExited (0)\t\t\tweb\tguarded\tr\t\t" + id + "\n");
        runner.answering("{{.Names}}", container + "\n");

        assertThatThrownBy(() -> new TaskControl(context).remove(container, false, true))
                .as("the words name the copy")
                .hasMessageContaining("Could not copy the mail record of " + container);

        assertThat(mailbox.record()).as("the mailbox is kept").isDirectory();
        assertThat(archived(context)).as("no half of a copy is left").isEmpty();
        assertThat(context.paths().tasks().taskRecord(container))
                .as("nothing of the task was forgotten")
                .isDirectory();

        Files.setPosixFilePermissions(
                unreadable, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        assertThat(new TaskControl(context).remove(container, false, true).removed())
                .as("removing it again tries the copy once more")
                .isTrue();
        assertThat(mailbox.root()).doesNotExist();
        assertThat(archived(context))
                .singleElement()
                .satisfies(kept -> assertThat(kept.resolve("zz-unreadable")).hasContent("kept\n"));
        runner.answering("ps", "");
        runner.answering("{{.Names}}", "");
        try (var again = new TaskReservations(context).reserve("web", "x", container, "second")) {
            assertThat(again).as("the name is free for the next start").isNotNull();
        }
    }

    @Test
    void anUnfinishedStartsRecordThatCannotBeCopiedLeavesItRemovableAgain() throws Exception {
        // A start that made no container yet: its records were forgotten before the copy failed,
        // and a second removal no longer found it unfinished.
        final PoreloContext context = context().recognizingTasks();
        final String container = "porelo-web-x";
        try (var claim = new TaskReservations(context).reserve("web", "x", container, "first")) {}
        final Mailbox mailbox = recorded(context, container);
        final Path unreadable = mailbox.record().resolve("zz-unreadable");
        Files.writeString(unreadable, "kept\n");
        Files.setPosixFilePermissions(
                unreadable, java.nio.file.attribute.PosixFilePermissions.fromString("---------"));
        runner.answering("ps", "");
        runner.answering("{{.Names}}", "");

        assertThatThrownBy(() -> new TaskControl(context).remove(container, false, true))
                .hasMessageContaining("Could not copy the mail record of " + container);
        assertThat(mailbox.record()).as("the mailbox is kept").isDirectory();
        assertThat(archived(context)).as("no half of a copy is left").isEmpty();

        Files.setPosixFilePermissions(
                unreadable, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        assertThat(new TaskControl(context).remove(container, false, true).removed())
                .as("still unfinished, and removed this time")
                .isTrue();
        assertThat(mailbox.root()).doesNotExist();
        assertThat(archived(context)).hasSize(1);
        try (var again = new TaskReservations(context).reserve("web", "x", container, "second")) {
            assertThat(again).as("the name is free for the next start").isNotNull();
        }
    }

    @Test
    void theArchiveIsTheAccountsAloneFromItsMaking() throws Exception {
        // Made with the umask's modes and closed afterwards, other accounts could list which tasks
        // were removed, and read in the moment between.
        final PoreloContext context = context().recognizingTasks();
        final String container = "porelo-web-x";
        final String id = "a".repeat(64);
        try (var claim = new TaskReservations(context).reserve("web", "x", container, "first")) {
            record(context, container, id);
        }
        final Mailbox mailbox = recorded(context, container);
        final Path answers = Files.createDirectories(mailbox.record().resolve("answers"));
        Files.writeString(answers.resolve("a-1.json"), "{}");
        Files.setPosixFilePermissions(
                answers.resolve("a-1.json"),
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
        runner.answering("ps", container + "\tExited (0)\t\t\tweb\tguarded\tr\t\t" + id + "\n");
        runner.answering("{{.Names}}", container + "\n");

        assertThat(new TaskControl(context).remove(container, false, true).removed()).isTrue();

        final Path archive = context.paths().messaging().archive();
        final Path kept = archived(context).getFirst();
        assertThat(kept.resolve("answers/a-1.json")).as("its answers kept too").hasContent("{}");
        for (final Path directory : List.of(archive, kept, kept.resolve("answers"))) {
            assertThat(modes(directory)).as(directory.toString()).isEqualTo("rwx------");
        }
        for (final Path file :
                List.of(kept.resolve(MessageRecord.FILE), kept.resolve("answers/a-1.json"))) {
            assertThat(modes(file)).as(file.toString()).isEqualTo("rw-------");
        }
    }

    private static Mailbox recorded(PoreloContext context, String container) throws Exception {
        final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox(container));
        mailbox.create();
        new MessageRecord(mailbox).append(MessageRecord.TAKEN, "m-1.json", "m-1", "signed");
        return mailbox;
    }

    private static List<Path> archived(PoreloContext context) throws Exception {
        final Path archive = context.paths().messaging().archive();
        if (!Files.isDirectory(archive)) {
            return List.of();
        }
        try (var found = Files.list(archive)) {
            return found.toList();
        }
    }

    private static String modes(Path path) throws Exception {
        return java.nio.file.attribute.PosixFilePermissions.toString(
                Files.getPosixFilePermissions(path, java.nio.file.LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void resumeCannotBypassContradictoryLegacyOwnership() throws Exception {
        final PoreloContext context = context().recognizingTasks();
        final String container = "porelo-web-x";
        final String id = "a".repeat(64);
        record(context, container, id);
        runner.answering(
                "--no-trunc", container + "\tExited (0)\t\t\tother\tguarded\tr\t\t" + id + "\n");
        assertThatThrownBy(() -> new TaskControl(context).resume(container))
                .isInstanceOf(TaskReservations.ContainerNameTaken.class);
        assertThat(runner.lines())
                .noneMatch(line -> line.contains("podman start") || line.contains("shield watch"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void cliRemovalCancelsAnAttemptWithNoContainer(boolean partialState) throws Exception {
        final PoreloContext context = context().recognizingTasks();
        try (var claim =
                new TaskReservations(context).reserve("web", "x", "porelo-web-x", "first")) {}
        final Path state = context.paths().tasks().containerState("porelo-web-x");
        if (partialState) {
            Files.createDirectories(state);
            Files.writeString(state.resolve("sentinel"), "the failed start's state");
        }
        final var cli = PoreloCli.commandLine(context);
        cli.setOut(new java.io.PrintWriter(java.io.Writer.nullWriter()));
        cli.setErr(new java.io.PrintWriter(java.io.Writer.nullWriter()));
        assertThat(cli.execute("task", "remove", "porelo-web-x", "--no-input")).isZero();
        assertThat(state).doesNotExist();
        assertThat(new VaultClearing(context).clear(false, true).refused()).isFalse();
    }

    @Test
    void recoveryAfterIdRecordingCompletesTheAttemptBeforeAdministrativeClearing()
            throws Exception {
        final PoreloContext context = context();
        final TaskReservations reservations = new TaskReservations(context);
        final String container = "porelo-web-x";
        final String id = "a".repeat(64);
        try (var first = reservations.reserve("web", "x", container, "first")) {
            record(context, container, id);
        }
        runner.answering(
                "--no-trunc", container + "\tExited (0)\t\t\tweb\tguarded\tr\t\t" + id + "\n");
        runner.answering("{{.Names}}", container + "\n");
        try (var retry = reservations.resume(container).orElseThrow()) {}
        assertThat(new ProjectDeletion(context).delete("web", true, false).outcome())
                .isNotEqualTo(ProjectDeletion.Outcome.FAILED);
    }

    @Test
    void removalCannotDeleteAnActiveStartsState() throws Exception {
        final PoreloContext context = context();
        try (var claim =
                new TaskReservations(context).reserve("web", "x", "porelo-web-x", "first")) {
            final Path sentinel =
                    context.paths().tasks().containerState("porelo-web-x").resolve("sentinel");
            Files.createDirectories(sentinel.getParent());
            Files.writeString(sentinel, "kept");
            try (var pool = Executors.newSingleThreadExecutor()) {
                pool.submit(
                                () -> {
                                    assertThatThrownBy(
                                                    () ->
                                                            new TaskControl(context)
                                                                    .remove(
                                                                            "porelo-web-x",
                                                                            false,
                                                                            true))
                                            .isInstanceOf(
                                                    TaskReservations.ContainerNameTaken.class);
                                })
                        .get(5, TimeUnit.SECONDS);
            }
            assertThat(sentinel).exists();
        }
    }

    @Test
    void retiringReleasesTheAttemptButKeepsHistoricalOwnership() throws Exception {
        final TaskReservations reservations = new TaskReservations(context());
        try (var claim = reservations.reserve("web", "x", "porelo-web-x", "first")) {}
        reservations.retire("porelo-web-x");
        assertThat(reservations.protectedNames()).doesNotContain("porelo-web-x");
        assertThatThrownBy(() -> reservations.start("other", "x", "porelo-web-x"))
                .isInstanceOf(TaskReservations.ContainerNameTaken.class);
        try (var next = reservations.start("web", "x", "porelo-web-x")) {
            assertThat(next.attempt()).isNotEqualTo("first");
        }
    }

    @Test
    void reservingARetiredNameDeletesItsLeftoverMailbox() throws Exception {
        final PoreloContext context = context();
        final String container = org.fuin.porelo.runtime.ContainerName.encoded("web", "x", 1000);
        final TaskReservations reservations = new TaskReservations(context);
        try (final var original = reservations.reserve("web", "x", container, "first")) {}
        reservations.retire(container);
        final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox(container));
        mailbox.create();
        Files.writeString(mailbox.inboxCur().resolve("old-message.json"), "{}");
        Files.writeString(mailbox.rejected().resolve("old-original.json"), "{}");

        try (final var next = reservations.start("web", "x", container)) {
            assertThat(mailbox.root())
                    .as("a new reservation takes none of the removed task's mailbox")
                    .doesNotExist();
            assertThat(next.attempt())
                    .as("the new task holds an attempt of its own")
                    .isNotEqualTo("first");
        }
    }

    @Test
    void aRetiredMailboxThatCannotBeDeletedRefusesTheStartWithRecoveryGuidance() throws Exception {
        final PoreloContext context = context();
        final String container = org.fuin.porelo.runtime.ContainerName.encoded("web", "x", 1000);
        final TaskReservations reservations = new TaskReservations(context);
        try (final var original = reservations.reserve("web", "x", container, "first")) {}
        reservations.retire(container);
        final Path stuck = undeletable(context, container);
        final Path project =
                Files.writeString(
                        dir.resolve("project.yml"),
                        "project: {name: web, security_class: guarded}\nimage: {from: 'ubuntu:24.04'}\n");
        new ProjectRegistry(context.paths().projects().projectRegistry()).remember("web", project);
        final var cli = PoreloCli.commandLine(context);
        final java.io.StringWriter said = new java.io.StringWriter();
        cli.setOut(new java.io.PrintWriter(java.io.Writer.nullWriter()));
        cli.setErr(new java.io.PrintWriter(said, true));
        try {
            assertThatThrownBy(() -> reservations.start("web", "x", container))
                    .as("the reservation reports a failed mailbox cleanup")
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessageContaining("mailbox")
                    .hasMessageContaining("porelo prune")
                    .hasMessageNotContaining(
                            context.paths().messaging().mailbox(container).toString());
            assertThat(
                            cli.execute(
                                    "task",
                                    "start",
                                    "x",
                                    "-p",
                                    "web",
                                    "--repository",
                                    "web",
                                    "--attach",
                                    "shell",
                                    "--no-input"))
                    .as("a leftover mailbox must refuse before a new task is made: %s", said)
                    .isEqualTo(70);
            assertThat(said.toString())
                    .as("the refusal names the kind and recovery command without exposing paths")
                    .contains("mailbox", "porelo prune")
                    .doesNotContain(context.paths().messaging().mailbox(container).toString())
                    .doesNotContain(stuck.toString());
            assertThat(reservations.removed(container))
                    .as("a failed cleanup leaves the name retired for prune or another start")
                    .isTrue();
            assertThat(runner.lines())
                    .as("a refusal must not build or create a container")
                    .noneMatch(
                            line ->
                                    line.contains("podman create")
                                            || line.contains("podman build"));
        } finally {
            Files.setPosixFilePermissions(
                    stuck, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        }
        try (final var next = reservations.start("web", "x", container)) {
            assertThat(context.paths().messaging().mailbox(container))
                    .as("repairing the leftover lets a later reservation clean it and proceed")
                    .doesNotExist();
        }
    }

    @Test
    void anotherOwnerCannotDeleteARetiredTasksLeftoverMailbox() throws Exception {
        final PoreloContext context = context();
        final String container = org.fuin.porelo.runtime.ContainerName.contract("web", "app-x");
        final TaskReservations reservations = new TaskReservations(context);
        try (final var original = reservations.reserve("web", "app-x", container, "first")) {}
        reservations.retire(container);
        final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox(container));
        mailbox.create();
        final Path message = Files.writeString(mailbox.inboxCur().resolve("message.json"), "{}");

        assertThatThrownBy(() -> reservations.start("web-app", "x", container))
                .as("historical ownership must be checked before cleanup")
                .isInstanceOf(TaskReservations.ContainerNameTaken.class);
        assertThat(message).as("another owner's conversation is left alone").hasContent("{}");
    }

    @Test
    void aRetryCannotFollowAReplacedMailboxLink() throws Exception {
        final PoreloContext context = context();
        final TaskReservations reservations = new TaskReservations(context);
        try (var claim = reservations.reserve("web", "x", "porelo-web-x", "first")) {}
        final Path foreign = Files.createDirectories(dir.resolve("foreign-mail"));
        Files.createDirectories(context.paths().messaging().mailboxes());
        Files.createSymbolicLink(context.paths().messaging().mailbox("porelo-web-x"), foreign);
        assertThatThrownBy(() -> reservations.reserve("web", "x", "porelo-web-x", "first"))
                .isInstanceOf(TaskReservations.ContainerNameTaken.class);
        try (var files = Files.list(foreign)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void transportOnlyOwnershipReservesTheName() throws Exception {
        final PoreloContext context = context();
        final Path addresses =
                context.paths().xdg().state().resolve("transport/room/addresses/web.json");
        Files.createDirectories(addresses.getParent());
        Files.writeString(addresses, "{\"porelo-web-x\":\"remote-person\"}");
        assertThatThrownBy(
                        () ->
                                new TaskReservations(context)
                                        .reserve("web", "x", "porelo-web-x", "first"))
                .isInstanceOf(TaskReservations.ContainerNameTaken.class);
        assertThat(context.paths().tasks().containerState("porelo-web-x")).doesNotExist();
    }

    @Test
    void aCreatedAttemptKeepsItsIdAndRefusesEveryOtherAttempt() throws Exception {
        final PoreloContext context = context();
        final TaskReservations reservations = new TaskReservations(context);
        final String container = "porelo-web-x";
        final String id = "a".repeat(64);
        try (var original = reservations.reserve("web", "x", container, "first")) {
            record(context, container, id);
            original.created(id);
        }
        runner.answering(
                "--no-trunc", container + "\tExited (0)\t\t\tweb\tguarded\tr\t\t" + id + "\n");
        runner.answering("{{.Names}}", container + "\n");
        assertThatThrownBy(() -> reservations.reserve("web", "x", container, "other"))
                .isInstanceOf(TaskReservations.ContainerNameTaken.class);
        try (var retry = reservations.reserve("web", "x", container, "first")) {
            assertThat(context.recordedId(container)).isEqualTo(id);
            retry.created(id);
        }
        org.fuin.porelo.wire.Sidecar.recordId(
                context.paths().tasks().containerState(container), "b".repeat(64));
        assertThatThrownBy(() -> reservations.reserve("web", "x", container, "first"))
                .isInstanceOf(TaskReservations.ContainerNameTaken.class);
    }

    @Test
    void conflictingIdCopiesRefuseFinalization() throws Exception {
        final PoreloContext context = context();
        try (var claim =
                new TaskReservations(context).reserve("web", "x", "porelo-web-x", "first")) {
            record(context, "porelo-web-x", "a".repeat(64));
            org.fuin.porelo.wire.Sidecar.recordId(
                    context.paths().tasks().containerState("porelo-web-x"), "b".repeat(64));
            assertThatThrownBy(() -> claim.created("a".repeat(64)))
                    .isInstanceOf(TaskReservations.ContainerNameTaken.class);
        }
    }

    private static void record(PoreloContext context, String container, String id)
            throws Exception {
        for (Path directory :
                List.of(
                        context.paths().tasks().containerState(container),
                        context.paths().tasks().taskRecord(container))) {
            org.fuin.porelo.wire.Sidecar.recordId(directory, id);
            new org.fuin.porelo.wire.Sidecar(
                            3,
                            "web",
                            "guarded",
                            "ruleset.nft",
                            "dns.conf",
                            "porelo",
                            context.paths().tasks().containerState(container).toString())
                    .writeTo(directory.resolve("sidecar.json"));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(TaskReservations.Publication.class)
    void publicationFailureNeverLendsTheNameToAnotherAttempt(TaskReservations.Publication failure)
            throws Exception {
        final PoreloContext context = context();
        final TaskReservations broken =
                new TaskReservations(
                        context,
                        point -> {
                            if (point == failure)
                                throw new java.io.IOException("injected publication failure");
                        });
        assertThatThrownBy(() -> broken.reserve("web", "x", "porelo-web-x", "first"))
                .isInstanceOf(java.io.IOException.class);
        final TaskReservations recovered = new TaskReservations(context);
        if (failure == TaskReservations.Publication.AFTER) {
            assertThatThrownBy(() -> recovered.reserve("web", "x", "porelo-web-x", "second"))
                    .isInstanceOf(TaskReservations.ContainerNameTaken.class);
        }
        try (var retry = recovered.reserve("web", "x", "porelo-web-x", "first")) {
            assertThat(retry.attempt()).isEqualTo("first");
        }
    }

    @Test
    void pruneAndTokenKeepingPreserveBothTaskAndTransportScopesImmediately() throws Exception {
        final PoreloContext context = context().asking(shown -> "test-passphrase".toCharArray());
        context.vault().write(java.util.Map.of(), "test-passphrase".toCharArray());
        assertThat(
                        context.openerAsking(
                                false, new java.io.PrintWriter(java.io.Writer.nullWriter())))
                .isPresent();
        try {
            try (var claim =
                    new TaskReservations(context).reserve("web", "x", "porelo-web-x", "first")) {}
            context.vault()
                    .update(
                            context.opener().orElseThrow(),
                            entries -> {
                                entries.put(
                                        "task/porelo-web-x/signing-key",
                                        new org.fuin.porelo.vault.VaultEntry("sentinel", null));
                                entries.put(
                                        "transport/room/task/porelo-web-x",
                                        new org.fuin.porelo.vault.VaultEntry("sentinel", null));
                                return entries;
                            });
            new Prune(context).run(true, true);
            new TaskSecrets(context)
                    .keep(
                            "porelo-web-y",
                            new TaskSecrets.Tokens("other", null),
                            java.util.Set.of());
            assertThat(context.vault().read(context.opener().orElseThrow()).keySet())
                    .contains("task/porelo-web-x/signing-key", "transport/room/task/porelo-web-x");
        } finally {
            context.forgetTyped();
        }
    }

    @Test
    void cliAndDaemonCannotBothMakeTheSameTask() throws Exception {
        final PoreloContext context = context();
        final CountDownLatch ready = new CountDownLatch(2);
        final CountDownLatch go = new CountDownLatch(1);
        final CountDownLatch claimed = new CountDownLatch(1);
        final CountDownLatch otherRefused = new CountDownLatch(1);
        final AtomicInteger made = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            final var callers =
                    List.of("cli", "daemon").stream()
                            .map(
                                    caller ->
                                            pool.submit(
                                                    () -> {
                                                        ready.countDown();
                                                        assertThat(go.await(5, TimeUnit.SECONDS))
                                                                .isTrue();
                                                        try (var lease =
                                                                new TaskReservations(context)
                                                                        .reserve(
                                                                                "web",
                                                                                "app-x",
                                                                                "porelo-web-app-x",
                                                                                UUID.randomUUID()
                                                                                        .toString())) {
                                                            made.incrementAndGet();
                                                            final Path policy =
                                                                    context.paths()
                                                                            .tasks()
                                                                            .containerState(
                                                                                    "porelo-web-app-x");
                                                            Files.createDirectories(policy);
                                                            Files.writeString(
                                                                    policy.resolve("policy"),
                                                                    caller,
                                                                    java.nio.file.StandardOpenOption
                                                                            .CREATE_NEW);
                                                            final Path mailbox =
                                                                    context.paths()
                                                                            .messaging()
                                                                            .mailbox(
                                                                                    "porelo-web-app-x");
                                                            Files.createDirectories(mailbox);
                                                            Files.writeString(
                                                                    mailbox.resolve("sentinel"),
                                                                    caller,
                                                                    java.nio.file.StandardOpenOption
                                                                            .CREATE_NEW);
                                                            context.runner()
                                                                    .runOrFail(
                                                                            org.fuin.porelo.core
                                                                                    .process.Command
                                                                                    .of(
                                                                                            "podman",
                                                                                            "create",
                                                                                            "--name",
                                                                                            "porelo-web-app-x"));
                                                            claimed.countDown();
                                                            assertThat(
                                                                            otherRefused.await(
                                                                                    5,
                                                                                    TimeUnit
                                                                                            .SECONDS))
                                                                    .isTrue();
                                                        } catch (
                                                                TaskReservations.ContainerNameTaken
                                                                        ex) {
                                                            assertThat(
                                                                            claimed.await(
                                                                                    5,
                                                                                    TimeUnit
                                                                                            .SECONDS))
                                                                    .isTrue();
                                                            otherRefused.countDown();
                                                        }
                                                        return null;
                                                    }))
                            .toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (var caller : callers) caller.get(10, TimeUnit.SECONDS);
        }
        assertThat(made).hasValue(1);
        assertThat(runner.lines().stream().filter(line -> line.contains("podman create")).count())
                .isEqualTo(1);
    }

    @Test
    void anotherLogicalOwnerCannotTakeAnOverlappingLegacyName() throws Exception {
        final TaskReservations reservations = new TaskReservations(context());
        try (var original = reservations.reserve("web", "app-x", "porelo-web-app-x", "first")) {
            assertThatThrownBy(
                            () -> reservations.reserve("web-app", "x", "porelo-web-app-x", "other"))
                    .isInstanceOfSatisfying(
                            TaskReservations.ContainerNameTaken.class,
                            taken -> {
                                assertThat(taken.ownerProject).isEqualTo("web");
                                assertThat(taken.ownerTask).isEqualTo("app-x");
                            });
        }
        assertThat(context().paths().tasks().containerState("porelo-web-app-x")).doesNotExist();
        assertThat(context().paths().messaging().mailbox("porelo-web-app-x")).doesNotExist();
    }

    @Test
    void onlyTheOriginalAttemptCanContinueAfterPublication() throws Exception {
        final TaskReservations reservations = new TaskReservations(context());
        try (var original = reservations.reserve("web", "x", "porelo-web-x", "first")) {}
        assertThatThrownBy(() -> reservations.reserve("web", "x", "porelo-web-x", "second"))
                .isInstanceOf(TaskReservations.ContainerNameTaken.class);
        try (var retry = reservations.reserve("web", "x", "porelo-web-x", "first")) {
            assertThat(retry.attempt()).isEqualTo("first");
        }
    }

    @Test
    void anUnrecordedContainerIsNeverAdoptedEvenByTheOriginalAttempt() throws Exception {
        final TaskReservations reservations = new TaskReservations(context());
        try (var original = reservations.reserve("web", "x", "porelo-web-x", "first")) {}
        runner.answering("{{.Names}}", "porelo-web-x\n");
        assertThatThrownBy(() -> reservations.reserve("web", "x", "porelo-web-x", "first"))
                .isInstanceOf(TaskReservations.ContainerNameTaken.class);
    }

    @Test
    void aBrokenClaimLinkAndUnownedMailboxBothKeepTheName() throws Exception {
        final TaskReservations reservations = new TaskReservations(context());
        Files.createDirectories(reservations.claim("porelo-web-x").getParent());
        Files.createSymbolicLink(reservations.claim("porelo-web-x"), dir.resolve("absent"));
        assertThatThrownBy(() -> reservations.reserve("web", "x", "porelo-web-x", "first"))
                .isInstanceOf(TaskReservations.ContainerNameTaken.class);
        Files.createDirectories(context().paths().messaging().mailbox("porelo-web-y"));
        assertThatThrownBy(() -> reservations.reserve("web", "y", "porelo-web-y", "first"))
                .isInstanceOf(TaskReservations.ContainerNameTaken.class);
        try (var alternative = reservations.reserve("web", "z", "porelo-web-z", "alternative")) {
            assertThat(context().paths().tasks().containerState("porelo-web-z")).doesNotExist();
        }
    }
}
