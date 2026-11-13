package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.junit.jupiter.api.Test;

/** Test for {@link ReviewCut}: a review fitted to a bound, at file boundaries, or refused. */
class ReviewCutTest {

    private static final List<String> COMMAND =
            List.of("porelo", "gate", "review", "-p", "p", "-r", "r", "--", "t");

    private static String part(String path, String line, int lines) {
        return "diff --git a/"
                + path
                + " b/"
                + path
                + "\n--- a/"
                + path
                + "\n+++ b/"
                + path
                + "\n@@ -0,0 +1,"
                + lines
                + " @@\n"
                + ("+" + line + "\n").repeat(lines);
    }

    private static Map<String, Object> reply(String diff) {
        final Map<String, Object> reply = new LinkedHashMap<>();
        reply.put("diff", diff);
        reply.put("log", "abc first\n");
        reply.put("files", List.of(Map.of("path", "a.txt"), Map.of("path", "b.txt")));
        return reply;
    }

    @Test
    void aReplyThatFitsIsSentAsItIs() {
        final Map<String, Object> reply = reply(part("a.txt", "x", 3));

        assertThat(ReviewCut.fitted(reply, "t", COMMAND, 1_000_000)).isSameAs(reply);
    }

    @Test
    void everyLimitGetsAReplyWithinItCutAtAFileBoundaryOrARefusal() {
        // Escapes in the content and in a path: the size is the JSON's, not the text's.
        final String a = part("a.txt", "\u0001\"\\ quoted", 20);
        final String b = part("b é.txt", "plain", 20);
        final String c = part("c.txt", "\t", 20);
        final String whole = a + b + c;
        final Map<String, Object> reply = reply(whole);
        final int full = ReviewCut.bytes(reply);
        int refused = 0;
        for (int limit = 100; limit < full; limit += 7) {
            final Map<String, Object> fitted;
            try {
                fitted = ReviewCut.fitted(reply, "t", COMMAND, limit);
            } catch (VarlinkException ex) {
                assertThat(ex.getErrorName()).isEqualTo(PoreloDaemon.INTERFACE + ".ReviewTooLarge");
                assertThat(ex.getParameters())
                        .containsEntry("limit", limit)
                        .containsEntry("command", COMMAND);
                assertThat((int) ex.getParameters().get("bytes")).isGreaterThan(limit);
                refused++;
                continue;
            }
            assertThat(ReviewCut.bytes(fitted)).as("within %d", limit).isLessThanOrEqualTo(limit);
            final String diff = String.valueOf(fitted.get("diff"));
            assertThat(List.of("", a, a + b)).as("whole files only").contains(diff);
            final Map<?, ?> cut = (Map<?, ?>) fitted.get("cut");
            final int shown = (int) cut.get("shownFiles");
            assertThat(cut.get("omittedFiles")).isEqualTo(3 - shown);
            assertThat(cut.get("omitted"))
                    .isEqualTo(List.of("a.txt", "b é.txt", "c.txt").subList(shown, 3));
            assertThat(cut.get("command")).isEqualTo(COMMAND);
            assertThat(fitted.get("files"))
                    .as("the list is never shortened")
                    .isEqualTo(reply.get("files"));
            assertThat(fitted.get("log")).isEqualTo(reply.get("log"));
        }
        assertThat(refused).as("the smallest limits cannot hold the list").isPositive();
        assertThat(ReviewCut.fitted(reply, "t", COMMAND, full)).doesNotContainKey("cut");
    }

    @Test
    void aSingleFileLargerThanTheLimitIsCutWithNoneShown() {
        final Map<String, Object> reply = reply(part("a.txt", "x".repeat(100), 100));

        final Map<String, Object> fitted = ReviewCut.fitted(reply, "t", COMMAND, 2_000);

        assertThat(fitted.get("diff")).isEqualTo("");
        assertThat(fitted.get("cut"))
                .isEqualTo(
                        Map.of(
                                "shownFiles",
                                0,
                                "omittedFiles",
                                1,
                                "omitted",
                                List.of("a.txt"),
                                "command",
                                COMMAND));
    }

    @Test
    void whatDoesNotFitWithoutAnyPatchIsRefusedByName() {
        final Map<String, Object> reply = reply(part("a.txt", "x", 3));
        reply.put("log", "y".repeat(5_000));

        assertThatThrownBy(() -> ReviewCut.fitted(reply, "t", COMMAND, 2_000))
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        ex -> {
                            assertThat(ex.getErrorName())
                                    .isEqualTo(PoreloDaemon.INTERFACE + ".ReviewTooLarge");
                            assertThat(ex.getParameters()).containsEntry("name", "t");
                        });
    }
}
