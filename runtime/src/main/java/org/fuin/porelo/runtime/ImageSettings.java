package org.fuin.porelo.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * What an image was built with and where each thing came from, recorded on the image as a label.
 *
 * <p>Only what the image's own layers settled: a value that could differ between two starts of the
 * same image, a time or how the agent was chosen, would give it a new id at each and report every
 * other task of the project as running an older image.
 *
 * @param baseImage The image it was built on.
 * @param baseImageSource Where that was chosen.
 * @param packages What the install commands among {@code lines} name.
 * @param lines The project's own build lines, as written.
 * @param linesSource Where those lines, and so those packages, came from.
 * @param tools What Porelo asks for in every image.
 * @param agent The agent whose tooling it carries, or "" for none.
 * @param agentVersion That agent's version, or "" when its definition names none.
 * @param securityClass The security class of the project it was built for.
 * @param securityClassSource Where that class was set.
 */
public record ImageSettings(
        String baseImage,
        String baseImageSource,
        List<String> packages,
        List<String> lines,
        String linesSource,
        List<String> tools,
        String agent,
        String agentVersion,
        String securityClass,
        String securityClassSource) {

    /** The label it is recorded in. */
    public static final String LABEL = "org.fuin.porelo.settings";

    /** A later release that changes the shape changes this, and reads as "does not know" here. */
    private static final int VERSION = 1;

    private static final Pattern INSTALL =
            Pattern.compile(
                    "(?:^|[\\s;&|(])(?:(?:apt-get|apt|dnf|yum|microdnf)(?:\\s+-\\S+)*\\s+install"
                            + "|apk(?:\\s+-\\S+)*\\s+add)(?=\\s)");

    /**
     * Constructor with all data.
     *
     * @param baseImage The image it was built on.
     * @param baseImageSource Where that was chosen.
     * @param packages What the install commands name.
     * @param lines The project's own build lines.
     * @param linesSource Where the lines came from.
     * @param tools What Porelo asks for in every image.
     * @param agent The agent, or "".
     * @param agentVersion Its version, or "".
     * @param securityClass The security class.
     * @param securityClassSource Where that class was set.
     */
    public ImageSettings {
        packages = List.copyOf(packages);
        lines = List.copyOf(lines);
        tools = List.copyOf(tools);
    }

    /**
     * Reads what a project's build would record.
     *
     * @param project The project as it is built.
     * @param baseImageSource Where its base image was chosen.
     * @param linesSource Where its build lines came from.
     * @param securityClassSource Where its class was set.
     * @param agent The agent, or "".
     * @param agentVersion Its version, or "".
     * @return What to record.
     */
    public static ImageSettings of(
            Project project,
            String baseImageSource,
            String linesSource,
            String securityClassSource,
            String agent,
            String agentVersion) {
        final List<String> lines = project.imageSnippetLines();
        return new ImageSettings(
                project.baseImage(),
                baseImageSource,
                packagesIn(lines),
                lines,
                linesSource,
                Containerfile.TOOLS,
                agent,
                agentVersion,
                project.securityClass().name().toLowerCase(java.util.Locale.ROOT),
                securityClassSource);
    }

    /** What install commands name, read and never run: anything else they fetch stays a line. */
    private static List<String> packagesIn(final List<String> lines) {
        final Set<String> found = new LinkedHashSet<>();
        final String joined = String.join("\n", lines).replaceAll("\\\\\\n", " ");
        for (final String line : joined.split("\n")) {
            if (line.stripLeading().startsWith("#")) {
                continue;
            }
            final Matcher install = INSTALL.matcher(line);
            while (install.find()) {
                found.addAll(namedAfter(line.substring(install.end())));
            }
        }
        return List.copyOf(found);
    }

    private static List<String> namedAfter(final String rest) {
        final List<String> named = new ArrayList<>();
        final String command = rest.split("&&|\\|\\||[;|#)]", 2)[0];
        for (final String word : command.strip().split("\\s+")) {
            final String bare = word.replaceAll("['\"]", "");
            if (!bare.isEmpty() && !bare.startsWith("-") && !bare.matches(".*[$`<>(].*")) {
                named.add(bare);
            }
        }
        return named;
    }

    /**
     * Returns the label's value.
     *
     * @return One line of JSON.
     */
    public String json() {
        final Map<String, Object> label = new LinkedHashMap<>();
        label.put("version", VERSION);
        label.put("baseImage", baseImage);
        label.put("baseImageSource", baseImageSource);
        label.put("packages", packages);
        label.put("lines", lines);
        label.put("linesSource", linesSource);
        label.put("tools", tools);
        label.put("agent", agent);
        label.put("agentVersion", agentVersion);
        label.put("securityClass", securityClass);
        label.put("securityClassSource", securityClassSource);
        return Json.write(label);
    }

    /**
     * Reads a label's value.
     *
     * @param json What the image holds.
     * @return The settings, or empty for anything this version did not write.
     */
    public static Optional<ImageSettings> parse(String json) {
        final Object parsed;
        try {
            parsed = Json.parse(json);
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
        if (!(parsed instanceof Map<?, ?> label)
                || !(label.get("version") instanceof Number version)
                || version.intValue() != VERSION) {
            return Optional.empty();
        }
        final String baseImage = text(label, "baseImage");
        final String baseImageSource = text(label, "baseImageSource");
        final List<String> packages = texts(label, "packages");
        final List<String> lines = texts(label, "lines");
        final String linesSource = text(label, "linesSource");
        final List<String> tools = texts(label, "tools");
        final String agent = text(label, "agent");
        final String agentVersion = text(label, "agentVersion");
        final String securityClass = text(label, "securityClass");
        final String securityClassSource = text(label, "securityClassSource");
        if (baseImage == null
                || baseImageSource == null
                || packages == null
                || lines == null
                || linesSource == null
                || tools == null
                || agent == null
                || agentVersion == null
                || securityClass == null
                || securityClassSource == null) {
            return Optional.empty();
        }
        return Optional.of(
                new ImageSettings(
                        baseImage,
                        baseImageSource,
                        packages,
                        lines,
                        linesSource,
                        tools,
                        agent,
                        agentVersion,
                        securityClass,
                        securityClassSource));
    }

    private static @Nullable String text(final Map<?, ?> label, final String name) {
        return label.get(name) instanceof String value ? value : null;
    }

    private static @Nullable List<String> texts(final Map<?, ?> label, final String name) {
        if (!(label.get(name) instanceof List<?> values)) {
            return null;
        }
        final List<String> read = new ArrayList<>();
        for (final Object value : values) {
            if (!(value instanceof String each)) {
                return null;
            }
            read.add(each);
        }
        return read;
    }
}
