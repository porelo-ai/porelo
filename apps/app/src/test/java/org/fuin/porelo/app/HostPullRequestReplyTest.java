package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.LongStream;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;

/** Tests the bound on the daemon's reply of a project's open reviews. */
class HostPullRequestReplyTest {

    @Test
    void aReplyAboveOneMebibyteButWithinOneMessageIsAnswered() {
        // Several thousand open reviews with long titles and trailers are a real project's.
        final var round = new HostPullRequestReader.Round(pulls(6_000, 300), List.of());

        final var reply = round.asMap();

        assertThat(Json.write(reply).getBytes(StandardCharsets.UTF_8).length)
                .as("the reply is above the earlier one MiB")
                .isGreaterThan(1024 * 1024);
        assertThat((List<?>) reply.get("pullRequests"))
                .as("every open review is answered")
                .hasSize(6_000);
    }

    @Test
    void aReplyThatCannotFitOneMessageIsRefused() {
        // Above the wire's message limit the reply could not be sent: refused, never cut.
        final var round = new HostPullRequestReader.Round(pulls(20_000, 300), List.of());

        assertThatThrownBy(round::asMap)
                .as("a reply above one message is refused")
                .isInstanceOf(HostPullRequestReader.Refused.class)
                .hasMessageContaining("size bound");
    }

    private static List<HostPullRequestReader.Pull> pulls(int count, int titleLength) {
        return LongStream.rangeClosed(1, count)
                .mapToObj(
                        n ->
                                new HostPullRequestReader.Pull(
                                        "repo",
                                        n,
                                        "t".repeat(titleLength),
                                        "a".repeat(40),
                                        "Author <author@example.org>",
                                        "Committer <committer@example.org>",
                                        List.of(),
                                        "open"))
                .toList();
    }
}
