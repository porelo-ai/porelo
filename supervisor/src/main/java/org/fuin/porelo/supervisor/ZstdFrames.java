package org.fuin.porelo.supervisor;

/**
 * Walks the frames of a zstd body as its bytes arrive, without decoding them, and refuses one the
 * broker will not decode.
 *
 * <p><strong>Why a walk beside the decoder.</strong> A zstd frame names its own window, up to 2
 * GiB, and a decoder keeps that much of the output to refer back to: one small request could make
 * the broker hold two gigabytes. RFC 9659 bounds the window of the {@code zstd} content coding to 8
 * MiB, and a frame that names more is refused here before the decoder sees it. The walk also knows
 * where the last frame ends, so bytes after it are refused rather than passed on unread.
 */
final class ZstdFrames {

    /** The largest window a frame may name: the bound RFC 9659 sets for HTTP. */
    static final long WINDOW_LIMIT = 8L * 1024 * 1024;

    /** The largest block a frame may hold. */
    private static final int BLOCK_LIMIT = 128 * 1024;

    private static final int MAGIC = 0xFD2FB528;

    private static final int SKIPPABLE = 0x184D2A50;

    private enum State {
        MAGIC,
        DESCRIPTOR,
        HEADER,
        BLOCK_HEADER,
        BLOCK,
        CHECKSUM,
        SKIP_SIZE,
        SKIP
    }

    private final long windowLimit;

    private State state = State.MAGIC;

    private final byte[] field = new byte[14];

    private int have;

    private int want = 4;

    private long skip;

    private int descriptor;

    private boolean lastBlock;

    /**
     * Creates a walk that refuses a frame naming a window past a bound.
     *
     * @param windowLimit The largest window a frame may name.
     */
    ZstdFrames(final long windowLimit) {
        this.windowLimit = windowLimit;
    }

    /**
     * Reads the next bytes of the body.
     *
     * @param bytes Where they are.
     * @param offset Where they start.
     * @param length How many there are.
     * @throws ScannedBody.Refused If the frames break, or a frame names a window past the bound.
     */
    void accept(final byte[] bytes, final int offset, final int length) throws ScannedBody.Refused {
        int at = offset;
        final int end = offset + length;
        while (at < end) {
            if (state == State.BLOCK || state == State.SKIP) {
                final int passed = (int) Math.min(skip, end - at);
                at += passed;
                skip -= passed;
                if (skip == 0) {
                    if (state == State.BLOCK) {
                        afterBlock();
                    } else {
                        expect(State.MAGIC, 4);
                    }
                }
                continue;
            }
            field[have++] = bytes[at++];
            if (have == want) {
                complete();
            }
        }
    }

    /**
     * Ends the body.
     *
     * @throws ScannedBody.Refused If it ends inside a frame.
     */
    void end() throws ScannedBody.Refused {
        if (state != State.MAGIC || have != 0) {
            throw unreadable("the zstd body ends inside a frame");
        }
    }

    private void complete() throws ScannedBody.Refused {
        switch (state) {
            case MAGIC -> {
                final int magic = (int) little(0, 4);
                if (magic == MAGIC) {
                    expect(State.DESCRIPTOR, 1);
                } else if ((magic & 0xFFFFFFF0) == SKIPPABLE) {
                    expect(State.SKIP_SIZE, 4);
                } else {
                    throw unreadable("the zstd body holds something that is not a frame");
                }
            }
            case DESCRIPTOR -> {
                descriptor = field[0] & 0xFF;
                if ((descriptor & 0x08) != 0) {
                    throw unreadable("a zstd frame sets its reserved bit");
                }
                expect(State.HEADER, (single() ? 0 : 1) + dictionary() + contentSize());
            }
            case HEADER -> {
                final long window;
                if (single()) {
                    final int size = contentSize();
                    window = little(dictionary(), size) + (size == 2 ? 256 : 0);
                } else {
                    final int exponent = (field[0] & 0xFF) >>> 3;
                    final int mantissa = field[0] & 0x07;
                    final long base = 1L << (10 + exponent);
                    window = base + base / 8 * mantissa;
                }
                if (window < 0 || window > windowLimit) {
                    throw new ScannedBody.Refused(
                            VaultProxy.Reading.UNSUPPORTED,
                            "a zstd frame names a window past " + windowLimit + " bytes");
                }
                expect(State.BLOCK_HEADER, 3);
            }
            case BLOCK_HEADER -> {
                final int header = (int) little(0, 3);
                lastBlock = (header & 1) != 0;
                final int type = (header >>> 1) & 3;
                final int size = header >>> 3;
                if (type == 3 || size > BLOCK_LIMIT) {
                    throw unreadable("a zstd block is not one");
                }
                final int content = type == 1 ? 1 : size;
                if (content == 0) {
                    afterBlock();
                } else {
                    skip = content;
                    expect(State.BLOCK, 0);
                }
            }
            case CHECKSUM -> expect(State.MAGIC, 4);
            case SKIP_SIZE -> {
                skip = little(0, 4);
                if (skip == 0) {
                    expect(State.MAGIC, 4);
                } else {
                    expect(State.SKIP, 0);
                }
            }
            default -> throw new IllegalStateException("not a field: " + state);
        }
    }

    private void afterBlock() {
        if (!lastBlock) {
            expect(State.BLOCK_HEADER, 3);
        } else if ((descriptor & 0x04) != 0) {
            expect(State.CHECKSUM, 4);
        } else {
            expect(State.MAGIC, 4);
        }
    }

    private void expect(final State next, final int bytes) {
        state = next;
        want = bytes;
        have = 0;
        if (bytes == 0 && next != State.BLOCK && next != State.SKIP) {
            throw new IllegalStateException("an empty field");
        }
    }

    private boolean single() {
        return (descriptor & 0x20) != 0;
    }

    private int dictionary() {
        return switch (descriptor & 0x03) {
            case 0 -> 0;
            case 1 -> 1;
            case 2 -> 2;
            default -> 4;
        };
    }

    private int contentSize() {
        return switch (descriptor >>> 6) {
            case 0 -> single() ? 1 : 0;
            case 1 -> 2;
            case 2 -> 4;
            default -> 8;
        };
    }

    private long little(final int from, final int bytes) {
        long value = 0;
        for (int i = bytes - 1; i >= 0; i--) {
            value = value << 8 | field[from + i] & 0xFF;
        }
        return value;
    }

    private static ScannedBody.Refused unreadable(final String message) {
        return new ScannedBody.Refused(VaultProxy.Reading.UNREADABLE, message);
    }
}
