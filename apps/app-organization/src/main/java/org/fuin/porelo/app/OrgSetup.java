package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.organization.OrganizationException;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.gittree.GitTreeStore;
import org.fuin.porelo.organization.gittree.ProjectFile;
import org.fuin.porelo.vault.AgentKey;
import org.fuin.porelo.vault.SshAgentClient;
import org.fuin.porelo.vault.VaultException;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;

/** Points this machine at an organization, once it is seen to answer. */
public final class OrgSetup {

    @Option(
            names = "--organization",
            paramLabel = "ADDRESS",
            description = "Points this machine at the organization kept at this address.")
    private @Nullable String address;

    @Option(
            names = "--organization-checkout",
            paramLabel = "PATH",
            description =
                    "Names the checkout you keep of the organization's repository, which signing a"
                            + " repository's file reads where it stands.")
    private @Nullable Path organizationCheckout;

    @Option(
            names = "--project-checkout",
            paramLabel = "PROJECT=PATH",
            description =
                    "Names the checkout you keep of a project's repository; may be given more than"
                            + " once.")
    private Map<String, Path> projectCheckouts = new LinkedHashMap<>();

    @Option(names = "--replace", description = "Replaces the organization this machine has.")
    private boolean replace;

    @Option(names = "--json", description = "Answers an organization's set-up as JSON.")
    private boolean json;

    /** Replaced by a test, which has no forge to ask. */
    OrgAccess.Opener opener = OrgAccess::real;

    /**
     * Returns whether this set-up is the organization's, and not the account's.
     *
     * @return Whether an address was named.
     */
    public boolean named() {
        return address != null || organizationCheckout != null || !projectCheckouts.isEmpty();
    }

    /**
     * Checks the organization and writes this machine's settings.
     *
     * @param machine The machine.
     * @param spec The command, whose streams it answers on.
     * @return The exit code.
     * @throws IOException When something unexpected happened.
     */
    public int run(final PoreloContext machine, final CommandSpec spec) throws IOException {
        return FileLocks.holding(OrgAccess.settingsLock(machine), () -> configure(machine, spec));
    }

    private int configure(final PoreloContext machine, final CommandSpec spec) throws IOException {
        try {
            if (OrgDeparture.read(machine) != null) {
                throw new Refused(
                        Refused.EXISTS,
                        "a leave is unfinished: run porelo org leave again before setup",
                        null);
            }
            final Map<String, @Nullable Object> answer =
                    address == null ? new LinkedHashMap<>() : answer(machine, spec);
            final Map<String, String> checkouts = checkouts(machine);
            if (address == null) {
                if (json) {
                    answer.put("checkouts", checkouts);
                    OrgOutput.answer(spec, answer);
                } else {
                    checkouts.forEach(
                            (what, where) ->
                                    OrgOutput.line(spec, "Checkout of " + what + ": " + where));
                }
                return 0;
            }
            if (!checkouts.isEmpty()) {
                answer.put("checkouts", checkouts);
            }
            if (json) {
                OrgOutput.answer(spec, answer);
            } else {
                OrgOutput.line(spec, "This machine's organization: " + answer.get("name"));
                OrgOutput.line(
                        spec,
                        answer.get("login") == null
                                ? "No key in your agent has a card there: this machine only reads"
                                : "Acting here as " + answer.get("login"));
                checkouts.forEach(
                        (what, where) ->
                                OrgOutput.line(spec, "Checkout of " + what + ": " + where));
            }
            return 0;
        } catch (Refused refused) {
            return OrgOutput.refuse(spec, json, refused);
        } catch (IOException | RuntimeException ex) {
            OrgOutput.failed(spec, json, "the organization could not be set up");
            throw ex;
        }
    }

    private Map<String, @Nullable Object> answer(
            final PoreloContext machine, final CommandSpec spec) throws IOException {
        final String named = OrgAccess.settable(String.valueOf(address));
        final String held = OrgAccess.repository(machine).orElse(null);
        if (held != null && !held.equals(named) && !replace) {
            throw new Refused(
                    Refused.EXISTS,
                    "this machine has an organization already: --replace puts this one in its"
                            + " place",
                    null);
        }
        spec.commandLine().getErr().println("porelo: reading the organization's repository");
        spec.commandLine().getErr().flush();
        final OrganizationQuestions state = checked(machine, named);
        final String login = keepOwnCard(machine, state);
        OrgAccess.settle(machine, named);
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put("name", state.describe().name());
        answer.put("revision", state.revision());
        answer.put(
                "address",
                org.fuin.porelo.organization.gittree.ForgeAddress.withoutUserinfo(named));
        answer.put("login", login);
        return answer;
    }

    /**
     * Writes the checkouts named, each seen to be a checkout of this account with a main.
     *
     * @return What each now names, as {@code organization} or {@code project NAME}.
     */
    private Map<String, String> checkouts(final PoreloContext machine) throws IOException {
        final Map<String, String> named = new LinkedHashMap<>();
        if (organizationCheckout == null && projectCheckouts.isEmpty()) {
            return named;
        }
        OrgCheckouts settings = OrgCheckouts.of(machine);
        if (organizationCheckout != null) {
            final Path checkout = seen(machine, organizationCheckout);
            settings = settings.withOrganization(checkout);
            named.put("organization", checkout.toString());
        }
        for (final Map.Entry<String, Path> row : projectCheckouts.entrySet()) {
            if (!row.getKey().matches(org.fuin.porelo.core.project.Project.NAME_PATTERN)) {
                throw new Refused(
                        Refused.USAGE,
                        "--project-checkout names no project: " + row.getKey(),
                        null);
            }
            final Path checkout = seen(machine, row.getValue());
            settings = settings.withProject(row.getKey(), checkout);
            named.put("project " + row.getKey(), checkout.toString());
        }
        settings.write(machine);
        return named;
    }

    private static Path seen(final PoreloContext machine, final Path checkout) throws IOException {
        final Path absolute = checkout.toAbsolutePath().normalize();
        ProjectFile.checkoutMain(machine.runner(), absolute);
        return absolute;
    }

    /**
     * Keeps the card of the one person this machine's agent holds a key of, so that a second
     * machine can write too; without one it only reads. The card is public and every write judges
     * it again, so only a card the forge vouches for is taken, and the agent is asked to list,
     * never to sign.
     */
    private static @Nullable String keepOwnCard(
            final PoreloContext machine, final OrganizationQuestions state) throws IOException {
        final Set<String> held = listed(machine.environment().apply(OrgWriting.AGENT));
        final List<OrganizationQuestions.Person> own =
                state.getPersons().stream()
                        .filter(
                                person ->
                                        Organization.TRUSTED.equals(person.state())
                                                && !person.revoked()
                                                && held.contains(person.fingerprint()))
                        .toList();
        if (own.size() != 1) {
            return null;
        }
        final String login = own.getFirst().login();
        final String card = state.getCard(login).orElse(null);
        if (card == null) {
            return null;
        }
        final Path home = machine.paths().xdg().config();
        final Path directory =
                org.fuin.porelo.vault.PrivateDirectory.make(home, home.resolve("identity"));
        written(directory.resolve("key.pub"), own.getFirst().key() + " " + login + "\n");
        written(directory.resolve("card"), card);
        return login;
    }

    private static Set<String> listed(final @Nullable String socket) {
        if (socket == null || socket.isBlank()) {
            return Set.of();
        }
        final Set<String> fingerprints = new HashSet<>();
        try {
            for (final AgentKey key : new SshAgentClient(Path.of(socket)).identities()) {
                final String fingerprint = OrgWriting.fingerprintOf(key);
                if (fingerprint != null) {
                    fingerprints.add(fingerprint);
                }
            }
        } catch (VaultException | java.nio.file.InvalidPathException ex) {
            // No agent to ask leaves a machine that reads, which is what it asked for.
            return Set.of();
        }
        return fingerprints;
    }

    /** Written whole and owner-only, as the identity's own commands keep these files. */
    private static void written(final Path file, final String text) throws IOException {
        final Path staged =
                Files.createTempFile(
                        file.getParent(),
                        ".",
                        ".staged",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        try {
            Files.writeString(staged, text, StandardCharsets.UTF_8);
            Files.move(
                    staged,
                    file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    /** Settings a machine could not read an organization with would only fail later. */
    private OrganizationQuestions checked(final PoreloContext machine, final String named)
            throws IOException {
        final OrgAccess.Opened opened = opener.open(machine, named);
        try {
            return opened.store().read(opened.identities());
        } catch (GitTreeStore.Empty ex) {
            throw new Refused(
                    Refused.CANNOT_SAY,
                    "that repository is empty: its first owner fills it with porelo org create",
                    null);
        } catch (GitTreeStore.Unreachable ex) {
            throw OrgAccess.unreached(ex, "the organization cannot be reached", named);
        } catch (CommandException ex) {
            throw new Refused(Refused.CANNOT_SAY, "the organization did not answer in time", null);
        } catch (OrganizationException ex) {
            throw new Refused(Refused.REFUSED_STATE, String.valueOf(ex.getMessage()), null);
        }
    }
}
