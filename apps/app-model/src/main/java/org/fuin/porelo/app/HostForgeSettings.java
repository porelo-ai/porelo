package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.fuin.porelo.wire.RootOwnedFile;
import org.jspecify.annotations.Nullable;

/** The bounded root-owned settings used both to store and to spend a person's forge token. */
public record HostForgeSettings(String name, String account, int accounts, @Nullable String port) {
    /** The host's root-owned forge settings. */
    public static final Path FILE = Path.of("/etc/porelo-host/forge.conf");

    /** Reads the same checked UTF-8 file for every consumer; null means absent or refused. */
    public static @Nullable HostForgeSettings read(Path file, RootOwnedFile.Attributes attributes) {
        final String text;
        try {
            final byte[] bytes = RootOwnedFile.read(file, 64 * 1024, attributes, 0);
            text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
        } catch (IOException | IllegalArgumentException ex) {
            return null;
        }
        String name = "";
        String account = "";
        String port = "";
        int accounts = 0;
        int ports = 0;
        for (String raw : text.split("\n", -1)) {
            final String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
            if (line.startsWith("name=")) name = line.substring(5);
            else if (line.startsWith("account=")) {
                account = line.substring(8);
                accounts++;
            } else if (line.startsWith("git_port=")) {
                port = line.substring(9);
                ports++;
            }
        }
        final boolean usablePort =
                ports == 1 && port.matches("[1-9][0-9]{0,4}") && Integer.parseInt(port) <= 65535;
        return new HostForgeSettings(name, account, accounts, usablePort ? port : null);
    }

    /** Whether the settings name one valid forge account. */
    public boolean validAccount() {
        return accounts == 1 && account.matches("[a-z_][a-z0-9_-]{0,31}");
    }

    /** Whether the name can form the vault entry the token command writes. */
    public boolean validName() {
        return name.matches("[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?");
    }

    /** The entry written by {@code porelo host forge-token}. */
    public String entryName() {
        return name + ".person";
    }
}
