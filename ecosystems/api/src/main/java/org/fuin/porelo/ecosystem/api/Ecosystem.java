package org.fuin.porelo.ecosystem.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * What a build-tool plugin implements: one build tool, asked about one directory or one task's
 * repositories at a time.
 *
 * <p>A plugin in its own repository implements this, and its main method hands it to {@link
 * EcosystemMain#run}; Porelo finds the executable, starts it normally, and asks it over {@link
 * EcosystemProtocol#INTERFACE}. It opens a connection in {@link #resolve} alone, never runs the
 * project's code and holds no credential.
 */
public interface Ecosystem {

    /**
     * Returns the ecosystem this plugin serves, which is the name its executable is installed
     * under.
     *
     * @return Lower-case letters, digits and hyphens.
     */
    String ecosystem();

    /**
     * Says what this plugin recognizes of its tool in a directory, writing nothing.
     *
     * @param directory The directory to read.
     * @param home The home whose files hold the person's own settings.
     * @return What it recognized.
     * @throws EcosystemRefused When the directory holds nothing of its tool, or a file it needs
     *     cannot be read.
     */
    Detection detect(Path directory, Path home);

    /**
     * Looks up the exact versions of what its own {@link #detect} read, when a project file is
     * made: the one method that may open a connection. The process asked is started with the
     * person's proxy settings ({@link EcosystemProtocol#PROXY_VARIABLES}) and nothing else, keeps
     * each request within {@link EcosystemProtocol#RESOLVE_REQUEST_SECONDS} and the whole within
     * {@link EcosystemProtocol#RESOLVE_CALL_SECONDS}. A proxy setting never goes into the answer.
     *
     * <p>Two kinds of key are placed by their prefix: {@code run.<name>}, image lines ({@code RUN},
     * {@code ARG} and {@code ENV} only), joined in the order of the names, and {@code env.<NAME>},
     * a variable's value for the image.
     *
     * @param directory The directory {@link #detect} read.
     * @param home The home it read.
     * @param findings What {@link #detect} answered for them.
     * @return The findings that replace those whole, or empty when this plugin cannot say, which
     *     its {@code Detect} findings then stand for. Empty unless a plugin overrides it.
     * @throws EcosystemRefused When it cannot answer.
     */
    default Optional<List<Finding>> resolve(
            final Path directory, final Path home, final List<Finding> findings) {
        return Optional.empty();
    }

    /**
     * Returns the files the tool's settings need so that it reaches these repositories, writing
     * nothing itself.
     *
     * @param repositories The repositories a task declares.
     * @return The files.
     * @throws EcosystemRefused When it cannot answer.
     */
    List<File> configure(List<Repository> repositories);

    /**
     * Returns the files the tool's settings need so that it reaches these repositories, given what
     * the project keeps for this adapter, writing nothing itself.
     *
     * <p>An adapter whose tool needs nothing beside the repositories leaves this as it is; one that
     * reads the block validates it and refuses with {@link EcosystemRefused} naming the key, never
     * a value.
     *
     * @param repositories The repositories a task declares.
     * @param extension What the project keeps for this adapter; {@link Extension#NONE} for nothing.
     * @return The files.
     * @throws EcosystemRefused When it cannot answer.
     */
    default List<File> configure(final List<Repository> repositories, final Extension extension) {
        return configure(repositories);
    }
}
