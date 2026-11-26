package org.fuin.porelo.gate.review;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * Writes a push's threads as SARIF 2.1.0 in Porelo's review profile 1, and a pull request's
 * conversation and a released item in profile 2: the one place a review document is written.
 *
 * <p>Every comment and every reply is a result of its own, in the threads' order and each thread's
 * items in theirs. A reply stands at its thread's place and names the thread it answers; it never
 * shares a {@code correlationGuid}. A page ends before its hundredth result or before it would pass
 * one mebibyte, and always carries at least one result; its {@code porelo/completeness} says
 * whether it is the last page, never whether the review is complete.
 *
 * <p>A profile 2 page holds a pull request's conversation with each item's forge account, the
 * forge's own thread ids, the adapter's guid and the time the forge says the item was written; it
 * ends before its hundredth result or before it would leave too little room for the message it
 * travels in. A submission is one released item without a producer, which the channel stamps.
 *
 * <p>What the check should have refused is refused here once more, loudly: a text with a control,
 * format or separator character or a lone surrogate, or one over its limit, is never written.
 */
public final class ReviewSarif implements ReviewWriter, ProfileWriting {

    /** The most results a page carries. */
    static final int MAX_RESULTS = 100;

    /** The most bytes of UTF-8 a page takes. */
    static final int MAX_BYTES = 1 << 20;

    /**
     * The most bytes of UTF-8 a profile 2 page takes: it travels as one task message of at most one
     * mebibyte, and 64 KiB stay free for the note around it.
     */
    static final int MAX_PAGE_BYTES = (1 << 20) - (64 << 10);

    /** The most bytes of UTF-8 one result takes, as every profile reader holds it. */
    static final int MAX_RESULT_BYTES = 32 << 10;

    private static final Pattern UUID4 =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    private static final int MAX_TEXT_BYTES = 8192;

    // Instant writes a whole second in this form within these years, as the profile requires.
    private static final Pattern CREATED_AT =
            Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z");

    private static final Pattern CURSOR = Pattern.compile("(0|[1-9][0-9]{0,8})\\.([0-9a-f]{16})");

    private static final HexFormat HEX = HexFormat.of();

    private static final HexFormat UPPER_HEX = HexFormat.of().withUpperCase();

    @Override
    public Page page(
            final Push push, final List<ReviewThread> threads, final @Nullable String cursor)
            throws ReviewRefused {
        final List<String> results = new ArrayList<>();
        final List<String> guids = new ArrayList<>();
        for (final ReviewThread thread : threads) {
            final ReviewComment root = (ReviewComment) thread.items().get(0).item();
            for (final Kept kept : thread.items()) {
                results.add(Json.write(result(thread, root, kept)));
                guids.add(kept.guid());
            }
        }
        final int start = cursor == null ? 0 : start(push, guids, cursor);
        long bytes = utf8(document(push, List.of(), "complete"));
        int end = start;
        while (end < results.size() && end - start < MAX_RESULTS) {
            final long more = utf8(results.get(end)) + (end > start ? 1 : 0);
            if (end > start && bytes + more > MAX_BYTES) {
                break;
            }
            bytes += more;
            end++;
        }
        final boolean last = end == results.size();
        final String sarif =
                document(push, results.subList(start, end), last ? "complete" : "partial");
        return new Page(sarif, last ? null : end + "." + digest(push, guids.subList(0, end)));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Each thread's items follow its opening comment, threads in the order they opened; a reply
     * stands at its thread's place. Every page but the last says {@code partial}, the last the
     * conversation's completeness. Each result says {@code resolved} when its thread is among the
     * conversation's resolved threads, {@code open} otherwise. Profile 2 has no field for an
     * incomplete conversation's reason, so it is not written here; the delivery's note text carries
     * it.
     *
     * @throws IllegalArgumentException When an item is not a forge account's at this forge or
     *     carries no time, a reply's thread opens with no comment of the conversation, or one item
     *     passes a result's bound.
     */
    @Override
    public List<byte[]> pages(final PullRequestFacts facts, final Conversation conversation) {
        final Map<String, LineAnchor> places = new LinkedHashMap<>();
        final Map<String, List<Kept>> threads = new LinkedHashMap<>();
        for (final Kept kept : conversation.items()) {
            if (kept.item() instanceof ReviewComment comment) {
                if (places.putIfAbsent(kept.thread(), line(comment.anchor())) != null) {
                    throw new IllegalArgumentException("a thread opens twice");
                }
            }
            threads.computeIfAbsent(kept.thread(), _ -> new ArrayList<>()).add(kept);
        }
        final List<String> results = new ArrayList<>();
        for (final List<Kept> thread : threads.values()) {
            for (final Kept kept : thread) {
                final LineAnchor place = places.get(kept.thread());
                if (place == null) {
                    throw new IllegalArgumentException(
                            "a reply's thread opens with no comment of the conversation");
                }
                results.add(
                        bounded(
                                Json.write(
                                        pageResult(
                                                facts,
                                                kept,
                                                place,
                                                conversation.resolved().contains(kept.thread())))));
            }
        }
        final List<byte[]> pages = new ArrayList<>();
        final long envelope = utf8(pullRequestDocument(facts, List.of(), "partial"));
        int start = 0;
        do {
            long bytes = envelope;
            int end = start;
            while (end < results.size() && end - start < MAX_RESULTS) {
                final long more = utf8(results.get(end)) + (end > start ? 1 : 0);
                if (bytes + more > MAX_PAGE_BYTES) {
                    break;
                }
                bytes += more;
                end++;
            }
            final boolean last = end == results.size();
            pages.add(
                    pullRequestDocument(
                                    facts,
                                    results.subList(start, end),
                                    last ? conversation.completeness().word() : "partial")
                            .getBytes(StandardCharsets.UTF_8));
            start = end;
        } while (start < results.size());
        return List.copyOf(pages);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The item's thread is written {@code open}: the filter judges the item's own text, which a
     * thread's state does not change.
     *
     * @throws IllegalArgumentException When the root is not the item's thread's opening comment (a
     *     comment's own root is itself), the item is not a forge account's at this forge or carries
     *     no time, or it passes a result's bound.
     */
    @Override
    public byte[] one(final PullRequestFacts facts, final Kept item, final Kept root) {
        if (!(root.item() instanceof ReviewComment opening)
                || !root.thread().equals(item.thread())
                || item.item() instanceof ReviewComment && !item.equals(root)) {
            throw new IllegalArgumentException(
                    "the root is not the item's thread's opening comment");
        }
        final String result =
                bounded(Json.write(pageResult(facts, item, line(opening.anchor()), false)));
        return pullRequestDocument(facts, List.of(result), Completeness.COMPLETE.word())
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * {@inheritDoc}
     *
     * <p>A reply stands at the place given and names its thread in {@code porelo/replyTo} alone,
     * which is what routes it.
     *
     * @throws IllegalArgumentException When a comment is given another place than its anchor, or
     *     the item passes a result's bound.
     */
    @Override
    public byte[] submission(
            final PullRequestFacts facts, final ReviewItem item, final LineAnchor place) {
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "review");
        result.put("level", "none");
        final Map<String, Object> properties =
                ordered("porelo/anchorKind", "line", "porelo/diffSide", side(place));
        switch (item) {
            case ReviewComment comment -> {
                if (!line(comment.anchor()).equals(place)) {
                    throw new IllegalArgumentException("a comment is submitted at its own anchor");
                }
                content(result, place, comment.body(), comment.suggestion());
            }
            case ForgeReply reply -> {
                content(result, place, reply.body(), null);
                properties.put("porelo/replyTo", reply.thread());
            }
            case ReviewReply reply -> {
                content(result, place, reply.body(), null);
                properties.put("porelo/replyTo", reply.thread());
            }
        }
        result.put("properties", properties);
        return pullRequestDocument(facts, List.of(bounded(Json.write(result))), null)
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A conversation's item as a page's result: its guid, the forge's ids, its account and the time
     * the forge gave it, which the profile requires of every forge account's item, and its thread's
     * state.
     */
    private static Map<String, Object> pageResult(
            final PullRequestFacts facts,
            final Kept kept,
            final LineAnchor place,
            final boolean resolved) {
        if (!(kept.origin().producer() instanceof ForgeAccount account)
                || !account.forge().equals(facts.ref().forge())) {
            throw new IllegalArgumentException(
                    "a pull request's page holds only accounts at its forge");
        }
        if (!UUID4.matcher(kept.guid()).matches()) {
            throw new IllegalArgumentException("a page's item has no UUIDv4 as its guid");
        }
        final Instant at = kept.at();
        if (at == null || !CREATED_AT.matcher(at.toString()).matches()) {
            throw new IllegalArgumentException(
                    "a page's item has no time in whole seconds of the years 0000 to 9999");
        }
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("guid", kept.guid());
        result.put("kind", "review");
        result.put("level", "none");
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("porelo/anchorKind", "line");
        properties.put("porelo/diffSide", side(place));
        properties.put("porelo/threadId", kept.thread());
        switch (kept.item()) {
            case ReviewComment comment ->
                    content(result, place, comment.body(), comment.suggestion());
            case ForgeReply reply -> {
                content(result, place, reply.body(), null);
                properties.put("porelo/replyTo", reply.thread());
            }
            case ReviewReply reply -> {
                content(result, place, reply.body(), null);
                properties.put("porelo/replyTo", reply.thread());
            }
        }
        properties.put("porelo/threadState", resolved ? "resolved" : "open");
        properties.put("porelo/sourceKind", "forge-account");
        properties.put("porelo/forgeAccountId", account.id());
        properties.put("porelo/forgeAccountName", account.name());
        properties.put("porelo/createdAt", at.toString());
        result.put("properties", properties);
        return result;
    }

    /** A result's message, location and, for a comment's suggestion, its fix. */
    private static void content(
            final Map<String, Object> result,
            final LineAnchor anchor,
            final Body body,
            final @Nullable Suggestion suggestion) {
        result.put("message", Map.of("text", message(body)));
        result.put("locations", List.of(Map.of("physicalLocation", location(anchor))));
        if (suggestion != null) {
            result.put("fixes", List.of(fix(anchor, suggestion)));
        }
    }

    private static String side(final LineAnchor anchor) {
        return anchor.side().word();
    }

    private static String bounded(final String result) {
        if (utf8(result) > MAX_RESULT_BYTES) {
            throw new IllegalArgumentException("an item is past a result's bound of 32 KiB");
        }
        return result;
    }

    /**
     * A pull request run's document: a page when a completeness is given, a submission otherwise.
     */
    private static String pullRequestDocument(
            final PullRequestFacts facts,
            final List<String> results,
            final @Nullable String completeness) {
        final Map<String, Object> run = new LinkedHashMap<>();
        run.put("tool", Map.of("driver", Map.of("name", "Porelo")));
        run.put(
                "versionControlProvenance",
                List.of(ordered("repositoryUri", facts.repository(), "revisionId", facts.head())));
        run.put("columnKind", "unicodeCodePoints");
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("porelo/pullRequest", facts.ref().url().toString());
        properties.put("porelo/baseRevisionId", facts.base());
        if (completeness != null) {
            properties.put("porelo/completeness", completeness);
        }
        run.put("properties", properties);
        return withResults(root("2", completeness != null ? "page" : "submission"), run, results);
    }

    private static String document(
            final Push push, final List<String> results, final String completeness) {
        final Map<String, Object> run = new LinkedHashMap<>();
        run.put("tool", Map.of("driver", Map.of("name", "Porelo")));
        run.put(
                "versionControlProvenance",
                List.of(
                        ordered(
                                "repositoryUri",
                                "urn:porelo:repository:"
                                        + encode(push.project(), false)
                                        + ":"
                                        + encode(push.repository(), false),
                                "revisionId",
                                push.head())));
        run.put("columnKind", "unicodeCodePoints");
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("porelo/project", push.project());
        properties.put("porelo/repository", push.repository());
        properties.put("porelo/push", push.name());
        final String base = push.base();
        if (base != null) {
            properties.put("porelo/baseRevisionId", base);
        }
        properties.put("porelo/completeness", completeness);
        run.put("properties", properties);
        return withResults(root("1", null), run, results);
    }

    /** A document's root properties: its profile and, from profile 2 on, its kind. */
    private static Map<String, Object> root(final String profile, final @Nullable String kind) {
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("porelo/profileVersion", profile);
        if (kind != null) {
            properties.put("porelo/documentKind", kind);
        }
        return properties;
    }

    private static String withResults(
            final Map<String, Object> properties,
            final Map<String, Object> run,
            final List<String> results) {
        run.put("results", List.of());
        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", "2.1.0");
        root.put("$schema", "https://json.schemastore.org/sarif-2.1.0.json");
        root.put("properties", properties);
        root.put("runs", List.of(run));
        final String empty = Json.write(root);
        // Each result is written once on its own, so a page is measured before it is made.
        final String marker = "\"results\":[";
        final int at = empty.lastIndexOf(marker + "]") + marker.length();
        return empty.substring(0, at) + String.join(",", results) + empty.substring(at);
    }

    private static Map<String, Object> result(
            final ReviewThread thread, final ReviewComment root, final Kept kept) {
        final LineAnchor anchor =
                line(kept.item() instanceof ReviewComment own ? own.anchor() : root.anchor());
        final Map<String, Object> result = new LinkedHashMap<>();
        result.put("guid", kept.guid());
        result.put("kind", "review");
        result.put("level", "none");
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("porelo/anchorKind", "line");
        properties.put("porelo/diffSide", anchor.side().word());
        properties.put("porelo/threadId", thread.id());
        switch (kept.item()) {
            case ReviewComment comment -> {
                result.put("message", Map.of("text", message(comment.body())));
                result.put("locations", List.of(Map.of("physicalLocation", location(anchor))));
                final Suggestion suggestion = comment.suggestion();
                if (suggestion != null) {
                    result.put("fixes", List.of(fix(anchor, suggestion)));
                }
            }
            case ReviewReply reply -> {
                result.put("message", Map.of("text", message(reply.body())));
                result.put("locations", List.of(Map.of("physicalLocation", location(anchor))));
                properties.put("porelo/replyTo", thread.id());
            }
            case ForgeReply reply ->
                    throw new IllegalArgumentException(
                            "a forge reply is not in profile 1, which writes a push's threads");
        }
        properties.put(
                "porelo/threadState", thread.state() == ThreadState.OPEN ? "open" : "resolved");
        switch (kept.origin().producer()) {
            case OwnerAccount _ -> properties.put("porelo/sourceKind", "owner-account");
            case Task task -> {
                properties.put("porelo/sourceKind", "task");
                properties.put("porelo/taskId", task.id());
            }
            case ForgeAccount _ ->
                    throw new IllegalArgumentException(
                            "a forge account is not in profile 1, which writes a push's threads");
        }
        result.put("properties", properties);
        return result;
    }

    private static LineAnchor line(final Anchor anchor) {
        return switch (anchor) {
            case LineAnchor line -> line;
        };
    }

    private static Map<String, Object> location(final LineAnchor anchor) {
        return ordered(
                "artifactLocation",
                Map.of("uri", encode(checked(anchor.path(), "path", false), true)),
                "region",
                region(anchor));
    }

    private static Map<String, Object> fix(final LineAnchor anchor, final Suggestion suggestion) {
        final String text = checked(suggestion.replacement(), "replacement", true);
        final Map<String, Object> replacement =
                ordered("deletedRegion", region(anchor), "insertedContent", Map.of("text", text));
        final Map<String, Object> change =
                ordered(
                        "artifactLocation",
                        Map.of("uri", encode(checked(anchor.path(), "path", false), true)),
                        "replacements",
                        List.of(replacement));
        return Map.of("artifactChanges", List.of(change));
    }

    private static Map<String, Object> region(final LineAnchor anchor) {
        return ordered("startLine", anchor.startLine(), "endLine", anchor.endLine());
    }

    /**
     * Plain text as SARIF reads a message: a backslash and square brackets escaped, so no text
     * becomes an embedded link, and literal braces doubled, so none becomes a placeholder.
     */
    private static String message(final Body body) {
        return checked(body.text(), "text", false)
                .replace("\\", "\\\\")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("{", "{{")
                .replace("}", "}}");
    }

    private static String checked(final String text, final String what, final boolean mayBeEmpty) {
        if (!mayBeEmpty && text.isBlank()) {
            throw new IllegalArgumentException("a kept " + what + " is empty");
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("a kept " + what + " is over 8 KiB");
        }
        for (int i = 0; i < text.length(); ) {
            final int c = text.codePointAt(i);
            i += Character.charCount(c);
            if (c == '\n' || c == '\t') {
                continue;
            }
            final int type = Character.getType(c);
            if (type == Character.CONTROL
                    || type == Character.FORMAT
                    || type == Character.SURROGATE
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR) {
                throw new IllegalArgumentException(
                        "a kept "
                                + what
                                + " holds a refused character, U+"
                                + UPPER_HEX.toHexDigits(c).replaceFirst("^0{0,4}", ""));
            }
        }
        return text;
    }

    /**
     * Writes text as a URI reference: every byte but the unreserved ones, and for a path the
     * separator, percent-encoded.
     */
    private static String encode(final String text, final boolean path) {
        final StringBuilder out = new StringBuilder();
        for (final byte b : text.getBytes(StandardCharsets.UTF_8)) {
            final int c = b & 0xff;
            if ((c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '-'
                    || c == '.'
                    || c == '_'
                    || c == '~'
                    || (path && c == '/')) {
                out.append((char) c);
            } else {
                out.append('%').append(UPPER_HEX.toHexDigits(b));
            }
        }
        return out.toString();
    }

    private static int start(final Push push, final List<String> guids, final String cursor)
            throws ReviewRefused {
        final Matcher matcher = CURSOR.matcher(cursor);
        if (!matcher.matches()) {
            throw refused();
        }
        final int offset = Integer.parseInt(matcher.group(1));
        if (offset < 1
                || offset >= guids.size()
                || !matcher.group(2).equals(digest(push, guids.subList(0, offset)))) {
            throw refused();
        }
        return offset;
    }

    private static ReviewRefused refused() {
        return new ReviewRefused(new ReviewRefusal(ReviewError.CURSOR_REFUSED, null));
    }

    /**
     * Binds a cursor to one push of one task at one head and to every result before it: once any of
     * those changed, a reply added to an earlier thread among them, the cursor is refused and the
     * reader starts again, so no result is repeated or lost.
     */
    private static String digest(final Push push, final List<String> before) {
        final List<String> parts =
                new ArrayList<>(
                        List.of(
                                push.project(),
                                push.repository(),
                                push.name(),
                                push.task(),
                                push.head()));
        parts.addAll(before);
        final String identity = String.join("\0", parts);
        try {
            return HEX.formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(identity.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 16);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }

    private static long utf8(final String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static Map<String, Object> ordered(
            final String k1, final Object v1, final String k2, final Object v2) {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }
}
