package org.fuin.porelo.daemon;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.gate.PatchFiles;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.varlink.VarlinkException;

/**
 * Fits a review into one reply: the whole review when it fits; else the diff cut at a file boundary
 * and the cut named, with the file list, the commits and the log whole; else each part's first
 * page, and {@code paged} saying where the rest starts.
 *
 * <p>The ranking was made from the whole change before anything was cut, and a cut changes what is
 * shown, never what it is ranked as. A diff that is cut or paged says where its next page starts,
 * and {@link ReviewPages} gives the rest. Only a reply that does not fit even with every part empty
 * is refused by name.
 */
final class ReviewCut {

    /**
     * Room kept for what an estimate cannot see: the cut's counts growing a digit. The result is
     * measured again whole before it is sent.
     */
    private static final int SLACK = 32;

    private ReviewCut() {}

    /**
     * Returns the reply as it can be sent.
     *
     * @param reply The whole review, its {@code diff} the whole patch.
     * @param name The pending push's name, for the refusal.
     * @param base The full base commit the review was read against, "" for the whole history.
     * @param command The command that shows the whole review on this machine.
     * @param limit The most bytes a message may have on the wire, without its terminating NUL.
     * @return The reply, unchanged when it fits; otherwise with a shorter {@code diff}, a {@code
     *     cut} saying what is not in it and {@code paged}; otherwise each part's first page and
     *     {@code paged}.
     * @throws VarlinkException {@code ReviewTooLarge} when the review does not fit even with every
     *     part empty.
     */
    static Map<String, Object> fitted(
            final Map<String, Object> reply,
            final String name,
            final String base,
            final List<String> command,
            final int limit) {
        if (bytes(reply) <= limit) {
            return reply;
        }
        final PatchFiles.Split split = PatchFiles.split(String.valueOf(reply.get("diff")));
        final List<PatchFiles.Part> parts = split.parts();
        if (parts.isEmpty()) {
            return ReviewPages.first(reply, name, base, command, limit);
        }
        final int none = bytes(cut(reply, split, 0, base, command));
        if (none > limit) {
            // The lists and the log do not fit whole beside an empty diff: every part is paged.
            return ReviewPages.first(reply, name, base, command, limit);
        }
        // Whole files in the patch's order, the dangerous first, up to the first that does not fit.
        long estimate = none;
        int shown = 0;
        while (shown < parts.size()) {
            final PatchFiles.Part part = parts.get(shown);
            final long next = estimate + quoted(part.text()) - 2 - quoted(part.path());
            if (next + SLACK > limit) {
                break;
            }
            estimate = next;
            shown++;
        }
        Map<String, Object> fitted = cut(reply, split, shown, base, command);
        while (shown > 0 && bytes(fitted) > limit) {
            shown--;
            fitted = cut(reply, split, shown, base, command);
        }
        return fitted;
    }

    /** The reply with only the first files' patch, and the cut that says so. */
    private static Map<String, Object> cut(
            final Map<String, Object> reply,
            final PatchFiles.Split split,
            final int shown,
            final String base,
            final List<String> command) {
        final List<PatchFiles.Part> parts = split.parts();
        final StringBuilder diff = new StringBuilder(split.preamble());
        for (final PatchFiles.Part part : parts.subList(0, shown)) {
            diff.append(part.text());
        }
        final String sent = diff.toString();
        final Map<String, Object> cut = new LinkedHashMap<>();
        cut.put("shownFiles", shown);
        cut.put("omittedFiles", parts.size() - shown);
        cut.put(
                "omitted",
                parts.subList(shown, parts.size()).stream().map(PatchFiles.Part::path).toList());
        cut.put("command", command);
        final Map<String, Object> fitted = new LinkedHashMap<>(reply);
        fitted.put("diff", sent);
        fitted.put("cut", cut);
        fitted.put(
                "paged",
                ReviewPages.diffPaged(reply, base, sent.getBytes(StandardCharsets.UTF_8).length));
        return fitted;
    }

    /** What a reply takes on the wire, as the server frames it, without the NUL after it. */
    static int bytes(final Map<String, Object> reply) {
        return Json.write(Map.of("parameters", reply)).getBytes(StandardCharsets.UTF_8).length;
    }

    /** What a string takes as JSON, quotes and escapes included. */
    private static long quoted(final String text) {
        return Json.write(text).getBytes(StandardCharsets.UTF_8).length;
    }
}
