package org.fuin.porelo.ecosystem.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The published interface, read as the jar carries it. */
class ContractTest {

    private static String idl() throws IOException {
        try (InputStream in =
                EcosystemProtocol.class.getResourceAsStream(
                        EcosystemProtocol.INTERFACE + ".varlink")) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void declaresTheFourMethodsTheProtocolNames() throws IOException {
        assertThat(
                        Pattern.compile("(?m)^method (\\w+)\\(")
                                .matcher(idl())
                                .results()
                                .map(match -> EcosystemProtocol.INTERFACE + "." + match.group(1)))
                .containsExactlyInAnyOrder(
                        EcosystemProtocol.DESCRIBE,
                        EcosystemProtocol.DETECT,
                        EcosystemProtocol.RESOLVE,
                        EcosystemProtocol.CONFIGURE);
    }

    @Test
    void declaresEveryFieldWithItsTypeAndEveryError() throws IOException {
        final String declared =
                idl().replaceAll("(?m)^#.*$", "")
                        .replaceAll("\\s+", " ")
                        .replace("( ", "(")
                        .replace(" )", ")");

        assertThat(declared)
                .contains("interface " + EcosystemProtocol.INTERFACE)
                .contains("method Describe() -> (protocolVersion: int, ecosystem: string)")
                .contains(
                        "type Finding (part: (TOOLS, RULES), key: string, value: string,"
                                + " file: string, line: int, origin: (YOURS, CHECKOUT, MACHINE),"
                                + " unknown: bool, note: string)")
                .contains("type Unplaced (file: string, line: int, note: string)")
                .contains(
                        "method Detect(directory: string, home: string)"
                                + " -> (findings: []Finding, unplaced: []Unplaced)")
                .contains(
                        "method Resolve(directory: string, home: string, findings: []Finding)"
                                + " -> (findings: []Finding)")
                .contains("type Repository (name: string, url: string)")
                .contains("type File (path: string, content: string, ownerOnly: bool)")
                .contains("method Configure(repositories: []Repository) -> (files: []File)")
                .contains("error NotRecognized ()")
                .contains("error Unreadable (detail: string)");
    }

    @Test
    void statesEveryRefusalOfAFilesPath() throws IOException {
        assertThat(idl().replaceAll("\\s*\\n# ", " "))
                .contains("relative to the task user's home")
                .contains("names joined by single slashes")
                .contains("none blank, \".\" or \"..\"")
                .contains("no quote, backslash or control character");
    }

    @Test
    void statesTheBoundsAndTheProxyVariablesOfAResolve() throws IOException {
        assertThat(idl().replaceAll("\\s*\\n# ", " "))
                .as("a plugin keeps the request bound, and only Resolve opens a connection")
                .contains("within " + EcosystemProtocol.RESOLVE_REQUEST_SECONDS + " seconds")
                .contains(EcosystemProtocol.RESOLVE_CALL_SECONDS + " seconds for the whole answer")
                .contains("HTTP_PROXY, HTTPS_PROXY, NO_PROXY and their lower-case forms")
                .contains("opens a connection in Resolve alone");
        assertThat(EcosystemProtocol.PROXY_VARIABLES)
                .containsExactly(
                        "HTTP_PROXY",
                        "HTTPS_PROXY",
                        "NO_PROXY",
                        "http_proxy",
                        "https_proxy",
                        "no_proxy");
    }

    @Test
    void namesEachRefusalAsTheContractDoes() throws IOException {
        for (final EcosystemRefused.Reason reason : EcosystemRefused.Reason.values()) {
            assertThat(idl()).contains("error " + reason.wire() + " (");
            assertThat(EcosystemRefused.Reason.of(EcosystemProtocol.error(reason)))
                    .isEqualTo(reason);
        }
        assertThat(EcosystemRefused.Reason.of("org.varlink.service.MethodNotFound")).isNull();
    }

    @Test
    void startsWithTheLowestVersionItsOwn() {
        assertThat(EcosystemProtocol.LOWEST).isEqualTo(1);
        assertThat(EcosystemProtocol.VERSION).isGreaterThanOrEqualTo(EcosystemProtocol.LOWEST);
    }
}
