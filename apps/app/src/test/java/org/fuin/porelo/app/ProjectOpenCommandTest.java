package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Opening a project. Harm guarded: a repository made where nobody asked for one or public by
 * default, a forge's refusal read as something else, or a first file Porelo itself cannot read.
 */
class ProjectOpenCommandTest {

    private HttpServer server;

    private int status = 201;

    private final List<String> requests = new ArrayList<>();

    private final List<String> bodies = new ArrayList<>();

    private final List<String> tokens = new ArrayList<>();

    @BeforeEach
    void forge() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    requests.add(
                            exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
                    bodies.add(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    tokens.add(
                            String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
                    final byte[] answer = "{}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(status, answer.length);
                    exchange.getResponseBody().write(answer);
                    exchange.close();
                });
        server.start();
    }

    @AfterEach
    void gone() {
        server.stop(0);
    }

    private URI base() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    private ForgeRepositoryMaker maker() {
        return new ForgeRepositoryMaker(
                base(), HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build());
    }

    @Test
    void onGitHubARepositoryIsMadePrivateAndEmptyInTheOrganization() {
        maker().make(URI.create("https://github.com"), "tok", "acme", "demo");

        assertThat(requests).containsExactly("POST /orgs/acme/repos");
        assertThat(bodies.getFirst())
                .contains("\"name\":\"demo\"")
                .contains("\"private\":true")
                .as("a commit nobody signed would stand before the first signed one")
                .doesNotContain("\"auto_init\":true");
        assertThat(tokens.getFirst()).contains("tok");
    }

    @Test
    void onAnyOtherForgeItIsMadeThroughForgejosApiOfThatForge() {
        maker().make(base(), "tok", "acme", "demo");

        assertThat(requests).containsExactly("POST /api/v1/orgs/acme/repos");
        assertThat(bodies.getFirst())
                .contains("\"name\":\"demo\"")
                .contains("\"private\":true")
                .contains("\"default_branch\":\"main\"")
                .doesNotContain("\"auto_init\":true");
    }

    private void refused(final int answering, final String reason) {
        status = answering;
        for (final URI forge : List.of(URI.create("https://github.com"), base())) {
            assertThatThrownBy(() -> maker().make(forge, "tok", "acme", "demo"))
                    .isInstanceOfSatisfying(
                            Refused.class, ex -> assertThat(ex.reason()).isEqualTo(reason));
        }
    }

    @Test
    void whatAForgeRefusesIsAnsweredByWhatItRefused() {
        refused(401, Refused.NOT_ALLOWED);
        refused(403, Refused.NOT_ALLOWED);
        refused(409, Refused.EXISTS);
        refused(422, Refused.EXISTS);
        refused(404, Refused.CANNOT_SAY);
        refused(500, Refused.CANNOT_SAY);
    }

    @Test
    void theFirstFileIsOnePoreloReads() {
        assertThat(
                        ProjectReader.read(
                                        new java.io.StringReader(
                                                ProjectOpenCommand.firstFile("demo")),
                                        "first")
                                .name())
                .isEqualTo("demo");
    }

    @Test
    void aProjectsRepositoryStandsBesideTheOrganizationsReachedTheSameWay() {
        assertThat(ProjectOpenCommand.beside("git@github.com:acme/organization.git", "demo"))
                .isEqualTo("git@github.com:acme/demo.git");
        assertThat(ProjectOpenCommand.beside("ssh://git@forge.example:2222/acme/org/", "demo"))
                .isEqualTo("ssh://git@forge.example:2222/acme/demo");
        assertThat(ProjectOpenCommand.beside("https://forge.example/acme/org", "demo"))
                .isEqualTo("https://forge.example/acme/demo");
    }
}
