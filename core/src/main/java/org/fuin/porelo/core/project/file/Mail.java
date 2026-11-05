package org.fuin.porelo.core.project.file;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The {@code mail} section: whom the project's tasks may write to, and over which transports.
 *
 * @param peers The names tasks may write to, in the order written ({@code peers}).
 * @param rules How closely a message is watched ({@code rules}).
 * @param outgoingFilter {@code blocking} or {@code reporting} ({@code outgoing_filter}).
 * @param transports Each transport's settings, by its scheme ({@code transports}).
 */
public record Mail(
        Map<String, Peer> peers,
        @Nullable Rules rules,
        @Nullable String outgoingFilter,
        Map<String, Transport> transports) {

    /**
     * Constructor.
     *
     * @param peers The peers by name.
     * @param rules The rules.
     * @param outgoingFilter The outgoing filter's mode.
     * @param transports The transports by scheme.
     */
    public Mail {
        peers = ProjectFile.ordered(peers);
        transports = ProjectFile.ordered(transports);
    }

    /**
     * One entry under {@code mail.peers}.
     *
     * @param address {@code <transport>:<address>} ({@code address}), required.
     * @param trust {@code vouched} or {@code external} ({@code trust}).
     * @param perDay Messages a day with the peer, each way ({@code per_day}).
     * @param mode The mode for a message to it ({@code mode}).
     */
    public record Peer(
            @Nullable String address,
            @Nullable String trust,
            @Nullable Integer perDay,
            @Nullable String mode) {}

    /**
     * The {@code mail.rules} block: a mode for each class of peer.
     *
     * @param project For another task of the project ({@code project}).
     * @param room For the project's conversation ({@code room}).
     * @param others For every other peer ({@code others}).
     */
    public record Rules(@Nullable String project, @Nullable String room, @Nullable String others) {}

    /**
     * One entry under {@code mail.transports}: what the project says to that transport.
     *
     * <p>The transport's own settings, each a name and one value; the transport says what they mean
     * and refuses what it does not know. Porelo itself reads {@link #homeserver()} and {@link
     * #room()}.
     *
     * @param settings Setting to its value, in the order written.
     */
    public record Transport(Map<String, String> settings) {

        /**
         * Constructor.
         *
         * @param settings Setting to its value.
         */
        public Transport {
            settings = ProjectFile.ordered(settings);
        }

        /**
         * Returns the homeserver the transport is told, which Porelo reads too.
         *
         * @return It, or {@code null} when unsaid.
         */
        public @Nullable String homeserver() {
            return settings.get("homeserver");
        }

        /**
         * Returns the room the transport is told, which Porelo reads too.
         *
         * @return It, or {@code null} when unsaid.
         */
        public @Nullable String room() {
            return settings.get("room");
        }
    }
}
