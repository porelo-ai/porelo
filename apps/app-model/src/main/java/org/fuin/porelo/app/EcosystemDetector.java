package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.ecosystem.api.Detection;
import org.fuin.porelo.ecosystem.api.InstalledEcosystem;
import org.fuin.porelo.ecosystem.api.InstalledEcosystems;

/** Asks an installed build-tool plugin what it recognizes; a test answers without a socket. */
@FunctionalInterface
public interface EcosystemDetector {

    /** Starts the plugin itself and asks it over its socket. */
    EcosystemDetector INSTALLED =
            (plugin, checkout, home) -> {
                final Path temporary = Files.createTempDirectory("porelo-detect-");
                try (var installed = new InstalledEcosystem(plugin.executable(), temporary)) {
                    return installed.detect(checkout, home);
                } finally {
                    Files.deleteIfExists(temporary);
                }
            };

    /**
     * Asks one plugin about a directory.
     *
     * @param plugin The plugin.
     * @param checkout The directory.
     * @param home The person's home.
     * @return What it recognized.
     * @throws IOException When it cannot be asked.
     */
    Detection detect(InstalledEcosystems.Description plugin, Path checkout, Path home)
            throws IOException;
}
