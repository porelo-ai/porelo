package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.vault.AgentKey;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.PersonCard;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Replaces a lost or stolen key: a new card signed by the new key alone, which files nothing. It is
 * answered, with the owners who may accept it, for the person to hand over outside Porelo.
 */
@Command(
        name = "revoke",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description =
                "Replaces a lost or stolen key with one the ssh-agent serves, and answers the new"
                        + " card and the owners who may accept it. It files nothing.")
public class IdentityRevokeCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    /** What holds for anyone who still has a backup key, in the order that it holds in. */
    static final String ORDER =
            "A backup key works only while the old key is still on the forge: first use the backup"
                    + " with porelo identity backup-key --use, then take the old key off the"
                    + " forge. After that only an owner's porelo org person replace helps.";

    @Option(names = "--lost", description = "The old key is lost or stolen.")
    private boolean lost;

    @Option(
            names = "--key",
            paramLabel = "FINGERPRINT|PUBFILE",
            description = "The new key, as the ssh-agent serves it.")
    private @Nullable String key;

    @Option(
            names = "--keepassxc",
            paramLabel = "DATABASE",
            description =
                    "Makes the new key into this KeePassXC database, which hands it to the"
                            + " agent.")
    private @Nullable Path keepassxc;

    @Option(
            names = "--entry",
            paramLabel = "GROUP/NAME",
            description = "The KeePassXC entry, with --keepassxc.")
    private @Nullable String entry;

    @Option(
            names = "--dry-run",
            description = "Makes nothing; answers who must accept and what is made again.")
    private boolean dryRun;

    @Option(
            names = "--id",
            paramLabel = "ID",
            description = "The operation's id; the same id again answers the same operation.")
    private @Nullable String id;

    @Option(names = "--json", description = "Answers the new card and its plan as JSON.")
    private boolean json;

    @Spec private CommandSpec spec;

    private @Nullable PoreloContext context;

    /** Replaced by a test, which runs a stand-in keepassxc-cli and waits less. */
    KeePassXcKey store = KeePassXcKey.real();

    /** Replaced by a test, which has no forge to ask. */
    IdentityOrganization.Opener opener = IdentityOrganization::real;

    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() throws IOException {
        final PoreloContext machine =
                java.util.Objects.requireNonNull(context, "picocli's factory sets the context");
        try {
            if (!lost) {
                throw new IdentityChange.Refused(
                        64, null, "revoke replaces a lost or stolen key: give --lost");
            }
            IdentityChange.checkId(id);
            if (dryRun && id != null) {
                throw new IdentityChange.Refused(
                        64, null, "--dry-run keeps nothing, so it takes no --id");
            }
            final @Nullable String named = IdentityChange.keyChosen(key, keepassxc, entry);
            final IdentityOrganization.Reading organization =
                    IdentityOrganization.read(machine, opener);
            final IdentityOperation again =
                    IdentityChange.kept(machine, id, IdentityOperation.REVOKE_LOST);
            if (again != null) {
                answer(IdentityChange.finish(machine, again), true, organization, null);
                return 0;
            }
            final IdentityChange change = new IdentityChange(machine);
            final String login = change.card().login();
            if (organization != null && !organization.stale() && !dryRun) {
                change.finishWhatReached(organization, login, id);
            }
            final PersonCard card = change.card();
            final String socket = change.socket();
            if (dryRun && named == null) {
                // A key made into KeePassXC is new, so nothing about it changes the plan.
                dryRun(login, organization);
                return 0;
            }
            final KeePassXcKey.Made made =
                    named != null
                            ? null
                            : store.make(
                                    machine,
                                    java.util.Objects.requireNonNull(
                                            keepassxc, "either --key or --keepassxc is given"),
                                    entry,
                                    login,
                                    socket);
            final AgentKey successor =
                    change.successor(
                            made == null
                                    ? java.util.Objects.requireNonNull(named, "--key is given")
                                    : made.fingerprint(),
                            login,
                            socket,
                            organization);
            final String fingerprint = PersonCard.fingerprint(PersonCard.keyLine(successor));
            if (fingerprint.equals(card.fingerprint())) {
                throw new IdentityChange.Refused(
                        64, null, "the key " + fingerprint + " is the lost key itself");
            }
            if (dryRun) {
                dryRun(login, organization);
                return 0;
            }
            final String text =
                    IdentityChange.signed(
                            login,
                            card.name(),
                            card.email(),
                            successor,
                            IdentityChange.today(),
                            null);
            if (organization != null && !organization.stale()) {
                change.replaceWhatReachedNowhere(organization, login, id);
            }
            final IdentityChange.Begun begun =
                    change.begin(
                            id,
                            new IdentityOperation(
                                    "",
                                    IdentityOperation.REVOKE_LOST,
                                    fingerprint,
                                    card.fingerprint(),
                                    text,
                                    false,
                                    null,
                                    false));
            final IdentityOperation operation =
                    begun.fresh() ? change.keep(begun.operation()) : begun.operation();
            answer(operation, !begun.fresh(), organization, made);
            return 0;
        } catch (IdentityChange.Refused ex) {
            return IdentityChange.refuse(spec, json, ex);
        } catch (IOException | CardException ex) {
            return IdentityChange.refuse(
                    spec,
                    json,
                    new IdentityChange.Refused(66, null, String.valueOf(ex.getMessage())));
        }
    }

    private void answer(
            final IdentityOperation operation,
            final boolean again,
            final IdentityOrganization.@Nullable Reading organization,
            final KeePassXcKey.@Nullable Made made) {
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put("revision", organization == null ? null : organization.revision());
        answer.put("stale", organization != null && organization.stale());
        answer.put("id", operation.id());
        answer.put("again", again);
        answer.put("action", operation.action());
        answer.put("fingerprint", operation.fingerprint());
        answer.put("predecessor", operation.predecessor());
        answer.put("card", operation.card());
        answer.put("plan", plan(organization));
        final List<String> text = new ArrayList<>();
        text.add(
                operation.action()
                        + " "
                        + operation.id()
                        + ": "
                        + operation.predecessor()
                        + " -> "
                        + operation.fingerprint());
        text.addAll(planLines(organization));
        IdentityChange.print(spec, json, answer, text, made);
    }

    /** Who may accept the new card, how, and what must come first. */
    private static Map<String, @Nullable Object> plan(
            final IdentityOrganization.@Nullable Reading organization) {
        final Map<String, @Nullable Object> plan = new LinkedHashMap<>();
        plan.put("who_may", IdentityFacts.owners(organization));
        plan.put("accepted_with", "porelo org person replace LOGIN --card FILE");
        plan.put("order", ORDER);
        return plan;
    }

    private static List<String> planLines(
            final IdentityOrganization.@Nullable Reading organization) {
        final List<String> logins =
                IdentityFacts.owners(organization).stream()
                        .map(owner -> String.valueOf(owner.get("login")))
                        .toList();
        return List.of(
                "nothing is filed; hand the card to "
                        + (logins.isEmpty() ? "an owner" : "an owner: " + String.join(", ", logins))
                        + ", who accepts it with porelo org person replace LOGIN --card FILE",
                ORDER);
    }

    /**
     * Answers what a replacement would reach: who accepts it, each host whose vault opened by the
     * old key is made again, and the sign-ins each such vault loses.
     */
    private void dryRun(
            final String login, final IdentityOrganization.@Nullable Reading organization) {
        final List<Map<String, @Nullable Object>> hosts = new ArrayList<>();
        final List<Map<String, @Nullable Object>> signIns = new ArrayList<>();
        for (final String host : hosts(login, organization)) {
            final Map<String, @Nullable Object> vault = new LinkedHashMap<>();
            vault.put("host", host);
            vault.put("vault", "reset");
            hosts.add(vault);
            // Which entries a host vault holds is not known here yet: none is named.
            final Map<String, @Nullable Object> signIn = new LinkedHashMap<>();
            signIn.put("host", host);
            signIn.put("entries", List.of());
            signIns.add(signIn);
        }
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put("revision", organization == null ? null : organization.revision());
        answer.put("stale", organization != null && organization.stale());
        answer.put("plan", plan(organization));
        answer.put("hosts", hosts);
        answer.put("sign_ins", signIns);
        final List<String> text = new ArrayList<>(planLines(organization));
        hosts.forEach(host -> text.add("host " + host.get("host") + " vault made again"));
        IdentityChange.print(spec, json, answer, text, null);
    }

    /** The hosts this login is on: those carrying a project they may act in, and those they run. */
    private static Set<String> hosts(
            final String login, final IdentityOrganization.@Nullable Reading organization) {
        final Set<String> hosts = new LinkedHashSet<>();
        if (organization != null) {
            final OrganizationQuestions state = organization.answers();
            hosts.addAll(state.hostsFor(login));
            state.getHosts().stream()
                    .map(OrganizationQuestions.Host::host)
                    .filter(host -> state.isHostAdmin(host, login))
                    .forEach(hosts::add);
        }
        return hosts;
    }
}
