package org.fuin.porelo.gate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Judges the commits a task's push brings by its certificate, at the time they arrive.
 *
 * <p>Every commit the push makes new to the mirror - not on one of its branches or tags, and not
 * below what the ref held before - must carry a signature that {@code ssh-keygen -Y verify} accepts
 * for the task's principal against the signers file, whose lines are the project's {@code
 * cert-authority} certifiers. It is verified at the time of arrival, never at the date the commit
 * carries, which its signer sets: a commit arriving after its certificate expired is refused
 * however it is dated.
 */
public final class SignedArrivals {

    /** Git's name for "no commit", as the old side of a ref that did not exist. */
    public static final String ZERO = "0000000000000000000000000000000000000000";

    private static final DateTimeFormatter VERIFY_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    /** The most commits one push is judged for; more is refused rather than walked. */
    static final int MAX_COMMITS = 10_000;

    private final Path mirror;

    private final String principal;

    private final Path allowedSigners;

    private final GitHttpServer.GitProcess git;

    private final Clock clock;

    /**
     * Constructor.
     *
     * @param mirror The bare mirror the push went to.
     * @param principal The task's principal, {@code task:<project>:<task>}.
     * @param allowedSigners The signers file, in {@code ssh-keygen}'s allowed signers format.
     * @param git Runs git.
     * @param clock Says when a push arrives.
     */
    public SignedArrivals(
            Path mirror,
            String principal,
            Path allowedSigners,
            GitHttpServer.GitProcess git,
            Clock clock) {
        this.mirror = mirror;
        this.principal = principal;
        this.allowedSigners = allowedSigners;
        this.git = git;
        this.clock = clock;
    }

    /**
     * Judges what a push brought.
     *
     * @param old What the ref pointed at before, or {@link #ZERO}.
     * @param commit What it points at now.
     * @return {@code null} when every new commit is accepted, else why the first is not.
     * @throws IOException If git cannot be asked.
     */
    public @Nullable String judge(String old, String commit) throws IOException {
        final String arrived = VERIFY_TIME.format(clock.instant());
        final List<String> walk =
                new ArrayList<>(
                        List.of(
                                "--git-dir",
                                mirror.toString(),
                                "rev-list",
                                "--max-count=" + (MAX_COMMITS + 1),
                                commit,
                                "--not",
                                "--branches",
                                "--tags"));
        if (!ZERO.equals(old)) {
            walk.add(old);
        }
        final List<String> commits =
                new String(git.run(walk, new byte[0]), StandardCharsets.US_ASCII)
                        .lines()
                        .filter(line -> !line.isBlank())
                        .toList();
        if (commits.size() > MAX_COMMITS) {
            return "the push brings more than " + MAX_COMMITS + " commits; push fewer at once";
        }
        for (final String each : commits) {
            final String refused = judgeOne(each, arrived);
            if (refused != null) {
                return refused;
            }
        }
        return null;
    }

    private @Nullable String judgeOne(String commit, String arrived) throws IOException {
        final byte[] raw =
                git.run(
                        List.of("--git-dir", mirror.toString(), "cat-file", "commit", commit),
                        new byte[0]);
        final Signed signed = Signed.of(raw);
        if (signed == null) {
            return "commit " + commit + " is not signed; a task's commits are signed by its key";
        }
        final Path dir = Files.createTempDirectory(spool(), "arrival-");
        try {
            final Path signature = dir.resolve("commit.sig");
            final Path payload = dir.resolve("commit");
            Files.write(signature, signed.signature());
            Files.write(payload, signed.payload());
            final Process process =
                    new ProcessBuilder(
                                    "ssh-keygen",
                                    "-Y",
                                    "verify",
                                    "-n",
                                    "git",
                                    "-I",
                                    principal,
                                    "-f",
                                    allowedSigners.toString(),
                                    "-s",
                                    signature.toString(),
                                    "-Overify-time=" + arrived + "Z")
                            .redirectInput(payload.toFile())
                            .redirectErrorStream(true)
                            .start();
            final String said =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            try {
                if (!process.waitFor(60, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    return "commit " + commit + " could not be judged in time";
                }
            } catch (InterruptedException ex) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while judging " + commit, ex);
            }
            if (process.exitValue() == 0) {
                return null;
            }
            return "commit "
                    + commit
                    + " is not signed for "
                    + principal
                    + " by a certified key at its arrival "
                    + arrived
                    + "Z: "
                    + reason(said);
        } finally {
            try (var files = Files.list(dir)) {
                for (final Path file : files.toList()) {
                    Files.deleteIfExists(file);
                }
            }
            Files.deleteIfExists(dir);
        }
    }

    /** ssh-keygen's own words, the line that names the cause, printable. */
    private static String reason(String said) {
        final String line =
                said.lines()
                        .filter(
                                each ->
                                        each.contains("Certificate invalid")
                                                || each.contains("not authorized"))
                        .findFirst()
                        .orElse(
                                said.lines()
                                        .filter(each -> !each.isBlank())
                                        .findFirst()
                                        .orElse(""));
        final int colon = line.indexOf("Certificate invalid: ");
        final String cause =
                colon >= 0 ? line.substring(colon + "Certificate invalid: ".length()) : line;
        return cause.replaceAll("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]", "?").strip();
    }

    private Path spool() {
        final Path parent = mirror.toAbsolutePath().getParent();
        return parent == null ? mirror : parent;
    }

    /**
     * A commit split into what was signed and its signature, as git splits it.
     *
     * @param payload The commit without its {@code gpgsig} header.
     * @param signature The armored signature.
     */
    record Signed(byte[] payload, byte[] signature) {

        /**
         * Splits a raw commit.
         *
         * @param raw The commit, as {@code git cat-file commit} answers it.
         * @return The parts, or {@code null} when it carries no signature of its own.
         */
        static @Nullable Signed of(byte[] raw) {
            final String text = new String(raw, StandardCharsets.UTF_8);
            final int end = text.indexOf("\n\n");
            final String headers = end < 0 ? text : text.substring(0, end + 1);
            final String rest = end < 0 ? "" : text.substring(end + 1);
            final StringBuilder payload = new StringBuilder();
            final StringBuilder signature = new StringBuilder();
            boolean inSignature = false;
            boolean found = false;
            for (final String line : headers.split("\n", -1)) {
                if (line.isEmpty()) {
                    continue;
                }
                if (line.startsWith(" ") && inSignature) {
                    signature.append(line.substring(1)).append('\n');
                    continue;
                }
                inSignature = false;
                // Only the commit's own signature header: a mergetag's signature is the tag's.
                if (line.startsWith("gpgsig ") && !found) {
                    found = true;
                    inSignature = true;
                    signature.append(line.substring("gpgsig ".length())).append('\n');
                    continue;
                }
                payload.append(line).append('\n');
            }
            if (!found) {
                return null;
            }
            payload.append(rest);
            return new Signed(
                    payload.toString().getBytes(StandardCharsets.UTF_8),
                    signature.toString().getBytes(StandardCharsets.UTF_8));
        }
    }
}
