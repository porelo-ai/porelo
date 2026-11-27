package org.fuin.porelo.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** porelo-mcp's pin, and where its install puts it. */
class McpServerTest {

    @Test
    void theKitNamesWhereArtifactoryKeepsBothPackages() {
        final java.util.Properties origin = MessageFilter.origin(McpServer.PORELO_MCP);

        assertThat(origin.getProperty("artifactory")).as("Artifactory").startsWith("https://");
        assertThat(origin.getProperty("deb.name"))
                .as("the .deb's name")
                .isEqualTo("porelo-mcp_{version}_amd64.deb");
        assertThat(origin.getProperty("rpm.name"))
                .as("the .rpm's name")
                .isEqualTo("porelo-mcp-{version}-1.x86_64.rpm");
    }

    @Test
    void itGoesBesideTheAccountsOwnPorelodFromWhereThePackagesHoldIt() {
        final String command =
                MessageFilter.command(
                        McpServer.PORELO_MCP,
                        new MessageFilter.Override("/opt/porelo-mcp", "0".repeat(64)));

        assertThat(command)
                .contains("dir=\"$HOME/.local/bin\"")
                .contains("target=\"$dir/porelo-mcp\"")
                .contains("fail() { printf '%s: %s\\n' 'porelo-mcp'");
        assertThat(
                        MessageFilter.command(
                                McpServer.PORELO_MCP,
                                new MessageFilter.Published(
                                        new MessageFilter.Package(
                                                "https://packages.invalid/porelo-mcp.deb",
                                                "0".repeat(64)),
                                        new MessageFilter.Package(
                                                "https://packages.invalid/porelo-mcp.rpm",
                                                "0".repeat(64)))))
                .contains("\"$work/root/usr/bin/porelo-mcp\"");
    }

    @Test
    void anOverrideNamesItsOwnVariables() {
        final Throwable refused =
                org.assertj.core.api.Assertions.catchThrowable(
                        () ->
                                MessageFilter.source(
                                        McpServer.PORELO_MCP,
                                        new java.util.Properties(),
                                        Map.of("PORELO_ACCEPTANCE_MCP", "relative"),
                                        url -> {
                                            throw new java.io.IOException("not asked: " + url);
                                        }));

        assertThat(refused)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("The MCP server porelo-mcp cannot be installed")
                .hasMessageContaining("PORELO_ACCEPTANCE_MCP_SHA256");
    }
}
