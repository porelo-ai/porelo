package org.fuin.porelo.ecosystem.api;

import java.nio.file.Path;
import java.util.List;

/**
 * What a build-tool plugin implements: one build tool, asked about one directory or one task's
 * repositories at a time.
 *
 * <p>A plugin in its own repository implements this, and its main method hands it to {@link
 * EcosystemMain#run}; Porelo finds the executable, starts it normally, and asks it over {@link
 * EcosystemProtocol#INTERFACE}. It opens no connection, never runs the project's code and holds no
 * credential.
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
     * Returns the files the tool's settings need so that it reaches these repositories, writing
     * nothing itself.
     *
     * @param repositories The repositories a task declares.
     * @return The files.
     * @throws EcosystemRefused When it cannot answer.
     */
    List<File> configure(List<Repository> repositories);
}
