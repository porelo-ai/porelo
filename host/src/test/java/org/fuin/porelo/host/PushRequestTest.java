package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.*;
import static org.fuin.porelo.host.PushJudgementTest.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Guards the hook's framing: ambiguous, truncated or oversized input must never authorize a push.
 */
class PushRequestTest {
    static final String REPOSITORY = "/var/lib/gitea/git/repositories/alpha/app.git";
    static final String QUARANTINE = REPOSITORY + "/objects/tmp_objdir-incoming-AbC123";
    static final String FIELDS =
            "repository "
                    + REPOSITORY
                    + "\nquarantine "
                    + QUARANTINE
                    + "\nobjects "
                    + QUARANTINE
                    + "\nalternates "
                    + REPOSITORY
                    + "/objects\npusher alice";
    static final String BODY = FIELDS + "\n\n" + line(OLD, NEXT, "refs/heads/main");

    static byte[] request(String body) {
        return raw(
                "POST /verify HTTP/1.1",
                "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n",
                body);
    }

    static byte[] raw(String first, String headers, String body) {
        return (first + "\r\n" + headers + "\r\n" + body).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void readsTheHooksCurlRequestWithoutInterpretingItsPathsOrPusher() {
        final var parsed =
                PushRequest.parse(
                        raw(
                                "POST /verify HTTP/1.1",
                                "Host: localhost\r\n"
                                        + "Content-Type: text/plain\r\ncOnTent-LENGTH: "
                                        + BODY.length()
                                        + "\r\n",
                                BODY));
        assertThat(parsed.fields())
                .as("only the five hook fields go to the installed adapter")
                .containsEntry("repository", REPOSITORY)
                .containsEntry("pusher", "alice")
                .hasSize(5);
        assertThat(parsed.lines())
                .as("verification sees exactly the hook's ref update")
                .isEqualTo(line(OLD, NEXT, "refs/heads/main"));
    }

    static Stream<Arguments> malformedRequests() {
        return Stream.of(
                Arguments.of(new byte[0], "the hook sent no complete request"),
                Arguments.of(
                        "POST /verify HTTP/1.1\r\n".getBytes(StandardCharsets.US_ASCII),
                        "the hook sent no complete request"),
                Arguments.of(
                        ("x".repeat(16385)).getBytes(StandardCharsets.US_ASCII),
                        "the hook sent no complete request"),
                Arguments.of(raw("GET /verify HTTP/1.1", "", ""), "the hook sent another request"),
                Arguments.of(
                        raw("POST /elsewhere HTTP/1.1", "", ""), "the hook sent another request"),
                Arguments.of(raw("POST /verify HTTP/1.0", "", ""), "the hook sent another request"),
                Arguments.of(
                        raw("POST /verify HTTP/1.1", "Transfer-Encoding: chunked\r\n", BODY),
                        "the hook's request has a malformed or chunked header"),
                Arguments.of(
                        raw("POST /verify HTTP/1.1", "Broken header\r\n", BODY),
                        "the hook's request has a malformed or chunked header"),
                Arguments.of(
                        raw(
                                "POST /verify HTTP/1.1",
                                "Content-Length: " + (BODY.length() + 1) + "\r\n",
                                BODY),
                        "the hook's request ended early"),
                Arguments.of(
                        raw(
                                "POST /verify HTTP/1.1",
                                "Content-Length: " + (BODY.length() - 1) + "\r\n",
                                BODY),
                        "the hook's request is longer than it says"),
                Arguments.of(
                        request(FIELDS + "\n" + line(OLD, NEXT, "refs/heads/main")),
                        "the hook's request has no line between its fields and its updates"),
                Arguments.of(
                        request(BODY.replace("pusher alice", "alien alice")),
                        "the hook's request names an unknown or repeated field"),
                Arguments.of(
                        request(BODY.replace("pusher alice", "pusher alice\npusher bob")),
                        "the hook's request names an unknown or repeated field"),
                Arguments.of(
                        request(BODY.replace("\npusher alice", "")),
                        "the hook's request lacks a field"));
    }

    @ParameterizedTest
    @MethodSource("malformedRequests")
    void malformedFramingRefusesWithItsOwnReason(byte[] bytes, String reason) {
        assertThatThrownBy(() -> PushRequest.parse(bytes))
                .as("the hook must not be parsed ambiguously")
                .isInstanceOf(HostRefusal.class)
                .hasMessage(reason);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "Content-Length: 0\r\n",
                "Content-Length: +5\r\n",
                "Content-Length: -1\r\n",
                "Content-Length: 1048577\r\n",
                "Content-Length: 10000000\r\n",
                "Content-Length: 1x\r\n",
                "Content-Length: 1\r\nContent-Length: 1\r\n"
            })
    void missingDuplicateNonDecimalAndOversizedLengthsRefuseBeforeAnyBodyIsRead(String header) {
        assertThatThrownBy(() -> PushRequest.parse(raw("POST /verify HTTP/1.1", header, BODY)))
                .as("a push length must be unambiguous and bounded")
                .isInstanceOf(HostRefusal.class)
                .hasMessage("the hook's request is not 1 to 1048576 bytes");
    }

    @Test
    void invalidUtf8DoesNotBecomeAReplacementCharacterInAPath() {
        final byte[] bytes = request(BODY);
        bytes[bytes.length - 1] = (byte) 0xff;
        assertThatThrownBy(() -> PushRequest.parse(bytes))
                .as("invalid bytes cannot name a different repository")
                .isInstanceOf(HostRefusal.class)
                .hasMessage("the hook's request is not UTF-8");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "not an update\n",
                "\n",
                "a b refs/heads/main\n",
                "OLD NEW refs/heads/main\n"
            })
    void emptyOrMalformedPushesNeverGetTheDeletionShortcut(String updates) {
        assertThatThrownBy(() -> PushRequest.parse(request(FIELDS + "\n\n" + updates)))
                .as("an empty push must not pass as a push containing only deletions")
                .isInstanceOf(HostRefusal.class)
                .hasMessage("the hook's updates are not 1 to 10000 lines <old> <new> <ref>");
    }

    @Test
    void anExtraEmptyUpdateLineIsNotIgnored() {
        assertThatThrownBy(() -> PushRequest.parse(request(BODY + "\n")))
                .as("the host and verifier must agree on every ref line")
                .hasMessage("the hook's updates are not 1 to 10000 lines <old> <new> <ref>");
    }

    @Test
    void limitsTheNumberOfRefsAndEachRefName() {
        for (String updates :
                List.of(
                        line(OLD, NEXT, "refs/" + "a".repeat(1001)),
                        line(OLD, NEXT, "refs/heads/main").repeat(10001))) {
            assertThatThrownBy(() -> PushRequest.updates(updates))
                    .as("ref floods must stop before verification")
                    .hasMessage("the hook's updates are not 1 to 10000 lines <old> <new> <ref>");
        }
    }

    @Test
    void acceptsBothGitObjectFormatsZeroShasAndMultipleRefs() {
        final var parsed =
                PushRequest.parse(
                        request(
                                FIELDS
                                        + "\n\n"
                                        + line(ZERO, NEXT, "refs/heads/new")
                                        + line("c".repeat(64), "0".repeat(64), "refs/tags/old")));
        assertThat(parsed.updates()).as("creation and deletion can share a push").hasSize(2);
        assertThat(parsed.updates().getFirst().deletion())
                .as("zero old SHA means creation")
                .isFalse();
        assertThat(parsed.updates().getLast().deletion())
                .as("zero new SHA means deletion")
                .isTrue();
    }

    @Test
    void aMissingFinalNewlineIsNormalizedForTheVerifier() {
        assertThat(PushRequest.parse(request(BODY.stripTrailing())).lines())
                .as("the verifier receives complete update lines")
                .isEqualTo(line(OLD, NEXT, "refs/heads/main"));
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 403, 500})
    void answersWithTheHooksExactHttpFramingAndUtf8ByteLength(int status) {
        final String text = "porelo: café\n";
        final var bytes = new PushJudgement.Answer(status, text).http();
        assertThat(new String(bytes, StandardCharsets.UTF_8))
                .as("curl reads the status and body byte for byte")
                .isEqualTo(
                        "HTTP/1.1 "
                                + status
                                + " "
                                + GateHttp.reason(status)
                                + "\r\nContent-Type: text/plain\r\nContent-Length: 14\r\nConnection: close\r\n\r\n"
                                + text);
    }

    // Prevent a trickling hook from retaining the host's shared lock indefinitely.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void oneDeadlineCoversTheHeaderAndBodyEvenWhenEveryReadMakesProgress(boolean inHeader) {
        final byte[] bytes = request(BODY);
        final var now = new java.util.concurrent.atomic.AtomicLong();
        final int header =
                new String(bytes, java.nio.charset.StandardCharsets.UTF_8).indexOf("\r\n\r\n") + 4;
        final var input =
                new java.io.ByteArrayInputStream(bytes) {
                    @Override
                    public synchronized int read(byte[] sink, int offset, int length) {
                        if (pos == 0 && !inHeader) return super.read(sink, offset, header);
                        now.addAndGet(java.time.Duration.ofSeconds(11).toNanos());
                        return super.read(sink, offset, Math.min(length, inHeader ? 16 : 128));
                    }
                };
        assertThatThrownBy(() -> PushRequest.read(input, now::get))
                .as("progress cannot renew the whole request's 30 second deadline")
                .isInstanceOf(java.net.SocketTimeoutException.class);
    }
}
