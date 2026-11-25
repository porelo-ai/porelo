package org.fuin.porelo.build.api.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.contrastsecurity.sarif.SarifSchema210;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/** What the hardened reader reads reflectively and as resources, declared to the native image. */
class SarifReadingMetadataTest {

    private static final Path METADATA =
            Path.of(
                    "src/main/resources/META-INF/native-image",
                    "org.fuin.porelo/porelo-build-api/reachability-metadata.json");

    @Test
    void everyClassOfSarifsModelIsDeclaredToTheNativeImage() throws Exception {
        // java-sarif ships no metadata, and Jackson binds an undeclared class to nothing there.
        final String metadata = Files.readString(METADATA, StandardCharsets.UTF_8);
        final List<String> model;
        try (JarFile jar =
                new JarFile(
                        Path.of(
                                        SarifSchema210.class
                                                .getProtectionDomain()
                                                .getCodeSource()
                                                .getLocation()
                                                .toURI())
                                .toFile())) {
            model =
                    jar.stream()
                            .map(entry -> entry.getName())
                            .filter(name -> name.startsWith("com/contrastsecurity/sarif/"))
                            .filter(name -> name.endsWith(".class"))
                            .map(name -> name.substring(0, name.length() - 6).replace('/', '.'))
                            .toList();
        }
        assertThat(model)
                .as("the classes of SARIF's model")
                .contains(SarifSchema210.class.getName());
        assertThat(model.stream().filter(type -> !metadata.contains('"' + type + '"')).toList())
                .as("SARIF model classes absent from %s", METADATA)
                .isEmpty();
    }

    @Test
    void theBundledProfileSchemasAreDeclaredToTheNativeImage() throws IOException {
        final String metadata = Files.readString(METADATA, StandardCharsets.UTF_8);
        for (final String schema :
                List.of("review-profile-1.schema.json", "review-profile-2.schema.json")) {
            assertThat(metadata)
                    .as("the resource %s in %s", schema, METADATA)
                    .contains("\"org/fuin/porelo/build/api/review/" + schema + "\"");
        }
    }
}
