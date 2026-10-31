package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link TaskSigning}: a task's git signs through its gate, never holding the key. */
class TaskSigningTest {

    private static final String CERTIFICATE = "ssh-ed25519-cert-v01@openssh.com AAAAcert";

    @TempDir Path dir;

    @Test
    void aCertifiedTasksGitSignsEveryCommitThroughTheShimAndAnUncertifiedOnesAsBefore() {
        final Map<String, String> certified =
                TaskSigning.gitEnvironment("http://gate:9418/", "token", CERTIFICATE);

        assertThat(certified)
                .containsEntry("GIT_CONFIG_COUNT", "5")
                .containsEntry("GIT_CONFIG_KEY_0", "http.http://gate:9418/.extraHeader")
                .containsEntry("PORELO_GATE_URL", "http://gate:9418/");
        assertThat(pairs(certified))
                .containsEntry("gpg.format", "ssh")
                .containsEntry("gpg.ssh.program", TaskSigning.SHIM_MOUNT)
                .containsEntry("user.signingkey", "key::" + CERTIFICATE)
                .containsEntry("commit.gpgsign", "true");

        assertThat(TaskSigning.gitEnvironment("http://gate:9418/", "token", null))
                .as("as the gate's credential alone")
                .isEqualTo(TaskWorkspace.gateCredential("http://gate:9418/", "token"));
    }

    @Test
    void theShimSendsAGitPayloadToTheGateAndNothingElse() throws Exception {
        final Path bin = Files.createDirectories(dir.resolve("bin"));
        stub(
                bin,
                "curl",
                "printf '%s\\n' \"$@\" > \"$LOG\"; while [ $# -gt 0 ]; do [ \"$1\" = -o ] && printf 'SIGNED' > \"$2\"; shift; done");
        stub(bin, "git", "echo 'Authorization: Basic abc'");
        stub(bin, "ssh-keygen", "printf 'real %s\\n' \"$*\" > \"$LOG\"");
        final Path shim = dir.resolve("sign");
        Files.writeString(shim, TaskSigning.SHIM);
        Files.writeString(dir.resolve("payload"), "tree 1\n");

        assertThat(
                        run(
                                bin,
                                shim,
                                "-Y",
                                "sign",
                                "-n",
                                "git",
                                "-f",
                                "/tmp/.git_signing_key_tmp",
                                "-U",
                                dir.resolve("payload").toString()))
                .isZero();
        assertThat(Files.readString(dir.resolve("payload.sig"))).isEqualTo("SIGNED");
        assertThat(Files.readString(dir.resolve("log")))
                .contains("Authorization: Basic abc")
                .contains("http://gate:9418/porelo/sign")
                .contains("@" + dir.resolve("payload"));

        Files.delete(dir.resolve("log"));
        assertThat(
                        run(
                                bin,
                                shim,
                                "-Y",
                                "sign",
                                "-n",
                                "file",
                                "-f",
                                "k",
                                dir.resolve("payload").toString()))
                .as("only git's namespace")
                .isNotZero();
        assertThat(dir.resolve("log")).doesNotExist();

        assertThat(
                        run(
                                bin, shim, "-Y", "verify", "-n", "git", "-I", "x", "-f", "allowed",
                                "-s", "sig"))
                .isZero();
        assertThat(Files.readString(dir.resolve("log")))
                .as("verifying is ssh-keygen's own")
                .isEqualTo("real -Y verify -n git -I x -f allowed -s sig\n");
    }

    private int run(Path bin, Path shim, String... arguments) throws Exception {
        final java.util.List<String> command =
                new java.util.ArrayList<>(java.util.List.of("sh", shim.toString()));
        command.addAll(java.util.List.of(arguments));
        final ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("PATH", bin + ":/usr/bin:/bin");
        builder.environment().put("LOG", dir.resolve("log").toString());
        builder.environment().put("PORELO_GATE_URL", "http://gate:9418/");
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        return builder.start().waitFor();
    }

    private static void stub(Path bin, String name, String body) throws Exception {
        final Path file = bin.resolve(name);
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
    }

    private static Map<String, String> pairs(Map<String, String> environment) {
        final Map<String, String> pairs = new java.util.LinkedHashMap<>();
        final int count = Integer.parseInt(environment.get("GIT_CONFIG_COUNT"));
        for (int i = 0; i < count; i++) {
            pairs.put(
                    environment.get("GIT_CONFIG_KEY_" + i),
                    environment.get("GIT_CONFIG_VALUE_" + i));
        }
        return pairs;
    }
}
