package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.fuin.porelo.core.credential.Credential;
import org.fuin.porelo.core.credential.CredentialRegistry;
import org.fuin.porelo.organization.gittree.FetchAccess;
import org.fuin.porelo.organization.gittree.ForgeAddress;
import org.fuin.porelo.vault.VaultEntry;
import org.jspecify.annotations.Nullable;

/**
 * What this person holds for reaching their organization: one rule for a fetch, a push and the
 * forge's own questions, whichever command asks.
 *
 * <p><strong>The git of the organization runs with a chosen environment</strong>, so a lease must
 * carry the agent itself: over ssh, a person with no key in the vault has no other way to it.
 */
public final class OrganizationAccess {

    /** The variable naming the agent. */
    public static final String AGENT = "SSH_AUTH_SOCK";

    /** The most a token file may hold, in bytes; anything longer is no token. */
    private static final long FILE_LIMIT = 64 * 1024;

    private OrganizationAccess() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Lends what this person holds for an address, and the agent their environment names.
     *
     * @param context The machine.
     * @return What a fetch or a push is lent.
     */
    public static FetchAccess lent(final PoreloContext context) {
        return lent(context, context.environment().apply(AGENT));
    }

    /**
     * Lends what this person holds for an address, and this agent.
     *
     * @param context The machine.
     * @param socket The agent's socket, or {@code null} or empty for none.
     * @return What a fetch or a push is lent.
     */
    public static FetchAccess lent(final PoreloContext context, final @Nullable String socket) {
        return address -> {
            final FollowCredential credential =
                    FollowCredential.open(context, address, "organization");
            return new FetchAccess.Lease() {
                @Override
                public Map<String, String> environment() {
                    final Map<String, String> environment =
                            new LinkedHashMap<>(credential.environment());
                    if (socket != null && !socket.isEmpty()) {
                        environment.putIfAbsent(AGENT, socket);
                    }
                    // Git runs here in an environment of its own, with nothing of the person's.
                    final String variable = credential.variable();
                    if (variable != null) {
                        final String token = context.environment().apply(variable);
                        if (token != null) {
                            environment.put(variable, token);
                        }
                    }
                    return environment;
                }

                @Override
                public List<String> arguments() {
                    return credential.arguments();
                }

                @Override
                public void close() {
                    credential.close();
                }
            };
        };
    }

    private static final java.util.regex.Pattern SCP_FORM =
            java.util.regex.Pattern.compile("git@(?<host>[^:/@]+):.+");

    private static final java.util.regex.Pattern SSH_FORM =
            java.util.regex.Pattern.compile("ssh://git@(?<host>[^:/@]+)(?<port>:[0-9]+)?/.+");

    private static final java.util.regex.Pattern HTTPS_FORM =
            java.util.regex.Pattern.compile("https://(?<host>[^:/@]+)/(?<path>[^?#]+)");

    private static final java.util.regex.Pattern ASCII_HOST =
            java.util.regex.Pattern.compile("[A-Za-z0-9.-]+");

    private static boolean sameHost(final String one, final String other) {
        return ASCII_HOST.matcher(one).matches()
                && one.toLowerCase(java.util.Locale.ROOT)
                        .equals(other.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Returns a project repository's address in the organization's own form, for its forge.
     *
     * @param organization The organization's address, as organization.yml names it.
     * @param filed The address the organization files for the project.
     * @return The ssh form of {@code filed} when the organization is named over ssh on its host,
     *     else {@code filed}.
     */
    public static String reached(final String organization, final String filed) {
        final java.util.regex.Matcher project = HTTPS_FORM.matcher(filed);
        if (!project.matches()) {
            return filed;
        }
        final String host = project.group("host");
        final String path = project.group("path");
        // Hosts of ASCII alone, compared as ASCII: equalsIgnoreCase takes 'ı' for 'i', which IDNA
        // keeps
        // apart, so a lookalike host would be reached as the organization's own.
        if (!ASCII_HOST.matcher(host).matches()) {
            return filed;
        }
        final java.util.regex.Matcher scp = SCP_FORM.matcher(organization);
        if (scp.matches() && sameHost(scp.group("host"), host)) {
            return "git@" + scp.group("host") + ":" + path;
        }
        final java.util.regex.Matcher ssh = SSH_FORM.matcher(organization);
        if (ssh.matches() && sameHost(ssh.group("host"), host)) {
            final String port = ssh.group("port");
            return "ssh://git@" + ssh.group("host") + (port == null ? "" : port) + "/" + path;
        }
        return filed;
    }

    /**
     * Finds the token the forge is asked with: declared for the https address, where it is sent,
     * however the repository is cloned, or held in the vault under the forge's name.
     *
     * @param context The machine.
     * @param address The organization's address.
     * @return The token, or {@code null} for none, unset, unreadable or blank.
     */
    public static @Nullable String forgeToken(
            final PoreloContext context, final ForgeAddress address) {
        return forgeToken(
                context.readableCredentials().orElseGet(Map::of),
                context.credentialRegistry(),
                context.environment(),
                address);
    }

    /**
     * The token of {@link #forgeToken(PoreloContext, ForgeAddress)}, from what the vault holds. An
     * address that names a port is another server than the one the forge's name stands for: only a
     * token declared for that host and port goes there.
     *
     * @param held What the vault holds.
     * @param registry The credentials declared for destinations.
     * @param environment The person's environment.
     * @param address The organization's address.
     * @return The token, or {@code null}.
     */
    static @Nullable String forgeToken(
            final Map<String, VaultEntry> held,
            final CredentialRegistry registry,
            final UnaryOperator<String> environment,
            final ForgeAddress address) {
        final Credential found = registry.forUrl("git", https(address));
        final Credential declared =
                found != null && found.kind() == Credential.Kind.TOKEN ? found : null;
        final @Nullable String name;
        if (declared == null) {
            name =
                    address.port() < 0
                            ? GitCredentialNames.impliedName(
                                    GitCredentialNames.Kind.TOKEN, address.forge().toString())
                            : null;
        } else if (address.port() >= 0 && !namesPort(declared.match(), address)) {
            return null;
        } else {
            if (declared.source() == Credential.Source.ENVIRONMENT) {
                return blankless(environment.apply(declared.id()));
            }
            if (declared.source() == Credential.Source.FILE) {
                return blankless(file(Path.of(declared.id())));
            }
            name = declared.source() == Credential.Source.VAULT ? declared.id() : null;
        }
        final VaultEntry entry = name == null ? null : held.get(name);
        return entry == null ? null : blankless(entry.value());
    }

    /**
     * Returns how the forge is asked: what the account trusts, and no redirect.
     *
     * @param context The machine.
     * @return The client.
     */
    public static java.net.http.HttpClient http(final PoreloContext context) {
        return org.fuin.porelo.core.net.TrustedCertificates.builder(
                        org.fuin.porelo.core.net.TrustedCertificates.file(context.paths().xdg()))
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .build();
    }

    private static @Nullable String blankless(final @Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String https(final ForgeAddress address) {
        final String url = address.url().replaceAll("/+$", "");
        final String repository = url.substring(url.lastIndexOf('/') + 1);
        return address.forge() + "/" + address.organization() + "/" + repository;
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
    private static boolean namesPort(final String match, final ForgeAddress address) {
        final int scheme = match.indexOf("://");
        if (scheme < 0) {
            return false;
        }
        final String rest = match.substring(scheme + 3);
        final int slash = rest.indexOf('/');
        final String authority = slash < 0 ? rest : rest.substring(0, slash);
        return authority.equalsIgnoreCase(address.forge().getHost() + ":" + address.port());
    }
}
