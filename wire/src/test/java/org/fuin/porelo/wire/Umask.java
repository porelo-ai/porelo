package org.fuin.porelo.wire;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Runs a piece of a test under a umask it chooses, not the one of whoever runs the suite: a test of
 * what a directory is made with shows its fault only under a umask that leaves the group's write,
 * such as {@code 002}, and passes unseen under {@code 022}.
 *
 * <p>The umask is the process's, so this suits a module whose tests run one after another.
 */
final class Umask {

    private static final MethodHandle UMASK =
            Linker.nativeLinker()
                    .downcallHandle(
                            Linker.nativeLinker().defaultLookup().find("umask").orElseThrow(),
                            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

    /** A piece of a test. */
    @FunctionalInterface
    interface Body {

        /**
         * Runs it.
         *
         * @throws Exception As the test does.
         */
        void run() throws Exception;
    }

    private Umask() {}

    /**
     * Runs the body under the umask given, and puts the one before back after it.
     *
     * @param mask The umask, such as {@code 0002}.
     * @param body What runs under it.
     * @throws Exception What the body throws.
     */
    static void under(final int mask, final Body body) throws Exception {
        final int before = set(mask);
        try {
            body.run();
        } finally {
            set(before);
        }
    }

    private static int set(final int mask) {
        try {
            return (int) UMASK.invokeExact(mask);
        } catch (Throwable ex) {
            throw new IllegalStateException("umask(" + Integer.toOctalString(mask) + ")", ex);
        }
    }
}
