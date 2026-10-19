package org.fuin.porelo.app;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The refusals {@code --passphrase-stdin} shares between {@code vault init} and {@code unlock}. */
final class VaultStdin {

    private VaultStdin() {}

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
