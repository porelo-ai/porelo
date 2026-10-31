package org.fuin.porelo.app;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The refusals {@code --passphrase-stdin} shares between {@code vault init} and {@code unlock}. */
final class VaultStdin {

    private VaultStdin() {}

    static @Nullable String shareRefusal(
            final PoreloContext context,
            final boolean share,
            final boolean passphrase,
            final @Nullable String command,
            final @Nullable String credential) {
        if (!share) {
            return refusal(context, passphrase, command, credential);
        }
        if (passphrase || command != null || credential != null) {
            return "--share-stdin is the only source when given; drop the passphrase options";
        }
        return context.input().terminal()
                ? "standard input is a terminal; send the share through a pipe without a terminal"
                : null;
    }

    static byte[] share(final PoreloContext context) {
        return shares(context, 1);
    }

    static byte[] shares(final PoreloContext context, final int count) {
        byte[] share = null;
        try {
            final int length = Keyslots.SHARE_BYTES * count;
            share = context.input().stream().readNBytes(length);
            if (share.length != length || context.input().stream().read() != -1) {
                java.util.Arrays.fill(share, (byte) 0);
                throw new IllegalArgumentException(
                        "standard input must give exactly " + length + " bytes then end of input");
            }
            return share;
        } catch (final java.io.IOException ex) {
            if (share != null) {
                java.util.Arrays.fill(share, (byte) 0);
            }
            throw new org.fuin.porelo.vault.VaultException(
                    "Cannot read the share from standard input", ex);
        }
    }

    /**
     * Says why {@code --passphrase-stdin} cannot be used here, or nothing when it can or was not
     * asked for.
     *
     * @param context Where standard input comes from.
     * @param stdin Whether {@code --passphrase-stdin} was given.
     * @param command The {@code --passphrase-command}, or {@code null}.
     * @param credential The {@code --systemd-credential}, or {@code null}.
     * @return The refusal, or {@code null}.
     */
    static @Nullable String refusal(
            final PoreloContext context,
            final boolean stdin,
            final @Nullable String command,
            final @Nullable String credential) {
        if (!stdin) {
            return null;
        }
        final List<String> beside = new ArrayList<>();
        if (command != null) {
            beside.add("--passphrase-command");
        }
        if (credential != null) {
            beside.add("--systemd-credential");
        }
        if (!beside.isEmpty()) {
            // One source, named by the caller: two would leave which one won to the order of a
            // list.
            return "--passphrase-stdin is the only source when given; drop "
                    + String.join(" and ", beside);
        }
        if (context.input().terminal()) {
            // Typed here it would echo, and land in the terminal's scrollback.
            return "standard input is a terminal; type the passphrase at the prompt instead,"
                    + " without --passphrase-stdin";
        }
        return null;
    }
}
