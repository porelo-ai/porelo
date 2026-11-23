package org.fuin.porelo.daemon;

import static org.fuin.porelo.daemon.PoreloDaemon.INTERFACE;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.gate.PatchFiles;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.jspecify.annotations.Nullable;

/**
 * A review too large for one message, sent in pages: Review's first page of each part, and every
 * further page ReviewPage gives from a position.
 *
 * <p>A position is where an entry starts in the file list or the commits, and a UTF-8 byte offset
 * in the log or the diff, where a line starts in the log and a file's patch starts in the diff.
 * Only what does not fit one page alone is entered further: a file's patch at its lines, a line at
 * its code points. Pages are measured as they are sent, escaped as JSON, with the bound the task
 * log's pages use, so each page fits one message.
 */
final class ReviewPages {

    /** The parts a review is paged in, in the order a reply holds them. */
    static final List<String> PARTS = List.of("files", "commits", "log", "diff");

    /** What one page's content may take on the wire, inside its brackets or quotes. */
    static final long ROOM = LogPages.MOST_SENT - 2L;

    /**
     * Room kept for what an estimate cannot see: Review's paged numbers, the fields' names. The
     * result is measured again whole before it is sent.
     */
    private static final int SLACK = 1024;

    private ReviewPages() {}

    /**
     * Returns one page of one part of a review, as ReviewPage answers it.
     *
     * @param part {@code files}, {@code commits}, {@code log} or {@code diff}.
     * @param whole The whole part: a list for files and commits, the text for log and diff.
     * @param from Where the page starts.
     * @return The reply: the page under the part's name, or under {@code text}, and {@code next}
     *     when the part goes on.
     * @throws VarlinkException InvalidParameter naming {@code part} or {@code from}.
     */
    static Map<String, Object> page(final String part, final Object whole, final int from) {
        if (!PARTS.contains(part)) {
            throw invalid("part");
        }
        final Map<String, Object> reply = new LinkedHashMap<>();
        final int next;
        if (whole instanceof List<?> list) {
            if (from < 0 || from >= list.size()) {
                throw invalid("from");
            }
            next = entries(list, from, ROOM, true);
            reply.put(part, List.copyOf(list.subList(from, next)));
            if (next < list.size()) {
                reply.put("next", next);
            }
        } else {
            final Text text = text(part, String.valueOf(whole));
            if (!text.valid(from)) {
                throw invalid("from");
            }
            next = text.fill(from, ROOM, false);
            reply.put("text", text.between(from, next));
            if (next < text.bytes.length) {
                reply.put("next", next);
            }
        }
        return reply;
    }

    /**
     * Returns a reply holding each part's first page and {@code paged}, fitted to a bound.
     *
     * <p>Each part gets an equal share of the room the rest of the reply leaves, and a part that
     * needs less leaves its rest to the others; a first page holds whole entries, lines and files
     * only, so every next position is one ReviewPage takes.
     *
     * @param reply The whole review.
     * @param name The pending push's name, for the refusal.
     * @param base The full base commit the review was read against, "" for the whole history.
     * @param command The command that shows the whole review on this machine.
     * @param limit The most bytes a message may have on the wire.
     * @return The fitted reply.
     * @throws VarlinkException {@code ReviewTooLarge} when the reply does not fit even with every
     *     part empty.
     */
    static Map<String, Object> first(
            final Map<String, Object> reply,
            final String name,
            final String base,
            final List<String> command,
            final int limit) {
        final List<?> files = list(reply.get("files"));
        final List<?> commits = list(reply.get("commits"));
        final Text log = text("log", string(reply.get("log")));
        final Text diff = text("diff", string(reply.get("diff")));
        final Map<String, Object> empty =
                assembled(
                        reply,
                        List.of(),
                        List.of(),
                        "",
                        "",
                        paged(files, commits, log, diff, -1, -1, -1, -1, true, base));
        final int fixed = ReviewCut.bytes(empty);
        if (fixed > limit) {
            throw new VarlinkException(
                    INTERFACE + ".ReviewTooLarge",
                    Map.of("name", name, "bytes", fixed, "limit", limit, "command", command));
        }
        final long[] wholes = {
            LogPages.sent(Json.write(files)) - 2L,
            LogPages.sent(Json.write(commits)) - 2L,
            log.size(0, log.bytes.length),
            diff.size(0, diff.bytes.length)
        };
        long room = Math.max(0, limit - fixed - SLACK);
        while (true) {
            final long[] shares = shares(wholes, room);
            final int filesNext = entries(files, 0, shares[0], false);
            final int commitsNext = entries(commits, 0, shares[1], false);
            final int logNext = log.fill(0, shares[2], true);
            final int diffNext = diff.fill(0, shares[3], true);
            final Map<String, Object> fitted =
                    assembled(
                            reply,
                            files.subList(0, filesNext),
                            commits.subList(0, commitsNext),
                            log.between(0, logNext),
                            diff.between(0, diffNext),
                            paged(
                                    files,
                                    commits,
                                    log,
                                    diff,
                                    filesNext,
                                    commitsNext,
                                    logNext,
                                    diffNext,
                                    false,
                                    base));
            if (ReviewCut.bytes(fitted) <= limit || room == 0) {
                return fitted;
            }
            room = room * 7 / 8;
        }
    }

    /**
     * Returns {@code paged} for a reply whose diff alone was cut at a file boundary: the lists and
     * the log whole, the diff's next page where its first ends.
     *
     * @param reply The whole review.
     * @param base The full base commit the review was read against, "" for the whole history.
     * @param diffNext Where the diff that is sent ends, in UTF-8 bytes.
     * @return The paged field.
     */
    static Map<String, Object> diffPaged(
            final Map<String, Object> reply, final String base, final int diffNext) {
        return paged(
                list(reply.get("files")),
                list(reply.get("commits")),
                text("log", string(reply.get("log"))),
                text("diff", string(reply.get("diff"))),
                -1,
                -1,
                -1,
                diffNext,
                false,
                base);
    }

    /** Divides room among parts: a part needing less than an equal share leaves the rest. */
    private static long[] shares(final long[] wholes, final long room) {
        final Integer[] order = {0, 1, 2, 3};
        Arrays.sort(order, (a, b) -> Long.compare(wholes[a], wholes[b]));
        final long[] shares = new long[wholes.length];
        long left = room;
        for (int i = 0; i < order.length; i++) {
            final long share = Math.min(wholes[order[i]], left / (order.length - i));
            shares[order[i]] = share;
            left -= share;
        }
        return shares;
    }

    private static Map<String, Object> assembled(
            final Map<String, Object> reply,
            final List<?> files,
            final List<?> commits,
            final String log,
            final String diff,
            final Map<String, Object> paged) {
        final Map<String, Object> fitted = new LinkedHashMap<>(reply);
        fitted.put("diff", diff);
        fitted.put("log", log);
        if (reply.containsKey("commits")) {
            fitted.put("commits", List.copyOf(commits));
        }
        fitted.put("files", List.copyOf(files));
        fitted.remove("cut");
        fitted.put("paged", paged);
        return fitted;
    }

    /**
     * Returns paged with each part's whole size, its next position where it goes on and the base
     * the pages are read against; with {@code widest}, every next as large as it can be, to measure
     * the most it can take.
     */
    private static Map<String, Object> paged(
            final List<?> files,
            final List<?> commits,
            final Text log,
            final Text diff,
            final int filesNext,
            final int commitsNext,
            final int logNext,
            final int diffNext,
            final boolean widest,
            final String base) {
        final Map<String, Object> paged = new LinkedHashMap<>();
        paged.put("files", files.size());
        paged.put("commits", commits.size());
        paged.put("logBytes", log.bytes.length);
        paged.put("diffBytes", diff.bytes.length);
        next(paged, "filesNext", widest ? Integer.MAX_VALUE : filesNext, files.size());
        next(paged, "commitsNext", widest ? Integer.MAX_VALUE : commitsNext, commits.size());
        next(paged, "logNext", widest ? Integer.MAX_VALUE : logNext, log.bytes.length);
        next(paged, "diffNext", widest ? Integer.MAX_VALUE : diffNext, diff.bytes.length);
        paged.put("base", base);
        return paged;
    }

    private static void next(
            final Map<String, Object> paged, final String key, final int next, final int end) {
        if (next >= 0 && next < end || next == Integer.MAX_VALUE) {
            paged.put(key, next);
        }
    }

    /**
     * Returns where a page of entries from a position ends: as many as fit the room, and with
     * {@code atLeastOne} the first even when it alone does not.
     */
    private static int entries(
            final List<?> all, final int from, final long room, final boolean atLeastOne) {
        long used = 0;
        int at = from;
        while (at < all.size()) {
            final long size = LogPages.sent(Json.write(all.get(at))) - 2L + (at > from ? 1 : 0);
            if (used + size > room && !(atLeastOne && at == from)) {
                break;
            }
            used += size;
            at++;
        }
        return at;
    }

    private static List<?> list(final @Nullable Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private static String string(final @Nullable Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Text text(final String part, final String text) {
        final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (!"diff".equals(part)) {
            return new Text(bytes, null);
        }
        final PatchFiles.Split split = PatchFiles.split(text);
        final int[] starts = new int[split.parts().size()];
        int at = split.preamble().getBytes(StandardCharsets.UTF_8).length;
        for (int i = 0; i < starts.length; i++) {
            starts[i] = at;
            at += split.parts().get(i).text().getBytes(StandardCharsets.UTF_8).length;
        }
        return new Text(bytes, starts);
    }

    private static VarlinkException invalid(final String parameter) {
        return new VarlinkException(
                "org.varlink.service.InvalidParameter", Map.of("parameter", parameter));
    }

    /**
     * A log or a diff as UTF-8 bytes, with the units a page is made of: for a diff its files, then
     * their lines, then code points; for a log its lines, then code points.
     */
    private static final class Text {

        private static final int FILE = 0;

        private static final int LINE = 1;

        private static final int POINT = 2;

        private final byte[] bytes;

        /** Where each file's patch starts, ascending; {@code null} for a log. */
        private final int @Nullable [] files;

        private Text(final byte[] bytes, final int @Nullable [] files) {
            this.bytes = bytes;
            this.files = files;
        }

        /** The units, coarsest first. */
        private int[] kinds() {
            return files == null ? new int[] {LINE, POINT} : new int[] {FILE, LINE, POINT};
        }

        private boolean boundary(final int kind, final int at) {
            if (at == 0) {
                return true;
            }
            return switch (kind) {
                case FILE -> Arrays.binarySearch(starts(), at) >= 0;
                case LINE -> bytes[at - 1] == '\n';
                default -> (bytes[at] & 0xC0) != 0x80;
            };
        }

        /** The first boundary of a unit after a position, or the end. */
        private int after(final int kind, final int at) {
            switch (kind) {
                case FILE -> {
                    final int[] starts = starts();
                    final int found = Arrays.binarySearch(starts, at);
                    final int index = found >= 0 ? found + 1 : -found - 1;
                    return index < starts.length ? starts[index] : bytes.length;
                }
                case LINE -> {
                    for (int i = at; i < bytes.length; i++) {
                        if (bytes[i] == '\n') {
                            return i + 1;
                        }
                    }
                    return bytes.length;
                }
                default -> {
                    int i = at + 1;
                    while (i < bytes.length && (bytes[i] & 0xC0) == 0x80) {
                        i++;
                    }
                    return i;
                }
            }
        }

        /** The last boundary of a unit at or before a position. */
        private int before(final int kind, final int at) {
            if (kind == FILE) {
                final int[] starts = starts();
                final int found = Arrays.binarySearch(starts, at);
                final int index = found >= 0 ? found : -found - 2;
                return index >= 0 ? starts[index] : 0;
            }
            int i = at;
            while (i > 0 && !boundary(kind, i)) {
                i--;
            }
            return i;
        }

        private int[] starts() {
            return java.util.Objects.requireNonNull(files, "only a diff has files");
        }

        /** What the bytes between two boundaries take on the wire, without quotes. */
        private long size(final int from, final int to) {
            return LogPages.sent(between(from, to)) - 2L;
        }

        private String between(final int from, final int to) {
            return new String(bytes, from, to - from, StandardCharsets.UTF_8);
        }

        /**
         * Whether a page may start at a position: the start of a coarsest unit, or of a finer one
         * inside a unit that does not fit a page alone.
         */
        private boolean valid(final int from) {
            if (from < 0 || from >= bytes.length) {
                return false;
            }
            for (final int kind : kinds()) {
                if (boundary(kind, from)) {
                    return true;
                }
                if (kind == POINT) {
                    return false;
                }
                final int start = before(kind, from);
                if (size(start, after(kind, start)) <= ROOM) {
                    return false;
                }
            }
            return false;
        }

        /**
         * Returns where a page from a valid position ends: whole units as long as they fit, and
         * when the first does not fit alone, its finer units, unless {@code coarse}.
         */
        private int fill(final int from, final long room, final boolean coarse) {
            final int[] kinds = kinds();
            int level = 0;
            while (level < kinds.length - 1 && !boundary(kinds[level], from)) {
                level++;
            }
            final int limit = level == 0 ? bytes.length : after(kinds[level - 1], from);
            return fill(kinds, level, from, limit, new long[] {room}, coarse);
        }

        private int fill(
                final int[] kinds,
                final int level,
                final int from,
                final int limit,
                final long[] room,
                final boolean coarse) {
            int at = from;
            while (at < limit) {
                final int end = Math.min(after(kinds[level], at), limit);
                final long size = size(at, end);
                if (size <= room[0]) {
                    room[0] -= size;
                    at = end;
                    continue;
                }
                if (at == from && !coarse && level < kinds.length - 1) {
                    return fill(kinds, level + 1, at, end, room, false);
                }
                break;
            }
            return at;
        }
    }
}
