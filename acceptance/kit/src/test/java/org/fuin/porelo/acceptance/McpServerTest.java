package org.fuin.porelo.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** porelo-mcp's pin, and where its install puts it. */
class McpServerTest {

    @Test
    void theKitPinsBothPackagesOfOneBuild() {
        final MessageFilter.Source source =
                MessageFilter.source(
                        McpServer.PORELO_MCP, MessageFilter.pin(McpServer.PORELO_MCP), Map.of());

        assertThat(source).isInstanceOf(MessageFilter.Pinned.class);
        final MessageFilter.Pinned pinned = (MessageFilter.Pinned) source;
        assertThat(pinned.deb().url()).startsWith("https://").endsWith("_amd64.deb");
        assertThat(pinned.rpm().url()).startsWith("https://").endsWith(".x86_64.rpm");
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
                                MessageFilter.source(
                                        McpServer.PORELO_MCP,
                                        MessageFilter.pin(McpServer.PORELO_MCP),
                                        Map.of())))
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
                                        Map.of("PORELO_ACCEPTANCE_MCP", "relative")));

        assertThat(refused)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("The MCP server porelo-mcp cannot be installed")
                .hasMessageContaining("PORELO_ACCEPTANCE_MCP_SHA256");
    }
}
