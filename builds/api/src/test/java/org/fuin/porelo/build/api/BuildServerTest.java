package org.fuin.porelo.build.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildServerTest {

    @TempDir Path directory;

    @Test
    void servesExactlyTheMethodsThePublishedInterfaceDeclares() {
        // A contract consumer must not call a described method the adapter does not serve.
        final Set<String> declared =
                Pattern.compile("(?m)^method (\\w+)\\(")
                        .matcher(BuildServer.idl())
                        .results()
                        .map(match -> BuildProtocol.INTERFACE + "." + match.group(1))
                        .collect(Collectors.toSet());
        assertThat(declared)
                .as("the published contract declares its four operations")
                .containsExactlyInAnyOrder(
                        BuildProtocol.DESCRIBE,
                        BuildProtocol.HEAD,
                        BuildProtocol.BUILD,
                        BuildProtocol.OPEN_PULL_REQUEST);
        try (BuildServer server = new BuildServer(new FakeReader(), directory.resolve("r.sock"))) {
            assertThat(
                            server.methodNames().stream()
                                    .filter(name -> name.startsWith(BuildProtocol.INTERFACE + ".")))
                    .as("registered build methods match the published contract")
                    .containsExactlyInAnyOrderElementsOf(declared);
        }
    }

    @Test
    void acceptsARequestForJobsWithoutLogs() throws IOException {
        // Refusing none would force a contract consumer to download unwanted logs.
        try (BuildServer server = new BuildServer(new FakeReader(), directory.resolve("r.sock"))) {
            Thread.ofPlatform().daemon().start(server::serve);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                final Build answer =
                        Build.of(
                                client.call(
                                        BuildProtocol.BUILD,
                                        Map.of(
                                                "upstream", "git@forge.example:owner/name.git",
                                                "api", "",
                                                "token", "test-token",
                                                "commit", "f".repeat(40),
                                                "logs", "none")));

                assertThat(answer)
                        .as("none reaches the adapter and preserves jobs without logs")
                        .isEqualTo(
                                new Build(
                                        Build.FAILURE,
                                        List.of(new Build.Job("Build / unit tests", "failure")),
                                        ""));
            }
        }
    }
}
