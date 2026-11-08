package org.fuin.porelo.app;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.gate.GitGate;

/**
 * Which delivery a pushed file came in through. A file counts as delivered when its content is a
 * delivered file's, at any path; every other file is the agent's own and is not named.
 */
public final class DeliveredOrigins {

    /**
     * Where one pushed file came from.
     *
     * @param kind {@code HAND_IN}.
     * @param name The delivered file's name.
     * @param by Who delivered it.
     * @param at When, UTC.
     * @param sha256 Its content's hash.
     */
    public record Origin(String kind, String name, String by, String at, String sha256) {

        /**
         * Returns it as the daemon sends it.
         *
         * @return Its fields.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("kind", kind);
            map.put("name", name);
            map.put("by", by);
            map.put("at", at);
            map.put("sha256", sha256);
            return map;
        }
    }

    private DeliveredOrigins() {}

    /**
     * Names the delivery each pushed file came in through.
     *
     * @param delivered What was delivered into the task, oldest first.
     * @param blobs The pushed files' blob ids.
     * @return The origin by path, only for files with a delivered content.
     */
    public static Map<String, Origin> of(
            final List<HandIns.Delivered> delivered, final GitGate.Blobs blobs) {
        final Map<String, Origin> byBlob = new LinkedHashMap<>();
        // Later deliveries replace earlier ones: the latest is what the agent saw last.
        for (final HandIns.Delivered each : delivered) {
            final String id = each.blobs().get(blobs.format());
            if (id != null) {
                final HandIns.HandedFile file = each.file();
                byBlob.put(
                        id,
                        new Origin("HAND_IN", file.name(), file.by(), file.at(), file.sha256()));
            }
        }
        final Map<String, Origin> byPath = new LinkedHashMap<>();
        blobs.byPath()
                .forEach(
                        (path, id) -> {
                            final Origin origin = byBlob.get(id);
                            if (origin != null) {
                                byPath.put(path, origin);
                            }
                        });
        return byPath;
    }
}
