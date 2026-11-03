package org.fuin.porelo.app;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.gate.GitCredentials;
import org.jspecify.annotations.Nullable;

/**
 * Lends a person's token for their host's own forge, asked of the host for each command, so that it
 * goes stale in no vault. Only for the port the host's root-owned file names, as anybody on a
 * shared machine may listen on another. Anything else, and a host that gives no token, is what
 * {@code otherwise} lends.
 */
final class HostForgeLending implements GitCredentials {

    /** Where a host names its forge's {@code git_port}. */
    static final Path FORGE_CONF = Path.of("/etc/porelo-host/forge.conf");

    /** Where a host installs the helper; sudo allows it by this path alone. */
    static final Path PROGRAM = Path.of("/usr/local/sbin/porelo-host-person");

    private static final Pattern ONE_TOKEN = Pattern.compile("\\S+");

    private final PoreloContext context;

    private final GitCredentials otherwise;

    private final Path forgeConf;

    /**
     * Constructor.
     *
     * @param context The machine.
     * @param otherwise What any other address is lent.
     * @param forgeConf Where the host names its forge's port.
     */
    HostForgeLending(
            final PoreloContext context, final GitCredentials otherwise, final Path forgeConf) {
        this.context = context;
        this.otherwise = otherwise;
        this.forgeConf = forgeConf;
    }

    @Override
    public Lease forUrl(final String url) {
        if (!onTheForge(url)) {
            return otherwise.forUrl(url);
        }
        final String token = token();
        if (token == null) {
            return otherwise.forUrl(url);
        }
        // Named for this forge alone: git's own settings can rewrite an address after it was
        // checked
        // here, and the header must not follow it.
        final String headerKey =
                "http.http://127.0.0.1:" + HostForgeAddress.port(forgeConf) + "/.extraHeader";
        final String header =
                "Authorization: Basic "
                        + Base64.getEncoder()
                                .encodeToString(
                                        ("x-access-token:" + token)
                                                .getBytes(StandardCharsets.UTF_8));
        return new Lease() {
            @Override
            public Map<String, String> environment() {
                return Map.of(
                        "GIT_CONFIG_COUNT", "2",
                        "GIT_CONFIG_KEY_0", headerKey,
                        "GIT_CONFIG_VALUE_0", header,
                        "GIT_CONFIG_KEY_1", "http.followRedirects",
                        "GIT_CONFIG_VALUE_1", "false",
                        "GIT_ALLOW_PROTOCOL", "http");
            }

            @Override
            public List<String> arguments() {
                return List.of();
            }

            @Override
            public void close() {}
        };
    }

    private boolean onTheForge(final String url) {
        final String port = HostForgeAddress.port(forgeConf);
        return port != null
                && Pattern.matches("http://127\\.0\\.0\\.1:" + port + "/[^\\s@?#]*", url.strip());
    }

    private @Nullable String token() {
        final CommandResult asked;
        try {
            asked =
                    context.runner()
                            .run(
                                    Command.of("sudo", "-n", PROGRAM.toString(), "forge-token")
                                            .withInput(""));
        } catch (final CommandException ex) {
            return null;
        }
        final String out = asked.standardOutput();
        // One line end a helper may print after the token is no part of it.
        final String token = out.endsWith("\n") ? out.substring(0, out.length() - 1) : out;
        return asked.successful() && ONE_TOKEN.matcher(token).matches() ? token : null;
    }
}
