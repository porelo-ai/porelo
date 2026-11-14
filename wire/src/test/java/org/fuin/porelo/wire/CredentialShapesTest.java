package org.fuin.porelo.wire;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Tests for {@link CredentialShapes}. */
class CredentialShapesTest {

    @ParameterizedTest
    @CsvSource({
        "ghp_, 36, forge token",
        "glpat-, 20, forge token",
        "sk-ant-, 24, provider key",
        "AKIA, 16, cloud key",
        "npm_, 36, registry token",
        "porelo_ab_, 20, Porelo token"
    })
    void namesAShapeByItsKind(String prefix, int length, String kind) {
        final String token = prefix + "A".repeat(length);

        assertThat(CredentialShapes.kindOf("x = " + token + ";")).contains(kind);
    }

    @Test
    void aPrivateKeyIsKnownByItsHeader() {
        assertThat(CredentialShapes.kindOf("-----BEGIN " + "EC PRIVATE KEY-----"))
                .contains(CredentialShapes.PRIVATE_KEY);
    }

    @Test
    void ordinaryTextIsNoCredential() {
        assertThat(CredentialShapes.kindOf("sk-short and ghp_ and AKIA alone")).isEmpty();
    }

    @Test
    void everyShapeIsPlainExtendedSyntax() {
        // git grep -E reads them: no Java-only group, class or lookaround.
        assertThat(CredentialShapes.ALL)
                .extracting(CredentialShapes.Shape::pattern)
                .noneMatch(
                        p ->
                                p.contains("(?")
                                        || p.contains("\\s")
                                        || p.contains("\\w")
                                        || p.contains("\\d")
                                        || p.contains("\\b"));
    }
}
