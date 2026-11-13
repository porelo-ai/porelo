package org.fuin.porelo.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.wire.SystemProgram;
import org.jspecify.annotations.Nullable;

/** Fences a transport's network mistakes; the account's Unix sockets remain accessible. */
final class TransportFence implements CommandRunner {

    private final CommandRunner runner;
    private final String rules;
    private final Set<String> loopbackPorts;
    private final Function<String, Path> programs;

    /** Uses checked distribution programs, never infrastructure from the caller's PATH. */
    TransportFence(final CommandRunner runner, final List<String> reaches, final boolean offline) {
        this(runner, reaches, offline, SystemProgram::find);
    }

    /** Program resolution is replaceable so unit tests need no namespace tools installed. */
    TransportFence(
            final CommandRunner runner,
            final List<String> reaches,
            final boolean offline,
            final Function<String, Path> programs) {
        this.runner = runner;
        this.rules = rules(reaches, offline);
        this.loopbackPorts = loopbackPorts(reaches, offline);
        this.programs = programs;
    }

    /** Runs only after pasta creates a namespace and nft successfully loads its fence. */
    @Override
    public CommandResult run(final Command verb) {
        return runner.run(command(verb));
    }

    /** Preserves live diagnostics for verbs that report progress. */
    @Override
    public CommandResult run(final Command verb, final Consumer<String> lines) {
        return runner.run(command(verb), lines);
    }

    /** Retains the verb's input, environment, directory and limit when wrapping its arguments. */
    Command command(final Command verb) {
        final List<String> arguments = new ArrayList<>();
        try {
            arguments.add(programs.apply("pasta").toString());
            // Automatic port forwarding could make namespace loopback reach the host's services.
            arguments.addAll(List.of("--config-net", "-t", "none", "-u", "none"));
            for (final String port : loopbackPorts.isEmpty() ? Set.of("none") : loopbackPorts) {
                arguments.addAll(List.of("-T", port));
            }
            arguments.addAll(
                    List.of(
                            "-U",
                            "none",
                            "--map-host-loopback",
                            "none",
                            "--map-guest-addr",
                            "none",
                            "--"));
            arguments.add(programs.apply("sh").toString());
            arguments.addAll(
                    List.of(
                            "-c",
                            script(),
                            "porelo-transport-fence",
                            programs.apply("nft").toString()));
        } catch (final UncheckedIOException ex) {
            throw new CommandException(
                    verb,
                    new IOException(
                            "Cannot fence the transport: "
                                    + ex.getMessage()
                                    + "; install pasta and nftables from the distribution",
                            ex));
        }
        arguments.addAll(verb.arguments());
        final Command chosen = verb.inheritsEnvironment() ? HelperEnvironment.chosen(verb) : verb;
        final Command fenced =
                new Command(
                        arguments,
                        chosen.workingDirectory(),
                        chosen.environment(),
                        chosen.input(),
                        false);
        final byte @Nullable [] binaryInput = chosen.binaryInput();
        final Command withInput =
                binaryInput == null ? fenced : fenced.withBinaryInput(binaryInput);
        final @Nullable Duration timeout = chosen.timeout();
        return timeout == null ? withInput : withInput.withTimeout(timeout);
    }

    private String script() {
        // Only nft's descriptor is redirected: a verb may receive credentials on the original
        // stdin.
        return "\"$1\" -f /dev/stdin <<'PORELO_TRANSPORT_RULES' &&\n"
                + rules
                + "PORELO_TRANSPORT_RULES\n"
                + "{ shift; exec \"$@\"; }\n";
    }

    private static String rules(final List<String> reaches, final boolean offline) {
        final Set<String> allowed = new LinkedHashSet<>();
        if (!offline) {
            for (final String reach : reaches) {
                final String host = host(reach);
                if ("localhost".equals(host)) continue;
                final InetAddress address;
                try {
                    address = InetAddress.ofLiteral(host);
                } catch (final IllegalArgumentException ex) {
                    throw new IllegalArgumentException(
                            "A transport fence requires literal IPv4 or IPv6 reaches; host names"
                                    + " are not supported",
                            ex);
                }
                if (!address.isLoopbackAddress()) {
                    allowed.add(
                            (address.getAddress().length == 4 ? "ip" : "ip6")
                                    + " daddr "
                                    + address.getHostAddress()
                                    + " accept");
                }
            }
        }
        final List<String> lines =
                new ArrayList<>(
                        List.of(
                                "table inet porelo_transport {",
                                "    chain output {",
                                "        type filter hook output priority filter; policy drop;",
                                "        oifname \"lo\" accept"));
        if (allowed.stream().anyMatch(rule -> rule.startsWith("ip6 "))) {
            // Discovery needs multicast destinations, but must not open other IPv6 control traffic.
            lines.addAll(
                    List.of(
                            "        ip6 hoplimit 255 ip6 saddr fe80::/10 icmpv6 type { 133, 134, 135, 136 } accept",
                            "        ip6 hoplimit 255 ip6 daddr fe80::/10 icmpv6 type { 133, 134, 135, 136 } accept"));
        }
        for (final String rule : allowed) lines.add("        " + rule);
        lines.addAll(List.of("    }", "}"));
        return String.join("\n", lines) + "\n";
    }

    private static Set<String> loopbackPorts(final List<String> reaches, final boolean offline) {
        final Set<String> ports = new LinkedHashSet<>();
        for (final String reach : reaches) {
            final String host = host(reach);
            if (!"localhost".equals(host)) {
                final InetAddress address;
                try {
                    address = InetAddress.ofLiteral(host);
                } catch (final IllegalArgumentException ex) {
                    if (offline) continue;
                    throw ex;
                }
                if (!address.isLoopbackAddress()) continue;
            }
            final String suffix =
                    reach.startsWith("[")
                            ? reach.substring(reach.indexOf(']') + 1)
                            : reach.indexOf(':') == reach.lastIndexOf(':') && reach.contains(":")
                                    ? reach.substring(reach.indexOf(':'))
                                    : "";
            if (!suffix.isEmpty())
                ports.add(Integer.toString(Integer.parseInt(suffix.substring(1))));
        }
        return ports;
    }

    /** Reaches name hosts, optionally with ports; nft receives only canonical literal addresses. */
    private static String host(final String reach) {
        if (reach.isEmpty() || !reach.equals(reach.strip()) || reach.contains("%"))
            throw invalidReach();
        if (reach.startsWith("[")) {
            final int close = reach.indexOf(']');
            if (close < 0) throw invalidReach();
            final String rest = reach.substring(close + 1);
            if (!rest.isEmpty()) port(rest);
            return reach.substring(1, close);
        }
        final int colon = reach.indexOf(':');
        if (colon >= 0 && colon == reach.lastIndexOf(':')) {
            port(reach.substring(colon));
            return reach.substring(0, colon);
        }
        return reach;
    }

    private static void port(final String port) {
        if (!port.matches(":[0-9]{1,5}")) throw invalidReach();
        final int number = Integer.parseInt(port.substring(1));
        if (number < 1 || number > 65535) throw invalidReach();
    }

    private static IllegalArgumentException invalidReach() {
        return new IllegalArgumentException(
                "A transport fence requires a literal address with an optional port from 1 to"
                        + " 65535");
    }
}
