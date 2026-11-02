package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * {@code porelo org signers}. Harm guarded: what it writes decides whose commits a host's push
 * judge takes and who may certify a task there, so a revoked or a locked person's key must not be
 * in it.
 */
class OrgSignersCommandTest {

    private static final String ALICE = "ssh-ed25519 AAAAalice";

    private static final String CAROL = "ssh-ed25519 AAAAcarol";

    private static final String GONE = "ssh-ed25519 AAAAgone";

    @TempDir private Path dir;

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private static Map<String, @Nullable Object> key(
            final String login, final String key, final @Nullable String revoked) {
        final Map<String, @Nullable Object> row = new java.util.LinkedHashMap<>();
        row.put("login", login);
        row.put("role", "admin");
        row.put("key", key);
        row.put("valid_from", "2026-11-02");
        row.put("valid_until", "2028-11-02");
        row.put("revoked_since", revoked);
        return row;
    }

    private int run(final String project) throws Exception {
        final Path state = dir.resolve("state.json");
        if (!Files.exists(state)) {
            Files.writeString(
                    state,
                    Json.write(
                            Map.of(
                                    "revision",
                                    "r1",
                                    "projects",
                                    List.of(
                                            Map.of(
                                                    "project",
                                                    "demo",
                                                    "members",
                                                    List.of(
                                                            Map.of(
                                                                    "login", "alice", "locked",
                                                                    false),
                                                            Map.of(
                                                                    "login", "carol", "locked",
                                                                    true)),
                                                    "signing_keys",
                                                    List.of(
                                                            key("alice", ALICE, null),
                                                            key("alice", GONE, "2026-12-01"),
                                                            key("carol", CAROL, null)))))));
        }
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("xdg-state").toString();
                                    default -> null;
                                },
                        dir);
        final CommandLine line =
                PoreloCli.commandLine(
                        new PoreloContext(
                                new ProcessCommandRunner(),
                                new PoreloPaths(xdg, dir.resolve("bin")),
                                arguments -> 0));
        line.setOut(new PrintWriter(out));
        line.setErr(new PrintWriter(err));
        return line.execute(
                "org", "signers", "--json", "--state", state.toString(), "--project", project);
    }

    private Map<?, ?> answer() {
        return (Map<?, ?>) Json.parse(out.toString().strip());
    }

    @Test
    void onlyTheKeysInForceOfMembersNotLockedAreBuilt() throws Exception {
        assertThat(run("demo")).as("what it said: %s", err).isZero();

        assertThat(answer().get("revision")).isEqualTo("r1");
        assertThat(String.valueOf(answer().get("signers")))
                .isEqualTo(
                        "alice namespaces=\"git\",valid-after=\"20261102Z\",valid-before=\"20281102Z\" "
                                + ALICE
                                + "\n");
        assertThat(String.valueOf(answer().get("certifiers")))
                .isEqualTo(
                        "task:demo:* cert-authority,namespaces=\"git\",valid-after=\"20261102Z\","
                                + "valid-before=\"20281102Z\" "
                                + ALICE
                                + "\n");
    }

    @Test
    void whatIsBuiltParsesAsTheJudgeReadsIt() throws Exception {
        run("demo");

        final AllowedSigner signer =
                AllowedSigner.parse(String.valueOf(answer().get("signers")).strip());

        assertThat(signer).as("commit verify must read its own input").isNotNull();
        assertThat(signer.principal()).isEqualTo("alice");
        assertThat(signer.key()).isEqualTo(ALICE);
    }

    @Test
    void aProjectNotOnThisHostIsNotFound() throws Exception {
        assertThat(run("other")).isEqualTo(66);

        assertThat(answer().get("reason")).isEqualTo("not-found");
    }

    @Test
    void aStateThatIsNoneIsRefusedWithoutSayingWhatItHeld() throws Exception {
        Files.writeString(dir.resolve("state.json"), "not json secret-looking");

        assertThat(run("demo")).isEqualTo(64);

        assertThat(out + err.toString()).doesNotContain("secret-looking");
    }

    @Test
    void aDateThatIsNoDateIsRefusedRatherThanWritten() throws Exception {
        Files.writeString(
                dir.resolve("state.json"),
                Json.write(
                        Map.of(
                                "projects",
                                List.of(
                                        Map.of(
                                                "project",
                                                "demo",
                                                "members",
                                                List.of(),
                                                "signing_keys",
                                                List.of(
                                                        Map.of(
                                                                "login",
                                                                "alice",
                                                                "key",
                                                                ALICE,
                                                                "valid_from",
                                                                "\" evil",
                                                                "valid_until",
                                                                "2028-11-02")))))));

        assertThat(run("demo")).as("an option line must not be injected").isEqualTo(64);
    }

    private boolean verifiesAt(
            final String signers, final Path message, final Path signature, final String time)
            throws Exception {
        final Path allowed = dir.resolve("allowed");
        Files.writeString(allowed, signers);
        return new ProcessBuilder(
                                "ssh-keygen",
                                "-Y",
                                "verify",
                                "-f",
                                allowed.toString(),
                                "-I",
                                "alice",
                                "-n",
                                "git",
                                "-s",
                                signature.toString(),
                                "-Overify-time=" + time)
                        .redirectInput(message.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start()
                        .waitFor()
                == 0;
    }

    @Test
    void aRealSshKeygenTakesTheKeyUpToTheCardsLastDayInUtcAndNotAfter() throws Exception {
        final Path own = dir.resolve("own");
        assertThat(
                        new ProcessBuilder(
                                        "ssh-keygen",
                                        "-q",
                                        "-t",
                                        "ed25519",
                                        "-N",
                                        "",
                                        "-f",
                                        own.toString())
                                .redirectErrorStream(true)
                                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                .start()
                                .waitFor())
                .isZero();
        final String real = Files.readString(dir.resolve("own.pub")).strip();
        final Path message = Files.writeString(dir.resolve("message"), "a commit");
        assertThat(
                        new ProcessBuilder(
                                        "ssh-keygen",
                                        "-Y",
                                        "sign",
                                        "-f",
                                        own.toString(),
                                        "-n",
                                        "git",
                                        message.toString())
                                .redirectErrorStream(true)
                                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                .start()
                                .waitFor())
                .isZero();
        Files.writeString(
                dir.resolve("state.json"),
                Json.write(
                        Map.of(
                                "revision",
                                "r1",
                                "projects",
                                List.of(
                                        Map.of(
                                                "project",
                                                "demo",
                                                "members",
                                                List.of(),
                                                "signing_keys",
                                                List.of(key("alice", real, null)))))));
        run("demo");
        final String signers = String.valueOf(answer().get("signers"));
        final Path signature = dir.resolve("message.sig");

        assertThat(verifiesAt(signers, message, signature, "20281101235959Z"))
                .as("the day before the card ends")
                .isTrue();
        assertThat(verifiesAt(signers, message, signature, "20281102000001Z"))
                .as("the day the card stops being valid")
                .isFalse();
        assertThat(verifiesAt(signers, message, signature, "20261101235959Z"))
                .as("before the card begins")
                .isFalse();
    }
}
