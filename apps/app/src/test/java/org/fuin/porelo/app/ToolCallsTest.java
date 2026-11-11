package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.agent.api.ToolEvent;
import org.fuin.porelo.agent.api.ToolEvent.Kind;
import org.fuin.porelo.agent.api.ToolEvent.Phase;
import org.junit.jupiter.api.Test;

/** Tests how a run's tool events become one entry per tool call in a task's record. */
class ToolCallsTest {

    /** A sub-agent named by an issue id. */
    private static final String ISSUE = "B622"; // not-a-citation

    private static final Instant ARRIVED = Instant.parse("2026-11-11T10:00:05Z");

    private static ToolEvent event(
            final String id,
            final Phase phase,
            final Kind kind,
            final String command,
            final boolean background,
            final String jobId,
            final String at,
            final String name,
            final String parent) {
        return new ToolEvent(
                0, id, phase, "Tool", kind, command, background, jobId, at, name, parent);
    }

    private static ToolCalls calls() {
        return new ToolCalls("porelo-uc_work", new CommandMask(List.of("fake-vault-value")));
    }

    @Test
    void aCallsStartAndEndAreJoinedByItsIdAndItsCommandIsMasked() {
        final ToolCalls calls = calls();
        calls.read(
                Optional.of(
                        List.of(
                                event(
                                        "c1",
                                        Phase.START,
                                        Kind.SHELL,
                                        "deploy --token fake-vault-value",
                                        false,
                                        null,
                                        "2026-11-11T10:00:00Z",
                                        null,
                                        null),
                                event(
                                        "c1",
                                        Phase.END,
                                        Kind.SHELL,
                                        null,
                                        false,
                                        null,
                                        "2026-11-11T10:00:02Z",
                                        null,
                                        null))),
                ARRIVED);

        assertThat(calls.asked()).isEqualTo(ToolCalls.ASKED);
        assertThat(calls.entries())
                .singleElement()
                .satisfies(
                        entry -> {
                            assertThat(entry)
                                    .containsEntry("task", "porelo-uc_work")
                                    .containsEntry("tool", "Tool")
                                    .containsEntry("kind", "shell")
                                    .containsEntry("start", "2026-11-11T10:00:00Z")
                                    .containsEntry("end", "2026-11-11T10:00:02Z")
                                    .containsEntry("background", false);
                            assertThat(entry.get("command"))
                                    .as("the vault's value never stored")
                                    .isEqualTo("deploy --token ***");
                            assertThat(entry.get("issue")).as("outside any sub-agent").isNull();
                        });
    }

    @Test
    void aBackgroundCallEndsWhenItsJobEndsJoinedByTheJobsId() {
        final ToolCalls calls = calls();
        calls.read(
                Optional.of(
                        List.of(
                                event(
                                        "c2",
                                        Phase.START,
                                        Kind.SHELL,
                                        "sleep 60",
                                        true,
                                        "job7",
                                        null,
                                        null,
                                        null),
                                // The call returned at once; that is not its end.
                                event(
                                        "c2",
                                        Phase.END,
                                        Kind.SHELL,
                                        null,
                                        false,
                                        null,
                                        null,
                                        null,
                                        null))),
                ARRIVED);
        calls.read(
                Optional.of(
                        List.of(
                                event(
                                        "c9",
                                        Phase.END,
                                        Kind.SHELL,
                                        null,
                                        true,
                                        "job7",
                                        "2026-11-11T10:01:00Z",
                                        null,
                                        null))),
                ARRIVED.plusSeconds(60));

        assertThat(calls.entries())
                .singleElement()
                .satisfies(
                        entry ->
                                assertThat(entry)
                                        .as("its start stamped on arrival, its end the job's")
                                        .containsEntry("start", ARRIVED.toString())
                                        .containsEntry("end", "2026-11-11T10:01:00Z")
                                        .containsEntry("background", true));
    }

    @Test
    void aCallUnderASubAgentNamedByAnIssueCarriesThatIssueThroughNestedOnes() {
        final ToolCalls calls = calls();
        calls.read(
                Optional.of(
                        List.of(
                                event(
                                        "s1",
                                        Phase.START,
                                        Kind.SUB_AGENT,
                                        null,
                                        false,
                                        null,
                                        null,
                                        ISSUE,
                                        null),
                                event(
                                        "s2",
                                        Phase.START,
                                        Kind.SUB_AGENT,
                                        null,
                                        false,
                                        null,
                                        null,
                                        "helper",
                                        "s1"),
                                event(
                                        "c3",
                                        Phase.START,
                                        Kind.SHELL,
                                        "mvn test",
                                        false,
                                        null,
                                        null,
                                        null,
                                        "s2"),
                                event(
                                        "c4",
                                        Phase.START,
                                        Kind.READ,
                                        "not kept",
                                        false,
                                        null,
                                        null,
                                        null,
                                        null))),
                ARRIVED);

        final List<Map<String, Object>> entries = calls.entries();
        assertThat(entries).hasSize(4);
        assertThat(entries.get(2))
                .as("the shell call ran under the issue's sub-agent")
                .containsEntry("kind", "shell")
                .containsEntry("issue", ISSUE);
        assertThat(entries.get(1)).containsEntry("kind", "sub-agent").containsEntry("issue", ISSUE);
        assertThat(entries.get(3).get("issue")).as("outside any sub-agent").isNull();
        assertThat(entries.get(3).get("command")).as("only a shell call keeps one").isNull();
        assertThat(entries.get(2).get("end")).as("a call the run never ended").isNull();
    }

    @Test
    void anAgentThatCannotSayIsRecordedSoAndNeverAsNoToolRan() {
        final ToolCalls calls = calls();
        assertThat(calls.asked()).as("nothing read yet").isEqualTo(ToolCalls.DID_NOT_ASK);
        calls.read(Optional.empty(), ARRIVED);
        assertThat(calls.asked()).isEqualTo(ToolCalls.CANNOT_SAY);
        assertThat(calls.entries()).isEmpty();
    }

    private static ToolEvent ended(final String id, final String at, final Long durationMs) {
        return new ToolEvent(
                0,
                id,
                Phase.END,
                "Tool",
                Kind.SHELL,
                null,
                false,
                null,
                at,
                null,
                null,
                durationMs);
    }

    @Test
    void aBurstOfLinesReadTogetherSpansTheCallsTrueTimeByItsDuration() {
        // Both lines arrive in one read: by arrival alone the call took no time at all.
        final ToolCalls calls = calls();
        calls.read(
                Optional.of(
                        List.of(
                                event(
                                        "c5",
                                        Phase.START,
                                        Kind.SHELL,
                                        "make",
                                        false,
                                        null,
                                        null,
                                        null,
                                        null),
                                ended("c5", null, 4_250L))),
                ARRIVED);

        assertThat(calls.entries())
                .singleElement()
                .satisfies(
                        entry ->
                                assertThat(entry)
                                        .containsEntry(
                                                "start", ARRIVED.minusMillis(4_250).toString())
                                        .containsEntry("startFrom", "duration")
                                        .containsEntry("end", ARRIVED.toString())
                                        .containsEntry("endFrom", "arrival"));
    }

    @Test
    void anEndWithoutItsTimeIsTheStartsStreamTimeAndTheDuration() {
        final ToolCalls calls = calls();
        calls.read(
                Optional.of(
                        List.of(
                                event(
                                        "c6",
                                        Phase.START,
                                        Kind.SHELL,
                                        "make",
                                        false,
                                        null,
                                        "2026-11-11T09:00:00Z",
                                        null,
                                        null),
                                ended("c6", null, 1_500L))),
                ARRIVED);
        calls.read(
                Optional.of(
                        List.of(
                                event(
                                        "c7",
                                        Phase.START,
                                        Kind.SHELL,
                                        "make",
                                        false,
                                        null,
                                        null,
                                        null,
                                        null))),
                ARRIVED);
        calls.read(
                Optional.of(List.of(ended("c7", "2026-11-11T10:00:09Z", 2_000L))),
                ARRIVED.plusSeconds(30));

        final List<Map<String, Object>> entries = calls.entries();
        assertThat(entries.get(0))
                .containsEntry("start", "2026-11-11T09:00:00Z")
                .containsEntry("startFrom", "stream")
                .containsEntry("end", "2026-11-11T09:00:01.500Z")
                .containsEntry("endFrom", "duration");
        assertThat(entries.get(1))
                .as("the stream's end, and the start it took the duration back from")
                .containsEntry("start", "2026-11-11T10:00:07Z")
                .containsEntry("startFrom", "duration")
                .containsEntry("end", "2026-11-11T10:00:09Z")
                .containsEntry("endFrom", "stream");
    }

    private static Map<String, Object> only(final ToolCalls calls, final List<ToolEvent> events) {
        calls.read(Optional.of(events), ARRIVED);
        return calls.entries().getFirst();
    }

    @Test
    void aJobNamedOnASecondStartEndsItsCallWhenTheJobEnds() {

        // Claude names a background job only in a later line, for a call it already started; the
        // call's own early result must not end it, the job's end must.
        final Map<String, Object> entry =
                only(
                        calls(),
                        List.of(
                                event(
                                        "c1",
                                        Phase.START,
                                        Kind.SHELL,
                                        "make",
                                        false,
                                        null,
                                        "2026-11-11T10:00:00Z",
                                        null,
                                        null),
                                event(
                                        "c1",
                                        Phase.START,
                                        Kind.SHELL,
                                        null,
                                        true,
                                        "j1",
                                        "2026-11-11T10:00:01Z",
                                        null,
                                        null),
                                event(
                                        "c1",
                                        Phase.END,
                                        Kind.SHELL,
                                        null,
                                        false,
                                        null,
                                        "2026-11-11T10:00:02Z",
                                        null,
                                        null),
                                event(
                                        "j1-end",
                                        Phase.END,
                                        Kind.SHELL,
                                        null,
                                        true,
                                        "j1",
                                        "2026-11-11T10:00:30Z",
                                        null,
                                        null)));

        assertThat(entry)
                .as("the call ran in the background and ended with its job")
                .containsEntry("background", true)
                .containsEntry("start", "2026-11-11T10:00:00Z")
                .containsEntry("end", "2026-11-11T10:00:30Z");
    }

    @Test
    void aCallMovedPastItsTimeoutEndsWhenItsJobEnds() {

        // A foreground call moved to a job after its result came back early: the job's end is
        // the real one.
        final Map<String, Object> entry =
                only(
                        calls(),
                        List.of(
                                event(
                                        "c1",
                                        Phase.START,
                                        Kind.SHELL,
                                        "make",
                                        false,
                                        null,
                                        "2026-11-11T10:00:00Z",
                                        null,
                                        null),
                                event(
                                        "c1",
                                        Phase.END,
                                        Kind.SHELL,
                                        null,
                                        false,
                                        null,
                                        "2026-11-11T10:00:02Z",
                                        null,
                                        null),
                                event(
                                        "c1",
                                        Phase.START,
                                        Kind.SHELL,
                                        null,
                                        false,
                                        "j1",
                                        "2026-11-11T10:00:03Z",
                                        null,
                                        null),
                                event(
                                        "j1-end",
                                        Phase.END,
                                        Kind.SHELL,
                                        null,
                                        true,
                                        "j1",
                                        "2026-11-11T10:00:40Z",
                                        null,
                                        null)));

        assertThat(entry)
                .containsEntry("background", true)
                .containsEntry("end", "2026-11-11T10:00:40Z");
    }

    private static ToolEvent shell(
            final String id,
            final Phase phase,
            final boolean background,
            final String jobId,
            final String at) {
        return event(id, phase, Kind.SHELL, "make", background, jobId, at, null, null);
    }

    @Test
    void aCallMovedToAJobHasNoEndWhileItsJobRuns() {

        // Its early return is not its end: a run that ends before the job leaves it open.
        final Map<String, Object> entry =
                only(
                        calls(),
                        List.of(
                                shell("c1", Phase.START, false, null, "2026-11-11T10:00:00Z"),
                                shell("c1", Phase.END, false, null, "2026-11-11T10:00:02Z"),
                                shell("c1", Phase.START, false, "j1", "2026-11-11T10:00:03Z")));

        assertThat(entry).containsEntry("background", true).containsEntry("end", null);
    }

    @Test
    void aCallMovedToAJobIsNotEndedByItsLateReturn() {

        // The early return may come after the line that names the job.
        final Map<String, Object> entry =
                only(
                        calls(),
                        List.of(
                                shell("c1", Phase.START, false, null, "2026-11-11T10:00:00Z"),
                                shell("c1", Phase.START, false, "j1", "2026-11-11T10:00:01Z"),
                                shell("c1", Phase.END, false, null, "2026-11-11T10:00:02Z")));

        assertThat(entry).containsEntry("end", null);
    }

    @Test
    void aJobIdUsedAgainEndsTheCallThatTookItLast() {

        final ToolCalls calls = calls();
        calls.read(
                Optional.of(
                        List.of(
                                shell("c1", Phase.START, true, "j1", "2026-11-11T10:00:00Z"),
                                shell("j1-end", Phase.END, true, "j1", "2026-11-11T10:00:10Z"),
                                shell("c2", Phase.START, true, "j1", "2026-11-11T10:00:20Z"),
                                shell("j1-end", Phase.END, true, "j1", "2026-11-11T10:00:30Z"))),
                ARRIVED);

        assertThat(calls.entries())
                .extracting(entry -> entry.get("end"))
                .containsExactly("2026-11-11T10:00:10Z", "2026-11-11T10:00:30Z");
    }

    @Test
    void aForegroundCallsFirstEndIsItsEnd() {

        final Map<String, Object> entry =
                only(
                        calls(),
                        List.of(
                                shell("c1", Phase.START, false, null, "2026-11-11T10:00:00Z"),
                                shell("c1", Phase.END, false, null, "2026-11-11T10:00:10Z"),
                                shell("c1", Phase.END, false, null, "2026-11-11T10:00:20Z")));

        assertThat(entry).containsEntry("end", "2026-11-11T10:00:10Z");
    }

    @Test
    void aJobsFirstEndIsItsCallsEndAndARepeatedOneChangesNothing() {

        // Oh My Pi repeats a finished sub-agent's end on every later progress line.
        final Map<String, Object> entry =
                only(
                        calls(),
                        List.of(
                                event(
                                        "c1",
                                        Phase.START,
                                        Kind.SHELL,
                                        "task",
                                        true,
                                        "j1",
                                        "2026-11-11T10:00:00Z",
                                        null,
                                        null),
                                event(
                                        "c1",
                                        Phase.END,
                                        Kind.SHELL,
                                        null,
                                        true,
                                        "j1",
                                        "2026-11-11T10:00:30Z",
                                        null,
                                        null),
                                event(
                                        "c1",
                                        Phase.END,
                                        Kind.SHELL,
                                        null,
                                        true,
                                        "j1",
                                        "2026-11-11T10:00:45Z",
                                        null,
                                        null),
                                event(
                                        "c1",
                                        Phase.END,
                                        Kind.SHELL,
                                        null,
                                        true,
                                        "j1",
                                        "2026-11-11T10:01:00Z",
                                        null,
                                        null)));

        assertThat(entry)
                .as("a repeat moves no end later")
                .containsEntry("end", "2026-11-11T10:00:30Z");
    }
}
