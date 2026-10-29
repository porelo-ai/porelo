package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.Sidecar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for removing what Porelo built for a project.
 *
 * <p>This is the only thing here that destroys, so what is checked hardest is what it refuses to do
 * and what it leaves alone. An unreviewed push exists only in the mirror; a project file belongs to
 * the operator; and a preview that touched anything would make the confirmation a lie.
 */
class ProjectDeletionTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"preview", "gate", "task"})
    void aPreviewOrRefusalKeepsTheFollowRecordAndPinnedKey(String reason, @TempDir Path dir)
            throws IOException {
        // Refusing removal must not silently stop updates or discard the project's trust anchor.
        final PoreloContext context = context(dir);
        project("uc");
        final FollowedProjects projects =
                new FollowedProjects(context.paths().projects().followed());
        final FollowedProjects.Followed previous = projects.follow("uc", "git@example.org:uc.git");
        final Path signers = context.paths().projects().configurationSigners();
        Files.createDirectories(signers.getParent());
        Files.writeString(signers, "uc pinned-key\nother other-key\n");
        if (reason.equals("gate")) {
            runner.answering("for-each-ref", "refs/porelo/incoming/shell\n");
        } else if (reason.equals("task")) {
            task("porelo-uc-shell-1", "uc", "Exited (0) 2 minutes ago");
        }

        final ProjectDeletion.Result result =
                new ProjectDeletion(context).unfollow("uc", reason.equals("preview"), false);

        assertThat(result.outcome())
                .as("the preview or refusal is preserved")
                .isEqualTo(
                        reason.equals("preview")
                                ? ProjectDeletion.Outcome.PREVIEWED
                                : ProjectDeletion.Outcome.HOLDS_WORK);
        assertThat(projects.find("uc"))
                .as("a preview or refusal keeps the complete follow record")
                .isEqualTo(previous);
        assertThat(Files.readString(signers))
                .as("a preview or refusal keeps both projects' pinned keys")
                .isEqualTo("uc pinned-key\nother other-key\n");
        assertThat(dir.resolve("data/porelo/mirrors/uc.git"))
                .as("a preview or refusal keeps the mirror")
                .exists();
    }

    @Test
    void anUnappliedFollowLosesItsCloneEvenWhenInventoryCannotListIt(@TempDir Path dir)
            throws IOException {
        // A failed inventory read must not leave configuration behind after updates stop.
        final PoreloContext context = context(dir);
        final FollowedProjects projects =
                new FollowedProjects(context.paths().projects().followed());
        final PoreloContext observed =
                new PoreloContext(
                        command -> {
                            if (command.arguments().contains("ps")) {
                                try {
                                    projects.follow("uc", "git@example.org:uc.git");
                                } catch (IOException ex) {
                                    throw new java.io.UncheckedIOException(ex);
                                }
                            }
                            return runner.run(command);
                        },
                        context.paths(),
                        arguments -> 0);
        final Path clone = Files.createDirectories(context.paths().projects().followedClone("uc"));
        final Path lock = Files.writeString(context.paths().projects().followedCloneLock("uc"), "");
        final Path accessClone =
                Files.createDirectories(context.paths().projects().followedAccessClone("uc"));
        Files.writeString(accessClone.resolve("access.yml"), "{}\n");

        final ProjectDeletion.Result result =
                new ProjectDeletion(observed).unfollow("uc", false, false);

        assertThat(result.outcome())
                .as("the inventory did not know the unapplied project")
                .isEqualTo(ProjectDeletion.Outcome.NO_SUCH_PROJECT);
        assertThat(projects.find("uc")).as("following has stopped").isNull();
        assertThat(clone).as("an unapplied follow leaves no configuration clone").doesNotExist();
        assertThat(lock).as("an unapplied follow leaves no clone lock").doesNotExist();
        assertThat(accessClone).as("an unapplied follow leaves no access clone").doesNotExist();
        assertThat(result.removes())
                .as("the removed clone and lock are reported")
                .contains(
                        new ProjectDeletion.Removal("FOLLOWED_CLONE", clone.toString()),
                        new ProjectDeletion.Removal("FOLLOWED_CLONE", accessClone.toString()),
                        new ProjectDeletion.Removal("FOLLOWED_CLONE", lock.toString()));
    }

    @Test
    void aReconcileWaitsUntilALateRefusalRestoresTheRecord(@TempDir Path dir) throws Exception {
        // Seeing a temporary absence would make a watcher skip a still-followed project.
        final PoreloContext base = context(dir);
        project("uc");
        task("porelo-uc-shell-1", "uc", "Exited (0) 2 minutes ago");
        runner.answering("for-each-ref", "");
        final FollowedProjects projects = new FollowedProjects(base.paths().projects().followed());
        final FollowedProjects.Followed previous =
                projects.follow("uc", dir.resolve("missing").toString());
        runner.answering("container inspect", "exited");
        final var pending =
                new java.util.concurrent.atomic.AtomicReference<
                        java.util.concurrent.Future<Reconcile.Result>>();
        final var sawGap = new java.util.concurrent.atomic.AtomicBoolean();
        try (var executor = Executors.newSingleThreadExecutor()) {
            final PoreloContext observed =
                    new PoreloContext(
                            command -> {
                                if (command.arguments().contains("inspect")
                                        && !Files.exists(
                                                base.paths()
                                                        .projects()
                                                        .followed()
                                                        .resolve("uc.json"))
                                        && pending.get() == null) {
                                    final CountDownLatch started = new CountDownLatch(1);
                                    final var reconcile =
                                            executor.submit(
                                                    () -> {
                                                        started.countDown();
                                                        return new Reconcile(base).run(previous);
                                                    });
                                    pending.set(reconcile);
                                    try {
                                        assertThat(started.await(5, TimeUnit.SECONDS))
                                                .as("the watcher started")
                                                .isTrue();
                                        reconcile.get(300, TimeUnit.MILLISECONDS);
                                        sawGap.set(true);
                                    } catch (TimeoutException expected) {
                                        // The clone lock should keep the watcher out until
                                        // restoration finishes.
                                    } catch (Exception ex) {
                                        throw new java.io.UncheckedIOException(new IOException(ex));
                                    }
                                }
                                return runner.run(command);
                            },
                            base.paths(),
                            arguments -> 0);

            final ProjectDeletion.Result result =
                    new ProjectDeletion(observed).unfollow("uc", false, false);

            assertThat(result.outcome())
                    .as("the stopped task refuses removal")
                    .isEqualTo(ProjectDeletion.Outcome.HOLDS_WORK);
            assertThat(projects.find("uc"))
                    .as("the complete follow record is restored")
                    .isEqualTo(previous);
            assertThat(pending.get()).as("reconciliation was attempted in the gap").isNotNull();
            final Reconcile.Result reconciled = pending.get().get(5, TimeUnit.SECONDS);
            assertThat(sawGap.get())
                    .as("reconciliation cannot observe the removal and restore gap")
                    .isFalse();
            assertThat(reconciled.outcome())
                    .as("the watcher reads the restored record")
                    .isNotEqualTo(Reconcile.Outcome.NOT_FOLLOWED);
        }
    }

    @Test
    void aFailedRestoreKeepsTheTaskRefusalsReason(@TempDir Path dir) throws IOException {
        // Losing the first refusal hides why work could not safely be removed.
        final PoreloContext base = context(dir);
        project("uc");
        task("porelo-uc-shell-1", "uc", "Exited (0) 2 minutes ago");
        runner.answering("for-each-ref", "");
        final FollowedProjects projects = new FollowedProjects(base.paths().projects().followed());
        projects.follow("uc", "git@example.org:uc.git");
        runner.answering("container inspect", "exited");
        final Path staged = base.paths().projects().followed().resolve(".uc.json.tmp");
        final PoreloContext observed =
                new PoreloContext(
                        command -> {
                            if (command.arguments().contains("inspect")
                                    && !Files.exists(
                                            base.paths()
                                                    .projects()
                                                    .followed()
                                                    .resolve("uc.json"))) {
                                try {
                                    Files.createDirectories(staged);
                                } catch (IOException ex) {
                                    throw new java.io.UncheckedIOException(ex);
                                }
                            }
                            return runner.run(command);
                        },
                        base.paths(),
                        arguments -> 0);

        final ProjectDeletion.Result result =
                new ProjectDeletion(observed).unfollow("uc", false, false);

        assertThat(result.outcome())
                .as("restoration failed")
                .isEqualTo(ProjectDeletion.Outcome.FAILED);
        assertThat(result.detail())
                .as("the first refusal survives a failed restore")
                .contains(
                        "task porelo-uc-shell-1 was not removed",
                        "nothing knows",
                        staged.toString());
    }

    @Test
    void aFailedKeyCleanupKeepsTheDeletionFailure(@TempDir Path dir) throws IOException {
        // Without the first failure the operator cannot tell what still needs cleanup.
        final PoreloContext base = context(dir);
        project("uc");
        new FollowedProjects(base.paths().projects().followed())
                .follow("uc", "git@example.org:uc.git");
        Files.createDirectories(base.paths().projects().configurationSigners().getParent());
        final Path signers =
                Files.writeString(
                        base.paths().projects().configurationSigners(), "uc pinned-key\n");
        final var permissions = Files.getPosixFilePermissions(signers);
        final PoreloContext observed =
                new PoreloContext(
                        command -> {
                            if (command.arguments().contains("rmi")) {
                                try {
                                    Files.setPosixFilePermissions(
                                            signers, PosixFilePermissions.fromString("r--------"));
                                } catch (IOException ex) {
                                    throw new java.io.UncheckedIOException(ex);
                                }
                                throw new IllegalStateException("primary removal failure");
                            }
                            return runner.run(command);
                        },
                        base.paths(),
                        arguments -> 0);
        final ProjectDeletion.Result result;
        try {
            result = new ProjectDeletion(observed).unfollow("uc", false, false);
        } finally {
            Files.setPosixFilePermissions(signers, permissions);
        }
        assertThat(result.outcome())
                .as("deletion and key cleanup failed")
                .isEqualTo(ProjectDeletion.Outcome.FAILED);
        assertThat(result.detail())
                .as("the deletion failure survives a failed key cleanup")
                .contains("primary removal failure", signers.toString());
    }

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private Path root;

    private PoreloContext context(Path dir) {
        root = dir;
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    /** A project with a mirror, a build directory, a registry entry and an upstream record. */
    private Path project(String name) throws IOException {
        final Path mirror = root.resolve("data/porelo/mirrors").resolve(name + ".git");
        Files.createDirectories(mirror.resolve("objects"));
        Files.createDirectories(root.resolve("data/porelo/build").resolve(name));
        Files.createDirectories(root.resolve("data/porelo/projects"));
        final Path file = root.resolve(name + "-project.yml");
        Files.writeString(file, "project:\n  name: \"" + name + "\"\n", StandardCharsets.UTF_8);
        Files.writeString(
                root.resolve("data/porelo/projects").resolve(name),
                file + "\n",
                StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("data/porelo/upstream"));
        Files.writeString(
                root.resolve("data/porelo/upstream").resolve(name),
                "MEASURED\t0\t\t\n",
                StandardCharsets.UTF_8);
        return file;
    }

    private void task(String container, String project, String state) throws IOException {
        runner.answering("ps", container + "\t" + state + "\n");
        final Path directory = root.resolve("run/porelo").resolve(container);
        Files.createDirectories(directory);
        new Sidecar(
                        Sidecar.VERSION,
                        project,
                        "guarded",
                        directory.resolve("r.nft").toString(),
                        directory.resolve("dns.conf").toString(),
                        "/usr/bin/porelo",
                        directory.toString())
                .writeTo(directory.resolve("sidecar.json"));
    }

    @Test
    void refusesWhileWorkIsWaitingAtTheGateAndRemovesNothing(@TempDir Path dir) throws IOException {

        // The refusal that justifies the whole confirmation. A branch that reached the mirror and
        // was never approved exists NOWHERE else - not in the operator's repository, not in a
        // workspace. Losing it to a tidy-up is the worst thing this could do.
        final PoreloContext context = context(dir);
        project("uc");
        runner.answering("for-each-ref", "refs/porelo/incoming/shell\n");

        final ProjectDeletion.Result result =
                new ProjectDeletion(context).delete("uc", false, false);

        assertThat(result.outcome()).isEqualTo(ProjectDeletion.Outcome.HOLDS_WORK);
        // Named by repository. A bare ref name was enough while a project was one repository; with
        // several, the person deciding whether to destroy it has to know which one is at risk, and
        // "shell" would be the answer for all of them.
        assertThat(result.unreviewed()).containsExactly("uc/shell");
        assertThat(Files.isDirectory(dir.resolve("data/porelo/mirrors/uc.git")))
                .as("the mirror is untouched")
                .isTrue();
        assertThat(runner.lines()).noneMatch(line -> line.startsWith("podman rmi"));
    }

    @Test
    void refusesWhileATaskIsUp(@TempDir Path dir) throws IOException {

        final PoreloContext context = context(dir);
        project("uc");
        task("porelo-uc-shell-1", "uc", "Up 4 minutes");
        runner.answering("for-each-ref", "");

        final ProjectDeletion.Result result =
                new ProjectDeletion(context).delete("uc", false, false);

        assertThat(result.outcome()).isEqualTo(ProjectDeletion.Outcome.TASKS_RUNNING);
        assertThat(result.running()).containsExactly("porelo-uc-shell-1");
        assertThat(Files.isDirectory(dir.resolve("data/porelo/mirrors/uc.git")))
                .as("nothing was removed")
                .isTrue();
    }

    @Test
    void stopsWhenATaskWillNotGiveUpWhatItHolds(@TempDir Path dir) throws IOException {

        // The answer from removing each task was discarded. A task refusing because it holds work
        // the gate never saw was ignored, and the mirror - the only place unreviewed pushes exist
        // - was deleted a moment later. Nothing reported it, because nothing looked.
        final PoreloContext context = context(dir);
        project("uc");
        task("porelo-uc-shell-1", "uc", "Exited (0) 2 minutes ago");
        runner.answering("for-each-ref", "");
        // Stopped, and nothing recorded what it holds: 'task remove' answers NOTHING_KNOWS, which
        // is a refusal and not a removal.

        final ProjectDeletion.Result result =
                new ProjectDeletion(context).delete("uc", false, false);

        assertThat(result.outcome()).isEqualTo(ProjectDeletion.Outcome.HOLDS_WORK);
        assertThat(result.detail()).contains("porelo-uc-shell-1").contains("was not removed");
        assertThat(Files.isDirectory(dir.resolve("data/porelo/mirrors/uc.git")))
                .as("the mirror is where unreviewed pushes live, and nothing else has them")
                .isTrue();
    }

    @Test
    void forceProceedsAndStillNamesWhatItDestroyed(@TempDir Path dir) throws IOException {

        // Saying it twice means the same here as for 'task stop --force'. What force must NOT do is
        // hide what it took: the unreviewed refs stay in the answer, because they are what it
        // destroyed.
        final PoreloContext context = context(dir);
        project("uc");
        runner.answering("for-each-ref", "refs/porelo/incoming/shell\n");

        final ProjectDeletion.Result result =
                new ProjectDeletion(context).delete("uc", false, true);

        assertThat(result.outcome()).isEqualTo(ProjectDeletion.Outcome.DELETED);
        assertThat(result.unreviewed())
                .as("what force destroyed, still named")
                .containsExactly("uc/shell");
        assertThat(Files.exists(dir.resolve("data/porelo/mirrors/uc.git"))).isFalse();
    }

    @Test
    void neverTouchesTheProjectFile(@TempDir Path dir) throws IOException {

        // It is the operator's, in their own directory. A deletion that took it would be claiming
        // ownership of something Porelo merely reads - and 'keeps' says so, so a confirmation can
        // too without the interface having to know which things are ours.
        final PoreloContext context = context(dir);
        final Path file = project("uc");
        runner.answering("for-each-ref", "");

        final ProjectDeletion.Result result =
                new ProjectDeletion(context).delete("uc", false, false);

        assertThat(result.outcome()).isEqualTo(ProjectDeletion.Outcome.DELETED);
        assertThat(Files.isRegularFile(file)).as("the operator's file survives").isTrue();
        assertThat(result.keeps()).anyMatch(kept -> kept.equals(file.toString()));
    }

    @Test
    void aPreviewRemovesNothingAndStillNamesEverything(@TempDir Path dir) throws IOException {

        // A confirmation is built from this. If the preview changed anything, the dialog would be
        // reporting a past event as a future one.
        final PoreloContext context = context(dir);
        project("uc");
        runner.answering("for-each-ref", "");

        final ProjectDeletion.Result result =
                new ProjectDeletion(context).delete("uc", true, false);

        assertThat(result.outcome()).isEqualTo(ProjectDeletion.Outcome.PREVIEWED);
        assertThat(result.removes())
                .extracting(ProjectDeletion.Removal::kind)
                .contains("MIRROR", "BUILD", "REGISTRY", "UPSTREAM_RECORD");
        assertThat(Files.isDirectory(dir.resolve("data/porelo/mirrors/uc.git"))).isTrue();
        assertThat(Files.isRegularFile(dir.resolve("data/porelo/projects/uc"))).isTrue();
        assertThat(runner.lines()).noneMatch(line -> line.startsWith("podman rmi"));
    }

    @Test
    void aProjectNothingKnowsIsAnOutcomeRatherThanAnError(@TempDir Path dir) {

        assertThat(
                        new ProjectDeletion(context(dir))
                                .delete("never-existed", false, false)
                                .outcome())
                .isEqualTo(ProjectDeletion.Outcome.NO_SUCH_PROJECT);
    }

    @Test
    void removesEveryThingPoreloBuiltForIt(@TempDir Path dir) throws IOException {

        final PoreloContext context = context(dir);
        project("uc");
        runner.answering("for-each-ref", "");

        new ProjectDeletion(context).delete("uc", false, false);

        assertThat(Files.exists(dir.resolve("data/porelo/mirrors/uc.git"))).isFalse();
        assertThat(Files.exists(dir.resolve("data/porelo/build/uc"))).isFalse();
        assertThat(Files.exists(dir.resolve("data/porelo/projects/uc"))).isFalse();
        assertThat(Files.exists(dir.resolve("data/porelo/upstream/uc"))).isFalse();
        assertThat(runner.lines())
                .anyMatch(line -> line.contains("rmi") && line.contains("porelo/uc"));
    }

    @Test
    void removesItsBuildLockAndItsRepositoriesUpstreamRecords(@TempDir Path dir)
            throws IOException {

        // Leftovers keep project data after unfollow; a broad prefix would delete another
        // project's.
        final PoreloContext context = context(dir);
        project("uc");
        project("uc-two");
        runner.answering("for-each-ref", "");
        final Path lock = Files.writeString(dir.resolve("data/porelo/build/uc.build.lock"), "");
        final Path backend = Files.writeString(dir.resolve("data/porelo/upstream/uc.backend"), "");
        final Path frontend =
                Files.writeString(dir.resolve("data/porelo/upstream/uc.frontend"), "");
        final Path otherLock =
                Files.writeString(dir.resolve("data/porelo/build/uc-two.build.lock"), "");
        final Path otherRecord =
                Files.writeString(dir.resolve("data/porelo/upstream/uc-two.backend"), "");

        final ProjectDeletion.Result result =
                new ProjectDeletion(context).delete("uc", false, false);

        assertThat(result.outcome())
                .as("the project can be removed")
                .isEqualTo(ProjectDeletion.Outcome.DELETED);
        assertThat(lock).as("the project's unused build lock goes").doesNotExist();
        assertThat(backend).as("the backend's upstream record goes").doesNotExist();
        assertThat(frontend).as("the frontend's upstream record goes").doesNotExist();
        assertThat(otherLock).as("another project's build lock stays").exists();
        assertThat(otherRecord).as("another project's upstream record stays").exists();
    }

    @Test
    void previewsItsBuildLockAndItsRepositoriesUpstreamRecordsWithoutRemovingThem(@TempDir Path dir)
            throws IOException {

        // A preview must describe the deletion before consent without destroying any files.
        final PoreloContext context = context(dir);
        project("uc");
        runner.answering("for-each-ref", "");
        final Path lock = Files.writeString(dir.resolve("data/porelo/build/uc.build.lock"), "");
        final Path record = Files.writeString(dir.resolve("data/porelo/upstream/uc.backend"), "");
        final Path otherLock =
                Files.writeString(dir.resolve("data/porelo/build/uc-two.build.lock"), "");
        final Path otherRecord =
                Files.writeString(dir.resolve("data/porelo/upstream/uc-two.backend"), "");

        final ProjectDeletion.Result result =
                new ProjectDeletion(context).delete("uc", true, false);

        assertThat(result.removes())
                .as("the preview names the project's lock and repository record only")
                .contains(
                        new ProjectDeletion.Removal("BUILD", lock.toString()),
                        new ProjectDeletion.Removal("UPSTREAM_RECORD", record.toString()))
                .doesNotContain(
                        new ProjectDeletion.Removal("BUILD", otherLock.toString()),
                        new ProjectDeletion.Removal("UPSTREAM_RECORD", otherRecord.toString()));
        assertThat(lock).as("preview leaves the build lock untouched").exists();
        assertThat(record).as("preview leaves the upstream record untouched").exists();
        assertThat(otherLock).as("another project's build lock stays").exists();
        assertThat(otherRecord).as("another project's upstream record stays").exists();
    }

    @Test
    void deletingANeverFollowedProjectCreatesNoFollowState(@TempDir Path dir) throws IOException {

        // Removing a local project must not create state that only following needs.
        final PoreloContext context = context(dir);
        project("uc");
        runner.answering("for-each-ref", "");

        final ProjectDeletion.Result result =
                new ProjectDeletion(context).delete("uc", false, false);

        assertThat(result.outcome())
                .as("the local project can be removed")
                .isEqualTo(ProjectDeletion.Outcome.DELETED);
        assertThat(context.paths().projects().followedCloneLock("uc"))
                .as("a never-followed project leaves no clone lock")
                .doesNotExist();
        assertThat(context.paths().projects().followed())
                .as("deleting a never-followed project creates no follow directory")
                .doesNotExist();
    }

    @Test
    void removesTheCloneLockEvenWhenDeletingTheCloneFails(@TempDir Path dir) throws IOException {

        // A failed deletion must not strand its synchronization file.
        final PoreloContext context = context(dir);
        project("uc");
        runner.answering("for-each-ref", "");
        final Path clone = Files.createDirectories(context.paths().projects().followedClone("uc"));
        Files.writeString(clone.resolve("project.yml"), "project data");
        final Path lock = Files.writeString(context.paths().projects().followedCloneLock("uc"), "");
        final var permissions = Files.getPosixFilePermissions(clone);
        final ProjectDeletion.Result result;
        try {
            Files.setPosixFilePermissions(clone, PosixFilePermissions.fromString("r-x------"));
            result = new ProjectDeletion(context).delete("uc", false, false);
        } finally {
            Files.setPosixFilePermissions(clone, permissions);
        }

        assertThat(result.outcome())
                .as("the unwritable clone prevents deletion")
                .isEqualTo(ProjectDeletion.Outcome.FAILED);
        assertThat(lock).as("a failed clone deletion still removes the clone lock").doesNotExist();
        assertThat(clone).as("the clone that could not be deleted remains").exists();
    }

    @Test
    void waitsForTheCloneLockBeforeDeletingTheClone(@TempDir Path dir) throws Exception {

        // Deleting during reconciliation can destroy a clone while git is writing it.
        final PoreloContext context = context(dir);
        project("uc");
        runner.answering("for-each-ref", "");
        final Path clone = Files.createDirectories(context.paths().projects().followedClone("uc"));
        final Path lock = context.paths().projects().followedCloneLock("uc");
        try (var executor = Executors.newSingleThreadExecutor()) {
            final var deletion =
                    FileLocks.holding(
                            lock,
                            () -> {
                                final CountDownLatch started = new CountDownLatch(1);
                                final var pending =
                                        executor.submit(
                                                () -> {
                                                    started.countDown();
                                                    return new ProjectDeletion(context)
                                                            .delete("uc", false, false);
                                                });
                                try {
                                    assertThat(started.await(5, TimeUnit.SECONDS))
                                            .as("the deletion thread has started")
                                            .isTrue();
                                    assertThatThrownBy(
                                                    () -> pending.get(300, TimeUnit.MILLISECONDS))
                                            .as(
                                                    "deletion waits while another thread holds the clone lock")
                                            .isInstanceOf(TimeoutException.class);
                                    assertThat(clone)
                                            .as(
                                                    "the clone survives while reconciliation holds the lock")
                                            .exists();
                                } catch (InterruptedException ex) {
                                    Thread.currentThread().interrupt();
                                    throw new IOException(
                                            "interrupted while checking deletion", ex);
                                }
                                return pending;
                            });

            assertThat(deletion.get(5, TimeUnit.SECONDS).outcome())
                    .as("deletion completes after the lock is released")
                    .isEqualTo(ProjectDeletion.Outcome.DELETED);
            assertThat(clone).as("the clone is removed after the lock is released").doesNotExist();
            assertThat(lock).as("the clone lock is removed after deletion").doesNotExist();
        }
    }

    @Test
    void removesTheFollowedClonesLockWithTheClone(@TempDir Path dir) throws IOException {

        // Leftover locks keep state for a project the operator asked to remove.
        final PoreloContext context = context(dir);
        project("uc");
        new FollowedProjects(context.paths().projects().followed())
                .follow("uc", "git@example.org:uc.git");
        final Path clone = Files.createDirectories(context.paths().projects().followedClone("uc"));
        final Path lock = Files.writeString(clone.resolveSibling("uc.git.lock"), "");
        final Path accessClone =
                Files.createDirectories(context.paths().projects().followedAccessClone("uc"));
        Files.writeString(accessClone.resolve("access.yml"), "{}\n");
        runner.answering("for-each-ref", "");

        final ProjectDeletion.Result result =
                new ProjectDeletion(context).delete("uc", false, false);

        assertThat(result.outcome())
                .as("the followed project can be removed")
                .isEqualTo(ProjectDeletion.Outcome.DELETED);
        assertThat(lock).as("the followed clone's lock goes with the clone").doesNotExist();
        assertThat(clone).as("the followed clone goes too").doesNotExist();
        assertThat(accessClone).as("the followed access clone goes too").doesNotExist();
        assertThat(result.removes())
                .as("the deletion names the lock it removed")
                .contains(
                        new ProjectDeletion.Removal("FOLLOWED_CLONE", lock.toString()),
                        new ProjectDeletion.Removal("FOLLOWED_CLONE", accessClone.toString()));
    }

    @Test
    void previewsTheFollowedClonesLockWithoutRemovingIt(@TempDir Path dir) throws IOException {

        // Consent must cover the lock as well as the clone without deleting either yet.
        final PoreloContext context = context(dir);
        project("uc");
        new FollowedProjects(context.paths().projects().followed())
                .follow("uc", "git@example.org:uc.git");
        final Path clone = Files.createDirectories(context.paths().projects().followedClone("uc"));
        final Path lock = Files.writeString(clone.resolveSibling("uc.git.lock"), "");
        final Path accessClone =
                Files.createDirectories(context.paths().projects().followedAccessClone("uc"));
        runner.answering("for-each-ref", "");

        final ProjectDeletion.Result result =
                new ProjectDeletion(context).delete("uc", true, false);

        assertThat(result.removes())
                .as("the preview names the followed clone and its lock")
                .contains(
                        new ProjectDeletion.Removal("FOLLOWED_CLONE", clone.toString()),
                        new ProjectDeletion.Removal("FOLLOWED_CLONE", accessClone.toString()),
                        new ProjectDeletion.Removal("FOLLOWED_CLONE", lock.toString()));
        assertThat(lock).as("preview leaves the clone's lock untouched").exists();
        assertThat(clone).as("preview leaves the clone untouched").exists();
        assertThat(accessClone).as("preview leaves the access clone untouched").exists();
    }

    @Test
    void leavesHeldBuildLocksAlone(@TempDir Path dir) throws IOException {

        // Removing a held lock lets a second build lock a new inode and bypass the first build.
        final PoreloContext context = context(dir);
        project("uc");
        runner.answering("for-each-ref", "");
        final Path lock = Files.writeString(dir.resolve("data/porelo/build/uc.build.lock"), "");
        final Path repositoryLock =
                Files.writeString(dir.resolve("data/porelo/build/uc_backend.build.lock"), "");

        FileLocks.holding(
                lock,
                () ->
                        FileLocks.holding(
                                repositoryLock,
                                () -> {
                                    final ProjectDeletion.Result result =
                                            new ProjectDeletion(context).delete("uc", false, false);

                                    assertThat(result.outcome())
                                            .as("held locks do not prevent removing the project")
                                            .isEqualTo(ProjectDeletion.Outcome.DELETED);
                                    assertThat(lock)
                                            .as("the active project build keeps its lock inode")
                                            .exists();
                                    assertThat(repositoryLock)
                                            .as("the active repository build keeps its lock inode")
                                            .exists();
                                    return null;
                                }));
    }

    @Test
    void removesItsRepositoriesImagesAndNoOtherProjects(@TempDir Path dir) throws IOException {

        // Left behind, a repository's image outlived its project; taken by prefix alone, the
        // project 'uc' would have taken 'uc-two''s images with it.
        final PoreloContext context = context(dir);
        project("uc");
        runner.answering("for-each-ref", "");
        runner.answering(
                "images",
                "localhost/porelo/uc\nlocalhost/porelo/uc_backend\nlocalhost/porelo/uc-two_backend\n");

        final Path own = Files.createDirectories(dir.resolve("data/porelo/build/uc_backend"));
        final Path other = Files.createDirectories(dir.resolve("data/porelo/build/uc-two_backend"));

        new ProjectDeletion(context).delete("uc", false, false);

        assertThat(own).as("its repository's build directory").doesNotExist();
        assertThat(other).as("another project's").exists();
        assertThat(runner.lines())
                .as("the repository's image")
                .anyMatch(line -> line.contains("rmi") && line.endsWith("porelo/uc_backend"));
        assertThat(runner.lines())
                .as("another project's")
                .noneMatch(line -> line.contains("rmi") && line.contains("porelo/uc-two"));
    }
}
