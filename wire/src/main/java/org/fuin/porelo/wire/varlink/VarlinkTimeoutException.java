package org.fuin.porelo.wire.varlink;

import java.time.Duration;

/**
 * A peer accepted a call and gave no answer within the call's deadline.
 *
 * <p>Carries no error name, since the peer sent none: to a caller that tells a refusal from a
 * failed connection by the name, a call that timed out is a failed connection.
 */
public class VarlinkTimeoutException extends VarlinkException {

    private static final long serialVersionUID = 1L;

    private final String method;

    private final Duration deadline;

    /**
     * Constructor.
     *
     * @param method The method called.
     * @param deadline How long the answer was waited for.
     */
    public VarlinkTimeoutException(String method, Duration deadline) {
        super(method + " got no answer within " + shown(deadline));
        this.method = method;
        this.deadline = deadline;
    }

    /**
     * Returns the method that got no answer.
     *
     * @return Fully qualified method name.
     */
    public String getMethod() {
        return method;
    }

    /**
     * Returns how long the answer was waited for.
     *
     * @return The deadline.
     */
    public Duration getDeadline() {
        return deadline;
    }

    private static String shown(Duration deadline) {
        return deadline.toMillis() % 1000 == 0
                ? deadline.toSeconds() + " seconds"
                : deadline.toMillis() + " ms";
    }
}
