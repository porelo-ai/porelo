package org.fuin.porelo.app;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.core.project.ProjectException;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** The files a machine consumes from a project's committed configuration. */
final class ProtectedFiles {

    private ProtectedFiles() {}

    static Set<String> at(final CommandRunner runner, final Path clone, final String commit) {
        final var shown =
                runner.run(
                        Command.of("git", "-C", clone.toString(), "show", commit + ":project.yml"));
        if (!shown.successful()) {
            throw new ProjectException("cannot read project.yml at " + commit);
        }
        final LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        final Object loaded = new Yaml(new SafeConstructor(options)).load(shown.standardOutput());
        if (!(loaded instanceof Map<?, ?> root)) {
            throw new ProjectException("project.yml at " + commit + " is not a mapping");
        }
        final Set<String> paths = new LinkedHashSet<>();
        paths.add("project.yml");
        paths.add("machine-signers");
        paths.add("porelo.yml");
        final var image =
                runner.run(
                        Command.of(
                                "git",
                                "-C",
                                clone.toString(),
                                "ls-tree",
                                commit,
                                "--",
                                ":(literal)porelo.yml"));
        if (!image.successful()) {
            throw new ProjectException("cannot inspect porelo.yml at " + commit);
        }
        if (!image.standardOutput().isEmpty()) {
            final var settings =
                    runner.run(
                            Command.of(
                                    "git", "-C", clone.toString(), "show", commit + ":porelo.yml"));
            if (!settings.successful()) {
                throw new ProjectException("cannot read porelo.yml at " + commit);
            }
            final Object repositoryImage =
                    new Yaml(new SafeConstructor(options)).load(settings.standardOutput());
            if (!(repositoryImage instanceof Map<?, ?> mapping)) {
                throw new ProjectException("porelo.yml at " + commit + " is not a mapping");
            }
            snippet(paths, mapping.get("image"));
        }
        if (root.get("access") instanceof Map<?, ?> access && access.containsKey("file")) {
            paths.add("access.yml");
        }
        snippet(paths, root.get("image"));
        if (root.get("repositories") instanceof Map<?, ?> repositories) {
            for (final Object value : repositories.values()) {
                if (value instanceof Map<?, ?> repository) {
                    snippet(paths, repository.get("image"));
                }
            }
        }
        // Attributes in any ancestor directory can transform a protected file at checkout.
        for (final String path : List.copyOf(paths)) {
            paths.add(".gitattributes");
            Path parent = Path.of(path).getParent();
            while (parent != null) {
                paths.add(parent.resolve(".gitattributes").toString());
                parent = parent.getParent();
            }
        }
        return paths;
    }

    private static void snippet(final Set<String> paths, final @Nullable Object image) {
        if (image instanceof Map<?, ?> section && section.get("snippet_file") != null) {
            final Path path = Path.of(String.valueOf(section.get("snippet_file"))).normalize();
            if (path.isAbsolute() || path.startsWith("..") || path.toString().isEmpty()) {
                throw new ProjectException("image.snippet_file must name a file of the repository");
            }
            paths.add(path.toString());
        }
    }
}
