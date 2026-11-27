package org.fuin.porelo.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.gate.review.Body;
import org.fuin.porelo.gate.review.Decision;
import org.fuin.porelo.gate.review.Direction;
import org.fuin.porelo.gate.review.ForgeReply;
import org.fuin.porelo.gate.review.Held;
import org.fuin.porelo.gate.review.LineAnchor;
import org.fuin.porelo.gate.review.ReviewComment;
import org.fuin.porelo.gate.review.ReviewError;
import org.fuin.porelo.gate.review.ReviewItem;
import org.fuin.porelo.gate.review.ReviewRefusal;
import org.fuin.porelo.gate.review.ReviewRefused;
import org.fuin.porelo.gate.review.ReviewReply;
import org.fuin.porelo.gate.review.Suggestion;
import org.fuin.porelo.gate.review.ThreadResolve;
import org.jspecify.annotations.Nullable;

/**
 * The held items as the contract's {@code HeldItem} shows them, and their pages: one shape for the
 * daemon's {@code HeldItems} and {@code porelo gate held --json}, so the two cannot differ.
 */
public final class HeldItemRows {

    /** The most items one page holds; a page of the longest items stays well under a message. */
    static final int PAGE = 25;

    private static final Pattern CURSOR = Pattern.compile("([1-9][0-9]{0,8})\\.([0-9a-f]{16})");

    private static final HexFormat HEX = HexFormat.of();

    private HeldItemRows() {}

    /**
     * One page of held items.
     *
     * @param items The page's items, each as {@link #row} gives it.
     * @param next The next page's cursor, or null on the last page.
     */
    public record Page(List<Map<String, Object>> items, @Nullable String next) {

        /** Keeps the items as given, unchangeable. */
        public Page {
            items = List.copyOf(items);
        }
    }

    /**
     * Returns one page of the undecided items, oldest first.
     *
     * @param undecided Every undecided item asked for, oldest first.
     * @param task The task asked for, or null for every task; a cursor is bound to it.
     * @param cursor A previous page's cursor, or null for the first page.
     * @return The page.
     * @throws ReviewRefused {@code CursorRefused} for a cursor no page of this listing gave, or one
     *     whose earlier items changed since, so no item is repeated or lost.
     */
    public static Page page(
            final List<Held> undecided, final @Nullable String task, final @Nullable String cursor)
            throws ReviewRefused {
        final List<String> ids = undecided.stream().map(Held::id).toList();
        final int start = cursor == null ? 0 : start(task, ids, cursor);
        final int end = Math.min(start + PAGE, undecided.size());
        final List<Map<String, Object>> items = new ArrayList<>();
        for (final Held held : undecided.subList(start, end)) {
            items.add(row(held));
        }
        final String next =
                end < undecided.size() ? end + "." + digest(task, ids.subList(0, end)) : null;
        return new Page(items, next);
    }

    /**
     * Returns an item as the contract's {@code HeldItem}: its text and suggestion exact and
     * unescaped, absent fields left out; a resolve as its own operation, its thread and no text.
     *
     * @param held The item.
     * @return Its fields, in the contract's order.
     */
    public static Map<String, Object> row(final Held held) {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", held.id());
        row.put("task", held.task());
        row.put("place", held.place());
        row.put("direction", held.direction().name().toLowerCase(Locale.ROOT));
        row.put("destination", destination(held));
        switch (held.item()) {
            case ReviewComment comment -> {
                row.put("operation", "comment");
                switch (comment.anchor()) {
                    case LineAnchor line -> {
                        row.put("path", line.path());
                        row.put("side", line.side().word());
                        row.put("startLine", line.startLine());
                        row.put("endLine", line.endLine());
                    }
                }
                final Suggestion suggestion = comment.suggestion();
                if (suggestion != null) {
                    row.put("suggestion", suggestion.replacement());
                }
            }
            case ReviewReply reply -> {
                row.put("operation", "reply");
                row.put("thread", reply.thread());
            }
            case ForgeReply reply -> {
                row.put("operation", "reply");
                row.put("thread", reply.thread());
            }
            case ThreadResolve resolve -> {
                row.put("operation", "resolve");
                row.put("thread", resolve.thread());
            }
        }
        row.put("commit", held.commit());
        final Body text = ReviewItem.text(held.item());
        if (text != null) {
            row.put("text", text.text());
        }
        row.put("digest", held.digest());
        final String from = held.from();
        if (from != null) {
            row.put("from", from);
        }
        if (held.unsigned()) {
            row.put("mark", TrustMark.UNSIGNED);
        }
        row.put("at", held.at().toString());
        row.put("run", held.run());
        return row;
    }

    /**
     * Returns held items as text: each item's id, task, direction, operation, run and time, and the
     * word {@code unsigned} when no certified key signed it, then its destination, commit, digest,
     * anchor or thread and writer, and every line of its text and suggestion, escaped and indented
     * below.
     *
     * <p>What a producer wrote goes through the terminal's escaping line by line and is indented
     * deeper than every header, so no text can draw an item of its own.
     *
     * @param held The items, oldest first.
     * @return The text; a line saying so when there are none.
     */
    static String shown(final List<Held> held) {
        if (held.isEmpty()) {
            return "nothing held\n";
        }
        final StringBuilder text = new StringBuilder();
        for (final Held each : held) {
            final Map<String, Object> row = row(each);
            text.append(TerminalText.line(each.id()))
                    .append("  ")
                    .append(TerminalText.line(each.task()))
                    .append("  ")
                    .append(row.get("direction"))
                    .append(' ')
                    .append(row.get("operation"))
                    .append("  run ")
                    .append(each.run())
                    .append("  ")
                    .append(row.get("at"))
                    // On the item's own line, so no reader of the list can miss it.
                    .append(each.unsigned() ? "  " + TrustMark.UNSIGNED : "")
                    .append('\n');
            text.append("  to ").append(TerminalText.line(destination(each))).append('\n');
            text.append("  commit ").append(each.commit()).append('\n');
            text.append("  digest ").append(each.digest()).append('\n');
            if (row.get("path") instanceof String path) {
                text.append("  ")
                        .append(TerminalText.line(path))
                        .append("  ")
                        .append(row.get("side"))
                        .append(' ')
                        .append(row.get("startLine"))
                        .append('-')
                        .append(row.get("endLine"))
                        .append('\n');
            }
            if (row.get("thread") instanceof String thread) {
                text.append("  thread ").append(TerminalText.line(thread)).append('\n');
            }
            final String from = each.from();
            if (from != null) {
                text.append("  from forge account ").append(TerminalText.line(from)).append('\n');
            }
            final Body said = ReviewItem.text(each.item());
            if (said != null) {
                indented(text, said.text());
            }
            if (row.get("suggestion") instanceof String suggestion) {
                text.append("  suggests\n");
                indented(text, suggestion);
            }
        }
        return text.toString();
    }

    /** Appends each line of a producer's text, escaped and indented below its header. */
    private static void indented(final StringBuilder text, final String written) {
        written.lines()
                .forEach(line -> text.append("    ").append(TerminalText.line(line)).append('\n'));
    }

    /**
     * Returns where an item goes: an outgoing one to its place, an incoming one to its task's run.
     *
     * @param held The item.
     * @return The place's name, or the task as "task NAME".
     */
    static String destination(final Held held) {
        return held.direction() == Direction.OUTGOING ? held.place() : "task " + held.task();
    }

    /**
     * Returns a decision by its contract word.
     *
     * @param word {@code release}, {@code refuse}, {@code pass} or {@code dismiss}.
     * @return The decision.
     * @throws IllegalArgumentException For any other word.
     */
    public static Decision decision(final String word) {
        return switch (word) {
            case "release" -> Decision.RELEASE;
            case "refuse" -> Decision.REFUSE;
            case "pass" -> Decision.PASS;
            case "dismiss" -> Decision.DISMISS;
            default ->
                    throw new IllegalArgumentException(
                            "a decision is release, refuse, pass or dismiss");
        };
    }

    private static int start(
            final @Nullable String task, final List<String> ids, final String cursor)
            throws ReviewRefused {
        final Matcher matcher = CURSOR.matcher(cursor);
        if (!matcher.matches()) {
            throw cursorRefused();
        }
        final int offset = Integer.parseInt(matcher.group(1));
        if (offset >= ids.size()
                || !matcher.group(2).equals(digest(task, ids.subList(0, offset)))) {
            throw cursorRefused();
        }
        return offset;
    }

    private static ReviewRefused cursorRefused() {
        return new ReviewRefused(new ReviewRefusal(ReviewError.CURSOR_REFUSED, null));
    }

    /** Binds a cursor to the listing it came from and to every item before it. */
    private static String digest(final @Nullable String task, final List<String> before) {
        final List<String> parts = new ArrayList<>();
        parts.add(task == null ? "" : task);
        parts.addAll(before);
        try {
            return HEX.formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            String.join("\0", parts)
                                                    .getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 16);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }
}
