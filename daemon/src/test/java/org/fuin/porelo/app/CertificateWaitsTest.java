package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Tests for a start waiting for its certificate from a person's own computer. */
class CertificateWaitsTest {

    private static final String KEY = "ssh-ed25519 AAAA";

    private static final Instant END = Instant.parse("2026-11-11T00:00:00Z");

    /** Records what a start asked, and says whether anyone is there. */
    private static final class Asked implements CertificateWaits.Asker {
        final CompletableFuture<Map<String, Object>> request = new CompletableFuture<>();
        volatile boolean open = true;

        @Override
        public void ask(final Map<String, Object> reply) {
            @SuppressWarnings("unchecked")
            final Map<String, Object> asked =
                    (Map<String, Object>) reply.get(StdinCertifier.REQUEST);
            request.complete(asked);
        }

        @Override
        public boolean open() {
            return open;
        }

        String start() throws Exception {
            return (String) request.get(5, TimeUnit.SECONDS).get("start");
        }
    }

    private static String certifying(final CertificateWaits waits, final Asked asked) {
        return waits.certifier(asked, List.of("ssh-ed25519 BBBB"))
                .certify(KEY, "task:foo:first", "porelo task first by alice", END);
    }

    @Test
    void aStartAsksWhatATerminalsStartAsksAndTakesTheAnswer() throws Exception {
        final CertificateWaits waits = new CertificateWaits();
        final Asked asked = new Asked();

        final CompletableFuture<String> line =
                CompletableFuture.supplyAsync(() -> certifying(waits, asked));

        assertThat(asked.request.get(5, TimeUnit.SECONDS))
                .containsEntry("key", KEY)
                .containsEntry("principal", "task:foo:first")
                .containsEntry("key_id", "porelo task first by alice")
                .containsEntry("valid_before", "2026-11-11T00:00:00Z")
                .containsEntry("certifiers", List.of("ssh-ed25519 BBBB"));
        assertThat(waits.certificate(asked.start(), "the line\n")).isTrue();
        assertThat(line.get(5, TimeUnit.SECONDS)).isEqualTo("the line");
        assertThat(waits.size()).as("nothing is left behind").isZero();
    }

    @Test
    void anIdNobodyWaitsByChangesNothing() throws Exception {
        final CertificateWaits waits = new CertificateWaits();
        final Asked asked = new Asked();
        final CompletableFuture<String> line =
                CompletableFuture.supplyAsync(() -> certifying(waits, asked));
        asked.start();

        assertThat(waits.certificate("no such start", "the line")).isFalse();
        assertThat(waits.refused("no such start", "no")).isFalse();

        assertThat(line).isNotDone();
        assertThat(waits.refused(asked.start(), "done")).isTrue();
        assertThatThrownBy(() -> line.get(5, TimeUnit.SECONDS)).hasMessageContaining("done");
    }

    @Test
    void anAnsweredIdIsSpent() throws Exception {
        final CertificateWaits waits = new CertificateWaits();
        final Asked asked = new Asked();
        final CompletableFuture<String> line =
                CompletableFuture.supplyAsync(() -> certifying(waits, asked));

        final String start = asked.start();
        assertThat(waits.certificate(start, "first")).isTrue();
        assertThat(waits.certificate(start, "second")).isFalse();
        assertThat(line.get(5, TimeUnit.SECONDS)).isEqualTo("first");
    }

    @Test
    void refusingEndsTheStartWithThosePersonsWordsOnOneBoundedLine() throws Exception {
        final CertificateWaits waits = new CertificateWaits();
        final Asked asked = new Asked();
        final CompletableFuture<String> line =
                CompletableFuture.supplyAsync(() -> certifying(waits, asked));

        waits.refused(asked.start(), "no key\n\u001b[31mhere " + "x".repeat(2000));

        assertThatThrownBy(() -> line.get(5, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(TaskCertification.Refused.class)
                .rootCause()
                .hasMessageContaining("did not certify task:foo:first: no key [31mhere")
                .hasMessageNotContaining("\n")
                .hasMessageNotContaining("\u001b")
                .satisfies(ex -> assertThat(ex.getMessage()).hasSizeLessThan(600));
    }

    @Test
    void aLineLongerThanACertificateEndsTheStart() throws Exception {
        final CertificateWaits waits = new CertificateWaits();
        final Asked asked = new Asked();
        final CompletableFuture<String> line =
                CompletableFuture.supplyAsync(() -> certifying(waits, asked));

        waits.certificate(asked.start(), "x".repeat(StdinCertifier.MAX_LINE + 1));

        assertThatThrownBy(() -> line.get(5, TimeUnit.SECONDS))
                .rootCause()
                .hasMessageContaining("longer than a certificate");
    }

    @Test
    void aWaitEndsAtItsLimitAndLeavesNothing() {
        final CertificateWaits waits = new CertificateWaits(Duration.ZERO, Clock.systemUTC());

        assertThatThrownBy(() -> certifying(waits, new Asked()))
                .isInstanceOf(TaskCertification.Refused.class)
                .hasMessageContaining("no certificate came for task:foo:first");
        assertThat(waits.size()).isZero();
    }

    @Test
    void aWaitEndsWhenItsStreamCloses() throws Exception {
        final CertificateWaits waits = new CertificateWaits();
        final Asked asked = new Asked();
        final CompletableFuture<String> line =
                CompletableFuture.supplyAsync(() -> certifying(waits, asked));
        asked.start();

        asked.open = false;

        assertThatThrownBy(() -> line.get(5, TimeUnit.SECONDS))
                .rootCause()
                .hasMessageContaining("went away");
        assertThat(waits.size()).isZero();
    }
}
