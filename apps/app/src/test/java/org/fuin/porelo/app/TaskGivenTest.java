package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The files a task is given to read: kept by the host where only its account reads them, and
 * mounted read only into the task, bounded before anything is made.
 */
class TaskGivenTest {
    private static final String TASK = "porelo-uc-analysis";
    @TempDir Path dir;

    private PoreloPaths paths() {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloPaths(xdg, dir.resolve("bin"));
    }

    @Test
    void twoGivenFilesAreReadBackWhereTheTaskReadsThemAndNoneCanBeWritten() throws IOException {
        final TaskGiven given = new TaskGiven(paths());
        final Map<String, String> files = new LinkedHashMap<>();
        files.put("the-issue.md", "# The issue as the planner holds it\n");
        files.put("notes.txt", "zwei Wörter\n");

        given.prepare(TASK, files);

        assertThat(given.has(TASK)).isTrue();
        assertThat(given.names(TASK)).as("by name").containsExactly("notes.txt", "the-issue.md");
        final Path mounted = given.files(TASK);
        assertThat(Files.readString(mounted.resolve("the-issue.md"), StandardCharsets.UTF_8))
                .isEqualTo(files.get("the-issue.md"));
        assertThat(Files.readString(mounted.resolve("notes.txt"), StandardCharsets.UTF_8))
                .isEqualTo("zwei Wörter\n");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(mounted)))
                .as("the agent, another uid, reads the files and writes none")
                .isEqualTo("r-xr-xr-x");
        assertThat(
                        PosixFilePermissions.toString(
                                Files.getPosixFilePermissions(mounted.resolve("the-issue.md"))))
                .isEqualTo("r--r--r--");
        assertThat(
                        PosixFilePermissions.toString(
                                Files.getPosixFilePermissions(paths().tasks().given(TASK))))
                .as("the record around them is the account's alone")
                .isEqualTo("rwx------");
        assertThat(TaskGiven.MOUNT).isEqualTo("/run/porelo/given");
    }

    @Test
    void aNewTaskOfTheNameHasOnlyItsOwnFilesAndARemovalForgetsThem() throws IOException {
        final TaskGiven given = new TaskGiven(paths());
        given.prepare(TASK, Map.of("old.md", "before"));

        given.prepare(TASK, Map.of("new.md", "now"));
        assertThat(given.names(TASK)).containsExactly("new.md");

        given.forget(TASK);
        assertThat(given.has(TASK)).isFalse();
        assertThat(paths().tasks().given(TASK)).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"../the-issue.md", "issues/the-issue.md", ".", "..", ""})
    void aNameThatIsNoSingleFilesIsRefused(final String name) {
        assertThat(TaskGiven.refusal(Map.of(name, "text"))).isNotNull();
    }

    @Test
    void aSeventeenthFileIsRefused() {
        final Map<String, String> files = new LinkedHashMap<>();
        for (int i = 0; i < TaskGiven.MOST_FILES; i++) {
            files.put("f" + i + ".md", "x");
        }
        assertThat(TaskGiven.refusal(files)).as("sixteen are given").isNull();
        files.put("f16.md", "x");
        assertThat(TaskGiven.refusal(files)).contains("at most 16");
    }

    @Test
    void aFileOverOneMebibyteIsRefused() {
        assertThat(TaskGiven.refusal(Map.of("big.md", "x".repeat(TaskGiven.MOST)))).isNull();
        assertThat(TaskGiven.refusal(Map.of("big.md", "x".repeat(TaskGiven.MOST + 1))))
                .contains("big.md")
                .contains("1 MiB");
    }

    @Test
    void textThatIsNotUtf8IsRefused() {
        // A lone surrogate has no UTF-8 encoding.
        assertThat(TaskGiven.refusal(Map.of("issue.md", "half \uD800 a character")))
                .contains("issue.md")
                .contains("UTF-8");
    }

    @Test
    void filesTogetherOverWhatOneMessageCarriesAreRefused() {
        final Map<String, String> files = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++) {
            files.put("f" + i + ".md", "x".repeat(TaskGiven.MOST - 16));
        }
        assertThat(TaskGiven.refusal(files)).contains("4 MiB");
    }

    @Test
    void aRefusedSetIsNeverWritten() {
        final TaskGiven given = new TaskGiven(paths());
        assertThatThrownBy(() -> given.prepare(TASK, Map.of("../x.md", "text")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(paths().tasks().given(TASK)).doesNotExist();
    }
}
