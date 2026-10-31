package org.fuin.porelo.wire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests for {@link Json}. */
class JsonTest {

    // Harm guarded: serialized keys and values must not inject terminal controls or forged lines.
    @ParameterizedTest
    @ValueSource(ints = {0, 27, 127, 128, 155, 159, 173, 8205, 8232, 8233, 8238, 917505})
    void controlsAndFormatCharactersAreEscapedWithoutChangingTheirValue(final int codePoint) {
        final String text = new String(Character.toChars(codePoint));
        final StringBuilder escaped = new StringBuilder();
        for (final char unit : text.toCharArray()) {
            escaped.append("\\u%04x".formatted((int) unit));
        }
        final Map<String, String> value = Map.of(text, text);

        final String json = Json.write(value);

        assertThat(json)
                .as("both keys and values are safe to display")
                .isEqualTo("{\"" + escaped + "\":\"" + escaped + "\"}");
        assertThat(Json.parse(json)).as("escaping preserves the complete value").isEqualTo(value);
    }

    @Test
    void lineEndsAndTabsKeepTheirShortEscapes() {
        assertThat(Json.write(Map.of("log", "one\r\ntwo\tthree")))
                .as("the short escapes, as before")
                .isEqualTo("{\"log\":\"one\\r\\ntwo\\tthree\"}");
    }

    @Test
    void readsAnObject() {

        final Object value = Json.parse("{\"a\":1,\"b\":\"two\",\"c\":true,\"d\":null}");

        assertThat(value).isInstanceOf(Map.class);
        final Map<?, ?> map = (Map<?, ?>) value;
        assertThat(map.get("a")).isEqualTo(Double.valueOf(1));
        assertThat(map.get("b")).isEqualTo("two");
        assertThat(map.get("c")).isEqualTo(Boolean.TRUE);
        assertThat(map.containsKey("d")).isTrue();
        assertThat(map.get("d")).isNull();
    }

    @Test
    void readsNestedStructures() {

        final Map<?, ?> map = (Map<?, ?>) Json.parse("{\"list\":[1,[2],{\"x\":\"y\"}]}");

        assertThat((List<?>) map.get("list")).hasSize(3);
    }

    @Test
    void readsEscapes() {

        assertThat(Json.parse("\"a\\\"b\\\\c\\nd\\u0041\"")).isEqualTo("a\"b\\c\ndA");
    }

    @Test
    void readsEmptyContainers() {

        assertThat((Map<?, ?>) Json.parse("{}")).isEmpty();
        assertThat((List<?>) Json.parse("[]")).isEmpty();
    }

    @Test
    void ignoresWhitespace() {

        assertThat(((Map<?, ?>) Json.parse("  {\n  \"a\" : [ 1 , 2 ]\n}  ")).containsKey("a"))
                .isTrue();
    }

    @Test
    void writesWhatItCanRead() {

        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", "a\"b");
        map.put("count", Integer.valueOf(3));
        map.put("on", Boolean.TRUE);
        map.put("items", List.of("x", "y"));
        map.put("nothing", null);

        final String json = Json.write(map);

        assertThat(json)
                .isEqualTo(
                        "{\"name\":\"a\\\"b\",\"count\":3,\"on\":true,"
                                + "\"items\":[\"x\",\"y\"],\"nothing\":null}");
        assertThat(Json.parse(json)).isInstanceOf(Map.class);
    }

    @Test
    void rejectsDuplicateKeys() {

        // A second value quietly winning is a bug in a file a fail-closed hook acts on.
        assertThatThrownBy(() -> Json.parse("{\"a\":1,\"a\":2}"))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("Duplicate key");
    }

    @Test
    void rejectsTrailingContent() {

        assertThatThrownBy(() -> Json.parse("{} {}"))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("Trailing content");
    }

    @Test
    void rejectsTruncatedInput() {

        assertThatThrownBy(() -> Json.parse("{\"a\":")).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> Json.parse("\"unterminated")).isInstanceOf(JsonException.class);
        assertThatThrownBy(() -> Json.parse("")).isInstanceOf(JsonException.class);
    }

    @Test
    void reportsWhereTheProblemIs() {

        assertThatThrownBy(() -> Json.parse("{\"a\" 1}"))
                .isInstanceOf(JsonException.class)
                .hasMessageContaining("at offset");
    }

    @org.junit.jupiter.api.Test
    void deepNestingIsRefusedAsBadInputNotAStackOverflow() {

        // A varlink message of brackets, well inside its size limit, ended in StackOverflowError -
        // which no caller that catches bad input as a RuntimeException ever saw.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Json.parse("[".repeat(200_000)))
                .isInstanceOf(JsonException.class);
    }

    @org.junit.jupiter.api.Test
    void aNumberNoDoubleCanHoldIsRefusedRatherThanWrittenOutAsNoJson() {

        // 1e999 was read as Infinity, written back as the bare word, and every subscriber that
        // parsed it lost its stream.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Json.parse("{\"port\":1e999}"))
                .isInstanceOf(JsonException.class);
    }

    @org.junit.jupiter.api.Test
    void aKeyGivenTwiceIsRefusedWhateverItsFirstValueWasAndAnEscapeHasNoSign() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> Json.parse("{\"a\":null,\"a\":\"x\"}"))
                .isInstanceOf(JsonException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Json.parse("\"\\u-001\""))
                .isInstanceOf(JsonException.class);
    }
}
