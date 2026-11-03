package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.ecosystem.api.Detection;
import org.fuin.porelo.ecosystem.api.Finding;
import org.fuin.porelo.ecosystem.api.Unplaced;
import org.junit.jupiter.api.Test;

/** Prevents ambiguous rules and credentials from becoming project policy. */
class ProjectProposalTest {
    private Finding finding(Finding.Part part, String key, String value, int line) {
        return new Finding(
                part, key, value, "settings.xml", line, Finding.Origin.CHECKOUT, false, "");
    }

    @Test
    void agreementKeepsEverySourceAndDisagreementNeverBecomesPolicy() {
        var proposal = new ProjectProposal("sample", null, null, null);
        proposal.add(
                "first",
                new Detection(
                        List.of(
                                finding(Finding.Part.RULES, "host.repo.example.org", "yes", 1),
                                finding(Finding.Part.TOOLS, "package.jq", "1", 2)),
                        List.of()));
        proposal.add(
                "second",
                new Detection(
                        List.of(
                                finding(Finding.Part.RULES, "host.repo.example.org", "yes", 3),
                                finding(Finding.Part.TOOLS, "package.jq", "2", 4)),
                        List.of()));
        var answer = proposal.answer();
        assertThat(answer.get("values").toString())
                .as("agreement retains both source lines")
                .contains("line=1", "line=3");
        assertThat(answer.get("disagreements").toString())
                .as("conflicting values remain available for review")
                .contains("package.jq", "value=1", "value=2");
        assertThat(answer.get("draft").toString())
                .as("a disputed package cannot be installed")
                .contains("repo.example.org")
                .doesNotContain("jq");
        assertThat(
                        ProjectReader.read(
                                        new StringReader(answer.get("draft").toString()), "draft")
                                .repositories())
                .as("the shown draft reads")
                .hasSize(1);
    }

    @Test
    void unknownFindingsKeepTheFileAndLineWhereTheyAreSet() {
        var proposal = new ProjectProposal("sample", null, null, null);
        proposal.add(
                "first",
                new Detection(
                        List.of(
                                new Finding(
                                        Finding.Part.TOOLS,
                                        "runtime.version",
                                        "",
                                        "settings.xml",
                                        5,
                                        Finding.Origin.CHECKOUT,
                                        true,
                                        "computed")),
                        List.of()));
        var answer = proposal.answer();
        assertThat(answer.get("values").toString())
                .as("an unknown setting is visible with where it is set")
                .contains("unknown=true", "settings.xml", "line=5");
    }

    @Test
    void aToolWithoutAGenericRuleStaysACommentAndIsListedAsNotPlaced() {
        var proposal = new ProjectProposal("sample", null, null, null);
        proposal.add(
                "first",
                new Detection(
                        List.of(finding(Finding.Part.TOOLS, "runtime.version", "25", 6)),
                        List.of()));
        var answer = proposal.answer();
        assertThat(answer.get("not_placed").toString())
                .as("a tool with no generic rule remains available to place")
                .contains("runtime.version", "25", "settings.xml");
        assertThat(answer.get("draft").toString())
                .as("a comment keeps the value and source")
                .contains("# runtime.version = 25 # settings.xml:6");
    }

    @Test
    void credentialsNeverExposeTheirValueInTheDraftOrTheAnswer() {
        var proposal = new ProjectProposal("sample", null, null, null);
        proposal.add(
                "first",
                new Detection(
                        List.of(
                                finding(
                                        Finding.Part.RULES,
                                        "credential.private",
                                        "password-fixture",
                                        7)),
                        List.of()));
        var answer = proposal.answer();
        assertThat(answer.toString())
                .as("a credential's value reaches neither draft nor answer")
                .doesNotContain("password-fixture");
        assertThat(answer.get("credentials_needed").toString())
                .as("the answer tells how to store the needed identifier")
                .contains("private", "porelo vault put private");
    }

    @Test
    void unplacedMaterialKeepsItsSourceAndExplanation() {
        var proposal = new ProjectProposal("sample", null, null, null);
        proposal.add(
                "first",
                new Detection(List.of(), List.of(new Unplaced("settings.xml", 8, "conditional"))));
        assertThat(proposal.answer().get("unplaced").toString())
                .as("unplaced material cannot disappear silently")
                .contains("settings.xml", "line=8", "conditional", "first");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {"on", "off", "yes", "no", "0123", "1.20"})
    void aValidRepositoryNameKeepsItsExactSpellingThroughYaml(String name) {
        var answer = new ProjectProposal(name, null, null, null).answer();
        var read = ProjectReader.read(new StringReader(answer.get("draft").toString()), "draft");
        assertThat(read.repositories().getFirst().name())
                .as("YAML scalar resolution cannot rename a repository")
                .isEqualTo(name);
    }

    @Test
    void aDraftThatTheProjectReaderRefusesCannotBeShown() {
        var proposal = new ProjectProposal("sample", "--unsafe", null, null);
        org.assertj.core.api.Assertions.assertThatThrownBy(proposal::answer)
                .as("the draft passes through the actual reader before it can be shown")
                .isInstanceOf(org.fuin.porelo.core.project.ProjectException.class);
    }

    @Test
    void aGenericPackageGetsAnImageSnippetWithItsSource() {
        var proposal =
                new ProjectProposal("sample", "https://example.org/sample.git", null, "optional");
        proposal.add(
                "first",
                new Detection(
                        List.of(finding(Finding.Part.TOOLS, "package.jq", "", 9)), List.of()));
        var draft = proposal.answer().get("draft").toString();
        assertThat(draft)
                .as("generic packages get a reviewable installation and source")
                .contains("apt-get install", "jq", "settings.xml:9", "review: \"optional\"");
        assertThat(
                        ProjectReader.read(new StringReader(draft), "draft")
                                .repositories()
                                .getFirst()
                                .image())
                .as("the repository image is understood by the reader")
                .isNotNull();
    }

    @Test
    void aPackagesValueIsThePackageToInstallNotAVersionOfIt() {
        final ProjectProposal proposal = new ProjectProposal("sample", null, null, null);
        proposal.add(
                "maven",
                new Detection(
                        List.of(
                                finding(
                                        Finding.Part.TOOLS,
                                        "package.openjdk-25-jdk-headless",
                                        "openjdk-25-jdk-headless",
                                        9)),
                        List.of()));

        assertThat(String.valueOf(proposal.answer().get("draft")))
                .contains("--no-install-recommends openjdk-25-jdk-headless ")
                .doesNotContain("=openjdk");
    }

    @Test
    void aFiledSnippetHoldsItsLinesWithoutTheDraftsIndentation() {
        final ProjectProposal proposal = new ProjectProposal("sample", null, null, null);
        proposal.add(
                "maven",
                new Detection(
                        List.of(finding(Finding.Part.TOOLS, "package.maven", "", 9)), List.of()));
        proposal.answer();

        final Map<?, ?> image = (Map<?, ?>) proposal.row().get("image");
        assertThat(String.valueOf(image.get("snippet"))).startsWith("RUN apt-get update");
    }
}
