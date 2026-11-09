package org.fuin.porelo.packagecheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HostInstallTest {

    @TempDir Path directory;

    @Test
    void emitsTheHostMarkersOnlyAfterTheCommandsMeetTheirContracts() throws IOException {
        final String output = probe("same", 64, 78);

        assertThat(output.lines().filter(line -> line.startsWith("HOST-")))
                .as(
                        "the actual shell fragment checks version, both runtime boundaries and the man page")
                .containsExactlyInAnyOrder(
                        "HOST-VERSION", "HOST-NO-RUNTIME-OPTIONS", "HOST-ENV-REFUSED", "HOST-MAN");
    }

    @ParameterizedTest
    @CsvSource({
        "different,64,78,HOST-VERSION",
        "failed,64,78,HOST-VERSION",
        "same,0,78,HOST-NO-RUNTIME-OPTIONS",
        "same,64,0,HOST-ENV-REFUSED",
        "same,64,1,HOST-ENV-REFUSED"
    })
    void withholdsTheMarkerForABrokenHostContract(
            String version, int runtimeExit, int environmentExit, String missing)
            throws IOException {
        // A marker printed unconditionally would pass an unsafe or stale installed binary.
        final String output = probe(version, runtimeExit, environmentExit);

        assertThat(output.lines())
                .as("the failed probe cannot claim success")
                .doesNotContain(missing);
    }

    private String probe(String version, int runtimeExit, int environmentExit) throws IOException {
        executable("porelo", "printf 'porelo same\\n'\n");
        executable("man", "[ \"$1\" = -w ] && [ \"$2\" = porelo-host ]\n");
        executable(
                "porelo-host",
                """
                # DEBIAN_FRONTEND stands for the apt/container environment the probe must clear.
                [ -z "${DEBIAN_FRONTEND+x}" ] || exit 78
                if [ "${JAVA_TOOL_OPTIONS+x}" = x ]; then
                    [ "$JAVA_TOOL_OPTIONS" = -Xmx1k ] || exit 1
                    exit %d
                fi
                if [ "$1" = -Dporelo.probe=1 ] && [ "$2" = --version ]; then
                    exit %d
                fi
                [ "$1" = --version ] || exit 1
                printf 'porelo-host %s\\n'
                exit %d
                """
                        .formatted(
                                environmentExit,
                                runtimeExit,
                                version,
                                version.equals("failed") ? 1 : 0));
        final Commands.Result result =
                Commands.runMerged(
                        List.of(
                                "sh",
                                "-c",
                                "PATH=\"$1:$PATH\"\nexport PATH\nexport DEBIAN_FRONTEND=noninteractive\n"
                                        + Install.HOST,
                                "host-check",
                                directory.toString()));
        assertThat(result.ok()).as("the fragment ran to completion: %s", result.output()).isTrue();
        return result.output();
    }

    private void executable(String name, String body) throws IOException {
        final Path file = Files.writeString(directory.resolve(name), "#!/bin/sh\n" + body);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
    }
}
