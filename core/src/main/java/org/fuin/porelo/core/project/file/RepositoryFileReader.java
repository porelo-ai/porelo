package org.fuin.porelo.core.project.file;

import java.io.Reader;
import java.nio.file.Path;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.SnippetFiles;
import org.jspecify.annotations.Nullable;

/**
 * Reads a repository's {@code porelo.yml} or {@code .porelo.yml} into a {@link RepositoryFile},
 * refusing it whole when anything in it is wrong: every fault at once, each with its line and key.
 */
public final class RepositoryFileReader {

    private RepositoryFileReader() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Reads a repository's file, its {@code run_file} from beside it.
     *
     * @param file The file.
     * @return The model, checked.
     * @throws ProjectFileException If anything in it is wrong.
     * @throws ProjectException If it cannot be read or is not YAML.
     */
    public static RepositoryFile read(final Path file) {
        return ProjectFileReader.read(
                file,
                (reader, origin) -> read(reader, origin, SnippetFiles.beside(Path.of(origin))));
    }

    /**
     * Reads a repository's file.
     *
     * @param reader The file's content.
     * @param origin The file, for messages.
     * @param snippets Where a {@code run_file} is read from to be checked, or {@code null} to check
     *     only how it is named.
     * @return The model, checked.
     * @throws ProjectFileException If anything in it is wrong.
     * @throws ProjectException If it is not YAML.
     */
    public static RepositoryFile read(
            final Reader reader, final String origin, final @Nullable SnippetFiles snippets) {
        final Binding binding = new Binding(origin, Sections.REPOSITORY);
        final RepositoryFile file =
                FileBinder.repository(binding, ProjectFileReader.root(reader, origin));
        ProjectFileReader.refuse(binding);
        ProjectFileCheck.check(file, snippets).forEach(binding::fault);
        ProjectFileReader.refuse(binding);
        return file;
    }
}
