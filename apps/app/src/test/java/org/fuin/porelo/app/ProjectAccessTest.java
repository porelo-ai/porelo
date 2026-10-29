package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where a transport's settings come from for a project that names an access file. Harm guarded: a
 * transport handed empty settings sets up the account's own homeserver, and one handed settings
 * nobody verified talks to whatever server they name.
 */
class ProjectAccessTest {

    private static final String PROJECT =
            """
            project:
              name: "p"
              security_class: "guarded"
            image:
              base_image: "ubuntu:24.04"
            mail:
              peers:
                people: {address: "room:", trust: "external"}
            """;

    private static final String ACCESS =
            "transports:\n  room: {homeserver: \"https://access.example\"}\n";

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

    /**
     * A project of this machine's own, registered from a file in a directory beside an access file.
     */
    private Project registered(final PoreloContext context, final String access, final String file)
            throws IOException {
        final Path home = Files.createDirectories(dir.resolve("p"));
        Files.writeString(home.resolve("project.yml"), PROJECT + access, StandardCharsets.UTF_8);
        if (file != null) {
            Files.writeString(home.resolve("access.yml"), file, StandardCharsets.UTF_8);
        }
        Files.createDirectories(context.paths().projects().projectRegistry());
        Files.writeString(
                context.paths().projects().projectRegistry().resolve("p"),
                home.resolve("project.yml") + "\n",
                StandardCharsets.UTF_8);
        return GateSupport.byName(context, "p");
    }

    /** A followed project whose clone holds the files, with what the record says is in force. */
    private Project followed(
            final PoreloContext context,
            final String access,
            final String accessCommit,
            final String accessOutcome)
            throws IOException {
        final Path clone = Files.createDirectories(context.paths().projects().followedClone("p"));
        Files.writeString(clone.resolve("project.yml"), PROJECT + access, StandardCharsets.UTF_8);
        Files.writeString(clone.resolve("access.yml"), ACCESS, StandardCharsets.UTF_8);
        final Path accessClone =
                Files.createDirectories(context.paths().projects().followedAccessClone("p"));
        Files.writeString(accessClone.resolve("access.yml"), ACCESS, StandardCharsets.UTF_8);
        runner.answering("show " + accessCommit + ":access.yml", ACCESS);
        new FollowedProjects(context.paths().projects().followed())
                .write(
                        new FollowedProjects.Followed(
                                "p",
                                "git@example.org:p/project.git",
                                "c1",
                                "",
                                "APPLIED",
                                "",
                                "",
                                "",
                                false,
                                accessCommit,
                                accessOutcome));
        return GateSupport.byName(context, "p");
    }

    @Test
    void a_project_without_an_access_file_keeps_its_own_settings() throws Exception {
        final PoreloContext context = context();
        final Path home = Files.createDirectories(dir.resolve("p"));
        Files.writeString(
                home.resolve("project.yml"),
                PROJECT + "  transports:\n    room: {homeserver: \"https://project.example\"}\n",
                StandardCharsets.UTF_8);
        Files.createDirectories(context.paths().projects().projectRegistry());
        Files.writeString(
                context.paths().projects().projectRegistry().resolve("p"),
                home.resolve("project.yml") + "\n",
                StandardCharsets.UTF_8);

        assertThat(ProjectAccess.settings(context, GateSupport.byName(context, "p"), "room"))
                .as("the project file's settings, as before")
                .containsEntry("homeserver", "https://project.example");
    }

    @Test
    void an_applied_access_file_in_a_repository_gives_the_settings() throws Exception {
        final PoreloContext context = context();
        final Project project =
                followed(
                        context,
                        "access: {repository: \"git@example.org:p/access.git\"}\n",
                        "a1",
                        "APPLIED");

        assertThat(ProjectAccess.settings(context, project, "room"))
                .as("the settings of the access file in force")
                .containsEntry("homeserver", "https://access.example");
    }

    @Test
    void an_applied_access_file_beside_the_project_file_gives_the_settings() throws Exception {
        final PoreloContext context = context();
        final Project project =
                followed(context, "access: {file: \"access.yml\"}\n", "c1", "APPLIED");

        assertThat(ProjectAccess.settings(context, project, "room"))
                .as("the settings of the file beside project.yml at the verified commit")
                .containsEntry("homeserver", "https://access.example");
    }

    @Test
    void the_access_file_is_read_at_the_commit_in_force_never_from_the_working_tree()
            throws Exception {
        final PoreloContext context = context();
        final Project project =
                followed(
                        context,
                        "access: {repository: \"git@example.org:p/access.git\"}\n",
                        "a1",
                        "UNUSABLE");
        Files.writeString(
                context.paths().projects().followedAccessClone("p").resolve("access.yml"),
                "transports:\n  room: {homeserver: \"https://unsigned.example\"}\n",
                StandardCharsets.UTF_8);

        assertThat(ProjectAccess.settings(context, project, "room"))
                .as("a later commit that did not read, or an edit nobody signed, is not in force")
                .containsEntry("homeserver", "https://access.example");
        assertThat(runner.invocations())
                .as("read from the access clone at the commit in force")
                .anyMatch(
                        each ->
                                each.arguments()
                                        .equals(
                                                java.util.List.of(
                                                        "git",
                                                        "-C",
                                                        context.paths()
                                                                .projects()
                                                                .followedAccessClone("p")
                                                                .toString(),
                                                        "show",
                                                        "a1:access.yml")));
    }

    @Test
    void an_access_file_naming_empty_settings_for_the_transport_withholds_them() throws Exception {
        final PoreloContext context = context();
        final Project project =
                registered(
                        context, "access: {file: \"access.yml\"}\n", "transports:\n  room: {}\n");

        assertThatThrownBy(() -> ProjectAccess.settings(context, project, "room"))
                .as("an empty mapping would set up the account's own homeserver as well")
                .isInstanceOf(ProjectAccess.Withheld.class)
                .hasMessageContaining("names no settings for the room transport");
    }

    @Test
    void an_access_file_with_no_commit_in_force_withholds_every_setting() throws Exception {
        final PoreloContext context = context();
        final Project project =
                followed(
                        context,
                        "access: {repository: \"git@example.org:p/access.git\"}\n",
                        "",
                        "UNKNOWN_KEY");

        assertThatThrownBy(() -> ProjectAccess.settings(context, project, "room"))
                .as("a file lying in the clone is not applied until a commit of it is in force")
                .isInstanceOf(ProjectAccess.Withheld.class)
                .hasMessageContaining("is not applied (unknown_key)");
    }

    @Test
    void an_access_file_waiting_for_a_rewrite_withholds_every_setting() throws Exception {
        final PoreloContext context = context();
        final Project project =
                followed(
                        context,
                        "access: {repository: \"git@example.org:p/access.git\"}\n",
                        "a1",
                        "REWRITTEN");

        assertThatThrownBy(() -> ProjectAccess.settings(context, project, "room"))
                .as("a rewritten history waits for a person, never guessed past")
                .isInstanceOf(ProjectAccess.Withheld.class)
                .hasMessageContaining("waits for a person");
    }

    @Test
    void an_access_file_that_names_nothing_for_the_transport_never_gives_empty_settings()
            throws Exception {
        final PoreloContext context = context();
        final Project project = registered(context, "access: {file: \"access.yml\"}\n", "{}\n");

        assertThatThrownBy(() -> ProjectAccess.settings(context, project, "room"))
                .as("empty settings would set up the account's own homeserver")
                .isInstanceOf(ProjectAccess.Withheld.class)
                .hasMessageContaining("names no settings for the room transport");
    }

    @Test
    void an_access_repository_of_a_project_not_followed_is_never_read() throws Exception {
        final PoreloContext context = context();
        final Project project =
                registered(
                        context, "access: {repository: \"git@example.org:p/access.git\"}\n", null);

        assertThatThrownBy(() -> ProjectAccess.settings(context, project, "room"))
                .as("nothing fetched or verified it")
                .isInstanceOf(ProjectAccess.Withheld.class)
                .hasMessageContaining("is not followed");
    }

    @Test
    void a_task_start_says_why_it_stays_outside_the_conversation() throws Exception {
        final PoreloContext context = context();
        final Path adapters = Files.createDirectories(dir.resolve("data/porelo/transports"));
        final Path adapter = adapters.resolve(TransportDirectory.PREFIX + "room");
        Files.writeString(adapter, "#!/bin/sh\n");
        assertThat(adapter.toFile().setExecutable(true)).as("the transport is executable").isTrue();
        final Project project =
                followed(
                        context,
                        "access: {repository: \"git@example.org:p/access.git\"}\n",
                        "",
                        "UNSIGNED");

        assertThat(new TaskConversations(context).enroll(project, "porelo-p-t"))
                .as("the start's own words, naming the access file")
                .contains("cannot take part in the project's room conversation")
                .contains("is not applied (unsigned)");
        assertThat(runner.invocations())
                .as("neither setup nor enroll ran")
                .noneMatch(
                        each ->
                                each.arguments().contains("setup")
                                        || each.arguments().contains("enroll"));
    }

    @Test
    void a_pass_by_hand_sends_and_fetches_nothing_while_the_access_file_is_withheld()
            throws Exception {
        final PoreloContext context = context();
        registered(context, "access: {file: \"access.yml\"}\n", null);
        new Mailbox(context.paths().messaging().mailbox("porelo-p-t")).create();
        final java.io.StringWriter err = new java.io.StringWriter();
        final picocli.CommandLine cli = PoreloCli.commandLine(context);
        cli.setOut(new java.io.PrintWriter(new java.io.StringWriter()));
        cli.setErr(new java.io.PrintWriter(err));

        final int code = cli.execute("talk", "pass", "porelo-p-t", "--project", "p");

        assertThat(code).as(err.toString()).isEqualTo(78);
        assertThat(err.toString())
                .as("says why, as the daemon's pass does")
                .contains("nothing was sent or fetched for porelo-p-t")
                .contains("the access file of p");
        assertThat(runner.invocations())
                .as("no transport was asked anything")
                .noneMatch(
                        each ->
                                each.arguments().contains("poll")
                                        || each.arguments().contains("send"));
    }

    @Test
    void a_transport_is_never_run_for_a_project_whose_access_file_is_not_applied()
            throws Exception {
        final PoreloContext context = context();
        final Path adapters = Files.createDirectories(dir.resolve("data/porelo/transports"));
        final Path adapter = adapters.resolve(TransportDirectory.PREFIX + "room");
        Files.writeString(adapter, "#!/bin/sh\n");
        assertThat(adapter.toFile().setExecutable(true)).as("the transport is executable").isTrue();
        final Project project =
                followed(
                        context,
                        "access: {repository: \"git@example.org:p/access.git\"}\n",
                        "",
                        "");

        assertThatThrownBy(() -> MachineTransports.admit(context, project, "host2-core"))
                .as("refused before the transport is asked anything")
                .isInstanceOf(Conversations.Refused.class)
                .hasMessageContaining("is not applied");
        assertThat(runner.invocations())
                .as("no lifecycle verb ran")
                .noneMatch(each -> each.arguments().contains("admit"));
    }
}
