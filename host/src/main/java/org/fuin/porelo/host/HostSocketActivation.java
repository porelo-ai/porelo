package org.fuin.porelo.host;

import java.io.IOException;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.util.Map;

/** Adapts systemd's first accepted descriptor to Java's inherited-channel API. */
final class HostSocketActivation {
    private HostSocketActivation() {}

    static void check(Map<String, String> environment, long pid) {
        if (!"1".equals(environment.get("LISTEN_FDS"))
                || !Long.toString(pid).equals(environment.get("LISTEN_PID")))
            throw new HostRefusal(
                    1,
                    "porelo-forge-gate: one accepted socket from this process's socket activation is required");
    }

    static SocketChannel accepted(Map<String, String> environment, String mode) throws IOException {
        check(environment, ProcessHandle.current().pid());
        // Java examines stdin; systemd Accept=yes supplies the connected socket at descriptor 3.
        final Linker linker = Linker.nativeLinker();
        final MethodHandle duplicate =
                linker.downcallHandle(
                        linker.defaultLookup().find("dup2").orElseThrow(),
                        FunctionDescriptor.of(
                                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        try {
            if ((int) duplicate.invokeExact(3, 0) != 0)
                throw new IOException("Cannot inherit accepted socket");
        } catch (Throwable ex) {
            throw new IOException("Cannot inherit accepted socket", ex);
        }
        final var channel = System.inheritedChannel();
        if (!(channel instanceof SocketChannel socket) || !socket.isConnected()) {
            throw new HostRefusal(
                    1, "porelo-forge-gate: socket activation supplied no accepted connection");
        }
        checkFamily(socket, mode);
        return socket;
    }

    static void checkFamily(SocketChannel socket, String mode) throws IOException {
        if (!(socket.getRemoteAddress() instanceof UnixDomainSocketAddress)
                && !mode.equals("git")) {
            throw new HostRefusal(
                    1, "porelo-forge-gate: " + mode + " mode serves a Unix socket alone");
        }
    }
}
