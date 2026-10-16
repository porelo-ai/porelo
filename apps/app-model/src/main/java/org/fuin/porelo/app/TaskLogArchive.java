package org.fuin.porelo.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A task's logs, and what Porelo says about the task, kept in one archive.
 * <p>
 * <strong>Why it exists.</strong> A task's logs live under {@code $XDG_RUNTIME_DIR} and are gone at a restart - under
 * WSL also at {@code wsl --shutdown}. Keeping what a task did meant zipping that directory by hand, which needed the
 * container name and the runtime path (found on 2026-10-13).
 * <p>
 * <strong>The agent's own words stay out unless asked for.</strong> {@code task.log} is what the agent printed, and it
 * may hold whatever the work was about. An archive made to keep what a task did that matters for security does not
 * need it, and one handed to somebody else must not carry it by accident.
 * <p>
 * <strong>Owner-only from the first byte.</strong> The file is created with {@code rw-------}, not changed to it
 * afterwards, so there is no moment another account could open it. An existing file is refused, never overwritten.
 */
public final class TaskLogArchive {

    /** What the agent itself printed: left out unless asked for. */
    public static final String AGENT_OUTPUT = "task.log";

    /** The checksum list, last in the archive, in the form {@code sha256sum -c} reads. */
    public static final String SUMS = "SHA256SUMS";

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private static final String README = """
            What one Porelo task did, as this machine had it when the archive was written.

            - The *.log files and events.jsonl are what the task's helpers on this machine wrote.
            - status.txt, files.txt and talk.txt are what 'porelo task status', 'porelo task files' and 'porelo talk log'
              said about the task at that moment.
            - task.log, what the agent itself printed, is here only when it was asked for.

            Check it with: sha256sum -c SHA256SUMS (in this directory).
            The checksums show that nothing was changed after the archive was written. They do not show that a log was
            not changed before: the logs are files of the account that ran the task.
            """;

    private TaskLogArchive() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Returns the name an archive gets when nobody names one.
     *
     * @param task The task, as a person names it.
     * @param now When it is written.
     * @return {@code porelo-logs-<task>-<yyyyMMdd'T'HHmmss'Z'>.zip}.
     */
    public static String defaultName(final String task, final Instant now) {
        return "porelo-logs-" + task + "-" + STAMP.format(now) + ".zip";
    }

    /**
     * Writes the archive.
     *
     * @param target Where; must not exist yet.
     * @param task The task, as a person names it: the directory every entry is in.
     * @param logs The task's logs. {@link #AGENT_OUTPUT} is taken only when {@code withAgentOutput} is set.
     * @param said What Porelo says about the task, by file name - {@code status.txt} and the others - in order.
     * @param withAgentOutput Whether {@link #AGENT_OUTPUT} goes in.
     * @return The file names in the archive, without the directory, in order.
     * @throws java.nio.file.FileAlreadyExistsException If {@code target} exists.
     * @throws IOException If a log cannot be read or the archive cannot be written.
     */
    public static List<String> write(final Path target, final String task, final List<Path> logs,
            final Map<String, String> said, final boolean withAgentOutput) throws IOException {
        final Map<String, String> sums = new LinkedHashMap<>();
        Files.createFile(target, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING))) {
            put(zip, task, "README.txt", README.getBytes(StandardCharsets.UTF_8), sums);
            for (final Path log : logs) {
                final String name = log.getFileName().toString();
                // Not through a link, and only a file: what is kept is what the helpers wrote, nothing it points at.
                if ((AGENT_OUTPUT.equals(name) && !withAgentOutput)
                        || !Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                try (InputStream in = Files.newInputStream(log, LinkOption.NOFOLLOW_LINKS)) {
                    put(zip, task, name, in, sums);
                }
            }
            for (final Map.Entry<String, String> text : said.entrySet()) {
                put(zip, task, text.getKey(), text.getValue().getBytes(StandardCharsets.UTF_8), sums);
            }
            final StringBuilder list = new StringBuilder();
            sums.forEach((name, sum) -> list.append(sum).append("  ").append(name).append('\n'));
            zip.putNextEntry(new ZipEntry(task + "/" + SUMS));
            zip.write(list.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException | RuntimeException ex) {
            // Half an archive is not one: it would be kept and trusted.
            Files.deleteIfExists(target);
            throw ex;
        }
        final List<String> names = new ArrayList<>(sums.keySet());
        names.add(SUMS);
        return List.copyOf(names);
    }

    private static void put(final ZipOutputStream zip, final String task, final String name, final byte[] bytes,
            final Map<String, String> sums) throws IOException {
        try (InputStream in = new java.io.ByteArrayInputStream(bytes)) {
            put(zip, task, name, in, sums);
        }
    }

    private static void put(final ZipOutputStream zip, final String task, final String name, final InputStream in,
            final Map<String, String> sums) throws IOException {
        final MessageDigest digest = sha256();
        zip.putNextEntry(new ZipEntry(task + "/" + name));
        // Streamed, and summed as it goes: a log can be large, and what is summed is exactly what was written.
        final OutputStream summed = new java.security.DigestOutputStream(new OutputStream() {
            @Override
            public void write(final int b) throws IOException {
                zip.write(b);
            }

            @Override
            public void write(final byte[] b, final int off, final int len) throws IOException {
                zip.write(b, off, len);
            }
        }, digest);
        in.transferTo(summed);
        summed.flush();
        zip.closeEntry();
        sums.put(name, HexFormat.of().formatHex(digest.digest()));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", ex);
        }
    }
}
