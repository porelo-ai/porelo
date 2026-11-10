package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatePushToCentralTest {
    @Test
    void mcpNeverOffersThePersonsReleaseEvenWhenListed(@TempDir Path dir) throws Exception {
        // A task must never spend the person's central credential to release its own work.
        final Path list = dir.resolve("writing");
        Files.writeString(list, "PushToCentral\n");
        final var answer = McpMethods.answer(list, Set.of("PushToCentral"));
        assertThat(answer.get("writing")).isEqualTo(List.of());
        assertThat(answer.get("never").toString()).contains("PushToCentral");
    }

    @Test
    void centralAuthenticationIsATypedWireError() {
        // Git authentication failures must not escape as prompt text or unstructured errors.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                GateMethods.pushToCentral(
                                        (progress, open) -> {
                                            throw new org.fuin.porelo.app.HostGatherRefusal(
                                                    org.fuin.porelo.app.HostGatherRefusal.Kind
                                                            .CENTRAL_TOKEN_REFUSED,
                                                    1,
                                                    "fatal: could not read Username secret-fixture",
                                                    java.util.Map.of(
                                                            "credential",
                                                            "forge.example.central",
                                                            "repository",
                                                            "first",
                                                            "forge",
                                                            "central.example",
                                                            "status",
                                                            401));
                                        },
                                        new Captured(true)))
                .isInstanceOfSatisfying(
                        org.fuin.porelo.wire.varlink.VarlinkException.class,
                        error -> {
                            assertThat(error.getErrorName())
                                    .isEqualTo(PoreloDaemon.INTERFACE + ".CentralTokenRefused");
                            assertThat(error.getParameters())
                                    .containsEntry("credential", "forge.example.central")
                                    .containsEntry("forge", "central.example")
                                    .containsEntry("status", 401);
                            assertThat(error.getParameters().toString())
                                    .doesNotContain("Username", "secret-fixture");
                        });
    }

    @Test
    void unexpectedFailuresCannotSendTheirMessages() {
        // An unexpected child or callback exception must not reach VarlinkServer's message
        // renderer.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                GateMethods.pushToCentral(
                                        (progress, open) -> {
                                            throw new IllegalStateException(
                                                    "central-secret local-secret");
                                        },
                                        new Captured(true)))
                .isInstanceOfSatisfying(
                        org.fuin.porelo.wire.varlink.VarlinkException.class,
                        error -> {
                            assertThat(error.getErrorName())
                                    .isEqualTo(PoreloDaemon.INTERFACE + ".HostPushRefused");
                            assertThat(error.getParameters().toString())
                                    .doesNotContain("central-secret", "local-secret");
                        });
    }

    @Test
    void progressAndFinalResultsCrossTheBoundaryWithoutProcessText() throws Exception {
        // The window must see confirmed completion separately from an in-progress remote write.
        final var replies = new Captured(true);
        GateMethods.pushToCentral(
                (progress, open) -> {
                    progress.stage("first", "pushing");
                    return new org.fuin.porelo.app.HostGatherPush.Result(
                            List.of("first"), List.of("second"));
                },
                replies);
        assertThat(replies.events)
                .containsExactly(
                        java.util.Map.of("repository", "first", "stage", "pushing"),
                        java.util.Map.of(
                                "pushed", List.of("first"), "upToDate", List.of("second")));
        assertThat(replies.ended).isTrue();
    }

    @Test
    void oneShotReleaseGetsOnlyTheFinalResult() throws Exception {
        // Sending intermediate replies without more can make a client mistake progress for
        // completion.
        final var replies = new Captured(false);
        GateMethods.pushToCentral(
                (progress, open) -> {
                    progress.stage("first", "pushing");
                    return new org.fuin.porelo.app.HostGatherPush.Result(
                            List.of("first"), List.of());
                },
                replies);
        assertThat(replies.events)
                .containsExactly(
                        java.util.Map.of("pushed", List.of("first"), "upToDate", List.of()));
        assertThat(replies.ended).isTrue();
    }

    @Test
    void oneShotCallerMayFinishSendingWhileStillReading() throws Exception {
        // A legitimate request-side EOF must not rotate a token and then cancel the release.
        final var replies = new Captured(false);
        replies.peerOpen = false;
        GateMethods.pushToCentral(
                (progress, open) -> {
                    assertThat(open.getAsBoolean()).isTrue();
                    assertThat(open.getAsBoolean()).isTrue();
                    return new org.fuin.porelo.app.HostGatherPush.Result(
                            List.of("first"), List.of());
                },
                replies);
        assertThat(replies.ended).isTrue();
    }

    static final class Captured implements org.fuin.porelo.wire.varlink.VarlinkServer.Replies {
        final boolean streaming;
        final java.util.List<java.util.Map<String, Object>> events = new java.util.ArrayList<>();
        boolean ended;
        boolean peerOpen = true;
        boolean firstOpen = true;

        Captured(boolean streaming) {
            this.streaming = streaming;
        }

        public void more(java.util.Map<String, Object> event) {
            events.add(event);
        }

        public void last(java.util.Map<String, Object> event) {
            ended = true;
            events.add(event);
        }

        public boolean streaming() {
            return streaming;
        }

        public boolean open() {
            if (firstOpen) {
                firstOpen = false;
                return true;
            }
            return peerOpen;
        }
    }
}
