package org.fuin.porelo.app;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A vault entry missing for something beside the agent, and the action this machine offers.
 *
 * @param purpose What the entry is for; clients show unknown values as they come.
 * @param credential The vault key as configured.
 * @param forge The forge whose builds the entry reads, by the name {@code builds.forge} gives it;
 *     "" in a record kept before it was named.
 * @param storeCommand What to run on this machine, reading nothing on standard input.
 * @param afterStore Whether to ask again or restart; clients show unknown values as they come.
 */
public record MissingCredential(
        String purpose,
        String credential,
        String forge,
        List<String> storeCommand,
        String afterStore) {

    /** Keeps the command immutable. */
    public MissingCredential {
        storeCommand = List.copyOf(storeCommand);
    }

    /** Returns the host's remedy for a missing builds credential. */
    public static MissingCredential hostBuilds(
            final String credential, final String forge, final String afterStore) {
        return new MissingCredential(
                "builds", credential, forge, List.of("porelo", "host", "forge-token"), afterStore);
    }

    /** Returns the fields agreed on the wire, including its reserved word. */
    public Map<String, Object> asMap() {
        return Map.of(
                "for", purpose,
                "credential", credential,
                "forge", forge,
                "storeCommand", storeCommand,
                "afterStore", afterStore);
    }

    /** Older records carry no reason; a sentence is never evidence of one. */
    static @Nullable MissingCredential read(final @Nullable Object value) {
        if (!(value instanceof Map<?, ?> map)
                || !(map.get("for") instanceof String purpose)
                || !(map.get("credential") instanceof String credential)
                || !(map.get("storeCommand") instanceof List<?> command)
                || !command.stream().allMatch(String.class::isInstance)
                || !(map.get("afterStore") instanceof String afterStore)) {
            return null;
        }
        return new MissingCredential(
                purpose,
                credential,
                map.get("forge") instanceof String forge ? forge : "",
                command.stream().map(String.class::cast).toList(),
                afterStore);
    }
}
