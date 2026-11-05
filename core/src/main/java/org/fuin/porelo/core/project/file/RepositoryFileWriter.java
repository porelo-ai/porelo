package org.fuin.porelo.core.project.file;

import java.io.StringReader;
import org.fuin.porelo.core.project.ProjectException;

/**
 * Writes a {@link RepositoryFile} as {@code porelo.yml}, by SnakeYAML, in the order the reader
 * knows the keys; what the model leaves out is not written.
 */
public final class RepositoryFileWriter {

    private RepositoryFileWriter() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Writes a repository's file the check finds no fault in.
     *
     * @param file The model.
     * @return The file's text.
     * @throws ProjectFileException If the check finds a fault; nothing is written then.
     */
    public static String write(final RepositoryFile file) {
        FileYaml.refuse(ProjectFileCheck.check(file, null));
        final String written = FileYaml.dump(FileYaml.repository(file));
        // What cannot be read back, such as a file past what the reader takes, is not written.
        if (!file.equals(
                RepositoryFileReader.read(new StringReader(written), "the written file", null))) {
            throw new ProjectException("The file would not read back as it was written");
        }
        return written;
    }
}
