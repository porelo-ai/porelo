package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.fuin.porelo.gate.SecretScan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link SecretsKnown}. */
class SecretsKnownTest {

    private static SecretScan.Finding at(String path, int line, String text) {
        return new SecretScan.Finding(path, line, "forge token", SecretScan.digest(text));
    }

    @Test
    void anAcknowledgedFindingHoldsWhileItsLineStaysAndNoLonger(@TempDir Path dir)
            throws Exception {
        final SecretsKnown known = new SecretsKnown(dir.resolve("state/secrets-known/p.json"));
        known.add("shop", at("a.txt", 2, "token=one"));

        assertThat(known.unknown("shop", List.of(at("a.txt", 2, "token=one"))))
                .as("the same line is not said again")
                .isEmpty();
        assertThat(known.unknown("shop", List.of(at("a.txt", 2, "token=two"))))
                .as("a changed line is said again")
                .hasSize(1);
        assertThat(known.unknown("back", List.of(at("a.txt", 2, "token=one"))))
                .as("another repository's line is its own")
                .hasSize(1);
    }

    @Test
    void theListIsTheOwnersAloneAndHoldsNoLine(@TempDir Path dir) throws Exception {
        final Path file = dir.resolve("state/secrets-known/p.json");
        new SecretsKnown(file).add("shop", at("a.txt", 2, "token=one"));

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
                .isEqualTo("rw-------");
        assertThat(Files.readString(file)).contains("a.txt:2").doesNotContain("token=one");
    }
}
