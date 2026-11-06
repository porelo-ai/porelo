package org.fuin.porelo.core.project.file;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Finds the keys of a project file still under an old name. The reader takes them alike, so a file
 * not yet renamed keeps working; what is found here is only said, so that a person renames it.
 */
public final class OldNames {

    private OldNames() {}

    /**
     * Returns where a project file says {@code upstream} for what is now {@code central}.
     *
     * @param text The file's text.
     * @return The keys' paths, such as {@code repositories.web.upstream}; none when the text does
     *     not read.
     */
    public static List<String> of(final String text) {
        final Object document;
        try {
            document = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        } catch (RuntimeException ex) {
            // The reader says what is wrong with a file that does not read; nothing to rename here.
            return List.of();
        }
        if (!(document instanceof Map<?, ?> top)) {
            return List.of();
        }
        final List<String> found = new ArrayList<>();
        if (top.get("project") instanceof Map<?, ?> project && project.containsKey("upstream")) {
            found.add("project.upstream");
        }
        if (top.get("repositories") instanceof Map<?, ?> repositories) {
            for (final Map.Entry<?, ?> each : repositories.entrySet()) {
                if (each.getValue() instanceof Map<?, ?> row && row.containsKey("upstream")) {
                    found.add("repositories." + each.getKey() + ".upstream");
                }
            }
        }
        return List.copyOf(found);
    }
}
