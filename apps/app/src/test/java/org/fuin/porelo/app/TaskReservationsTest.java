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
    void aReservationWithoutAContainerRefusesBeforeCredentialPreflight() throws Exception {
        final PoreloContext context = context();
        try (var claim =
                new TaskReservations(context)
                        .reserve("web", "app-x", "porelo-web-app-x", "first")) {}
        final Path project =
                Files.writeString(
                        dir.resolve("project.yml"),
                        "project: {name: web-app, security_class: guarded}\nimage: {from: 'ubuntu:24.04'}\n");
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
        assertThat(context.paths().tasks().containerState("porelo-web-app-x")).doesNotExist();
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
