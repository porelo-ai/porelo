package org.fuin.porelo.acceptance;

import io.cucumber.java.Before;
import java.io.IOException;

/**
 * Installs porelo-mcp into each account of the run, before its first scenario, the way {@link
 * MessageFilter} installs the filter.
 *
 * <p><strong>Without it no task of a project with an {@code mcp} section starts.</strong> Porelo
 * gives such a task its own endpoint through porelo-mcp and refuses the start on a machine without
 * it, so a run without one fails each such scenario at its start, as something else.
 *
 * <p><strong>Beside the account's own porelod.</strong> It goes into {@code $XDG_BIN_HOME}, where
 * an account's install puts porelod and where porelod looks first, executable by the account alone.
 *
 * <p><strong>The newest snapshot.</strong> The kit's {@code mcp-server.properties} names where
 * Artifactory keeps the packages, and the run takes the newest snapshot there with the sha256
 * Artifactory names for it; {@code PORELO_ACCEPTANCE_MCP} and {@code PORELO_ACCEPTANCE_MCP_SHA256}
 * name an executable on the machine and its digest instead, for a build not yet published.
 */
public final class McpServer {

    /** porelo-mcp, as porelod looks for it beside itself. */
    static final MessageFilter.Tool PORELO_MCP =
            new MessageFilter.Tool(
                    "The MCP server",
                    "porelo-mcp",
                    "org/fuin/porelo/acceptance/mcp-server.properties",
                    "PORELO_ACCEPTANCE_MCP",
                    "PORELO_ACCEPTANCE_MCP_SHA256",
                    "usr/bin/porelo-mcp",
                    "$HOME/.local/bin");

    /**
     * Installs porelo-mcp into the account this scenario runs as, the first time that account is
     * seen in the run.
     *
     * @throws IOException If the machine cannot be reached.
     * @throws AssertionError If it cannot be installed; it names porelo-mcp and where it was to
     *     come from.
     */
    @Before(order = 0)
    public void installIntoTheAccount() throws IOException {
        MessageFilter.install(PORELO_MCP, Accounts.forThisThread());
    }
}
