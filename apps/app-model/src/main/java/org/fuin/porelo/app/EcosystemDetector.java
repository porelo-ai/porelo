package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.ecosystem.api.Detection;
import org.fuin.porelo.ecosystem.api.Extension;
import org.fuin.porelo.ecosystem.api.File;
import org.fuin.porelo.ecosystem.api.Finding;
import org.fuin.porelo.ecosystem.api.InstalledEcosystem;
import org.fuin.porelo.ecosystem.api.InstalledEcosystems;
import org.fuin.porelo.ecosystem.api.Repository;

/** Asks an installed build-tool plugin what it recognizes; a test answers without a socket. */
@FunctionalInterface
public interface EcosystemDetector {

    /** Starts the plugin itself and asks it over its socket, every time limit at its default. */
    EcosystemDetector INSTALLED = new Installed(org.fuin.porelo.wire.LimitFactors.DEFAULT);

    /**
     * Returns this detector with the time limits of the plugins it starts under the account's
     * factors; one that starts none stays as it is.
     *
     * @param limits The factors.
     * @return The detector.
     */
    default EcosystemDetector limitedBy(final org.fuin.porelo.wire.LimitFactors limits) {
        return this;
    }

    /**
     * Starts the plugin itself and asks it over its socket.
     *
     * @param limits The factors the plugins' time limits follow.
     */
    record Installed(org.fuin.porelo.wire.LimitFactors limits) implements EcosystemDetector {

        @Override
        public EcosystemDetector limitedBy(final org.fuin.porelo.wire.LimitFactors factors) {
            return new Installed(factors);
        }

        @Override
        public Detection detect(
                final InstalledEcosystems.Description plugin, final Path checkout, final Path home)
                throws IOException {
            final Path temporary = Files.createTempDirectory("porelo-detect-");
            try (var installed = new InstalledEcosystem(plugin.executable(), temporary, limits)) {
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
                            plugin.executable(), temporary, proxies, limits)) {
                return installed.resolve(checkout, home, detected);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }

        @Override
        public List<File> configure(
                final InstalledEcosystems.Description plugin,
                final List<Repository> repositories,
                final Extension extension)
                throws IOException {
            final Path temporary = Files.createTempDirectory("porelo-configure-");
            try (var installed = new InstalledEcosystem(plugin.executable(), temporary, limits)) {
                return installed.configure(repositories, extension);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

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

    /**
     * Asks one plugin which settings files its tool needs in a task to reach these repositories.
     *
     * @param plugin The plugin.
     * @param repositories The repositories the project declares for it, each URL as written.
     * @param extension The block the project keeps for it, or {@link Extension#NONE}.
     * @return The files, each relative to the task user's home.
     * @throws IOException When it cannot be asked.
     * @throws org.fuin.porelo.ecosystem.api.EcosystemRefused When it refuses.
     * @throws org.fuin.porelo.ecosystem.api.EcosystemException When it fails, takes longer than the
     *     contract allows, or answers a file that cannot be placed.
     */
    default List<File> configure(
            final InstalledEcosystems.Description plugin,
            final List<Repository> repositories,
            final Extension extension)
            throws IOException {
        final Path temporary = Files.createTempDirectory("porelo-configure-");
        try (var installed = new InstalledEcosystem(plugin.executable(), temporary)) {
            return installed.configure(repositories, extension);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
