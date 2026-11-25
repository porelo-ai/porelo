package org.fuin.porelo.build.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ReviewRecordsTest {

    private static final String SHA = "a".repeat(40);

    @Test
    void factsAsTheContractAnswersThemAreRead() {
        assertThat(ReviewFacts.of(facts(Map.of())))
                .as("open, at a full commit, on an https repository")
                .isEqualTo(
                        new ReviewFacts(
                                "https://forge.example/o/n",
                                SHA,
                                SHA,
                                SHA,
                                ReviewFacts.State.OPEN,
                                false,
                                null,
                                null));
        assertThat(
                        ReviewFacts.of(
                                        facts(
                                                Map.of(
                                                        "state",
                                                        "merged",
                                                        "mergeCommit",
                                                        SHA,
                                                        "mergedHead",
                                                        "release/2.x")))
                                .mergedHead())
                .as("a branch with a slash")
                .isEqualTo("release/2.x");
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "repository|http://forge.example/o/n",
                "repository|https://user:pass@forge.example/o/n",
                "repository|https://forge.example/n",
                "repository|https://forge.example/o/n?x=1",
                "repository|https://forge.example/o/..",
                "head|abc",
                "base|" + "A" + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "state|pending",
                "approved|yes",
                "mergeCommit|" + "dddddddddddddddddddddddddddddddddddddddd"
            })
    void factsOutOfShapeAreRefused(final String field, final String value) {
        assertThatThrownBy(() -> ReviewFacts.of(facts(Map.of(field, value))))
                .as("%s %s", field, value)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "main\nstatus: ok",
                "main..x",
                "-main",
                "main.lock",
                ".hidden",
                "a//b",
                "a b",
                "x@{1}",
                "@",
                "end/",
                "end."
            })
    void aMergedBranchThatGitWouldRefuseIsRefused(final String branch) {
        assertThatThrownBy(
                        () ->
                                ReviewFacts.of(
                                        facts(
                                                Map.of(
                                                        "state",
                                                        "merged",
                                                        "mergeCommit",
                                                        SHA,
                                                        "mergedHead",
                                                        branch))))
                .as("%s is no branch", branch)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMergedPullRequestNamesItsMergeAndAnOpenOneNone() {
        assertThatThrownBy(() -> ReviewFacts.of(facts(Map.of("state", "merged"))))
                .as("merged without its merge")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                ReviewFacts.of(
                                        facts(Map.of("mergeCommit", SHA, "mergedHead", "main"))))
                .as("open with a merge")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPageIsUtf8AndItsCursorBounded() {
        assertThatThrownBy(() -> new ConversationPage(new byte[] {(byte) 0xc3}, null))
                .as("bytes that are not UTF-8")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConversationPage.of("{}", "x".repeat(257)))
                .as("a cursor past 256 characters")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConversationPage.of("{}", "a\u0000b"))
                .as("a cursor with a control character")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ConversationPage.of("{}", "x".repeat(256)).cursor())
                .as("a cursor of 256 characters")
                .hasSize(256);
    }

    @Test
    void aRefusalWithoutAReasonOrFieldSaysWhichIsMissing() {
        assertThatThrownBy(() -> ForgeReviewRefused.notInProfile(null))
                .as("no field")
                .isInstanceOf(NullPointerException.class)
                .hasMessage("field");
        assertThatThrownBy(() -> new ForgeReviewRefused(null))
                .as("no reason")
                .isInstanceOf(NullPointerException.class)
                .hasMessage("reason");
    }

    private static Map<String, Object> facts(final Map<String, Object> changed) {
        final Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("repository", "https://forge.example/o/n");
        facts.put("head", SHA);
        facts.put("base", SHA);
        facts.put("mergeBase", SHA);
        facts.put("state", "open");
        facts.put("approved", Boolean.FALSE);
        facts.putAll(changed);
        return facts;
    }
}
