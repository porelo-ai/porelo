package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.OrganizationWrites;
import org.fuin.porelo.organization.gittree.CommitSigning;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.OpenSshPrivateKey;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.VaultException;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * A second, offline key for an owner of the organization or an admin of a host: made once into a
 * file the person keeps away from their daily agent, and filed beside the key it backs. With {@code
 * --use} the file signs the one change it may make, the key it backs replaced by a new card's.
 *
 * <p>Neither the key's secret half nor its passphrase passes through Porelo: {@code ssh-keygen}
 * makes the file on the person's terminal, and git signs with it through {@code ssh-keygen}, which
 * asks the passphrase there too.
 */
@Command(
        name = "backup-key",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description =
                "Makes an offline backup key for your place as an owner or a host's admin, or uses"
                        + " one to replace the key it backs.")
public class IdentityBackupKeyCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    /** How the store says a key did not sign a commit. */
    private static final String NO_KEY = OrganizationQuestions.Refused.NO_KEY;

    /** A base no store stands at: the id is looked for first, and nothing is made without it. */
    private static final String NO_REVISION = "0".repeat(40);

    /** Where the backup key goes: exactly one. */
    static final class Scope {
        @Option(names = "--host", paramLabel = "HOST", description = "A host you are an admin of.")
        @Nullable String host;

        @Option(names = "--organization", description = "The organization you are an owner of.")
        boolean organization;
    }

    @ArgGroup(multiplicity = "1")
    private Scope scope = new Scope();

    @Option(
            names = "--out",
            paramLabel = "FILE",
            description = "Where the new backup key's file is written; it must not exist.")
    private @Nullable Path out;

    @Option(
            names = "--use",
            paramLabel = "FILE",
            description = "The backup key's file, to replace the key it backs.")
    private @Nullable Path use;

    @Option(
            names = "--card",
            paramLabel = "NEW_CARD",
            description = "With --use: the card whose key replaces the one the backup key backs.")
    private @Nullable Path card;

    @Option(
            names = "--base",
            paramLabel = "REVISION",
            description = "The organization's revision the change is made against.")
    private @Nullable String base;

    @Option(
            names = "--id",
            paramLabel = "ID",
            description = "Names the change: asked again, it answers the same change.")
    private @Nullable String id;

    @Option(names = "--json", description = "Answers as JSON.")
    private boolean json;

    @Spec private CommandSpec spec;

    private @Nullable PoreloContext context;

    /** Whether this call's ssh-keygen wrote the file at {@code --out}. */
    private boolean written;

    /** Whether the file written there is the person's to keep: its key may stand. */
    private boolean kept;

    /** Replaced by a test, which has no forge to ask. */
    IdentityOrganization.Opener opener = IdentityOrganization::real;

    /** Whether a terminal is there to ask the passphrase on; replaced by a test. */
    BooleanSupplier terminal = () -> System.console() != null;

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() throws IOException {
        final PoreloContext machine =
                java.util.Objects.requireNonNull(context, "picocli's factory sets the context");
        final String change =
                id != null
                        ? id
                        : "bk-"
                                + IdentityChange.today()
                                + "-"
                                + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());
        try {
            IdentityChange.checkId(id);
            if ((out == null) == (use == null) || (use != null) != (card != null)) {
                throw new IdentityChange.Refused(
                        64,
                        null,
                        "give --out FILE to make a backup key, or --use FILE --card CARD");
            }
            final PersonCard own = new IdentityChange(machine).card();
            final IdentityOrganization.Reading organization =
                    IdentityOrganization.required(machine, opener);
            return use == null
                    ? make(machine, organization, own, change)
                    : replace(machine, organization, own, change);
        } catch (IdentityChange.Refused ex) {
            kept = kept || ex.mayHaveBeenMade();
            return refused(ex, change);
        } catch (CardException | IllegalArgumentException ex) {
            return refused(
                    new IdentityChange.Refused(65, null, String.valueOf(ex.getMessage())), change);
        } catch (IOException ex) {
            return refused(
                    new IdentityChange.Refused(66, null, String.valueOf(ex.getMessage())), change);
        } finally {
            if (written && !kept) {
                forgetUnfiled();
            }
        }
    }

    /** Answers a refusal naming the change's id, which may be one nobody has seen yet. */
    private int refused(final IdentityChange.Refused refused, final String change)
            throws IOException {
        if (id == null) {
            final PrintWriter err = spec.commandLine().getErr();
            err.println("porelo: this change's id is " + change);
            err.flush();
        }
        final Map<String, @Nullable Object> named = new LinkedHashMap<>();
        named.put("id", change);
        return IdentityChange.refuse(spec, json, refused.saying(named));
    }

    /** A key made and never filed is no backup of anything: the file goes, so none is kept. */
    private void forgetUnfiled() {
        final Path file = java.util.Objects.requireNonNull(out).toAbsolutePath();
        try {
            Files.deleteIfExists(file);
            Files.deleteIfExists(Path.of(file + ".pub"));
        } catch (IOException ex) {
            spec.commandLine()
                    .getErr()
                    .println("porelo: warning: the unfiled key " + file + " could not be removed");
        }
    }

    /** The rows of the place this asks about, which the store answers one per key. */
    private List<Organization.Owner> place(final OrganizationQuestions state)
            throws IdentityChange.Refused {
        if (scope.organization) {
            return state.getOrganizationOwners();
        }
        return state.getHosts().stream()
                .filter(each -> each.host().equals(scope.host))
                .findFirst()
                .map(OrganizationQuestions.Host::admins)
                .orElseThrow(
                        () ->
                                new IdentityChange.Refused(
                                        66,
                                        OrganizationQuestions.Refused.NOT_FOUND,
                                        "no host " + scope.host));
    }

    private int make(
            final PoreloContext machine,
            final IdentityOrganization.Reading organization,
            final PersonCard own,
            final String change)
            throws IdentityChange.Refused, IOException {
        final String login = own.login();
        final boolean existing = id != null && Files.exists(outFile(), LinkOption.NOFOLLOW_LINKS);
        final OrganizationQuestions state = organization.answers();
        final String socket = new IdentityChange(machine).socket();
        if (!existing) {
            refuseBeforeMaking(machine, organization, own, login);
            IdentityChange.servedKey(own.fingerprint(), socket);
        }
        final String key = existing ? publicHalf(outFile()) : keygen(machine, login);
        final OrganizationWrites writes =
                organization.opened().writes().signingWith(CommitSigning.agent(socket));
        final OrganizationWrites.Change made =
                new OrganizationWrites.Change(
                        new OrganizationWrites.Acting(login, own.key()),
                        base != null ? base : state.revision(),
                        change);
        final OrganizationWrites.Written filed =
                IdentityOrganization.filed(() -> writes.addBackupKey(made, scope.host, key));
        kept = true;
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put("id", filed.id());
        answer.put("scope", scope(organization));
        answer.put("fingerprint", PersonCard.fingerprint(key));
        answer.put("file", outFile().toString());
        answer.put("revision", filed.revision());
        answer.put("again", filed.again());
        answer.put("of", "organization");
        answer.put("pushed", true);
        return answer(
                answer,
                "filed backup key " + PersonCard.fingerprint(key) + " in " + filed.revision());
    }

    /** Everything that would refuse the key, so none is made for a change that cannot be made. */
    private void refuseBeforeMaking(
            final PoreloContext machine,
            final IdentityOrganization.Reading organization,
            final PersonCard own,
            final String login)
            throws IdentityChange.Refused {
        if (new IdentityChange(machine).revoked(own.key(), organization)) {
            throw IdentityChange.revokedRefusal(
                    "your card's key " + own.fingerprint() + " is revoked", organization);
        }
        final List<Organization.Owner> rows = place(organization.answers());
        if (rows.stream().noneMatch(row -> !row.backup() && row.login().equals(login))) {
            throw new IdentityChange.Refused(
                    65,
                    OrganizationQuestions.Refused.NOT_ALLOWED,
                    login + " does not hold " + where());
        }
        if (rows.stream().anyMatch(row -> row.backup() && row.login().equals(login))) {
            throw new IdentityChange.Refused(
                    65,
                    OrganizationQuestions.Refused.EXISTS,
                    login + " has a backup key for " + where() + " already");
        }
    }

    private Path outFile() {
        return java.util.Objects.requireNonNull(out, "--out is given").toAbsolutePath();
    }

    /**
     * Makes the backup key's file on the person's terminal, and answers its public half; refused
     * over a file that exists and without a terminal.
     */
    private String keygen(final PoreloContext machine, final String login)
            throws IdentityChange.Refused, IOException {
        final Path file = outFile();
        // ssh-keygen writes both halves, and the public one is removed once read.
        for (final Path half : List.of(file, Path.of(file + ".pub"))) {
            if (Files.exists(half, LinkOption.NOFOLLOW_LINKS)) {
                throw new IdentityChange.Refused(65, "file-exists", half + " exists already");
            }
        }
        if (!terminal.getAsBoolean()) {
            throw new IdentityChange.Refused(
                    65, "no-terminal", "the backup key's passphrase is asked on a terminal");
        }
        // ssh-keygen asks the passphrase itself, twice, and writes the file with mode 0600.
        final int made =
                machine.exec()
                        .applyAsInt(
                                List.of(
                                        "ssh-keygen",
                                        "-q",
                                        "-t",
                                        "ed25519",
                                        "-C",
                                        "porelo backup key of " + login,
                                        "-f",
                                        file.toString()));
        final Path pub = Path.of(file + ".pub");
        written = Files.exists(file, LinkOption.NOFOLLOW_LINKS);
        if (made != 0 || !Files.isRegularFile(file)) {
            Files.deleteIfExists(pub);
            throw new IdentityChange.Refused(
                    65, null, "ssh-keygen made no key (exit " + made + ")");
        }
        try {
            return publicHalf(file);
        } finally {
            // Only the file is the person's to keep; its public half is filed.
            Files.deleteIfExists(pub);
        }
    }

    /** The public half of a key file; its secret part stays in the file. */
    private static String publicHalf(final Path file) throws IdentityChange.Refused, IOException {
        try {
            return OpenSshPrivateKey.publicKey(Files.readString(file));
        } catch (VaultException ex) {
            throw new IdentityChange.Refused(65, "not-the-backup", file + ": " + ex.getMessage());
        }
    }

    /** The new card, of the person's own login and signed as a card must be. */
    private PersonCard next(final String login) throws IdentityChange.Refused, IOException {
        final PersonCard next;
        try {
            next =
                    PersonCard.read(
                            Files.readString(java.util.Objects.requireNonNull(card)), "--card");
        } catch (CardException ex) {
            throw new IdentityChange.Refused(65, "card-login", String.valueOf(ex.getMessage()));
        }
        if (!next.login().equals(login) || !next.signedAsItMustBe()) {
            throw new IdentityChange.Refused(
                    65,
                    "card-login",
                    "the new card is not a card of " + login + " signed as it must be");
        }
        return next;
    }

    private int replace(
            final PoreloContext machine,
            final IdentityOrganization.Reading organization,
            final PersonCard own,
            final String change)
            throws IdentityChange.Refused, IOException {
        final Path file = java.util.Objects.requireNonNull(use).toAbsolutePath();
        final String login = own.login();
        final String key = publicHalf(file);
        final String backup = PersonCard.fingerprint(key);
        final PersonCard next = next(login);
        final IdentityChange identity = new IdentityChange(machine);
        if (identity.revoked(key, organization)) {
            throw IdentityChange.revokedRefusal(
                    "the backup key " + backup + " is revoked", organization);
        }
        if (identity.revoked(next.key(), organization)) {
            throw IdentityChange.revokedRefusal(
                    "the new card's key " + next.fingerprint() + " is revoked", organization);
        }
        final List<Organization.Owner> rows = place(organization.answers());
        final String old =
                rows.stream()
                        .filter(row -> !row.backup() && row.login().equals(login))
                        .map(Organization.Owner::fingerprint)
                        .findFirst()
                        .orElse(own.fingerprint());
        if (id == null
                && rows.stream()
                        .noneMatch(
                                row ->
                                        row.backup()
                                                && row.login().equals(login)
                                                && row.fingerprint().equals(backup))) {
            throw new IdentityChange.Refused(
                    65,
                    "not-the-backup",
                    "the key in " + file + " is not " + login + "'s backup key for " + where());
        }
        final Map<String, String> agent = new LinkedHashMap<>();
        final String socket = machine.environment().apply(IdentityChange.AGENT);
        if (socket != null && !socket.isEmpty()) {
            agent.put(IdentityChange.AGENT, socket);
        }
        final OrganizationWrites writes =
                organization
                        .opened()
                        .writes()
                        .signingWith(new CommitSigning(agent, Map.of(key, file)));
        // A card that stands already can be this id's own change, or another's. Only the first may
        // be answered, and no base lets the store make anything of the second.
        final boolean standing = old.equals(next.fingerprint());
        final OrganizationWrites.Change made =
                new OrganizationWrites.Change(
                        new OrganizationWrites.Acting(login, key),
                        standing ? NO_REVISION : base != null ? base : organization.revision(),
                        change);
        final OrganizationWrites.Written filed;
        try {
            filed =
                    IdentityOrganization.filed(
                            () ->
                                    writes.replaceKeyWithBackup(
                                            made,
                                            scope.host,
                                            Files.readString(
                                                    java.util.Objects.requireNonNull(card))));
        } catch (IdentityChange.Refused ex) {
            if (standing && OrganizationQuestions.Refused.MOVED_ON.equals(ex.reason())) {
                throw new IdentityChange.Refused(
                        65,
                        OrganizationQuestions.Refused.EXISTS,
                        "the new card's key stands already, by a change this backup key did not"
                                + " make");
            }
            // The store names the key that did not sign: an agent short of the new key is not a
            // file that stayed shut.
            if (NO_KEY.equals(ex.reason()) && ex.words().contains(backup)) {
                throw new IdentityChange.Refused(
                        65,
                        "wrong-passphrase",
                        "the backup key's file did not sign: its passphrase was wrong, or no"
                                + " terminal could ask it");
            }
            throw ex;
        }
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put("id", filed.id());
        answer.put("scope", scope(organization));
        answer.put("revision", filed.revision());
        answer.put("again", filed.again());
        answer.put("of", "organization");
        answer.put("pushed", true);
        // Asked again, the place holds the new key; what it replaced is on the card kept here.
        answer.put("replaced", standing ? own.fingerprint() : old);
        answer.put("by", next.fingerprint());
        return answer(
                answer,
                "replaced "
                        + answer.get("replaced")
                        + " by "
                        + next.fingerprint()
                        + " in "
                        + filed.revision());
    }

    private Map<String, @Nullable Object> scope(final IdentityOrganization.Reading organization) {
        final Map<String, @Nullable Object> named = new LinkedHashMap<>();
        named.put("kind", scope.organization ? "organization" : "host");
        named.put("name", scope.organization ? organization.address() : scope.host);
        return named;
    }

    private String where() {
        return scope.organization ? "the organization" : "host " + scope.host;
    }

    private int answer(final Map<String, @Nullable Object> answer, final String text) {
        final PrintWriter said = spec.commandLine().getOut();
        said.println(json ? Json.write(AnswerText.printable(answer)) : text);
        said.flush();
        return 0;
    }
}
