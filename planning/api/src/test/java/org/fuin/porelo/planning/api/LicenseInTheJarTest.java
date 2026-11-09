package org.fuin.porelo.planning.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LicenseInTheJarTest {

    @Test
    void theModulesOwnLicenseGoesIntoItsJar() throws IOException, URISyntaxException {
        // Read from this module's own classes: a dependency's jar holds a META-INF/LICENSE too.
        final Path classes =
                Path.of(
                        PlanningAdapter.class
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toURI());

        assertThat(classes.resolve("META-INF/LICENSE"))
                .as("the extension API must carry its own Apache license")
                .isRegularFile()
                .content()
                .contains("Apache License")
                .contains("Version 2.0, January 2004");
    }
}
