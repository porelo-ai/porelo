package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.runtime.ImageLayers;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Checks the policy files actually handed to the hooks, without a container runtime. */
class HostServiceWiringTest {

    @TempDir Path dir;

    private Path start(String declaration, String address) throws IOException {
        final Project project =
                ProjectReader.read(
                        new StringReader(
                                """
                project:
                  name: local
                  security_class: guarded
                image:
                  base_image: ubuntu:24.04
                """
                                        + declaration),
                        "local.yml");
        final PoreloPaths paths =
                new PoreloPaths(
                        XdgPaths.of(
                                name ->
                                        switch (name) {
                                            case "XDG_CONFIG_HOME" ->
                                                    dir.resolve("config").toString();
                                            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                            case "XDG_STATE_HOME" ->
                                                    dir.resolve("state").toString();
                                            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                            default -> null;
                                        },
                                dir),
                        dir.resolve("bin"));
        new TaskRunner(new FakeCommandRunner().answering(" create ", "0123456789abcdef"), paths)
                .start(
                        project,
                        project.repository("backend"),
                        "porelo-local-service",
                        ImageLayers.none(),
                        Map.of(),
                        List.of("external.example"),
                        new TaskWiring(address, 9418, null, null),
                        new PrintWriter(new StringWriter()));
        return paths.tasks().containerState("porelo-local-service");
    }

    @Test
    void carriesProjectAndRepositoryHostServicesToBothPolicies() throws IOException {
        final Path state =
                start(
                        """
                egress:
                  host_services: [forge.example]
                repositories:
                  backend:
                    egress:
                      host_services: [registry.example]
                """,
                        "192.0.2.5");
        assertThat(Files.readString(state.resolve("dns.conf")))
                .as("DNS uses the gate's probed fallback address for every effective service")
                .contains(
                        "host-record=forge.example,192.0.2.5",
                        "host-record=registry.example,192.0.2.5");
        assertThat(Files.readString(state.resolve("ruleset.nft")))
                .as("the hook gets the matching rule beside its gate")
                .contains("ip daddr 192.0.2.5 tcp dport 443 accept");
    }

    @Test
    void aProjectWithoutHostServicesGetsNeitherDnsRecordsNorTheRule() throws IOException {
        final Path state =
                start(
                        """
                repositories:
                  backend:
                """,
                        "169.254.1.2");
        assertThat(Files.readString(state.resolve("dns.conf")))
                .as("no declaration means no local DNS answer")
                .doesNotContain("host-record=");
        assertThat(Files.readString(state.resolve("ruleset.nft")))
                .as("no declaration means the gate is still the only open host port")
                .doesNotContain("ip daddr 169.254.1.2 tcp dport 443 accept")
                .contains("ip daddr 169.254.1.2 tcp dport 9418 accept");
    }
}
