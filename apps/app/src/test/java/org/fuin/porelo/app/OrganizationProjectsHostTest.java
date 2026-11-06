package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}
