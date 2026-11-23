package org.fuin.porelo.daemon;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.Json;
import org.fuin.porelo.wire.varlink.VarlinkException;

/**
 * Measures rows into a reply of one message: how many of a list, from a position, fit the room a
 * reply leaves them.
 */
final class MessagePages {

    /**
     * Room in one message for what an estimate leaves out: a position's digits and names, the
     * commas between rows of two lists. The reply is measured again whole before it is sent.
     */
    static final int ENVELOPE = 1024;

    private MessagePages() {}

    /**
     * Returns what one value takes as JSON on the wire.
     *
     * @param value A row or a reply.
     * @return Its UTF-8 bytes.
     */
    static long sent(final Object value) {
        return Json.write(value).getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Returns how many rows from a position fit a room, each with the comma before it.
     *
     * @param rows The rows.
     * @param from The first row's index.
     * @param room The bytes left for them.
     * @return How many, 0 when not even the first fits.
     */
    static int fitting(final List<?> rows, final int from, final long room) {
        long left = room;
        int count = 0;
        for (int i = from; i < rows.size(); i++) {
            final long size = sent(rows.get(i)) + 1;
            if (size > left) {
                break;
            }
            left -= size;
            count++;
        }
        return count;
    }

    /**
     * Returns a reply after measuring it whole.
     *
     * @param reply The reply.
     * @param limit The most bytes a message may have.
     * @return The reply.
     * @throws VarlinkException Failed when it does not fit, which an estimate never lets happen.
     */
    static Map<String, Object> checked(final Map<String, Object> reply, final int limit) {
        if (ReviewCut.bytes(reply) > limit) {
            throw LogPages.failed("A page did not fit one message");
        }
        return reply;
    }

    /**
     * Returns InvalidParameter naming a parameter.
     *
     * @param parameter Its name.
     * @return The error.
     */
    static VarlinkException invalid(final String parameter) {
        return new VarlinkException(
                "org.varlink.service.InvalidParameter", Map.of("parameter", parameter));
    }
}
