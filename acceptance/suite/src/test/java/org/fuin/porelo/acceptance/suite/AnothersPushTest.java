package org.fuin.porelo.acceptance.suite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;

class AnothersPushTest {

    private static final String BASE = "0123456789abcdef0123456789abcdef01234567";

    private static final String HEAD = "89abcdef0123456789abcdef0123456789abcdef";

    @Test
    void everyStepOfTheReadingFeatureHasItsGlue() {
        final byte exit =
                io.cucumber.core.cli.Main.run(
                        new String[] {
                            "--dry-run",
                            "--glue",
                            "org.fuin.porelo.acceptance",
                            "--plugin",
                            "summary",
                            "classpath:org/fuin/porelo/acceptance/gate/reading-anothers-push.feature"
                        },
                        Thread.currentThread().getContextClassLoader());

        assertThat(exit)
                .as(
                        "a dry run of reading-anothers-push.feature finds an undefined or ambiguous"
                                + " step")
                .isZero();
    }

    @Test
    void theCommentingFileNamesThePushsRunAndALineThePushedWorkHas() {
        final String text = GatePushReview.of("comments", "shop", "author");

        assertThat(text.lines().findFirst())
                .as("the file's first line")
                .contains("submit gate-comment");
        final Map<?, ?> run = run(filled(body(text)));
        assertThat(run.get("properties"))
                .as("the run's properties")
                .isEqualTo(
                        Map.of(
                                "porelo/project", "shop",
                                "porelo/repository", "shop",
                                "porelo/push", "author",
                                "porelo/baseRevisionId", BASE));
        final Map<?, ?> provenance =
                (Map<?, ?>) ((List<?>) run.get("versionControlProvenance")).get(0);
        assertThat(provenance.get("repositoryUri")).isEqualTo("urn:porelo:repository:shop:shop");
        assertThat(provenance.get("revisionId")).isEqualTo(HEAD);
        final Map<?, ?> comment = (Map<?, ?>) ((List<?>) run.get("results")).get(0);
        assertThat(comment.containsKey("fixes")).as("a comment proposes no fix").isFalse();
        assertThat(body(text))
                .as("a line is a whole number, as the message filter takes it")
                .contains("\"uri\":\"notes.txt\"")
                .contains("\"startLine\":3")
                .doesNotContainPattern("\"(start|end)Line\":[0-9]+\\.");
    }

    @Test
    void theReplyingFileHoldsTheReplyOnTheOneLineTheStubRepeats() {
        final String text = GatePushReview.of("replies", "shop", "author");
        final List<String> lines = text.lines().toList();

        assertThat(lines.getFirst()).isEqualTo("replies");
        assertThat(lines.stream().filter(line -> line.contains(StubReview.THREAD)))
                .as("the lines naming the thread's place")
                .hasSize(1);
        final Map<?, ?> reply =
                (Map<?, ?>)
                        ((List<?>)
                                        run(filled(body(text)).replace(StubReview.THREAD, "t-1"))
                                                .get("results"))
                                .get(0);
        assertThat(((Map<?, ?>) reply.get("properties")).get("porelo/replyTo")).isEqualTo("t-1");
    }

    @Test
    void theResolvingFileNamesEveryOpenThreadOnTheOneLineTheStubRepeatsAndHasNoResult() {
        final String text = GatePushReview.of("resolves", "shop", "author");

        assertThat(text.lines().filter(line -> line.contains(StubReview.THREAD)))
                .containsExactly("\"" + StubReview.THREAD + "\"");
        final Map<?, ?> run = run(filled(body(text)).replace(StubReview.THREAD, "t-1"));
        assertThat(((Map<?, ?>) run.get("properties")).get("porelo/resolveThreads"))
                .isEqualTo(List.of("t-1"));
        assertThat((List<?>) run.get("results")).as("a resolve alone has no result").isEmpty();
    }

    @Test
    void theRejectingFileHoldsOneJudgmentWithTheReasonAndNoLocation() {
        final Map<?, ?> run = run(filled(body(GatePushReview.of("rejects", "shop", "author"))));
        final List<?> results = (List<?>) run.get("results");

        assertThat(results).hasSize(1);
        final Map<?, ?> reject = (Map<?, ?>) results.get(0);
        assertThat(reject.containsKey("locations")).as("a reject's location").isFalse();
        assertThat(((Map<?, ?>) reject.get("properties")).get("porelo/judgment"))
                .isEqualTo("reject");
        assertThat(((Map<?, ?>) reject.get("message")).get("text"))
                .isEqualTo(GatePushReview.REASON);
    }

    @Test
    void aReviewOfAnotherKindIsRefused() {
        assertThatThrownBy(() -> GatePushReview.of("approves", "shop", "author"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aReaderIsStartedAtThePushWithItsRightsAndAPromptQuoted() {
        assertThat(
                        AnothersPushSteps.startCommand(
                                "reader", "shop", "author", List.of("reply", "openThreads"), true))
                .contains("porelo task start 'reader' --project 'shop' --repository 'shop'")
                .contains(
                        "--agent stub --right 'reply' --right 'openThreads' --place 'push:author'")
                .endsWith("--clearance deny --prompt review");
        assertThat(AnothersPushSteps.startCommand("reader", "shop", "author", List.of(), true))
                .as("a start naming no right")
                .doesNotContain("--right");
    }

    @Test
    void aReaderLeftRunningIsDetachedWithNoRunOfItsAgent() {
        assertThat(
                        AnothersPushSteps.startCommand(
                                "reader", "shop", "author", List.of("reply"), false))
                .contains("--right 'reply' --place 'push:author'")
                .endsWith("--detach --clearance deny")
                .doesNotContain("--prompt");
    }

    @Test
    void aReadersNextRunKeepsItsTaskProjectRightsAndWaitingPush() {
        assertThat(
                        AnothersPushSteps.runCommand(
                                "reader", "shop", "author", List.of("reply", "openThreads")))
                .startsWith("porelo task stop 'porelo-shop_reader'")
                .endsWith(
                        "porelo task start 'reader' --project 'shop' --repository 'shop'"
                                + " --agent stub --right 'reply' --right 'openThreads'"
                                + " --place 'push:author' --clearance deny --prompt review")
                .doesNotContain("ssh-agent", "--write-as", "task remove");
    }

    @Test
    void theDevelopersAnswerNamesTheReviewThreadAndNoPeerTransport() {
        final Map<?, ?> message =
                (Map<?, ?>)
                        Json.parse(
                                AnothersPushSteps.replyMessage(
                                        "answer-1", "thread-1", "Three follows two."));

        assertThat(message.get("messageId")).isEqualTo("answer-1");
        assertThat(message.get("role")).isEqualTo("ROLE_AGENT");
        assertThat(message.get("metadata"))
                .isEqualTo(Map.of("to", "review", "kind", "comment", "thread", "thread-1"));
        assertThat(message.get("parts")).isEqualTo(List.of(Map.of("text", "Three follows two.")));
    }

    @Test
    void theWholeWaitingPushConversationIncludesTheOpeningAndTheDevelopersAnswer() {
        final String page =
                """
                {"runs":[{"results":[
                  {"message":{"text":"Why three?"},"properties":{"porelo/threadId":"thread-1"}},
                  {"message":{"text":"Three follows two."},"properties":{"porelo/threadId":"thread-1"}}
                ]}]}
                """;

        assertThat(AnothersPushSteps.conversationItems(page))
                .containsExactly("thread-1", "Why three?", "thread-1", "Three follows two.");
        assertThatThrownBy(
                        () -> AnothersPushSteps.conversationItems("{\"runs\":[{\"results\":[]}]}"))
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void aThreadsItemsAreReadInOrderWithTheirThreadAndTheirTask() {
        // The step judges one thread and its order; an item of the owner account has no task id.
        final String page =
                """
                {"runs":[{"results":[
                  {"message":{"text":"Why three?"},"properties":{"porelo/threadId":"t-1","porelo/taskId":"porelo-p_reviewer"}},
                  {"message":{"text":"Because."},"properties":{"porelo/threadId":"t-1","porelo/taskId":"p"}},
                  {"message":{"text":"Why two?"},"properties":{"porelo/threadId":"t-2"}}
                ]}]}
                """;

        assertThat(AnothersPushSteps.threadItems(page))
                .containsExactly(
                        List.of("t-1", "porelo-p_reviewer", "Why three?"),
                        List.of("t-1", "p", "Because."),
                        List.of("t-2", "owner account", "Why two?"));
    }

    @Test
    void aNameThatCouldEndAnArgumentIsRefused() {
        assertThatThrownBy(
                        () ->
                                AnothersPushSteps.startCommand(
                                        "x'; rm -rf ~", "shop", "a", List.of(), true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // Harm: a reader's push read as refused by its gate when it never left its clone, so the gate's
    // own refusal is never shown, or a push that leaves its words unprinted.
    @Test
    void aReadersPushesGoThroughItsCloneAndStraightToItsGateEachSayingWhy() {
        final String clone = AnothersPushSteps.clonePushCommand("porelo-shop_reader");
        final String gate = AnothersPushSteps.gatePushCommand("porelo-shop_reader");

        assertThat(clone)
                .as("the push the clone makes, to its remote porelo")
                .startsWith("timeout 60 podman exec --user agent 'porelo-shop_reader' sh -c ")
                .contains("commit -q --allow-empty")
                .contains("git push porelo HEAD:")
                .as("a reader has no task ref, so its push must still name one to reach the URL")
                .contains("${PORELO_TASK_REF:-")
                .doesNotContain("push -q", "$PORELO_REMOTE_URL");
        assertThat(gate)
                .as("the push straight to the gate's address, past the clone's push URL")
                .startsWith("timeout 60 podman exec --user agent 'porelo-shop_reader' sh -c ")
                .contains("git push \"$PORELO_REMOTE_URL\" HEAD:")
                .contains("${PORELO_TASK_REF:-")
                .doesNotContain("push -q", "push porelo");
        assertThatThrownBy(() -> AnothersPushSteps.gatePushCommand("a b"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void thePushFillsInItsBaseAndPrintsItsHead() {
        final String command = AnothersPushSteps.pushCommand("porelo-shop_author");

        assertThat(command)
                .startsWith("timeout 120 podman exec -i --user agent 'porelo-shop_author' sh -c ")
                .contains("b=$(git rev-parse HEAD)")
                .contains("s/" + GatePushReview.BASE + "/$b/g")
                .contains("git push -q porelo HEAD:\"$PORELO_TASK_REF\"")
                .endsWith("git rev-parse HEAD'");
    }

    @Test
    void aMailboxTextAndACommitAreHandedInAsVariablesNeverPasted() {
        assertThat(AnothersPushSteps.toldCommand("porelo-shop_reader", "it's refused", 60))
                .contains("-e T='it'\\''s refused'")
                .contains("grep -qF -- \"$T\"")
                .endsWith("exit 1");
        assertThat(AnothersPushSteps.fetchCommand("porelo-shop_reader", HEAD))
                .contains("-e H='" + HEAD + "'")
                .contains("git fetch -q porelo \"$H\"");
    }

    private static String body(String text) {
        return text.substring(text.indexOf('\n') + 1);
    }

    private static String filled(String body) {
        return body.replace(GatePushReview.BASE, BASE).replace(StubReview.HEAD, HEAD);
    }

    private static Map<?, ?> run(String document) {
        final Map<?, ?> root = (Map<?, ?>) Json.parse(document);
        return (Map<?, ?>) ((List<?>) root.get("runs")).get(0);
    }
}
