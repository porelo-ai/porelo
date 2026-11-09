package org.fuin.porelo.host;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Two independent readings before a certificate becomes a durable task record. */
final class HostTaskCheck {
    private final HostFiles files;
    private final ProjectRuntime runtime;

    HostTaskCheck(HostFiles files, ProjectRuntime runtime) {
        this.files = files;
        this.runtime = runtime;
    }

    HostCertificate inspect(String line) throws IOException {
        runtime.require("env", "ssh-keygen", "systemd-run", "porelo");
        final Path directory =
                Files.createTempDirectory(
                        "porelo-host-task.",
                        java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                                java.nio.file.attribute.PosixFilePermissions.fromString(
                                        "rwx------")));
        try {
            final Path certificate =
                    ProjectFiles.stagePrivate(
                            directory.resolve("task-cert.pub"), "certificate.", line + "\n", null);
            final var answer =
                    run(
                            List.of(
                                    runtime.personProgram("env").toString(),
                                    "TZ=UTC",
                                    runtime.personProgram("ssh-keygen").toString(),
                                    "-L",
                                    "-f",
                                    certificate.toString()),
                            new byte[0]);
            if (answer.exitCode() != 0) {
                throw new HostRefusal(
                        65,
                        "ssh-keygen refused the task certificate: its form or signature is bad");
            }
            return HostCertificate.read(answer.output());
        } finally {
            files.removeTree(directory);
        }
    }

    Map<String, @Nullable Object> judge(String login, String task, String line, String key)
            throws IOException {
        final Path directory = files.syncDirectory("task");
        try {
            final Path keys = directory.resolve("keys");
            final Path krl = directory.resolve("revoked.krl");
            final byte[] revocations = files.readBytes(files.path("/etc/ssh/porelo-revoked.krl"));
            try {
                write(keys, (key + "\n").getBytes(StandardCharsets.US_ASCII));
                write(krl, revocations);
            } finally {
                Arrays.fill(revocations, (byte) 0);
            }
            final String unit = "porelo-host-task-check";
            final String credentials = "/run/credentials/" + unit + ".service/";
            final List<String> command =
                    List.of(
                            runtime.personProgram("systemd-run").toString(),
                            "--pipe",
                            "--wait",
                            "--quiet",
                            "--collect",
                            "--unit=" + unit,
                            "-p",
                            "DynamicUser=yes",
                            "-p",
                            "PrivateNetwork=yes",
                            "-p",
                            "ProtectHome=yes",
                            "-p",
                            "LoadCredential=keys:" + keys,
                            "-p",
                            "LoadCredential=revoked.krl:" + krl,
                            runtime.personProgram("porelo").toString(),
                            "task",
                            "check-certificate",
                            "--task",
                            task,
                            "--login",
                            login,
                            "--keys",
                            credentials + "keys",
                            "--krl",
                            credentials + "revoked.krl",
                            "--json");
            final byte[] input = (line + "\n").getBytes(StandardCharsets.US_ASCII);
            final HostPrograms.Result answer;
            try {
                answer = run(command, input);
            } finally {
                Arrays.fill(input, (byte) 0);
            }
            if (answer.exitCode() != 0) {
                throw new HostRefusal(
                        answer.exitCode() == 65 ? 65 : 69, refusalWords(answer, line));
            }
            try {
                return HostJson.object(HostJson.parse(answer.output()));
            } catch (IllegalArgumentException ex) {
                throw new HostRefusal(
                        69, "porelo task check-certificate answered no valid JSON object");
            }
        } finally {
            files.removeTree(directory);
        }
    }

    private HostPrograms.Result run(List<String> argv, byte[] input) {
        try {
            return runtime.run(argv, input, HostPrograms.ENVIRONMENT, Duration.ofSeconds(60));
        } catch (IOException ex) {
            throw new HostRefusal(
                    69,
                    Path.of(argv.getFirst()).getFileName()
                            + " failed to complete ("
                            + HostPrograms.failureKind(ex)
                            + ")");
        }
    }

    private static String refusalWords(HostPrograms.Result answer, String certificate) {
        final String words =
                answer.error()
                        .lines()
                        .map(String::strip)
                        .filter(line -> !line.isEmpty())
                        .map(line -> line.startsWith("porelo: ") ? line.substring(8) : line)
                        .collect(java.util.stream.Collectors.joining("; "));
        // A certificate echo, including an encoded fragment, must not become a diagnostic.
        final String encoded = certificate.split(" ", -1)[1];
        final boolean echoes =
                words.contains(HostCertificate.KIND)
                        || java.util.regex.Pattern.compile("[A-Za-z0-9+/=]{8,}")
                                .matcher(words)
                                .results()
                                .anyMatch(
                                        part ->
                                                encoded.contains(part.group())
                                                        || part.group().contains(encoded));
        if (words.isEmpty() || echoes) {
            return "porelo task check-certificate refused the task certificate (exit "
                    + answer.exitCode()
                    + ")";
        }
        return words.substring(0, Math.min(words.length(), 512)).replaceAll("\\p{Cntrl}", "?");
    }

    private static void write(Path path, byte[] bytes) throws IOException {
        try (var out =
                java.nio.channels.FileChannel.open(
                        path,
                        java.util.Set.of(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW),
                        java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                                java.nio.file.attribute.PosixFilePermissions.fromString(
                                        "rw-------")))) {
            final var buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                out.write(buffer);
            }
        }
    }
}
