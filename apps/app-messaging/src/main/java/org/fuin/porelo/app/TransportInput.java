package org.fuin.porelo.app;

import java.util.LinkedHashMap;
import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * How a transport is handed what Porelo kept for it: on its standard input, as one object, when it
 * says in {@code describe} that it takes its secrets so; otherwise as before, the settings alone on
 * its input and the secrets in its environment, where another process of the account can read them.
 */
final class TransportInput {

    /** What a transport that takes its secrets on its input says in {@code describe}. */
    static final String ON_INPUT = "input";

    /**
     * How {@link TransportDescription} records it among what the transport takes: a name no option
     * can have, so an option a transport lists is never read as this claim.
     */
    static final String INPUT = "secrets:input";

    private TransportInput() {}

    /**
     * Hands a transport its settings and secrets the way it takes them.
     *
     * @param command The verb, with its arguments.
     * @param described What the transport said about itself.
     * @param settings What the project says to the transport, for a lifecycle verb; {@code null}
     *     for a verb that takes none.
     * @param secrets What Porelo kept for it, the whole map; empty when it kept none.
     * @return The command to run.
     */
    static Command handed(
            Command command,
            TransportDescription described,
            @Nullable Map<String, Object> settings,
            Map<String, String> secrets) {
        if (TransportDescription.UNKNOWN.equals(described)) {
            // It could not be asked, so how it takes them is unknown; in its environment another
            // process of the account reads them. Its verb runs without them and fails instead.
            return settings == null ? command : command.withInput(Json.write(settings));
        }
        if (described.takes(INPUT)) {
            final Map<String, Object> handed = new LinkedHashMap<>();
            if (settings != null) {
                handed.put("settings", settings);
            }
            if (!secrets.isEmpty()) {
                handed.put("secrets", secrets);
            }
            return handed.isEmpty() ? command : command.withInput(Json.write(handed));
        }
        final Command withSettings =
                settings == null ? command : command.withInput(Json.write(settings));
        return secrets.isEmpty() ? withSettings : withSettings.withEnvironment(secrets);
    }
}
