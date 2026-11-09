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

    /** A repository's file's sections, where it may set its image alone. */
    static final Sections REPOSITORY = new Sections(true);

    /**
     * A repository's file's sections where it may add settings to the project: the project's, less
     * the list of repositories. Only the image is bound from it; the rest is judged merged.
     */
    static final Sections REPOSITORY_SETTINGS = new Sections(PROJECT);

    /** The section that allows a task an MCP endpoint: the project file's alone. */
    private static final String MCP = "mcp";

    /** Keys of the image a file written before this model used, each with the one it became. */
    private static final Map<String, String> RENAMED =
            Map.of("base_image", "from", "snippet", "run", "snippet_file", "run_file");

    private final Map<String, List<String>> keys = new LinkedHashMap<>();
    private final boolean repositoryFile;

    private Sections(final Sections project) {
        this.repositoryFile = false;
        keys.putAll(project.keys);
        keys.put(
                TOP,
                project.keys(TOP).stream()
                        .filter(key -> !"repositories".equals(key) && !MCP.equals(key))
                        .toList());
        keys.remove("repositories.<name>");
        keys.remove(MCP);
    }

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
                        "branch",
                        "base_branch",
                        MCP,
                        "providers"));
        keys.put(
                "project",
                List.of(
                        "name",
                        "description",
                        "security_class",
                        "central",
                        "upstream",
                        "review",
                        "nickname"));
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
        keys.put(MCP, List.of("methods", "agents", "tasks", "minutes"));
        keys.put("providers.<name>", List.of("model", "label"));
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
     * Returns every section with its keys.
     *
     * @return Section to its keys, both in the order they are written.
     */
    Map<String, List<String>> all() {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(keys));
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
        if (TOP.equals(section) && MCP.equals(key)) {
            return "'"
                    + where
                    + "' cannot be set here; what a task may do through its MCP endpoint is the"
                    + " project file's alone";
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
            // A key within two letters of one the section holds is a slip of the hand far more
            // often than a key of a later Porelo, so it is named.
            final String near = nearest(key, keys.getOrDefault(section, List.of()));
            return near == null
                    ? "'"
                            + where
                            + "' is not a setting this Porelo knows; it may be older than the file"
                    : "'" + where + "' is not a setting; did you mean '" + near + "'?";
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

    private static @Nullable String nearest(final String key, final List<String> known) {
        String best = null;
        int distance = 3;
        for (final String candidate : known) {
            final int each = distance(key, candidate);
            if (each < distance) {
                distance = each;
                best = candidate;
            }
        }
        return best;
    }

    private static int distance(final String a, final String b) {
        final int[] previous = new int[b.length() + 1];
        final int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                current[j] =
                        Math.min(
                                Math.min(current[j - 1], previous[j]) + 1,
                                previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
            System.arraycopy(current, 0, previous, 0, current.length);
        }
        return previous[b.length()];
    }
}
