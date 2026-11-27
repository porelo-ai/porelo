package org.fuin.porelo.acceptance.suite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void whatATaskHoldsIsAwaitedPastTheDaemonsMinuteOfMovingMail() {
        assertThat(PullRequestReviewSteps.MAILBOX_MOVED)
                .as("a run's last message can wait for the timer, once a minute")
                .isGreaterThan(java.time.Duration.ofSeconds(60));
    }

    @Test
    void aForgeAccountsCommentStandsAtALineEveryServedPullRequestHas() {
        final Map<?, ?> result =
                (Map<?, ?>)
                        ((List<?>)
                                        run(ForgeStub.forgeItem(
                                                        ForgeStub.fixture(ForgeStub.FORGE_PAGE),
                                                        URL,
                                                        "hubot",
                                                        "9100000003",
                                                        false,
                                                        "Why?"))
                                                .get("results"))
                                .get(0);

        assertThat(Json.write(result.get("locations")))
                .as("the README.md an author's project and the stub git forge both hold, line 1")
                .contains("\"uri\":\"README.md\"")
                .contains("\"startLine\":1");
        assertThat(result.containsKey("fixes"))
                .as("no suggestion for lines of another file")
                .isFalse();
        assertThat(PullRequestReviewSteps.repositoryCommand("/r"))
                .as("the stub git forge's repository holds that README.md")
                .contains("> \"$r/README.md\"");
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
    void theHeldItemsArrayIsReadItemByItem() {
        final List<Map<String, Object>> items =
                PullRequestReviewSteps.items(
                        "[{\"id\":\"h1\",\"direction\":\"outgoing\",\"digest\":\"ab\"}]");

        assertThat(items).as("the items read").hasSize(1);
        assertThat(items.get(0).get("digest")).as("the first item's digest").isEqualTo("ab");
    }

    @Test
    void anEmptyHeldItemsArrayIsNothingHeld() {
        assertThat(PullRequestReviewSteps.items("[]")).as("the items of an empty array").isEmpty();
    }

    @Test
    void aHeldItemsAnswerThatIsNoArrayIsRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> PullRequestReviewSteps.items("{\"items\":[]}"))
                .as("an object in place of the array")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("no JSON array");
    }

    // Harm: a reviewer started in the account's default project, which names no repository of the
    // pull request, so its start is refused before the run the scenario shows.
    @Test
    void aRunIsATaskStartWithItsAgentRightsPlaceAndAPromptQuoted() {
        assertThat(
                        PullRequestReviewSteps.runCommand(
                                "reviewer",
                                "reviewed",
                                "review",
                                List.of("reply", "openThreads"),
                                URL))
                .as("the run's command")
                .startsWith(PullRequestReviewSteps.certified("t='reviewer'; "))
                .contains("porelo task stop \"$t\"")
                .endsWith(
                        "porelo task start \"$t\" --project 'reviewed' --repository 'reviewed'"
                                + " --agent stub --right 'reply' --right 'openThreads'"
                                + " --write-as 'review' --place '"
                                + URL
                                + "' --prompt review")
                .doesNotContain("task run");
        assertThat(PullRequestReviewSteps.runCommand("reviewer", null, null, List.of(), URL))
                .as("a scenario that gave its reviewer no project of its own")
                .contains(" --project 'default' --repository 'default' --agent stub --place '");
        assertThat(
                        PullRequestReviewSteps.runCommand(
                                "porelo-shop_authored", "reviewed", null, List.of(), URL))
                .as("a task named by its container carries its own project and names no right")
                .contains("porelo task start \"$t\" --agent stub --place '")
                .doesNotContain("--project", "--right", "--role", "--write-as");
        assertThatThrownBy(
                        () ->
                                PullRequestReviewSteps.runCommand(
                                        "reviewer", "x'; rm -rf ~; '", null, List.of(), URL))
                .as("a project that is more than a name")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ScenarioTasks.words(" reply,  openThreads "))
                .as("a step's rights, separated by commas")
                .containsExactly("reply", "openThreads");
    }

    @Test
    void theSameReviewFileTwiceStillMovesTheHead() throws Exception {
        final Path repository = home.resolve("head");
        Files.createDirectories(repository);
        assertThat(exitOf(home, "git init -q -b review-head " + repository)).as("init").isZero();
        final String command = PullRequestReviewSteps.commitCommand(repository.toString());

        assertThat(exitOf(home, "printf 'replies\\n' | " + command)).as("the first round").isZero();
        assertThat(exitOf(home, "printf 'replies\\n' | " + command))
                .as("the second round, the same file")
                .isZero();
        assertThat(exitOf(home, "test \"$(git -C " + repository + " rev-list --count HEAD)\" = 2"))
                .as("two commits: the head moved twice")
                .isZero();
    }

    private static int exitOf(Path home, String command) throws Exception {
        final ProcessBuilder builder =
                new ProcessBuilder("sh", "-c", command).redirectErrorStream(true);
        builder.environment().put("HOME", home.toString());
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        final Process process = builder.start();
        process.getInputStream().readAllBytes();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("ran").isTrue();
        return process.exitValue();
    }

    @Test
    void aProjectIsPlacedNamingTheScenariosKeyAsTheOneThatCertifiesItsTasks() throws Exception {
        final ProcessBuilder builder =
                new ProcessBuilder(
                                "sh",
                                "-c",
                                PullRequestReviewSteps.certified(
                                        PullRequestReviewSteps.placed("authored") + "true"))
                        .redirectErrorStream(true);
        builder.environment().put("HOME", home.toString());
        builder.environment().remove("XDG_STATE_HOME");
        builder.environment().remove("SSH_AUTH_SOCK");
        final Process process = builder.start();
        final String said =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("ran").isTrue();
        assertThat(process.exitValue()).as("exit, having said:%n%s", said).isZero();

        final Path places = home.resolve(".local/state/porelo/organization-projects");
        assertThat(Files.readSymbolicLink(places.resolve("authored.git")))
                .as("the placed project is the scenario's repository")
                .isEqualTo(home.resolve("authored"));
        final Map<?, ?> placed =
                (Map<?, ?>) Json.parse(Files.readString(places.resolve("authored.json")));
        final String key =
                String.join(
                        " ",
                        java.util.Arrays.asList(
                                        Files.readString(
                                                        home.resolve(
                                                                "porelo-acceptance-certifier/key.pub"))
                                                .strip()
                                                .split(" "))
                                .subList(0, 2));
        assertThat(placed.get("project")).as("the project").isEqualTo("authored");
        assertThat(((Map<?, ?>) ((List<?>) placed.get("signing_keys")).get(0)).get("key"))
                .as("the one key that may certify its tasks: the scenario's")
                .isEqualTo(key);
    }

    @Test
    void aStartRunsUnderAnAgentOfItsOwnHoldingTheScenariosKeySoItsTaskIsCertified()
            throws Exception {
        final Path key = home.resolve("porelo-acceptance-certifier/key");
        final Path agents = home.resolve("agents");

        final ProcessBuilder builder =
                new ProcessBuilder(
                                "sh",
                                "-c",
                                PullRequestReviewSteps.certified(
                                        "ssh-add -L | cut -d' ' -f3; echo \"$SSH_AGENT_PID\" >"
                                                + " \"$AGENTS\""))
                        .redirectErrorStream(true);
        builder.environment().put("HOME", home.toString());
        builder.environment().put("AGENTS", agents.toString());
        builder.environment().remove("SSH_AUTH_SOCK");
        final Process process = builder.start();
        final String said =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("ran").isTrue();

        assertThat(process.exitValue()).as("exit, having said:%n%s", said).isZero();
        assertThat(said)
                .as("the agent holds the scenario's key")
                .isEqualTo("porelo-acceptance-certifier");
        assertThat(key).as("the key, kept for the next start").isRegularFile();
        assertThat(home.resolve("porelo-stub-git-forge"))
                .as("the forge's folder, which a key in it would keep after the forge stopped")
                .doesNotExist();
        final java.util.Optional<ProcessHandle> agent =
                ProcessHandle.of(Long.parseLong(Files.readString(agents).strip()));
        final long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (agent.map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < until) {
            Thread.sleep(100);
        }
        assertThat(agent.map(ProcessHandle::isAlive).orElse(false))
                .as("the agent ended with its command")
                .isFalse();
    }

    @Test
    void anAuthorsPushIsAwaitedAtItsUpstreamBranchUntilItHoldsTheTasksHead() {
        assertThat(
                        PullRequestReviewSteps.arrivedCommand(
                                "porelo-shop_authored",
                                "\"$HOME/shop/.git/upstream.git\"",
                                "porelo/a"))
                .as("the task's head, then the upstream's branch compared to it")
                .startsWith(
                        "h=$(timeout 30 podman exec --user agent 'porelo-shop_authored'"
                                + " git -C /workspace rev-parse HEAD) || exit 1;")
                .contains("u=\"$HOME/shop/.git/upstream.git\";")
                .contains("rev-parse -q --verify 'refs/heads/porelo/a')\" = \"$h\" ] && exit 0")
                .endsWith("exit 1");
    }

    // Harm: a reviewer's held items read and decided at a container of another project, where
    // nothing is held, so a release the scenario shows never happens.
    @Test
    void theGatesCommandsNameATaskByItsContainerInTheProjectItWasStartedIn() {
        assertThat(PullRequestReviewSteps.containerOf("reviewer", "reviewed"))
                .as("a bare name is a task of the project the scenario gave its reviewers")
                .isEqualTo("porelo-reviewed_reviewer");
        assertThat(PullRequestReviewSteps.containerOf("reviewer", null))
                .as("a bare name where the scenario gave none is a task of the default project")
                .isEqualTo("porelo-default_reviewer");
        assertThat(PullRequestReviewSteps.containerOf("porelo-shop_authored", "reviewed"))
                .as("a container's name stays as it is")
                .isEqualTo("porelo-shop_authored");
    }

    @Test
    void aRunStopsTheTaskFirstWhateverItsState() throws Exception {
        final Path bin = Files.createDirectories(home.resolve("bin"));
        final Path calls = home.resolve("calls");
        final Path porelo = bin.resolve("porelo");
        Files.writeString(
                porelo,
                "#!/bin/sh\necho \"$*\" >> '"
                        + calls
                        + "'\nif [ \"$1 $2\" = 'task status' ]; then printf 'state      %s\\n'"
                        + " \"$STATE\"; fi\n");
        Files.writeString(bin.resolve("timeout"), "#!/bin/sh\nshift\nexec \"$@\"\n");
        porelo.toFile().setExecutable(true);
        bin.resolve("timeout").toFile().setExecutable(true);
        for (final String state : List.of("running", "stopped")) {
            Files.deleteIfExists(calls);
            final ProcessBuilder builder =
                    new ProcessBuilder(
                                    "sh",
                                    "-c",
                                    PullRequestReviewSteps.runCommand(
                                            "reviewer",
                                            "reviewed",
                                            "review",
                                            List.of("reply", "openThreads"),
                                            URL))
                            .redirectErrorStream(true);
            builder.environment().put("PATH", bin + ":" + System.getenv("PATH"));
            builder.environment().put("STATE", state);
            final Process shell = builder.start();
            shell.getOutputStream().close();
            final String said =
                    new String(shell.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(shell.waitFor(30, TimeUnit.SECONDS)).as("the run ended").isTrue();
            assertThat(shell.exitValue()).as("the run's exit while %s: %s", state, said).isZero();
            final List<String> called =
                    Files.readAllLines(calls).stream()
                            .map(line -> line.split(" ")[1] + " " + line.split(" ")[0])
                            .toList();
            assertThat(called)
                    .as("what porelo was asked while the task was %s", state)
                    .containsExactly("stop task", "start task");
        }
    }

    @Test
    void theTasksBaseIsTheGatesBranchWhereTheWorkspaceHasOne() throws Exception {
        final Path workspace = workspace(3);
        sh(workspace, "git update-ref refs/remotes/porelo/main HEAD~1");

        assertThat(taskBase(workspace))
                .as("the base beside porelo/main")
                .isEqualTo(sh(workspace, "git rev-parse HEAD~1"));
    }

    @Test
    void theTasksBaseIsItsFirstCommitWhereTheWorkspaceHasNoGateBranch() throws Exception {
        final Path workspace = workspace(3);

        assertThat(taskBase(workspace))
                .as("the base without porelo/HEAD or porelo/main")
                .isEqualTo(sh(workspace, "git rev-list --max-parents=0 HEAD"));
    }

    @Test
    void aGateBranchAtTheHeadIsNoBase() throws Exception {
        final Path workspace = workspace(2);
        sh(workspace, "git update-ref refs/remotes/porelo/main HEAD");

        assertThat(taskBase(workspace))
                .as("the base where porelo/main is the head itself")
                .isEqualTo(sh(workspace, "git rev-list --max-parents=0 HEAD"));
    }

    private Path workspace(int commits) throws Exception {
        final Path workspace = Files.createDirectories(home.resolve("workspace"));
        sh(workspace, "git init -q -b work .");
        for (int i = 1; i <= commits; i++) {
            sh(
                    workspace,
                    "git -c user.email=t@example.invalid -c user.name=t -c commit.gpgsign=false"
                            + " commit -q --allow-empty -m c"
                            + i);
        }
        return workspace;
    }

    private static String taskBase(Path workspace) throws Exception {
        return sh(
                workspace,
                PullRequestReviewSteps.TASK_BASE.replace(
                        "cd /workspace", "cd '" + workspace + "'"));
    }

    private static String sh(Path directory, String command) throws Exception {
        final ProcessBuilder builder =
                new ProcessBuilder("sh", "-c", command).directory(directory.toFile());
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        final Process process = builder.start();
        process.getOutputStream().close();
        final String out =
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("%s ended", command).isTrue();
        assertThat(process.exitValue()).as("%s exited", command).isZero();
        return out.strip();
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
    void aSubmissionKeepsItsLinesWholeNumbersSoTheMessageFilterTakesIt() {
        final String text = StubReview.submit("submitted-comment.sarif.json", URL, BASE, null);

        assertThat(text.substring(text.indexOf('\n') + 1))
                .as("the submission as the stub sends it: a line is a whole number, never 27.0")
                .contains("\"startLine\":27")
                .doesNotContainPattern("\"(start|end)Line\":[0-9]+\\.");
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
