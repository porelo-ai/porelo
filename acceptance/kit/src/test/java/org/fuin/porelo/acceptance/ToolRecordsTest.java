package org.fuin.porelo.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests the record summary where the file stays inside the container. */
class ToolRecordsTest {

    @ParameterizedTest
    @ValueSource(strings = {"refused", "-refused", "a'b; $(printf injected)", "literal.*[failure]"})
    void onlyThePathAndLiteralMarkerCountLeaveTheContainer(String marker, @TempDir Path home)
            throws Exception {
        final Path directory = Files.createDirectories(home.resolve(".sessions/nested"));
        final Path file =
                Files.writeString(
                        directory.resolve("run.jsonl"),
                        "private record text\n".repeat(1000)
                                + LoopWatch.SEPARATOR
                                + marker
                                + "\n"
                                + LoopWatch.SESSION_SEPARATOR
                                + marker
                                + "\n"
                                + marker
                                + " "
                                + marker);
        final ProcessBuilder builder =
                new ProcessBuilder(
                        "sh", "-c", new ToolRecords(".sessions", ".jsonl").command(marker));
        builder.environment().put("HOME", home.toString());
        builder.environment().put("LC_ALL", "C");
        final Process process = builder.redirectErrorStream(true).start();
        final String printed;
        try (var output = process.getInputStream()) {
            printed = new String(output.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(process.waitFor())
                .as("counting a safely quoted literal marker must succeed")
                .isZero();
        assertThat(printed)
                .as(
                        "record text and separator-looking lines must stay in the container; each literal occurrence counts")
                .isEqualTo(LoopWatch.SESSION_SEPARATOR + file.toString() + "\t4\n");
    }

    /** What the refused call printed, as a compact-screen agent's record holds it. */
    private static final String REFUSED = "option requires an argument";

    /**
     * The bare call's empty output in the same record, its quotes escaped as the record writes
     * them.
     */
    private static final String EMPTY = "\\\"output\\\":\\\"\\\"";

    /** One tool output holding a refused call and, after it, a call that printed nothing. */
    private static final String REFUSED_THEN_EMPTY =
            "{\"type\":\"custom_tool_call_output\",\"output\":["
                    + "{\"text\":\"{\\\"exit_code\\\":2,\\\"output\\\":\\\"bash: -c: "
                    + REFUSED
                    + "\\\\n\\\"}\"},"
                    + "{\"text\":\"{\\\"exit_code\\\":0,\\\"output\\\":\\\"\\\"}\"}]}\n";

    private static String count(Path home, String record, ToolRecords records) throws Exception {
        final Path directory = Files.createDirectories(home.resolve(".sessions"));
        Files.writeString(directory.resolve("run.jsonl"), record);
        final ProcessBuilder builder =
                new ProcessBuilder("sh", "-c", records.command(REFUSED)).redirectErrorStream(true);
        builder.environment().put("HOME", home.toString());
        builder.environment().put("LC_ALL", "C");
        final Process process = builder.start();
        final String printed;
        try (var output = process.getInputStream()) {
            printed = new String(output.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(process.waitFor()).as("counting must succeed: %s", printed).isZero();
        return printed.substring(printed.lastIndexOf('\t') + 1).strip();
    }

    @org.junit.jupiter.api.Test
    void anEmptyCallRightAfterARefusedOneIsAnotherTry(@TempDir Path home) throws Exception {

        // Measured: two refused `bash -c` calls and then a bare `bash` that printed nothing ran the
        // wait to its bound, nine paid requests, since the bare call carries no refusal to count.
        assertThat(
                        count(
                                home,
                                REFUSED_THEN_EMPTY.replace(
                                                ",{\"text\":\"{\\\"exit_code\\\":0,\\\"output\\\":\\\"\\\"}\"}",
                                                "")
                                        + REFUSED_THEN_EMPTY,
                                new ToolRecords(".sessions", ".jsonl", EMPTY)))
                .as("two refusals and the empty call right after the second are three tries")
                .isEqualTo("3");
    }

    @org.junit.jupiter.api.Test
    void anEmptyCallWithNoRefusalBeforeItIsNoTry(@TempDir Path home) throws Exception {
        assertThat(
                        count(
                                home,
                                "{\"text\":\"{\\\"exit_code\\\":0,\\\"output\\\":\\\"\\\"}\"}\n"
                                        + REFUSED_THEN_EMPTY.replace(
                                                ",{\"text\":\"{\\\"exit_code\\\":0,\\\"output\\\":\\\"\\\"}\"}",
                                                ""),
                                new ToolRecords(".sessions", ".jsonl", EMPTY)))
                .as("a call that printed nothing is a try only right after a refused one")
                .isEqualTo("1");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.Tag("documents")
    void theReadmesExampleHandsTheKitTheBackslashesTheRecordHolds() throws Exception {

        // Copied from the kit's README into a feature: a step that lost the backslashes would
        // declare a text the record never holds, and the bare call would go uncounted again.
        final String example =
                Files.readAllLines(Path.of("README.md"), StandardCharsets.UTF_8).stream()
                        .map(String::strip)
                        .filter(line -> line.startsWith("And an empty tool call in its records"))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("the README shows no such step"))
                        .substring("And ".length());
        final var expression =
                new io.cucumber.cucumberexpressions.ExpressionFactory(
                                new io.cucumber.cucumberexpressions.ParameterTypeRegistry(
                                        java.util.Locale.ENGLISH))
                        .createExpression(
                                TerminalSteps.class
                                        .getMethod("anEmptyToolCallLooksLike", String.class)
                                        .getAnnotation(io.cucumber.java.en.Given.class)
                                        .value());
        final var match = expression.match(example);

        assertThat(match).as("the README's line must be the step the kit declares").isNotNull();
        assertThat(match.getFirst().getValue())
                .as("the step must receive what the record writes")
                .isEqualTo(EMPTY);
    }

    @org.junit.jupiter.api.Test
    void anEmptyCallMustEndBeforeTheNextMarkerToCount(@TempDir Path home) throws Exception {

        // Taken across the next marker, the empty call swallowed it and a third try went uncounted.
        final Path directory = Files.createDirectories(home.resolve(".sessions"));
        Files.writeString(directory.resolve("run.jsonl"), "abbabba\n");
        final ProcessBuilder builder =
                new ProcessBuilder(
                                "sh",
                                "-c",
                                new ToolRecords(".sessions", ".jsonl", "ba").command("ab"))
                        .redirectErrorStream(true);
        builder.environment().put("HOME", home.toString());
        builder.environment().put("LC_ALL", "C");
        final Process process = builder.start();
        final String printed;
        try (var output = process.getInputStream()) {
            printed = new String(output.readAllBytes(), StandardCharsets.UTF_8);
        }
        process.waitFor();

        assertThat(printed.strip())
                .as("two markers, each followed by an empty call that ends before the next")
                .endsWith("\t3");
    }

    @org.junit.jupiter.api.Test
    void anEmptyMarkerIsRefusedBeforeItReachesTheContainer() {

        // awk finds the empty string everywhere and never moves past it, so the count never ended.
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new ToolRecords(".sessions", ".jsonl").command(""))
                .as("an empty marker must not reach awk")
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new ToolRecords(".sessions", ".jsonl", " "))
                .as("a blank empty call would count every space after a refusal")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @org.junit.jupiter.api.Test
    void withoutAnEmptyCallDeclaredOnlyRefusalsCount(@TempDir Path home) throws Exception {
        assertThat(count(home, REFUSED_THEN_EMPTY, new ToolRecords(".sessions", ".jsonl")))
                .as("with no empty call declared, only the refusal is a try")
                .isEqualTo("1");
    }
}
