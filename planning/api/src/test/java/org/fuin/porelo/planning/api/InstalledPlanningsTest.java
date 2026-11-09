package org.fuin.porelo.planning.api;

import static org.assertj.core.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Discovery must not silently switch implementations or accept an incompatible contract. */
class InstalledPlanningsTest {
    @TempDir Path directory;

    @Test
    void theAccountAdapterWinsEvenWhenItsDescriptionFails() throws IOException {
        Path local = executable("local", "example", "exit 7");
        described("packages", "example", "example", 1);
        Path healthy = described("packages", "healthy", "healthy", 1);
        var found = InstalledPlannings.all(locations());
        assertThat(found).as("one outcome per name, including the refused local copy").hasSize(2);
        assertThat(found.getFirst().executable())
                .as("the account's executable shadows the package")
                .isEqualTo(local);
        assertThat(found.getFirst().refusal())
                .as("failure is named and visible")
                .contains(local.toString(), "7");
        assertThat(found.getLast().executable())
                .as("one failure does not stop discovery")
                .isEqualTo(healthy);
        assertThat(found.getLast().refusal()).as("healthy description is accepted").isNull();
    }

    @Test
    void discoveryCarriesAllDescriptionFieldsAndAcceptsNewerVersions() throws IOException {
        Path local = described("local", "example", "example", 2);
        described("packages", "example", "example", 1);
        assertThat(InstalledPlannings.all(locations()))
                .as("first directory wins with its checked metadata")
                .containsExactly(
                        new InstalledPlannings.Description(
                                "example",
                                local,
                                null,
                                "1.0",
                                2,
                                List.of("write", "watch", "issues"),
                                List.of("github-app", "token")));
        assertThat(InstalledPlanning.find("example", locations()))
                .as("lookup and discovery choose the same executable")
                .isEqualTo(local);
    }

    @Test
    void malformedOldAndMismatchedDescriptionsAreRefusedByName() throws IOException {
        described("local", "old", "old", 0);
        described("local", "liar", "other", 1);
        executable("local", "malformed", "printf '%s' 'not-json'");
        executable("local", "oversized", "/usr/bin/head -c 65537 /dev/zero");
        for (var found : InstalledPlannings.all(locations())) {
            assertThat(found.refusal())
                    .as("a bad adapter remains visible with its executable named")
                    .contains(found.executable().toString());
        }
        assertThat(InstalledPlannings.all(locations()))
                .as("every attempted adapter has an outcome")
                .hasSize(4);
    }

    @Test
    void invalidNamesNonexecutablesAndDirectoriesAreNeverRun() throws IOException {
        Path valid = described("local", "valid", "valid", 1);
        described("local", "Upper", "Upper", 1);
        described("local", "with.dot", "with.dot", 1);
        Path plain = described("local", "plain", "plain", 1);
        Files.setPosixFilePermissions(plain, PosixFilePermissions.fromString("rw-------"));
        Files.createDirectory(directory.resolve("local/folder"));
        assertThat(InstalledPlannings.all(locations()))
                .as("only executable files under plain names are described")
                .extracting(InstalledPlannings.Description::executable)
                .containsExactly(valid);
        assertThatThrownBy(() -> InstalledPlanning.find("../valid", locations()))
                .as("lookup cannot escape its directory")
                .isInstanceOf(PlanningException.class);
    }

    @Test
    void requiredDescriptionFieldsCannotBeMissingOrMistyped() {
        Map<String, Object> valid =
                Map.of(
                        "protocolVersion",
                        1,
                        "name",
                        "example",
                        "version",
                        "1",
                        "capabilities",
                        List.of("watch"),
                        "credentials",
                        List.of("token"));
        for (String key : valid.keySet()) {
            var absent = new java.util.LinkedHashMap<>(valid);
            absent.remove(key);
            assertThatThrownBy(() -> InstalledPlanning.handshake(Path.of("example"), absent))
                    .as("each description field is mandatory: %s", key)
                    .isInstanceOf(PlanningException.class);
            var wrong = new java.util.LinkedHashMap<>(valid);
            wrong.put(key, true);
            assertThatThrownBy(() -> InstalledPlanning.handshake(Path.of("example"), wrong))
                    .as("each description field is typed: %s", key)
                    .isInstanceOf(PlanningException.class);
        }
        var fractional = new java.util.LinkedHashMap<>(valid);
        fractional.put("protocolVersion", 1.5);
        assertThatThrownBy(() -> InstalledPlanning.handshake(Path.of("example"), fractional))
                .as("a fractional version is never accepted")
                .isInstanceOf(PlanningException.class);
    }

    @Test
    void descriptionsNormalizeMissingVersionsAndBoundPrintableMetadata() throws IOException {
        var fields =
                new java.util.LinkedHashMap<String, Object>(
                        Map.of(
                                "protocolVersion",
                                1,
                                "name",
                                "example",
                                "version",
                                "\u001b",
                                "capabilities",
                                List.of("watch\n" + "x".repeat(300)),
                                "credentials",
                                List.of("token\u001b")));
        executable("local", "example", "printf '%s' '" + Json.write(fields) + "'");
        var found = InstalledPlannings.all(locations()).getFirst();
        assertThat(found.version()).isNull();
        assertThat(found.capabilities().getFirst())
                .hasSizeLessThanOrEqualTo(128)
                .doesNotContain("\n");
        assertThat(found.credentials().getFirst()).doesNotContain("\u001b");
        described("local", "liar", "other", 1);
        var refused = InstalledPlannings.all(locations()).getLast();
        assertThat(refused.refusal()).containsOnlyOnce(refused.executable().toString());
    }

    @Test
    void describeTimeoutNamesTheExecutableWithoutEchoingItsOutput() throws IOException {
        Path executable =
                executable("local", "slow", "printf 'untrusted-output'; exec /bin/sleep 60");
        var found = InstalledPlannings.all(locations()).getFirst();
        assertThat(found.refusal())
                .contains(executable.toString(), "10 seconds")
                .doesNotContain("untrusted-output");
    }

    private List<Path> locations() {
        return List.of(
                directory.resolve("local"),
                directory.resolve("missing"),
                directory.resolve("packages"));
    }

    private Path described(String location, String file, String name, int protocol)
            throws IOException {
        String json =
                Json.write(
                        Map.of(
                                "protocolVersion",
                                protocol,
                                "name",
                                name,
                                "version",
                                "1.0",
                                "capabilities",
                                List.of("write", "watch", "issues"),
                                "credentials",
                                List.of("github-app", "token")));
        return executable(location, file, "printf '%s' '" + json + "'");
    }

    private Path executable(String location, String name, String body) throws IOException {
        Path file = Files.createDirectories(directory.resolve(location)).resolve(name);
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
        return file;
    }
}
