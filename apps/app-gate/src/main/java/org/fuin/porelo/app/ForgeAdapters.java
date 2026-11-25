package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.fuin.porelo.build.api.BuildReaderException;
import org.fuin.porelo.build.api.InstalledBuildReader;

/**
 * The forge adapters installed on this machine, found where a task's builds find their reader: the
 * account's own first, then the packaged ones.
 */
public final class ForgeAdapters {

    private final List<Path> directories;

    /**
     * Looks in the given directories.
     *
     * @param directories Where adapters are installed, in the order they are looked for.
     */
    public ForgeAdapters(final List<Path> directories) {
        this.directories = List.copyOf(directories);
    }

    /**
     * Returns the adapters of this account and of the packages.
     *
     * @param context The machine.
     * @return Them.
     */
    public static ForgeAdapters of(final PoreloContext context) {
        return new ForgeAdapters(
                List.of(
                        context.paths().xdg().data().resolve("builds"),
                        InstalledBuildReader.PACKAGED));
    }

    /**
     * Returns where adapters are looked for.
     *
     * @return The directories, in the order they are looked in.
     */
    public List<Path> directories() {
        return directories;
    }

    /**
     * Returns whether an adapter of that name is installed.
     *
     * @param name The adapter's name, which is the forge kind it serves.
     * @return Whether one is found.
     */
    public boolean installed(final String name) {
        try {
            InstalledBuildReader.find(name, directories);
            return true;
        } catch (BuildReaderException ex) {
            return false;
        }
    }

    /**
     * Returns every adapter installed, each name once.
     *
     * @return The names, sorted.
     */
    public List<String> names() {
        final TreeSet<String> names = new TreeSet<>();
        for (final Path directory : directories) {
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (Stream<Path> listed = Files.list(directory)) {
                listed.map(path -> path.getFileName().toString())
                        .filter(this::installed)
                        .forEach(names::add);
            } catch (IOException ex) {
                // A directory that cannot be listed holds no adapter this account can start.
            }
        }
        return List.copyOf(names);
    }
}
