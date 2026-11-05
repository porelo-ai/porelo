package org.fuin.porelo.core.hardening;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Makes what this process and its children create readable by this account alone, whatever umask
 * the shell or the service manager gave it.
 *
 * <p>One call at the start of a program, rather than a mode at each of the places that write: the
 * mode of a mirror, a registry entry or a checkout was the shell's umask because nobody chose one,
 * and a list of writers is exactly what the next writer is missing from. It covers git and tar too,
 * which no argument of ours reaches. What must be readable by another uid, a task's mounts and what
 * a build copies into an image, says its mode where it is written; that was already so, because the
 * umask could be anything.
 */
public final class OwnerOnlyFiles {

    private static final int GROUP_AND_OTHERS = 0077;

    private OwnerOnlyFiles() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Sets the process's umask. It cannot fail once called, and it holds for the whole process and
     * every child started after it, so it belongs before the first thing that writes or starts one.
     *
     * @throws HardeningException If the C library does not offer it.
     */
    public static void fromNowOn() {
        umask(GROUP_AND_OTHERS);
    }

    /**
     * Sets the umask.
     *
     * @param mask The new one.
     * @return The one before it.
     * @throws HardeningException If the C library does not offer it.
     */
    static int umask(final int mask) {
        final Linker linker = Linker.nativeLinker();
        final MethodHandle umask =
                linker.downcallHandle(
                        linker.defaultLookup()
                                .find("umask")
                                .orElseThrow(
                                        () ->
                                                new HardeningException(
                                                        "Symbol 'umask' not found in libc")),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        try {
            return (int) umask.invokeExact(mask);
        } catch (Throwable ex) {
            throw new HardeningException("umask could not be called", ex);
        }
    }
}
