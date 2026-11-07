package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The checkouts a person keeps of the organization's repository and of projects' repositories, as
 * this machine's settings name them once: signing a repository's file reads them where they stand.
 *
 * @param organization The organization's checkout, or {@code null} when none is named.
 * @param projects Each project's checkout, by the project's name.
 */
public record OrgCheckouts(@Nullable Path organization, Map<String, Path> projects) {

    private static final String FILE = "checkouts.yml";

    /**
     * Constructor.
     *
     * @param organization The organization's checkout.
     * @param projects The projects' checkouts.
     */
    public OrgCheckouts {
        projects = Map.copyOf(projects);
    }

    /**
     * Reads this machine's settings.
     *
     * @param context The machine.
     * @return What they name; nothing when there are none.
     * @throws IOException When the file cannot be read.
     * @throws Refused {@code usage} when it does not read.
     */
    public static OrgCheckouts of(final PoreloContext context) throws IOException {
        final Path file = file(context);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return new OrgCheckouts(null, Map.of());
        }
        final Object loaded;
        try {
            loaded =
                    new Yaml(new SafeConstructor(new LoaderOptions()))
                            .load(Files.readString(file, StandardCharsets.UTF_8));
        } catch (RuntimeException ex) {
            throw unreadable();
        }
        if (loaded == null) {
            return new OrgCheckouts(null, Map.of());
        }
        if (!(loaded instanceof Map<?, ?> settings)) {
            throw unreadable();
        }
        final Object organization = settings.get("organization");
        if (organization != null && !(organization instanceof String)) {
            throw unreadable();
        }
        final Map<String, Path> projects = new TreeMap<>();
        final Object named = settings.get("projects");
        if (named != null) {
            if (!(named instanceof Map<?, ?> rows)) {
                throw unreadable();
            }
            for (final Map.Entry<?, ?> row : rows.entrySet()) {
                if (!(row.getKey() instanceof String name)
                        || !name.matches(Project.NAME_PATTERN)
                        || !(row.getValue() instanceof String path)) {
                    throw unreadable();
                }
                projects.put(name, path(path));
            }
        }
        return new OrgCheckouts(
                organization == null ? null : path((String) organization), projects);
    }

    private static Path path(final String named) {
        try {
            return Path.of(named);
        } catch (java.nio.file.InvalidPathException ex) {
            throw unreadable();
        }
    }

    /**
     * Returns these settings with the organization's checkout named anew.
     *
     * @param checkout The checkout.
     * @return The settings.
     */
    public OrgCheckouts withOrganization(final Path checkout) {
        return new OrgCheckouts(checkout, projects);
    }

    /**
     * Returns these settings with a project's checkout named anew.
     *
     * @param project The project's name.
     * @param checkout The checkout.
     * @return The settings.
     */
    public OrgCheckouts withProject(final String project, final Path checkout) {
        final Map<String, Path> all = new TreeMap<>(projects);
        all.put(project, checkout);
        return new OrgCheckouts(organization, all);
    }

    /**
     * Writes them, whole, so that no reader sees half of them.
     *
     * @param context The machine.
     * @throws IOException When they cannot be written.
     */
    public void write(final PoreloContext context) throws IOException {
        final Map<String, Object> document = new LinkedHashMap<>();
        if (organization != null) {
            document.put("organization", organization.toString());
        }
        final Map<String, String> rows = new TreeMap<>();
        projects.forEach((name, path) -> rows.put(name, path.toString()));
        if (!rows.isEmpty()) {
            document.put("projects", rows);
        }
        final DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        final Path file = file(context);
        Files.createDirectories(file.getParent());
        final Path temporary = Files.createTempFile(file.getParent(), "checkouts", ".yml");
        try {
            Files.writeString(temporary, new Yaml(options).dump(document), StandardCharsets.UTF_8);
            Files.move(
                    temporary,
                    file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path file(final PoreloContext context) {
        return context.paths().xdg().config().resolve(FILE);
    }

    private static Refused unreadable() {
        return new Refused(
                Refused.USAGE,
                FILE
                        + " does not read: it names an organization's checkout and projects' checkouts",
                null);
    }
}
