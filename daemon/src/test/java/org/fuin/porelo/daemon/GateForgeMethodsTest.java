package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.app.ForgeAdapters;
import org.fuin.porelo.app.ForgeInstanceFile;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The forge instances' methods on the owner's socket. */
class GateForgeMethodsTest {

    private static final String FORGES = PoreloDaemon.INTERFACE + ".Forges";

    private static final String FORGE_SET = PoreloDaemon.INTERFACE + ".ForgeSet";

    @TempDir Path dir;

    private final List<String> log = java.util.Collections.synchronizedList(new ArrayList<>());

    @Test
    void listsTheForgesInTheOrderAddedAndTheAdaptersInstalled() throws Exception {
        installed("github", "gitlab");
        try (VarlinkServer server = serve();
                VarlinkClient client = new VarlinkClient(server.socketPath())) {
            client.call(
                    FORGE_SET,
                    Map.of("host", "github.com", "adapter", "github", "comments", "TRUSTED"));
            client.call(FORGE_SET, Map.of("host", "gitlab.com", "adapter", "gitlab"));

            final Map<String, Object> answer = client.call(FORGES, Map.of());

            assertThat(answer.get("forges"))
                    .as("each instance with its adapter and reading")
                    .isEqualTo(
                            List.of(
                                    Map.of(
                                            "host",
                                            "github.com",
                                            "adapter",
                                            "github",
                                            "comments",
                                            "TRUSTED"),
                                    Map.of(
                                            "host",
                                            "gitlab.com",
                                            "adapter",
                                            "gitlab",
                                            "comments",
                                            "EXTENDED")));
            assertThat(answer.get("adapters"))
                    .as("the adapters installed")
                    .isEqualTo(List.of("github", "gitlab"));
        }
    }

    @Test
    void aForgeSetWithoutCommentsMakesATrustedHostExtended() throws Exception {
        installed("github");
        try (VarlinkServer server = serve();
                VarlinkClient client = new VarlinkClient(server.socketPath())) {
            client.call(
                    FORGE_SET,
                    Map.of("host", "github.com", "adapter", "github", "comments", "TRUSTED"));

            client.call(FORGE_SET, Map.of("host", "github.com", "adapter", "github"));

            assertThat(client.call(FORGES, Map.of()).get("forges"))
                    .as("leaving comments out only makes the reading stricter")
                    .isEqualTo(
                            List.of(
                                    Map.of(
                                            "host",
                                            "github.com",
                                            "adapter",
                                            "github",
                                            "comments",
                                            "EXTENDED")));
        }
    }

    @Test
    void refusesAnAdapterNotInstalledAndAHostThatIsNoDnsName() throws Exception {
        installed("github");
        try (VarlinkServer server = serve();
                VarlinkClient client = new VarlinkClient(server.socketPath())) {

            assertThatThrownBy(
                            () ->
                                    client.call(
                                            FORGE_SET,
                                            Map.of("host", "gitlab.com", "adapter", "gitlab")))
                    .as("an adapter not installed")
                    .isInstanceOfSatisfying(
                            VarlinkException.class,
                            refused -> {
                                assertThat(refused.getErrorName())
                                        .as("the error")
                                        .isEqualTo(PoreloDaemon.INTERFACE + ".AdapterNotInstalled");
                                assertThat(refused.getParameters())
                                        .as("names it")
                                        .containsEntry("adapter", "gitlab");
                            });
            assertThatThrownBy(
                            () ->
                                    client.call(
                                            FORGE_SET,
                                            Map.of("host", "not a host", "adapter", "github")))
                    .as("a host that is no DNS name")
                    .isInstanceOfSatisfying(
                            VarlinkException.class,
                            refused ->
                                    assertThat(refused.getErrorName())
                                            .as("the error")
                                            .isEqualTo("org.varlink.service.InvalidParameter"));
            assertThat(dir.resolve(ForgeInstanceFile.NAME))
                    .as("nothing was written")
                    .doesNotExist();
        }
    }

    @Test
    void anUnreadableFileIsRefusedAndALoosenedOneIsNamedInTheLog() throws Exception {
        final Path file = dir.resolve(ForgeInstanceFile.NAME);
        try (VarlinkServer server = serve();
                VarlinkClient client = new VarlinkClient(server.socketPath())) {
            Files.writeString(file, "forges: [unclosed\n", StandardCharsets.UTF_8);

            assertThatThrownBy(() -> client.call(FORGES, Map.of()))
                    .as("a file that cannot be read as a whole")
                    .isInstanceOfSatisfying(
                            VarlinkException.class,
                            refused ->
                                    assertThat(refused.getErrorName())
                                            .as("the error")
                                            .isEqualTo(
                                                    PoreloDaemon.INTERFACE
                                                            + ".ForgeFileUnreadable"));
            assertThat(log).as("the log names it").anyMatch(line -> line.contains(file.toString()));

            log.clear();
            Files.writeString(
                    file,
                    "forges:\n  - host: github.com\n    adapter: github\n    comments: trusted\n",
                    StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-r--"));
            assertThat(client.call(FORGES, Map.of()).get("forges"))
                    .as("a loosened file's trusted is read extended")
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                    .singleElement()
                    .satisfies(
                            forge ->
                                    assertThat(((Map<?, ?>) forge).get("comments"))
                                            .as("its reading")
                                            .isEqualTo("EXTENDED"));
            assertThat(log).as("the log says why").anyMatch(line -> line.contains(file.toString()));
        }
    }

    private VarlinkServer serve() {
        final VarlinkServer server =
                new VarlinkServer(dir.resolve("o.sock"), PoreloDaemon.INTERFACE);
        GateMethods.registerForges(
                server,
                new ForgeInstanceFile(
                        dir.resolve(ForgeInstanceFile.NAME),
                        new ForgeAdapters(List.of(dir.resolve("builds")))),
                new ForgeAdapters(List.of(dir.resolve("builds"))),
                log::add);
        Thread.ofVirtual().start(server);
        return server;
    }

    private void installed(final String... names) throws IOException {
        final Path builds = Files.createDirectories(dir.resolve("builds"));
        for (final String name : names) {
            final Path adapter = builds.resolve(name);
            Files.writeString(adapter, "#!/bin/sh\nexit 0\n");
            Files.setPosixFilePermissions(adapter, PosixFilePermissions.fromString("rwx------"));
        }
    }
}
