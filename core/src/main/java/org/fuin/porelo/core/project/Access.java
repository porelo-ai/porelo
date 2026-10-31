package org.fuin.porelo.core.project;

/**
 * Where a project keeps its access file: the host, the persons and the transports' settings that
 * the public {@code project.yml} must not show.
 *
 * <p>The place is the project's own choice. A second repository is for confidentiality, not for
 * trust: the access file is checked against the project's signers in either place.
 */
public sealed interface Access permits Access.InRepository, Access.BesideProject {

    /** The file's name, in either place. */
    String FILE = "access.yml";

    /**
     * {@code access.yml} in a repository of its own, followed beside the project's.
     *
     * @param url Where the repository is fetched from.
     */
    record InRepository(String url) implements Access {

        /**
         * Constructor.
         *
         * @param url Where the repository is fetched from.
         * @throws ProjectException If the address is empty or reads as an option.
         */
        public InRepository {
            if (url.isBlank()) {
                throw new ProjectException("'access.repository' names no repository");
            }
            Repository.refuseAsOption(url, "access.repository");
        }
    }

    /**
     * {@code access.yml} beside {@code project.yml}, in the project's own commit.
     *
     * @param file The file's path relative to {@code project.yml}; always {@link #FILE}.
     */
    record BesideProject(String file) implements Access {

        /**
         * Constructor.
         *
         * @param file The file's path relative to {@code project.yml}.
         * @throws ProjectException For any other name than {@link #FILE}: a path elsewhere in the
         *     commit would be a second place to look for what one file says.
         */
        public BesideProject {
            if (!FILE.equals(file)) {
                throw new ProjectException(
                        "'access.file' is '" + file + "'; the access file is '" + FILE + "'");
            }
        }
    }
}
