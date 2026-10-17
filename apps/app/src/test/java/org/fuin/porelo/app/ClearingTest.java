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
                        new org.fuin.porelo.core.process.ProcessCommandRunner(),
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
