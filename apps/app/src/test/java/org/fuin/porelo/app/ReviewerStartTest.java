package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.agent.api.ProviderDefinition;
import org.fuin.porelo.agent.api.ProviderDirectory;
import org.fuin.porelo.agent.api.ProviderRoute;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.gate.GitGate;
import org.fuin.porelo.gate.PinnedRepository;
import org.junit.jupiter.api.Test;

/**
 * What a task started as a reviewer is given, decided from its recorded role: no credential of the
 * project, no package repository, egress to its agent's and its provider's hosts and no project
 * origin, a gate that shows its pinned ref and takes nothing, and a workspace with nowhere to push.
 */
class ReviewerStartTest {

    private static final String TASK = "shop-2";

    private static final RunPlace REVIEWER = new RunPlace(RunPlace.REVIEWER, null, "shop-1");

    private static final RunPlace AUTHOR = RunPlace.ownPush("shop", null, TASK);

    private static final Map<String, String> PROJECT_ORIGINS =
            Map.of("forge.example", "project file");

    @Test
    void aReviewerIsGivenNoCredentialTheProjectNames() {
        final Map<String, String> declared = Map.of("GH_TOKEN", "forge.example");
        assertThat(ReviewerStart.credentials(REVIEWER, declared))
                .as("a reviewer's credentials")
                .isEmpty();
        assertThat(ReviewerStart.credentials(AUTHOR, declared))
                .as("an author's credentials")
                .isEqualTo(declared);
        assertThat(ReviewerStart.credentials(null, declared))
                .as("credentials of a task without a gate")
                .isEqualTo(declared);
    }

    @Test
    void aReviewerIsServedNoPackageRepository() {
        final List<String> declared = List.of("maven-central-mirror");
        assertThat(ReviewerStart.repositories(REVIEWER, declared))
                .as("a reviewer's repositories")
                .isEmpty();
        assertThat(ReviewerStart.repositories(AUTHOR, declared))
                .as("an author's repositories")
                .isEqualTo(declared);
    }

    @Test
    void aReviewerReachesItsProviderButNoHostTheProjectNames() {
        final SelectedProvider serving = provider();
        final List<String> providerHosts = serving.definition().domains();
        assertThat(ReviewerStart.reachable(REVIEWER, null, serving, PROJECT_ORIGINS).domains())
                .as("what a reviewer may reach")
                .containsExactlyElementsOf(providerHosts)
                .doesNotContain("forge.example");
        assertThat(ReviewerStart.reachable(REVIEWER, null, null, PROJECT_ORIGINS).domains())
                .as("what a reviewer without a provider may reach")
                .isEmpty();
        assertThat(ReviewerStart.reachable(AUTHOR, null, serving, PROJECT_ORIGINS).domains())
                .as("what an author may reach")
                .containsAll(providerHosts)
                .contains("forge.example");
    }

    @Test
    void aReviewersWorkspaceHasNoRefToPushToAndKnowsItsPinnedRef() {
        assertThat(ReviewerStart.workspaceRefs(REVIEWER, TASK))
                .as("a reviewer's workspace variables")
                .containsExactly(Map.entry("PORELO_PINNED_REF", GitGate.PINNED + TASK));
        assertThat(ReviewerStart.workspaceRefs(AUTHOR, TASK))
                .as("an author's workspace variables")
                .containsExactly(Map.entry("PORELO_TASK_REF", GitGate.INCOMING + TASK));
    }

    @Test
    void aReviewersGateServesItsOwnRepositoryShowsItsPinnedRefAndPassesNothingOn() {
        final List<String> command =
                GateWiring.reviewing(
                        project(), "127.0.0.1", 9418, Path.of("/run/porelo/state"), TASK, "uc");
        assertThat(command)
                .as("a reviewer's gate")
                .containsSequence("gate", "serve")
                .containsSequence("--pinned", GitGate.PINNED + TASK)
                .containsSequence(
                        "--pinned-repository",
                        PinnedRepository.in(Path.of("/run/porelo/state")).toString())
                .containsSequence("--repository", "uc")
                .doesNotContain("--ref", "--pass-on", "--host-push", "--branch", "--reads");
    }

    @Test
    void aReviewersContainerIsStartedWithoutTheProjectsHostServices() {
        final Project project =
                ProjectReader.read(
                        new StringReader(
                                """
                project:
                  name: "uc"
                  security_class: "online"
                  upstream: "https://forge.example/uc.git"
                image:
                  from: "ubuntu:24.04"
                egress:
                  domains: [nexus.example]
                  refused: [telemetry.example]
                  host_services: [registry.example]
                """),
                        "test");
        final Project reviewing = ReviewerStart.started(REVIEWER, project);
        assertThat(reviewing.egress().hostServices()).as("a reviewer's host services").isEmpty();
        assertThat(reviewing.egress().domains()).as("a reviewer's own hosts").isEmpty();
        assertThat(reviewing.egress().refused())
                .as("what a reviewer is refused")
                .containsExactly("telemetry.example");
        assertThat(ReviewerStart.started(AUTHOR, project).egress().hostServices())
                .as("an author's host services")
                .containsExactly("registry.example");
        assertThat(ReviewerStart.started(null, project).egress().hostServices())
                .as("host services of a task without a gate")
                .containsExactly("registry.example");
    }

    private static Project project() {
        return ProjectReader.read(
                new StringReader(
                        """
                project:
                  name: "uc"
                  security_class: "online"
                  upstream: "https://forge.example/uc.git"
                image:
                  from: "ubuntu:24.04"
                """),
                "test");
    }

    /** The first provider this repository ships, whichever it is. */
    private static SelectedProvider provider() {
        final ProviderDefinition definition =
                new ProviderDirectory(List.of(Path.of("..", "..", "providers")))
                        .all().values().stream()
                                .filter(each -> !each.domains().isEmpty())
                                .findFirst()
                                .orElseThrow();
        return new SelectedProvider(
                definition,
                new ProviderRoute(
                        "https://" + definition.domains().getFirst(),
                        null,
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        ProviderRoute.Endpoint.SOCKET,
                        "",
                        Map.of()));
    }
}
