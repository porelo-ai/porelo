package org.fuin.porelo.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.fuin.porelo.core.project.Egress;
import org.fuin.porelo.core.project.Limits;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.SecurityClass;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A task image builds with real podman when its first mirror refuses at once.
 *
 * <p>Needs podman and the network, which a unit test may not assume, so it runs only when asked:
 * {@code ./mvnw -s settings.xml -pl runtime -am test -Dtest=TaskImageMirrorBuildTest
 * -Dsurefire.failIfNoSpecifiedTests=false -Dporelo.test.podman.build=true}. It builds on Debian,
 * whose own mirror is the fallback; the image is removed afterwards.
 */
class TaskImageMirrorBuildTest {

    private static final String ENABLED = "porelo.test.podman.build";

    @Test
    void aTaskImageBuildsWhenItsFirstMirrorRefuses(@TempDir Path context) throws Exception {
        assumeTrue(Boolean.getBoolean(ENABLED), "set -D" + ENABLED + "=true to build with podman");

        // Port 9 on the container's own loopback: nothing listens, so the mirror refuses at once.
        final Project project =
                new Project(
                        "mirror-test",
                        "",
                        SecurityClass.GUARDED,
                        "docker.io/library/debian:trixie-slim",
                        null,
                        null,
                        Limits.defaults(),
                        Egress.none(),
                        List.of("http://127.0.0.1:9/debian/"));
        final Path file =
                Files.writeString(context.resolve("Containerfile"), Containerfile.render(project));
        final String tag = "localhost/mirror-build-test-" + ProcessHandle.current().pid();

        try {
            final Process build =
                    new ProcessBuilder(
                                    "podman",
                                    "build",
                                    "--file",
                                    file.toString(),
                                    "--tag",
                                    tag,
                                    context.toString())
                            .redirectErrorStream(true)
                            .start();
            final String output =
                    new String(build.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(build.waitFor(15, TimeUnit.MINUTES)).as("the build ends").isTrue();

            assertThat(build.exitValue()).as("the build passes: %s", output).isZero();
            assertThat(output)
                    .contains(
                            "apt-get update failed; trying the next mirror, http://deb.debian.org/debian");
        } finally {
            new ProcessBuilder("podman", "rmi", "--force", tag)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor(2, TimeUnit.MINUTES);
        }
    }
}
