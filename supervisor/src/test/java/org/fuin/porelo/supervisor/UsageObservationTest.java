package org.fuin.porelo.supervisor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Prevents slow or failed observation from blocking the answer. Inline answer fragments are written
 * from provider documentation, with synthetic values; no live answer is recorded yet.
 */
class UsageObservationTest {
    @Test
    void aParserThatFallsBehindDropsCountsInsteadOfWaiting() {
        final var jobs = new ArrayList<Runnable>();
        final var result = new AtomicReference<Map<String, Object>>();
        final var reading = new UsageReading("/responses", new byte[0], false, true);
        final var observation = new UsageObservation(reading, result::set, jobs::add);
        observation.start(true, 200);
        final byte[] event =
                "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":7}}}\n\n"
                        .getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 1000; i++) observation.accept(event, event.length);
        observation.finish(true);
        assertThat(result.get())
                .as("the relay completes without running the observer or waiting for disk")
                .isNull();
        assertThat(jobs).as("one bounded observer serves the whole answer").hasSize(1);
        jobs.getFirst().run();
        assertThat(result.get().get("input")).as("lost events invalidate all counts").isNull();
    }

    @Test
    void aFailedRecordWriterCannotFailTheRelay() {
        final var jobs = new ArrayList<Runnable>();
        final var reading = new UsageReading("/responses", new byte[0], false, true);
        final var observation =
                new UsageObservation(
                        reading,
                        line -> {
                            throw new IllegalStateException("PRIVATE_ERROR");
                        },
                        jobs::add);
        observation.start(false, 200);
        final byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        observation.accept(body, body.length);
        observation.finish(true);
        jobs.getFirst().run();
        assertThat(jobs).as("record failure is isolated in the worker").hasSize(1);
    }

    @Test
    void shutdownFinishesAnInFlightObservationBeforeItsProcessExits() {
        final var jobs = new ArrayList<Runnable>();
        final var result = new AtomicReference<Map<String, Object>>();
        final var observation =
                new UsageObservation(
                        new UsageReading("/responses", new byte[0], false, true),
                        result::set,
                        jobs::add);
        observation.start(true, 200);
        observation.finish(false);
        jobs.getFirst().run();
        observation.awaitUntil(
                System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(1));
        assertThat(result.get().get("end"))
                .as("shutdown retains an aborted record with unknown counts")
                .isEqualTo("aborted");
    }

    @Test
    void aBurstOfAnOrdinaryAnswerIsNotLostBeforeTheObserverRuns() {
        // A provider's answer arrives faster than a scheduler wakes a thread: counts lost to that
        // would be unknown for no reason.
        final var jobs = new ArrayList<Runnable>();
        final var result = new AtomicReference<Map<String, Object>>();
        final var reading = new UsageReading("/responses", new byte[0], false, true);
        final var observation = new UsageObservation(reading, result::set, jobs::add);
        observation.start(true, 200);
        final byte[] event =
                "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":7}}}\n\n"
                        .getBytes(StandardCharsets.UTF_8);

        for (int i = 0; i < 200; i++) observation.accept(event, event.length);
        observation.finish(true);
        jobs.getFirst().run();

        assertThat(result.get().get("input")).isEqualTo(7L);
    }

    @Test
    void aShutdownBeforeTheProvidersAnswerIsAnAbortRatherThanAnError() {
        // No status arrived, so the provider said nothing wrong: it was only cut off.
        final var jobs = new ArrayList<Runnable>();
        final var result = new AtomicReference<Map<String, Object>>();
        final var observation =
                new UsageObservation(
                        new UsageReading("/responses", new byte[0], false, true),
                        result::set,
                        jobs::add);

        observation.finish(false);
        jobs.getFirst().run();

        assertThat(result.get().get("end")).as("end").isEqualTo("aborted");
        assertThat(result.get().get("closed_by")).as("closed by").isEqualTo("provider");
    }

    @Test
    void aLineAfterAKilledBrokersHalfLineStartsOnItsOwn(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        // Joined to the half line, a valid request would be lost with it.
        final java.nio.file.Path file = dir.resolve("usage.jsonl");
        java.nio.file.Files.writeString(file, "{\"time\":\"2026");
        final var observation =
                new UsageObservation(
                        new UsageReading("/responses", new byte[0], false, true),
                        new VaultProxy.Usage(file, "a", "p", "c", true),
                        "p",
                        "c",
                        "real",
                        "phantom");
        observation.start(false, 200);

        observation.finish(true);
        observation.awaitUntil(
                System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5));

        final var lines = java.nio.file.Files.readAllLines(file);
        assertThat(lines).as("the broken line and the new one").hasSize(2);
        assertThat(lines.get(1)).as("the new line").startsWith("{").contains("\"format\"");
    }
}
