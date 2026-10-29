package org.fuin.porelo.core.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Where a project names its access file. Harm guarded: a project whose public file still carried a
 * server's name and room would show them to everyone, and a file whose access place is ambiguous
 * would leave a machine guessing which signed file decides.
 */
class ProjectAccessTest {

    private static Project read(final String access, final String mail) {
        return ProjectReader.read(
                new StringReader(
                        """
                project:
                  name: "p"
                  security_class: "guarded"
                image:
                  base_image: "ubuntu:24.04"
                %s%s"""
                                .formatted(access, mail)),
                "test");
    }

    @Test
    void a_file_that_names_no_access_has_none_and_keeps_its_transports() {
        final Project project =
                read(
                        "",
                        """
                mail:
                  transports:
                    matrix: {homeserver: "https://chat.example.org"}
                """);

        assertThat(project.access()).as("no access file named").isNull();
        assertThat(project.mail().transports())
                .as("transport settings read from the project file, as before")
                .containsKey("matrix");
    }

    @Test
    void a_repository_names_the_access_file_in_a_repository_of_its_own() {
        assertThat(read("access: {repository: \"git@example.org:p/access.git\"}\n", "").access())
                .as("the second repository")
                .isEqualTo(new Access.InRepository("git@example.org:p/access.git"));
    }

    @Test
    void a_file_names_the_access_file_beside_the_project_file() {
        assertThat(read("access: {file: \"access.yml\"}\n", "").access())
                .as("the file beside project.yml")
                .isEqualTo(new Access.BesideProject("access.yml"));
    }

    @Test
    void both_places_at_once_are_refused() {
        assertThatThrownBy(
                        () ->
                                read(
                                        "access: {repository: \"git@example.org:p/a.git\", file:"
                                                + " \"access.yml\"}\n",
                                        ""))
                .as("one signed file decides, never two")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("names both 'repository' and 'file'");
    }

    @Test
    void neither_place_is_refused() {
        assertThatThrownBy(() -> read("access: {}\n", ""))
                .as("an access section naming no place names no file")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("names neither 'repository' nor 'file'");
    }

    @Test
    void unknown_keys_include_misspelled_access_settings() {
        assertThatThrownBy(
                        () ->
                                ProjectReader.unknownKeys(
                                        new StringReader("access:\n  repositroy: x\n"), "test"))
                .as("an editor must warn about an access setting that will not take effect")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("'access.repositroy'");
    }

    @Test
    void another_key_under_access_is_refused() {
        assertThatThrownBy(() -> read("access: {file: \"access.yml\", branch: \"main\"}\n", ""))
                .as("a key Porelo does not read is no setting at all")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("'access.branch' is not a setting");
    }

    @ParameterizedTest
    @ValueSource(strings = {"../access.yml", "secrets/access.yml", "project.yml", ""})
    void a_file_other_than_access_yml_beside_the_project_file_is_refused(final String file) {
        assertThatThrownBy(() -> read("access: {file: \"" + file + "\"}\n", ""))
                .as("only access.yml beside project.yml, never a path elsewhere")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("the access file is 'access.yml'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"--upload-pack=touch /tmp/x", "ext::sh -c touch% /tmp/x", ""})
    void a_repository_git_would_read_as_an_option_or_nothing_is_refused(final String url) {
        assertThatThrownBy(() -> read("access: {repository: \"" + url + "\"}\n", ""))
                .as("the address reaches git's command line")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("access.repository");
    }

    @Test
    void transport_settings_in_the_project_file_are_refused_once_access_is_named() {
        assertThatThrownBy(
                        () ->
                                read(
                                        "access: {file: \"access.yml\"}\n",
                                        """
                mail:
                  transports:
                    matrix: {homeserver: "https://chat.example.org"}
                """))
                .as("the public file must not show what the access file hides")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("move matrix into access.yml");
    }
}
