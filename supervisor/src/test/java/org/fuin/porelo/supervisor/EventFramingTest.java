package org.fuin.porelo.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests for {@link EventFraming}. */
class EventFramingTest {

    /** Feeds the reads in turn and ends the answer, returning everything that was passed on. */
    private static String forward(final String... reads) throws EventFraming.Refused {
        final EventFraming framing = new EventFraming();
        final ByteArrayOutputStream passed = new ByteArrayOutputStream();
        for (final String read : reads) {
            final byte[] bytes = read.getBytes(StandardCharsets.UTF_8);
            passed.writeBytes(framing.pass(bytes, bytes.length));
        }
        passed.writeBytes(framing.end());
        return passed.toString(StandardCharsets.UTF_8);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "event: response.created\ndata: {\"delta\":\"an access_token: field\"}\n\n",
                ": keep-alive\n\ndata: {\"ok\":true}\r\n\r\n",
                "id: 7\nretry: 1000\ndata:[{\"a\":1}]\n\n",
                "data: [DONE]\n\n",
                "data:  {\"ok\":true}\n\n",
                "data\n\ndata:\n\n",
                "﻿data: {}\n\n"
            })
    void aStreamOfEventsCarryingJsonPassesUnchanged(final String stream) throws Exception {

        // What providers send: every line an event field, every data line JSON. The JSON is the
        // credential scan's to read, so a model writing about a token field is not refused here.
        assertThat(forward(stream)).as(stream).isEqualTo(stream.replace("﻿", ""));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "event: auth\ndata: access_token=sk-live-REAL\n\n",
                ": note\naccess_token=sk-live-REAL",
                "id: 1\nrefresh_token: sk-live-REAL\n",
                "data: {}\n  data: {}\n",
                ": token is access%5Ftoken=sk-live-REAL\n",
                ": the refresh_token is sk-live-REAL\n",
                "event: ID_TOKEN\n",
                "data: {access_token: sk-live-REAL}\n\n",
                "data: [default] access_token=sk-live-REAL\n\n"
            })
    void aStreamWithALineThatIsNotAnEventOfJsonIsRefused(final String stream) {

        // An untyped answer that opens like events says nothing about its other lines: a form
        // value, YAML or a token in a comment reaches no credential scan, so the framing decides.
        assertThatThrownBy(() -> forward(stream))
                .as(stream)
                .isInstanceOf(EventFraming.Refused.class);
    }

    @Test
    void nothingOfALineIsPassedOnBeforeItIsJudged() throws Exception {

        // Measured in review: judged only at its line break, a comment was already forwarded by the
        // read that ended before it, so the refusal came after the token had crossed.
        final EventFraming framing = new EventFraming();
        final byte[] head =
                "event: ok\n: access_token=sk-live-REAL".getBytes(StandardCharsets.UTF_8);
        final String passed = new String(framing.pass(head, head.length), StandardCharsets.UTF_8);
        assertThat(passed)
                .as("the judged line goes, the open one is held")
                .isEqualTo("event: ok\n");
        final byte[] rest = "\n".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> framing.pass(rest, rest.length))
                .isInstanceOf(EventFraming.Refused.class);
    }

    @Test
    void aLineTheAnswerEndsInIsJudgedAtTheEnd() {

        // With no line break after it, a comment would otherwise never be judged at all.
        assertThatThrownBy(() -> forward("data: {}\n\n: access_token=sk-live-REAL"))
                .isInstanceOf(EventFraming.Refused.class);
    }

    @Test
    void aDataLineIsPassedOnAsItArrivesOnceItsJsonHasBegun() throws Exception {

        // A long completion is one data line; holding it to its end would deliver it in one piece,
        // which reads as an agent that hung.
        final EventFraming framing = new EventFraming();
        final byte[] head = "data: {\"delta\":\"lo".getBytes(StandardCharsets.UTF_8);
        assertThat(new String(framing.pass(head, head.length), StandardCharsets.UTF_8))
                .isEqualTo("data: {\"delta\":\"lo");
    }

    @Test
    void aHeldLineTooLongToBeAFieldIsRefused() {

        // A comment or an event name has no reason to be long, and every byte of it is held.
        assertThatThrownBy(() -> forward(": " + "x".repeat(5000) + "\n"))
                .isInstanceOf(EventFraming.Refused.class);
    }

    @Test
    void aDataLineLongerThanAHeldLineMayBeStillPasses() throws Exception {

        // The limit is for what is held; a completion's data line is streamed and may be any
        // length.
        final String stream = "data: {\"delta\":\"" + "x".repeat(10_000) + "\"}\n\n";
        assertThat(forward(stream)).isEqualTo(stream);
    }
}
