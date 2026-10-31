package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.PersonCard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link TaskCertificateRecords}: a task key's certificate, found by its fingerprint. */
class TaskCertificateRecordsTest {

    @TempDir Path dir;

    @Test
    void aCertificateIsFoundByItsKeysFingerprintAfterTheTaskIsGone() throws Exception {
        final PoreloContext context = context();
        final String person = keygen("person");
        final TaskCertification.Issued issued =
                TaskCertification.certify(
                        "demo",
                        "fix",
                        "alice",
                        new AgentlessCertifier(dir.resolve("person")),
                        List.of(person),
                        Clock.systemUTC());
        final TaskCertificateRecords records = new TaskCertificateRecords(context);

        records.keep("fix", "alice", issued.certificate());
        final String fingerprint = issued.certificate().fingerprint();
        final Map<String, Object> found = records.find(fingerprint);

        assertThat(found)
                .containsEntry("task", "fix")
                .containsEntry("login", "alice")
                .containsEntry("fingerprint", fingerprint)
                .containsEntry("signed_by", PersonCard.fingerprint(person))
                .containsEntry("key_id", "porelo task fix by alice")
                .containsEntry("principals", List.of("task:demo:fix"))
                .containsEntry("certificate", issued.certificate().line());
        assertThat(found.get("valid_before"))
                .isEqualTo(issued.certificate().validBefore().toString());
        assertThat(records.find("SHA256:" + "A".repeat(43))).as("none kept").isNull();
        assertThat(records.find("../../etc/passwd")).as("not a fingerprint").isNull();
        assertThat(records.find("x")).as("too short to be one").isNull();
        try (var files = Files.list(context.paths().tasks().certificates())) {
            assertThat(files.toList())
                    .singleElement()
                    .satisfies(file -> assertThat(file.getFileName().toString()).endsWith(".json"));
        }
    }

    private PoreloContext context() {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(
                new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    /** Signs with a key file, as {@code ssh-keygen -s} does without an agent. */
    private record AgentlessCertifier(Path ca) implements TaskCertification.Certifier {
        @Override
        public String certify(
                String key, String principal, String keyId, java.time.Instant before) {
            try {
                final Path dir = Files.createTempDirectory(ca.getParent(), "cert-");
                final Path pub = dir.resolve("task.pub");
                Files.writeString(pub, key + "\n");
                final Process process =
                        new ProcessBuilder(
                                        "ssh-keygen",
                                        "-q",
                                        "-s",
                                        ca.toString(),
                                        "-I",
                                        keyId,
                                        "-n",
                                        principal,
                                        "-V",
                                        "+1d",
                                        pub.toString())
                                .redirectErrorStream(true)
                                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                .start();
                assertThat(process.waitFor()).isZero();
                return Files.readString(dir.resolve("task-cert.pub")).strip();
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    private String keygen(String name) throws Exception {
        assertThat(
                        new ProcessBuilder(
                                        "ssh-keygen",
                                        "-q",
                                        "-t",
                                        "ed25519",
                                        "-N",
                                        "",
                                        "-C",
                                        name,
                                        "-f",
                                        dir.resolve(name).toString())
                                .redirectErrorStream(true)
                                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                .start()
                                .waitFor())
                .isZero();
        final String[] fields = Files.readString(dir.resolve(name + ".pub")).strip().split(" ");
        return fields[0] + " " + fields[1];
    }
}
