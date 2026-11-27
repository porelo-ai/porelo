package org.fuin.porelo.build.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewServerTest {

    @TempDir Path directory;

    @Test
    void anAdapterThatReadsReviewsServesExactlyTheMethodsTheReviewDescriptionDeclares() {
        final Set<String> declared =
                Pattern.compile("(?m)^method (\\w+)\\(")
                        .matcher(ReviewMethods.idl())
                        .results()
                        .map(match -> ReviewProtocol.INTERFACE + "." + match.group(1))
                        .collect(Collectors.toSet());
        assertThat(declared)
                .as("the review contract declares its six methods and none that reaches a verdict")
                .containsExactlyInAnyOrder(
                        ReviewProtocol.DESCRIBE,
                        ReviewProtocol.PULL_REQUEST,
                        ReviewProtocol.PULL_REQUEST_OF,
                        ReviewProtocol.COMMENTS,
                        ReviewProtocol.POST,
                        ReviewProtocol.RESOLVE);
        try (BuildServer server =
                new BuildServer(new FakeReviewReader(), directory.resolve("r.sock"))) {
            assertThat(
                            server.methodNames().stream()
                                    .filter(
                                            name ->
                                                    name.startsWith(
                                                            ReviewProtocol.INTERFACE + ".")))
                    .as("registered review methods match the published description")
                    .containsExactlyInAnyOrderElementsOf(declared);
        }
    }

    @Test
    void anAdapterThatReadsNoReviewServesTheBuildContractAlone() {
        try (BuildServer server = new BuildServer(new FakeReader(), directory.resolve("r.sock"))) {
            assertThat(server.methodNames())
                    .as("no review method on a build reader alone")
                    .noneMatch(name -> name.startsWith(ReviewProtocol.INTERFACE + "."));
        }
    }

    @Test
    void buildAndReviewAnswerOnOneSocketAndOneConnection() throws IOException {
        try (BuildServer server =
                new BuildServer(new FakeReviewReader(), directory.resolve("r.sock"))) {
            Thread.ofPlatform().daemon().start(server::serve);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {

                assertThat(client.call(ReviewProtocol.DESCRIBE, Map.of()))
                        .as("the review contract's version and the forge")
                        .isEqualTo(
                                Map.of(
                                        "protocolVersion",
                                        (long) ReviewProtocol.VERSION,
                                        "forge",
                                        "fake"));
                assertThat(
                                client.call(
                                        BuildProtocol.HEAD,
                                        Map.of(
                                                "upstream", "git@forge.example:owner/name.git",
                                                "api", "",
                                                "token", "test-token",
                                                "branch", "main")))
                        .as("Build2 still answers beside it")
                        .isEqualTo(Map.of("commit", "a".repeat(40)));
                assertThat(client.call("org.varlink.service.GetInfo", Map.of()).get("interfaces"))
                        .as("both contracts are listed")
                        .asInstanceOf(
                                org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
                        .contains(BuildProtocol.INTERFACE, ReviewProtocol.INTERFACE);
                assertThat(
                                client.call(
                                                "org.varlink.service.GetInterfaceDescription",
                                                Map.of("interface", ReviewProtocol.INTERFACE))
                                        .get("description"))
                        .as("the review description is served as the jar carries it")
                        .isEqualTo(ReviewMethods.idl());
            }
        }
    }
}
