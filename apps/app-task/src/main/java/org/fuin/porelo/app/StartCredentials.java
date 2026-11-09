package org.fuin.porelo.app;

import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/** The model credential gate, before a start may change the machine. */
final class StartCredentials {
    /** What the vault can say about the credential this start needs. */
    enum State {
        PRESENT,
        MISSING,
        VAULT_LOCKED
    }

    /** A refused start, with the agent and any sign-in still running. */
    record Refusal(String agent, State state, @Nullable String running) {}

    private StartCredentials() {}

    /** Checks the selected model credential, optionally awaiting this agent's sign-in. */
    static @Nullable Refusal check(
            String agent,
            Supplier<State> credential,
            @Nullable AgentSignInRuns signIns,
            boolean waitForSignIn) {
        State state = credential.get();
        if (state == State.PRESENT) {
            return null;
        }
        if (state == State.MISSING && waitForSignIn && signIns != null) {
            if (signIns.running(agent).isPresent()) {
                signIns.await(agent);
            }
            state = credential.get();
        }
        return state == State.PRESENT
                ? null
                : new Refusal(
                        agent,
                        state,
                        state == State.MISSING && signIns != null
                                ? signIns.running(agent).orElse(null)
                                : null);
    }
}
