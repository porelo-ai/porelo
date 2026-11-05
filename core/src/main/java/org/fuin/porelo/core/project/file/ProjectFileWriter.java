package org.fuin.porelo.core.project.file;

import java.io.StringReader;
import org.fuin.porelo.core.project.ProjectException;

/**
 * Writes a {@link ProjectFile} as {@code project.yml}, by SnakeYAML, in the order the reader knows
 * the keys; what the model leaves out is not written.
 */
public final class ProjectFileWriter {

    private ProjectFileWriter() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Writes a project file the check finds no fault in.
     *
     * @param file The model.
     * @return The file's text.
     * @throws ProjectFileException If the check finds a fault; nothing is written then.
     */
    public static String write(final ProjectFile file) {
        FileYaml.refuse(ProjectFileCheck.check(file, null));
        final String written = FileYaml.dump(FileYaml.project(file));
        // What cannot be read back, such as a file past what the reader takes, is not written.
        if (!file.equals(
                ProjectFileReader.read(new StringReader(written), "the written file", null))) {
            throw new ProjectException("The file would not read back as it was written");
        }
        return written;
    }

    /**
     * Writes the part of a file a person adds to theirs, after checking the whole it makes.
     *
     * <p>Porelo changes no project file that exists: it shows the entry, and the person writes it.
     * The whole is checked so that an entry that would make the file unusable is never shown.
     *
     * @param whole The file as it reads once the entry is in it.
     * @param part The sections, keys and entries the person writes; only what it holds is shown.
     * @return The part's text.
     * @throws ProjectFileException If the check finds a fault in the whole.
     */
    public static String entry(final ProjectFile whole, final ProjectFile part) {
        FileYaml.refuse(ProjectFileCheck.check(whole, null));
        return FileYaml.dump(FileYaml.project(part));
    }

    /**
     * Writes the part of a file a person adds to theirs, where the file it goes into is not at
     * hand; what reads it later checks the whole.
     *
     * @param part The sections, keys and entries the person writes.
     * @return The part's text.
     */
    public static String entry(final ProjectFile part) {
        return FileYaml.dump(FileYaml.project(part));
    }
}
