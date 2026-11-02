package org.fuin.porelo.ecosystem.api;

import java.io.Serial;
import org.jspecify.annotations.Nullable;

/**
 * The plugin answered that it cannot say anything of what it was asked, for a reason the contract
 * names.
 *
 * <p>Distinct from an empty answer: a directory of another tool and a file nobody could read are
 * not "nothing found".
 */
public final class EcosystemRefused extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    /** Why the plugin would not answer. */
    public enum Reason {

        /** The directory holds nothing of this plugin's tool. */
        NOT_RECOGNIZED("NotRecognized"),

        /** A file it needs cannot be read. */
        UNREADABLE("Unreadable");

        private final String wire;

        Reason(final String wire) {
            this.wire = wire;
        }

        /**
         * Returns the error's name on the wire, without the interface.
         *
         * @return It.
         */
        public String wire() {
            return wire;
        }

        /**
         * Returns the reason a wire name stands for.
         *
         * @param wire A fully qualified error name.
         * @return It, or {@code null} when the name is none of these.
         */
        static @Nullable Reason of(final @Nullable String wire) {
            for (final Reason reason : values()) {
                if (EcosystemProtocol.error(reason).equals(wire)) {
                    return reason;
                }
            }
            return null;
        }
    }

    private final Reason reason;

    /**
     * Constructor.
     *
     * @param reason Why.
     * @param detail Which file and why, for {@link Reason#UNREADABLE}, never what it holds.
     */
    public EcosystemRefused(final Reason reason, final String detail) {
        super(detail);
        this.reason = reason;
    }

    /**
     * Returns why.
     *
     * @return The reason.
     */
    public Reason reason() {
        return reason;
    }

    /**
     * Returns what the plugin said.
     *
     * @return The detail; "" for {@link Reason#NOT_RECOGNIZED}, which carries none.
     */
    public String detail() {
        return String.valueOf(getMessage());
    }
}
