package org.fuin.porelo.core.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The project file's declarations of services on this host. */
class HostServicesTest {

    @ParameterizedTest
    @ValueSource(strings = {"offline", "guarded", "online"})
    void readsHostServicesInEverySecurityClass(String securityClass) {
        final String yaml =
                """
                project:
                  name: host-services
                  security_class: %s
                %s
                image:
                  from: ubuntu:24.04
                egress:
                  host_services: [forge.example, registry.example]
                """
                        .formatted(
                                securityClass,
                                securityClass.equals("online")
                                        ? "  upstream: https://upstream.example/repo.git"
                                        : "");
        assertThat(
                        ProjectReader.read(new StringReader(yaml), "host-services.yml")
                                .egress()
                                .hostServices())
                .as("local services are available in %s", securityClass)
                .containsExactly("forge.example", "registry.example");
    }

    @Test
    void aRepositoryTakesTheProjectsHostServices() {
        final Project project =
                read(
                        """
                  host_services: [forge.example]
                repositories:
                  backend: {}
                """);
        assertThat(project.egressFor(project.repository("backend")).hostServices())
                .as("egress is the project's alone, so each repository's task has the project's")
                .containsExactly("forge.example");
    }

    private static Project read(String egress) {
        return ProjectReader.read(
                new StringReader(
                        """
                project:
                  name: host-services
                  security_class: guarded
                image:
                  from: ubuntu:24.04
                egress:
                """
                                + egress),
                "host-services.yml");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "evil.example\\nserver=/#/8.8.8.8",
                "evil.example/../..",
                "*.example.com",
                "http://evil.example",
                "evil example",
                "UPPER.example.com",
                "localhost"
            })
    void refusesAnInvalidHostServiceName(String name) {
        assertThatThrownBy(() -> read("  host_services: [\"" + name + "\"]\n"))
                .as("a host service uses the domain grammar: %s", name)
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("egress.host_services");
    }

    @Test
    void refusesANameInBothDomainsAndHostServices() {
        assertThatThrownBy(
                        () ->
                                read(
                                        """
                  domains: [forge.example]
                  host_services: [forge.example]
                """))
                .as("a name cannot mean both upstream DNS and this host")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("forge.example")
                .hasMessageContaining("egress.domains")
                .hasMessageContaining("egress.host_services");
    }

    @Test
    void refusesAScalarInsteadOfAHostServicesList() {
        assertThatThrownBy(() -> read("  host_services: forge.example\n"))
                .as("a host service declaration is a list like domains")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("egress.host_services")
                .hasMessageContaining("must be a list");
    }
}
