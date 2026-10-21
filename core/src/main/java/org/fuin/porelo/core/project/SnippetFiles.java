package org.fuin.porelo.core.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads a {@code snippet_file} a file names, from the same place the naming file came from.
 *
 * <p>One interface because the place differs: a project file and a checkout's {@code porelo.yml}
 * sit in a directory, a repository's {@code porelo.yml} is read from its mirror's default branch.
 * The rule is the same everywhere: the file is the repository's own, never a path that leaves it.
 */
@FunctionalInterface
public interface SnippetFiles {

    /**
     * Returns a snippet file's content.
     *
     * @param named The path as the naming file wrote it.
     * @param origin The naming file, for messages.
     * @return The content.
     * @throws ProjectException If it names no file of the repository or cannot be read.
     */
    String read(String named, String origin);

    /**
     * Reads snippet files from the directory a file lies in, and from nowhere else.
     *
     * @param file The naming file.
     * @return The reader.
     */
    static SnippetFiles beside(Path file) {
        return (named, origin) -> {
            // From the repository and nowhere else: resolved as written, an absolute path, '../' or
            // a link read any file the person can - a key included - into the image the agent runs
            // in, or into an error on their screen.
            final Path base = file.toAbsolutePath().getParent();
            if (base == null || Path.of(named).isAbsolute()) {
                throw new ProjectException(
                        origin
                                + ": 'image.snippet_file' must name a file of the repository, not "
                                + named);
            }
            final Path path = base.resolve(named).normalize();
            if (!Files.isRegularFile(path)) {
                throw new ProjectException(origin + ": no image snippet at " + path);
            }
            try {
                if (!path.toRealPath().startsWith(base.toRealPath())) {
                    throw new ProjectException(
                            origin
                                    + ": 'image.snippet_file' must name a file of the repository, not "
                                    + named);
                }
                return Files.readString(path, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new ProjectException("Cannot read " + path, ex);
            }
        };
    }
}
