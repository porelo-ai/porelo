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
