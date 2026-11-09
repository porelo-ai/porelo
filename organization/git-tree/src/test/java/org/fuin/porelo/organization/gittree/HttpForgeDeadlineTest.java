package org.fuin.porelo.organization.gittree;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HttpForgeDeadlineTest {
    private static final char[] TOKEN = "fixture-private-token".toCharArray();

    @Test
    void anExpiredOperationStartsNoRequest() {
        final AtomicInteger called = new AtomicInteger();
        final Instant now = Instant.parse("2026-11-09T00:00:00Z");
        try (var forge =
                new HttpForgeAdmin(
                                (method, path, authorization, body) -> {
                                    called.incrementAndGet();
                                    throw new AssertionError("expired");
                                },
                                TOKEN,
                                false)
                        .until(Clock.fixed(now, ZoneOffset.UTC), now)) {
            assertThatThrownBy(() -> forge.request("GET", "/orgs/alpha", null, 200))
                    .isInstanceOf(HttpForgeAdmin.Refusal.class)
                    .hasMessageNotContaining("fixture-private-token");
            assertThat(called).hasValue(0);
        }
    }

    @Test
    void aStalledRequestIsInterruptedAtTheEnclosingDeadline() throws Exception {
        final CountDownLatch interrupted = new CountDownLatch(1);
        final Instant now = Instant.now();
        try (var forge =
                new HttpForgeAdmin(
                                (method, path, authorization, body) -> {
                                    try {
                                        Thread.sleep(10000);
                                        throw new AssertionError("deadline did not interrupt");
                                    } catch (InterruptedException ex) {
                                        interrupted.countDown();
                                        throw new IOException("interrupted");
                                    }
                                },
                                TOKEN,
                                false)
                        .until(Clock.fixed(now, ZoneOffset.UTC), now.plusSeconds(1))) {
            assertThatThrownBy(() -> forge.request("GET", "/orgs/alpha", null, 200))
                    .isInstanceOf(HttpForgeAdmin.Refusal.class);
            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void nestedBudgetsNeverExtendTheExistingDeadline() {
        final Instant now = Instant.parse("2026-11-09T00:00:00Z");
        final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        try (var forge =
                new HttpForgeAdmin(
                                (method, path, authorization, body) -> {
                                    throw new AssertionError("extended");
                                },
                                TOKEN,
                                false)
                        .until(clock, now)
                        .until(clock, now.plusSeconds(60))) {
            assertThatThrownBy(() -> forge.request("GET", "/orgs/alpha", null, 200))
                    .isInstanceOf(HttpForgeAdmin.Refusal.class);
        }
    }
}
