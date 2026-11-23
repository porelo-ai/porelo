package org.fuin.porelo.host;

import static org.fuin.porelo.host.HostJson.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Bounded verifier lines staged privately; only a complete accepted stream can be published. */
final class PushVerdicts extends OutputStream {
    // A verdict's bounded fields serialize to at most 100627 UTF-8 bytes, independent of push size.
    private static final int MAX_LINE = 128 * 1024;
    private final Set<String> refs;
    private final Path entries;
    private final Path ids;
    private final java.io.BufferedWriter rows;
    private final java.io.BufferedWriter names;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();
    private final PushCommitIds commits;
    private long count;
    private boolean refused;
    private @Nullable Map<String, @Nullable Object> summary;
    private boolean closed;

    PushVerdicts(Path directory, Set<String> refs, Duration timeout) throws IOException {
        this.refs = refs;
        entries = directory.resolve("entries");
        ids = directory.resolve("ids");
        rows = Files.newBufferedWriter(entries);
        names = Files.newBufferedWriter(ids);
        commits = new PushCommitIds(directory, System.nanoTime() + timeout.toNanos());
    }

    @Override
    public void write(int value) throws IOException {
        commits.checkDeadline();
        if (value == '\n') {
            readLine();
            line.reset();
        } else {
            if (line.size() >= MAX_LINE) throw new IOException("Verifier line exceeds its limit");
            line.write(value);
        }
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
        for (int index = offset; index < offset + length; index++) write(bytes[index]);
    }

    private void readLine() throws IOException {
        if (summary != null) throw new IOException("Data follows the verifier summary");
        try {
            final String text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(line.toByteArray()))
                            .toString();
            // Bound parser recursion too, before an untrusted worker's JSON reaches it.
            int depth = 0;
            boolean quoted = false;
            boolean escaped = false;
            for (int index = 0; index < text.length(); index++) {
                final char value = text.charAt(index);
                if (escaped) escaped = false;
                else if (quoted && value == '\\') escaped = true;
                else if (value == '"') quoted = !quoted;
                else if (!quoted && (value == '{' || value == '[')) {
                    if (++depth > 8) throw new IOException("Verifier JSON is nested too deeply");
                } else if (!quoted && (value == '}' || value == ']')) depth--;
            }
            final var item = object(parse(text));
            if (item.containsKey("checked_at")) {
                if (!item.keySet().equals(Set.of("checked_at", "accepted", "count", "reason")))
                    throw new IOException("Invalid verifier summary");
                summary = item;
            } else {
                count++;
                if (!"accepted".equals(item.get("verdict"))) {
                    refused = true;
                    return;
                }
                final var entry = PushJudgement.entry(item, refs, "");
                rows.write(HostJson.write(entry));
                rows.newLine();
                names.write(string(entry.get("commit")));
                names.newLine();
            }
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid verifier provenance", ex);
        }
    }

    PushJudgement.Answer finish(HostPrograms.Result result) throws IOException {
        close();
        if (result.exitCode() != 0) return PushJudgement.verified(result, refs);
        final var end = summary;
        if (line.size() != 0
                || end == null
                || refused
                || !Boolean.TRUE.equals(end.get("accepted"))
                || !java.math.BigInteger.valueOf(count).equals(end.get("count"))
                || !"".equals(end.get("reason"))) return PushJudgement.failed();
        try {
            if (!PushJudgement.checkedAt(string(end.get("checked_at"))))
                return PushJudgement.failed();
        } catch (IllegalArgumentException ex) {
            return PushJudgement.failed();
        }
        commits.check(ids);
        return new PushJudgement.Answer(
                200, "porelo: " + count + " new commit(s) judged and recorded\n");
    }

    Path entries() {
        return entries;
    }

    String checkedAt() {
        return string(java.util.Objects.requireNonNull(summary).get("checked_at"));
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            try (rows;
                    names) {
                rows.flush();
                names.flush();
            }
        }
    }
}
