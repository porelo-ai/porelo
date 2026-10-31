package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * Certifies a task's key with the person's key through their own agent, as {@code ssh-keygen -s
 * <their key>.pub -U} does: the private key stays in the agent, and no key file is read.
 */
final class AgentCertifier implements TaskCertification.Certifier {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withZone(ZoneOffset.UTC);

    private final String personKey;

    private final Path agent;

    /**
     * Constructor.
     *
     * @param personKey The person's public key, {@code ssh-ed25519 AAAA...}, which the agent holds.
     * @param agent The agent's socket.
     */
    AgentCertifier(String personKey, Path agent) {
        this.personKey = personKey;
        this.agent = agent;
    }

    @Override
    public String certify(String key, String principal, String keyId, Instant validBefore) {
        final Path dir;
        try {
            dir = Files.createTempDirectory("porelo-certify-");
        } catch (IOException ex) {
            throw new TaskCertification.Refused(
                    "no place to certify the task's key: " + ex.getMessage());
        }
        try {
            final Path ca = dir.resolve("person.pub");
            final Path task = dir.resolve("task.pub");
            Files.writeString(ca, personKey + "\n", StandardCharsets.US_ASCII);
            Files.writeString(task, key + "\n", StandardCharsets.US_ASCII);
            // A minute back, for a clock that is a little behind on the side that checks.
            final String window =
                    WHEN.format(Instant.now().minusSeconds(60))
                            + "Z:"
                            + WHEN.format(validBefore)
                            + "Z";
            final ProcessBuilder builder =
                    new ProcessBuilder(
                                    "ssh-keygen",
                                    "-q",
                                    "-s",
                                    ca.toString(),
                                    "-U",
                                    "-I",
                                    keyId,
                                    "-n",
                                    principal,
                                    "-V",
                                    window,
                                    task.toString())
                            .redirectErrorStream(true);
            builder.environment().put("SSH_AUTH_SOCK", agent.toString());
            final Process process = builder.start();
            final String said =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new TaskCertification.Refused(
                        "the agent did not certify the task's key in time");
            }
            if (process.exitValue() != 0) {
                throw new TaskCertification.Refused(
                        "the agent at "
                                + agent
                                + " did not certify the task's key with "
                                + org.fuin.porelo.vault.PersonCard.fingerprint(personKey)
                                + ": "
                                + said.strip().replaceAll("[\\p{Cc}\\p{Cf}]", "?"));
            }
            return Files.readString(dir.resolve("task-cert.pub"), StandardCharsets.US_ASCII)
                    .strip();
        } catch (IOException ex) {
            throw new TaskCertification.Refused(
                    "the task's key could not be certified: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new TaskCertification.Refused("interrupted while certifying the task's key");
        } finally {
            try (var files = Files.list(dir)) {
                for (final Path file : files.toList()) {
                    Files.deleteIfExists(file);
                }
                Files.deleteIfExists(dir);
            } catch (IOException ex) {
                // Public keys only; nothing secret is left behind.
            }
        }
    }
}
