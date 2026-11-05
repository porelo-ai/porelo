package org.fuin.porelo.app;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.runtime.ImageSettings;

/** Answers what a task's image was built with, from the image its container was made from. */
public final class TaskImageSettings {

    private TaskImageSettings() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Reads what a task's image recorded.
     *
     * <p>By the id its container holds, since the image's name may name a newer build by now.
     *
     * @param context The machine.
     * @param task The task's container name.
     * @return The answer, as the contract names its fields.
     */
    public static Map<String, Object> read(final PoreloContext context, final String task) {
        final Optional<String> image = context.podman().imageOf(task).map(found -> found[1]);
        if (image.isEmpty()) {
            return unknown();
        }
        final Optional<ImageSettings> recorded = context.podman().imageSettings(image.get());
        return recorded.isEmpty()
                ? unknown()
                : known(recorded.get(), context.podman().imageBuilt(image.get()).orElse(""));
    }

    private static Map<String, Object> known(final ImageSettings settings, final String built) {
        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("known", true);
        answer.put("baseImage", setting(settings.baseImage(), settings.baseImageSource()));
        answer.put("packages", settings(settings.packages(), settings.linesSource()));
        answer.put("tools", settings(settings.tools(), ImageRecording.PORELO));
        answer.put("lines", settings(settings.lines(), settings.linesSource()));
        answer.put(
                "agent",
                setting(
                        settings.agent(),
                        settings.agent().isEmpty() ? "" : ImageRecording.AGENT_PACKAGE));
        answer.put("agentVersion", settings.agentVersion());
        answer.put(
                "securityClass", setting(settings.securityClass(), settings.securityClassSource()));
        answer.put("recorded", built);
        return answer;
    }

    private static Map<String, Object> unknown() {
        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("known", false);
        answer.put("baseImage", setting("", ""));
        answer.put("packages", List.of());
        answer.put("tools", List.of());
        answer.put("lines", List.of());
        answer.put("agent", setting("", ""));
        answer.put("agentVersion", "");
        answer.put("securityClass", setting("", ""));
        answer.put("recorded", "");
        return answer;
    }

    private static List<Map<String, Object>> settings(
            final List<String> values, final String source) {
        return values.stream().map(value -> setting(value, source)).toList();
    }

    private static Map<String, Object> setting(final String value, final String source) {
        return Map.of("value", value, "source", source);
    }
}
