package org.fuin.porelo.supervisor;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Holds an answer that declares no type to the framing of server-sent events that carry JSON, and
 * passes on only what it has judged.
 *
 * <p>An answer with no {@code Content-Type} that opens like events says nothing about its other
 * lines. The credential scan reads JSON member names only, so a form value, a YAML key or a token
 * in a comment would cross unseen. This lets such an answer through only as a typed event stream
 * looks: every line empty, a comment, an {@code event:}, {@code id:} or {@code retry:} field, or a
 * {@code data:} field whose value opens as JSON - an object whose first member is quoted, or an
 * array of JSON - or is {@code [DONE]}. The credential scan then reads what passes.
 *
 * <p><strong>Nothing of a line is passed on before it is judged.</strong> A comment or a field
 * other than {@code data:} is held whole until its line ends and refused if it names one of the
 * scan's credential fields in any spelling; judged only at its end while passed on as it came, a
 * read that ended first had already handed the token over. A {@code data:} line is held only until
 * its JSON has begun, then passed on as it arrives, since a completion is one long line.
 */
final class EventFraming {

    /** Why an answer was refused, said without anything of what it carried. */
    static final class Refused extends Exception {

        private static final long serialVersionUID = 1L;

        Refused(final String why) {
            super(why);
        }
    }

    /** The fields of an event stream other than {@code data:}, whose values are never JSON. */
    private static final List<String> FIELDS = List.of("event:", "id:", "retry:");

    private static final String DATA = "data:";

    private static final String DONE = "[DONE]";

    /**
     * The longest line held to judge it; nothing a provider sends as a comment or field is near.
     */
    private static final int LINE_LIMIT = 4096;

    /** A credential field's name in any case, with its underscore perhaps percent-encoded. */
    private static final Pattern NAMED =
            Pattern.compile(
                    "access(?:_|%5f)token|refresh(?:_|%5f)token|id(?:_|%5f)token",
                    Pattern.CASE_INSENSITIVE);

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /** What a line has turned out to be so far. */
    private enum Line {
        /** Too little of it has arrived to tell. */
        OPEN,
        /** A comment or a field other than data, held to its end. */
        KEPT,
        /** A data field whose JSON has begun, passed on as it arrives. */
        PASSING
    }

    /** The current line's bytes that are not passed on yet. */
    private final ByteArrayOutputStream held = new ByteArrayOutputStream();

    private Line line = Line.OPEN;

    private int bomLeft = BOM.length;

    /**
     * Takes the next bytes of the answer and returns those that may be passed on.
     *
     * @param chunk The next bytes.
     * @param length How many of them are used.
     * @return What has been judged and may go to the agent; may be empty while a line is held.
     * @throws Refused If a line is not an event field of JSON.
     */
    byte[] pass(final byte[] chunk, final int length) throws Refused {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int at = 0; at < length; at++) {
            final byte each = chunk[at];
            if (bomLeft > 0) {
                if (each == BOM[BOM.length - bomLeft]) {
                    bomLeft--;
                    continue;
                }
                if (bomLeft < BOM.length) {
                    throw new Refused("a broken byte order mark");
                }
                bomLeft = 0;
            }
            if (each == '\n' || each == '\r') {
                endOfLine(out);
                out.write(each);
                continue;
            }
            if (line == Line.PASSING) {
                out.write(each);
                continue;
            }
            held.write(each);
            if (held.size() > LINE_LIMIT) {
                throw new Refused("a line too long to be an event field");
            }
            if (line == Line.OPEN) {
                decide(out);
            }
        }
        return out.toByteArray();
    }

    /**
     * Ends the answer, judging the line it ends in.
     *
     * @return What of that line may still be passed on.
     * @throws Refused If that line is not an event field of JSON.
     */
    byte[] end() throws Refused {
        if (bomLeft > 0 && bomLeft < BOM.length) {
            throw new Refused("a broken byte order mark");
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        endOfLine(out);
        return out.toByteArray();
    }

    /**
     * Tells what an open line is as soon as its first bytes allow, and refuses what it cannot be.
     */
    private void decide(final ByteArrayOutputStream out) throws Refused {
        final String text = held.toString(StandardCharsets.ISO_8859_1);
        if (text.startsWith(":") || FIELDS.stream().anyMatch(text::startsWith)) {
            line = Line.KEPT;
            return;
        }
        if (text.startsWith(DATA)) {
            final String value = text.substring(DATA.length()).stripLeading();
            if (value.isEmpty() || DONE.startsWith(value)) {
                return;
            }
            final char opening = value.charAt(0);
            final String rest = value.substring(1).stripLeading();
            if (opening != '{' && opening != '[') {
                throw new Refused("a data field that is not JSON");
            }
            if (rest.isEmpty()) {
                return;
            }
            if ((opening == '{' ? "\"}" : "{[\"]").indexOf(rest.charAt(0)) < 0) {
                throw new Refused("a data field that is not JSON");
            }
            line = Line.PASSING;
            out.writeBytes(held.toByteArray());
            held.reset();
            return;
        }
        final boolean couldBecomeOne =
                DATA.startsWith(text) || FIELDS.stream().anyMatch(field -> field.startsWith(text));
        if (!couldBecomeOne) {
            throw new Refused("a line that is no event field");
        }
    }

    /** Judges what of the line is still held, passes it on if it may go, and starts the next. */
    private void endOfLine(final ByteArrayOutputStream out) throws Refused {
        final String text = held.toString(StandardCharsets.ISO_8859_1);
        if (line == Line.KEPT) {
            if (NAMED.matcher(text).find()) {
                throw new Refused("a comment or field that names a credential");
            }
        } else if (line == Line.OPEN && !text.isEmpty()) {
            final String value =
                    text.startsWith(DATA) ? text.substring(DATA.length()).strip() : null;
            final boolean empty = text.equals("data") || value != null && value.isEmpty();
            if (!empty && !DONE.equals(value)) {
                throw new Refused("a line that is no event field");
            }
        }
        out.writeBytes(held.toByteArray());
        held.reset();
        line = Line.OPEN;
    }
}
