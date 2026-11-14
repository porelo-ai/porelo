package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.gittree.ForgeAddress;
import org.fuin.porelo.vault.RegistryText;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** Hands a forge token to the host named by an accepted card. */
@Command(
        name = "refresh",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Hands the organization's forge token to a host over pinned SSH.")
public class OrgHostRefreshCommand implements Callable<Integer>, PoreloFactory.ContextAware {
    /** Where the host's porelo-host package installs its program; env looks no further. */
    private static final String HOST_PROGRAM = "/usr/sbin/porelo-host";

    private static final Set<String> STATUS_FIELDS =
            Set.of("host", "organization", "refused", "commit", "pinned", "persons", "projects");

    @Parameters(paramLabel = "HOST", description = "The organization's host.")
    private String host = "";

    @Option(
            names = "--json",
            description = "Answers whether the token was handed over, and the safe host answer.")
    private boolean json;

    @Spec private CommandSpec spec;
    private @Nullable PoreloContext context;

    /** Replaced by a test, which has no organization to read. */
    @FunctionalInterface
    interface Reader {
        List<OrganizationQuestions.Host> hosts(PoreloContext machine) throws IOException;
    }

    Reader reader = OrgHostRefreshCommand::hosts;

    private static List<OrganizationQuestions.Host> hosts(PoreloContext machine)
            throws IOException {
        final OrgAccess.Reading reading = OrgAccess.read(machine, OrgAccess::real);
        if (reading.stale()) {
            throw new Refused(
                    Refused.CANNOT_SAY,
                    "the organization cannot be checked; restore access and run porelo org --json before refreshing",
                    null);
        }
        return reading.answers().getHosts();
    }

    /** Uses the machine whose organization and credential are being refreshed. */
    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
    }

    /** Hands over the credential and reports the host's safe answer. */
    @Override
    public Integer call() {
        final PoreloContext machine = Objects.requireNonNull(context, "picocli sets the context");
        @Nullable String token = null;
        try {
            if (!host.matches("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")) {
                return report(64, "name a host shown by porelo org --json", null);
            }
            final var held = machine.readableCredentials();
            if (held.isEmpty()) {
                return report(
                        77,
                        "the vault is shut or unreadable; run porelo vault unlock and retry",
                        null);
            }
            final String repository = OrgAccess.repository(machine).orElse(null);
            if (repository == null) {
                return report(78, "this machine has no organization; run porelo setup first", null);
            }
            final ForgeAddress address = ForgeAddress.of(repository);
            // The card first, read as every organization command reads it: the token is taken out
            // of the vault only for the one process it goes to.
            final Organization.Card card = card(machine);
            token =
                    OrganizationAccess.forgeToken(
                            held.orElseThrow(),
                            machine.credentialRegistry(),
                            machine.environment(),
                            address);
            if (token == null) {
                return report(
                        69,
                        "no token for the organization's forge; store it with porelo vault put central-forge, then declare it with porelo credentials declare "
                                + address.forge()
                                + "/"
                                + address.organization()
                                + "/ --kind token --vault central-forge",
                        null);
            }
            if (token.length() > 65536 || !token.matches("[!-~]+")) {
                return report(
                        65,
                        "the forge token must be one printable ASCII line of at most 65536 bytes; replace its entry with porelo vault put",
                        null);
            }
            return send(machine, card, token);
        } catch (Refused refused) {
            final int exit =
                    switch (refused.reason()) {
                        case Refused.NOT_FOUND -> 66;
                        case Refused.NO_CARD -> 65;
                        case Refused.NO_KEY, Refused.CANNOT_SAY -> 69;
                        default -> 65;
                    };
            return report(exit, safe(String.valueOf(refused.getMessage()), token), null);
        } catch (CommandException ex) {
            // Process exceptions can retain remote output; none of it belongs in the failure log.
            return report(
                    69,
                    "SSH could not complete; verify the host card, root SSH access and the connection, then retry",
                    null);
        } catch (IOException | RuntimeException ex) {
            return report(
                    70,
                    "the host refresh could not complete; check the organization settings and host card, then retry",
                    null);
        }
    }

    private Organization.Card card(PoreloContext machine) throws IOException {
        for (OrganizationQuestions.Host each : reader.hosts(machine)) {
            if (!each.host().equals(host)) continue;
            final Organization.Card card = each.card();
            if (card == null) {
                throw new Refused(
                        Refused.NO_CARD,
                        "the host has no accepted card; file one with porelo org host change "
                                + host
                                + " --address ADDRESS --ssh-port PORT --host-key KEY",
                        null);
            }
            if (card.sshHostKey().isBlank()) {
                throw new Refused(
                        Refused.NO_KEY,
                        "the host card has no key; file it with porelo org host change "
                                + host
                                + " --host-key KEY",
                        null);
            }
            return card;
        }
        throw new Refused(
                Refused.NOT_FOUND,
                "no such host; choose one from porelo org --json or add it with porelo org host add HOST --admin LOGIN",
                null);
    }

    private int send(PoreloContext machine, Organization.Card card, String token)
            throws IOException {
        final byte[] input = token.getBytes(StandardCharsets.US_ASCII);
        try (final PinnedHostSsh ssh =
                PinnedHostSsh.open(card.address(), card.sshPort(), "root", card.sshHostKey())) {
            final PrintWriter err = spec.commandLine().getErr();
            err.println(
                    "asking "
                            + host
                            + " to follow the organization; this takes a while for many repositories");
            err.flush();
            final CommandResult result =
                    machine.runner()
                            .run(
                                    ssh.command(
                                            // A root SSH session carries pam_systemd's and
                                            // sshd's names (DBUS_SESSION_BUS_ADDRESS,
                                            // XDG_*, SSH_*), which porelo-host refuses.
                                            List.of(
                                                    "env",
                                                    "-i",
                                                    "PATH=/usr/sbin:/usr/bin:/sbin:/bin",
                                                    "LANG=C.UTF-8",
                                                    HOST_PROGRAM,
                                                    "org",
                                                    "refresh"),
                                            input,
                                            machine.environment()),
                                    line -> {
                                        err.println(host + ": " + safe(line, token));
                                        err.flush();
                                    });
            if (result.exitCode() == 127 || result.exitCode() == 126) {
                // env's own lines name no package; a host whose collection predates the program
                // answers so.
                return report(
                        69,
                        "porelo-host is not installed at "
                                + HOST_PROGRAM
                                + " on "
                                + host
                                + "; converge the host with porelo-host collection 0.5.13 or later, which installs it, then retry",
                        null);
            }
            if (result.exitCode() != 0) {
                final int exit =
                        switch (result.exitCode()) {
                            case 64, 65, 69, 73 -> result.exitCode();
                            case 255 -> 69;
                            default -> 70;
                        };
                return report(
                        exit,
                        result.standardError().isBlank()
                                ? "the host refused refresh; check root SSH access and the host's organization settings, then retry"
                                : "",
                        null);
            }
            final Object parsed = Json.parse(result.standardOutput());
            if (!(parsed instanceof Map<?, ?> answer)
                    || !answer.keySet().equals(STATUS_FIELDS)
                    || !host.equals(answer.get("host"))
                    || !(answer.get("persons") instanceof List<?>)
                    || !(answer.get("projects") instanceof List<?>)
                    || result.standardOutput().contains(token)
                    || containsToken(answer, token)) {
                return report(
                        70,
                        "the host returned an invalid or unsafe refresh answer; check porelo-host on the host",
                        null);
            }
            return report(0, "", answer);
        } finally {
            Arrays.fill(input, (byte) 0);
        }
    }

    private static boolean containsToken(@Nullable Object value, String token) {
        return switch (value) {
            case String text ->
                    text.contains(token) || RegistryText.printable(text).contains(token);
            case Map<?, ?> map ->
                    map.entrySet().stream()
                            .anyMatch(
                                    entry ->
                                            containsToken(entry.getKey(), token)
                                                    || containsToken(entry.getValue(), token));
            case List<?> list -> list.stream().anyMatch(each -> containsToken(each, token));
            case Number number -> number.toString().contains(token);
            case Boolean flag -> flag.toString().contains(token);
            case null, default -> false;
        };
    }

    private static String safe(String text, @Nullable String token) {
        final String printable = RegistryText.printable(text);
        return token != null && (text.contains(token) || printable.contains(token))
                ? "the host's diagnostic was withheld because it contained the credential"
                : printable;
    }

    private int report(int exit, String words, @Nullable Object answer) {
        if (json) {
            final Map<String, @Nullable Object> response = new LinkedHashMap<>();
            response.put("handedOver", exit == 0);
            response.put("answer", answer);
            OrgOutput.answer(spec, response);
        } else if (exit == 0) {
            OrgOutput.line(spec, "handed over the organization's forge token to " + host);
            OrgOutput.line(spec, Json.write(answer));
        }
        if (!words.isBlank()) {
            spec.commandLine().getErr().println("porelo: " + words);
            spec.commandLine().getErr().flush();
        }
        return exit;
    }
}
