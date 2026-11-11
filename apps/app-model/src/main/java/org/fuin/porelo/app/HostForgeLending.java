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
    static final Path PROGRAM = Path.of("/usr/sbin/porelo-host");

    /**
     * All of the environment a call of the host program is given: the caller's own session (a
     * desktop's bus address, a terminal's variables) is no part of it, as the host program refuses
     * any variable it does not know.
     */
    static final Map<String, String> HOST_ENVIRONMENT =
            Map.of("PATH", "/usr/sbin:/usr/bin:/sbin:/bin", "LC_ALL", "C");

    private static final Pattern ONE_TOKEN = Pattern.compile("\\S+");

    private final PoreloContext context;

    private final GitCredentials otherwise;

    private final Path forgeConf;
    private final java.util.function.BooleanSupplier installed;

    /**
     * Constructor.
     *
     * @param context The machine.
     * @param otherwise What any other address is lent.
     * @param forgeConf Where the host names its forge's port.
     */
    HostForgeLending(
            final PoreloContext context, final GitCredentials otherwise, final Path forgeConf) {
        this(context, otherwise, forgeConf, () -> java.nio.file.Files.isRegularFile(PROGRAM));
    }

    HostForgeLending(
            final PoreloContext context,
            final GitCredentials otherwise,
            final Path forgeConf,
            final java.util.function.BooleanSupplier installed) {
        this.context = context;
        this.otherwise = otherwise;
        this.forgeConf = forgeConf;
        this.installed = installed;
    }

    @Override
    public Lease forPush(String url) {
        return onTheForge(url) ? forUrl(url) : otherwise.forPush(url);
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
        return onTheForge(url, forgeConf);
    }

    static boolean onTheForge(final String url, final Path forgeConf) {
        final String port = HostForgeAddress.port(forgeConf);
        return port != null
                && Pattern.matches("http://127\\.0\\.0\\.1:" + port + "/[^\\s@?#]*", url.strip());
    }

    /**
     * A call of the host program through sudo, in the root directory and its chosen environment.
     *
     * @param program The host program.
     * @param arguments Its arguments.
     * @return The command.
     */
    static Command onHost(final Path program, final String... arguments) {
        final String[] line = new String[arguments.length + 3];
        line[0] = "sudo";
        line[1] = "-n";
        line[2] = program.toString();
        System.arraycopy(arguments, 0, line, 3, arguments.length);
        return Command.of(line).in(Path.of("/")).withChosenEnvironment(HOST_ENVIRONMENT);
    }

    private @Nullable String token() {
        if (!installed.getAsBoolean()) {
            throw new org.fuin.porelo.gate.GateException(
                    "the host's /usr/sbin/porelo-host is missing; install the porelo-host package");
        }
        final CommandResult asked;
        try {
            asked =
                    context.runner()
                            .run(
                                    // A token of its own for this command: a plain forge-token
                                    // replaces every token of the login, a running task's too.
                                    onHost(PROGRAM, "forge", "lend-token").withInput(""));
        } catch (final CommandException ex) {
            return null;
        }
        final String out = asked.standardOutput();
        // One line end a helper may print after the token is no part of it.
        final String token = out.endsWith("\n") ? out.substring(0, out.length() - 1) : out;
        return asked.successful() && ONE_TOKEN.matcher(token).matches() ? token : null;
    }
}
