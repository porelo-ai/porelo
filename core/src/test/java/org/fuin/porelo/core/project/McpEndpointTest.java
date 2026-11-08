package org.fuin.porelo.core.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Tests for the {@code mcp} section of a project file, which allows a task an MCP endpoint. */
class McpEndpointTest {

    private static final String HEAD =
            "project:\n  name: \"p\"\n  security_class: \"guarded\"\nimage:\n  from:"
                    + " \"ubuntu:24.04\"\n";

    private static Optional<McpEndpoint> read(final String section) {
        return ProjectReader.endpoint(new StringReader(HEAD + section), "project.yml");
    }

    @Test
    void aFileWithoutTheSectionAllowsNoEndpoint() {
        assertThat(read("")).isEmpty();
    }

    @Test
    void aSectionNamingItsMethodsAndAgentsTakesTheDefaultLimits() {
        final McpEndpoint endpoint =
                read("mcp:\n  methods: [\"List\", \"Start\", \"Stop\"]\n  agents:\n    alpha:\n")
                        .orElseThrow();

        assertThat(endpoint.methods()).containsExactly("List", "Start", "Stop");
        assertThat(endpoint.tasks()).isEqualTo(6);
        assertThat(endpoint.minutes()).isEqualTo(240);
        assertThat(endpoint.agents()).isEqualTo(Map.of("alpha", List.of()));
    }

    @Test
    void aSectionNamingNoAgentIsRefused() {
        // Which agents a started task may use is the file's to say: Porelo names no agent itself.
        assertThatThrownBy(() -> read("mcp:\n  methods: [\"Start\"]\n"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("'mcp.agents' is required");
    }

    @Test
    void eachLimitIsTheFilesWhenItSaysOne() {
        final McpEndpoint endpoint =
                read("mcp:\n  methods: [\"Start\"]\n  tasks: 2\n  minutes: 30\n"
                                + "  agents:\n    alpha:\n    other: [\"small\", \"large\"]\n")
                        .orElseThrow();

        assertThat(endpoint.tasks()).isEqualTo(2);
        assertThat(endpoint.minutes()).isEqualTo(30);
        assertThat(endpoint.agents())
                .isEqualTo(Map.of("alpha", List.of(), "other", List.of("small", "large")));
        assertThat(endpoint.allows("alpha", null))
                .as("an agent named alone takes any model")
                .isTrue();
        assertThat(endpoint.allows("alpha", "whatever")).isTrue();
        assertThat(endpoint.allows("other", "small")).isTrue();
        assertThat(endpoint.allows("other", "medium")).isFalse();
        assertThat(endpoint.allows("other", null))
                .as("an agent whose models are listed is started with one of them named")
                .isFalse();
        assertThat(endpoint.allows("third", null)).isFalse();
    }

    @Test
    void aSectionWithoutMethodsIsRefused() {
        assertThatThrownBy(() -> read("mcp:\n  tasks: 2\n"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("'mcp.methods' is required");
        assertThatThrownBy(() -> read("mcp:\n  methods: []\n"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("'mcp.methods' is required");
    }

    @Test
    void aValueThatAllowsNothingOrIsNoMethodNameIsRefused() {
        assertThatThrownBy(() -> read("mcp:\n  methods: [\"Start\"]\n  tasks: 0\n"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("'mcp.tasks'");
        assertThatThrownBy(() -> read("mcp:\n  methods: [\"Start\"]\n  minutes: 0\n"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("'mcp.minutes'");
        assertThatThrownBy(() -> read("mcp:\n  methods: [\"org.fuin.porelo.Tasks1.Start\"]\n"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("'mcp.methods'")
                .hasMessageContaining("without the interface");
        assertThatThrownBy(() -> read("mcp:\n  methods: [\"Start\"]\n  agents:\n    alpha: []\n"))
                .as("an empty list of models would allow the agent nothing")
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("'mcp.agents.alpha'");
        assertThatThrownBy(
                        () -> read("mcp:\n  methods: [\"Start\"]\n  agents:\n    Not_An_Agent:\n"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("'mcp.agents.Not_An_Agent'");
        assertThatThrownBy(() -> read("mcp:\n  methods: [\"Start\"]\n  method: [\"List\"]\n"))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("did you mean 'methods'");
    }

    @Test
    void aRepositorysOwnFileCannotAllowAnEndpoint() {
        assertThatThrownBy(
                        () ->
                                org.fuin.porelo.core.project.file.RepositorySettings.read(
                                        new StringReader(HEAD),
                                        "project.yml",
                                        null,
                                        List.of(
                                                new org.fuin.porelo.core.project.file
                                                        .RepositorySettings.Layer(
                                                        "porelo.yml of shop",
                                                        "mcp:\n  methods: [\"Start\"]\n"))))
                .isInstanceOf(ProjectException.class)
                .hasMessageContaining("'mcp' is the project's to say, not a repository's");
    }

    @Test
    void theSchemaDescribesEveryKeyOfTheSection() {
        assertThat(ProjectReader.schema())
                .containsEntry("mcp", List.of("agents", "methods", "minutes", "tasks"));
        assertThat(ProjectReader.openSections()).contains("mcp.agents");
        assertThat(ProjectReader.keys())
                .filteredOn(key -> key.section().equals("mcp") && key.name().equals("tasks"))
                .singleElement()
                .satisfies(key -> assertThat(key.defaultValue()).isEqualTo("6"));
        assertThat(ProjectReader.keys())
                .filteredOn(key -> key.section().equals("mcp") && key.name().equals("minutes"))
                .singleElement()
                .satisfies(key -> assertThat(key.defaultValue()).isEqualTo("240"));
    }

    @Test
    void theSectionIsWrittenBackAsItWasRead() {
        final String text =
                HEAD
                        + "mcp:\n  methods: [\"Start\"]\n  agents:\n    alpha:\n"
                        + "    other: [\"small\"]\n  tasks: 2\n";
        final var file =
                org.fuin.porelo.core.project.file.ProjectFileReader.read(
                        new StringReader(text), "project.yml");
        final String written = org.fuin.porelo.core.project.file.ProjectFileWriter.write(file);
        assertThat(
                        org.fuin.porelo.core.project.file.ProjectFileReader.read(
                                        new StringReader(written), "again.yml")
                                .mcp())
                .isEqualTo(file.mcp());
    }
}
