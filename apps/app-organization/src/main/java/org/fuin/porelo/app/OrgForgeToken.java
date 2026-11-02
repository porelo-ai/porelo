package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.core.credential.Credential;
import org.fuin.porelo.organization.git.RegistryOrigin;
import org.fuin.porelo.vault.VaultEntry;
import org.jspecify.annotations.Nullable;

/**
 * The token that asks a forge who owns the organization: this person's, declared for its address in
 * a variable, a file or the vault, or kept in the vault under the forge's own name. Never a plain
 * {@code git.token}, which a machine keeps for another forge and which the address would otherwise
 * send to whatever host it names.
 */
final class OrgForgeToken {

    private static final long FILE_LIMIT = 64 * 1024;

    private OrgForgeToken() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Finds the token.
     *
     * @param context The machine.
     * @param origin The organization's address.
     * @return The token, or {@code null} for none, unset, unreadable or blank.
     */
    static @Nullable String of(final PoreloContext context, final RegistryOrigin origin) {
        // Declared for the https address, where it is sent, however the repository is cloned.
        final Credential found = context.credentialRegistry().forUrl("git", https(origin));
        final Credential declared =
                found != null && found.kind() == Credential.Kind.TOKEN ? found : null;
        final @Nullable String name;
        if (declared == null) {
            name =
                    origin.port() < 0
                            ? GitCredentialNames.impliedName(
                                    GitCredentialNames.Kind.TOKEN, origin.forge().toString())
                            : null;
        } else if (origin.port() >= 0 && !namesPort(declared.match(), origin)) {
            // An address that names a port is another server than the forge's name stands for.
            return null;
        } else {
            name =
                    switch (declared.source()) {
                        case ENVIRONMENT, FILE -> null;
                        case VAULT -> declared.id();
                        case AGENT -> null;
                    };
            if (declared.source() == Credential.Source.ENVIRONMENT) {
                return blankless(context.environment().apply(declared.id()));
            }
            if (declared.source() == Credential.Source.FILE) {
                return blankless(file(Path.of(declared.id())));
            }
        }
        final Map<String, VaultEntry> held = context.readableCredentials().orElseGet(Map::of);
        final VaultEntry entry = name == null ? null : held.get(name);
        return entry == null ? null : blankless(entry.value());
    }

    private static @Nullable String blankless(final @Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String https(final RegistryOrigin origin) {
        final String url = origin.url().replaceAll("/+$", "");
        final String repository = url.substring(url.lastIndexOf('/') + 1);
        return origin.forge() + "/" + origin.organisation() + "/" + repository;
    }

    private static @Nullable String file(final Path path) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > FILE_LIMIT) {
                return null;
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return null;
        }
    }

    /** Whether a declared match names the address's host and port, not the host alone. */
    private static boolean namesPort(final String match, final RegistryOrigin origin) {
        final int scheme = match.indexOf("://");
        if (scheme < 0) {
            return false;
        }
        final String rest = match.substring(scheme + 3);
        final int slash = rest.indexOf('/');
        final String authority = slash < 0 ? rest : rest.substring(0, slash);
        return authority.equalsIgnoreCase(origin.forge().getHost() + ":" + origin.port());
    }
}
