package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.vault.HostCard;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.SigningKey;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for {@code porelo card make-host} and {@code porelo card verify-host}, with keys {@code
 * ssh-keygen} wrote. Harm guarded: a host card taken on its word sends a person's ssh, git and
 * messages to whoever wrote the file; one the host admin cannot make on their own machine is made
 * where their key is not.
 */
class CardHostCommandTest {

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private final SigningKey admin = SigningKey.generate("admin");

    private final String hostKey = PersonCard.keyLine(SigningKey.generate("host"));

    @TempDir Path dir;

    /** What {@code SSH_AUTH_SOCK} says to the command, or {@code null} for no agent at all. */
    private @Nullable String agentSocket;

    private PoreloContext context() {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(
                new ProcessCommandRunner(),
                new PoreloPaths(xdg, dir.resolve("bin")),
                args -> 0,
                name -> "SSH_AUTH_SOCK".equals(name) ? agentSocket : System.getenv(name));
    }

    private int card(final String... args) {
        out.getBuffer().setLength(0);
        err.getBuffer().setLength(0);
        final CommandLine cmd = PoreloCli.commandLine(context());
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        final List<String> all = new ArrayList<>(List.of("card"));
        all.addAll(List.of(args));
        return cmd.execute(all.toArray(String[]::new));
    }

    private Path file(final String name, final String content) throws Exception {
        return Files.writeString(dir.resolve(name), content);
    }

    private static String pin(final SigningKey key) {
        return PersonCard.fingerprint(PersonCard.keyLine(key));
    }

    /** A key pair as the host admin has it, from {@code ssh-keygen}. */
    private Path keygen(final String name, final String passphrase) throws Exception {
        final Path key = dir.resolve(name);
        new ProcessCommandRunner()
                .runOrFail(
                        Command.of(
                                "ssh-keygen",
                                "-q",
                                "-t",
                                "ed25519",
                                "-N",
                                passphrase,
                                "-C",
                                "admin@laptop",
                                "-f",
                                key.toString()));
        return key;
    }

    private static String pinOf(final Path key) throws Exception {
        final String[] pub =
                Files.readString(Path.of(key + ".pub"), StandardCharsets.UTF_8).split(" ");
        return PersonCard.fingerprint(pub[0] + " " + pub[1]);
    }

    private List<String> makeHost(final String host, final Path key, final Path hostKeyFile) {
        return makeHost(host, hostKeyFile, "--key", key.toString());
    }

    private List<String> makeHost(
            final String host, final Path hostKeyFile, final String... signing) {
        final List<String> args =
                new ArrayList<>(
                        List.of(
                                "make-host",
                                "--host",
                                host,
                                "--address",
                                "walk-9.example.org",
                                "--ssh-port",
                                "2201",
                                "--ssh-host-key",
                                hostKeyFile.toString(),
                                "--forge-web-port",
                                "3000",
                                "--forge-git-port",
                                "2222",
                                "--matrix-port",
                                "8448"));
        args.addAll(List.of(signing));
        return args;
    }

    /** Runs a real {@code ssh-agent} holding the given keys, which are then deleted from disk. */
    private Process agent(final Path... keys) throws Exception {
        final Path socket = dir.resolve("agent.sock");
        final Process agent =
                new ProcessBuilder("ssh-agent", "-D", "-a", socket.toString())
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
        for (int i = 0; i < 100 && !Files.exists(socket); i++) {
            Thread.sleep(20);
        }
        for (final Path key : keys) {
            final ProcessBuilder add = new ProcessBuilder("ssh-add", "-q", key.toString());
            add.environment().put("SSH_AUTH_SOCK", socket.toString());
            assertThat(
                            add.redirectErrorStream(true)
                                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                    .start()
                                    .waitFor())
                    .isZero();
            // Gone from disk: only the agent holds it now.
            Files.delete(key);
        }
        agentSocket = socket.toString();
        return agent;
    }

    @Test
    void make_host_writes_a_card_verify_host_accepts_with_the_makers_key() throws Exception {
        final Path key = keygen("admin", "");
        final Path hostKeyFile =
                keygen("ssh_host_ed25519_key", "").resolveSibling("ssh_host_ed25519_key.pub");
        final String hostKeyLine =
                String.join(
                        " ",
                        List.of(Files.readString(hostKeyFile).strip().split(" ")).subList(0, 2));

        assertThat(card(makeHost("walk-9", key, hostKeyFile).toArray(String[]::new)))
                .as(err.toString())
                .isZero();
        final Path made = file("walk-9.card", out.toString());
        assertThat(card("verify-host", made.toString(), "--signer", pinOf(key), "--json"))
                .as(err.toString())
                .isZero();

        final Map<?, ?> answer = (Map<?, ?>) Json.parse(out.toString());
        assertThat(answer.get("host")).isEqualTo("walk-9");
        assertThat(answer.get("address")).isEqualTo("walk-9.example.org");
        assertThat(answer.get("ssh_port")).isEqualTo(2201.0);
        assertThat(answer.get("ssh_host_key")).isEqualTo(hostKeyLine);
        assertThat(answer.get("services"))
                .isEqualTo(
                        Map.of(
                                "forge", Map.of("web_port", 3000.0, "git_port", 2222.0),
                                "matrix", Map.of("port", 8448.0)));
        assertThat(answer.get("signed_by")).isEqualTo(pinOf(key));
    }

    @Test
    void ssh_keygen_checks_the_signature_of_a_made_card() throws Exception {
        final Path key = keygen("admin", "");
        final Path hostKeyFile = file("host.pub", hostKey + " root@walk-9\n");
        assertThat(card(makeHost("walk-9", key, hostKeyFile).toArray(String[]::new)))
                .as(err.toString())
                .isZero();
        final String made = out.toString();
        final int start = made.indexOf("-----BEGIN SSH SIGNATURE-----");
        final Path body = file("body", made.substring(0, start));
        final Path signature = file("body.sig", made.substring(start));
        final Path signers =
                file("allowed", "admin " + Files.readString(Path.of(key + ".pub")).strip() + "\n");

        final CommandResult result =
                new ProcessCommandRunner()
                        .run(
                                Command.of(
                                        "sh",
                                        "-c",
                                        "ssh-keygen -Y verify -n porelo -I admin -f \"$1\" -s"
                                                + " \"$2\" < \"$3\"",
                                        "sh",
                                        signers.toString(),
                                        signature.toString(),
                                        body.toString()));

        assertThat(result.exitCode()).as(result.trimmedOutput()).isZero();
    }

    @Test
    void a_card_signed_by_another_key_is_refused_with_65_naming_both() throws Exception {
        final SigningKey other = SigningKey.generate("other");
        final Path made =
                file(
                        "walk-9.card",
                        HostCard.write(
                                "walk-9",
                                "walk-9.example.org",
                                22,
                                hostKey,
                                3000,
                                2222,
                                8448,
                                other));

        assertThat(card("verify-host", made.toString(), "--signer", pin(admin), "--json"))
                .isEqualTo(65);
        assertThat(err.toString()).contains(pin(other)).contains(pin(admin));
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void a_card_changed_after_signing_is_refused_with_65() throws Exception {
        final Path made =
                file(
                        "walk-9.card",
                        HostCard.write(
                                        "walk-9",
                                        "walk-9.example.org",
                                        22,
                                        hostKey,
                                        3000,
                                        2222,
                                        8448,
                                        admin)
                                .replace("port: 8448", "port: 8449"));

        assertThat(card("verify-host", made.toString(), "--signer", pin(admin))).isEqualTo(65);
        assertThat(err.toString()).contains("does not verify");
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void a_host_name_that_is_not_a_dns_label_is_refused_with_65_naming_it() throws Exception {
        final Path made =
                file(
                        "walk-9.card",
                        HostCard.write(
                                        "walk-9",
                                        "walk-9.example.org",
                                        22,
                                        hostKey,
                                        3000,
                                        2222,
                                        8448,
                                        admin)
                                .replace("\"walk-9\"", "\"Walk_9\""));

        assertThat(card("verify-host", made.toString(), "--signer", pin(admin))).isEqualTo(65);
        assertThat(err.toString()).contains("'Walk_9' is not a host name");
    }

    @Test
    void a_missing_port_is_refused_with_65_naming_it() throws Exception {
        final Path made =
                file(
                        "walk-9.card",
                        HostCard.write(
                                        "walk-9",
                                        "walk-9.example.org",
                                        22,
                                        hostKey,
                                        3000,
                                        2222,
                                        8448,
                                        admin)
                                .replace("    git_port: 2222\n", ""));

        assertThat(card("verify-host", made.toString(), "--signer", pin(admin))).isEqualTo(65);
        assertThat(err.toString()).contains("'services.forge.git_port' is missing");
    }

    @Test
    void a_signer_that_is_no_fingerprint_is_refused_with_65() throws Exception {
        final Path made =
                file(
                        "walk-9.card",
                        HostCard.write(
                                "walk-9",
                                "walk-9.example.org",
                                22,
                                hostKey,
                                3000,
                                2222,
                                8448,
                                admin));

        assertThat(card("verify-host", made.toString(), "--signer", "admin")).isEqualTo(65);
        assertThat(err.toString()).contains("is not a key's fingerprint");
    }

    @Test
    void a_card_that_is_no_text_is_65_and_a_file_of_two_host_keys_is_refused() throws Exception {
        final Path binary = dir.resolve("bad.card");
        Files.write(binary, new byte[] {(byte) 0xFF, (byte) 0xFE});
        assertThat(card("verify-host", binary.toString(), "--signer", pin(admin))).isEqualTo(65);

        final Path key = keygen("admin", "");
        final Path two = file("two.pub", hostKey + " a\n" + hostKey + " b\n");
        assertThat(card(makeHost("walk-9", key, two).toArray(String[]::new))).isEqualTo(65);
        assertThat(err.toString()).contains("holds 2 keys");
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void verify_host_answers_a_person_in_plain_text() throws Exception {
        final Path key = keygen("admin", "");
        final Path hostKeyFile = file("host.pub", hostKey + "\n");
        assertThat(card(makeHost("walk-9", key, hostKeyFile).toArray(String[]::new))).isZero();
        final Path made = file("walk-9.card", out.toString());
        out.getBuffer().setLength(0);

        assertThat(card("verify-host", made.toString(), "--signer", pinOf(key))).isZero();
        assertThat(out.toString()).contains("walk-9").contains("2201").contains(pinOf(key));
    }

    @Test
    void an_unreadable_card_is_66_naming_it() throws Exception {
        assertThat(card("verify-host", dir.resolve("none.card").toString(), "--signer", pin(admin)))
                .isEqualTo(66);
        assertThat(err.toString()).contains("none.card");
        final Path folder = Files.createDirectories(dir.resolve("a-folder"));
        assertThat(card("verify-host", folder.toString(), "--signer", pin(admin))).isEqualTo(66);
        assertThat(err.toString()).contains("'" + folder + "'");
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void make_host_refuses_an_empty_host_key_file_and_files_that_are_no_text() throws Exception {
        final Path key = keygen("admin", "");
        final Path hostKeyFile = file("host.pub", hostKey + "\n");
        final Path empty = file("empty.pub", "\n");
        final Path binary = dir.resolve("binary");
        Files.write(binary, new byte[] {(byte) 0xFF, (byte) 0xFE});

        assertThat(card(makeHost("walk-9", key, empty).toArray(String[]::new))).isEqualTo(65);
        assertThat(err.toString()).contains("holds 0 keys");
        assertThat(card(makeHost("walk-9", key, binary).toArray(String[]::new))).isEqualTo(65);
        assertThat(err.toString()).contains("is not a public key's text");
        assertThat(card(makeHost("walk-9", binary, hostKeyFile).toArray(String[]::new)))
                .isEqualTo(65);
        assertThat(err.toString()).contains("is not an OpenSSH private key");
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void make_host_shows_a_key_files_own_type_cleaned() throws Exception {
        final Path hostKeyFile = file("host.pub", hostKey + "\n");
        final java.io.ByteArrayOutputStream secret = new java.io.ByteArrayOutputStream();
        secret.writeBytes(new byte[8]);
        sshString(secret, "x\u001b[2J".getBytes(StandardCharsets.UTF_8));
        final java.io.ByteArrayOutputStream blob = new java.io.ByteArrayOutputStream();
        blob.writeBytes("openssh-key-v1\0".getBytes(StandardCharsets.US_ASCII));
        sshString(blob, "none".getBytes(StandardCharsets.US_ASCII));
        sshString(blob, "none".getBytes(StandardCharsets.US_ASCII));
        sshString(blob, new byte[0]);
        blob.writeBytes(java.nio.ByteBuffer.allocate(4).putInt(1).array());
        sshString(blob, new byte[0]);
        sshString(blob, secret.toByteArray());
        final Path odd =
                file(
                        "odd",
                        "-----BEGIN OPENSSH PRIVATE KEY-----\n"
                                + java.util.Base64.getEncoder().encodeToString(blob.toByteArray())
                                + "\n-----END OPENSSH PRIVATE KEY-----\n");

        assertThat(card(makeHost("walk-9", odd, hostKeyFile).toArray(String[]::new))).isEqualTo(65);
        assertThat(err.toString()).contains("x?[2J").doesNotContain("\u001b");
    }

    private static void sshString(final java.io.ByteArrayOutputStream out, final byte[] value) {
        out.writeBytes(java.nio.ByteBuffer.allocate(4).putInt(value.length).array());
        out.writeBytes(value);
    }

    @Test
    void make_host_writes_nothing_for_a_bad_host_name_or_a_key_it_cannot_sign_with()
            throws Exception {
        final Path key = keygen("admin", "");
        final Path locked = keygen("locked", "a passphrase");
        final Path hostKeyFile = file("host.pub", hostKey + "\n");

        assertThat(card(makeHost("Walk_9", key, hostKeyFile).toArray(String[]::new))).isEqualTo(65);
        assertThat(err.toString()).contains("'Walk_9' is not a host name");
        assertThat(out.toString()).isEmpty();

        assertThat(card(makeHost("walk-9", locked, hostKeyFile).toArray(String[]::new)))
                .isEqualTo(65);
        assertThat(err.toString())
                .contains("is not an unencrypted Ed25519 OpenSSH key")
                .contains("protected by a passphrase")
                .doesNotContain("ssh-keygen -p");
        assertThat(out.toString()).isEmpty();

        assertThat(
                        card(
                                makeHost("walk-9", dir.resolve("none"), hostKeyFile)
                                        .toArray(String[]::new)))
                .isEqualTo(66);
        assertThat(err.toString()).contains("cannot read the key '" + dir.resolve("none") + "'");
        assertThat(card(makeHost("walk-9", key, dir.resolve("none.pub")).toArray(String[]::new)))
                .isEqualTo(66);
        assertThat(err.toString()).contains("cannot read the host key '" + dir.resolve("none.pub"));
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void make_host_signs_through_the_agent_the_same_card_a_key_file_signs() throws Exception {
        // Harm guarded: a host admin whose key lives only in their agent writes it to disk to sign.
        final Path key = keygen("admin", "");
        final Path pub = Path.of(key + ".pub");
        final Path hostKeyFile = file("host.pub", hostKey + "\n");
        assertThat(card(makeHost("walk-9", key, hostKeyFile).toArray(String[]::new))).isZero();
        final String fromFile = out.toString();
        final Process agent = agent(key);
        try {
            assertThat(
                            card(
                                    makeHost("walk-9", hostKeyFile, "--agent-key", pinOf(key))
                                            .toArray(String[]::new)))
                    .as(err.toString())
                    .isZero();
            final String byFingerprint = out.toString();
            assertThat(
                            card(
                                    makeHost("walk-9", hostKeyFile, "--agent-key", pub.toString())
                                            .toArray(String[]::new)))
                    .as(err.toString())
                    .isZero();
            assertThat(out.toString()).isEqualTo(byFingerprint);
            assertThat(byFingerprint).isEqualTo(fromFile);

            final Path made = file("walk-9.card", byFingerprint);
            assertThat(card("verify-host", made.toString(), "--signer", pinOf(key)))
                    .as(err.toString())
                    .isZero();
        } finally {
            agent.destroy();
        }
    }

    @Test
    void make_host_refuses_with_65_naming_the_key_an_agent_does_not_hold() throws Exception {
        // Harm guarded: a card signed by whichever key the agent holds, not the one named.
        final Path other = keygen("other", "");
        final Path key = keygen("admin", "");
        final Path hostKeyFile = file("host.pub", hostKey + "\n");
        final Process agent = agent(other);
        try {
            assertThat(
                            card(
                                    makeHost("walk-9", hostKeyFile, "--agent-key", pinOf(key))
                                            .toArray(String[]::new)))
                    .isEqualTo(65);
            assertThat(err.toString())
                    .contains("the ssh agent does not hold the key " + pinOf(key));
            assertThat(out.toString()).isEmpty();
        } finally {
            agent.destroy();
        }
    }

    @Test
    void make_host_refuses_with_65_naming_the_key_when_no_agent_answers() throws Exception {
        // Harm guarded: an absent agent read as a key refused, or a card written unsigned.
        final Path key = keygen("admin", "");
        final Path hostKeyFile = file("host.pub", hostKey + "\n");
        final String[] args =
                makeHost("walk-9", hostKeyFile, "--agent-key", pinOf(key)).toArray(String[]::new);

        agentSocket = null;
        assertThat(card(args)).isEqualTo(65);
        assertThat(err.toString())
                .contains("no ssh agent answers for " + pinOf(key))
                .contains("SSH_AUTH_SOCK is not set");
        assertThat(out.toString()).isEmpty();

        agentSocket = dir.resolve("no-agent.sock").toString();
        assertThat(card(args)).isEqualTo(65);
        assertThat(err.toString()).contains("no ssh agent answers for " + pinOf(key));
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void make_host_takes_exactly_one_of_key_and_agent_key_else_64() throws Exception {
        // Harm guarded: one of two keys signing silently, or a card asked for with no key at all.
        final Path key = keygen("admin", "");
        final Path hostKeyFile = file("host.pub", hostKey + "\n");

        assertThat(
                        card(
                                makeHost(
                                                "walk-9",
                                                hostKeyFile,
                                                "--key",
                                                key.toString(),
                                                "--agent-key",
                                                pinOf(key))
                                        .toArray(String[]::new)))
                .isEqualTo(64);
        assertThat(err.toString()).contains("exactly one of --key FILE and --agent-key");
        assertThat(out.toString()).isEmpty();

        assertThat(card(makeHost("walk-9", hostKeyFile).toArray(String[]::new))).isEqualTo(64);
        assertThat(err.toString()).contains("exactly one of --key FILE and --agent-key");
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void make_host_answers_66_for_an_agent_key_file_it_cannot_read() throws Exception {
        // Harm guarded: a mistyped .pub path taken for a key the agent lacks, sending the admin
        // to look for a key where the file is the fault.
        final Path hostKeyFile = file("host.pub", hostKey + "\n");
        final Path none = dir.resolve("admin.pub");

        assertThat(
                        card(
                                makeHost("walk-9", hostKeyFile, "--agent-key", none.toString())
                                        .toArray(String[]::new)))
                .isEqualTo(66);
        assertThat(err.toString()).contains("cannot read the public key '" + none + "'");
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void a_card_made_goes_out_only_when_it_verifies_as_its_signers() throws Exception {
        // Harm guarded: an agent answering with another key's signature, or with bytes that verify
        // as nothing, and the card printed as if the admin had signed it.
        final Path key = keygen("admin", "");
        final Path other = keygen("other", "");
        final Path hostKeyFile = file("host.pub", hostKey + "\n");

        assertThat(card(makeHost("walk-9", key, hostKeyFile).toArray(String[]::new))).isZero();
        final String made = out.toString();

        final String otherPin = pinOf(other);
        assertThat(CardMakeHostCommand.unverified(made, pinOf(key))).isEmpty();
        assertThat(CardMakeHostCommand.unverified(made, otherPin))
                .hasValueSatisfying(
                        why ->
                                assertThat(why)
                                        .contains("does not verify as signed by " + otherPin));
    }

    @Test
    void make_host_takes_an_agent_key_file_only_as_itself_never_through_a_link() throws Exception {
        // Harm guarded: a link named as the admin's .pub picking another key the agent holds.
        final Path hostKeyFile = file("host.pub", hostKey + "\n");
        final Path real = file("real.pub", PersonCard.keyLine(admin) + "\n");
        final Path link = dir.resolve("link.pub");
        Files.createSymbolicLink(link, real);

        assertThat(
                        card(
                                makeHost("walk-9", hostKeyFile, "--agent-key", link.toString())
                                        .toArray(String[]::new)))
                .isEqualTo(66);
        assertThat(err.toString()).contains("no regular file");
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void make_host_reads_a_signing_key_or_host_key_only_as_itself_never_through_a_link()
            throws Exception {
        // Harm guarded: a link named as a key file signing with, or signing in, another key.
        final Path key = keygen("admin", "");
        final Path keyLink = dir.resolve("admin-link");
        Files.createSymbolicLink(keyLink, key);
        final Path hostKeyFile = file("host.pub", hostKey + "\n");
        final Path hostLink = dir.resolve("host-link.pub");
        Files.createSymbolicLink(hostLink, hostKeyFile);

        assertThat(card(makeHost("walk-9", keyLink, hostKeyFile).toArray(String[]::new)))
                .isEqualTo(66);
        assertThat(err.toString()).contains("'" + keyLink + "'").contains("no regular file");
        assertThat(out.toString()).isEmpty();
        assertThat(card(makeHost("walk-9", key, hostLink).toArray(String[]::new))).isEqualTo(66);
        assertThat(err.toString()).contains("'" + hostLink + "'").contains("no regular file");
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void make_host_answers_65_for_a_key_file_too_large_to_be_a_key() throws Exception {
        // Harm guarded: a readable file that is no key told as one that cannot be read.
        final Path key = keygen("admin", "");
        final Path large = file("large.pub", hostKey + "\n" + "#".repeat(16 * 1024) + "\n");

        assertThat(card(makeHost("walk-9", key, large).toArray(String[]::new))).isEqualTo(65);
        assertThat(err.toString()).contains("'" + large + "' is not a public key's text");
        assertThat(out.toString()).isEmpty();
    }

    @Test
    void make_host_shows_a_refusals_file_name_without_its_controls() throws Exception {
        // Harm guarded: a file name's escape sequence rewriting the admin's terminal.
        final Path hostKeyFile = file("host.pub", hostKey + "\n");
        final Path none = dir.resolve("key\u001b[2J");

        assertThat(card(makeHost("walk-9", none, hostKeyFile).toArray(String[]::new)))
                .isEqualTo(66);
        assertThat(err.toString()).doesNotContain("\u001b").contains("key?[2J");
    }
}
