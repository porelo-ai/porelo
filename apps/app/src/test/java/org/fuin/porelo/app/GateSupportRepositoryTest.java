package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.util.List;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.core.project.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests that a repository name selects one repository without guessing between matches. */
class GateSupportRepositoryTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://forge.example.org/team/Message_Sluice.git",
                "ssh://git@forge.example.org:2222/team/Message_Sluice.git/",
                "git@forge.example.org:team/Message_Sluice.git",
                "git@forge.example.org:Message_Sluice.git"
            })
    void aFullNameSelectsTheRepositoryWithoutChangingItsSpelling(String address) {
        final Repository repository = new Repository("sluice", address);
        final Project project = project("shop").withRepositories(List.of(repository));

        assertThat(GateSupport.repository(project, "Message_Sluice"))
                .as("the full address name must select the same repository as its key")
                .isEqualTo(repository);
    }

    @Test
    void aKeyWinsEvenWhenOtherRepositoriesHaveThatFullName() {
        final Repository keyed = new Repository("sluice", "https://forge.example.org/o/other.git");
        final Project project =
                project("shop")
                        .withRepositories(
                                List.of(
                                        new Repository(
                                                "first", "git@forge.example.org:a/sluice.git"),
                                        keyed,
                                        new Repository(
                                                "second", "git@forge.example.org:b/sluice.git")));

        assertThat(GateSupport.repository(project, "sluice"))
                .as("adding a matching full name must never redirect an existing key")
                .isEqualTo(keyed);
    }

    @Test
    void theCentralNameTakesPrecedenceOverTheUpstreamName() {
        final Repository repository =
                new Repository(
                        "sluice",
                        "https://host.example.org/local/mirror.git",
                        "",
                        List.of(),
                        null,
                        null,
                        null,
                        Repository.REVIEW_REQUIRED,
                        "git@central.example.org:team/message-sluice.git");
        final Project project = project("shop").withRepositories(List.of(repository));

        assertThat(GateSupport.repository(project, "message-sluice"))
                .as("a host's address must not replace the central repository's identity")
                .isEqualTo(repository);
        assertThatThrownBy(() -> GateSupport.repository(project, "mirror"))
                .as("an upstream alias must not select a repository with a different central name")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("sluice (message-sluice)");
    }

    @Test
    void aProjectsOwnAddressNeverMakesItsOneWorkRepositoryAmbiguous() {
        // A project of one repository keeps its file in that repository, so both name one address.
        final Repository sluice =
                new Repository("sluice", "https://forge.example.org/team/message-sluice.git");
        final Project project =
                ProjectReader.read(
                                new StringReader(
                                        "project: { name: sluice-project, security_class: guarded,"
                                                + " upstream:"
                                                + " 'https://forge.example.org/team/message-sluice.git' }\n"
                                                + "image: { from: 'ubuntu:24.04' }\n"),
                                "test")
                        .withRepositories(List.of(sluice));

        assertThat(GateSupport.repository(project, "message-sluice"))
                .as("the full name selects the repository work happens in, not the project's own")
                .isEqualTo(sluice);
    }

    @Test
    void aSharedFullNameIsRefusedWithTheKeysThatDisambiguateIt() {
        final Project project =
                project("shop")
                        .withRepositories(
                                List.of(
                                        new Repository(
                                                "first", "https://forge.example.org/a/shared.git"),
                                        new Repository(
                                                "second", "git@forge.example.org:b/shared.git")));

        assertThatThrownBy(() -> GateSupport.repository(project, "shared"))
                .as("a full name shared by two repositories must never pick either one")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("ambiguous")
                .hasMessageContaining("first (shared)")
                .hasMessageContaining("second (shared)");
    }

    @Test
    void anUnknownNameOffersEveryWorkableRepositoryInBothForms() {
        final Project project =
                project("shop")
                        .withRepositories(
                                List.of(
                                        new Repository(
                                                "sluice",
                                                "git@forge.example.org:o/message-sluice.git"),
                                        new Repository("local", null)));

        assertThatThrownBy(() -> GateSupport.repository(project, "missing"))
                .as(
                        "a refused selection must offer usable keys and full names, including local"
                                + " work")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("missing")
                .hasMessageContaining("works in: sluice (message-sluice), local")
                .hasMessageNotContaining(", shop");
    }

    @Test
    void anEmptyDefaultNeverOffersItsOwnRepositoryAsWork() {
        assertThatThrownBy(() -> GateSupport.repository(project("default"), "missing"))
                .as("the empty default placeholder must never be offered as a place to work")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("none yet")
                .hasMessageNotContaining("works in: default");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void anUnnamedRepositoryStillMeansTheProjectsOwn(String unnamed) {
        final Project project =
                project("shop").withRepositories(List.of(new Repository("other", null)));

        assertThat(GateSupport.repository(project, unnamed))
                .as("the new name lookup must preserve the existing unnamed fallback")
                .isEqualTo(project.ownRepository());
        assertThat(GateSupport.repository(project, null))
                .as("an omitted option must preserve the existing unnamed fallback")
                .isEqualTo(project.ownRepository());
    }

    private static Project project(String name) {
        return ProjectReader.read(
                new StringReader(
                        "project: { name: "
                                + name
                                + ", security_class: guarded }\n"
                                + "image: { from: 'ubuntu:24.04' }\n"),
                "test");
    }
}
