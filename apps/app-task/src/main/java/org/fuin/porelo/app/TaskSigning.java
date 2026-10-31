package org.fuin.porelo.app;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * How a certified task's git signs its commits: through its gate, which holds the task's key on the
 * host, so the container never holds it.
 *
 * <p>git is told to sign every commit with the task's certificate, by a program of Porelo's in
 * place of {@code ssh-keygen}. For a signature in git's namespace it sends what git would sign to
 * the gate, behind the gate's own token, and writes back the gate's answer where {@code ssh-keygen}
 * would have written it. Anything else - verifying a signature, finding its principals - is {@code
 * ssh-keygen}'s own. A signature in another namespace is refused, so the gate's key signs commits
 * and nothing that could be passed off as something else.
 */
final class TaskSigning {

    /** Where the program is in the container. */
    static final String SHIM_MOUNT = SigningShim.MOUNT;

    /** The program. */
    static final String SHIM = SigningShim.TEXT;

    private TaskSigning() {}

    /**
     * Returns the environment that hands git the gate's token and, for a certified task, has it
     * sign every commit through the gate.
     *
     * @param gate The gate's base URL, ending in a slash.
     * @param token The token the gate and the container share.
     * @param certificate The task's certificate line, or {@code null} for a task without one.
     * @return The variables.
     */
    static Map<String, String> gitEnvironment(
            String gate, String token, @Nullable String certificate) {
        final Map<String, String> credential = TaskWorkspace.gateCredential(gate, token);
        if (certificate == null) {
            return credential;
        }
        final Map<String, String> pairs = new LinkedHashMap<>();
        pairs.put(credential.get("GIT_CONFIG_KEY_0"), credential.get("GIT_CONFIG_VALUE_0"));
        pairs.put("gpg.format", "ssh");
        pairs.put("gpg.ssh.program", SHIM_MOUNT);
        pairs.put("user.signingkey", "key::" + certificate);
        pairs.put("commit.gpgsign", "true");
        final Map<String, String> environment = new LinkedHashMap<>();
        environment.put("GIT_CONFIG_COUNT", String.valueOf(pairs.size()));
        int index = 0;
        for (final Map.Entry<String, String> pair : pairs.entrySet()) {
            environment.put("GIT_CONFIG_KEY_" + index, pair.getKey());
            environment.put("GIT_CONFIG_VALUE_" + index, pair.getValue());
            index++;
        }
        environment.put("PORELO_GATE_URL", gate);
        return Map.copyOf(environment);
    }
}
