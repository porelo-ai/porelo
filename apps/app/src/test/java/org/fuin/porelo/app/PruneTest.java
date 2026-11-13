package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link Prune}, against real directories: what nothing owns is found, shown, and removed
 * only when asked, and what holds work stays unless that is asked too.
 */
class PruneTest {

    @Test
    void applyPruneRetainsAVaultOnlyScopeWhoseOwnerCannotBeProved() throws Exception {
        // Absence from the runtime is not permission to erase the only remaining task secrets.
        final PoreloContext context = context().asking(shown -> "fixture-passphrase".toCharArray());
        final String entry = TaskSecrets.PREFIX + "porelo-legacy-only/signing-key";
        context.vault()
                .write(
                        java.util.Map.of(entry, org.fuin.porelo.vault.VaultEntry.of("sentinel")),
                        "fixture-passphrase".toCharArray());
        try {
            context.openerAsking(false, new java.io.PrintWriter(java.io.Writer.nullWriter()));
            new Prune(context).run(true, false);
            assertThat(context.vault().read("fixture-passphrase".toCharArray()).keySet())
                    .as("prune retains unidentified legacy scopes")
                    .contains(entry);
        } finally {
            context.forgetTyped();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void pruningCannotGiveAnotherOwnerAHistoricalName(boolean known) throws Exception {
        final PoreloContext context = context();
        final String name = "porelo-web-app-x";
        if (known) LegacyTaskFixture.record(context, name, "web", "c0ffeec0ffee");
        else Files.createDirectories(context.paths().tasks().taskRecord(name));
        final Path state = context.paths().tasks().taskRecord(name);
        try (var paths = Files.walk(state)) {
            for (Path path : paths.toList())
                Files.setLastModifiedTime(
                        path, java.nio.file.attribute.FileTime.from(java.time.Instant.EPOCH));
        }
        new Prune(context).run(true, true);
        assertThat(state).doesNotExist();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new TaskReservations(context).reserve("web-app", "x", name, "other"))
                .isInstanceOfSatisfying(
                        TaskReservations.ContainerNameTaken.class,
                        taken -> {
                            assertThat(taken.ownerProject).isEqualTo(known ? "web" : null);
                            assertThat(taken.ownerTask).isEqualTo(known ? "app-x" : null);
                        });
    }

    @TempDir Path dir;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private PoreloContext context() {
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
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void anUnfinishedAttemptKeepsItsStateAndMailPastTheGrace(boolean live) throws Exception {
        final PoreloContext context = context();
        try (var claim =
                new TaskReservations(context).reserve("web", "x", "porelo-web-x", "first")) {
            final Path state = context.paths().tasks().taskRecord("porelo-web-x");
            final Path mailbox = context.paths().messaging().mailbox("porelo-web-x");
            for (Path directory : java.util.List.of(state, mailbox)) {
                Files.createDirectories(directory);
                Files.writeString(directory.resolve("sentinel"), "kept");
                try (var paths = Files.walk(directory)) {
                    for (Path path : paths.toList())
                        Files.setLastModifiedTime(
                                path,
                                java.nio.file.attribute.FileTime.from(java.time.Instant.EPOCH));
                }
            }
            if (!live) claim.close();
            new Prune(context).run(true, true);
            assertThat(state.resolve("sentinel")).exists();
            assertThat(mailbox.resolve("sentinel")).exists();
        }
    }

    /**
     * A project as September left it: a registry entry into a deleted temporary directory, and its
     * mirror.
     */
    private Path leftOver(PoreloContext context, String project) throws IOException {
        final Path registry =
                Files.createDirectories(context.paths().projects().projectRegistry())
                        .resolve(project);
        Files.writeString(registry, dir.resolve("tmp.gone/project.yml") + "\n");
        final Path mirror =
                Files.createDirectories(
                        context.paths().xdg().data().resolve("mirrors").resolve(project + ".git"));
        Files.createDirectories(context.paths().tasks().buildContext(project));
        return mirror;
    }

    @Test
    void theLockOfALiveProjectsUpstreamRecordIsNoLeftover() throws IOException {
        // Unlinked while a writer holds it, a second writer locks a new file and both write.
        final PoreloContext context = context();
        final Path mirror =
                Files.createDirectories(
                        context.paths().xdg().data().resolve("mirrors").resolve("kept.git"));
        new UpstreamRecords(context.paths().projects().upstreamRecords())
                .putCentral(
                        "kept",
                        new org.fuin.porelo.gate.UpstreamDistance.Central(
                                "up-to-date", 0, null, null),
                        mirror);
        final Path lock = context.paths().projects().upstreamRecords().resolve(".kept.lock");
        assertThat(lock).as("the fixture writes the lock the record is merged under").exists();

        final Prune.Result result = new Prune(context).run(true, false);

        assertThat(result.removes())
                .extracting(Prune.Item::name)
                .as("a live project's record lock is not offered for removal")
                .doesNotContain(lock.toString());
        assertThat(lock).as("and stays").exists();
    }

    @Test
    void aProjectWhoseFileIsGoneIsShownAndRemovedOnlyWithYes() throws IOException {
        final PoreloContext context = context();
        final Path mirror = leftOver(context, "gatedemo");

        final Prune.Result shown = new Prune(context).run(false, false);

        assertThat(shown.removes())
                .extracting(Prune.Item::kind, Prune.Item::name)
                .contains(org.assertj.core.groups.Tuple.tuple("PROJECT", "gatedemo"))
                .as("its build context is the project's, not listed a second time on its own")
                .doesNotContain(
                        org.assertj.core.groups.Tuple.tuple(
                                "BUILD",
                                context.paths().tasks().buildContext("gatedemo").toString()));
        assertThat(mirror).as("nothing removed without --yes").exists();

        final Prune.Result done = new Prune(context).run(true, false);

        assertThat(done.applied()).isTrue();
        assertThat(mirror).doesNotExist();
        assertThat(context.paths().projects().projectRegistry().resolve("gatedemo")).doesNotExist();
        assertThat(context.paths().tasks().buildContext("gatedemo")).doesNotExist();
        assertThat(new Prune(context).run(false, false).found())
                .as("nothing left to find")
                .isFalse();
    }

    @Test
    void aMirrorWithAPushNobodyReviewedIsKeptUntilWorkIsIncluded() throws IOException {
        final PoreloContext context = context();
        final Path mirror = leftOver(context, "gatetask");
        runner.answering("for-each-ref", "refs/porelo/incoming/shell-1\n");

        final Prune.Result kept = new Prune(context).run(true, false);

        assertThat(kept.keeps())
                .singleElement()
                .satisfies(
                        item -> {
                            assertThat(item.name()).isEqualTo("gatetask");
                            assertThat(item.holds())
                                    .contains("pushes nobody reviewed")
                                    .contains("shell-1");
                        });
        assertThat(mirror).exists();

        new Prune(context).run(true, true);

        assertThat(mirror).as("--including-work").doesNotExist();
    }

    @Test
    void theBuildLockOfAProjectThatExistsIsNeverLeftOver() throws IOException {

        // Listed as "build …/default.build.lock - its build context" while a task of 'default' ran:
        // the lock beside the context was read as a project called "default.build.lock".
        final PoreloContext context = context();
        final Path file = Files.createDirectories(dir.resolve("readable")).resolve("project.yml");
        Files.writeString(
                file,
                "project:\n  name: \"kept\"\n  security_class: \"guarded\"\n"
                        + "image:\n  from: \"ubuntu:24.04\"\n");
        Files.createDirectories(context.paths().projects().projectRegistry());
        Files.writeString(
                context.paths().projects().projectRegistry().resolve("kept"), file + "\n");
        final Path build = Files.createDirectories(context.paths().tasks().buildContext("kept"));
        final Path lock = Files.writeString(build.resolveSibling("kept.build.lock"), "");

        final Prune.Result found = new Prune(context).run(true, false);

        assertThat(
                        java.util.stream.Stream.concat(
                                        found.removes().stream(), found.keeps().stream())
                                .map(Prune.Item::name))
                .noneMatch(name -> name.contains("kept"));
        assertThat(lock).exists();
        assertThat(build).exists();
    }

    @Test
    void theBuildLockOfAProjectThatIsGoneGoesWithItsContextUnlessSomebodyHoldsIt()
            throws Exception {
        final PoreloContext context = context();
        leftOver(context, "gone");
        final Path build = context.paths().tasks().buildContext("gone");
        final Path lock = Files.writeString(build.resolveSibling("gone.build.lock"), "");

        // Held, as an image build holds it: neither it nor its context is touched while it is.
        try (java.nio.channels.FileChannel channel =
                java.nio.channels.FileChannel.open(lock, java.nio.file.StandardOpenOption.WRITE)) {
            final java.nio.channels.FileLock held = channel.lock();
            try {
                assertThat(new Prune(context).run(false, false).removes())
                        .extracting(Prune.Item::name)
                        .noneMatch(name -> name.endsWith(".build.lock"));
            } finally {
                held.release();
            }
        }

        new Prune(context).run(true, false);

        assertThat(lock).as("not held any more, it goes with its project").doesNotExist();
    }

    @Test
    void aProjectWithARunningTaskOrAReadableFileIsNeverOrphaned() throws IOException {
        final PoreloContext context = context();
        leftOver(context, "busy");
        runner.answering("ps --all", "porelo-busy-shell\tUp 2 minutes\t\t\tbusy\tguarded\t\t\n");
        final Path file = Files.createDirectories(dir.resolve("readable")).resolve("project.yml");
        Files.writeString(
                file,
                "project:\n  name: \"kept\"\n  security_class: \"guarded\"\n"
                        + "image:\n  from: \"ubuntu:24.04\"\n");
        Files.writeString(
                context.paths().projects().projectRegistry().resolve("kept"), file + "\n");

        final Prune.Result found = new Prune(context).run(false, false);

        assertThat(
                        java.util.stream.Stream.concat(
                                        found.removes().stream(), found.keeps().stream())
                                .map(Prune.Item::name))
                .doesNotContain("busy", "kept");
    }

    @Test
    void whatIsKeptForATaskWhoseContainerIsGoneGoesUnlessItsMailboxHoldsAMessage()
            throws IOException {
        final PoreloContext context = context();
        Files.createDirectories(context.paths().tasks().taskRecord("porelo-old-shell-1"));
        new Mailbox(context.paths().messaging().mailbox("porelo-old-shell-1")).create();
        final Mailbox waiting =
                new Mailbox(context.paths().messaging().mailbox("porelo-old-shell-2"));
        waiting.create();
        Files.writeString(waiting.hold().resolve("m-1.json"), "{\"messageId\":\"m-1\"}");
        age(context.paths().xdg().state());

        final Prune.Result result = new Prune(context).run(true, false);

        assertThat(result.removes())
                .as(result.toString())
                .extracting(Prune.Item::name)
                .contains("porelo-old-shell-1");
        assertThat(context.paths().tasks().taskRecord("porelo-old-shell-1")).doesNotExist();
        assertThat(context.paths().messaging().mailbox("porelo-old-shell-1")).doesNotExist();
        assertThat(result.keeps())
                .singleElement()
                .satisfies(
                        item -> {
                            assertThat(item.name()).isEqualTo("porelo-old-shell-2");
                            assertThat(item.holds()).contains("held or refused");
                        });
        assertThat(waiting.hold().resolve("m-1.json")).exists();
    }

    @Test
    void aMailboxItsRemovalCouldNotDeleteIsALeftoverOnceItsNameIsRetired() throws IOException {
        // A removal retires the name though the mailbox stayed: what stayed is offered here.
        final PoreloContext context = context();
        final String container = "porelo-web-x";
        final TaskReservations reservations = new TaskReservations(context);
        try (var claim = reservations.reserve("web", "x", container, "first")) {}
        final Mailbox left = new Mailbox(context.paths().messaging().mailbox(container));
        left.create();
        reservations.retire(container);
        age(context.paths().xdg().state());

        final Prune.Result result = new Prune(context).run(true, false);

        assertThat(result.removes())
                .as(result.toString())
                .extracting(Prune.Item::name)
                .contains(container);
        assertThat(left.root()).as("the leftover went").doesNotExist();
    }

    @Test
    void aTaskStartingNowIsNotLeftOverThoughItsContainerIsNotThereYet() throws IOException {
        // Its mailbox and state are made before its container: a prune at that moment must not take
        // them.
        final PoreloContext context = context();
        new Mailbox(context.paths().messaging().mailbox("porelo-p-starting")).create();

        assertThat(new Prune(context).run(true, false).found()).isFalse();
        assertThat(context.paths().messaging().mailbox("porelo-p-starting")).exists();
    }

    @Test
    void theLoginImageAndBuildContextAreNeverAProjectsLeftovers() throws IOException {
        final PoreloContext context = context();
        Files.createDirectories(context.paths().tasks().buildContext(Prune.LOGIN));
        Files.createDirectories(context.paths().tasks().buildContext("gone"));
        runner.answering("images", "localhost/porelo/porelo-login\nlocalhost/porelo/gone\n");

        final Prune.Result found = new Prune(context).run(false, false);

        assertThat(found.removes())
                .extracting(Prune.Item::name)
                .contains("porelo/gone", context.paths().tasks().buildContext("gone").toString())
                .doesNotContain(
                        "porelo/porelo-login",
                        context.paths().tasks().buildContext(Prune.LOGIN).toString());
    }

    @Test
    void aRepositorysImageBelongsToItsProject() throws IOException {

        // Taken for a project of its own, a placed project's repository image was a leftover, and
        // its next task built it again from nothing.
        final PoreloContext context = context();
        PlacedFixture.record(context, "kept");
        runner.answering(
                "images", "localhost/porelo/kept_backend\nlocalhost/porelo/gone_backend\n");
        final Path kept =
                Files.createDirectories(context.paths().tasks().buildContext("kept_backend"));
        final Path gone =
                Files.createDirectories(context.paths().tasks().buildContext("gone_backend"));

        final Prune.Result found = new Prune(context).run(false, false);

        assertThat(found.removes())
                .extracting(Prune.Item::name)
                .as("a vanished project's repository image goes, a placed one's stays")
                .contains("porelo/gone_backend", gone.toString())
                .doesNotContain("porelo/kept_backend", kept.toString());
    }

    @Test
    void aCloneLeftBesideNoRecordIsALeftoverAndAPlacedOnesIsNot() throws IOException {
        final PoreloContext context = context();
        Files.createDirectories(context.paths().projects().organizationClone("refused"));
        PlacedFixture.record(context, "kept");
        Files.createDirectories(context.paths().projects().organizationClone("kept"));

        final Prune.Result found = new Prune(context).run(true, false);

        assertThat(context.paths().projects().organizationClone("refused")).doesNotExist();
        assertThat(context.paths().projects().organizationClone("kept")).exists();
        assertThat(found.removes())
                .extracting(Prune.Item::name)
                .contains(context.paths().projects().organizationClone("refused").toString());
    }

    @Test
    void aVaultEntryIsOrphanedOnlyWhenTheTaskOrProjectItIsKeptForIsGone() {
        final Set<String> containers = Set.of("porelo-p-live");
        final Set<String> projects = Set.of("p");

        assertThat(Prune.orphanedEntry("task/porelo-p-gone/route/forge", containers, projects))
                .isTrue();
        assertThat(Prune.orphanedEntry("task/porelo-p-live/gate-token", containers, projects))
                .isFalse();
        assertThat(Prune.orphanedEntry("transport/matrix/task/porelo-p-gone", containers, projects))
                .isTrue();
        assertThat(Prune.orphanedEntry("transport/matrix/project/gone", containers, projects))
                .isTrue();
        assertThat(Prune.orphanedEntry("transport/matrix/project/p", containers, projects))
                .isFalse();
        assertThat(Prune.orphanedEntry("transport/matrix/account", containers, projects))
                .as("the account's, owned by no task or project")
                .isFalse();
        assertThat(Prune.orphanedEntry("grant/forge-app", containers, projects)).isFalse();
    }

    @Test
    void aTaskThatStartsWhileAPruneRunsKeepsTheTokensItKeptInTheVault() throws IOException {
        // Its container appears after the prune listed the containers, and its start keeps its
        // tokens; taken for a gone task's, they were removed, and it could not come back after a
        // reboot.
        final char[] passphrase = "correct horse".toCharArray();
        final java.util.concurrent.atomic.AtomicInteger listed =
                new java.util.concurrent.atomic.AtomicInteger();
        final FakeCommandRunner starting =
                new FakeCommandRunner() {
                    @Override
                    public org.fuin.porelo.core.process.CommandResult run(
                            final org.fuin.porelo.core.process.Command command) {
                        if (command.describe().endsWith("{{.Names}}")) {
                            return new org.fuin.porelo.core.process.CommandResult(
                                    command,
                                    0,
                                    listed.getAndIncrement() == 0 ? "" : "porelo-p-new\n",
                                    "");
                        }
                        return super.run(command);
                    }
                };
        final PoreloContext context =
                new PoreloContext(
                                starting,
                                new PoreloPaths(
                                        XdgPaths.of(
                                                name ->
                                                        switch (name) {
                                                            case "XDG_CONFIG_HOME" ->
                                                                    dir.resolve("config")
                                                                            .toString();
                                                            case "XDG_DATA_HOME" ->
                                                                    dir.resolve("data").toString();
                                                            case "XDG_STATE_HOME" ->
                                                                    dir.resolve("state").toString();
                                                            default -> null;
                                                        },
                                                dir),
                                        dir.resolve("bin")),
                                arguments -> 0)
                        .asking(prompt -> passphrase.clone());
        Files.createDirectories(context.vault().path().getParent());
        TaskIdentityFixture.record(context.paths(), "porelo-p-gone", "p", "gone", "a".repeat(64));
        new TaskReservations(context).mutate("porelo-p-gone", () -> Boolean.TRUE);
        new TaskReservations(context).retire("porelo-p-gone");
        context.vault()
                .write(
                        java.util.Map.of(
                                "task/porelo-p-new/gate-token",
                                org.fuin.porelo.vault.VaultEntry.of("kept"),
                                "task/porelo-p-gone/gate-token",
                                org.fuin.porelo.vault.VaultEntry.of("left")),
                        passphrase);
        try {
            assertThat(
                            context.openerAsking(
                                    false, new java.io.PrintWriter(new java.io.StringWriter())))
                    .isPresent();

            new Prune(context).run(true, false);

            assertThat(context.vault().read(context.opener().orElseThrow()).keySet())
                    .as("the starting task's tokens stay, the gone one's go")
                    .containsExactly("task/porelo-p-new/gate-token");
        } finally {
            PoreloContext.forgetAsked();
        }
    }

    /** Makes everything under a directory look untouched for longer than {@link Prune#SETTLED}. */
    private static void age(Path root) throws IOException {
        final java.nio.file.attribute.FileTime then =
                java.nio.file.attribute.FileTime.from(
                        java.time.Instant.now().minus(Prune.SETTLED).minusSeconds(60));
        try (java.util.stream.Stream<Path> tree = Files.walk(root)) {
            for (final Path path : tree.toList()) {
                Files.setLastModifiedTime(path, then);
            }
        }
    }

    private String prune(
            final PoreloContext context, final Offer.Asker asker, final String... arguments) {
        final PruneCommand command = new PruneCommand();
        command.setContext(context);
        command.asker = asker;
        final java.io.StringWriter out = new java.io.StringWriter();
        final picocli.CommandLine cmd = new picocli.CommandLine(command);
        cmd.setOut(new java.io.PrintWriter(out));
        cmd.setErr(new java.io.PrintWriter(out));
        cmd.execute(arguments);
        return out.toString();
    }

    @Test
    void atATerminalWhatIsListedIsOfferedToBeRemovedWithNoAsTheDefault() throws IOException {
        final PoreloContext context = context();
        final Path mirror = leftOver(context, "gatedemo");

        // It said "'porelo prune --yes' removes what would be", a second command with no decision
        // in it the first had not.
        assertThat(prune(context, question -> "")).contains("Nothing was removed");
        assertThat(mirror).as("an answer of nothing is no").exists();

        assertThat(prune(context, question -> "y")).contains("Removed:");
        assertThat(mirror).doesNotExist();
    }

    @Test
    void withoutATerminalOrWithNoInputNothingIsAskedAndTheLineIsTodays() throws IOException {
        final PoreloContext context = context();
        final Path mirror = leftOver(context, "gatedemo");

        assertThat(prune(context, Offer.Asker.NOBODY))
                .contains("'porelo prune --yes' removes what would be");
        assertThat(prune(context, question -> "y", "--no-input"))
                .contains("'porelo prune --yes' removes what would be");
        assertThat(mirror).exists();
    }
}
