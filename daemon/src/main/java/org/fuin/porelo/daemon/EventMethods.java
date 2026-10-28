package org.fuin.porelo.daemon;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.app.EventLog;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;

/** The account's event log over the socket: what {@code porelo events} shows and checks. */
final class EventMethods {

    private EventMethods() {}

    /**
     * Registers the methods.
     *
     * @param server The server.
     * @param context The machine.
     * @param inventory The daemon's one task inventory, which the log does not need.
     * @param control The daemon's one task control, which the log does not need.
     */
    @SuppressWarnings("unused")
    static void register(
            final VarlinkServer server,
            final PoreloContext context,
            final org.fuin.porelo.app.TaskInventory inventory,
            final org.fuin.porelo.app.TaskControl control) {
        server.method(
                "Events",
                (parameters, replies) -> {
                    final String since = PoreloDaemon.text(parameters, "since");
                    final Instant from;
                    try {
                        from = since.isEmpty() ? null : Instant.parse(since);
                    } catch (DateTimeParseException ex) {
                        throw new VarlinkException(
                                PoreloDaemon.INTERFACE + ".Failed",
                                Map.of(
                                        "message",
                                        "since takes an instant such as 2026-10-28T03:00:00Z"));
                    }
                    final List<Map<String, Object>> events;
                    try {
                        events = EventLog.of(context).entries(from);
                    } catch (IOException ex) {
                        throw failed(ex);
                    }
                    replies.last(Map.of("events", events));
                });
        server.method(
                "VerifyEvents",
                (parameters, replies) -> {
                    final EventLog.Verification found;
                    try {
                        found = EventLog.of(context).verify();
                    } catch (IOException ex) {
                        throw failed(ex);
                    }
                    replies.last(
                            Map.of(
                                    "holds",
                                    found.holds(),
                                    "lines",
                                    found.lines(),
                                    "firstBroken",
                                    found.firstBroken(),
                                    "notAnchored",
                                    found.notAnchored(),
                                    "missing",
                                    found.missing(),
                                    "anchorRead",
                                    found.anchorRead(),
                                    "beforeAnchor",
                                    found.beforeAnchor()));
                });
    }

    private static VarlinkException failed(final IOException ex) {
        return new VarlinkException(
                PoreloDaemon.INTERFACE + ".Failed",
                Map.of("message", String.valueOf(ex.getMessage())));
    }
}
