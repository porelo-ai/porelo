package org.fuin.porelo.ecosystem.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Where a plugin may place a settings file: its answer becomes Porelo's instruction here. */
class FileTest {

    @Test
    void aPathInsideTheHomeIsAccepted() {
        assertThat(new File(".m2/settings.xml", "<settings/>", true).path())
                .isEqualTo(".m2/settings.xml");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/etc/passwd",
                "../outside",
                ".m2/../../outside",
                ".m2/..",
                "..",
                ".m2//settings.xml",
                ".m2/",
                " ",
                "quo'te",
                "quo\"te",
                "back\\slash",
                "line\nbreak",
                ".",
                "./settings",
                ".m2/./settings",
                "del\u007fete"
            })
    void aPathThatIsNoPlainOneInsideTheHomeIsRefused(final String path) {
        assertThatThrownBy(() -> new File(path, "x", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsAFileAnsweredWithAllItsFields() {
        assertThat(File.of(answer(Map.of("path", "s", "content", "", "ownerOnly", true))))
                .containsExactly(new File("s", "", true));
    }

    @Test
    void anOwnerOnlyThatIsMissingNullOrNoBoolIsRefusedNeverReadAsFalse() {
        final Map<String, Object> asNull = new HashMap<>();
        asNull.put("path", "s");
        asNull.put("content", "c");
        asNull.put("ownerOnly", null);
        for (final Map<String, Object> file :
                List.of(
                        Map.<String, Object>of("path", "s", "content", "c"),
                        asNull,
                        Map.<String, Object>of("path", "s", "content", "c", "ownerOnly", "true"),
                        Map.<String, Object>of("path", "s", "content", "c", "ownerOnly", 1L))) {
            assertThatThrownBy(() -> File.of(answer(file)))
                    .as(String.valueOf(file))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ownerOnly");
        }
    }

    @Test
    void aFileWithoutContentIsRefusedNeverReadAsEmpty() {
        assertThatThrownBy(() -> File.of(answer(Map.of("path", "s", "ownerOnly", false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("content");
    }

    private static Map<String, Object> answer(final Map<String, Object> file) {
        return Map.of("files", List.of(file));
    }
}
