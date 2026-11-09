package org.fuin.porelo.organization.gittree;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HttpForgePoolKeyTest {
    @Test
    void anUnauthenticatedProbeCannotBeUsedForAnAdministrativeRequest() {
        try (var forge =
                HttpForgeAdmin.unauthenticated(java.nio.file.Path.of("/fixture/http.sock"))) {
            assertThatThrownBy(() -> forge.sendPoolKey("alpha", "fixture key"))
                    .as("an unauthenticated client has no mutation capability")
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 302, 401, 403, 404, 503})
    void anyHttpAnswerMeansTheForgeIsPresentWithoutSendingACredential(int status) {
        try (var forge =
                new HttpForgeAdmin(
                        (method, path, authorization, body) -> {
                            assertThat(method).as("presence is a read").isEqualTo("GET");
                            assertThat(path)
                                    .as("probe the public version endpoint")
                                    .isEqualTo("/version");
                            assertThat(authorization)
                                    .as("the presence probe never authenticates")
                                    .isEmpty();
                            assertThat(body).as("the presence probe sends no body").isEmpty();
                            return new HttpForgeAdmin.Answer(
                                    status, "not JSON".getBytes(StandardCharsets.UTF_8));
                        },
                        "fixture-token".toCharArray(),
                        false)) {
            assertThat(forge.answers())
                    .as("HTTP %s still answers; JSON is irrelevant", status)
                    .isTrue();
        }
    }

    @Test
    void aTransportFailureMeansTheForgeDoesNotAnswer() {
        try (var forge =
                new HttpForgeAdmin(
                        (method, path, authorization, body) -> {
                            throw new IOException("fixture failure");
                        },
                        "fixture-token".toCharArray(),
                        false)) {
            assertThat(forge.answers()).as("a stopped forge cannot answer").isFalse();
        }
    }

    @Test
    void theSharedClientListsAllSecretPagesAndMutatesOnlyThePoolSecret() {
        final List<String> calls = new ArrayList<>();
        try (var forge =
                new HttpForgeAdmin(
                        (method, path, authorization, body) -> {
                            calls.add(method + " " + path);
                            if (method.equals("GET")) {
                                final String json =
                                        path.endsWith("page=1")
                                                ? "[{\"name\":\"OTHER\"}]"
                                                : path.endsWith("page=2")
                                                        ? "[{\"name\":\"PORELO_POOL_SSH\"}]"
                                                        : "[]";
                                return new HttpForgeAdmin.Answer(
                                        200, json.getBytes(StandardCharsets.UTF_8));
                            }
                            if (method.equals("PUT")) {
                                assertThat(
                                                Json.parseExact(
                                                        new String(body, StandardCharsets.UTF_8)))
                                        .as(
                                                "the run key travels whole in the existing JSON protocol")
                                        .isEqualTo(
                                                java.util.Map.of("data", "fixture private key\n"));
                            } else {
                                assertThat(body).as("DELETE needs no key value").isEmpty();
                            }
                            return new HttpForgeAdmin.Answer(204, new byte[0]);
                        },
                        "fixture-token".toCharArray(),
                        false)) {
            assertThat(forge.holdsPoolKey("alpha")).as("a later page cannot hide the key").isTrue();
            forge.sendPoolKey("alpha", "fixture private key\n");
            forge.withdrawPoolKey("alpha");
        }
        assertThat(calls)
                .as("both mutations use exactly the secret uploaded by registration")
                .containsExactly(
                        "GET /orgs/alpha/actions/secrets?limit=50&page=1",
                        "GET /orgs/alpha/actions/secrets?limit=50&page=2",
                        "GET /orgs/alpha/actions/secrets?limit=50&page=3",
                        "PUT /orgs/alpha/actions/secrets/PORELO_POOL_SSH",
                        "DELETE /orgs/alpha/actions/secrets/PORELO_POOL_SSH");
    }

    @ParameterizedTest
    @ValueSource(strings = {"../alpha", "alpha/other", "alpha?query", "-alpha", "alpha-"})
    void poolSecretPathsCannotEscapeTheProject(String project) {
        try (var forge =
                new HttpForgeAdmin(
                        (method, path, authorization, body) -> {
                            throw new AssertionError("invalid projects reach no forge");
                        },
                        "fixture-token".toCharArray(),
                        false)) {
            assertThatThrownBy(() -> forge.sendPoolKey(project, "fixture key"))
                    .as("the new public method validates its route component")
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
