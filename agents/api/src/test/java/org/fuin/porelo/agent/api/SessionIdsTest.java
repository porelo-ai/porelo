package org.fuin.porelo.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SessionIds}: where an agent declares it names its sessions, and how that is
 * read.
 */
class SessionIdsTest {

    private static final String MINIMAL =
            """
            name: example
            binary: example-cli

            git_identity:
              name: Example
              email: noreply@example.com

            headless:
              prompt_flag: "-p"
            """;

    private static AgentDefinition read(String yaml) {
        return AgentDefinitionReader.read(new StringReader(yaml), "test.yaml");
    }

    @Test
    void readsBothWaysOfFindingTheIdAndCarriesThemThroughItsDescription() {
        final AgentDefinition definition =
                read(
                        MINIMAL
                                + """
                session:
                  supports_resume: true
                  resume_flag: "--resume"
                  session_id:
                    record: { type: system, subtype: init }
                    key: session_id
                    directory: ".example/projects"
                    suffix: ".jsonl"
                """);

        final SessionIds expected =
                new SessionIds(
                        Map.of("type", "system", "subtype", "init"),
                        "session_id",
                        ".example/projects",
                        ".jsonl");
        assertThat(definition.sessionIds()).isEqualTo(expected);
        assertThat(AgentDefinitionJson.read(AgentDefinitionJson.write(definition)).sessionIds())
                .isEqualTo(expected);
    }

    @Test
    void anIdNothingCanContinueIsRefused() {
        assertThatThrownBy(
                        () ->
                                read(
                                        MINIMAL
                                                + """
                session:
                  session_id: { record: { type: system }, key: session_id }
                """))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("does not support resume");
    }

    @Test
    void eachWayIsDeclaredWholeOrNotAtAll() {
        assertThatThrownBy(() -> new SessionIds(Map.of("type", "system"), null, null, null))
                .isInstanceOf(AgentException.class);
        assertThatThrownBy(() -> new SessionIds(Map.of(), null, ".example", null))
                .isInstanceOf(AgentException.class);
        assertThatThrownBy(() -> new SessionIds(Map.of(), null, null, null))
                .hasMessageContaining("leaving it out");
    }

    @Test
    void aDirectoryIsAPlainPathUnderTheHomeBecauseItReachesACommand() {
        for (final String bad : new String[] {"/etc", "../x", "a b", "$(id)", "a;b", ""}) {
            assertThatThrownBy(() -> new SessionIds(Map.of(), null, bad, ".jsonl"))
                    .as("'%s'", bad)
                    .isInstanceOf(AgentException.class);
        }
        assertThatThrownBy(() -> new SessionIds(Map.of(), null, ".x", "*.jsonl"))
                .isInstanceOf(AgentException.class);
    }

    @Test
    void refusesAMisspeltKey() {
        assertThatThrownBy(
                        () ->
                                read(
                                        MINIMAL
                                                + """
                session:
                  supports_resume: true
                  resume_flag: "--resume"
                  session_id: { recrd: { type: system }, key: session_id }
                """))
                .hasMessageContaining("'recrd'");
    }

    @Test
    void theDeclaredKeyNotAnotherIsWhatIsPickedUp() {
        final List<Object> records =
                List.of(
                        Map.of("type", "assistant", "session_id", "not-this"),
                        Map.of("type", "system", "session_id", "s-1", "other_id", "o-1"),
                        Map.of("type", "system", "session_id", "s-2"));

        assertThat(new SessionIds(Map.of("type", "system"), "session_id", null, null).of(records))
                .isEqualTo("s-1");
        // Changing the declared key, and nothing else, changes which field is picked up.
        assertThat(new SessionIds(Map.of("type", "system"), "other_id", null, null).of(records))
                .isEqualTo("o-1");
    }

    @Test
    void somethingThatCannotBeOneWordOnACommandLineIsNoId() {
        final SessionIds ids = new SessionIds(Map.of("type", "system"), "session_id", null, null);

        assertThat(ids.of(List.of(Map.of("type", "system", "session_id", "a b; rm -rf /"))))
                .isNull();
        assertThat(SessionIds.isId("0b1f-42_x.y")).isTrue();
        assertThat(SessionIds.isId("")).isFalse();
    }

    @Test
    void aSessionIdBelowTheTopLevelOfARecordIsFound() {
        // As Codex CLI writes a session file's first line.
        final List<Object> records =
                List.of(
                        Map.of(
                                "type",
                                "session_meta",
                                "payload",
                                Map.of("id", "0199a213-81c0-7800-8aa1-bbab2a035a53", "cwd", "/w")));

        assertThat(
                        new SessionIds(Map.of("type", "session_meta"), "payload.id", null, null)
                                .of(records))
                .as("a dotted key walks through the record's mappings")
                .isEqualTo("0199a213-81c0-7800-8aa1-bbab2a035a53");
    }

    @Test
    void aRecordIsMatchedOnAFieldBelowItsTopLevel() {
        final List<Object> records =
                List.of(
                        Map.of("type", "event", "body", Map.of("kind", "other"), "id", "not-this"),
                        Map.of("type", "event", "body", Map.of("kind", "start"), "id", "s-1"));

        assertThat(
                        new SessionIds(
                                        Map.of("type", "event", "body.kind", "start"),
                                        "id",
                                        null,
                                        null)
                                .of(records))
                .as("a dotted field name is matched where it points")
                .isEqualTo("s-1");
    }

    @Test
    void aPathOfMoreThanFourSegmentsOrAnEmptyOneIsRefused() {
        for (final String bad : new String[] {"a.b.c.d.e", "a..b", ".a", "a.", "a-b.c", "a b"}) {
            assertThatThrownBy(() -> new SessionIds(Map.of("type", "x"), bad, null, null))
                    .as("key '%s'", bad)
                    .isInstanceOf(AgentException.class);
            assertThatThrownBy(() -> new SessionIds(Map.of(bad, "x"), "id", null, null))
                    .as("field '%s'", bad)
                    .isInstanceOf(AgentException.class);
        }
    }

    @Test
    void aSessionFileReadByItsOwnRecordGivesTheIdInsideAndARunKeepsItsOwn() {
        // As Codex CLI declares it: a run names its thread, a session file its payload.
        final AgentDefinition definition =
                read(
                        MINIMAL
                                + """
                session:
                  supports_resume: true
                  resume_flag: "resume"
                  session_id:
                    record: { type: thread.started }
                    key: thread_id
                    directory: ".codex/sessions"
                    suffix: ".jsonl"
                    file_record: { type: session_meta }
                    file_key: payload.id
                """);
        final SessionIds ids = java.util.Objects.requireNonNull(definition.sessionIds());
        final List<Object> file =
                List.of(Map.of("type", "session_meta", "payload", Map.of("id", "f-1")));
        final List<Object> run = List.of(Map.of("type", "thread.started", "thread_id", "t-1"));

        assertThat(ids.ofFile(file))
                .as("a session file is read by its own record")
                .isEqualTo("f-1");
        assertThat(ids.of(run)).as("a run is still read by the run's record").isEqualTo("t-1");
        assertThat(ids.ofFile(run))
                .as("a file is not read by the run's record once it has its own")
                .isNull();
        assertThat(AgentDefinitionJson.read(AgentDefinitionJson.write(definition)).sessionIds())
                .as("what 'describe' and the socket carry comes back the same")
                .isEqualTo(ids);
    }

    @Test
    void aSessionFilesOwnRecordIsDeclaredWholeAndOnlyWithItsFiles() {
        assertThatThrownBy(
                        () ->
                                new SessionIds(
                                        Map.of(),
                                        null,
                                        ".x",
                                        ".jsonl",
                                        Map.of("type", "session_meta"),
                                        null))
                .as("a file record without its key")
                .isInstanceOf(AgentException.class);
        assertThatThrownBy(
                        () ->
                                new SessionIds(
                                        Map.of("type", "system"),
                                        "id",
                                        null,
                                        null,
                                        Map.of("type", "session_meta"),
                                        "payload.id"))
                .as("a file record where no files are declared")
                .isInstanceOf(AgentException.class);
    }
}
