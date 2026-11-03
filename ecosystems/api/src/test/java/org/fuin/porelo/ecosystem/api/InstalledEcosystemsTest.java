package org.fuin.porelo.ecosystem.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstalledEcosystemsTest {

    @TempDir Path directory;

    @Test
    void theUsersPluginWinsOverAPackagedPluginWithTheSameName() throws IOException {
        final Path user = plugin("user", "example", "example");
        plugin("packaged", "example", "example");

        assertThat(InstalledEcosystems.all(locations()))
                .as("the first directory supplies the executable for each name")
                .containsExactly(new InstalledEcosystems.Description("example", user, null));
    }

    @Test
    void aPluginCallingItselfAnotherEcosystemIsNamedAndDoesNotHideTheOthers() throws IOException {
        final Path liar = plugin("user", "example", "another");
        plugin("packaged", "example", "example");
        final Path healthy = plugin("packaged", "healthy", "healthy");

        final var found = InstalledEcosystems.all(locations());

        assertThat(found).as("a refused user plugin still shadows its packaged copy").hasSize(2);
        assertThat(found.getFirst().refusal())
                .as("the refusal names the executable and both ecosystem names")
                .contains(liar.toString(), "another", "example");
        assertThat(found.getLast())
                .as("a lying plugin does not stop discovery")
                .isEqualTo(new InstalledEcosystems.Description("healthy", healthy, null));
    }

    @Test
    void onlyExecutableRegularFilesWithValidNamesAreAsked() throws IOException {
        plugin("user", "a", "a");
        final String longest = "a".repeat(63);
        plugin("user", longest, longest);
        for (final String invalid : List.of("A", "1tool", "with.dot", "a".repeat(64))) {
            plugin("user", invalid, invalid);
        }
        final Path plain = plugin("user", "plain", "plain");
        Files.setPosixFilePermissions(plain, PosixFilePermissions.fromString("rw-------"));
        Files.createDirectory(directory.resolve("user/folder"));

        assertThat(InstalledEcosystems.all(locations()))
                .as(
                        "missing directories, nonexecutables, directories and invalid names add no plugin")
                .extracting(InstalledEcosystems.Description::name)
                .containsExactly("a", longest);
    }

    @Test
    void failedDescriptionsAreNamedAndDoNotStopDiscovery() throws IOException {
        executable("user", "broken", "exit 7");
        executable("user", "malformed", "printf 'not json'");
        executable("user", "old", "printf '{\"protocolVersion\":0,\"ecosystem\":\"old\"}'");
        executable("user", "oversized", "/usr/bin/head -c 65537 /dev/zero");
        plugin("packaged", "healthy", "healthy");

        final var found = InstalledEcosystems.all(locations());

        assertThat(found)
                .as("every failed plugin remains visible alongside a working one")
                .hasSize(5);
        for (final var description : found.subList(0, 4)) {
            assertThat(description.refusal())
                    .as("a failed description names its executable")
                    .contains(description.executable().toString());
        }
        assertThat(found.getFirst().refusal())
                .as("the exit code explains the failure")
                .contains("7");
        assertThat(found.getLast().refusal()).as("the healthy plugin is accepted").isNull();
    }

    @Test
    void anEcosystemNameMustBeAStringEvenWhenItsTextMatchesTheFilename() throws IOException {
        executable("user", "null", "printf '{\"protocolVersion\":1}'");
        executable("user", "true", "printf '{\"protocolVersion\":1,\"ecosystem\":true}'");

        assertThat(InstalledEcosystems.all(locations()))
                .as("missing and boolean fields cannot impersonate string ecosystem names")
                .allSatisfy(
                        description ->
                                assertThat(description.refusal())
                                        .as("each malformed description is refused")
                                        .isNotNull());
    }

    @Test
    void aPluginThatNeverDescribesItselfIsNamedAndDoesNotStopTheOthers() throws IOException {
        plugin("user", "silent", "silent");
        plugin("packaged", "healthy", "healthy");

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(15),
                () -> {
                    final var found = InstalledEcosystems.all(locations());
                    assertThat(found.getFirst().refusal())
                            .as("a timed out plugin is named with its deadline")
                            .contains("silent", "10", "seconds");
                    assertThat(found.getLast().refusal())
                            .as("discovery continues after the timed out plugin")
                            .isNull();
                });
    }

    private List<Path> locations() {
        return List.of(
                directory.resolve("user"),
                directory.resolve("missing"),
                directory.resolve("packaged"));
    }

    private Path plugin(final String location, final String name, final String reports)
            throws IOException {
        return executable(
                location,
                name,
                "exec '"
                        + Path.of(System.getProperty("java.home"), "bin/java")
                        + "' -cp '"
                        + System.getProperty("java.class.path")
                        + "' '"
                        + FakePlugin.class.getName()
                        + "' \"$@\" "
                        + reports);
    }

    private Path executable(final String location, final String name, final String body)
            throws IOException {
        final Path file = Files.createDirectories(directory.resolve(location)).resolve(name);
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
        return file;
    }

    /** A separately started plugin main whose reported name the test controls. */
    public static final class FakePlugin {
        /**
         * Describes the fake plugin.
         *
         * @param arguments The mode and name to report.
         */
        public static void main(final String[] arguments) throws InterruptedException {
            if ("silent".equals(arguments[1])) {
                Thread.sleep(java.time.Duration.ofSeconds(30));
            }
            if (!"describe".equals(arguments[0])) {
                throw new IllegalArgumentException("expected describe");
            }
            System.out.println(
                    Json.write(
                            Map.of(
                                    "protocolVersion",
                                    EcosystemProtocol.VERSION,
                                    "ecosystem",
                                    arguments[1])));
        }
    }
}
