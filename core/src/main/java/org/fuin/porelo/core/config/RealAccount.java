package org.fuin.porelo.core.config;

/**
 * Whether this JVM may reach the account it runs under.
 *
 * <p>A real run always may: nothing but the build sets {@value #REFUSED}. A unit test's JVM may
 * not, so a test that builds a command without a context fails here instead of reaching the
 * person's vault and configuration. Refusing is all this does: it names no other place to read
 * from.
 */
public final class RealAccount {

    /** Set to {@code true} by the build for its unit tests, and nowhere else. */
    public static final String REFUSED = "porelo.test.real.account.refused";

    private RealAccount() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Says whether this JVM is refused the real account.
     *
     * @return {@code true} in a unit test's JVM.
     */
    public static boolean refused() {
        return Boolean.getBoolean(REFUSED);
    }

    /**
     * Returns the failure for reaching the real account from a test.
     *
     * @param what What was reached.
     * @return The failure to throw.
     */
    public static Refused refusal(final String what) {
        return new Refused(what);
    }

    /**
     * A test reached the real account. An {@link Error}, so that no {@code catch} of an exception
     * on the way out can read it as a refusal the command meant, and a test cannot pass over it.
     */
    public static final class Refused extends Error {

        private static final long serialVersionUID = 1L;

        private Refused(final String what) {
            super(
                    "A test reached the real account through "
                            + what
                            + ": give it the test's context (setContext or PoreloFactory). Nothing"
                            + " real was read or written.");
        }
    }
}
