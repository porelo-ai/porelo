package org.fuin.porelo.supervisor;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * A request body passed on as it arrives, every byte of it read for a token grant first, and its
 * end held back until all of it has been.
 *
 * <p><strong>What it holds.</strong> One read from the agent ({@link #CHUNK}), the bytes read and
 * not yet handed on - about two reads, which a coded body may let grow to {@link #HELD}, and a zstd
 * body to its window more, while its decoder produces nothing - a decoder's own window (32 KiB for
 * gzip and deflate, at most {@link ZstdFrames#WINDOW_LIMIT} for zstd), and the scans' windows of 16
 * KiB each. Never the body: a body without a coding holds about 200 KiB however large it is, a zstd
 * body at most about 17 MiB.
 *
 * <p><strong>Read before it is handed on.</strong> Each read from the agent is scanned as written,
 * form-decoded and JSON-unescaped before any of it can be handed on; a coded body is also decoded,
 * and what it decodes to is scanned in the same three readings. A grant found ends the body with
 * {@link Refused}, which the client sending it upstream turns into an aborted request.
 *
 * <p><strong>The end is held back.</strong> The last byte read is handed on only once the body has
 * ended, its framing and coding have been checked to their ends and every reading has been searched
 * to the last character. A grant found anywhere - in the last read, in a decoder's last output, in
 * an escape the end completes - therefore always stops the request before the destination has all
 * of it: a request with a length is one byte short, a chunked one has no last chunk.
 */
final class ScannedBody extends InputStream {

    /** The most read from the agent at once. */
    static final int CHUNK = 64 * 1024;

    /**
     * How much is read and judged before the destination is reached at all: a token request is
     * small, so one is refused before even the credential's header leaves this machine.
     */
    static final int PREFETCH = 64 * 1024;

    /**
     * The most bytes read and not yet handed on, past a zstd frame's window: a decoder may hold
     * back a whole frame's output until the frame ends (measured: aircompressor does for a frame of
     * one segment), so up to a window of coded bytes may wait for it. A coded body whose decoder
     * produces nothing for longer is refused, rather than held without bound.
     */
    static final int HELD = 1024 * 1024;

    /**
     * The most a coded body may unpack to per byte it carries, once {@link #RATIO_FROM} bytes are
     * decoded: each is decoded and scanned three ways, so this bounds the work one byte the agent
     * sends may cost. Deflate itself cannot pass about 1032 to one, which only a run of one
     * repeated byte comes near; zstd passes 30000 to one in blocks of one repeated byte, and 64 KiB
     * unpacked to 2 GiB before the bound refused it.
     */
    static final int RATIO = 1000;

    /** How much is decoded before {@link #RATIO} applies: a short body's header weighs too much. */
    static final int RATIO_FROM = 1024 * 1024;

    /** Why a body is not passed on. */
    static final class Refused extends IOException {

        private static final long serialVersionUID = 1L;

        /** What the body was read as. */
        private final VaultProxy.Reading reading;

        Refused(final VaultProxy.Reading reading, final String message) {
            super(message);
            this.reading = reading;
        }

        /**
         * Returns what the body was read as.
         *
         * @return The reading.
         */
        VaultProxy.Reading reading() {
            return reading;
        }
    }

    private final Framed source;

    private final long limit;

    private final @Nullable String coding;

    private final GrantScan written = new GrantScan();

    private final GrantScan decoded = new GrantScan();

    private final @Nullable ZstdFrames frames;

    private final long heldLimit;

    private final Tee tee = new Tee();

    private final PushbackInputStream coded = new PushbackInputStream(tee, 1);

    private final byte[] scratch = new byte[CHUNK];

    private byte[] held = new byte[2 * CHUNK];

    private int first;

    private int last;

    private long read;

    private long unpacked;

    private boolean sourceEnded;

    private boolean decoderEnded;

    private boolean done;

    private @Nullable InputStream decoder;

    private java.util.zip.@Nullable Inflater inflater;

    private @Nullable GzipMembers members;

    /**
     * Written on the thread that sends the body upstream, read on the one that answers the agent
     * once the sending failed: volatile, so the answer names why.
     */
    private volatile VaultProxy.@Nullable Reading verdict;

    /**
     * Creates the body of a request.
     *
     * @param in The agent's connection, at the first byte of the body.
     * @param length The declared length, or {@code -1} for a chunked body.
     * @param coding The body's only content coding - {@code gzip}, {@code x-gzip}, {@code deflate}
     *     or {@code zstd} - or {@code null} for none.
     * @param limit The most the body may carry, as sent and as decoded.
     * @param window The largest window a zstd frame may name.
     */
    ScannedBody(
            final InputStream in,
            final long length,
            final @Nullable String coding,
            final long limit,
            final long window) {
        this.source = new Framed(in, length, limit);
        this.limit = limit;
        this.coding = coding == null ? null : coding.toLowerCase(Locale.ROOT);
        this.frames = "zstd".equals(this.coding) ? new ZstdFrames(window) : null;
        this.heldLimit = (this.frames == null ? 0 : window) + HELD;
    }

    /**
     * Reads ahead until {@link #PREFETCH} bytes are held or the body has ended.
     *
     * @throws Refused If the body is refused on what was read.
     */
    void prefetch() throws Refused {
        while (!done && queued() < PREFETCH) {
            step();
        }
    }

    /**
     * Returns whether all of the body has been read and judged.
     *
     * @return Whether it has.
     */
    boolean ended() {
        return done;
    }

    /**
     * Returns what was held, once the whole body has been read: a short body goes on as it is.
     *
     * @return The body.
     */
    byte[] whole() {
        if (!done) {
            throw new IllegalStateException("the body has not been read to its end");
        }
        return java.util.Arrays.copyOfRange(held, first, last);
    }

    /**
     * Returns why the body was refused, or {@code null} while it is not.
     *
     * @return The reading that refused it.
     */
    VaultProxy.@Nullable Reading verdict() {
        return verdict;
    }

    @Override
    public int read() throws IOException {
        final byte[] one = new byte[1];
        final int got = read(one, 0, 1);
        return got < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(final byte[] buffer, final int offset, final int length) throws IOException {
        java.util.Objects.checkFromIndexSize(offset, length, buffer.length);
        if (length == 0) {
            return 0;
        }
        while (true) {
            stopIfRefused();
            // The last byte stays until the end is judged.
            final int ready = done ? queued() : queued() - 1;
            if (ready > 0) {
                final int given = Math.min(length, ready);
                System.arraycopy(held, first, buffer, offset, given);
                first += given;
                return given;
            }
            if (done) {
                return -1;
            }
            step();
        }
    }

    /** One step on: one read from the agent, or one from the decoder. */
    private void step() throws Refused {
        try {
            if (coding == null) {
                if (tee.read(scratch, 0, CHUNK) < 0) {
                    finish();
                }
            } else if (!decoderEnded) {
                decode();
            } else if (coded.read() >= 0) {
                throw refuse(
                        VaultProxy.Reading.UNREADABLE, "the request body goes on past its coding");
            } else {
                finish();
            }
            stopIfRefused();
        } catch (final Refused refused) {
            throw refused;
        } catch (final IOException | RuntimeException ex) {
            stopIfRefused();
            throw refuse(VaultProxy.Reading.UNREADABLE, "the request body does not decode");
        }
    }

    private void decode() throws IOException {
        InputStream from = decoder;
        if (from == null) {
            final int opening = coded.read();
            stopIfRefused();
            if (opening < 0) {
                // No body at all: nothing to decode.
                decoderEnded = true;
                return;
            }
            coded.unread(opening);
            from = open();
            decoder = from;
        }
        final int got = from.read(scratch, 0, CHUNK);
        stopIfRefused();
        if (got < 0) {
            decoderEnded = true;
            final java.util.zip.Inflater used = inflater;
            if (used != null && used.getRemaining() > 0) {
                throw refuse(
                        VaultProxy.Reading.UNREADABLE, "the request body goes on past its coding");
            }
            return;
        }
        unpacked += got;
        if (unpacked > limit) {
            throw refuse(
                    VaultProxy.Reading.TOO_LARGE,
                    "the request body unpacks beyond what one request may carry");
        }
        if (unpacked >= RATIO_FROM && unpacked > RATIO * decodedFrom()) {
            throw refuse(
                    VaultProxy.Reading.TOO_LARGE,
                    "the request body unpacks to more than " + RATIO + " times what it carries");
        }
        if (decoded.accept(scratch, 0, got)) {
            throw refuse(VaultProxy.Reading.MINTS, "the request body asks for a token");
        }
    }

    private InputStream open() throws IOException {
        return switch (java.util.Objects.requireNonNull(coding)) {
            case "gzip", "x-gzip" -> {
                final GzipMembers made = new GzipMembers(coded);
                members = made;
                yield made;
            }
            case "deflate" -> {
                final java.util.zip.Inflater made = new java.util.zip.Inflater();
                inflater = made;
                yield new java.util.zip.InflaterInputStream(coded, made, 8192);
            }
            case "zstd" -> new io.airlift.compress.v3.zstd.ZstdInputStream(coded);
            default ->
                    throw refuse(
                            VaultProxy.Reading.UNSUPPORTED,
                            "the request body is coded in a way the broker does not read");
        };
    }

    /**
     * How much of the coded body the decoder has taken in: exactly, where the decoder says, else
     * all read so far, which reads ahead of it.
     */
    private long decodedFrom() {
        final GzipMembers gzip = members;
        if (gzip != null) {
            return gzip.consumed();
        }
        final java.util.zip.Inflater used = inflater;
        return used != null ? used.getBytesRead() : read;
    }

    /** The body has ended: every reading is searched to its last character. */
    private void finish() throws Refused {
        final boolean asWritten = written.end();
        final boolean asDecoded = coding != null && decoded.end();
        if (asWritten || asDecoded) {
            throw refuse(VaultProxy.Reading.MINTS, "the request body asks for a token");
        }
        done = true;
    }

    private int queued() {
        return last - first;
    }

    private void hold(final byte[] bytes, final int offset, final int length) throws Refused {
        if (queued() + length > heldLimit) {
            throw refuse(
                    VaultProxy.Reading.UNREADABLE,
                    "the request body's coding yields nothing for too long");
        }
        if (last + length > held.length) {
            System.arraycopy(held, first, held, 0, queued());
            last = queued();
            first = 0;
            if (last + length > held.length) {
                held = java.util.Arrays.copyOf(held, Math.max(held.length * 2, last + length));
            }
        }
        System.arraycopy(bytes, offset, held, last, length);
        last += length;
    }

    private void stopIfRefused() throws Refused {
        final VaultProxy.Reading reading = verdict;
        if (reading != null) {
            throw new Refused(reading, "the request body was refused");
        }
    }

    private Refused refuse(final VaultProxy.Reading reading, final String message) {
        if (verdict == null) {
            verdict = reading;
        }
        return new Refused(java.util.Objects.requireNonNull(verdict), message);
    }

    /**
     * What comes from the agent, scanned as written and held to be handed on; read by the decoder
     * when the body is coded, else directly.
     */
    private final class Tee extends InputStream {

        @Override
        public int read() throws IOException {
            final byte[] one = new byte[1];
            final int got = read(one, 0, 1);
            return got < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(final byte[] buffer, final int offset, final int length)
                throws IOException {
            stopIfRefused();
            if (sourceEnded || length == 0) {
                return sourceEnded ? -1 : 0;
            }
            final int got = source.read(buffer, offset, Math.min(length, CHUNK));
            if (got < 0) {
                sourceEnded = true;
                final ZstdFrames walked = frames;
                if (walked != null) {
                    remember(walked::end);
                }
                return -1;
            }
            read += got;
            if (read > limit) {
                throw refuse(
                        VaultProxy.Reading.TOO_LARGE,
                        "the request body is larger than one request may carry");
            }
            if (written.accept(buffer, offset, got)) {
                throw refuse(VaultProxy.Reading.MINTS, "the request body asks for a token");
            }
            final ZstdFrames walked = frames;
            if (walked != null) {
                remember(() -> walked.accept(buffer, offset, got));
            }
            hold(buffer, offset, got);
            return got;
        }

        /** Until the body has ended there may be more: a decoder asks before it reads on. */
        @Override
        public int available() {
            return sourceEnded ? 0 : 1;
        }
    }

    /** A check that may refuse. */
    @FunctionalInterface
    private interface Check {
        void run() throws Refused;
    }

    /** Runs a check, keeping its refusal as the verdict even when a decoder swallows it. */
    private void remember(final Check check) throws Refused {
        try {
            check.run();
        } catch (final Refused refused) {
            throw refuse(refused.reading(), String.valueOf(refused.getMessage()));
        }
    }

    /**
     * The body as the agent framed it: a declared length, or chunks. A body that ends early or
     * breaks its framing is refused, never handed on as a shorter one.
     */
    private final class Framed extends InputStream {

        private final InputStream in;

        private final boolean chunked;

        private final long limit;

        /** Left of the declared length, or of the current chunk. */
        private long left;

        private long total;

        private boolean ended;

        Framed(final InputStream in, final long length, final long limit) {
            this.in = in;
            this.chunked = length < 0;
            this.left = Math.max(0, length);
            this.limit = limit;
            this.ended = length == 0;
        }

        @Override
        public int read() throws IOException {
            throw new UnsupportedOperationException("read in blocks");
        }

        @Override
        public int read(final byte[] buffer, final int offset, final int length)
                throws IOException {
            try {
                if (ended) {
                    return -1;
                }
                if (chunked && left == 0 && !nextChunk()) {
                    ended = true;
                    return -1;
                }
                final int got = in.read(buffer, offset, (int) Math.min(length, left));
                if (got < 0) {
                    throw broken("the request body ended before its declared end");
                }
                left -= got;
                if (left == 0) {
                    if (chunked) {
                        final String separator = HttpHead.line(in);
                        if (separator == null || !separator.isEmpty()) {
                            throw broken("a chunk was not followed by the required blank line");
                        }
                    } else {
                        ended = true;
                    }
                }
                return got;
            } catch (final Refused refused) {
                throw refused;
            } catch (final IOException ex) {
                throw broken("the request body could not be read");
            }
        }

        /** Reads the next chunk's size, or the last chunk and its trailers. */
        private boolean nextChunk() throws IOException {
            final String header = HttpHead.line(in);
            if (header == null) {
                throw broken("the chunked body ended without its last chunk");
            }
            final int semicolon = header.indexOf(';');
            // An extension is ignored; the size before it must be hexadecimal digits alone.
            final String size = (semicolon < 0 ? header : header.substring(0, semicolon)).strip();
            if (!size.matches("[0-9A-Fa-f]{1,15}")) {
                throw broken("a chunk size that is not one");
            }
            final long length = Long.parseLong(size, 16);
            if (length == 0) {
                String trailer;
                while ((trailer = HttpHead.line(in)) != null) {
                    if (trailer.isEmpty()) {
                        return false;
                    }
                }
                throw broken("the chunked body ended without its final blank line");
            }
            total += length;
            if (total > limit) {
                // Refused on the size alone: nothing of a chunk that passes the bound is read.
                throw refuse(
                        VaultProxy.Reading.TOO_LARGE,
                        "the request body is larger than one request may carry");
            }
            left = length;
            return true;
        }

        private Refused broken(final String message) {
            return refuse(VaultProxy.Reading.BROKEN, message);
        }
    }
}
