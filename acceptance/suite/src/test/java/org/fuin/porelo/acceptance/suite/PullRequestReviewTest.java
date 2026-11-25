package org.fuin.porelo.acceptance.suite;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PullRequestReviewTest {

    private static final String BASE = "0123456789abcdef0123456789abcdef01234567";

    private static final String URL = "https://review.stub.invalid/shop/app/pull/42";

    @TempDir Path home;

    @Test
    void everyStepOfThePullRequestFeatureHasItsGlue() {
        final byte exit =
                io.cucumber.core.cli.Main.run(
                        new String[] {
                            "--dry-run",
                            "--glue",
                            "org.fuin.porelo.acceptance",
                            "--plugin",
                            "summary",
                            "classpath:org/fuin/porelo/acceptance/gate/pr-review.feature"
                        },
                        Thread.currentThread().getContextClassLoader());

        assertThat(exit)
                .as("a dry run of pr-review.feature finds an undefined or ambiguous step")
                .isZero();
    }

    @Test
    void aForgeAccountsAnswerIsOnePageOfOneReplyAtTheScenariosPullRequest() {
        final String page =
                ForgeStub.forgeItem(
                        ForgeStub.fixture(ForgeStub.FORGE_PAGE),
                        URL,
                        "hubot",
                        "9100000001",
                        true,
                        "Why there?");

        final Map<?, ?> run = run(page);
        final List<?> results = (List<?>) run.get("results");
        final Map<?, ?> properties = (Map<?, ?>) ((Map<?, ?>) results.get(0)).get("properties");
        assertThat(((Map<?, ?>) run.get("properties")).get("porelo/pullRequest"))
                .as("the page's pull request")
                .isEqualTo(URL);
        assertThat(results).as("the page's results").hasSize(1);
        assertThat(properties.get("porelo/sourceKind"))
                .as("who wrote it")
                .isEqualTo("forge-account");
        assertThat(properties.get("porelo/forgeAccountName")).as("its account").isEqualTo("hubot");
        assertThat(properties.get("porelo/replyTo"))
                .as("the thread it answers")
                .isEqualTo("9100000001");
        assertThat(((Map<?, ?>) ((Map<?, ?>) results.get(0)).get("message")).get("text"))
                .as("what it says")
                .isEqualTo("Why there?");
    }

    @Test
    void aForgeAccountsNewThreadAnswersNone() {
        final String page =
                ForgeStub.forgeItem(
                        ForgeStub.fixture(ForgeStub.FORGE_PAGE),
                        URL,
                        "hubot",
                        "9100000002",
                        false,
                        "Why?");

        final Map<?, ?> properties =
                (Map<?, ?>)
                        ((Map<?, ?>) ((List<?>) run(page).get("results")).get(0)).get("properties");
        assertThat(properties.containsKey("porelo/replyTo"))
                .as("a new thread answers one")
                .isFalse();
        assertThat(properties.get("porelo/threadId")).as("its native id").isEqualTo("9100000002");
    }

    @Test
    void theAnswersAreReplacedWholeFromStandardInput() throws Exception {
        Files.createDirectories(home.resolve("data/porelo/builds"));
        Files.writeString(home.resolve("data/porelo/builds/" + ForgeStub.answersFile()), "old");

        final ProcessBuilder builder =
                new ProcessBuilder("sh", "-c", ForgeStub.writeCommand(ForgeStub.answersFile()))
                        .redirectErrorStream(true);
        builder.environment().put("XDG_DATA_HOME", home.resolve("data").toString());
        final Process process = builder.start();
        process.getOutputStream().write("new answers".getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("the write ended").isTrue();

        assertThat(process.exitValue()).as("the write's exit").isZero();
        assertThat(home.resolve("data/porelo/builds/" + ForgeStub.answersFile()))
                .as("the answers after the write")
                .hasContent("new answers");
        try (var left = Files.list(home.resolve("data/porelo/builds"))) {
            assertThat(left.map(path -> path.getFileName().toString()))
                    .as("what the write left beside the answers")
                    .containsExactly(ForgeStub.answersFile());
        }
    }

    @Test
    void theHeldItemsAnswerIsReadItemByItem() {
        final List<Map<String, Object>> items =
                PullRequestReviewSteps.items(
                        "{\"items\":[{\"id\":\"h1\",\"direction\":\"outgoing\",\"digest\":\"ab\"}]}");

        assertThat(items).as("the items read").hasSize(1);
        assertThat(items.get(0).get("digest")).as("the first item's digest").isEqualTo("ab");
    }

    @Test
    void aRunNamesItsAgentRoleAndPlaceQuoted() {
        assertThat(PullRequestReviewSteps.runCommand("reviewer", "reviewer", URL))
                .as("the run's command")
                .endsWith(
                        "porelo task run 'reviewer' --agent stub --role 'reviewer' --place '"
                                + URL
                                + "'");
    }

    @Test
    void theReviewFileThatRepliesHoldsTheReplyOnTheOneLineTheStubRepeats() {
        final List<String> lines = StubReview.replies(URL, BASE).lines().toList();

        assertThat(lines.getFirst()).as("the file's first line").isEqualTo("replies");
        assertThat(lines.stream().filter(line -> line.contains(StubReview.THREAD)))
                .as("the lines naming the thread's place")
                .hasSize(1);
        final String once =
                String.join("\n", lines.subList(1, lines.size()))
                        .replace(StubReview.THREAD, "1834527690");
        assertThat(((Map<?, ?>) run(once).get("properties")).get("porelo/pullRequest"))
                .as("the submission's pull request, with one reply in place")
                .isEqualTo(URL);
    }

    @Test
    void theReviewFileThatSubmitsCarriesTheFixtureAtThePullRequestWithTheFieldAsked() {
        final String text =
                StubReview.submit("submitted-comment.sarif.json", URL, BASE, "porelo/extra");

        assertThat(text)
                .as("the file's first line")
                .startsWith("submit submitted-comment.sarif.json\n");
        final Map<?, ?> run = run(text.substring(text.indexOf('\n') + 1));
        final Map<?, ?> result = (Map<?, ?>) ((List<?>) run.get("results")).get(0);
        assertThat(((Map<?, ?>) run.get("properties")).get("porelo/pullRequest"))
                .as("the submission's pull request")
                .isEqualTo(URL);
        final Map<?, ?> provenance =
                (Map<?, ?>) ((List<?>) run.get("versionControlProvenance")).get(0);
        assertThat(provenance.get("repositoryUri"))
                .as("the submission's repository: the pull request's")
                .isEqualTo("https://review.stub.invalid/shop/app");
        assertThat(provenance.get("revisionId"))
                .as("the submission's commit: the head the stub names")
                .isEqualTo(StubReview.HEAD);
        assertThat(((Map<?, ?>) run.get("properties")).get("porelo/baseRevisionId"))
                .as("the submission's base: the pull request's, never the fixture's")
                .isEqualTo(BASE);
        assertThat(((Map<?, ?>) result.get("properties")).get("porelo/extra"))
                .as("the field outside the profile")
                .isNotNull();
    }

    @Test
    void aPullRequestsRepositoryIsNamedInTheAccountsHomeByItsAddress() {
        assertThat(PullRequestReviewSteps.repositoryOf(URL))
                .as("the repository's path")
                .isEqualTo("\"$HOME/pr-review-forge/https-review-stub-invalid-shop-app-pull-42\"");
    }

    @Test
    void theRepliesFileNamesThePullRequestsBaseNeverTheFixtures() {
        final String once =
                StubReview.replies(URL, BASE)
                        .lines()
                        .skip(1)
                        .collect(java.util.stream.Collectors.joining("\n"))
                        .replace(StubReview.THREAD, "1834527690");

        assertThat(((Map<?, ?>) run(once).get("properties")).get("porelo/baseRevisionId"))
                .as("the replies' base")
                .isEqualTo(BASE);
    }

    @Test
    void aBaseCommitIsReadOnlyFromAWholeCommitId() {
        assertThat(
                        PullRequestReviewSteps.commit(
                                new org.fuin.porelo.machines.Ssh.Output(BASE + "\n", "", 0),
                                "base"))
                .as("the base read")
                .isEqualTo(BASE);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                PullRequestReviewSteps.commit(
                                        new org.fuin.porelo.machines.Ssh.Output(
                                                "", "no such ref", 128),
                                        "base"))
                .as("a failed read")
                .isInstanceOf(AssertionError.class);
    }

    private static Map<?, ?> run(String page) {
        final Map<?, ?> document = (Map<?, ?>) Json.parse(page);
        return (Map<?, ?>) ((List<?>) document.get("runs")).get(0);
    }
}
