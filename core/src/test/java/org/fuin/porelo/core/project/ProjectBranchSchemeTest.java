package org.fuin.porelo.core.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** How a task's branch at the forge is named, by the project and by a repository. */
class ProjectBranchSchemeTest {

    private static final String HEAD =
            """
            project:
              name: "p"
              security_class: "online"
              upstream: "https://forge.example/p.git"
            image:
              base_image: "ubuntu:24.04"
            """;

    private static Project read(final String more) {
        return ProjectReader.read(new StringReader(HEAD + more), "test");
    }

    /** The branch of a task {@code login-fix} on a repository, as its start makes it. */
    private static String fill(
            final Project project, final String repository, final Map<String, String> values) {
        final Map<String, Parameters.Given> given = new java.util.LinkedHashMap<>();
        values.forEach((name, value) -> given.put(name, new Parameters.Given(value, "test")));
        return project.parameters()
                .fill(
                        project.branchScheme(repository).template(),
                        given,
                        "login-fix",
                        project.name(),
                        repository);
    }

    @Test
    void aProjectThatSaysNothingNamesItsTasksBranchesAsToday() {
        final Project project = read("");

        assertThat(project.branch()).as("no block written").isNull();
        assertThat(project.declaresBranch("p")).isFalse();
        assertThat(project.branchScheme("p")).isEqualTo(BranchScheme.DEFAULT);
        assertThat(fill(project, "p", Map.of())).isEqualTo("porelo/login-fix");
        assertThat(BranchScheme.DEFAULT.accepts("porelo/login-fix")).isTrue();
        assertThat(BranchScheme.DEFAULT.accepts("main")).isFalse();
    }

    @Test
    void theProjectsSchemeMakesAndJudgesTheNameAndARepositorysReplacesIt() {
        final Project project =
                read(
                        """
                        parameters:
                          type:
                            values: ["feature", "bugfix"]
                        branch:
                          template: "{type}/porelo/{task}"
                          pattern: "^(feature|bugfix)/porelo/[a-z0-9-]+$"
                        repositories:
                          site:
                            upstream: "https://forge.example/site.git"
                            branch:
                              template: "site/{task}"
                              pattern: "^site/.+$"
                          docs:
                            upstream: "https://forge.example/docs.git"
                        """);

        final BranchScheme own = project.branchScheme("p");
        final String name = fill(project, "p", Map.of("type", "bugfix"));
        assertThat(name).isEqualTo("bugfix/porelo/login-fix");
        assertThat(own.accepts(name)).isTrue();
        assertThat(own.accepts("chore/porelo/login-fix")).as("a name the forge refuses").isFalse();
        assertThat(fill(project, "site", Map.of()))
                .as("a repository's scheme replaces the project's")
                .isEqualTo("site/login-fix");
        assertThat(project.branchScheme("docs"))
                .as("one that names none takes the project's")
                .isEqualTo(own);
    }

    @Test
    void aRepositorysOwnBlockIsABlockForItAloneEvenWithoutTheProjects() {
        final Project project =
                read(
                        """
                        repositories:
                          site:
                            upstream: "https://forge.example/site.git"
                            branch:
                              template: "porelo/{task}"
                              pattern: "^porelo/.+$"
                          docs:
                            upstream: "https://forge.example/docs.git"
                        """);

        assertThat(project.declaresBranch("site")).isTrue();
        assertThat(project.declaresBranch("docs")).isFalse();
        assertThat(project.declaresBranch("p")).isFalse();
    }

    @Test
    void aTemplateWithoutAPatternIsRefused() {

        // The pattern is also what the gate keeps from the tasks; without it, another task's
        // branch named by the template would reach every task's mirror.
        assertThatThrownBy(() -> read("branch:\n  template: \"x/{task}\"\n"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("no pattern");
    }

    @Test
    void aPatternThatIsNoRegularExpressionOrAStrayBraceIsRefused() {
        assertThatThrownBy(() -> read("branch:\n  template: \"x/{task}\"\n  pattern: \"(\"\n"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("not a regular expression");
        assertThatThrownBy(() -> read("branch:\n  template: \"x/{task\"\n  pattern: \"^x/.+$\"\n"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("brace");
    }

    @Test
    void aNameGitDoesNotTakeForABranchIsNoName() {
        assertThat(BranchScheme.refFormat("bugfix/porelo/fix")).isTrue();
        for (final String name :
                java.util.List.of(
                        "HEAD", "@", "a..b", "a/.b", "a.lock", "a b", "a~b", "/a", "a/", "a//b",
                        "a.", "-a", "a@{b")) {
            assertThat(BranchScheme.refFormat(name)).as(name).isFalse();
        }
    }

    @Test
    void aMisplacedKeyIsRefusedByTheSchema() {
        assertThatThrownBy(() -> read("branch:\n  templat: \"x\"\n"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("branch.templat");
    }
}
