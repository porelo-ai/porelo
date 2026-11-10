package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.fuin.porelo.agent.api.AgentEnd;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link AgentEnding}: a task whose agent ended says so, with the error and whose it was.
 */
class AgentEndingTest {

    private static final Instant NOW = Instant.parse("2026-10-03T13:30:00Z");

    @Test
    void anAgentThatEndedWithAProvidersRefusalIsSaidSoWithTheTextAndWhatWasLeftUnpushed(
            @TempDir Path dir) throws IOException {

        // The provider answered 402 and the agent ended; the task said 'idle', then 'working', and
        // nothing pointed the person at what had happened, nor at the report it had written and
        // never pushed.
        final Path log =
                Files.writeString(
                        dir.resolve("task.log"),
                        "{\"type\":\"start\"}\n{\"type\":\"agent_end\",\"error\":\"402 more credits\"}\n\n");
        Files.setLastModifiedTime(log, FileTime.from(NOW.minusSeconds(120)));
        final FakeCommandRunner runner = new FakeCommandRunner().answering("git status", "1 0");
        final AgentEnding ending =
                new AgentEnding(
                        runner,
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        (agent, lines) ->
                                lines.stream().anyMatch(line -> line.contains("agent_end"))
                                        ? new AgentEnd(
                                                false, "402 more credits", AgentEnd.PROVIDER, 402)
                                        : null);

        final AgentEnding.Ended ended =
                ending.about("porelo-default-contracts", true, "example", dir);

        assertThat(ended).isNotNull();
        assertThat(ended.finished()).isFalse();
        assertThat(ended.error()).isEqualTo("402 more credits");
        assertThat(ended.source()).isEqualTo("provider");
        assertThat(ended.status()).isEqualTo(402);
        assertThat(ended.unpushed()).isEqualTo(1);
        assertThat(ended.at()).isEqualTo(NOW.minusSeconds(120).toString());
    }

    @Test
    void aLogStillGrowingIsNotAskedAndAnUnchangedOneIsNotAskedTwice(@TempDir Path dir)
            throws IOException {

        // Asked on every pass, every running task would start every agent twice a second.
        final Path log = Files.writeString(dir.resolve("task.log"), "{\"type\":\"agent_end\"}\n");
        final AtomicInteger asked = new AtomicInteger();
        final AgentEnding ending =
                new AgentEnding(
                        new FakeCommandRunner().answering("git status", "0 0"),
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        (agent, lines) -> {
                            asked.incrementAndGet();
                            return new AgentEnd(true, "", AgentEnd.AGENT, null);
                        });

        Files.setLastModifiedTime(log, FileTime.from(NOW.minusSeconds(2)));
        assertThat(ending.about("porelo-p-t", true, "example", dir))
                .as("still being written")
                .isNull();
        assertThat(asked).hasValue(0);

        Files.setLastModifiedTime(log, FileTime.from(NOW.minusSeconds(60)));
        assertThat(ending.about("porelo-p-t", true, "example", dir)).isNotNull();
        assertThat(ending.about("porelo-p-t", true, "example", dir)).isNotNull();
        assertThat(asked).hasValue(1);
    }

    @Test
    void anEndLineThatCarriesTheWholeRunIsStillOfferedWhole(@TempDir Path dir) throws IOException {

        // An agent's end event can hold every message of its run, the output of every tool it ran
        // included: a run that read a few files writes an end line past 64 KiB.
        final String run = "x".repeat(300 * 1024);
        final String end = "{\"type\":\"agent_end\",\"messages\":[\"" + run + "\"]}";
        final Path log =
                Files.writeString(dir.resolve("task.log"), "{\"type\":\"start\"}\n" + end + "\n");
        Files.setLastModifiedTime(log, FileTime.from(NOW.minusSeconds(120)));
        final AgentEnding ending =
                new AgentEnding(
                        new FakeCommandRunner().answering("git status", "0 0"),
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        (agent, lines) ->
                                lines.contains(end)
                                        ? new AgentEnd(
                                                false, "402 more credits", AgentEnd.PROVIDER, 402)
                                        : null);

        assertThat(ending.about("porelo-p-t", true, "example", dir))
                .as("the end line, whole, is offered to the agent")
                .isNotNull();
    }

    @Test
    void aTaskWithoutALogOrAnAgentThatSaysNothingHasNoEnd(@TempDir Path dir) throws IOException {
        final AgentEnding ending =
                new AgentEnding(
                        new FakeCommandRunner(),
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        (agent, lines) -> null);
        assertThat(ending.about("porelo-p-t", true, "example", dir)).isNull();

        final Path log = Files.writeString(dir.resolve("task.log"), "working\n");
        Files.setLastModifiedTime(log, FileTime.from(NOW.minusSeconds(60)));
        assertThat(ending.about("porelo-p-t", true, "example", dir)).isNull();
        assertThat(ending.about("porelo-p-t", true, null, dir)).isNull();
    }

    @Test
    void aTaskWhoseContainerStoppedWhenItsAgentEndedStillSaysHowAndWhatItLeftUnpushed(
            @TempDir Path dir) throws IOException {

        // Measured with a second agent on the VM: its container stopped with its run, and the task
        // said only 'dead'.
        final Path log = Files.writeString(dir.resolve("task.log"), "{\"type\":\"agent_end\"}\n");
        Files.setLastModifiedTime(log, FileTime.from(NOW.minusSeconds(120)));
        UnhandedWork.note(dir, UnhandedWork.census("2 1"));
        final AgentEnding ending =
                new AgentEnding(
                        new FakeCommandRunner(),
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        (agent, lines) ->
                                new AgentEnd(
                                        false,
                                        "401 Missing Authentication header",
                                        AgentEnd.PROVIDER,
                                        401));

        final AgentEnding.Ended ended = ending.about("porelo-refused-talk", false, "example", dir);

        assertThat(ended).isNotNull();
        assertThat(ended.status()).isEqualTo(401);
        assertThat(ended.unpushed()).as("as recorded when it stopped").isEqualTo(3);
    }

    /** Where the stub agent of these tests declares its last message. */
    private static final org.fuin.porelo.agent.api.Waiting.LastMessage RESULT =
            new org.fuin.porelo.agent.api.Waiting.LastMessage(
                    java.util.Map.of("type", "result"), "result");

    /** A quiet log of an unattended run that said {@code said} last and then ended. */
    private static void ranAndSaid(Path dir, String said) throws IOException {
        final Path log =
                Files.writeString(
                        dir.resolve("task.log"),
                        "{\"type\":\"start\"}\n"
                                + org.fuin.porelo.wire.Json.write(
                                        java.util.Map.of("type", "result", "result", "earlier"))
                                + "\n"
                                + org.fuin.porelo.wire.Json.write(
                                        java.util.Map.of("type", "result", "result", said))
                                + "\n{\"type\":\"agent_end\"}\n");
        Files.setLastModifiedTime(log, FileTime.from(NOW.minusSeconds(120)));
    }

    private static AgentEnding declaring(
            java.util.function.Function<
                            String,
                            org.fuin.porelo.agent.api.Waiting.@org.jspecify.annotations.Nullable
                            LastMessage>
                    declared) {
        return new AgentEnding(
                new FakeCommandRunner().answering("git status", "0 0"),
                Clock.fixed(NOW, ZoneOffset.UTC),
                (agent, lines) ->
                        lines.stream().anyMatch(line -> line.contains("agent_end"))
                                ? new AgentEnd(true, "", AgentEnd.AGENT, null)
                                : null,
                declared);
    }

    @Test
    void aLongAnswerKeepsItsEndWhereTheWorkOrdersLastLineStandsAndSaysItWasCut(@TempDir Path dir)
            throws IOException {

        // A work order reads its outcome from the answer's last line: a cut that kept the start
        // lost exactly that line.
        final String said = "report line\n".repeat(20 * 1024 / 12) + "Outcome: done";
        ranAndSaid(dir, said);

        final AgentEnding.Ended ended =
                declaring(agent -> RESULT).about("porelo-p-t", true, "example", dir);

        assertThat(ended).isNotNull();
        assertThat(ended.answer()).endsWith("\nOutcome: done");
        assertThat(said).endsWith(ended.answer());
        assertThat(ended.answer().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .hasSizeLessThanOrEqualTo(16 * 1024)
                .hasSizeGreaterThan(16 * 1024 - 4);
        assertThat(ended.answerCut()).isTrue();
        assertThat(ended.asMap())
                .containsEntry("answer", ended.answer())
                .containsEntry("answerCut", true);
    }

    @Test
    void aShortAnswerIsWholeAndNotCut(@TempDir Path dir) throws IOException {

        // A short answer said to be cut sends a person to the log for nothing it lacks.
        ranAndSaid(dir, "All three files fixed.\nOutcome: done");

        final AgentEnding.Ended ended =
                declaring(agent -> RESULT).about("porelo-p-t", true, "example", dir);

        assertThat(ended).isNotNull();
        assertThat(ended.answer()).isEqualTo("All three files fixed.\nOutcome: done");
        assertThat(ended.answerCut()).isFalse();
    }

    @Test
    void anAgentDeclaringNoLastMessageAnswersNothing(@TempDir Path dir) throws IOException {

        // An answer guessed from lines no agent declared as its word would be read as its word.
        ranAndSaid(dir, "Outcome: done");

        final AgentEnding.Ended ended =
                declaring(agent -> null).about("porelo-p-t", true, "example", dir);

        assertThat(ended).isNotNull();
        assertThat(ended.answer()).isEmpty();
        assertThat(ended.answerCut()).isFalse();
    }

    @Test
    void aCutInsideAMultiByteCharacterKeepsAValidString(@TempDir Path dir) throws IOException {

        // A cut through a character sends a broken string a client may refuse, and a reply with it.
        for (final String character : java.util.List.of("\u20ac", "\ud83d\ude00")) {
            final String said = character.repeat(6000) + "\nOutcome: done";
            ranAndSaid(dir, said);

            final AgentEnding.Ended ended =
                    declaring(agent -> RESULT)
                            .about("porelo-" + character.length(), true, "a", dir);

            assertThat(ended).isNotNull();
            assertThat(ended.answerCut()).isTrue();
            assertThat(ended.answer()).doesNotContain("\ufffd").endsWith("Outcome: done");
            assertThat(said).endsWith(ended.answer());
            assertThat(ended.answer().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    .hasSizeLessThanOrEqualTo(16 * 1024);
        }
    }

    @Test
    void theSameLogSizeIsNotReadTwiceForItsAnswer(@TempDir Path dir) throws IOException {

        // Read on every pass, a listing twice a second parses 2 MiB of every ended task's log.
        ranAndSaid(dir, "first");
        final AtomicInteger asked = new AtomicInteger();
        final AgentEnding ending =
                declaring(
                        agent -> {
                            asked.incrementAndGet();
                            return RESULT;
                        });

        assertThat(ending.about("porelo-p-t", true, "example", dir).answer()).isEqualTo("first");
        ranAndSaid(dir, "other");
        assertThat(ending.about("porelo-p-t", true, "example", dir).answer())
                .as("the same size is not read again")
                .isEqualTo("first");
        assertThat(asked).hasValue(1);
        ranAndSaid(dir, "longer");
        assertThat(ending.about("porelo-p-t", true, "example", dir).answer()).isEqualTo("longer");
    }

    @Test
    void aLineTheAgentFailsOnDoesNotHideTheEndBeforeIt() {
        // One bad line stopped the search, and the end was recorded as never said for that size.
        final AgentEnd end = new AgentEnd(false, "402 more credits", AgentEnd.PROVIDER, 402);

        assertThat(
                        AgentEnding.lastEnd(
                                line -> {
                                    if (line.equals("garbled")) {
                                        throw new org.fuin.porelo.wire.varlink.VarlinkException(
                                                "org.fuin.porelo.Agent1.Failed",
                                                java.util.Map.of());
                                    }
                                    return end;
                                },
                                java.util.List.of("garbled", "the end")))
                .isEqualTo(end);
    }

    @Test
    void anAgentThatCannotBeReachedIsNotAskedAboutEveryLine() {
        // A connection that is gone fails at once on each line, for up to 2 MiB of them.
        final java.util.concurrent.atomic.AtomicInteger asked =
                new java.util.concurrent.atomic.AtomicInteger();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                AgentEnding.lastEnd(
                                        line -> {
                                            asked.incrementAndGet();
                                            throw new org.fuin.porelo.wire.varlink.VarlinkException(
                                                    "connection lost");
                                        },
                                        java.util.List.of("one", "two", "three")))
                .isInstanceOf(org.fuin.porelo.wire.varlink.VarlinkException.class);
        assertThat(asked).as("stopped at the first line").hasValue(1);
    }
}
