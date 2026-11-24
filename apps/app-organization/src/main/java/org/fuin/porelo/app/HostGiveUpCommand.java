package org.fuin.porelo.app;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.gittree.ForgeAddress;
import org.fuin.porelo.organization.gittree.ForgeReadKey;
import org.fuin.porelo.vault.RegistryText;
import org.fuin.porelo.vault.VaultEntry;
import org.fuin.porelo.vault.VaultException;
import org.fuin.porelo.vault.VaultFile;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Gives a host up for good: removes the read keys its identity carries at the central forge, then
 * the identity from the vault. Its own command, never a part of {@code down}, which keeps both.
 */
@Command(
        name = "give-up",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description =
                "Gives a host up for good: removes its read keys at the central forge, then its"
                        + " identity from the vault.")
public class HostGiveUpCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    /** Where a host's identity is kept in the vault, by the host's name. */
    static final String ENTRY_PREFIX = VaultNames.HOST_IDENTITY_PREFIX;

    private static final String HOST = "[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?";

    private static final int MOST_TOKEN_BYTES = 65536;

    @Parameters(paramLabel = "HOST", description = "The host's name, as its card names it.")
    private String host = "";

    @Option(names = "--json", description = "Answers the keys removed and the keys kept.")
    private boolean json;

    @Spec private CommandSpec spec;
    private @Nullable PoreloContext context;

    /** How one repository at the forge is asked; a test points it at its own server. */
    @FunctionalInterface
    interface Forge {
        ForgeReadKey at(URI forge, String owner, String name, String token, HttpClient client);
    }

    Forge forge =
            (forge, owner, name, token, client) ->
                    ForgeReadKey.of(ForgeReadKey.api(forge), owner, name, token, client);

    /**
     * A key the identity carries, public half only, with why it is never sent to the forge when it
     * is not: its address on no central repository, or its key no key.
     */
    record Key(
            @Nullable String project,
            @Nullable ForgeAddress repository,
            String repositoryName,
            String title,
            String key,
            @Nullable String unsent) {}

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final PoreloContext machine = Objects.requireNonNull(context, "picocli sets the context");
        if (!host.matches(HOST)) {
            return refuse(64, "name a host as its card names it");
        }
        final VaultFile vault = machine.vault();
        final VaultFile.Opener opener = machine.opener().orElse(null);
        if (!vault.exists() || opener == null) {
            return refuse(
                    vault.exists() ? 77 : 66,
                    vault.exists()
                            ? "the vault is shut; run porelo vault unlock and retry"
                            : "no identity of " + host + " is kept: there is no vault");
        }
        final List<Key> keys;
        final ForgeAddress central;
        char[] token = null;
        try {
            final VaultEntry entry = vault.read(opener).get(ENTRY_PREFIX + host);
            if (entry == null) {
                return refuse(66, "no identity of " + host + " is kept in the vault");
            }
            final String repository = OrgAccess.repository(machine).orElse(null);
            if (repository == null) {
                return refuse(78, "this machine has no organization; run porelo setup first");
            }
            central = ForgeAddress.of(repository);
            keys = keys(host, entry.value(), central);
            token = token(machine, central);
            if (token == null) {
                return refuse(
                        69,
                        "no token for the central forge: store it with porelo vault put"
                                + " central-forge, or give it on standard input");
            }
            return giveUp(machine, vault, opener, entry.value(), keys, new String(token));
        } catch (VaultException ex) {
            return refuse(70, RegistryText.printable(String.valueOf(ex.getMessage())));
        } catch (Refused refused) {
            return refuse(
                    Refused.USAGE.equals(refused.reason()) ? 64 : 65,
                    RegistryText.printable(String.valueOf(refused.getMessage())));
        } catch (IllegalArgumentException ex) {
            return refuse(65, "organization.yml names no forge repository");
        } catch (IOException ex) {
            return refuse(74, "organization.yml cannot be read");
        } finally {
            if (token != null) {
                Arrays.fill(token, '\0');
            }
        }
    }

    private int giveUp(
            final PoreloContext machine,
            final VaultFile vault,
            final VaultFile.Opener opener,
            final String read,
            final List<Key> keys,
            final String token) {
        final HttpClient client = OrganizationAccess.http(machine);
        final List<Map<String, @Nullable Object>> removed = new ArrayList<>();
        final List<Map<String, @Nullable Object>> kept = new ArrayList<>();
        for (final Key key : keys) {
            final ForgeAddress at = key.repository();
            final Map<String, @Nullable Object> row = new LinkedHashMap<>();
            row.put("project", key.project());
            row.put("repository", key.repositoryName());
            row.put("title", key.title());
            if (at == null || key.unsent() != null) {
                // Nothing is asked: no link either, since the address is the host's own words.
                row.put("why", key.unsent());
                row.put("link", null);
                kept.add(row);
                continue;
            }
            final String link =
                    at.forge() + "/" + at.organization() + "/" + at.repository() + "/settings/keys";
            try {
                forge.at(at.forge(), at.organization(), at.repository(), token, client)
                        .removeHosts(key.key(), key.title());
                removed.add(row);
            } catch (Refused refused) {
                final String why = RegistryText.printable(String.valueOf(refused.getMessage()));
                // A forge's words never reach here; ours never quote the token, but are checked.
                row.put(
                        "why",
                        why.contains(token)
                                ? "the forge's answer was withheld because it held the token"
                                : why);
                row.put("link", link);
                kept.add(row);
            } catch (IllegalArgumentException ex) {
                row.put("why", "the address names no repository the forge takes");
                row.put("link", null);
                kept.add(row);
            }
        }
        boolean changed = false;
        if (kept.isEmpty()) {
            final boolean[] same = {false};
            try {
                // Deleted only as it was read: an identity that gained a key meanwhile keeps it.
                vault.update(
                        opener,
                        entries -> {
                            final VaultEntry now = entries.get(ENTRY_PREFIX + host);
                            same[0] = now != null && read.equals(now.value());
                            if (same[0]) {
                                entries.remove(ENTRY_PREFIX + host);
                            }
                            return entries;
                        });
            } catch (VaultException ex) {
                return refuse(70, RegistryText.printable(String.valueOf(ex.getMessage())));
            }
            changed = !same[0];
        }
        if (json) {
            final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
            answer.put("removed", removed);
            answer.put("kept", kept);
            OrgOutput.answer(spec, answer);
        } else {
            removed.forEach(row -> OrgOutput.line(spec, "removed   " + named(row)));
            kept.forEach(
                    row ->
                            OrgOutput.line(
                                    spec,
                                    "kept      "
                                            + named(row)
                                            + ": "
                                            + row.get("why")
                                            + (row.get("link") == null
                                                    ? ""
                                                    : "; its owner removes it at "
                                                            + row.get("link"))));
            OrgOutput.line(
                    spec,
                    kept.isEmpty() && !changed
                            ? "gave up   " + host + ": its identity is deleted from the vault"
                            : "kept      "
                                    + host
                                    + "'s identity in the vault: "
                                    + kept.size()
                                    + " of its keys remain at the central forge");
        }
        if (changed) {
            return refuse(
                    65,
                    "the identity of "
                            + host
                            + " changed while it was given up and is kept; run porelo host give-up"
                            + " again");
        }
        return kept.isEmpty() ? 0 : 65;
    }

    private static String named(final Map<String, @Nullable Object> row) {
        final Object project = row.get("project");
        return (project == null ? "the organization" : "project " + project)
                + ", "
                + row.get("repository")
                + " ("
                + row.get("title")
                + ")";
    }

    /**
     * Reads the public halves an identity carries, each checked: a key the person did not see go
     * there is never sent to the forge, and no address leads the token to another server.
     */
    static List<Key> keys(final String host, final String value, final ForgeAddress central) {
        final Object parsed;
        try {
            parsed = Json.parse(value);
        } catch (RuntimeException ex) {
            throw invalid("its value does not read");
        }
        if (!(parsed instanceof Map<?, ?> identity) || !host.equals(identity.get("host"))) {
            throw invalid("it names another host");
        }
        final List<Key> keys = new ArrayList<>();
        if (!(identity.get("organization") instanceof Map<?, ?> organization)
                || !(organization.get("public") instanceof String orgPublic)) {
            throw invalid("its organization key has no public half");
        }
        final String orgKey = canonical(orgPublic);
        final String fingerprint;
        try {
            fingerprint = OrgAddReadKeyCommand.fingerprint(orgKey);
        } catch (IllegalArgumentException ex) {
            throw invalid("its organization key does not read");
        }
        keys.add(
                new Key(
                        null,
                        central,
                        central.repository(),
                        "porelo host read key " + fingerprint,
                        orgKey,
                        null));
        if (!(identity.get("repositories") instanceof List<?> repositories)) {
            throw invalid("it lists no repositories");
        }
        for (final Object each : repositories) {
            if (!(each instanceof Map<?, ?> repository)
                    || !(repository.get("project") instanceof String project)
                    || !(repository.get("address") instanceof String address)
                    || !(repository.get("title") instanceof String title)
                    || !(repository.get("public") instanceof String key)) {
                throw invalid("a repository lacks its project, address, title or key");
            }
            if (!project.matches("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")
                    || !title.equals("porelo host " + host + " project " + project)) {
                throw invalid("a repository's project or title is not this host's");
            }
            // A row that cannot be sent is kept and named; the other rows are still given up.
            ForgeAddress at = null;
            String unsent = null;
            try {
                at = ForgeAddress.of(address);
            } catch (IllegalArgumentException ex) {
                unsent = "its address is no forge repository";
            }
            // The token goes to the central forge alone: an address on another server is kept.
            if (at != null && !at.forge().equals(central.forge())) {
                unsent = "its address is not on the central forge";
            }
            String publicKey = null;
            try {
                publicKey = canonical(key);
            } catch (Refused refused) {
                unsent = unsent != null ? unsent : "its key does not read";
            }
            keys.add(
                    new Key(
                            project,
                            unsent == null ? at : null,
                            unsent == null && at != null ? at.repository() : project,
                            title,
                            publicKey == null ? "" : publicKey,
                            unsent));
        }
        return keys;
    }

    /** A key's type and base64, nothing else. */
    private static String canonical(final String key) {
        final String[] words = key.strip().split(" ");
        if (words.length < 2
                || !words[0].matches("ssh-ed25519|ecdsa-sha2-nistp(256|384|521)|ssh-rsa")
                || !words[1].matches("[A-Za-z0-9+/]+={0,2}")) {
            throw invalid("a key's public half is no OpenSSH public key");
        }
        return words[0] + " " + words[1];
    }

    private static Refused invalid(final String why) {
        return new Refused(
                Refused.CANNOT_SAY, "the kept identity is not one to give up: " + why, null);
    }

    /** The vault's token for the central forge, or one line on standard input, kept nowhere. */
    private static char @Nullable [] token(final PoreloContext machine, final ForgeAddress central)
            throws IOException {
        final String held = OrganizationAccess.forgeToken(machine, central);
        if (held != null) {
            return held.toCharArray();
        }
        if (machine.input().terminal() && System.console() != null) {
            final char[] typed = System.console().readPassword("Central forge token: ");
            return typed == null || typed.length == 0 ? null : checked(typed);
        }
        final InputStream in = machine.input().stream();
        final byte[] read = in.readNBytes(MOST_TOKEN_BYTES + 1);
        try {
            if (read.length > MOST_TOKEN_BYTES) {
                throw new Refused(Refused.USAGE, "standard input holds more than a token", null);
            }
            final String text = new String(read, StandardCharsets.UTF_8).strip();
            return text.isEmpty() ? null : checked(text.toCharArray());
        } finally {
            Arrays.fill(read, (byte) 0);
        }
    }

    private static char[] checked(final char[] token) {
        for (final char each : token) {
            if (each <= ' ' || each >= 0x7f) {
                Arrays.fill(token, '\0');
                throw new Refused(Refused.USAGE, "the token is not one printable line", null);
            }
        }
        return token;
    }

    private int refuse(final int exit, final String words) {
        spec.commandLine().getErr().println("porelo: " + words);
        spec.commandLine().getErr().flush();
        return exit;
    }
}
