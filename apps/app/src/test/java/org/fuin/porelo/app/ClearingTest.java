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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link Clearing}: everything of a project, or of the account, in one step, and an
 * answer that names each thing it removed, could not remove, or leaves for a person's credentials.
 */
class ClearingTest {

    @Test
    void clearingRemovesTheRecordBeforeDeletingProjectData(@TempDir final Path dir)
            throws IOException {
        // Clearing must close the same stale-reconcile race as an explicit unfollow.
        final PoreloContext context = context(dir);
        project("uc");
        new FollowedProjects(context.paths().projects().followed())
                .follow("uc", "git@example.org:uc.git");
        final Path clone = Files.createDirectories(context.paths().projects().followedClone("uc"));
        final var removals = new java.util.concurrent.atomic.AtomicInteger();
        final PoreloContext observed =
                new PoreloContext(
                        command -> {
                            if (command.arguments().contains("rmi")) {
                                removals.incrementAndGet();
                                assertThat(context.paths().projects().followed().resolve("uc.json"))
                                        .as(
                                                "clearing stops following before project deletion starts")
                                        .doesNotExist();
                                assertThat(clone)
                                        .as("clearing removes the record before the clone")
                                        .exists();
                            }
                            return runner.run(command);
                        },
                        context.paths(),
                        arguments -> 0);

        final Clearing.Result result = new Clearing(observed).project("uc", false, false);

        assertThat(result.refused()).as("clearing is allowed").isFalse();
        assertThat(removals.get()).as("the deletion was observed").isPositive();
        assertThat(clone).as("clearing removes the clone after the record").doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"repository", "file"})
    void clearingKeepsAppliedConversationSettingsBeforeUnfollowing(
            final String place, @TempDir final Path dir) throws IOException {
        final PoreloContext context = context(dir);
        accessProject(context, place, "a1", "APPLIED");
        final Path adapters = Files.createDirectories(dir.resolve("data/porelo/transports"));
        final Path adapter = adapters.resolve(TransportDirectory.PREFIX + "room");
        Files.writeString(adapter, "#!/bin/sh\n");
        assertThat(adapter.toFile().setExecutable(true)).as("the transport is executable").isTrue();
        runner.answering("describe", "{\"scheme\":\"room\",\"lifecycle\":[\"clear\"]}");
        runner.answering(" clear", "{\"removed\":[],\"kept\":[]}");

        final Clearing.Result result = new Clearing(context).project("uc", false, false);

        assertThat(result.items())
                .filteredOn(item -> item.kind().equals("conversation"))
                .as("the conversation is cleared after its follow and access file are gone")
                .singleElement()
                .satisfies(
                        item ->
                                assertThat(item.status())
                                        .as(item.why())
                                        .isEqualTo(Clearing.Status.REMOVED));
        assertThat(runner.only("clear --project uc").input())
                .as("the transport receives the applied settings captured before unfollowing")
                .contains("https://access.example.org");
    }

    @Test
    void clearingKeepsTheAccessRefusalFromBeforeUnfollowing(@TempDir final Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        accessProject(context, "repository", "", "UNKNOWN_KEY");

        final Clearing.Result result = new Clearing(context).project("uc", false, false);

        assertThat(result.items())
                .filteredOn(item -> item.kind().equals("conversation"))
                .as("the original refusal survives deletion of the follow record")
                .singleElement()
                .satisfies(
                        item -> {
                            assertThat(item.status())
                                    .as("the unverified conversation stays")
                                    .isEqualTo(Clearing.Status.NOT_REMOVED);
                            assertThat(item.why())
                                    .as("the reason is captured while still followed")
                                    .contains("is not applied (unknown_key)");
                        });
    }

    private void accessProject(
            final PoreloContext context,
            final String place,
            final String commit,
            final String outcome)
            throws IOException {
        project("uc");
        final String access =
                place.equals("repository")
                        ? "access: {repository: 'git@example.org:uc/access.git'}\n"
                        : "access: {file: access.yml}\n";
        Files.writeString(
                root.resolve("uc-project.yml"),
                access + "mail:\n  peers:\n    people: {address: 'room:', trust: external}\n",
                java.nio.file.StandardOpenOption.APPEND);
        final Path clone = Files.createDirectories(context.paths().projects().followedClone("uc"));
        Files.copy(root.resolve("uc-project.yml"), clone.resolve("project.yml"));
        final Path accessClone =
                Files.createDirectories(context.paths().projects().followedAccessClone("uc"));
        final String settings = "transports: {room: {homeserver: 'https://access.example.org'}}\n";
        Files.writeString(clone.resolve("access.yml"), settings);
        Files.writeString(accessClone.resolve("access.yml"), settings);
        runner.answering("show " + commit + ":access.yml", settings);
        new FollowedProjects(context.paths().projects().followed())
                .write(
                        new FollowedProjects.Followed(
                                "uc",
                                "git@example.org:uc.git",
                                "c1",
                                "",
                                "APPLIED",
                                "",
                                "",
                                "",
                                false,
                                commit,
                                outcome));
    }

    @Test
    void aFailedRemovalStillReportsThatFollowingStopped(@TempDir final Path dir)
            throws IOException {
        // An omitted removal makes a partial clear look as though updates still run.
        final PoreloContext base = context(dir);
        project("uc");
        final FollowedProjects projects = new FollowedProjects(base.paths().projects().followed());
        final String url = "git@example.org:uc.git";
        projects.follow("uc", url);
        final PoreloContext observed =
                new PoreloContext(
                        command -> {
                            if (command.arguments().contains("rmi")) {
                                throw new IllegalStateException("removal failed");
                            }
                            return runner.run(command);
                        },
                        base.paths(),
                        arguments -> 0);

        final Clearing.Result result = new Clearing(observed).project("uc", false, false);

        assertThat(projects.find("uc")).as("following stopped despite deletion failing").isNull();
        assertThat(result.items())
                .as("a partial clear reports the follow it removed")
                .contains(new Clearing.Item("follow", url, Clearing.Status.REMOVED, ""));
    }

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private Path root;

    private PoreloContext context(final Path dir) {
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
                        + "image:\n  base_image: \"ubuntu:24.04\"\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                root.resolve("data/porelo/projects").resolve(name),
                file + "\n",
                StandardCharsets.UTF_8);
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
