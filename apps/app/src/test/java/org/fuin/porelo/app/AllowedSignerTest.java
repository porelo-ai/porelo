package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.fuin.porelo.vault.SigningKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Test for {@link AllowedSigner}. */
class AllowedSignerTest {

    private static final int UNWRITABLE = 3;

    /** All ssh-keygen gets of the environment, here and where Porelo runs it. */
    private static final Map<String, String> KEYGEN = Map.of("PATH", "/usr/bin:/bin");

    private final ProcessCommandRunner runner = new ProcessCommandRunner();

    @TempDir private Path dir;

    /**
     * ssh-keygen ends a line at a line feed alone, and reads a lone carriage return in it as a
     * blank between the principals and the key, so a file git reads is not refused.
     */
    @Test
    void reads_a_lone_carriage_return_as_ssh_keygen_does_within_one_line() throws Exception {
        final String key = keygen("alice");
        final Path signers = signers("alice\r" + key + "\n");

        assertThat(keygenVerifies(signers, "alice", "alice")).as("ssh-keygen accepts it").isTrue();
        assertThat(read(signers))
                .as("one line, as ssh-keygen reads it")
                .containsExactly(new AllowedSigner("alice", key, List.of()));
    }

    /**
     * ssh-keygen skips a line it cannot parse and reads the next, so git verifies by the lines that
     * do parse; Porelo skips the same lines rather than refusing the whole file.
     */
    @Test
    void skips_a_line_ssh_keygen_cannot_parse_as_git_does() throws Exception {
        final String key = keygen("alice");
        final String other = keygen("bob");
        final Path signers =
                signers(
                        "bob\n"
                                + "bob bogus-option "
                                + other
                                + "\n"
                                + "bob valid-before=\"never\" "
                                + other
                                + "\n"
                                + "\"bob "
                                + other
                                + "\n"
                                + "bob "
                                + other
                                + "\rtrailing\n"
                                + "alice "
                                + key
                                + "\n");

        assertThat(keygenVerifies(signers, "alice", "alice"))
                .as("ssh-keygen accepts alice")
                .isTrue();
        assertThat(keygenVerifies(signers, "bob", "bob")).as("and nobody as bob").isFalse();
        assertThat(read(signers))
                .as("the lines ssh-keygen skips are skipped")
                .containsExactly(new AllowedSigner("alice", key, List.of()));
    }

    /**
     * ssh-keygen skips only spaces, tabs and line ends before a {@code #}: a line that opens with
     * another Unicode space names a principal, which git verifies by.
     */
    @Test
    void reads_a_unicode_space_before_a_hash_as_ssh_keygen_does() throws Exception {
        final String key = keygen("alice");
        final Path signers = signers("\u2000#x " + key + "\n");

        assertThat(keygenFinds(signers, "alice"))
                .as("ssh-keygen finds the principal git verifies by")
                .isEqualTo("\u2000#x");
        assertThat(keygenVerifies(signers, "alice", "\u2000#x")).isTrue();
        assertThat(read(signers))
                .as("no comment, as ssh-keygen reads it")
                .containsExactly(new AllowedSigner("\u2000#x", key, List.of()));
        assertThat(read(signers(" \t\r#x " + key + "\n")))
                .as("a comment after the blanks ssh-keygen skips")
                .isEmpty();
    }

    /**
     * ssh-keygen ignores a key's trailing comment whatever it holds, a carriage return or a Unicode
     * line separator too, so the line names its signer.
     */
    @Test
    void reads_a_line_whose_comment_holds_a_line_end_as_ssh_keygen_does() throws Exception {
        final String key = keygen("alice");
        for (final String end : List.of("\r", "\u2028", "\u0085")) {
            final Path signers = signers("alice " + key + " comment" + end + "continued\n");

            assertThat(keygenVerifies(signers, "alice", "alice"))
                    .as("ssh-keygen accepts a comment holding U+%04X", (int) end.charAt(0))
                    .isTrue();
            assertThat(read(signers))
                    .as("the comment holding U+%04X is ignored", (int) end.charAt(0))
                    .containsExactly(new AllowedSigner("alice", key, List.of()));
        }
    }

    /**
     * Only ssh-keygen's own refusal to parse a line skips it: a check killed, or answering in any
     * other way, refuses the whole file, as skipping that line could drop a revocation git keeps.
     */
    @Test
    void refuses_the_file_when_a_line_check_fails_other_than_by_a_parse_refusal() throws Exception {
        final String key = keygen("alice");
        final Path signers =
                signers("!alice " + key + "\nbob " + key + "\nz bogus-option " + key + "\n");
        final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        // The whole file is asked first, then its first line alone: that check is killed.
        final org.fuin.porelo.core.process.CommandRunner killing =
                command ->
                        calls.incrementAndGet() == 2
                                ? new CommandResult(command, 137, "", "")
                                : runner.run(command);

        assertThat(keygenFinds(signers, "alice"))
                .as("git verifies by the first line, which it cannot verify")
                .isEqualTo("!alice");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                AllowedSigner.read(
                                        killing,
                                        signers,
                                        KEYGEN,
                                        Files.createTempDirectory(dir, "scratch-")))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("could not be checked");
    }

    /**
     * ssh-keygen answers a failed allocation as it answers a line it cannot parse, so its refusal
     * skips a line only where Porelo's own reading finds that line malformed for the same reason: a
     * line Porelo reads whole refuses the file, as skipping it could turn a refusal into an
     * acceptance.
     */
    @Test
    void refuses_the_file_when_ssh_keygen_refuses_a_line_read_here_whole() throws Exception {
        final String key = keygen("alice");
        final Path signers =
                signers("!alice " + key + "\nbob " + key + "\nz bogus-option " + key + "\n");
        final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        // The whole file is asked first, then its first line alone: that check's allocation fails.
        final org.fuin.porelo.core.process.CommandRunner failing =
                command ->
                        calls.incrementAndGet() == 2
                                ? new CommandResult(
                                        command,
                                        252,
                                        "",
                                        command.arguments().getLast() + ":1: bad options: (null)\n")
                                : runner.run(command);

        assertThat(keygenFinds(signers, "alice"))
                .as("git verifies by the first line, which it cannot verify")
                .isEqualTo("!alice");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                AllowedSigner.read(
                                        failing,
                                        signers,
                                        KEYGEN,
                                        Files.createTempDirectory(dir, "scratch-")))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("could not be checked");
    }

    /**
     * ssh-keygen parses an empty option between commas or before the first, refusing only a comma
     * that ends the options, so a line Porelo reads alike whose check answers a failed allocation
     * refuses the file rather than dropping its revocation.
     */
    @Test
    void refuses_the_file_when_ssh_keygen_refuses_a_line_whose_empty_options_it_parses()
            throws Exception {
        final String key = keygen("alice");
        assertThat(keygenMatches(signers("bob ,cert-authority " + key + "\n")))
                .as("ssh-keygen parses an empty first option")
                .isZero();
        assertThat(keygenMatches(signers("bob cert-authority,,namespaces=\"git\" " + key + "\n")))
                .as("and an empty option between two")
                .isZero();
        for (final String options : List.of("cert-authority,", ",")) {
            assertThat(keygenMatches(signers("bob " + options + " " + key + "\n")))
                    .as("but refuses %s", options)
                    .isEqualTo(252);
        }
        final Path signers =
                signers(
                        "!alice ,cert-authority "
                                + key
                                + "\nbob "
                                + key
                                + "\nz bogus-option "
                                + key
                                + "\n");
        final org.fuin.porelo.core.process.CommandRunner failing =
                refusingTheFirstLine(signers, "bad options: (null)");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                AllowedSigner.read(
                                        failing,
                                        signers,
                                        KEYGEN,
                                        Files.createTempDirectory(dir, "scratch-")))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("could not be checked");
    }

    /**
     * A key refusal skips a line only where Porelo's own reading finds its key malformed: a line it
     * reads whole, or finds malformed by its options, refuses the file.
     */
    @Test
    void refuses_the_file_when_ssh_keygen_refuses_the_key_of_a_line_whose_key_is_read_here()
            throws Exception {
        final String key = keygen("alice");
        for (final String first : List.of("!alice ", "z bogus-option ")) {
            final Path signers =
                    signers(first + key + "\nbob " + key + "\nz bogus-option " + key + "\n");
            final org.fuin.porelo.core.process.CommandRunner failing =
                    refusingTheFirstLine(signers, "invalid key");

            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () ->
                                    AllowedSigner.read(
                                            failing,
                                            signers,
                                            KEYGEN,
                                            Files.createTempDirectory(dir, "scratch-")))
                    .as("the first line %s", first)
                    .isInstanceOf(java.io.IOException.class)
                    .hasMessageContaining("could not be checked");
        }
    }

    /**
     * Runs ssh-keygen, but answers the check of a file's first line alone, asked after the whole
     * file, with a refusal to parse it for a reason.
     */
    private org.fuin.porelo.core.process.CommandRunner refusingTheFirstLine(
            final Path signers, final String reason) {
        final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        return command ->
                calls.incrementAndGet() == 2
                        ? new CommandResult(
                                command,
                                252,
                                "",
                                command.arguments().getLast() + ":1: " + reason + "\n")
                        : runner.run(command);
    }

    /**
     * ssh-keygen refuses an ed25519 key whose blob is not exactly its fields, and git skips that
     * line; Porelo reads such a blob as ssh-keygen does and skips the same line.
     */
    @Test
    void skips_a_line_whose_ed25519_blob_ssh_keygen_refuses_as_git_does() throws Exception {
        final String key = keygen("alice");
        final byte[] point = point(key);
        final List<String> broken =
                List.of(
                        key(ED25519, field(ED25519), field(point), new byte[] {0}),
                        key(ED25519, field(ED25519), field(java.util.Arrays.copyOf(point, 31))),
                        key(ED25519, field(ED25519)),
                        key(SK, field(SK), field(point)),
                        key(SK, field(SK), field(point), field("ssh:"), new byte[] {0}),
                        key(SK, field(SK), field(point), field("ss\0h:")));
        final StringBuilder text = new StringBuilder();
        for (final String each : broken) {
            assertThat(keygenMatches(signers("bob " + each + "\n")))
                    .as("ssh-keygen refuses %s", each)
                    .isEqualTo(252);
            text.append("bob ").append(each).append('\n');
        }
        final Path signers = signers(text + "alice " + key + "\n");

        assertThat(keygenVerifies(signers, "alice", "alice"))
                .as("ssh-keygen accepts alice")
                .isTrue();
        assertThat(read(signers))
                .as("the lines whose key ssh-keygen refuses are skipped")
                .containsExactly(new AllowedSigner("alice", key, List.of()));
    }

    /** A security key's blob that is exactly its fields is read, an empty application too. */
    @Test
    void reads_a_security_key_whose_blob_ssh_keygen_reads() throws Exception {
        final byte[] point = point(keygen("alice"));
        final String key = key(SK, field(SK), field(point), field(""));
        final Path signers = signers("bob " + key + "\n");

        assertThat(keygenMatches(signers)).as("ssh-keygen reads it").isZero();
        assertThat(read(signers)).containsExactly(new AllowedSigner("bob", key, List.of()));
    }

    /**
     * Porelo does not judge the blob of a key type it does not read itself, so its reading cannot
     * agree with ssh-keygen refusing one: the whole file is refused, never the line skipped.
     */
    @Test
    void refuses_the_file_when_ssh_keygen_refuses_a_key_of_a_type_not_read_here() throws Exception {
        final String key = keygen("alice");
        final String rsa = key("ssh-rsa", field("ssh-rsa"), field(new byte[] {1, 0, 1}));
        final Path signers = signers("bob " + rsa + "\nalice " + key + "\n");

        assertThat(keygenMatches(signers("bob " + rsa + "\n")))
                .as("ssh-keygen refuses the rsa key")
                .isEqualTo(252);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> read(signers))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("could not be checked");
    }

    private static final String ED25519 = "ssh-ed25519";

    private static final String SK = "sk-ssh-ed25519@openssh.com";

    /** An ed25519 public key's 32 bytes, the last field of its blob. */
    private static byte[] point(final String key) {
        final byte[] blob = java.util.Base64.getDecoder().decode(key.split(" ")[1]);
        return java.util.Arrays.copyOfRange(blob, blob.length - 32, blob.length);
    }

    /** A key line's type and the base64 of these parts joined. */
    private static String key(final String type, final byte[]... parts) {
        final java.io.ByteArrayOutputStream blob = new java.io.ByteArrayOutputStream();
        for (final byte[] part : parts) {
            blob.writeBytes(part);
        }
        return type + " " + java.util.Base64.getEncoder().encodeToString(blob.toByteArray());
    }

    /** An SSH wire string: its length, then its bytes. */
    private static byte[] field(final byte[] bytes) {
        return java.nio.ByteBuffer.allocate(4 + bytes.length)
                .putInt(bytes.length)
                .put(bytes)
                .array();
    }

    private static byte[] field(final String text) {
        return field(text.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    /** ssh-keygen's exit code when asked whether a principal of the file matches. */
    private int keygenMatches(final Path signers) {
        return runner.run(
                        Command.of(
                                        "ssh-keygen",
                                        "-Y",
                                        "match-principals",
                                        "-I",
                                        "bob",
                                        "-f",
                                        signers.toString())
                                .withChosenEnvironment(KEYGEN))
                .exitCode();
    }

    /** A check of the whole file that fails other than by a parse refusal refuses the file. */
    @Test
    void refuses_the_file_when_the_whole_check_fails_other_than_by_a_parse_refusal()
            throws Exception {
        final String key = keygen("alice");
        final Path signers = signers("alice " + key + "\n");
        final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        final org.fuin.porelo.core.process.CommandRunner failing =
                command ->
                        calls.incrementAndGet() == 1
                                ? new CommandResult(command, 255, "", "out of memory\n")
                                : runner.run(command);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                AllowedSigner.read(
                                        failing,
                                        signers,
                                        KEYGEN,
                                        Files.createTempDirectory(dir, "scratch-")))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("could not be checked");
    }

    private List<AllowedSigner> read(final Path signers) throws Exception {
        return AllowedSigner.read(
                runner, signers, KEYGEN, Files.createTempDirectory(dir, "scratch-"));
    }

    private Path signers(final String text) throws Exception {
        final Path file = Files.createTempFile(dir, "signers-", "");
        Files.writeString(file, text);
        return file;
    }

    /** Makes a throwaway key and signs a message with it; answers its public key. */
    private String keygen(final String name) throws Exception {
        final Path key = dir.resolve(name);
        keygen("-q", "-t", "ed25519", "-N", "", "-C", name, "-f", key.toString());
        Files.writeString(dir.resolve(name + ".msg"), "signed by " + name + "\n");
        keygen("-Y", "sign", "-n", "git", "-f", key.toString(), name + ".msg");
        final String[] fields = Files.readString(Path.of(key + ".pub")).strip().split(" ");
        return fields[0] + " " + fields[1];
    }

    private void keygen(final String... arguments) {
        final List<String> command = new java.util.ArrayList<>(List.of("ssh-keygen"));
        command.addAll(List.of(arguments));
        final CommandResult result =
                runner.run(Command.of(command).withChosenEnvironment(KEYGEN).in(dir));
        assertThat(result.successful()).as("ssh-keygen %s", result.standardError()).isTrue();
    }

    /** Whether ssh-keygen verifies a signer's message as by a principal. */
    private boolean keygenVerifies(final Path signers, final String signer, final String as)
            throws Exception {
        return runner.run(
                        Command.of(
                                        "ssh-keygen",
                                        "-Y",
                                        "verify",
                                        "-n",
                                        "git",
                                        "-I",
                                        as,
                                        "-f",
                                        signers.toString(),
                                        "-s",
                                        dir.resolve(signer + ".msg.sig").toString())
                                .withChosenEnvironment(KEYGEN)
                                .withInput(Files.readString(dir.resolve(signer + ".msg"))))
                .successful();
    }

    /** The principals ssh-keygen finds for a signer's signature, as git asks for them. */
    private String keygenFinds(final Path signers, final String signer) {
        return runner.run(
                        Command.of(
                                        "ssh-keygen",
                                        "-Y",
                                        "find-principals",
                                        "-f",
                                        signers.toString(),
                                        "-s",
                                        dir.resolve(signer + ".msg.sig").toString())
                                .withChosenEnvironment(KEYGEN))
                .standardOutput()
                .replaceAll("\n\\z", "");
    }

    /**
     * A scratch file that cannot be written is still removed, so a caller's directory holds no copy
     * of the signers it was asked about: the read runs in a JVM of its own that may create files
     * but not write a byte into one.
     */
    @Test
    void removes_its_check_file_when_writing_it_fails() throws Exception {
        final Path signers = dir.resolve("signers");
        Files.writeString(
                signers, "demo " + SigningKey.generate("one").authorizedKeysLine() + "\n");
        final Path scratch = Files.createDirectory(dir.resolve("scratch"));

        final CommandResult result =
                new ProcessCommandRunner()
                        .run(
                                Command.of(
                                        "bash",
                                        "-c",
                                        "ulimit -f 0 && exec \"$@\"",
                                        "bash",
                                        Path.of(System.getProperty("java.home"), "bin", "java")
                                                .toString(),
                                        "-XX:-UsePerfData",
                                        "-cp",
                                        System.getProperty("java.class.path"),
                                        UnwritableRead.class.getName(),
                                        signers.toString(),
                                        scratch.toString()));

        assertThat(result.exitCode())
                .as("the write failed as an unwritable scratch file: %s", result.standardError())
                .isEqualTo(UNWRITABLE);
        try (var left = Files.list(scratch)) {
            assertThat(left.toList()).as("the check file is gone").isEmpty();
        }
    }

    /** Reads a signers file with its scratch file in a directory, in a JVM of its own. */
    static final class UnwritableRead {

        private UnwritableRead() {}

        public static void main(final String[] args) throws Exception {
            try {
                AllowedSigner.read(
                        command -> {
                            throw new IllegalStateException("ssh-keygen is not reached");
                        },
                        Path.of(args[0]),
                        Map.of(),
                        Path.of(args[1]));
            } catch (final UncheckedIOException ex) {
                System.exit(UNWRITABLE);
            }
        }
    }
}
