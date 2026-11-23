package org.fuin.porelo.supervisor;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import java.util.zip.ZipException;
import org.jspecify.annotations.Nullable;

/**
 * Decodes a gzip body one member at a time, accounting for every byte of it.
 *
 * <p><strong>Why not {@link java.util.zip.GZIPInputStream}.</strong> After a member it reads on for
 * the next, and when what follows is not a member it can read - a header whose checksum is wrong,
 * or bytes that are no header at all - it reports the end of the stream and drops what it had
 * already taken in. Another decoder may read those bytes: Python's gzip does not check a header's
 * checksum. Here a member that does not parse, a member that ends early, and bytes after the last
 * member that are not a whole member are each an error, so what this reads is all the body holds.
 */
final class GzipMembers extends InputStream {

    private static final int FTEXT_RESERVED = 0xE0;

    private static final int FHCRC = 0x02;

    private static final int FEXTRA = 0x04;

    private static final int FNAME = 0x08;

    private static final int FCOMMENT = 0x10;

    private final InputStream in;

    private final byte[] buffer = new byte[8192];

    private final Inflater inflater = new Inflater(true);

    private final CRC32 crc = new CRC32();

    private int position;

    private int limit;

    private long size;

    private long consumed;

    private boolean inMember;

    private boolean started;

    private boolean ended;

    /**
     * Creates a decoder of the members a stream holds.
     *
     * @param in The coded body.
     */
    GzipMembers(final InputStream in) {
        this.in = in;
    }

    /**
     * Returns how many bytes of the coded body have been decoded so far: what is read ahead and not
     * yet decoded does not count.
     *
     * @return The bytes.
     */
    long consumed() {
        return consumed;
    }

    @Override
    public int read() throws IOException {
        final byte[] one = new byte[1];
        final int got = read(one, 0, 1);
        return got < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(final byte[] out, final int offset, final int length) throws IOException {
        java.util.Objects.checkFromIndexSize(offset, length, out.length);
        if (length == 0) {
            return 0;
        }
        while (!ended) {
            if (!inMember) {
                // The first member must be there; after one, only the end or another whole one.
                if (started && position == limit && fill() < 0) {
                    ended = true;
                    return -1;
                }
                header();
                started = true;
                inMember = true;
            }
            if (inflater.needsInput()) {
                // What the header left in the buffer is the start of the data.
                if (position == limit && fill() < 0) {
                    throw new EOFException("a gzip member ends before its data does");
                }
                inflater.setInput(buffer, position, limit - position);
            }
            final int got;
            try {
                got = inflater.inflate(out, offset, length);
            } catch (final DataFormatException ex) {
                throw new ZipException("a gzip member's data does not inflate");
            }
            final int taken = limit - inflater.getRemaining();
            consumed += taken - position;
            position = taken;
            if (got > 0) {
                crc.update(out, offset, got);
                size += got;
                return got;
            }
            if (inflater.finished()) {
                trailer();
                inflater.reset();
                crc.reset();
                size = 0;
                inMember = false;
            } else if (inflater.needsDictionary()) {
                throw new ZipException("a gzip member asks for a dictionary");
            }
        }
        return -1;
    }

    @Override
    public void close() {
        inflater.end();
    }

    /** Reads a member's header, checking its checksum when it names one. */
    private void header() throws IOException {
        final CRC32 sum = new CRC32();
        if (next(sum) != 0x1f || next(sum) != 0x8b) {
            throw new ZipException("a gzip member does not start as one");
        }
        if (next(sum) != 8) {
            throw new ZipException("a gzip member is not deflated");
        }
        final int flags = next(sum);
        if ((flags & FTEXT_RESERVED) != 0) {
            throw new ZipException("a gzip member sets a reserved flag");
        }
        // Modification time, extra flags, operating system.
        for (int i = 0; i < 6; i++) {
            next(sum);
        }
        if ((flags & FEXTRA) != 0) {
            final int extra = next(sum) | next(sum) << 8;
            for (int i = 0; i < extra; i++) {
                next(sum);
            }
        }
        if ((flags & FNAME) != 0) {
            while (next(sum) != 0) {
                // The name is not used.
            }
        }
        if ((flags & FCOMMENT) != 0) {
            while (next(sum) != 0) {
                // The comment is not used.
            }
        }
        if ((flags & FHCRC) != 0) {
            final int expected = (int) sum.getValue() & 0xffff;
            if ((next(null) | next(null) << 8) != expected) {
                throw new ZipException("a gzip member's header does not match its checksum");
            }
        }
    }

    /** Reads a member's trailer and checks it against what was decoded. */
    private void trailer() throws IOException {
        final long sum = word();
        final long length = word();
        if (sum != crc.getValue() || length != (size & 0xffffffffL)) {
            throw new ZipException("a gzip member does not match its trailer");
        }
    }

    private long word() throws IOException {
        long value = 0;
        for (int i = 0; i < 4; i++) {
            value |= (long) next(null) << 8 * i;
        }
        return value;
    }

    /** The next byte of a header or trailer, which must be there. */
    private int next(final @Nullable CRC32 sum) throws IOException {
        if (position == limit && fill() < 0) {
            throw new EOFException("a gzip member ends inside its header or trailer");
        }
        final int value = buffer[position++] & 0xFF;
        consumed++;
        if (sum != null) {
            sum.update(value);
        }
        return value;
    }

    /** Reads more of the body when all read so far is used. */
    private int fill() throws IOException {
        int got;
        do {
            got = in.read(buffer, 0, buffer.length);
        } while (got == 0);
        if (got > 0) {
            position = 0;
            limit = got;
        }
        return got;
    }
}
