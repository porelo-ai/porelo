package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.fuin.porelo.host.HostReadFixture.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostTaskShowTest {
    @TempDir Path directory;
    private HostReadFixture fixture;

    @BeforeEach
    void prepareAnAdmittedPerson() throws Exception {
        fixture = new HostReadFixture(directory);
        fixture.person("alice", "member=alpha developer\n", "");
    }

    @Test
    void theSudoPersonReadsTheWholeTaskRecordInItsStoredOrder() throws Exception {
        final String record = task("alice", "one", FINGERPRINT);
        fixture.put("/var/lib/porelo-host/tasks/alice/one.json", record);
        assertThat(fixture.run(SUDO, "task", "show", FINGERPRINT))
                .as("a read-only lookup returns exactly the recorded certificate fields and order")
                .isEqualTo(new Answer(0, record + "\n", ""));
    }

    @Test
    void anotherPersonsTaskIsNotDisclosed() throws Exception {
        fixture.person("bob", "member=alpha developer\n", "");
        fixture.put("/var/lib/porelo-host/tasks/bob/one.json", task("bob", "one", FINGERPRINT));
        assertThat(fixture.run(SUDO, "task", "show", FINGERPRINT))
                .as("membership of the host grants no access to another person's task records")
                .isEqualTo(
                        new Answer(
                                65,
                                "",
                                "porelo-host-person: no task certificate is recorded for "
                                        + FINGERPRINT
                                        + "\n"));
    }

    @Test
    void aMissingTaskIsRefused() {
        assertThat(fixture.run(SUDO, "task", "show", FINGERPRINT))
                .as("an absent tasks tree is not a successful empty record")
                .isEqualTo(
                        new Answer(
                                65,
                                "",
                                "porelo-host-person: no task certificate is recorded for "
                                        + FINGERPRINT
                                        + "\n"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SHA256:short",
                "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                "../../etc/passwd",
                "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA_"
            })
    void aMalformedFingerprintIsRefusedBeforeAnyFileIsRead(String fingerprint) throws Exception {
        Files.delete(fixture.path("/etc/porelo-host/forge.conf"));
        assertThat(fixture.run(SUDO, "task", "show", fingerprint))
                .as("fingerprints use precisely the Python argument contract")
                .isEqualTo(
                        new Answer(
                                64,
                                "",
                                "porelo-host-person: a task must match [a-z0-9][a-z0-9._-]{0,63}, a task key's fingerprint SHA256:<43 base64>\n"));
    }

    @Test
    void rootWithoutASudoPersonCannotReadATask() {
        assertThat(fixture.run(Map.of(), "task", "show", FINGERPRINT))
                .as("this verb requires HostPerson even when the process is root")
                .isEqualTo(
                        new Answer(
                                77,
                                "",
                                "porelo-host: SUDO_USER is required; invoke through the installed sudo rule\n"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"locked=true", "lock=pending", "no-members"})
    void aPersonWithoutActiveAdmissionIsRefused(String state) throws Exception {
        final String members = state.equals("no-members") ? "" : "member=alpha developer\n";
        fixture.put(
                "/etc/porelo-host/people/alice.conf",
                members
                        + (state.equals("locked=true") ? "locked=true" : "locked=false")
                        + "\nssh="
                        + KEY
                        + "\nexpires=\n"
                        + (state.equals("lock=pending") ? "lock=pending\n" : ""));
        assertThat(fixture.run(SUDO, "task", "show", FINGERPRINT))
                .as(
                        "locked, pending, and membership-less people are refused by the same admission rule")
                .isEqualTo(
                        new Answer(
                                65,
                                "",
                                "porelo-host-person: alice is no unlocked person of this host\n"));
    }

    @Test
    void aTaskFileNotOwnedByRootIsRefused() throws Exception {
        final Path file =
                fixture.put(
                        "/var/lib/porelo-host/tasks/alice/one.json",
                        task("alice", "one", FINGERPRINT));
        fixture.overrides.put(file, Map.of("uid", 1000));
        assertThat(fixture.run(SUDO, "task", "show", FINGERPRINT))
                .as("a person's own substitute cannot supply a root record")
                .isEqualTo(
                        new Answer(
                                73,
                                "",
                                "porelo-host-person: "
                                        + file
                                        + " is not a plain file of root's alone\n"));
    }

    @Test
    void aGroupWritableTaskFileIsRefused() throws Exception {
        final Path file =
                fixture.put(
                        "/var/lib/porelo-host/tasks/alice/one.json",
                        task("alice", "one", FINGERPRINT));
        fixture.overrides.put(file, Map.of("mode", 0100620));
        assertThat(fixture.run(SUDO, "task", "show", FINGERPRINT))
                .as("group write permission must fail before any task is returned")
                .isEqualTo(
                        new Answer(
                                73,
                                "",
                                "porelo-host-person: "
                                        + file
                                        + " is not a plain file of root's alone\n"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-login", "wrong-task", "invalid-expiry", "invalid-json"})
    void aMalformedTaskRecordIsRefused(String defect) throws Exception {
        String record = task("alice", "one", FINGERPRINT);
        record =
                switch (defect) {
                    case "wrong-login" -> record.replace("\"alice\"", "\"bob\"");
                    case "wrong-task" -> record.replace("\"one\"", "\"two\"");
                    case "invalid-expiry" ->
                            record.replace("2030-01-02T03:04:05Z", "2030-02-30T03:04:05Z");
                    default -> "{";
                };
        final Path file = fixture.put("/var/lib/porelo-host/tasks/alice/one.json", record);
        assertThat(fixture.run(SUDO, "task", "show", FINGERPRINT))
                .as("stored names, login, and certificate end must agree before disclosure")
                .isEqualTo(
                        new Answer(
                                65,
                                "",
                                "porelo-host-person: " + file + " holds no valid task record\n"));
    }

    @Test
    void aLaterMalformedRecordStillRefusesTheWholeLookup() throws Exception {
        fixture.put("/var/lib/porelo-host/tasks/alice/one.json", task("alice", "one", FINGERPRINT));
        final Path bad = fixture.put("/var/lib/porelo-host/tasks/alice/two.json", "{}");
        assertThat(fixture.run(SUDO, "task", "show", FINGERPRINT))
                .as("Python validates its whole task_records list before searching for a match")
                .isEqualTo(
                        new Answer(
                                65,
                                "",
                                "porelo-host-person: " + bad + " holds no valid task record\n"));
    }

    @Test
    void aTaskRecordRetainsUnicodeEscapesAndLargeIntegerExtensions() throws Exception {
        final String record =
                task("alice", "one", FINGERPRINT)
                        .replace("porelo task", "café 😀\\b\\f\\\\u0008")
                        .replace(
                                "\"project\":\"alpha\"",
                                "\"project\":\"alpha\",\"serial\":9223372036854775808");
        fixture.put("/var/lib/porelo-host/tasks/alice/one.json", record);
        assertThat(fixture.run(SUDO, "task", "show", FINGERPRINT))
                .as(
                        "Python emits ASCII JSON, keeps literal backslash-u text, and never rounds an integer")
                .isEqualTo(
                        new Answer(
                                0,
                                record.replace("é", "\\u00e9").replace("😀", "\\ud83d\\ude00")
                                        + "\n",
                                ""));
    }

    @Test
    void argumentFilesAreNotExpandedInsideTheTaskCommand() throws Exception {
        final Path arguments = fixture.put("/arguments", "private-marker");
        final var answer = fixture.run(SUDO, "task", "show", "@" + arguments);
        assertThat(answer.code())
                .as("nested parsers still treat @file as an invalid fingerprint")
                .isEqualTo(64);
        assertThat(answer.out() + answer.err())
                .as("the parser never reads or prints the argument file")
                .doesNotContain("private-marker");
    }

    @Test
    void anUnknownTaskEntryRefusesTheLookup() throws Exception {
        final Path path = fixture.put("/var/lib/porelo-host/tasks/alice/unknown", "{}");
        assertThat(fixture.run(SUDO, "task", "show", FINGERPRINT))
                .as("unrecognized files cannot silently disappear from the certificate census")
                .isEqualTo(
                        new Answer(
                                65,
                                "",
                                "porelo-host-person: "
                                        + path.getParent()
                                        + " holds an unknown entry\n"));
    }

    @Test
    void anUnsafeTaskDirectoryIsRefusedBeforeListing() throws Exception {
        fixture.put("/var/lib/porelo-host/tasks/alice/one.json", task("alice", "one", FINGERPRINT));
        final Path path = fixture.path("/var/lib/porelo-host/tasks/alice");
        fixture.overrides.put(path, Map.of("mode", 040770));
        assertThat(fixture.run(SUDO, "task", "show", FINGERPRINT))
                .as("another writer cannot replace task entries during a root lookup")
                .isEqualTo(
                        new Answer(
                                73,
                                "",
                                "porelo-host-person: "
                                        + path
                                        + " is not a directory of root's alone\n"));
    }

    @Test
    void anUnrecordedSudoPersonIsRefused() throws Exception {
        Files.delete(fixture.path("/etc/porelo-host/people/alice.conf"));
        assertThat(fixture.run(SUDO, "task", "show", FINGERPRINT))
                .as("a valid account in NSS alone does not establish host admission")
                .isEqualTo(
                        new Answer(
                                65,
                                "",
                                "porelo-host-person: alice is no unlocked person of this host\n"));
    }
}
