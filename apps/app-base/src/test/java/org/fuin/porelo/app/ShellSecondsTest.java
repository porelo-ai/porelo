package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Tests for {@link ShellSeconds}: a limit written for a program that takes seconds. */
class ShellSecondsTest {

    @ParameterizedTest
    @CsvSource({
        "60000, 60",
        "100000, 100",
        "600000, 600",
        "500, 0.5",
        "1500, 1.5",
        "100, 0.1",
        "1, 0.001",
        "30000, 30",
        "0, 0"
    })
    void decimalWritesThePlainSecondsWithNoExponentAndNoTrailingZeros(
            final long millis, final String expected) {
        assertThat(ShellSeconds.decimal(Duration.ofMillis(millis))).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "60000, 60",
        "1000, 1",
        "1001, 2",
        "1500, 2",
        "100, 1",
        "1, 1",
        "0, 1",
        "100000, 100"
    })
    void wholeRoundsUpToWholeSecondsAndNeverBelowOne(final long millis, final String expected) {
        assertThat(ShellSeconds.whole(Duration.ofMillis(millis))).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"1000, sleep 1", "300000, sleep 300", "2000, sleep 2"})
    void aWholeSleepIsWrittenAsItIs(final long millis, final String expected) {
        assertThat(ShellSeconds.sleep(Duration.ofMillis(millis))).isEqualTo(expected);
    }

    @Test
    void aFractionalSleepFallsBackToWholeSecondsWhereTheShellsSleepRefusesAFraction(
            @TempDir final Path dir) throws Exception {

        // A busybox sleep may take no fraction: the line ran "sleep 0.5", which failed at once,
        // and the loop around it spun. Where the fraction is refused, whole seconds rounded up.
        final Path bin = Files.createDirectories(dir.resolve("bin"));
        final Path asked = dir.resolve("asked");
        Files.writeString(
                bin.resolve("sleep"),
                "#!/bin/sh\necho \"$1\" >> "
                        + asked
                        + "\ncase \"$1\" in *.*) echo 'sleep: invalid number' >&2; exit 1 ;; esac\n");
        bin.resolve("sleep").toFile().setExecutable(true);
        final String line = ShellSeconds.sleep(Duration.ofMillis(1500));

        final ProcessBuilder builder =
                new ProcessBuilder("sh", "-c", line + " && echo slept").redirectErrorStream(true);
        builder.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        final Process process = builder.start();
        final String said = new String(process.getInputStream().readAllBytes());

        assertThat(process.waitFor()).isZero();
        assertThat(said).as("the refusal is not shown").isEqualTo("slept\n");
        assertThat(Files.readAllLines(asked)).containsExactly("1.5", "2");
    }
}
