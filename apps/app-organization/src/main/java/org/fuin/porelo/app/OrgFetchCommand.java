package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.project.AddressForm;
import org.fuin.porelo.organization.OfflineOrganization;
import org.fuin.porelo.organization.OrganizationException;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.gittree.FetchAccess;
import org.fuin.porelo.organization.gittree.ForgeAddress;
import org.fuin.porelo.organization.gittree.GitTreeOffline;
import org.fuin.porelo.organization.gittree.GitTreeStore;
import org.fuin.porelo.organization.gittree.HttpForgeDirectory;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.PersonCard;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Fetches the organization for one host, as root in its follow's own unit: no home, no vault and no
 * person, only the host's own read key, the forge's pinned host key and the pin of its admins.
 */
@Command(
        name = "fetch",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Fetches and checks the organization for one host, against its pin.")
public class OrgFetchCommand extends OrgReading {

    /** Opens the host's reader; replaced by a test, which has no forge to ask. */
    @FunctionalInterface
    interface Fetcher {
        OfflineOrganization open(PoreloContext context, String address, FetchAccess access);
    }

    @Option(names = "--host", required = true, description = "The host's name.")
    private String host = "";

    @Option(names = "--settings", required = true, description = "The host's organization.yml.")
    private Path settings = Path.of("");

    @Option(names = "--ssh-key", required = true, description = "The read-only deploy key.")
    private Path sshKey = Path.of("");

    @Option(names = "--known-hosts", required = true, description = "The forge's pinned host key.")
    private Path knownHosts = Path.of("");

    @Option(names = "--pin", required = true, description = "The admin keys, 'login key' a line.")
    private Path pin = Path.of("");

    @Option(names = "--since", description = "The revision taken last.")
    private @Nullable String since;

    /** Replaced by a test, which has no forge to ask. */
    Fetcher fetcher = OrgFetchCommand::real;

    @Override
    Map<String, @Nullable Object> answer(final PoreloContext machine) throws IOException {
        if (!host.matches("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")) {
            throw usage("--host is no host's name");
        }
        if (since != null && !since.matches("[0-9a-f]{40}|[0-9a-f]{64}")) {
            throw usage("--since is no commit's full name");
        }
        final Set<String> pinned = pins(pin);
        final String address = address(settings);
        regular(sshKey, "--ssh-key");
        regular(knownHosts, "--known-hosts");
        final FetchAccess access = new HostKey(sshKey, knownHosts);
        try {
            return new LinkedHashMap<>(
                    fetcher.open(machine, address, access)
                            .check(host, pinned, since == null ? "" : since));
        } catch (GitTreeStore.Unreachable | CommandException ex) {
            // Never a stale state: a host acts on this answer. A forge whose host key does not
            // match the pin ends here as well.
            throw new Refused(Refused.CANNOT_SAY, "the organization cannot be reached", since);
        } catch (OrganizationException ex) {
            // A shallow or unreadable history proves nothing.
            throw new Refused(Refused.REFUSED_STATE, String.valueOf(ex.getMessage()), since);
        }
    }

    @Override
    void text(final Map<String, @Nullable Object> answer) {
        line("Revision: " + answer.get("revision"));
    }

    /** The store for a host: its own read key, and the forge's public list with no credential. */
    private static OfflineOrganization real(
            final PoreloContext context, final String address, final FetchAccess access) {
        return new GitTreeOffline(
                GitTreeStore.open(
                        context.runner(),
                        address,
                        access,
                        context.paths().xdg().state().resolve("organization-host")),
                new HttpForgeDirectory(
                        ForgeAddress.of(address).forge(), null, OrganizationAccess.http(context)));
    }

    private static Refused usage(final String words) {
        return new Refused(Refused.USAGE, words, null);
    }

    static void regular(final Path file, final String option) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw usage(option + " names no regular file");
        }
    }

    /** Reads the pin, whose lines are not printed: a file named wrongly may hold anything. */
    private static Set<String> pins(final Path file) throws IOException {
        regular(file, "--pin");
        final Set<String> pinned = new HashSet<>();
        for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            final String[] fields = line.strip().split("\\s+", 2);
            try {
                if (fields.length != 2 || !PersonCard.isLogin(fields[0])) {
                    throw usage("--pin holds a line that is not 'login key'");
                }
                pinned.add(PersonCard.fingerprint(fields[1]));
            } catch (CardException ex) {
                throw usage("--pin holds a line that is not 'login key'");
            }
        }
        if (pinned.isEmpty()) {
            throw usage("--pin holds no key");
        }
        return pinned;
    }

    /**
     * Reads the address. Settings that name a credential of their own are refused: this reaches the
     * repository with the host's read key alone.
     */
    static String address(final Path file) throws IOException {
        regular(file, "--settings");
        final Object loaded;
        try {
            loaded =
                    new Yaml(new SafeConstructor(new LoaderOptions()))
                            .load(Files.readString(file, StandardCharsets.UTF_8));
        } catch (RuntimeException ex) {
            // The parser's own message quotes the line.
            throw usage("--settings does not read");
        }
        if (loaded instanceof Map<?, ?> top
                && "git".equals(top.get("adapter"))
                && top.keySet().equals(Set.of("adapter", "settings"))
                && top.get("settings") instanceof Map<?, ?> own
                && own.keySet().equals(Set.of("repository"))
                && own.get("repository") instanceof String repository) {
            if (AddressForm.isHttps(repository)) {
                throw usage("--settings names an address --ssh-key cannot reach");
            }
            try {
                ForgeAddress.of(repository);
            } catch (IllegalArgumentException ex) {
                throw usage("--settings names no address");
            }
            return repository;
        }
        throw usage("--settings names something other than the adapter and the address");
    }

    /**
     * The host's read key and the forge's pinned key, for one fetch, and nothing of this account.
     */
    record HostKey(Path key, Path knownHosts) implements FetchAccess {

        @Override
        public Lease lend(final String address) {
            final String command =
                    String.join(
                            " ",
                            List.of(
                                    "ssh",
                                    "-F",
                                    "/dev/null",
                                    "-o",
                                    "IdentitiesOnly=yes",
                                    "-o",
                                    "IdentityAgent=none",
                                    "-o",
                                    "UpdateHostKeys=no",
                                    "-o",
                                    "BatchMode=yes",
                                    "-o",
                                    "StrictHostKeyChecking=yes",
                                    "-o",
                                    "GlobalKnownHostsFile=/dev/null",
                                    "-o",
                                    "UserKnownHostsFile=" + quoted(knownHosts),
                                    "-i",
                                    quoted(key)));
            return new Lease() {
                @Override
                public Map<String, String> environment() {
                    return Map.of("GIT_SSH_COMMAND", command);
                }
            };
        }

        private static String quoted(final Path path) {
            return "'" + path.toString().replace("'", "'\\''") + "'";
        }
    }
}
