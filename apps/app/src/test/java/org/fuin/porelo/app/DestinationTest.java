package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.agent.api.ProviderDefinition;
import org.fuin.porelo.supervisor.UnixUpstream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link Destination}: a service a credential is for that is not a model provider. */
class DestinationTest {

    private static final String SEARCH =
            """
            name: brave-search
            label: Brave Search
            upstream: https://api.search.brave.com
            auth_header: X-Subscription-Token
            """;

    @Test
    void readsWhereTheServiceIsAndWhereItsKeyGoes() {
        final Destination search = Destination.parse(SEARCH);

        assertThat(search.name()).isEqualTo("brave-search");
        assertThat(search.host()).isEqualTo("api.search.brave.com");
        assertThat(search.authHeader()).isEqualTo("X-Subscription-Token");
        assertThat(search.authPrefix()).isEmpty();
        assertThat(search.authQuery()).isNull();
    }

    @Test
    void aKeyMayGoInTheUrlInstead() {
        final Destination maps =
                Destination.parse(
                        """
                name: maps
                upstream: https://maps.example.com
                auth_query: key
                """);

        assertThat(maps.authQuery()).isEqualTo("key");
    }

    @Test
    void refusesAServiceReachedInTheClear() {
        assertThatThrownBy(
                        () -> Destination.parse("name: plain\nupstream: http://api.example.com\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("over https");
    }

    @Test
    void findsTheUsersOwnBeforeThePackagedOnesAndSkipsWhatItCannotRead(@TempDir Path dataHome)
            throws IOException {
        Files.createDirectories(dataHome.resolve("destinations"));
        Files.writeString(dataHome.resolve("destinations/brave.yaml"), SEARCH);
        Files.writeString(dataHome.resolve("destinations/broken.yaml"), "name: [");

        assertThat(Destination.all(dataHome)).containsOnlyKeys("brave-search");
    }

    @Test
    void aProviderIsADestinationToo() {
        final ProviderDefinition anthropic =
                new ProviderDefinition(
                        "anthropic",
                        "Anthropic",
                        "https://api.anthropic.com",
                        Map.of("anthropic-messages", ""),
                        Map.of("_default", "x-api-key"),
                        Map.of("_default", ""),
                        Map.of(),
                        Map.of());

        final Destination resolved =
                Destination.resolve("anthropic", Map.of(), Map.of("anthropic", anthropic), null);

        assertThat(resolved).isNotNull();
        assertThat(resolved.authHeader()).isEqualTo("x-api-key");
        assertThat(Destination.resolve("nobody", Map.of(), Map.of("anthropic", anthropic), null))
                .isNull();
    }

    @Test
    void anEndpointOnThisHostIsASocketInTheRuntimeDirectory(@TempDir Path dir) {
        final Path runtime = dir.resolve("porelo");
        final Destination mcp =
                Destination.parse(
                        "name: porelo-mcp\nupstream: unix:"
                                + runtime
                                + "/mcp/task-1.sock\nauth_prefix: \"Bearer \"\n");

        assertThat(mcp.socket()).isEqualTo(runtime.resolve("mcp/task-1.sock"));
        assertThat(mcp.host()).isEqualTo(runtime.resolve("mcp/task-1.sock").toString());
        assertThat(mcp.reachableUnder(runtime)).isSameAs(mcp);
        assertThat(Destination.parse(mcp.yaml())).isEqualTo(mcp);
        assertThat(Destination.parse(SEARCH).socket()).isNull();
    }

    @Test
    void refusesASocketByItsFormWhereverItIsRead() {
        assertRefused("unix:porelo/mcp/task-1.sock", UnixUpstream.Refusal.RELATIVE);
        assertRefused("unix:/run/user/1000/porelo/mcp/../task-1.sock", UnixUpstream.Refusal.PARENT);
    }

    @Test
    void refusesASocketOutsideTheRuntimeDirectory(@TempDir Path dir) throws IOException {
        final Path runtime = dir.resolve("porelo");
        Files.createDirectories(runtime);
        Files.createSymbolicLink(
                runtime.resolve("mcp"), Files.createDirectories(dir.resolve("elsewhere")));

        assertThatThrownBy(
                        () ->
                                Destination.parse(
                                                "name: mcp\nupstream: unix:"
                                                        + dir
                                                        + "/elsewhere/task-1.sock\n")
                                        .reachableUnder(runtime))
                .isInstanceOfSatisfying(
                        UnixUpstream.Refused.class,
                        refused -> {
                            assertThat(refused.reason()).isEqualTo(UnixUpstream.Refusal.OUTSIDE);
                            assertThat(refused).hasMessageStartingWith("Destination 'mcp'");
                        });
        assertThatThrownBy(
                        () ->
                                Destination.parse(
                                                "name: mcp\nupstream: unix:"
                                                        + runtime
                                                        + "/mcp/task-1.sock\n")
                                        .reachableUnder(runtime))
                .isInstanceOfSatisfying(
                        UnixUpstream.Refused.class,
                        refused ->
                                assertThat(refused.reason()).isEqualTo(UnixUpstream.Refusal.LINK));
    }

    @Test
    void anAddressIsStillHttpsAlone() {
        assertThatThrownBy(() -> Destination.parse("name: f\nupstream: file:/run/x.sock\n"))
                .hasMessageContaining("over https");
        assertThatThrownBy(() -> Destination.parse("name: u\nupstream: unixx:/run/x.sock\n"))
                .hasMessageContaining("over https");
    }

    private static void assertRefused(String upstream, UnixUpstream.Refusal reason) {
        assertThatThrownBy(() -> Destination.parse("name: mcp\nupstream: " + upstream + "\n"))
                .isInstanceOfSatisfying(
                        UnixUpstream.Refused.class,
                        refused -> assertThat(refused.reason()).isEqualTo(reason));
    }
}
