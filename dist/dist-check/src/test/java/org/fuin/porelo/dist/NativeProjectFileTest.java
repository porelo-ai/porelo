package org.fuin.porelo.dist;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the native binary this build wrote on a project file that does not read.
 *
 * <p>The file is read through a YAML model whose classes the native image must hold: one left out
 * of its reflection data reads in every JVM test and fails only here, so it is the binary that is
 * run.
 */
class NativeProjectFileTest {

    @Test
    void refusesAnInvalidProjectFileAndNamesItsLine(@TempDir Path dir) throws Exception {
        final String binary = System.getProperty("porelo.check.binary");
        assertThat(binary).as("porelo.check.binary is set by -Pdist").isNotNull();
        assertThat(Path.of(binary)).as("the native binary").isExecutable();

        // Where a person writes a project of their own, which the binary finds by its name.
        final Path file =
                Files.createDirectories(dir.resolve("config/porelo/projects/demo"))
                        .resolve("project.yml");
        Files.writeString(
                file,
                """
                project:
                  name: demo
                image:
                  from: ubuntu:24.04
                  no_such_key: true
                """);

        final ProcessBuilder builder =
                new ProcessBuilder(binary, "shield", "egress", "-p", "demo")
                        .redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()));
        // A home of its own: the binary reads and writes nothing of the person running the build.
        final Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("PATH", "/usr/bin:/bin");
        environment.put("HOME", dir.resolve("home").toString());
        environment.put("XDG_CONFIG_HOME", dir.resolve("config").toString());
        environment.put("XDG_DATA_HOME", dir.resolve("data").toString());
        environment.put("XDG_STATE_HOME", dir.resolve("state").toString());
        environment.put("XDG_CACHE_HOME", dir.resolve("cache").toString());
        environment.put("XDG_RUNTIME_DIR", dir.resolve("runtime").toString());
        final Path err = dir.resolve("err");
        final Path out = dir.resolve("out");
        final Process process =
                builder.redirectError(err.toFile()).redirectOutput(out.toFile()).start();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("the binary answers").isTrue();

        final String said = Files.readString(err, StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as(said).isEqualTo(2);
        assertThat(said)
                .as("the file, the line and the key")
                .contains(file + ":5")
                .contains("no_such_key");
    }
}
