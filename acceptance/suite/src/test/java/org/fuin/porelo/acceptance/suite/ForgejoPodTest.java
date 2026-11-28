package org.fuin.porelo.acceptance.suite;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Checks the fixture's orchestration with recorded command answers, without podman or sockets. */
class ForgejoPodTest {

    private static final String TOKEN = "not-a-real-forgejo-token";

    @TempDir Path dir;

    @Test
    void startsHttpsWithTheIpSanAndPrivateValuesWithoutPrintingSecrets() throws Exception {
        prepare();
        final Result result = run(dir.resolve("instance").toString());

        assertThat(result.status()).as(result.output()).isZero();
        assertThat(result.output())
                .contains(
                        "PORELO_FORGEJO_URL:",
                        "PORELO_FORGEJO_TEST_TOKEN:",
                        "PORELO_FORGEJO_TEST_OWNER:",
                        "PORELO_FORGEJO_CA:")
                .doesNotContain(TOKEN, "test-private-key");
        final Path instance = dir.resolve("instance");
        assertThat(instance.resolve("token")).hasContent(TOKEN + "\n");
        for (final String file :
                new String[] {"url", "token", "owner", "ca.pem", "tls/server.key"}) {
            assertThat(Files.getPosixFilePermissions(instance.resolve(file)))
                    .as("%s permissions", file)
                    .isEqualTo(PosixFilePermissions.fromString("rw-------"));
        }
        assertThat(Files.readString(instance.resolve("data/gitea/conf/app.ini")))
                .contains(
                        "PROTOCOL = https",
                        "CERT_FILE = /tls/server.pem",
                        "KEY_FILE = /tls/server.key",
                        "ROOT_URL = https://127.0.0.1:3443/",
                        "DB_TYPE = sqlite3",
                        "INSTALL_LOCK = true");
        assertThat(Files.readString(dir.resolve("commands")))
                .contains(
                        "--publish 127.0.0.1:3443:3000",
                        "--pull=never",
                        "admin user create",
                        "--scopes write:repository,write:issue,write:user",
                        "subjectAltName=IP:127.0.0.1")
                .doesNotContain(TOKEN);
        assertThat(instance.resolve("ca.key")).doesNotExist();
    }

    @Test
    void aMissingImageLoadsOnlyThePreinstalledArchive() throws Exception {
        prepare();
        Files.writeString(dir.resolve("missing-image"), "");

        final Result result = run(dir.resolve("instance").toString());

        assertThat(result.status()).as(result.output()).isZero();
        assertThat(Files.readString(dir.resolve("commands")))
                .contains("load -i /opt/images/forgejo-17.0.0.tar")
                .doesNotContain("pull ");
    }

    @Test
    void removeDeletesTheRecordedContainerAndItsDirectory() throws Exception {
        prepare();
        final Path instance = dir.resolve("instance");
        assertThat(run(instance.toString()).status()).isZero();

        final Result result = run("--remove", instance.toString());

        assertThat(result.status()).as(result.output()).isZero();
        assertThat(Files.readString(dir.resolve("commands")))
                .contains("rm --force porelo-forgejo-012345abcdef");
        assertThat(instance).doesNotExist();
    }

    @Test
    void aFailedTokenGeneratorFailsWithoutPrintingItsOutput() throws Exception {
        prepare();
        Files.writeString(dir.resolve("fail-token"), "");

        final Result result = run(dir.resolve("instance").toString());

        assertThat(result.status()).isNotZero();
        assertThat(result.output())
                .contains("cannot generate the Forgejo test token")
                .doesNotContain(TOKEN);
    }

    private void prepare() throws Exception {
        final Path bin = Files.createDirectory(dir.resolve("bin"));
        command(
                bin.resolve("podman"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                printf '%s\\n' "$*" >> "$FIXTURE_TEST_ROOT/commands"
                case "$1 $2" in
                    'image exists')
                        if [[ -f $FIXTURE_TEST_ROOT/missing-image ]]; then exit 1; fi ;;
                    'load -i') rm "$FIXTURE_TEST_ROOT/missing-image" ;;
                    'container exists') exit 0 ;;
                    'inspect --format') cat "$FIXTURE_TEST_ROOT/instance-path" ;;
                    'exec --user')
                        if [[ $* == *generate-access-token* ]]; then
                            printf '%s\\n' 'not-a-real-forgejo-token'
                            [[ ! -f $FIXTURE_TEST_ROOT/fail-token ]]
                        fi ;;
                esac
                """);
        command(
                bin.resolve("openssl"),
                """
                #!/usr/bin/env bash
                set -euo pipefail
                printf '%s\\n' "$*" >> "$FIXTURE_TEST_ROOT/commands"
                if [[ $1 == rand ]]; then printf '%s\\n' 012345abcdef; exit 0; fi
                for ((i=1; i<=$#; i++)); do
                    argument=${!i}
                    if [[ $argument == -keyout || $argument == -out ]]; then
                        ((i+=1))
                        printf '%s\\n' test-private-key > "${!i}"
                    fi
                    if [[ $argument == -extfile ]]; then
                        ((i+=1))
                        cat "${!i}" >> "$FIXTURE_TEST_ROOT/commands"
                    fi
                    if [[ $argument == -CAcreateserial ]]; then
                        touch "$FIXTURE_TEST_ROOT/instance/ca.srl"
                    fi
                done
                """);
        command(bin.resolve("curl"), "#!/usr/bin/env bash\nexit 0\n");
        try (InputStream resource =
                ForgejoPodTest.class.getResourceAsStream(
                        "/org/fuin/porelo/acceptance/forgejo/forgejo-pod.sh")) {
            Files.copy(
                    Objects.requireNonNull(resource, "fixture script"),
                    dir.resolve("forgejo-pod.sh"));
        }
        Files.writeString(dir.resolve("instance-path"), dir.resolve("instance").toString());
    }

    private static void command(Path path, String text) throws Exception {
        Files.writeString(path, text);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
    }

    private Result run(String... arguments) throws Exception {
        final List<String> command =
                new ArrayList<>(List.of("bash", dir.resolve("forgejo-pod.sh").toString()));
        command.addAll(List.of(arguments));
        final ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("FIXTURE_TEST_ROOT", dir.toString());
        builder.environment().put("PATH", dir.resolve("bin") + ":" + System.getenv("PATH"));
        final Process process = builder.start();
        process.getOutputStream().close();
        try {
            assertThat(process.waitFor(10, TimeUnit.SECONDS))
                    .as("fixture script finishes")
                    .isTrue();
            return new Result(
                    process.exitValue(),
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private record Result(int status, String output) {}
}
