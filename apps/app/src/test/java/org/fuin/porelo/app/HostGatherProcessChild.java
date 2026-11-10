package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** A real process tree for the gathered transfer's shutdown boundary. */
public final class HostGatherProcessChild {
    private HostGatherProcessChild() {}

    public static void main(String[] args) throws Throwable {
        final Path marker = Path.of(args[1]);
        final String javaProgram = Path.of(System.getProperty("java.home"), "bin/java").toString();
        if (args[0].equals("uid")) {
            System.setProperty("porelo.test.real.account.refused", "true");
            final var linker = java.lang.foreign.Linker.nativeLinker();
            final var prctl =
                    linker.downcallHandle(
                            linker.defaultLookup().find("prctl").orElseThrow(),
                            java.lang.foreign.FunctionDescriptor.of(
                                    java.lang.foreign.ValueLayout.JAVA_INT,
                                    java.lang.foreign.ValueLayout.JAVA_INT,
                                    java.lang.foreign.ValueLayout.JAVA_LONG,
                                    java.lang.foreign.ValueLayout.JAVA_LONG,
                                    java.lang.foreign.ValueLayout.JAVA_LONG,
                                    java.lang.foreign.ValueLayout.JAVA_LONG));
            final int result = (int) prctl.invokeExact(4, 0L, 0L, 0L, 0L);
            if (result != 0) throw new IllegalStateException("Cannot set non-dumpable");
            System.out.print(
                    org.fuin.porelo.core.process.SocketPeer.ownUid()
                            + ":"
                            + new HostGatherPushCommand().uid.getAsLong());
        } else if (args[0].startsWith("large-")) {
            final byte[] block = new byte[8192];
            java.util.Arrays.fill(block, (byte) 'a');
            for (int i = 0; i < 1024; i++) System.err.write(block);
            final String token =
                    args[0].equals("large-raw")
                            ? boundaryToken()
                            : java.util.Base64.getEncoder()
                                    .encodeToString(
                                            ("x-access-token:" + boundaryToken())
                                                    .getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8));
            System.err.print(token);
            for (int i = 0; i < 1024; i++) System.err.write(block);
            System.err.flush();
            System.exit(1);
        } else if (args[0].equals("spawn") || args[0].equals("orphan")) {
            final Process grandchild =
                    new ProcessBuilder(
                                    javaProgram,
                                    "-cp",
                                    System.getProperty("java.class.path"),
                                    HostGatherProcessChild.class.getName(),
                                    "idle",
                                    marker.toString())
                            .inheritIO()
                            .start();
            // A reader polls for the marker: it must never see the file before its pids are in it.
            final Path written = marker.resolveSibling(marker.getFileName() + ".tmp");
            Files.writeString(written, ProcessHandle.current().pid() + " " + grandchild.pid());
            Files.move(written, marker, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            if (args[0].equals("orphan")) {
                Thread.sleep(300);
                return;
            }
            Thread.sleep(60000);
        } else if (args[0].equals("idle")) Thread.sleep(60000);
        else {
            final var runner =
                    new HostGatherRunner() {
                        @Override
                        protected Process start(ProcessBuilder builder) throws java.io.IOException {
                            builder.command(
                                    javaProgram,
                                    "-cp",
                                    System.getProperty("java.class.path"),
                                    HostGatherProcessChild.class.getName(),
                                    "spawn",
                                    marker.toString());
                            return super.start(builder);
                        }
                    };
            final var config =
                    new HostGatherConfig(
                            "http://127.0.0.1:3001",
                            "acme",
                            Path.of("/etc/central.pem"),
                            List.of(
                                    new HostGatherConfig.Repository(
                                            "first", "https://central.example/acme/first.git")));
            new HostGatherFlow(runner, Path.of("/usr/bin/git"), Path.of(args[2]))
                    .run(
                            false,
                            config,
                            List.of(),
                            "local-fixture".toCharArray(),
                            "central-fixture".toCharArray(),
                            new PrintWriter(System.out, true),
                            new PrintWriter(System.err, true));
        }
    }

    static String boundaryToken() {
        return "fixture-boundary-" + "credential-fragment-".repeat(2048);
    }
}
