package org.fuin.porelo.wire;

/**
 * Where the NFLOG reader writes what it saw.
 * <p>
 * In {@code porelo-wire} because three separate programs agree on it and none of them can depend
 * on the others: the reader hook creates it, the {@code porelo shield read} process appends to it,
 * and the clearance watcher follows it. A constant in any one of them would be a name the other
 * two had copied.
 */
public final class ReaderEvents {

    /** File name, inside the container's state directory. */
    public static final String FILE = "events.jsonl";

    private ReaderEvents() {
        throw new UnsupportedOperationException("Utility class");
    }
}
