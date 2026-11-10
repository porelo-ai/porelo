package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostProjectPoolKeyTest {
    private static final String KEY = "private run key\n";
    private static final String SECRET = "PORELO_POOL_SSH";
    private static final String RUN_KEY = "/var/lib/porelo-host/pool/run-key";
    @TempDir Path root;

    @Test
    void aLaterPoolSendsItsKeyToEveryRegisteredProjectInOrder() throws Exception {
        try (var host = new ProjectFixture(root)) {
            register(host, "beta", "alpha");
            key(host);
            changed(host.run("pool-key", "send"), "alpha", "beta");
            assertThat(host.secrets)
                    .as("older projects receive exactly the private run key")
                    .containsEntry("alpha/" + SECRET, KEY)
                    .containsEntry("beta/" + SECRET, KEY);
            assertThat(
                            host.calls.stream()
                                    .filter(call -> call.path().endsWith("/" + SECRET))
                                    .map(ProjectFixture.Call::path))
                    .as("uploads follow sorted project records")
                    .containsExactly(
                            "/api/v1/orgs/alpha/actions/secrets/" + SECRET,
                            "/api/v1/orgs/beta/actions/secrets/" + SECRET);
            final Path sent = host.files.at(RUN_KEY + ".sent");
            assertThat(Files.readString(sent))
                    .as("the marker is the key's hex SHA-256 plus newline")
                    .isEqualTo(
                            HexFormat.of()
                                            .formatHex(
                                                    MessageDigest.getInstance("SHA-256")
                                                            .digest(
                                                                    KEY.getBytes(
                                                                            java.nio.charset
                                                                                    .StandardCharsets
                                                                                    .UTF_8)))
                                    + "\n");
            assertThat((int) Files.getAttribute(sent, "unix:mode") & 0777)
                    .as("the marker is private")
                    .isEqualTo(0600);
        }
    }

    @Test
    void aSecondSendReportsNoChangeAndMakesNoUpload() throws Exception {
        try (var host = new ProjectFixture(root)) {
            key(host);
            register(host, "alpha");
            changed(host.run("pool-key", "send"), "alpha");
            host.calls.clear();
            changed(host.run("pool-key", "send"));
            assertThat(host.calls)
                    .as("a matching marker and held secret need only listing")
                    .noneMatch(call -> call.method().equals("PUT"));
        }
    }

    @Test
    void registrationSuppliesTheSameSecretThatTheNextSendRecognizes() throws Exception {
        try (var host = new ProjectFixture(root)) {
            key(host);
            register(host, "alpha");
            changed(host.run("pool-key", "send"), "alpha");
            register(host, "beta");
            assertThat(host.secrets)
                    .as("registration uses the same pool secret")
                    .containsEntry("beta/" + SECRET, KEY);
            changed(host.run("pool-key", "send"));
        }
    }

    @Test
    void aRotatedKeyIsUploadedToEveryProjectAgain() throws Exception {
        try (var host = new ProjectFixture(root)) {
            key(host);
            register(host, "alpha", "beta");
            changed(host.run("pool-key", "send"), "alpha", "beta");
            host.write(RUN_KEY, "another run key\n");
            changed(host.run("pool-key", "send"), "alpha", "beta");
            assertThat(host.secrets)
                    .as("rotation replaces every old key")
                    .containsEntry("alpha/" + SECRET, "another run key\n")
                    .containsEntry("beta/" + SECRET, "another run key\n");
        }
    }

    @Test
    void anExposedKeyIsRefusedBeforeAnyUpload() throws Exception {
        try (var host = new ProjectFixture(root)) {
            register(host, "alpha");
            key(host);
            ProjectFixture.mode(host.files.at(RUN_KEY), 0644);
            refused(host.run("pool-key", "send"), 69);
            assertThat(host.secrets)
                    .as("a public key file is never distributed")
                    .doesNotContainKey("alpha/" + SECRET);
            assertThat(host.files.at(RUN_KEY + ".sent")).doesNotExist();
        }
    }

    @Test
    void aFailedUploadLeavesNoMarkerAndCanBeRetried() throws Exception {
        try (var host = new ProjectFixture(root)) {
            register(host, "alpha", "beta");
            key(host);
            host.replies.put(
                    "PUT /orgs/beta/actions/secrets/" + SECRET,
                    new ProjectFixture.Reply(503, null));
            refused(host.run("pool-key", "send"), 69);
            assertThat(host.secrets)
                    .as("a partial send is possible before the failed later project")
                    .containsEntry("alpha/" + SECRET, KEY)
                    .doesNotContainKey("beta/" + SECRET);
            assertThat(host.files.at(RUN_KEY + ".sent"))
                    .as("failed distribution must not suppress retry")
                    .doesNotExist();
            host.replies.clear();
            changed(host.run("pool-key", "send"), "alpha", "beta");
        }
    }

    @Test
    void withdrawalRemovesOnlyThePoolKeyAndItsRetryReportsNoChange() throws Exception {
        try (var host = new ProjectFixture(root)) {
            key(host);
            register(host, "beta", "alpha");
            final Map<String, Object> others = new java.util.HashMap<>(host.secrets);
            others.keySet().removeIf(name -> name.endsWith("/" + SECRET));
            Files.delete(host.files.at(RUN_KEY));
            changed(host.run("pool-key", "withdraw"), "alpha", "beta");
            assertThat(host.secrets)
                    .as("publisher and other secrets remain untouched")
                    .isEqualTo(others);
            changed(host.run("pool-key", "withdraw"));
        }
    }

    @Test
    void aStoppedForgeNeedsNoAdminTokenToWithdraw() throws Exception {
        try (var host = new ProjectFixture(root)) {
            key(host);
            register(host, "alpha");
            host.unavailable = true;
            host.commands.clear();
            changed(host.run("pool-key", "withdraw"));
            assertThat(host.commands)
                    .as("stopped containers cannot mint an admin token")
                    .noneMatch(command -> command.contains("generate-access-token"));
        }
    }

    @Test
    void missingForgeSettingsMakeWithdrawalAnEmptySuccess() throws Exception {
        try (var host = new ProjectFixture(root)) {
            Files.delete(host.records.conf());
            host.commands.clear();
            changed(host.run("pool-key", "withdraw"));
            assertThat(host.commands).as("absent settings require no dependency").isEmpty();
        }
    }

    @Test
    void aRefusedDeleteFailsAndKeepsTheKey() throws Exception {
        try (var host = new ProjectFixture(root)) {
            key(host);
            register(host, "alpha");
            host.replies.put(
                    "DELETE /orgs/alpha/actions/secrets/" + SECRET,
                    new ProjectFixture.Reply(503, null));
            refused(host.run("pool-key", "withdraw"), 69);
            assertThat(host.secrets)
                    .as("a refused deletion must never be reported as withdrawn")
                    .containsEntry("alpha/" + SECRET, KEY);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "show", "SEND", "withdraw alpha"})
    void onlyTheTwoDirectionsAreAccepted(String direction) throws Exception {
        try (var host = new ProjectFixture(root)) {
            refused(host.run("pool-key", direction), 64);
            assertThat(host.calls).as("invalid input reaches no forge").isEmpty();
            assertThat(host.commands).as("invalid input mints no token").isEmpty();
        }
    }

    @Test
    void aMissingSecretIsRestoredEvenWhenTheMarkerMatches() throws Exception {
        try (var host = new ProjectFixture(root)) {
            key(host);
            register(host, "alpha");
            changed(host.run("pool-key", "send"), "alpha");
            host.secrets.remove("alpha/" + SECRET);
            changed(host.run("pool-key", "send"), "alpha");
            assertThat(host.secrets).containsEntry("alpha/" + SECRET, KEY);
        }
    }

    @Test
    void anAbsentForgeAccountNeedsNoProbeOrToken() throws Exception {
        try (var host = new ProjectFixture(root)) {
            host.program = command -> new HostPrograms.Result(2, "");
            changed(host.run("pool-key", "withdraw"));
            assertThat(host.calls).as("there is no account socket to probe").isEmpty();
        }
    }

    @Test
    void anAnsweringForgeIsNotGoneWhenItsVersionEndpointRefuses() throws Exception {
        try (var host = new ProjectFixture(root)) {
            key(host);
            register(host, "alpha");
            host.replies.put("GET /api/v1/version", new ProjectFixture.Reply(403, null));
            changed(host.run("pool-key", "withdraw"), "alpha");
        }
    }

    @Test
    void sendKeepsTheInheritedLockUntilTheMarkerIsPublished() throws Exception {
        try (var host = new ProjectFixture(root)) {
            register(host, "alpha");
            key(host);
            final var locked = new AtomicBoolean();
            host.locking =
                    (conf, descriptor) -> {
                        assertThat(conf).isEqualTo(host.records.conf());
                        assertThat(descriptor).isEqualTo(7);
                        locked.set(true);
                        return () -> {
                            assertThat(host.files.at(RUN_KEY + ".sent"))
                                    .as("publication precedes unlock")
                                    .exists();
                            locked.set(false);
                        };
                    };
            host.beforeCall =
                    call -> assertThat(locked).as("all forge calls hold the project lock").isTrue();
            changed(host.run("pool-key", "send", "--lock-fd", "7"), "alpha");
            assertThat(locked).as("the inherited lock scope closes after completion").isFalse();
        }
    }

    @Test
    void anExposedMarkerCannotMakeSendTrustAPreviousDistribution() throws Exception {
        try (var host = new ProjectFixture(root)) {
            key(host);
            register(host, "alpha");
            host.write(RUN_KEY + ".sent", "untrusted marker\n");
            ProjectFixture.mode(host.files.at(RUN_KEY + ".sent"), 0644);
            refused(host.run("pool-key", "send"), 69);
            assertThat(host.calls).as("marker validation precedes all uploads").isEmpty();
        }
    }

    @Test
    void failedRotationKeepsTheOldMarkerForTheNextRetry() throws Exception {
        try (var host = new ProjectFixture(root)) {
            key(host);
            register(host, "alpha");
            changed(host.run("pool-key", "send"), "alpha");
            final String marker = Files.readString(host.files.at(RUN_KEY + ".sent"));
            host.write(RUN_KEY, "another run key\n");
            host.replies.put(
                    "PUT /orgs/alpha/actions/secrets/" + SECRET,
                    new ProjectFixture.Reply(503, null));
            refused(host.run("pool-key", "send"), 69);
            assertThat(Files.readString(host.files.at(RUN_KEY + ".sent")))
                    .as("only a successful distribution may replace the old digest")
                    .isEqualTo(marker);
        }
    }

    @Test
    void universalNewlinesInTheRunKeyAreReadAsLineFeeds() throws Exception {
        try (var host = new ProjectFixture(root)) {
            key(host);
            host.write(RUN_KEY, "private run key\r\n");
            register(host, "alpha");
            assertThat(host.secrets)
                    .as("registration uses the same normalized secret")
                    .containsEntry("alpha/" + SECRET, KEY);
            changed(host.run("pool-key", "send"), "alpha");
            host.write(RUN_KEY, KEY);
            changed(host.run("pool-key", "send"));
        }
    }

    private static void key(ProjectFixture host) throws Exception {
        host.directories("/var/lib/porelo-host/pool");
        host.write(RUN_KEY, KEY);
    }

    private static void register(ProjectFixture host, String... names) {
        for (String project : names) {
            final var answer =
                    host.run(
                            "add",
                            project,
                            project.equals("alpha") ? "alice" : "bob",
                            "--signed-by",
                            ProjectFixture.KEY);
            assertThat(answer.code()).as("registration succeeds: %s", answer.err()).isZero();
        }
        host.calls.clear();
    }

    private static void changed(ProjectFixture.Answer answer, String... names) {
        assertThat(answer.code()).as("pool-key succeeds: %s", answer.err()).isZero();
        assertThat(answer.err()).as("success has no diagnostic").isEmpty();
        assertThat(answer.out())
                .as("the reply is compact JSON plus newline, byte for byte")
                .isEqualTo(
                        ProjectRecords.pythonJson(Map.of("changed", List.of(names)), true) + "\n");
        assertThat(answer.out() + answer.err())
                .as("private key material stays out of replies")
                .doesNotContain("private run key", "another run key");
    }

    private static void refused(ProjectFixture.Answer answer, int code) {
        assertThat(answer.code()).as("pool-key refuses: %s", answer.err()).isEqualTo(code);
        assertThat(answer.out()).as("failure never reports partial success").isEmpty();
        assertThat(answer.err())
                .startsWith("porelo-host-project: ")
                .doesNotContain("private run key", "another run key");
    }
}
