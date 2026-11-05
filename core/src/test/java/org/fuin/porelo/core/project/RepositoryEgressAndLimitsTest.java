package org.fuin.porelo.core.project;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What a task on one repository may reach and consume: the project's, and nothing a repository's
 * entry says, since egress and limits are the project's alone.
 */
class RepositoryEgressAndLimitsTest {

    private static Project read(String yaml) {
        return ProjectReader.read(new StringReader(yaml), "test");
    }

    /** A project that raises memory and pids away from the defaults, so a fallback is visible. */
    private static final String HEAD =
            """
            project:
              name: "acme"
              security_class: "guarded"
            image:
              from: "ubuntu:24.04"
            limits:
              memory: "16g"
              cpus: "4.0"
              pids: 4096
            egress:
              sets: [maven]
              domains: ["nexus.corp.example"]
            """;

    @ParameterizedTest
    @ValueSource(strings = {"egress:\n      sets: [flutter]", "limits:\n      memory: \"32g\""})
    void aRepositorysOwnEgressOrLimitsIsRefusedAtItsLine(String block) {
        // Read and ignored, either would look applied; read and applied, egress would widen what
        // a task of that repository reaches beyond the project's own grants.
        assertThatThrownBy(() -> read(HEAD + "repositories:\n  frontend:\n    " + block + "\n"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("test:" + (HEAD.lines().count() + 3) + ":")
                .hasMessageContaining("are the project's alone, at the top level");
    }
}
