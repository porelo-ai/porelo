package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.gate.GitGate;
import org.fuin.porelo.gate.ReviewRanking;
import org.fuin.porelo.wire.TaskMode;
import org.fuin.porelo.wire.TaskProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link ReviewText}: what a person reads before the patch. */
class ReviewTextTest {

    @Test
    void aRescuedPushRetainsItsOriginalInstruction() throws IOException {
        // Rescuing must not hide the instruction the unreviewed work was meant to follow.
        final PoreloPaths paths = paths();
        TaskIdentityFixture.record(paths, "porelo-checkout_fix", "checkout", "fix", "a".repeat(64));
        task(paths.tasks().taskRecord("porelo-checkout_fix"), "original instruction", "fix");
        assertThat(ReviewText.instruction(paths, "checkout", "fix" + GitGate.RESCUED).prompt())
                .as("the rescued ref keeps its original task's instruction")
                .isEqualTo("original instruction");
    }

    @Test
    void retainedInstructionsFollowTheRecordedOwnerInBothFormats() throws IOException {
        // A review must not omit an old instruction or show a different project's instruction.
        final PoreloPaths paths = paths();
        TaskIdentityFixture.record(paths, "porelo-web-app-x", "web", "app-x", "a".repeat(64));
        TaskIdentityFixture.record(paths, "porelo-web-app_x", "web-app", "x", "b".repeat(64));
        task(paths.tasks().taskRecord("porelo-web-app-x"), "original instruction", "app-x");
        task(paths.tasks().taskRecord("porelo-web-app_x"), "sibling instruction", "x");

        assertThat(ReviewText.instruction(paths, "web", "app-x").prompt())
                .as("the old actual owner keeps its instruction")
                .isEqualTo("original instruction");
        assertThat(ReviewText.instruction(paths, "web-app", "x").prompt())
                .as("the encoded sibling never borrows the overlapping old name")
                .isEqualTo("sibling instruction");
    }

    @TempDir Path dir;

    private PoreloPaths paths() {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloPaths(xdg, dir.resolve("bin"));
    }

    private void task(Path directory, String prompt, String push) throws IOException {
        Files.createDirectories(directory);
        final String container = directory.getFileName().toString();
        if (!Files.exists(paths().tasks().taskRecord(container).resolve("sidecar.json"))) {
            TaskIdentityFixture.record(paths(), container, "checkout", push, "a".repeat(64));
        }
        new TaskProfile(
                        1,
                        "asker",
                        TaskMode.UNATTENDED,
                        prompt,
                        GitGate.INCOMING + push,
                        "2026-09-29T12:00:00Z",
                        "deny")
                .writeTo(directory);
    }

    @Test
    void findsWhatTheTaskThatPushedWasAsked() throws IOException {
        task(paths().tasks().containerState("porelo-checkout-fix"), "fix the flaky test", "fix");

        final ReviewText.Instruction asked = ReviewText.instruction(paths(), "checkout", "fix");

        assertThat(asked.found()).isTrue();
        assertThat(asked.describe()).isEqualTo("fix the flaky test");
    }

    @Test
    void findsItInTheCopyKeptAfterARestartToo() throws IOException {
        task(paths().tasks().taskRecord("porelo-checkout-fix"), "fix the flaky test", "fix");

        assertThat(ReviewText.instruction(paths(), "checkout", "fix").describe())
                .isEqualTo("fix the flaky test");
    }

    @Test
    void saysSoWhenTheTaskWasStartedWithoutAnInstruction() throws IOException {
        task(paths().tasks().containerState("porelo-checkout-fix"), "", "fix");

        assertThat(ReviewText.instruction(paths(), "checkout", "fix").describe())
                .startsWith("none:");
    }

    @Test
    void saysSoWhenTheTaskIsGoneRatherThanShowingNothing() {
        assertThat(ReviewText.instruction(paths(), "checkout", "fix").describe())
                .startsWith("not known:");
    }

    @Test
    void namesTheDangerousFirstAndSaysSoWhenThereIsNone() {
        final String withDanger =
                ReviewText.render(
                        new ReviewText.Instruction(true, "fix it"),
                        List.of(
                                new ReviewRanking.File(
                                        ".github/workflows/ci.yml",
                                        "M",
                                        1,
                                        0,
                                        1,
                                        0,
                                        ReviewRanking.Rank.DANGEROUS,
                                        "a CI definition: it runs with the CI's credentials"),
                                new ReviewRanking.File(
                                        "App.java",
                                        "M",
                                        3,
                                        1,
                                        3,
                                        1,
                                        ReviewRanking.Rank.ORDINARY,
                                        ""),
                                new ReviewRanking.File(
                                        "Big.java",
                                        "M",
                                        400,
                                        400,
                                        0,
                                        0,
                                        ReviewRanking.Rank.REFORMATTING,
                                        "whitespace only")));

        assertThat(withDanger)
                .startsWith(
                        "asked: fix it\n\nREAD FIRST - dangerous by kind, however small:\n"
                                + "  M  .github/workflows/ci.yml  +1 -0  a CI definition");
        assertThat(withDanger.indexOf("App.java")).isLessThan(withDanger.indexOf("Big.java"));
        assertThat(withDanger)
                .contains(
                        "Almost never the finding - at the end of the patch:\n"
                                + "  M  Big.java  +400 -400  whitespace only");

        final String without =
                ReviewText.render(
                        ReviewText.Instruction.GONE,
                        List.of(
                                new ReviewRanking.File(
                                        "App.java",
                                        "M",
                                        3,
                                        1,
                                        3,
                                        1,
                                        ReviewRanking.Rank.ORDINARY,
                                        "")));
        assertThat(without).contains("Nothing dangerous by kind");
    }
}
