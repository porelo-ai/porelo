package org.fuin.porelo.agent.api;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One thing an agent finds of itself on this machine.
 *
 * <p>Presence only, never a credential's value: every field is a closed set apart from the path and
 * the provider, and there is deliberately no free text, which would give a value a place to travel.
 *
 * @param what What was found.
 * @param path Absolute path it was found at, or {@code null}.
 * @param signedIn For a sign-in, its state, or {@code null}.
 * @param importable For a sign-in, whether {@code vault import} can take this route, or {@code
 *     null}.
 * @param provider For a sign-in, a provider of this agent's definition where known, or {@code
 *     null}.
 * @param kind For a sign-in, its kind where known, or {@code null}.
 */
public record Finding(
        What what,
        @Nullable String path,
        @Nullable SignedIn signedIn,
        @Nullable Boolean importable,
        @Nullable String provider,
        @Nullable Kind kind) {

    /** The longest path Porelo keeps, in bytes. */
    static final int LONGEST_PATH = 4096;

    /** What a finding says. */
    public enum What {

        /** The tool's configuration is there. */
        CONFIGURATION("configuration"),

        /** One way of being signed in, or its absence. */
        SIGN_IN("sign-in");

        private final String wire;

        What(final String wire) {
            this.wire = wire;
        }

        /**
         * Returns the name this has on the wire.
         *
         * @return The name.
         */
        public String wire() {
            return wire;
        }
    }

    /** The state of a sign-in. */
    public enum SignedIn {

        /** Signed in. */
        SIGNED_IN,

        /** Not signed in, or a file that cannot be used. */
        NOT_SIGNED_IN,

        /** The agent cannot tell, which is never the same as not signed in. */
        CANNOT_SAY
    }

    /** The kind of a sign-in. */
    public enum Kind {

        /** A subscription. */
        SUBSCRIPTION,

        /** An API key. */
        API_KEY
    }

    /**
     * Returns the finding of an agent that cannot tell.
     *
     * @return A sign-in with {@link SignedIn#CANNOT_SAY}.
     */
    public static Finding cannotSay() {
        return new Finding(What.SIGN_IN, null, SignedIn.CANNOT_SAY, null, null, null);
    }

    /**
     * Returns this as plain values, with only the fields it has.
     *
     * @return What goes on the wire.
     */
    Map<String, Object> asMap() {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("what", what.wire());
        if (path != null) {
            map.put("path", path);
        }
        if (signedIn != null) {
            map.put("signedIn", signedIn.name());
        }
        if (importable != null) {
            map.put("importable", importable);
        }
        if (provider != null) {
            map.put("provider", provider);
        }
        if (kind != null) {
            map.put("kind", kind.name());
        }
        return map;
    }

    /**
     * Reads one finding from an agent's answer, keeping only the fields known here.
     *
     * <p>What comes from an agent is not trusted to be what the contract says. An unknown field is
     * dropped unread, a path that is not one refuses the finding, an unknown {@code signedIn}
     * counts as cannot say, and an unknown {@code what} (a {@code command} included, which is
     * Porelo's own finding to make) is kept out of any decision.
     *
     * @param raw One entry of the answer.
     * @return The finding, or {@code null} when it is refused.
     */
    static @Nullable Finding read(final Object raw) {
        if (!(raw instanceof Map<?, ?> fields)) {
            return null;
        }
        final What what = what(fields.get("what"));
        final Object path = fields.get("path");
        if (what == null || (path != null && !usablePath(path))) {
            return null;
        }
        return new Finding(
                what,
                path instanceof String text ? text : null,
                what == What.SIGN_IN ? signedIn(fields.get("signedIn")) : null,
                fields.get("importable") instanceof Boolean flag ? flag : null,
                fields.get("provider") instanceof String named && !named.isBlank() ? named : null,
                kind(fields.get("kind")));
    }

    private static @Nullable What what(final @Nullable Object raw) {
        for (final What what : What.values()) {
            if (what.wire().equals(raw)) {
                return what;
            }
        }
        return null;
    }

    private static SignedIn signedIn(final @Nullable Object raw) {
        for (final SignedIn state : SignedIn.values()) {
            if (state.name().equals(raw)) {
                return state;
            }
        }
        return SignedIn.CANNOT_SAY;
    }

    private static @Nullable Kind kind(final @Nullable Object raw) {
        for (final Kind kind : Kind.values()) {
            if (kind.name().equals(raw)) {
                return kind;
            }
        }
        return null;
    }

    private static boolean usablePath(final Object raw) {
        return raw instanceof String path
                && path.startsWith("/")
                && path.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= LONGEST_PATH
                && path.chars().noneMatch(Character::isISOControl)
                && !java.util.Arrays.asList(path.split("/")).contains("..");
    }
}
