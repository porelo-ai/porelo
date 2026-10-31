package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link StdinCertifier}: the host asks the person's machine on one line and reads one.
 */
class StdinCertifierTest {

    private static final Instant BEFORE = Instant.parse("2026-11-07T00:00:00Z");

    @Test
    void theRequestIsOneLineAndTheAnswerTheNextLineOfInput() {
        final StringWriter asked = new StringWriter();
        final StdinCertifier certifier =
                new StdinCertifier(
                        new BufferedReader(
                                new StringReader("ssh-ed25519-cert-v01@openssh.com AAAA\nrest\n")),
                        new PrintWriter(asked),
                        List.of("ssh-ed25519 PERSON"));

        assertThat(
                        certifier.certify(
                                "ssh-ed25519 TASK",
                                "task:uc:fix",
                                "porelo task fix by alice",
                                BEFORE))
                .isEqualTo("ssh-ed25519-cert-v01@openssh.com AAAA");
        assertThat(asked.toString()).endsWith("\n").doesNotContain("\n\n");
        final Object line = Json.parse(asked.toString().strip());
        assertThat(line).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        final Map<String, Object> request =
                (Map<String, Object>) ((Map<?, ?>) line).get(StdinCertifier.REQUEST);
        assertThat(request)
                .containsEntry("key", "ssh-ed25519 TASK")
                .containsEntry("principal", "task:uc:fix")
                .containsEntry("key_id", "porelo task fix by alice")
                .containsEntry("valid_before", BEFORE.toString())
                .containsEntry("certifiers", List.of("ssh-ed25519 PERSON"));
    }

    @Test
    void noAnswerOrAnOverlongOneIsRefused() {
        assertThatThrownBy(
                        () ->
                                new StdinCertifier(
                                                new BufferedReader(new StringReader("")),
                                                new PrintWriter(new StringWriter()),
                                                List.of())
                                        .certify("k", "task:default:fix", "id", BEFORE))
                .isInstanceOf(TaskCertification.Refused.class)
                .hasMessageContaining("no certificate");
        assertThatThrownBy(
                        () ->
                                new StdinCertifier(
                                                new BufferedReader(
                                                        new StringReader(
                                                                "A".repeat(20_000) + "\n")),
                                                new PrintWriter(new StringWriter()),
                                                List.of())
                                        .certify("k", "task:default:fix", "id", BEFORE))
                .isInstanceOf(TaskCertification.Refused.class)
                .hasMessageContaining("longer");
    }
}
