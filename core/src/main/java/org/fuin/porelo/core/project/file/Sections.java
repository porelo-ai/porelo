package org.fuin.porelo.core.project.file;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Which keys each section of a file holds, in the order they are written, and what a key found
 * where it does not belong is told.
 */
final class Sections {

    /** The top level of a file. */
    static final String TOP = "";

    /** The keys {@code image} holds, in either file. */
    private static final List<String> IMAGE = List.of("from", "env", "run", "run_file");

    /** The project file's sections. */
    static final Sections PROJECT = new Sections(false);

    /** A repository's file's sections. */
    static final Sections REPOSITORY = new Sections(true);

    /** Keys of the image a file written before this model used, each with the one it became. */
    private static final Map<String, String> RENAMED =
            Map.of("base_image", "from", "snippet", "run", "snippet_file", "run_file");

    private final Map<String, List<String>> keys = new LinkedHashMap<>();
    private final boolean repositoryFile;

    private Sections(final boolean repositoryFile) {
        this.repositoryFile = repositoryFile;
        if (repositoryFile) {
            keys.put(TOP, List.of("image"));
            keys.put("image", IMAGE);
            return;
        }
        keys.put(
                TOP,
                List.of(
                        "project",
                        "repositories",
                        "image",
                        "build",
                        "egress",
                        "limits",
                        "mail",
                        "credentials",
                        "builds",
                        "parameters",
                        "branch"));
        keys.put(
                "project",
                List.of("name", "description", "security_class", "upstream", "review", "nickname"));
        keys.put(
                "repositories.<name>",
                List.of(
                        "upstream",
                        "central",
                        "review",
                        "description",
                        "issues",
                        "agent",
                        "nickname",
                        "maturity",
                        "branch"));
        keys.put("image", IMAGE);
        keys.put("build", List.of("package_sources"));
        keys.put("egress", List.of("sets", "domains", "host_services", "refused"));
        keys.put("limits", List.of("memory", "pids", "cpus", "hand_in", "subagents"));
        keys.put("mail", List.of("peers", "rules", "outgoing_filter", "transports"));
        keys.put("mail.peers.<name>", List.of("address", "trust", "per_day", "mode"));
        keys.put("mail.rules", List.of("project", "room", "others"));
        keys.put("builds", List.of("forge", "credential", "api", "logs"));
        keys.put(
                "parameters.<name>",
                List.of("description", "values", "pattern", "default", "required", "env"));
        keys.put("branch", List.of("template", "pattern"));
    }

    /**
     * Returns the keys a section holds.
     *
     * @param section The section, {@code <name>} standing for a name the file chooses.
     * @return Its keys, in the order they are written.
     */
    List<String> keys(final String section) {
        return Objects.requireNonNull(keys.get(section), section);
    }

    /**
     * Says what is wrong with a key a section does not hold.
     *
     * @param section The section it was found in.
     * @param key The key.
     * @param where Its full path.
     * @return The refusal.
     */
    String refusal(final String section, final String key, final String where) {
        if ("image".equals(section) && RENAMED.containsKey(key)) {
            return "'" + where + "' is not a setting; write '" + RENAMED.get(key) + "' instead";
        }
        if (repositoryFile) {
            return TOP.equals(section)
                    ? "'" + where + "' cannot be set here; only image may be set here"
                    : "'"
                            + where
                            + "' cannot be set here; only from, env, run and run_file may be set"
                            + " under image";
        }
        if ("repositories.<name>".equals(section) && "image".equals(key)) {
            return "'"
                    + where
                    + "' is not a setting; a repository's image is in its own porelo.yml, and the"
                    + " project's is 'image' at the top level";
        }
        if ("repositories.<name>".equals(section)
                && ("egress".equals(key) || "limits".equals(key))) {
            return "'"
                    + where
                    + "' is not a setting; egress and limits are the project's alone, at the top"
                    + " level, and a repository that needs other rules is another project";
        }
        final String home = home(section, key);
        if (home == null) {
            return "'"
                    + where
                    + "' is not a setting this Porelo knows; it may be older than the file";
        }
        return "'"
                + where
                + "' is not a setting"
                + (TOP.equals(home)
                        ? "; it belongs at the top level"
                        : "; it belongs under '" + home + ":'");
    }

    private @Nullable String home(final String not, final String key) {
        for (final Map.Entry<String, List<String>> section : keys.entrySet()) {
            if (!section.getKey().equals(not) && section.getValue().contains(key)) {
                return section.getKey();
            }
        }
        return null;
    }
}
