package org.fuin.porelo.core.project;

import java.io.StringReader;
import org.fuin.porelo.core.project.file.Image;
import org.fuin.porelo.core.project.file.RepositoryFile;
import org.fuin.porelo.core.project.file.RepositoryFileReader;

/**
 * Reads a repository's {@code porelo.yml} or {@code .porelo.yml} as one source of a task's image.
 *
 * <p><strong>Only {@code image}.</strong> Egress, limits, the class and every other rule a task
 * runs under stay the project's: a repository that could name them would widen what its own tasks
 * may do. {@link RepositoryFileReader} refuses each such key by its name, never ignores it, so a
 * file that tried is not mistaken for one that was applied.
 */
public final class RepositoryImageReader {

    private RepositoryImageReader() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Reads one file.
     *
     * @param text The file's content.
     * @param origin Which file it is, as the start names it.
     * @param files Where its {@code run_file} is read from.
     * @return What it says.
     * @throws ProjectException If it is not YAML or anything in it is wrong.
     */
    public static ImageSource read(String text, String origin, SnippetFiles files) {
        final RepositoryFile file =
                RepositoryFileReader.read(new StringReader(text), origin, files);
        final Image image =
                java.util.Objects.requireNonNull(file.image(), "the reader requires an image");
        final String runFile = image.runFile();
        final String run = runFile == null ? image.run() : files.read(runFile, origin);
        return new ImageSource(image.from(), run, image.env(), origin);
    }
}
