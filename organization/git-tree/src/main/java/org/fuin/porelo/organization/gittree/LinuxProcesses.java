package org.fuin.porelo.organization.gittree;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Identifies a Linux process across PID reuse and machine restarts. */
public final class LinuxProcesses {
    private static final Path BOOT = Path.of("/proc/sys/kernel/random/boot_id");
    private static final String UUID = "[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}";
    private final Reader reader;

    /**
     * The process identity published by a live host follow.
     *
     * @param pid The Linux process ID.
     * @param start Start ticks since boot from field 22 of its stat file.
     * @param boot The machine boot UUID.
     */
    public record Identity(long pid, long start, String boot) {
        /** Refuses identities that cannot come from Linux's process files. */
        public Identity {
            if (pid < 1 || pid > 4194304 || start < 0 || !boot.matches(UUID))
                throw new IllegalArgumentException("Invalid Linux process identity");
        }
    }

    /** Reads one process file; tests substitute fixed answers or read failures. */
    @FunctionalInterface
    public interface Reader {
        /** Reads the named process file, or reports why it could not be read. */
        String read(Path path) throws IOException;
    }

    /** Reads this machine's process files through bounded streams, including in native images. */
    public LinuxProcesses() {
        this(LinuxProcesses::read);
    }

    /** Uses the supplied process-file reader. */
    public LinuxProcesses(Reader reader) {
        this.reader = reader;
    }

    /** Captures this process's PID, start ticks and boot identity. */
    public Identity current() throws IOException {
        final long pid = ProcessHandle.current().pid();
        return new Identity(pid, start(reader.read(Path.of("/proc/self/stat")), pid), boot());
    }

    /** Refuses a missing, hidden, terminated or different process, without using wall-clock age. */
    public void verify(Identity identity) throws IOException {
        if (!boot().equals(identity.boot()))
            throw new IOException("the follow belongs to another boot");
        final long start =
                start(reader.read(Path.of("/proc/" + identity.pid() + "/stat")), identity.pid());
        if (start != identity.start())
            throw new IOException("the follow PID has a different start time");
    }

    private String boot() throws IOException {
        final String boot = reader.read(BOOT).strip();
        if (!boot.matches(UUID)) throw new IOException("the machine boot identity is malformed");
        return boot;
    }

    private static long start(String stat, long pid) throws IOException {
        final int begin = stat.indexOf('('), end = stat.lastIndexOf(')');
        if (begin < 1
                || end <= begin
                || !stat.substring(0, begin).strip().equals(Long.toString(pid)))
            throw new IOException("the follow process stat is malformed");
        final String[] fields = stat.substring(end + 1).strip().split("\\s+");
        if (fields.length < 20
                || !fields[0].matches("[RSDZTtWXxKIP]")
                || !fields[19].matches("[0-9]+"))
            throw new IOException("the follow process stat is malformed");
        if (fields[0].matches("[ZXx]")) throw new IOException("the follow process has terminated");
        try {
            return Long.parseLong(fields[19]);
        } catch (NumberFormatException ex) {
            throw new IOException("the follow process start time is malformed", ex);
        }
    }

    private static String read(Path path) throws IOException {
        // Proc files report size zero, which Files.readString can truncate in a native image.
        try (var input = Files.newInputStream(path)) {
            final byte[] bytes = input.readNBytes(65537);
            if (bytes.length > 65536) throw new IOException("the process file is too large");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
