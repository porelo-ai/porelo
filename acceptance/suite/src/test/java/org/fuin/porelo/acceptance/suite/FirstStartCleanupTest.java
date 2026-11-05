package org.fuin.porelo.acceptance.suite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.machines.Ssh;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FirstStartCleanupTest {

    @TempDir Path home;

    @Test
    void setupDoesNotHideAnEarlierFailureBySuccessfullyWritingAnswers() throws IOException {
        Files.createDirectories(home.resolve("data/porelo/ecosystems"));
        final String feature;
        try (var input =
                getClass()
                        .getResourceAsStream(
                                "/org/fuin/porelo/acceptance/project/project-first-start.feature")) {
            feature = new String(java.util.Objects.requireNonNull(input).readAllBytes());
        }
        final String setup = feature.split("\"\"\"")[1].stripIndent();

        final Ssh.Output output = run("git() { return 17; }\n" + setup);

        assertThat(output.status())
                .as("the first failing setup command must determine its exit")
                .isEqualTo(17);
        assertThat(home.resolve("data/porelo/ecosystems/answers.json"))
                .as("setup must stop before publishing answers after a failure")
                .doesNotExist();
    }

    @Test
    void removesOnlyTheAnswersAndCanRunAgainWithNothingToRemove() throws Exception {
        final Path ecosystems = Files.createDirectories(home.resolve("data/porelo/ecosystems"));
        final Path answers = Files.writeString(ecosystems.resolve("answers.json"), "{}");
        final Path executable = Files.writeString(ecosystems.resolve("stub-ecosystem"), "keep");
        final FirstStartCleanup cleanup = new FirstStartCleanup(this::run);

        cleanup.removeAnswers();
        cleanup.removeAnswers();

        assertThat(answers).as("leftover answers would change later scenarios").doesNotExist();
        assertThat(executable).as("the installed plugin must survive cleanup").hasContent("keep");
    }

    @Test
    void reportsACleanupFailureInsteadOfLettingLaterScenariosUseTheAnswers() {
        final FirstStartCleanup cleanup =
                new FirstStartCleanup(command -> new Ssh.Output("", "removal refused", 1));

        assertThatThrownBy(cleanup::removeAnswers)
                .as("an unsuccessful cleanup must fail the scenario")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("removal refused");
    }

    private Ssh.Output run(String command) throws IOException {
        final ProcessBuilder builder = new ProcessBuilder("bash", "-c", command);
        builder.environment().put("HOME", home.toString());
        builder.environment().put("XDG_DATA_HOME", home.resolve("data").toString());
        builder.redirectErrorStream(true);
        final Process process = builder.start();
        try {
            assertThat(process.waitFor(5, TimeUnit.SECONDS)).as("cleanup must finish").isTrue();
            return new Ssh.Output(
                    new String(process.getInputStream().readAllBytes()), "", process.exitValue());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException(ex);
        } finally {
            process.destroyForcibly();
        }
    }
}
