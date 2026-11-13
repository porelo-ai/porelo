package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A task started without a certificate, only while root's test switch holds. */
class HostTestModeTest {
    private static final String FILE = "/etc/porelo-host/test-mode.json";
    private static final String ON =
            "{\"v\": 1, \"switches\": [\"start-uncertified\"], \"why\": \"measuring host\"}\n";

    @TempDir Path directory;
    private HostTaskFixture host;

    @BeforeEach
    void prepare() throws Exception {
        host = new HostTaskFixture(directory);
        host.getent.put("passwd alice", "alice:x:1000:1000::/home/alice:/bin/bash\n");
        host.getent.put("group porelo-people", "porelo-people:x:990:alice\n");
    }

    private HostReadFixture.Answer uncertified() {
        return host.run("", "task", "start", "t1");
    }

    private HostReadFixture.Answer switches() {
        return host.run(Map.of(), "", "test-switches", "--json");
    }

    @Test
    void theSwitchesAnswerWhatHoldsAndNoneWithoutTheFile() throws Exception {
        assertThat(switches()).isEqualTo(new HostReadFixture.Answer(0, "{\"switches\":[]}\n", ""));
        host.tree.put(FILE, ON);
        assertThat(switches())
                .isEqualTo(
                        new HostReadFixture.Answer(
                                0, "{\"switches\":[\"start-uncertified\"]}\n", ""));
        assertThat(host.run("", "test-switches", "--json").out())
                .as("through the role's sudo rule, too")
                .isEqualTo("{\"switches\":[\"start-uncertified\"]}\n");
    }

    @Test
    void theSwitchesQuestionIsWrongUseWithoutJsonOrWithMore() throws Exception {
        host.tree.put(FILE, ON);
        assertThat(host.run(Map.of(), "", "test-switches").code()).isEqualTo(64);
        assertThat(host.run(Map.of(), "", "test-switches", "--json", "x").code()).isEqualTo(64);
    }

    @Test
    void withTheSwitchAnEmptyInputStartsTheTaskUncertifiedForDefault() throws Exception {
        host.tree.put(FILE, ON);
        // Whatever the organization says: a measuring host has none applied, no person record.
        host.organization(host.alice, false, true);
        Files.delete(host.tree.path("/etc/porelo-host/people/alice.conf"));

        final var answer = uncertified();

        assertThat(answer.code()).as(answer.err()).isZero();
        assertThat(answer.out()).matches("[0-9a-f]{40}");
        assertThat(host.names("porelo-alice")).containsExactly("porelo-task-default_t1");
        final Path record = host.record("default", "t1");
        assertThat((int) Files.getAttribute(record, "unix:mode") & 0777).isEqualTo(0600);
        final var entry = HostJson.object(HostJson.parse(Files.readString(record)));
        assertThat(entry)
                .containsEntry("task", "t1")
                .containsEntry("login", "alice")
                .containsEntry("uncertified", true)
                .containsEntry("project", "default")
                .containsEntry("valid_before", "2026-11-06T12:00:00Z")
                .doesNotContainKeys("certificate", "fingerprint", "principals");
        host.noSecrets(answer);

        assertThat(host.run(Map.of(), "", "task", "sweep").code())
                .as("the sweep reads the record")
                .isZero();
        assertThat(host.names("porelo-alice")).containsExactly("porelo-task-default_t1");
        // Its person's own project is not default: the end finds it under default all the same.
        assertThat(host.run("", "task", "end", "alpha", "t1"))
                .isEqualTo(new HostReadFixture.Answer(0, "{\"task\":\"t1\",\"ended\":true}\n", ""));
        assertThat(host.names("porelo-alice")).isEmpty();
        assertThat(record).as("the end keeps the record, as for every task").exists();
    }

    @Test
    void aCertificateForAnUncertifiedTaskIsAnotherOne() throws Exception {
        host.tree.put(FILE, ON);
        assertThat(uncertified().code()).isZero();
        assertThat(uncertified().code()).as("a retry rotates its token").isZero();
        final String forDefault =
                host.certificate(
                        "alice",
                        "task",
                        java.util.List.of("task:default:t1"),
                        "20261030000000Z:20261031000000Z",
                        false,
                        "porelo task t1 by alice");
        assertThat(host.run(forDefault, "task", "start", "t1").code()).isEqualTo(65);
        assertThat(host.run(host.certificate(), "task", "start", "t1").code())
                .as("project alpha's task t1 is another task than default's")
                .isZero();
    }

    @Test
    void withoutTheSwitchAnEmptyInputIsRefusedWith65AndTouchesNothing() throws Exception {
        final var before = host.snapshot();
        final var answer = uncertified();
        assertThat(answer.code()).as(answer.err()).isEqualTo(65);
        assertThat(answer.err()).contains("test mode");
        assertThat(host.snapshot()).isEqualTo(before);
        assertThat(host.calls).isEmpty();
    }

    @Test
    void aPersonOutsidePoreloPeopleStartsNothingUncertified() throws Exception {
        host.tree.put(FILE, ON);
        host.getent.put("group porelo-people", "porelo-people:x:990:bob\n");
        final var answer = uncertified();
        assertThat(answer.code()).as(answer.err()).isEqualTo(65);
        assertThat(host.record("default", "t1")).doesNotExist();
        assertThat(host.names("porelo-alice")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "link",
                "owner",
                "mode",
                "group-writable directory",
                "others-writable directory",
                "directory owner",
                "another v",
                "no v",
                "unknown name only",
                "a name not text",
                "not json",
                "unreadable",
                "a directory"
            })
    void aBadFileHoldsNoSwitchAndTheStartIsRefused(String defect) throws Exception {
        final Path file = host.tree.put(FILE, ON);
        final Path parent = file.getParent();
        switch (defect) {
            case "link" -> {
                final Path target = host.tree.put("/etc/porelo-host/elsewhere.json", ON);
                Files.delete(file);
                Files.createSymbolicLink(file, target);
            }
            case "owner" -> host.tree.overrides.put(file, Map.of("uid", 1000));
            case "mode" -> host.tree.overrides.put(file, Map.of("mode", 0100640));
            case "group-writable directory" ->
                    host.tree.overrides.put(parent, Map.of("mode", 040770));
            case "others-writable directory" ->
                    host.tree.overrides.put(parent.getParent(), Map.of("mode", 040757));
            case "directory owner" -> host.tree.overrides.put(parent, Map.of("uid", 1000));
            case "another v" ->
                    host.tree.put(FILE, "{\"v\": 2, \"switches\": [\"start-uncertified\"]}");
            case "no v" -> host.tree.put(FILE, "{\"switches\": [\"start-uncertified\"]}");
            case "unknown name only" -> host.tree.put(FILE, "{\"v\": 1, \"switches\": [\"all\"]}");
            case "a name not text" ->
                    host.tree.put(FILE, "{\"v\": 1, \"switches\": [\"start-uncertified\", 1]}");
            case "not json" -> host.tree.put(FILE, "start-uncertified\n");
            case "unreadable" -> {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
                host.tree.overrides.put(file, Map.of("mode", 0100600));
            }
            case "a directory" -> {
                Files.delete(file);
                Files.createDirectory(file);
                host.tree.overrides.put(file, Map.of("mode", 040600));
            }
            default -> throw new AssertionError(defect);
        }
        assertThat(switches())
                .as(defect)
                .isEqualTo(new HostReadFixture.Answer(0, "{\"switches\":[]}\n", ""));
        final var answer = uncertified();
        // An unsafe directory above it fails forge.conf's own check first, with 69.
        assertThat(answer.code()).as(defect + ": " + answer.err()).isIn(65, 69);
        assertThat(host.record("default", "t1")).doesNotExist();
        assertThat(host.names("porelo-alice")).isEmpty();
    }

    @Test
    void theSwitchTakesNoFlagOfTheCaller() throws Exception {
        assertThat(host.run("", "task", "start", "--yes", "t1").code()).isEqualTo(64);
        assertThat(host.run("", "task", "start", "t1", "--uncertified").code()).isEqualTo(64);
        assertThat(host.record("default", "t1")).doesNotExist();
    }
}
