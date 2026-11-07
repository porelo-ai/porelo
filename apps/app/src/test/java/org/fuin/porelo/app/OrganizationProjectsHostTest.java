package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.project.Builds;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** What a host's file says it carries of a project. */
class OrganizationProjectsHostTest {

    @TempDir Path dir;

    private OrganizationProjects projects() throws IOException {
        Files.createDirectories(dir.resolve("hosted"));
        return new OrganizationProjects(dir.resolve("placed"), dir.resolve("hosted"));
    }

    private void hostFile(final Map<String, Object> more) throws IOException {
        final Map<String, Object> file = new LinkedHashMap<>();
        file.put("project", "demo");
        file.put("revision", "the-organizations");
        file.put("address", "git@forge.example.org:acme/demo.git");
        file.put("signing_keys", List.of());
        file.put("host", "build");
        file.putAll(more);
        Files.writeString(dir.resolve("hosted/demo.json"), Json.write(file));
    }

    @Test
    void aHostCarryingSomeRepositoriesNamesThem() throws IOException {
        final OrganizationProjects projects = projects();
        hostFile(Map.of("repositories", List.of("api", "web")));

        assertThat(projects.host("demo"))
                .isNotNull()
                .extracting(OrganizationProjects.Placed::repositories)
                .isEqualTo(List.of("api", "web"));
    }

    @Test
    void aHostFileFromBeforeNamesNoneAndSoCarriesTheWholeProject() throws IOException {
        final OrganizationProjects projects = projects();
        hostFile(Map.of());

        assertThat(projects.host("demo"))
                .isNotNull()
                .extracting(OrganizationProjects.Placed::repositories)
                .isEqualTo(List.of());
    }

    @Test
    void aNameNoRepositoryCanHaveDoesNotRead() throws IOException {
        final OrganizationProjects projects = projects();
        hostFile(Map.of("repositories", List.of("../other")));

        assertThatThrownBy(() -> projects.host("demo"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("no repository can be called");
    }

    @Test
    void theHostsBuildsReachThePlacedProjectWithTheSameDefaultsAsTheProjectFile()
            throws IOException {
        // Losing the host's forge or credential breaks the contract the host writes for a start.
        final OrganizationProjects projects = projects();
        projects.write(
                new OrganizationProjects.Placed(
                        "demo",
                        "placed-revision",
                        "https://central.example/demo.git",
                        null,
                        List.of(),
                        null,
                        null,
                        null));
        hostFile(Map.of("builds", Map.of("forge", "host-forge", "credential", "host-builds")));

        assertThat(projects.find("demo"))
                .as("joining the placed record must retain the host's builds configuration")
                .isNotNull()
                .extracting(OrganizationProjects.Placed::builds)
                .isEqualTo(new Builds("host-forge", "host-builds", "", Builds.FAILURE));
    }

    @Test
    void aHostFileWithoutBuildsGrantsNoBuildsAccess() throws IOException {
        final OrganizationProjects projects = projects();
        hostFile(Map.of());

        assertThat(projects.host("demo"))
                .as("an older host file must start no new forge access")
                .isNotNull()
                .extracting(OrganizationProjects.Placed::builds)
                .isNull();
    }

    @Test
    void buildsSurviveWritingAndReadingThePlacedRecord() throws IOException {
        final OrganizationProjects projects = projects();
        final Builds builds =
                new Builds("host-forge", "host-builds", "https://forge.example/api", Builds.ALL);
        projects.write(
                new OrganizationProjects.Placed(
                        "demo",
                        "revision",
                        "https://central.example/demo.git",
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        List.of(),
                        builds));

        assertThat(projects.find("demo"))
                .as("writing the record must not discard the host's builds configuration")
                .isNotNull()
                .extracting(OrganizationProjects.Placed::builds)
                .isEqualTo(builds);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "[]",
                "{\"forge\":\"../reader\",\"credential\":\"builds\"}",
                "{\"forge\":\"host-forge\"}",
                "{\"forge\":\"host-forge\",\"credential\":\" \"}",
                "{\"forge\":\"host-forge\",\"credential\":42}",
                "{\"forge\":\"host-forge\",\"credential\":\"builds\",\"api\":false}",
                "{\"forge\":\"host-forge\",\"credential\":\"builds\",\"api\":\"http://forge.example/api\"}",
                "{\"forge\":\"host-forge\",\"credential\":\"builds\",\"api\":\"http://localhost:3000/api\"}",
                "{\"forge\":\"host-forge\",\"credential\":\"builds\",\"api\":\"https:forge.example\"}",
                "{\"forge\":\"host-forge\",\"credential\":\"builds\",\"api\":\"http://user@127.0.0.1/api\"}",
                "{\"forge\":\"host-forge\",\"credential\":\"builds\",\"logs\":\"none\"}",
                "{\"forge\":\"host-forge\",\"credential\":\"builds\",\"logs\":false}",
                "{\"forge\":\"host-forge\",\"credential\":\"builds\",\"unknown\":true}"
            })
    void aHostCannotBypassTheBuildsValidationWithItsFile(String builds) throws IOException {
        // An unchecked reader name or API could execute another program or expose a forge token.
        final OrganizationProjects projects = projects();
        hostFile(Map.of("builds", Json.parse(builds)));

        assertThatThrownBy(() -> projects.host("demo"))
                .as("an invalid builds block must be refused, rather than ignored: %s", builds)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("demo.json");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://forge.example/api",
                "http://127.0.0.1:3000/api",
                "http://[::1]:3000/api"
            })
    void aHostCanReadBuildsOverHttpsOrLiteralLoopbackHttp(String api) throws IOException {
        final OrganizationProjects projects = projects();
        hostFile(
                Map.of(
                        "builds",
                        Map.of("forge", "host-forge", "credential", "builds", "api", api)));

        assertThat(projects.host("demo"))
                .as("a permitted API must reach the helper without being replaced")
                .isNotNull()
                .extracting(OrganizationProjects.Placed::builds)
                .isEqualTo(new Builds("host-forge", "builds", api, Builds.FAILURE));
    }
}
