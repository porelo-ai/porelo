package org.fuin.porelo.host;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Reads the real uid from the kernel, independently of the caller's environment. */
final class HostUid {
    private HostUid() {}

    static long real() throws IOException {
        try (InputStream in = Files.newInputStream(Path.of("/proc/self/uid_map"))) {
            if (!initialNamespace(new String(in.readNBytes(65536), StandardCharsets.US_ASCII))) {
                // In a user namespace "uid 0" is the caller itself, and an unmapped owner reads as
                // the overflow uid that every other unmapped owner shares.
                throw new HostRefusal(
                        77, "porelo-host: runs only in the host's own user namespace");
            }
        }
        try (InputStream in = Files.newInputStream(Path.of("/proc/self/status"))) {
            return parse(new String(in.readNBytes(65536), StandardCharsets.US_ASCII));
        }
    }

    /**
     * Whether a {@code uid_map} is the initial user namespace's: every uid mapped to itself.
     *
     * @param uidMap The contents of {@code /proc/self/uid_map}.
     * @return {@code true} for the host's own namespace alone.
     */
    static boolean initialNamespace(String uidMap) {
        final List<String> lines = uidMap.lines().filter(line -> !line.isBlank()).toList();
        return lines.size() == 1
                && List.of(lines.get(0).strip().split("\\s+"))
                        .equals(List.of("0", "0", "4294967295"));
    }

    static long parse(String status) throws IOException {
        for (String line : status.lines().toList()) {
            if (line.startsWith("Uid:")) {
                final String[] fields = line.strip().split("\\s+");
                if (fields.length == 5 && fields[1].matches("[0-9]+")) {
                    try {
                        return Long.parseLong(fields[1]);
                    } catch (NumberFormatException ex) {
                        throw new IOException("Cannot establish the real uid", ex);
                    }
                }
            }
        }
        throw new IOException("Cannot establish the real uid");
    }
}
