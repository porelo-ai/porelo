package org.fuin.porelo.gate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link SecretScan}, against real bare repositories: what has to hold is what {@code git
 * grep} finds in a real mirror, and a fake would only repeat its arguments.
 */
class SecretScanTest {

    /** Built here, so this file is not itself a finding of any scanner. */
    private static final String KEY_HEADER = "-----BEGIN " + "OPENSSH PRIVATE KEY-----";

    private static final String KEY_BODY = "b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQ";

    private static final String TOKEN = "ghp_" + "Ab3".repeat(12);

    private final CommandRunner runner = new ProcessCommandRunner(Duration.ofSeconds(60));

    private String git(Path directory, String... arguments) {
        final List<String> all = new ArrayList<>(List.of("git"));
        all.addAll(List.of(arguments));
        return runner.runOrFail(
                        new Command(
                                all,
                                directory,
                                Map.of(
                                        "GIT_AUTHOR_NAME", "Test",
                                        "GIT_AUTHOR_EMAIL", "test@example.com",
                                        "GIT_COMMITTER_NAME", "Test",
                                        "GIT_COMMITTER_EMAIL", "test@example.com",
                                        "GIT_TERMINAL_PROMPT", "0"),
                                null))
                .standardOutput();
    }

    /**
     * Makes a bare mirror whose one commit on {@code main} holds the given files.
     *
     * @param root Where to work.
     * @param files Path to content.
     * @return The mirror.
     */
    private Path mirror(Path root, Map<String, String> files) throws java.io.IOException {
        final Path work = root.resolve("work");
        final Path mirror = root.resolve("mirror.git");
        Files.createDirectories(root);
        git(root, "init", "--quiet", "--initial-branch=main", work.toString());
        for (final var file : files.entrySet()) {
            final Path path = work.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue(), StandardCharsets.UTF_8);
        }
        git(work, "add", ".");
        git(work, "commit", "--quiet", "-m", "one");
        git(root, "clone", "--quiet", "--bare", work.toString(), mirror.toString());
        return mirror;
    }

    @Test
    void findsAPrivateKeyByItsHeaderAndKeepsNoByteOfIt(@TempDir Path root) throws Exception {
        final Path mirror =
                mirror(
                        root,
                        Map.of(
                                "deploy/key",
                                KEY_HEADER + "\n" + KEY_BODY + "\n",
                                "README.md",
                                "nothing here\n"));

        final List<SecretScan.Finding> found = SecretScan.scan(runner, mirror, "main");

        assertThat(found).hasSize(1);
        final SecretScan.Finding finding = found.getFirst();
        assertThat(finding.path()).isEqualTo("deploy/key");
        assertThat(finding.line()).isEqualTo(1);
        assertThat(finding.kind()).isEqualTo("private key");
        assertThat(finding.toString())
                .as("nothing of the key is kept")
                .doesNotContain("PRIVATE KEY")
                .doesNotContain(KEY_BODY);
        assertThat(finding.said()).doesNotContain("BEGIN").contains("deploy/key:1");
    }

    @Test
    void namesEachShapedTokenByItsKindAndLine(@TempDir Path root) throws Exception {
        final Path mirror =
                mirror(
                        root,
                        Map.of(
                                "src/config.properties",
                                "name=shop\nforge.token=" + TOKEN + "\n",
                                "docs/notes.md",
                                "a ghp_ that is too short is no token\n"));

        final List<SecretScan.Finding> found = SecretScan.scan(runner, mirror, "main");

        assertThat(found)
                .singleElement()
                .satisfies(
                        finding -> {
                            assertThat(finding.path()).isEqualTo("src/config.properties");
                            assertThat(finding.line()).isEqualTo(2);
                            assertThat(finding.kind()).isEqualTo("forge token");
                            assertThat(finding.toString()).doesNotContain(TOKEN);
                        });
    }

    @Test
    void theSameLineHasTheSameDigestAndAnotherLineAnother(@TempDir Path root) throws Exception {
        final Path one = mirror(root.resolve("one"), Map.of("a.txt", "token=" + TOKEN + "\n"));
        final Path same = mirror(root.resolve("same"), Map.of("a.txt", "token=" + TOKEN + "\n"));
        final Path changed =
                mirror(root.resolve("changed"), Map.of("a.txt", "token=" + TOKEN + "x\n"));

        final String digest = SecretScan.scan(runner, one, "main").getFirst().digest();

        assertThat(digest).hasSize(64).doesNotContain(TOKEN);
        assertThat(SecretScan.scan(runner, same, "main").getFirst().digest()).isEqualTo(digest);
        assertThat(SecretScan.scan(runner, changed, "main").getFirst().digest())
                .isNotEqualTo(digest);
    }

    @Test
    void aCleanTreeAndAnEmptyMirrorHaveNoFinding(@TempDir Path root) throws Exception {
        final Path clean = mirror(root.resolve("clean"), Map.of("a.txt", "hello\n"));
        final Path empty = root.resolve("empty.git");
        git(root, "init", "--quiet", "--bare", empty.toString());

        assertThat(SecretScan.scan(runner, clean, "main")).isEmpty();
        assertThat(SecretScan.scan(runner, empty, "main")).isEmpty();
    }

    @Test
    void binaryFilesAreNotRead(@TempDir Path root) throws Exception {
        final Path mirror = mirror(root, Map.of("blob.bin", "\u0000\u0001" + KEY_HEADER + "\n"));

        assertThat(SecretScan.scan(runner, mirror, "main")).isEmpty();
    }

    @Test
    void severalMatchesOnOneLineAreOneFinding(@TempDir Path root) throws Exception {
        final Path mirror =
                mirror(
                        root,
                        Map.of("bundle.js", "var a=\"" + TOKEN + "\",b=\"" + TOKEN + "x\";\n"));

        assertThat(SecretScan.scan(runner, mirror, "main"))
                .singleElement()
                .extracting(SecretScan.Finding::place)
                .isEqualTo("bundle.js:1");
    }

    @Test
    void aGitThatTimesOutIsNoCleanTree(@TempDir Path root) {
        final org.fuin.porelo.testing.FakeCommandRunner timing =
                new org.fuin.porelo.testing.FakeCommandRunner()
                        .answering("rev-parse", "0123abcd\n")
                        .timingOut("grep --no-color");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> SecretScan.scan(timing, root.resolve("m.git"), "main"))
                .isInstanceOf(GateException.class)
                .hasMessageContaining("could not be scanned");
    }

    @Test
    void anAnswerTheRunnerCutIsNoCleanTree(@TempDir Path root) {
        final org.fuin.porelo.testing.FakeCommandRunner cut =
                new org.fuin.porelo.testing.FakeCommandRunner()
                        .answering("rev-parse", "0123abcd\n")
                        .answering(
                                "grep --no-color",
                                "[12345 bytes cut before this]\n0123abcd:a\u00001\u0000x\n");

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> SecretScan.scan(cut, root.resolve("m.git"), "main"))
                .isInstanceOf(GateException.class)
                .hasMessageContaining("more than can be read at once");
    }

    @Test
    void aWordThatHoldsAShapeInsideItIsNoToken(@TempDir Path root) throws Exception {
        // A scenario's name, a disk's: 'sk-' inside a longer word is no key.
        final Path mirror =
                mirror(
                        root,
                        Map.of(
                                "notes.md",
                                "the task-unattended-feature-scenario-name and a disk-partition-label-xyz\n"));

        assertThat(SecretScan.scan(runner, mirror, "main")).isEmpty();
    }
}
