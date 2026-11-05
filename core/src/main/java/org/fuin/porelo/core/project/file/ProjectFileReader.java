package org.fuin.porelo.core.project.file;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiFunction;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.SnippetFiles;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.nodes.Node;

/**
 * Reads a {@code project.yml} into a {@link ProjectFile}, refusing it whole when anything in it is
 * wrong: every fault at once, each with its line and key.
 */
public final class ProjectFileReader {

    private ProjectFileReader() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Reads a project file, its {@code run_file} from beside it.
     *
     * @param file The file.
     * @return The model, checked.
     * @throws ProjectFileException If anything in it is wrong.
     * @throws ProjectException If it cannot be read or is not YAML.
     */
    public static ProjectFile read(final Path file) {
        return read(file, ProjectFileReader::read);
    }

    /**
     * Reads a project file whose {@code run_file} is read from beside its origin.
     *
     * @param reader The file's content.
     * @param origin The file, for messages.
     * @return The model, checked.
     * @throws ProjectFileException If anything in it is wrong.
     * @throws ProjectException If it is not YAML.
     */
    public static ProjectFile read(final Reader reader, final String origin) {
        return read(reader, origin, SnippetFiles.beside(Path.of(origin)));
    }

    /**
     * Reads a project file.
     *
     * @param reader The file's content.
     * @param origin The file, for messages.
     * @param snippets Where a {@code run_file} is read from to be checked, or {@code null} to check
     *     only how it is named.
     * @return The model, checked.
     * @throws ProjectFileException If anything in it is wrong.
     * @throws ProjectException If it is not YAML.
     */
    public static ProjectFile read(
            final Reader reader, final String origin, final @Nullable SnippetFiles snippets) {
        final Binding binding = new Binding(origin, Sections.PROJECT);
        final ProjectFile file = FileBinder.project(binding, root(reader, origin));
        refuse(binding);
        ProjectFileCheck.check(file, snippets).forEach(binding::fault);
        refuse(binding);
        return file;
    }

    /**
     * Reads only the name a project file declares, before its signature is checked: nothing else of
     * it is judged, and a refusal names no excerpt of it.
     *
     * @param reader The unverified file's content.
     * @param origin The file, for messages.
     * @return The declared name, or {@code null} when it names none.
     * @throws ProjectException If it is not YAML.
     */
    public static @Nullable String name(final Reader reader, final String origin) {
        final Node root = Binding.compose(reader, origin);
        if (root == null) {
            return null;
        }
        final ProjectInfo info =
                FileBinder.project(new Binding(origin, Sections.PROJECT), root).project();
        final String name = info == null ? null : info.name();
        return name == null || name.isBlank() ? null : name;
    }

    /**
     * Returns the keys each section of a project file holds.
     *
     * @return Section to its keys, both in the order they are written; {@code ""} is the top level,
     *     and {@code <name>} stands for a name the file chooses.
     */
    public static java.util.Map<String, List<String>> sections() {
        return Sections.PROJECT.all();
    }

    static Node root(final Reader reader, final String origin) {
        final Node root = Binding.compose(reader, origin);
        if (root == null) {
            throw new ProjectException(origin + " is empty or is not a YAML mapping");
        }
        return root;
    }

    static void refuse(final Binding binding) {
        final List<String> faults = binding.faults();
        if (!faults.isEmpty()) {
            throw new ProjectFileException(faults);
        }
    }

    static <T> T read(final Path file, final BiFunction<Reader, String, T> read) {
        if (!Files.isRegularFile(file)) {
            throw new ProjectException("No file at " + file);
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            // Absolute, because the message is read somewhere else than it was typed.
            return read.apply(reader, file.toAbsolutePath().toString());
        } catch (final IOException ex) {
            throw new ProjectException("Cannot read " + file, ex);
        }
    }
}
