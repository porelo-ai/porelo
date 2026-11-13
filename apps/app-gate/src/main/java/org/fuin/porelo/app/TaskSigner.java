package org.fuin.porelo.app;

import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.fuin.porelo.gate.GateException;
import org.fuin.porelo.gate.GitHttpServer;
import org.fuin.porelo.vault.SigningKey;
import org.fuin.porelo.vault.SshCertificate;
import org.fuin.porelo.vault.SshSignature;

/**
 * Signs a task's commits at its gate, with the task's key through its certificate: what {@code
 * ssh-keygen -Y sign -n git -f key-cert.pub} would write, so {@code ssh-keygen -Y verify} traces it
 * to the person who certified the key. A certificate that names another task signs nothing.
 */
final class TaskSigner {

    private TaskSigner() {}

    /**
     * Returns the gate's signer.
     *
     * @param project The task's project.
     * @param task The task.
     * @param signing Reads the task's key and certificate, at each signature: a vault unlocked
     *     after the gate started is read then.
     * @return The signer, always in git's namespace.
     */
    static GitHttpServer.Signer of(
            String project, String task, Supplier<Optional<TaskSecrets.Signing>> signing) {
        final String principal = "task:" + project + ":" + task;
        return payload -> {
            final TaskSecrets.Signing held =
                    signing.get()
                            .orElseThrow(
                                    () ->
                                            new GateException(
                                                    "this task's key cannot be read: the vault is"
                                                            + " locked, or the task has no key"));
            final SshCertificate certificate = SshCertificate.read(held.certificate());
            // As a task's certificate is issued: naming this task alone, or it signs nothing.
            if (!certificate.principals().equals(List.of(principal))) {
                throw new GateException(
                        "this task's certificate names "
                                + String.join(", ", certificate.principals())
                                + ", not "
                                + principal
                                + ": nothing is signed");
            }
            final SigningKey key = new SigningKey(Base64.getDecoder().decode(held.seed()), "task");
            return SshSignature.sign(key, certificate.blob(), payload, "git");
        };
    }
}
