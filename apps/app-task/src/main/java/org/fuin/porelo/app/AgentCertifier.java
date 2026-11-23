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

    private final String program;

    private final org.fuin.porelo.wire.LimitFactors limits;

    /**
     * How far back a certificate starts, for a clock a little behind on the side that checks. A
     * protocol value, no time limit: that side runs under another account, so no factor scales it.
     */
    static final java.time.Duration BACKDATE = java.time.Duration.ofSeconds(60);

    /** How long the agent may take to certify. */
    static final java.time.Duration PATIENCE = java.time.Duration.ofSeconds(60);

    /**
     * Constructor.
     *
     * @param personKey The person's public key, {@code ssh-ed25519 AAAA...}, which the agent holds.
     * @param agent The agent's socket.
     * @param limits The account's time factors.
     */
    AgentCertifier(String personKey, Path agent, org.fuin.porelo.wire.LimitFactors limits) {
        this(personKey, agent, "ssh-keygen", limits);
    }

    /**
     * Constructor naming the program; a test has an older one stand in.
     *
     * @param personKey The person's public key, which certifies.
     * @param agent The agent's socket, which holds its private half.
     * @param program What signs, as {@code ssh-keygen} does.
     * @param limits The account's time factors.
     */
    AgentCertifier(
            String personKey,
            Path agent,
            String program,
            org.fuin.porelo.wire.LimitFactors limits) {
        this.personKey = personKey;
        this.agent = agent;
        this.program = program;
        this.limits = limits;
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
            // Back a while, for a clock that is a little behind on the side that checks.
            // Both in UTC by the environment: a time ending in Z is refused before OpenSSH 9.1.
            final String window =
                    WHEN.format(Instant.now().minus(BACKDATE)) + ":" + WHEN.format(validBefore);
            final ProcessBuilder builder =
                    new ProcessBuilder(
                                    program,
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
            builder.environment().put("TZ", "UTC");
            final Process process = builder.start();
            final String said =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(limits.machines(PATIENCE).toMillis(), TimeUnit.MILLISECONDS)) {
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
