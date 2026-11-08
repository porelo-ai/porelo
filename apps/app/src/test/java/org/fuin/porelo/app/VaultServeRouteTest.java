package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.fuin.porelo.supervisor.UnixUpstream;
import org.fuin.porelo.supervisor.VaultProxy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for the routes {@code porelo vault serve} takes: where the gateway sends a credential. */
class VaultServeRouteTest {

    @Test
    void takesASocketInThePersonsRuntimeDirectory(@TempDir Path dir) {
        final Path runtime = dir.resolve("porelo");

        final VaultProxy.Route route =
                VaultServeCommand.route(
                        "mcp",
                        "unix:" + runtime + "/mcp/task-1.sock|Authorization|Bearer |",
                        runtime);

        assertThat(route.upstream()).isEqualTo("unix:" + runtime + "/mcp/task-1.sock");
        assertThat(route.authHeader()).isEqualTo("Authorization");
        assertThat(route.authPrefix()).isEqualTo("Bearer ");
    }

    @Test
    void refusesASocketAnywhereElse(@TempDir Path dir) {
        final Path runtime = dir.resolve("porelo");

        assertThatThrownBy(
                        () ->
                                VaultServeCommand.route(
                                        "mcp",
                                        "unix:" + dir + "/other/task-1.sock|Authorization|Bearer |",
                                        runtime))
                .isInstanceOfSatisfying(
                        UnixUpstream.Refused.class,
                        refused ->
                                assertThat(refused.reason())
                                        .isEqualTo(UnixUpstream.Refusal.OUTSIDE));
    }

    @Test
    void anAddressIsStillHttpsAlone(@TempDir Path dir) {
        assertThatThrownBy(
                        () ->
                                VaultServeCommand.route(
                                        "search", "http://api.example.com|X-Key||", dir))
                .hasMessageContaining("must be https");
    }
}
