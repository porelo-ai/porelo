package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Proves the account lock excludes a different JVM, beyond the daemon's threads. */
public class TaskReservationLockTest {
    static TaskReservations reservations(Path root) {
        return new TaskReservations(
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(
                                new XdgPaths(
                                        root.resolve("config"),
                                        root.resolve("data"),
                                        root.resolve("state"),
                                        root.resolve("run"),
                                        root),
                                root.resolve("bin")),
                        arguments -> 0));
    }

    @Test
    void aSecondJvmCannotEnterUntilTheAccountLockIsReleased(@TempDir Path root) throws Exception {
        try (var threads = Executors.newSingleThreadExecutor()) {
            final Process[] child = new Process[1];
            final java.util.concurrent.Future<String>[] entered =
                    new java.util.concurrent.Future[1];
            try {
                reservations(root)
                        .locked(
                                () -> {
                                    child[0] =
                                            new ProcessBuilder(
                                                            Path.of(
                                                                            System.getProperty(
                                                                                    "java.home"),
                                                                            "bin",
                                                                            "java")
                                                                    .toString(),
                                                            "-XX:-UsePerfData",
                                                            "-cp",
                                                            System.getProperty(
                                                                    "surefire.test.class.path",
                                                                    System.getProperty(
                                                                            "java.class.path")),
                                                            TaskReservationLockTest.class.getName(),
                                                            root.toString())
                                                    .redirectError(
                                                            root.resolve("child.err").toFile())
                                                    .start();
                                    final BufferedReader output =
                                            new BufferedReader(
                                                    new InputStreamReader(
                                                            child[0].getInputStream()));
                                    assertThat(output.readLine()).isEqualTo("waiting");
                                    entered[0] = threads.submit(output::readLine);
                                    assertThatThrownBy(
                                                    () ->
                                                            entered[0].get(
                                                                    250, TimeUnit.MILLISECONDS))
                                            .isInstanceOf(TimeoutException.class);
                                    return null;
                                });
                assertThat(entered[0].get(5, TimeUnit.SECONDS)).isEqualTo("entered");
                assertThat(child[0].waitFor(5, TimeUnit.SECONDS)).isTrue();
                assertThat(child[0].exitValue()).isZero();
            } finally {
                if (child[0] != null) child[0].destroyForcibly();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("waiting");
        System.out.flush();
        reservations(Path.of(args[0]))
                .locked(
                        () -> {
                            System.out.println("entered");
                            System.out.flush();
                            return null;
                        });
    }
}
