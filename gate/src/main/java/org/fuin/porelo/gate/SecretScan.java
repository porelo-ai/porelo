package org.fuin.porelo.gate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.wire.CredentialShapes;

/**
 * Looks for credentials in the tree a task will check out, in the gate's bare mirror, before the
 * agent can read it.
 *
 * <p>{@code git grep -o} over one commit's tree, with the shapes of {@link CredentialShapes}:
 * binary files are skipped, and the history is not read, so a credential removed in an earlier
 * commit is not found though the task receives that history. Only the matched text is printed, so a
 * long minified line costs no more than its match. Of each place only the path, the line number,
 * the kind and a digest of the match with its file's blob are kept; the match is dropped where it
 * is read.
 */
public final class SecretScan {

    /**
     * One line that looks like a credential.
     *
     * @param path The file, relative to the tree's root.
     * @param line The line, from 1.
     * @param kind What it looks like, as {@link CredentialShapes} names it.
     * @param digest SHA-256 of the file's blob and the match, hex: an acknowledgement holds while
     *     the file stays as it is.
     */
    public record Finding(String path, int line, String kind, String digest) {

        /**
         * Returns where it is, as a person names it.
         *
         * @return {@code <path>:<line>}.
         */
        public String place() {
            return path + ":" + line;
        }

        /**
         * Says the finding without a byte of what was found.
         *
         * @return The line a start prints.
         */
        public String said() {
            return place() + "  looks like a " + kind + "; the agent can read it";
        }

        @Override
        public String toString() {
            return "Finding[" + place() + ", " + kind + "]";
        }
    }

    private SecretScan() {}

    /**
     * Scans one revision's tree in a bare mirror.
     *
     * @param runner Runs git.
     * @param mirror The bare mirror.
     * @param revision The branch or commit the task starts from.
     * @return What was found, in git's order; empty when the revision does not exist yet.
     * @throws GateException If git could not search.
     */
    public static List<Finding> scan(
            final CommandRunner runner, final Path mirror, final String revision) {
        try {
            return scanned(runner, mirror, revision);
        } catch (org.fuin.porelo.core.process.CommandException ex) {
            // A git that timed out or could not start found nothing: that is no clean tree.
            throw new GateException(
                    "The workspace could not be scanned for credentials: " + ex.getMessage());
        }
    }

    private static List<Finding> scanned(
            final CommandRunner runner, final Path mirror, final String revision) {
        final CommandResult commit =
                runner.run(
                        Command.of(
                                "git",
                                "--git-dir",
                                mirror.toString(),
                                "rev-parse",
                                "--verify",
                                "--quiet",
                                revision + "^{commit}"));
        if (!commit.successful()) {
            // An empty mirror: nothing to check out, so nothing to read.
            return List.of();
        }
        final String id = commit.standardOutput().strip();
        final List<String> command =
                new ArrayList<>(
                        List.of(
                                "git",
                                "--git-dir",
                                mirror.toString(),
                                "grep",
                                "--no-color",
                                "-z",
                                "-n",
                                "-o",
                                "-I",
                                "-E"));
        for (final CredentialShapes.Shape shape : CredentialShapes.ALL) {
            command.add("-e");
            command.add(shape.pattern());
        }
        command.add(id);
        command.add("--");
        final CommandResult result = runner.run(Command.of(command));
        // 1 is "nothing matched", the answer of every clean tree.
        if (result.exitCode() == 1) {
            return List.of();
        }
        if (!result.successful()) {
            throw new GateException(
                    "The workspace could not be scanned for credentials: "
                            + result.standardError().strip());
        }
        final String output = result.standardOutput();
        if (output.startsWith("[") && output.lines().findFirst().orElse("").contains("bytes cut")) {
            // The runner keeps the end of a very long answer: what it cut is not a clean tree.
            throw new GateException(
                    "The workspace could not be scanned for credentials: git found more than"
                            + " can be read at once");
        }
        final java.util.Map<String, String> blobs = new java.util.HashMap<>();
        return findings(
                output,
                id + ":",
                path ->
                        blobs.computeIfAbsent(
                                path,
                                each ->
                                        runner.run(
                                                        Command.of(
                                                                "git",
                                                                "--git-dir",
                                                                mirror.toString(),
                                                                "rev-parse",
                                                                "--verify",
                                                                "--quiet",
                                                                id + ":" + each))
                                                .standardOutput()
                                                .strip()));
    }

    /**
     * Reads {@code git grep -z -n -o} output: {@code <rev>:<path> NUL <line> NUL <match>} per
     * match, one finding per place.
     *
     * @param output What git printed, used here and not kept.
     * @param prefix The revision's prefix before each path.
     * @param blob The blob id of a path, part of each digest.
     * @return The findings.
     */
    static List<Finding> findings(
            final String output,
            final String prefix,
            final java.util.function.UnaryOperator<String> blob) {
        final List<Finding> found = new ArrayList<>();
        final java.util.Set<String> places = new java.util.HashSet<>();
        for (final String hit : output.split("\n")) {
            final int first = hit.indexOf('\0');
            final int second = first < 0 ? -1 : hit.indexOf('\0', first + 1);
            if (second < 0) {
                continue;
            }
            String path = hit.substring(0, first);
            if (path.startsWith(prefix)) {
                path = path.substring(prefix.length());
            }
            final int line;
            try {
                line = Integer.parseInt(hit.substring(first + 1, second));
            } catch (NumberFormatException ex) {
                continue;
            }
            if (!places.add(path + ":" + line)) {
                continue;
            }
            final String text = hit.substring(second + 1);
            final String kind = CredentialShapes.kindOf(text).orElse("credential");
            found.add(new Finding(path, line, kind, digest(blob.apply(path) + "\0" + text)));
        }
        return List.copyOf(found);
    }

    /**
     * Returns the SHA-256 of a line, hex.
     *
     * @param text The line.
     * @return The digest.
     */
    public static String digest(final String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is part of every Java", ex);
        }
    }
}
