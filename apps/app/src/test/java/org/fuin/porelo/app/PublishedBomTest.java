package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * What {@code porelo-bom} publishes, read from the pom flatten writes for it: this module's test
 * dependency on {@code porelo-bom} has the reactor build {@code bom} first, so it is there when
 * this runs.
 */
class PublishedBomTest {

    private static final Path FLATTENED = Path.of("..", "..", "bom", ".flattened-pom.xml");

    @Test
    void carriesNoFlagThatWouldPublishWhoeverTakesItAsParent() throws IOException {

        assertThat(FLATTENED)
                .as(
                        "the pom bom's flatten writes, made by './mvnw -pl bom process-resources' or by"
                                + " any run that takes in bom")
                .exists();
        // A repository took porelo-bom as its parent, inherited both flags as false and counted as
        // published to Central, which it never was.
        final String published = Files.readString(FLATTENED);

        assertThat(published)
                .as("a BOM, its versions there")
                .contains("<dependencyManagement>")
                .contains("<artifactId>porelo-agent-api</artifactId>");
        assertThat(published)
                .as("no publishing flag an importer or a child would inherit")
                .doesNotContain("<skipPublishing>false</skipPublishing>")
                .doesNotContain("<maven.deploy.skip>false</maven.deploy.skip>");
    }
}
