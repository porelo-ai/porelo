package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link Clearing}: everything of a project, or of the account, in one step, and an
 * answer that names each thing it removed, could not remove, or leaves for a person's credentials.
 */
class ClearingTest {

    private final FakeCommandRunner runner = new TransportTestRunner();

    private Path root;

    private PoreloContext context(final Path dir) {
        return context(dir, runner);
    }

    private PoreloContext context(final Path dir, final FakeCommandRunner runner) {
        root = dir;
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    /** A project with a mirror, a build directory and a registry entry. */
    private void project(final String name) throws IOException {
        Files.createDirectories(
                root.resolve("data/porelo/mirrors").resolve(name + ".git").resolve("objects"));
        Files.createDirectories(root.resolve("data/porelo/build").resolve(name));
        Files.createDirectories(root.resolve("data/porelo/projects"));
        final Path file = root.resolve(name + "-project.yml");
        Files.writeString(
                file,
                "project:\n  name: \""
                        + name
                        + "\"\n  security_class: \"guarded\"\n"
                        + "image:\n  from: \"ubuntu:24.04\"\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                root.resolve("data/porelo/projects").resolve(name),
                file + "\n",
                StandardCharsets.UTF_8);
    }

    @Test
    void aClearWithoutForceNamesARunningTaskAndLeavesItsRecordsWhole(@TempDir final Path dir)
            throws Exception {
        final StoppingCommandRunner stopping = new StoppingCommandRunner();
        final PoreloContext context = context(dir, stopping).recognizingTasks();
        final String container = runningTask(context, stopping);

        final Clearing.Result result = new Clearing(context).project("uc", false, false);

        assertThat(result.refused()).isTrue();
        assertThat(result.items()).anyMatch(item -> item.why().contains("still up"));
        assertThat(
                        org.fuin.porelo.wire.Sidecar.recordedId(
                                context.paths().tasks().taskRecord(container)))
                .as("its identity record stays")
                .isNotNull();
        assertThat(context.paths().tasks().containerState(container)).exists();
        assertThat(TaskHistory.find(context.paths(), "uc", "shell")).isNotNull();
        assertThat(stopping.lines()).noneMatch(line -> line.startsWith("podman rm"));
    }

    /** A running task "shell" of project "uc", with every record a start writes. */
    private String runningTask(final PoreloContext context, final StoppingCommandRunner stopping)
            throws Exception {
        project("uc");
        final String container = "porelo-uc-shell";
        final String id = "a".repeat(64);
        try (var claim = new TaskReservations(context).reserve("uc", "shell", container, "first")) {
            TaskIdentityFixture.running(context.paths(), container, "uc", "shell", id);
            claim.created(id);
        }
        stopping.answering("ps --all --format {{.Names}}", container + "\n");
        stopping.answering(
                "ps", container + "\tUp 4 minutes\t\t\tuc\tguarded\tr\t\t" + id + "\tshell\n");
        stopping.answering("for-each-ref", "");
        return container;
    }

    @Test
    void aForcedClearTakesARunningTasksRecordsTogether(@TempDir final Path dir) throws Exception {
        // On the test VM a forced clear left a running task's files without its identity record,
        // and every later gate of the project refused.
        final StoppingCommandRunner stopping = new StoppingCommandRunner();
        final PoreloContext context = context(dir, stopping).recognizingTasks();
        final String container = runningTask(context, stopping);

        final Clearing.Result result = new Clearing(context).project("uc", false, true);

        assertThat(result.items())
                .as("the running task went through the task removal")
                .anyMatch(
                        item ->
                                item.kind().equals("TASK")
                                        && item.what().equals(container)
                                        && item.status() == Clearing.Status.REMOVED);
        final boolean taken =
                Files.notExists(context.paths().tasks().containerState(container))
                        && Files.notExists(context.paths().tasks().taskRecord(container));
        final boolean whole =
                org.fuin.porelo.wire.Sidecar.recordedId(
                                context.paths().tasks().taskRecord(container))
                        != null;
        assertThat(taken || whole)
                .as("the task's records are all taken or all kept: " + result.items())
                .isTrue();
        assertThat(TaskHistory.find(context.paths(), "uc", "next"))
                .as("no name is left that a later task's gate cannot verify")
                .isNull();
        assertThat(TaskHistory.find(context.paths(), "uc", "shell"))
                .as("the cleared task's ownership is still known")
                .isNotNull();
    }

    @Test
    void aDryRunNamesWhatWouldGoAndChangesNothing(@TempDir final Path dir) throws IOException {
        final PoreloContext context = context(dir);
        project("uc");

        final Clearing.Result result = new Clearing(context).project("uc", true, false);

        assertThat(result.items())
                .isNotEmpty()
                .allMatch(
                        item ->
                                item.status() == Clearing.Status.WOULD_REMOVE
                                        || item.status() == Clearing.Status.FOR_A_PERSON);
        assertThat(result.items()).anyMatch(item -> item.what().contains("mirrors"));
        assertThat(dir.resolve("data/porelo/mirrors/uc.git")).exists();
    }

    @Test
    void aProjectIsClearedAndASecondClearFindsNothing(@TempDir final Path dir) throws IOException {
        final PoreloContext context = context(dir);
        project("uc");

        final Clearing.Result result = new Clearing(context).project("uc", false, false);

        assertThat(result.refused()).isFalse();
        assertThat(result.items())
                .anyMatch(
                        item ->
                                item.status() == Clearing.Status.REMOVED
                                        && item.what().contains("mirrors"));
        assertThat(dir.resolve("data/porelo/mirrors/uc.git")).doesNotExist();
        assertThat(dir.resolve("data/porelo/projects/uc")).doesNotExist();
        // The person's own file is not Porelo's to remove.
        assertThat(dir.resolve("uc-project.yml")).exists();

        assertThat(new Clearing(context).project("uc", false, false).items())
                .as("nothing left to clear")
                .isEmpty();
    }

    @Test
    void aProjectWithNoHomeserverHasNoConversationToClear(@TempDir final Path dir)
            throws IOException {
        // Its tasks started with no conversation: none was made, so none is "not removed".
        final PoreloContext context = context(dir);
        project("uc");
        Files.writeString(
                dir.resolve("uc-project.yml"),
                "mail:\n  transports:\n    matrix: {}\n",
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);
        OrganizationProjects.of(context)
                .write(
                        new OrganizationProjects.Placed(
                                "uc",
                                "c0",
                                "https://forge.example/uc.git",
                                null,
                                java.util.List.of(),
                                null,
                                null,
                                null));

        // A placed project is read from the organization's clone.
        final Path clone = context.paths().projects().organizationClone("uc");
        Files.createDirectories(clone);
        Files.copy(dir.resolve("uc-project.yml"), clone.resolve("project.yml"));
        assertThat(GateSupport.byName(context, "uc").mail().conversations())
                .as("the project reads, and has a conversation to look at")
                .containsExactly("matrix");

        final Clearing.Result result = new Clearing(context).project("uc", false, false);

        assertThat(result.items())
                .as("no conversation is named, as none was made: %s", result.items())
                .noneMatch(item -> item.what().contains("conversation"));
        assertThat(result.refused()).isFalse();
    }

    @Test
    void theAccountIsEveryProjectOnIt(@TempDir final Path dir) throws IOException {
        final PoreloContext context = context(dir);
        project("one");
        project("two");

        final Clearing.Result result = new Clearing(context).account(false, false);

        assertThat(result.refused()).isFalse();
        assertThat(dir.resolve("data/porelo/mirrors/one.git")).doesNotExist();
        assertThat(dir.resolve("data/porelo/mirrors/two.git")).doesNotExist();
        assertThat(new Clearing(context).account(false, false).items()).isEmpty();
    }

    @Test
    void clearingTheAccountLeavesItsEventLogWithItsChainIntact(@TempDir final Path dir)
            throws IOException {

        // The account's history, not a project's: what was approved and allowed here is asked
        // about after a clearing, not before.
        final PoreloContext context = context(dir);
        project("one");
        final java.util.List<EventLog.Anchored> anchored = new java.util.ArrayList<>();
        final EventLog.Anchor journal =
                new EventLog.Anchor() {
                    @Override
                    public boolean send(final String hash, final String kind) {
                        return anchored.add(new EventLog.Anchored(hash, java.time.Instant.now()));
                    }

                    @Override
                    public java.util.List<EventLog.Anchored> anchored() {
                        return java.util.List.copyOf(anchored);
                    }
                };
        final EventLog log = new EventLog(context.paths().eventLog(), journal);
        log.append(EventLog.APPROVED, "one", "porelo-one-fix", "onto main");

        new Clearing(context).account(false, false);

        assertThat(context.paths().eventLog()).exists();
        assertThat(log.verify().holds()).isTrue();
    }

    @Test
    void clearingTheAccountLeavesNothingOfWhatPoreloKeptForAnyConversation(@TempDir final Path dir)
            throws IOException {

        // A project cleared before, or by hand, left its conversation's files behind, and 'porelo
        // clear' said there was nothing to clear.
        final PoreloContext context = context(dir);
        final Path transport =
                Files.createDirectories(
                        context.paths().xdg().state().resolve("transport/room/members"));
        Files.writeString(transport.resolveSibling("gone.json"), "{}");
        Files.writeString(transport.resolve("gone.json"), "{}");

        final Clearing.Result listed = new Clearing(context).account(true, false);
        assertThat(listed.items())
                .anyMatch(
                        item ->
                                item.status() == Clearing.Status.WOULD_REMOVE
                                        && item.what().contains("room"));
        assertThat(transport).exists();

        new Clearing(context).account(false, false);

        assertThat(context.paths().xdg().state().resolve("transport/room")).doesNotExist();
        assertThat(new Clearing(context).account(false, false).items()).isEmpty();
    }

    @Test
    void anAccountWithWorkWaitingInOneProjectClearsNothingAndItsListingStillNamesEverything(
            @TempDir final Path dir) throws Exception {

        // The account was cleared project by project and stopped at the first that held work: the
        // ones before it were gone, and the listing a person confirmed had stopped there too. With
        // git itself: the gate asks it.
        context(dir);
        final PoreloContext context =
                new PoreloContext(
                        NoTasks.over(new org.fuin.porelo.core.process.ProcessCommandRunner()),
                        new PoreloPaths(
                                XdgPaths.of(
                                        name ->
                                                switch (name) {
                                                    case "XDG_DATA_HOME" ->
                                                            dir.resolve("data").toString();
                                                    case "XDG_STATE_HOME" ->
                                                            dir.resolve("state").toString();
                                                    case "XDG_RUNTIME_DIR" ->
                                                            dir.resolve("run").toString();
                                                    default -> null;
                                                },
                                        dir),
                                dir.resolve("bin")),
                        arguments -> 0);
        project("one");
        project("two");
        final Path mirror = dir.resolve("data/porelo/mirrors/two.git");
        new org.fuin.porelo.core.process.ProcessCommandRunner()
                .runOrFail(
                        org.fuin.porelo.core.process.Command.of(
                                "sh",
                                "-c",
                                "rm -rf "
                                        + mirror
                                        + " "
                                        + dir.resolve("data/porelo/mirrors/one.git")
                                        + " && git init -q --bare "
                                        + dir.resolve("data/porelo/mirrors/one.git")
                                        + " && git init -q --bare "
                                        + mirror
                                        + " && t=$(git --git-dir "
                                        + mirror
                                        + " hash-object -w -t blob --stdin </dev/null)"
                                        + " && tree=$(git --git-dir "
                                        + mirror
                                        + " mktree </dev/null)"
                                        + " && c=$(echo work | git --git-dir "
                                        + mirror
                                        + " -c user.name=a -c user.email=a@b commit-tree $tree)"
                                        + " && git --git-dir "
                                        + mirror
                                        + " update-ref refs/porelo/incoming/x $c"));

        final Clearing.Result listed = new Clearing(context).account(true, false);
        assertThat(listed.items()).anyMatch(item -> item.what().contains("one.git"));
        assertThat(listed.items()).anyMatch(item -> item.what().contains("two.git"));

        final Clearing.Result cleared = new Clearing(context).account(false, false);

        assertThat(cleared.refused()).isTrue();
        assertThat(dir.resolve("data/porelo/mirrors/one.git"))
                .as("nothing is cleared while one project refuses")
                .exists();
    }

    @Test
    void atATerminalWhatTheListingNamesIsOfferedToBeClearedWithNoAsTheDefault(
            @TempDir final Path dir) throws IOException {
        final PoreloContext context = context(dir);
        project("one");
        final java.util.function.Function<Offer.Asker, String> clear =
                asker -> {
                    final ClearCommand command = new ClearCommand();
                    command.setContext(context);
                    command.asker = asker;
                    final java.io.StringWriter out = new java.io.StringWriter();
                    final picocli.CommandLine cmd = new picocli.CommandLine(command);
                    cmd.setOut(new java.io.PrintWriter(out));
                    cmd.setErr(new java.io.PrintWriter(out));
                    cmd.execute();
                    return out.toString();
                };

        assertThat(clear.apply(question -> "")).contains("nothing was removed");
        assertThat(dir.resolve("data/porelo/mirrors/one.git")).exists();
        assertThat(clear.apply(Offer.Asker.NOBODY)).contains("run it again with --yes");

        assertThat(clear.apply(question -> "y")).contains("removed");
        assertThat(dir.resolve("data/porelo/mirrors/one.git")).doesNotExist();
    }
}
