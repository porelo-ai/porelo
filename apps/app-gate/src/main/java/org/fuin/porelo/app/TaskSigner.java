package org.fuin.porelo.app;

import java.util.Base64;
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
 * to the person who certified the key.
 */
final class TaskSigner {

    private TaskSigner() {}

    /**
     * Returns the gate's signer.
     *
     * @param signing Reads the task's key and certificate, at each signature: a vault unlocked
     *     after the gate started is read then.
     * @return The signer, always in git's namespace.
     */
    static GitHttpServer.Signer of(Supplier<Optional<TaskSecrets.Signing>> signing) {
        return payload -> {
            final TaskSecrets.Signing held =
                    signing.get()
                            .orElseThrow(
                                    () ->
                                            new GateException(
                                                    "this task's key cannot be read: the vault is"
                                                            + " locked, or the task has no key"));
            final SigningKey key = new SigningKey(Base64.getDecoder().decode(held.seed()), "task");
            return SshSignature.sign(
                    key, SshCertificate.read(held.certificate()).blob(), payload, "git");
        };
    }
}
