package org.fuin.porelo.app;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandRunner;
import org.jspecify.annotations.Nullable;

/** Checks the host init needed by a task's {@code --init}. */
final class TaskInit {

    private TaskInit() {}

    static Optional<String> missing(PoreloContext context) {
        final List<Path> defaults = new ArrayList<>();
        defaults.add(Path.of("/usr/libexec/podman/catatonit"));
        final String helper = context.environment().apply("CONTAINERS_HELPER_BINARY_DIR");
        if (helper != null) {
            defaults.add(Path.of(helper).toAbsolutePath().resolve("catatonit"));
        }
        for (final String directory :
                List.of(
                        "/usr/local/libexec/podman",
                        "/usr/local/lib/podman",
                        "/usr/libexec/podman",
                        "/usr/lib/podman")) {
            defaults.add(Path.of(directory).resolve("catatonit"));
        }
        final String configuredHome = context.environment().apply("XDG_CONFIG_HOME");
        final Path configHome =
                configuredHome == null || configuredHome.isEmpty()
                        ? context.paths().xdg().home().resolve(".config")
                        : Path.of(configuredHome);
        return missing(
                context.runner(),
                defaults,
                java.util.Objects.requireNonNullElse(context.environment().apply("PATH"), ""),
                Path.of("/"),
                configHome,
                context.environment());
    }

    static Optional<String> missing(CommandRunner runner, List<Path> defaults, String path) {
        return missing(runner, defaults.isEmpty() ? null : defaults.getFirst(), defaults, path);
    }

    private static Optional<String> missing(
            CommandRunner runner, @Nullable Path legacy, List<Path> candidates, String path) {
        final List<String> arguments =
                new ArrayList<>(
                        List.of(
                                "/bin/sh",
                                "-c",
                                """
                legacy=$1
                shift
                if [ -e "$legacy" ]; then
                    [ ! -d "$legacy" ] && [ -x "$legacy" ]
                    exit $?
                fi
                for candidate do
                    if [ ! -d "$candidate" ] && [ -x "$candidate" ]; then
                        case "$candidate" in /*) exit 0 ;; *) exit 1 ;; esac
                    fi
                done
                exit 1
                """,
                                "porelo-init-check"));
        arguments.add(legacy == null ? "" : legacy.toString());
        candidates.stream().map(Path::toString).forEach(arguments::add);
        if (!path.isEmpty()) {
            for (final String directory : path.split(":", -1)) {
                arguments.add(Path.of(directory).resolve("catatonit").toString());
            }
        }
        try {
            if (runner.run(Command.of(arguments)).exitCode() == 0) {
                return Optional.empty();
            }
        } catch (CommandException ex) {
            return Optional.empty();
        }
        return Optional.of(
                "catatonit is missing or is not executable; install the catatonit package required by podman --init");
    }

    static Optional<String> missing(
            CommandRunner runner,
            List<Path> defaults,
            String path,
            Path root,
            Path configHome,
            java.util.function.Function<String, String> environment) {
        final Optional<InitConfiguration> configuration =
                InitConfiguration.read(root, configHome, environment);
        if (configuration.isEmpty()) {
            return Optional.empty();
        }
        final InitConfiguration settings = configuration.get();
        final String init =
                settings.containersInit().isEmpty()
                        ? settings.engineInit()
                        : settings.containersInit();
        try {
            if (!init.isEmpty()) {
                return missing(runner, List.of(Path.of(init).toAbsolutePath()), "")
                        .map(
                                ignored ->
                                        "configured init '"
                                                + init
                                                + "' is missing or is not executable; repair init_path in containers.conf or install its package");
            }
            final List<String> helpers = settings.helpers();
            if (helpers == null) {
                return missing(runner, defaults, path);
            }
            final List<Path> selected = new ArrayList<>();
            final String helper = environment.apply("CONTAINERS_HELPER_BINARY_DIR");
            if (helper != null) {
                selected.add(Path.of(helper).toAbsolutePath().resolve("catatonit"));
            }
            for (final String directory : helpers) {
                if (directory.equals("$BINDIR") || directory.startsWith("$BINDIR/")) {
                    return Optional.empty();
                }
                selected.add(Path.of(directory).toAbsolutePath().resolve("catatonit"));
            }
            return missing(runner, defaults.isEmpty() ? null : defaults.getFirst(), selected, path);
        } catch (IllegalArgumentException | SecurityException ex) {
            return Optional.empty();
        }
    }
}
