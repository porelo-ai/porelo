package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for the verb that ends a project.
 *
 * <p><strong>What is measured here is a project of this machine alone</strong>: a mirror, a build
 * directory and a registry entry, and nothing of the organization.
 */
class ProjectRemoveCommandTest {

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

    /**
     * A project of this machine: a mirror, a build directory and a registry entry. Nothing of the
     * organization names it.
     */
    private void localProject(final String name) throws IOException {
        Files.createDirectories(
                root.resolve("data/porelo/mirrors").resolve(name + ".git").resolve("objects"));
        Files.createDirectories(root.resolve("data/porelo/build").resolve(name));
        Files.createDirectories(root.resolve("data/porelo/projects"));
        final Path file = root.resolve(name + "-project.yml");
        Files.writeString(file, "project:\n  name: \"" + name + "\"\n", StandardCharsets.UTF_8);
        Files.writeString(
                root.resolve("data/porelo/projects").resolve(name),
                file + "\n",
                StandardCharsets.UTF_8);
    }

    private record Run(int code, String out, String err) {}

    private Run run(final PoreloContext context, final String... arguments) {
        final ProjectRemoveCommand command = new ProjectRemoveCommand();
        command.setContext(context);
        final StringWriter out = new StringWriter();
        final StringWriter err = new StringWriter();
        final int code =
                new CommandLine(command)
                        .setOut(new PrintWriter(out, true))
                        .setErr(new PrintWriter(err, true))
                        .execute(arguments);
        return new Run(code, out.toString(), err.toString());
    }

    @Test
    void previews_a_project_of_this_machine_rather_than_refusing_to_look(@TempDir final Path dir)
            throws IOException {

        final PoreloContext context = context(dir);
        localProject("objects4j");

        final Run previewed = run(context, "objects4j", "--dry-run");

        assertThat(previewed.code()).isZero();
        assertThat(previewed.out())
                .contains("would remove objects4j")
                // What goes, by name. A preview that says only "yes, something" is not a preview.
                .contains("mirrors")
                .contains("objects4j-project.yml");
        // And what stays, as plainly as what goes: the project file is the operator's.
        assertThat(previewed.out()).contains("kept:");
        // A preview removes nothing. The whole worth of the confirmation rests on this.
        assertThat(dir.resolve("data/porelo/mirrors/objects4j.git")).exists();
    }

    @Test
    void clears_a_project_of_this_machine(@TempDir final Path dir) throws IOException {

        final PoreloContext context = context(dir);
        localProject("utils4j");

        final Run cleared = run(context, "utils4j");

        assertThat(cleared.code()).isZero();
        assertThat(dir.resolve("data/porelo/mirrors/utils4j.git")).doesNotExist();
        assertThat(dir.resolve("data/porelo/projects/utils4j")).doesNotExist();
        assertThat(dir.resolve("data/porelo/build/utils4j")).doesNotExist();
        // The operator's own file is not Porelo's to remove, whichever verb was typed.
        assertThat(dir.resolve("utils4j-project.yml")).exists();
    }

    @Test
    void a_name_this_machine_does_not_list_is_still_its_own_answer(@TempDir final Path dir) {

        // The distinction is the value of the error: "it is here and is yours to remove" is a
        // thing to act on, "there is no such project" is a typo.
        final Run missing = run(context(dir), "nowhere", "--dry-run");

        assertThat(missing.code()).isEqualTo(70);
        assertThat(missing.err()).contains("no project called 'nowhere' here");
    }

    @Test
    void refuses_a_project_of_this_machine_that_holds_unreviewed_work(@TempDir final Path dir)
            throws IOException {

        // The point of the whole requirement. These are the pushes that exist in the mirror and
        // nowhere else, and they do not become less valuable because the project predates
        // following. Without this, clearing such a project from an interface would be blind.
        final PoreloContext context = context(dir);
        localProject("objects4j");
        final Path mirror = dir.resolve("data/porelo/mirrors/objects4j.git");
        runner.answering("for-each-ref", "refs/porelo/incoming/shell\n");

        final Run refused = run(context, "objects4j");

        assertThat(refused.code()).isEqualTo(70);
        assertThat(refused.err()).contains("holds work nobody has reviewed");
        assertThat(mirror).exists();
    }

    @Test
    void takes_the_verified_clone_with_the_rest(@TempDir final Path dir) throws IOException {

        // It did not, over the socket: the terminal removed the clone by hand AFTER the deletion
        // and the daemon never did, so a project removed through an interface left its verified
        // clone on the machine - while the contract said the clone goes. Found through the
        // interface. Removed through ProjectDeletion now, which is the one thing both ways call,
        // and it is named in the preview for the same reason.
        final PoreloContext context = context(dir);
        localProject("uc");
        final Path clone = context.paths().projects().organizationClone("uc");
        Files.createDirectories(clone);
        Files.writeString(
                clone.resolve("project.yml"), "project:\n  name: \"uc\"\n", StandardCharsets.UTF_8);

        final Run previewed = run(context, "uc", "--dry-run");
        assertThat(previewed.out()).contains(clone.toString());
        // Readable, not a record's toString: this is the screen somebody decides on.
        assertThat(previewed.out()).contains("placed clone").doesNotContain("Removal[");
        // And what is inside the clone is NOT listed as kept, because the clone goes. Saying both
        // about one path in one preview is worse than saying neither.
        assertThat(
                        previewed
                                .out()
                                .lines()
                                .filter(line -> line.contains("kept:"))
                                .anyMatch(line -> line.contains(clone.toString())))
                .isFalse();
        assertThat(clone).exists();

        run(context, "uc");

        assertThat(clone).doesNotExist();
    }

    @Test
    void removing_a_written_file_takes_a_link_to_its_checkout_and_leaves_the_checkout(
            @TempDir final Path dir) throws IOException {
        final PoreloContext context = context(dir);
        final Path checkout = Files.createDirectories(dir.resolve("mine"));
        Files.writeString(checkout.resolve("project.yml"), "project:\n  name: \"mine\"\n");
        final Path place = context.paths().projects().defaultProjectFile("mine").getParent();
        Files.createDirectories(place.getParent());
        Files.createSymbolicLink(place, checkout);
        runner.answering("for-each-ref", "");

        final Run removed = run(context, "mine", "--force");

        assertThat(removed.code()).as(removed.err()).isZero();
        assertThat(place).doesNotExist();
        assertThat(java.nio.file.Files.isSymbolicLink(place)).isFalse();
        assertThat(checkout.resolve("project.yml")).exists();
    }

    @Test
    void removing_a_written_file_takes_it_and_its_empty_place(@TempDir final Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        final Path file = context.paths().projects().defaultProjectFile("mine");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "project:\n  name: \"mine\"\n");
        runner.answering("for-each-ref", "");

        final Run removed = run(context, "mine");

        assertThat(removed.code()).as(removed.err()).isZero();
        assertThat(file.getParent()).doesNotExist();
        assertThat(ProjectSource.resolve(context, "mine").file()).isNull();
    }

    @Test
    void an_organizations_project_is_not_this_commands_to_remove(@TempDir final Path dir)
            throws IOException {
        final PoreloContext context = context(dir);
        PlacedFixture.place(context, "theirs", "project:\n  name: \"theirs\"\n");

        final Run removed = run(context, "theirs", "--force", "--json");

        assertThat(removed.code()).isEqualTo(65);
        assertThat(removed.out()).contains("not-allowed");
        assertThat(context.paths().projects().organizationClone("theirs")).exists();
    }

    @Test
    void default_is_not_removed_either(@TempDir final Path dir) {
        assertThat(run(context(dir), DefaultProject.NAME, "--force").code()).isEqualTo(65);
    }

    @Test
    void forced_with_nothing_there_is_a_success_for_a_cleanup_trap(@TempDir final Path dir) {
        assertThat(run(context(dir), "nowhere", "--force").code()).isZero();
    }
}
