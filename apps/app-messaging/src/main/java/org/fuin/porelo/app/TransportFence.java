package org.fuin.porelo.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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

    /** Resolves on the host before a verb starts; tests supply answers without using DNS. */
    @FunctionalInterface
    interface Resolver {
        /** Returns this lookup's addresses, or throws when the host cannot be resolved. */
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final CommandRunner runner;
    private final List<String> reaches;
    private final boolean offline;
    private final Function<String, Path> programs;
    private final Resolver resolver;
    private final Path resolvConf;

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
        this(
                runner,
                reaches,
                offline,
                programs,
                InetAddress::getAllByName,
                Path.of("/etc/resolv.conf"));
    }

    /** Takes both host DNS inputs explicitly so tests never consult the real machine. */
    TransportFence(
            final CommandRunner runner,
            final List<String> reaches,
            final boolean offline,
            final Function<String, Path> programs,
            final Resolver resolver,
            final Path resolvConf) {
        this.runner = runner;
        this.reaches = List.copyOf(reaches);
        this.offline = offline;
        this.programs = programs;
        this.resolver = resolver;
        this.resolvConf = resolvConf;
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
        final List<Reach> parsed = reaches.stream().map(TransportFence::reach).toList();
        for (final Reach reach : parsed) {
            final @Nullable InetAddress literal = reach.literal();
            if (literal != null && unreachable(literal)) throw cannotBeReached(reach.host());
        }
        final Map<String, List<InetAddress>> resolved = new LinkedHashMap<>();
        for (final Reach reach : parsed) {
            resolved.computeIfAbsent(reach.host(), host -> addresses(reach));
        }
        final List<InetAddress> nameservers =
                !offline && parsed.stream().anyMatch(reach -> reach.literal() == null)
                        ? nameservers()
                        : List.of();
        final boolean loopbackDns =
                !nameservers.isEmpty()
                        && nameservers.stream().allMatch(InetAddress::isLoopbackAddress);
        final Set<String> ports = loopbackPorts(parsed, resolved);
        if (loopbackDns) ports.add("53");
        final String rules = rules(resolved, nameservers);
        final List<String> arguments = new ArrayList<>();
        try {
            arguments.add(programs.apply("pasta").toString());
            // Automatic port forwarding could make namespace loopback reach the host's services.
            arguments.addAll(List.of("--config-net", "-t", "none", "-u", "none"));
            for (final String port : ports.isEmpty() ? Set.of("none") : ports) {
                arguments.addAll(List.of("-T", port));
            }
            arguments.addAll(
                    List.of(
                            "-U",
                            loopbackDns ? "53" : "none",
                            "--map-host-loopback",
                            "none",
                            "--map-guest-addr",
                            "none",
                            "--"));
            arguments.add(programs.apply("sh").toString());
            arguments.addAll(
                    List.of(
                            "-c",
                            script(rules),
                            "porelo-transport-fence",
                            programs.apply("nft").toString()));
            // pasta runs the verb as root of its user namespace with every capability, and
            // CAP_NET_ADMIN there flushes the ruleset just loaded: the verb holds none, and an
            // exec cannot give it one back.
            arguments.addAll(
                    List.of(
                            programs.apply("setpriv").toString(),
                            "--bounding-set=-all",
                            "--inh-caps=-all",
                            "--ambient-caps=-all",
                            "--no-new-privs",
                            "--"));
        } catch (final UncheckedIOException ex) {
            throw new CommandException(
                    verb,
                    new IOException(
                            "Cannot fence the transport: "
                                    + ex.getMessage()
                                    + "; install pasta, nftables and util-linux's setpriv from"
                                    + " the distribution",
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

    private static String script(final String rules) {
        // Only nft's descriptor is redirected: a verb may receive credentials on the original
        // stdin.
        return "\"$1\" -f /dev/stdin <<'PORELO_TRANSPORT_RULES' &&\n"
                + rules
                + "PORELO_TRANSPORT_RULES\n"
                + "{ shift; exec \"$@\"; }\n";
    }

    private List<InetAddress> addresses(final Reach reach) {
        final @Nullable InetAddress literal = reach.literal();
        if (literal != null) return List.of(literal);
        if (offline) return List.of();
        final InetAddress[] addresses;
        try {
            addresses = resolver.resolve(reach.host());
        } catch (final UnknownHostException ex) {
            throw unresolved(reach.host(), ex);
        }
        if (addresses.length == 0) throw unresolved(reach.host(), null);
        for (final InetAddress address : addresses) {
            if (address.getHostAddress().contains("%"))
                throw new IllegalArgumentException(
                        "A transport reach resolved to a scoped address");
            // A name's answer is the transport's or its resolver's to give: loopback would map a
            // host port no literal reach named.
            if (address.isLoopbackAddress() || unreachable(address))
                throw cannotBeReached(reach.host() + " (" + address.getHostAddress() + ")");
        }
        return List.of(addresses);
    }

    /** An address no reach may open: every host's, a group's, a link's own, or everyone's. */
    private static boolean unreachable(final InetAddress address) {
        return address.isAnyLocalAddress()
                || address.isMulticastAddress()
                || address.isLinkLocalAddress()
                || "255.255.255.255".equals(address.getHostAddress());
    }

    private static IllegalArgumentException cannotBeReached(final String reach) {
        return new IllegalArgumentException(
                "Cannot fence the transport: reach '"
                        + reach
                        + "' cannot be reached through a fence - an unspecified, multicast,"
                        + " broadcast or link-local address, or a name answering loopback");
    }

    private static IllegalArgumentException unresolved(
            final String host, final @Nullable Throwable cause) {
        return new IllegalArgumentException(
                "Cannot fence the transport: reach '" + host + "' resolves to no addresses", cause);
    }

    private List<InetAddress> nameservers() {
        final Set<InetAddress> found = new LinkedHashSet<>();
        try {
            for (final String line : Files.readAllLines(resolvConf)) {
                final String[] fields = line.split("#", 2)[0].strip().split("\\s+");
                if (!"nameserver".equals(fields[0])) continue;
                if (fields.length != 2 || fields[1].contains("%")) throw invalidNameserver();
                final InetAddress address;
                try {
                    address = InetAddress.ofLiteral(fields[1]);
                } catch (final IllegalArgumentException ex) {
                    throw invalidNameserver(ex);
                }
                if (address.isAnyLocalAddress() || address.isMulticastAddress())
                    throw invalidNameserver();
                found.add(address);
            }
        } catch (final IOException ex) {
            throw new UncheckedIOException(
                    "Cannot read the transport's resolver configuration", ex);
        }
        if (found.isEmpty()) throw invalidNameserver();
        return List.copyOf(found);
    }

    private static IllegalArgumentException invalidNameserver() {
        return invalidNameserver(null);
    }

    private static IllegalArgumentException invalidNameserver(final @Nullable Throwable cause) {
        return new IllegalArgumentException(
                "A transport fence requires literal nameserver addresses in resolv.conf", cause);
    }

    private String rules(
            final Map<String, List<InetAddress>> resolved, final List<InetAddress> nameservers) {
        final Set<String> allowed = new LinkedHashSet<>();
        if (!offline) {
            for (final List<InetAddress> addresses : resolved.values()) {
                for (final InetAddress address : addresses) {
                    if (!address.isLoopbackAddress()) allowed.add(destination(address) + " accept");
                }
            }
            for (final InetAddress address : nameservers) {
                if (address.isLoopbackAddress()) continue;
                allowed.add(destination(address) + " udp dport 53 accept");
                allowed.add(destination(address) + " tcp dport 53 accept");
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
                            "        ip6 hoplimit 255 ip6 saddr fe80::/10 icmpv6 type { 133, 134,"
                                    + " 135, 136 } accept",
                            "        ip6 hoplimit 255 ip6 daddr fe80::/10 icmpv6 type { 133, 134,"
                                    + " 135, 136 } accept"));
        }
        for (final String rule : allowed) lines.add("        " + rule);
        lines.addAll(List.of("    }", "}"));
        return String.join("\n", lines) + "\n";
    }

    private static String destination(final InetAddress address) {
        return (address.getAddress().length == 4 ? "ip" : "ip6")
                + " daddr "
                + address.getHostAddress();
    }

    private static Set<String> loopbackPorts(
            final List<Reach> reaches, final Map<String, List<InetAddress>> resolved) {
        final Set<String> ports = new LinkedHashSet<>();
        for (final Reach reach : reaches) {
            final List<InetAddress> addresses =
                    java.util.Objects.requireNonNull(
                            resolved.get(reach.host()),
                            "every parsed reach was resolved before ports are mapped");
            if (!reach.port().isEmpty()
                    && addresses.stream().anyMatch(InetAddress::isLoopbackAddress)) {
                ports.add(reach.port());
            }
        }
        return ports;
    }

    private record Reach(String host, @Nullable InetAddress literal, String port) {}

    private static Reach reach(final String reach) {
        final String host = host(reach);
        final @Nullable InetAddress literal;
        if ("localhost".equals(host)) {
            literal = InetAddress.ofLiteral("127.0.0.1");
        } else {
            literal = literal(host);
            if (literal == null) {
                if (reach.startsWith("[")
                        || host.length() > 253
                        || host.matches("[0-9.]+")
                        || !host.matches(
                                "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*\\.?"))
                    throw invalidReach();
            }
        }
        final String suffix =
                reach.startsWith("[")
                        ? reach.substring(reach.indexOf(']') + 1)
                        : reach.indexOf(':') == reach.lastIndexOf(':') && reach.contains(":")
                                ? reach.substring(reach.indexOf(':'))
                                : "";
        return new Reach(
                host,
                literal,
                suffix.isEmpty() ? "" : Integer.toString(Integer.parseInt(suffix.substring(1))));
    }

    private static @Nullable InetAddress literal(final String host) {
        try {
            return InetAddress.ofLiteral(host);
        } catch (final IllegalArgumentException ex) {
            return null;
        }
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
                "A transport fence requires a host name or literal address with an optional port"
                        + " from 1 to 65535");
    }
}
