package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.ecosystem.api.Detection;
import org.fuin.porelo.ecosystem.api.Finding;
import org.fuin.porelo.ecosystem.api.InstalledEcosystem;
import org.fuin.porelo.ecosystem.api.InstalledEcosystems;

/** Asks an installed build-tool plugin what it recognizes; a test answers without a socket. */
@FunctionalInterface
public interface EcosystemDetector {

    /** Starts the plugin itself and asks it over its socket. */
    EcosystemDetector INSTALLED =
            new EcosystemDetector() {
                @Override
                public Detection detect(
                        final InstalledEcosystems.Description plugin,
                        final Path checkout,
                        final Path home)
                        throws IOException {
                    final Path temporary = Files.createTempDirectory("porelo-detect-");
                    try (var installed = new InstalledEcosystem(plugin.executable(), temporary)) {
                        return installed.detect(checkout, home);
                    } finally {
                        Files.deleteIfExists(temporary);
                    }
                }

                @Override
                public Optional<List<Finding>> resolve(
                        final InstalledEcosystems.Description plugin,
                        final Path checkout,
                        final Path home,
                        final List<Finding> detected,
                        final Map<String, String> proxies)
                        throws IOException {
                    // A process of its own: the one Detect asked never holds a proxy setting.
                    final Path temporary = Files.createTempDirectory("porelo-resolve-");
                    try (var installed =
                            InstalledEcosystem.forResolve(
                                    plugin.executable(), temporary, proxies)) {
                        return installed.resolve(checkout, home, detected);
                    } finally {
                        Files.deleteIfExists(temporary);
                    }
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

    /**
     * Asks one plugin to look up what its own {@link #detect} read, when a project file is made.
     *
     * @param plugin The plugin.
     * @param checkout The directory.
     * @param home The person's home.
     * @param detected What its {@link #detect} answered, and no other plugin's.
     * @param proxies The person's proxy settings, which the plugin is started with.
     * @return The findings that replace those, or empty when the plugin cannot say; a detector that
     *     only detects cannot.
     * @throws IOException When it cannot be asked.
     */
    default Optional<List<Finding>> resolve(
            final InstalledEcosystems.Description plugin,
            final Path checkout,
            final Path home,
            final List<Finding> detected,
            final Map<String, String> proxies)
            throws IOException {
        return Optional.empty();
    }
}
