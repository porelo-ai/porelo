package org.fuin.porelo.organization.gittree;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.organization.IdentitySource;
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.OrganizationWrites.Acting;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.PersonCard;
import org.jspecify.annotations.Nullable;

/** A person's own signed first card, waiting outside main until an admin accepts its commit. */
public final class CardProposals {
    /** The forge's pull request; its commit is fetched from the organization's own pull ref. */
    public record Pull(
            long number,
            String url,
            String author,
            String branch,
            String base,
            String commit,
            String opened,
            boolean open,
            boolean merged,
            String sourceOwner) {}

    /** What the forge alone knows; git and TreeWalk judge everything the proposal carries. */
    public interface Forge {
        /** Returns the authenticated forge login. */
        String login();

        /** Lists open pull requests, including all pages. */
        List<Pull> list();

        /** Reads one pull request afresh. */
        Pull read(long number);

        /** Forks into this login, waiting until git can read it, and returns its git address. */
        String fork(String login);

        /** Opens a pull request from the person's branch, in the organization or their fork. */
        Pull open(String login, boolean fork);

        /** Closes a pull request and leaves the reason as a comment. */
        void close(long number, String comment);
    }

    /** A command's state and its pull request URL, absent when the card was filed directly. */
    public record Result(String state, @Nullable String pullRequest) {}

    /** A judged proposal, with a reason when it is stale or refused. */
    public record Proposal(
            String login,
            String name,
            String fingerprint,
            Pull pullRequest,
            String state,
            String reason) {}

    private final CommandRunner runner;
    private final String address;
    private final String organization;
    private final FetchAccess access;
    private final IdentitySource identities;
    private final CommitSigning signing;
    private final Forge forge;
    private final Clock clock;
    private final @Nullable String minimum;

    /**
     * Opens proposals with the credentials, identity source and signing used for organization
     * writes.
     */
    public CardProposals(
            CommandRunner runner,
            String address,
            String organization,
            FetchAccess access,
            IdentitySource identities,
            CommitSigning signing,
            Forge forge,
            Clock clock) {
        this(runner, address, organization, access, identities, signing, forge, clock, null);
    }

    private CardProposals(
            CommandRunner runner,
            String address,
            String organization,
            FetchAccess access,
            IdentitySource identities,
            CommitSigning signing,
            Forge forge,
            Clock clock,
            @Nullable String minimum) {
        this.runner = runner;
        this.address = address;
        this.organization = organization;
        this.access = access;
        this.identities = identities;
        this.signing = signing;
        this.forge = forge;
        this.clock = clock;
        this.minimum = minimum;
    }

    /** Returns a service whose fetched tips must contain the revision already trusted here. */
    public CardProposals standingAt(@Nullable String revision) {
        return new CardProposals(
                runner, address, organization, access, identities, signing, forge, clock, revision);
    }

    /**
     * Files the kept card or proposes its own commit; withdrawing closes only this person's
     * proposal.
     */
    public Result join(Acting acting, String text, boolean withdraw) throws IOException {
        if (!forge.login().equals(acting.login())) {
            throw new Refused(
                    Refused.NOT_ALLOWED, "the forge token belongs to another login", null);
        }
        final Pull own = own(acting.login(), false);
        if (withdraw) {
            if (own == null)
                throw new Refused(Refused.NOT_FOUND, "no open proposal of yours", null);
            forge.close(
                    own.number(), "Withdrawn by the person through porelo org join --withdraw.");
            return new Result("withdrawn", own.url());
        }
        try (TreeRepository clone = TreeRepository.fetch(address, runner, access)) {
            final String tip = clone.tip();
            final TreeRecord before = walk(clone);
            reached(before, tip);
            final String path = path(acting.login());
            if (clone.object(tip, path).isPresent()) {
                if (answers(before)
                                .trusts(acting.login(), acting.key())
                                .answer()
                                .equals(OrganizationQuestions.Trust.YES)
                        && clone.text(clone.object(tip, path).orElseThrow()).equals(text)) {
                    return new Result("accepted", own == null ? null : own.url());
                }
                throw new Refused(Refused.EXISTS, "main already holds " + path, tip);
            }
            final PersonCard card = PersonCard.read(text, "the kept card");
            if (!card.login().equals(acting.login())
                    || !card.fingerprint().equals(PersonCard.fingerprint(acting.key()))) {
                throw new Refused(
                        Refused.NOT_ALLOWED,
                        "the kept card is not of the acting login and key",
                        tip);
            }
            final String commit =
                    clone.commit(
                            tip,
                            Map.of(path, text),
                            "Propose card for " + acting.login(),
                            "card-" + UUID.randomUUID(),
                            signing,
                            acting.key(),
                            acting.login());
            final Pull local =
                    new Pull(
                            1,
                            "",
                            acting.login(),
                            "porelo-card-" + acting.login(),
                            "main",
                            commit,
                            "",
                            true,
                            false,
                            organization);
            final Proposal judged = judge(clone, before, tip, local, commit);
            if (!judged.state().equals("ready"))
                throw new Refused(Refused.REFUSED_STATE, judged.reason(), tip);
            if (clone.pushLeased(address, commit, "refs/heads/main", tip, access)) {
                if (own != null)
                    forge.close(
                            own.number(),
                            "Filed the person's card directly through porelo org join.");
                return new Result("filed", null);
            }
            if (!clone.mainAt(address, access).equals(tip)) {
                throw new Refused(Refused.MOVED_ON, "main moved; run porelo org join again", tip);
            }
            final String ref = "refs/heads/porelo-card-" + acting.login();
            boolean fork =
                    own != null
                            && !own.sourceOwner().isEmpty()
                            && !own.sourceOwner().equals(organization);
            if (fork && own != null && !own.sourceOwner().equals(acting.login())) {
                throw new Refused(
                        Refused.REFUSED_STATE,
                        "the open proposal's branch belongs to another account; withdraw it before joining again",
                        tip);
            }
            String target = fork ? forge.fork(acting.login()) : address;
            String expected = clone.remoteTip(target, ref, access);
            if (!clone.pushLeased(target, commit, ref, expected, access)) {
                if (own != null)
                    throw new Refused(
                            Refused.NOT_ALLOWED,
                            "the open proposal's branch refused the update; check its permissions or withdraw it before joining again",
                            tip);
                target = forge.fork(acting.login());
                fork = true;
                expected = clone.remoteTip(target, ref, access);
                if (!clone.pushLeased(target, commit, ref, expected, access)) {
                    throw new Refused(
                            Refused.NOT_ALLOWED,
                            "the fork refused the branch; check your forge token and run porelo org join again",
                            tip);
                }
            }
            final Pull pull =
                    own == null ? forge.open(acting.login(), fork) : forge.read(own.number());
            return new Result("proposed", pull.url());
        } catch (CardException ex) {
            throw new Refused(Refused.REFUSED_STATE, String.valueOf(ex.getMessage()), null);
        }
    }

    /** Judges every own-branch, own-author proposal on a fresh main. */
    public List<Proposal> proposals() throws IOException {
        final java.util.ArrayList<Proposal> proposals = new java.util.ArrayList<>();
        for (Pull listed : forge.list()) {
            if (!candidate(listed)) continue;
            final Pull pull = forge.read(listed.number());
            if (!candidate(pull)) continue;
            try (TreeRepository clone = TreeRepository.fetch(address, runner, access)) {
                final String tip = clone.tip();
                final TreeRecord before = walk(clone);
                reached(before, tip);
                proposals.add(
                        judge(
                                clone,
                                before,
                                tip,
                                pull,
                                clone.proposal(address, pull.number(), access)));
            }
        }
        return List.copyOf(proposals);
    }

    /** Checks the admin afresh, judges again, and fast-forwards the unchanged person's commit. */
    public Result accept(Acting acting, String login) throws IOException {
        try (TreeRepository clone = TreeRepository.fetch(address, runner, access)) {
            final String tip = clone.tip();
            final TreeRecord before = walk(clone);
            reached(before, tip);
            admin(before, acting);
            final Pull pull =
                    java.util.Objects.requireNonNull(own(login, true), "required proposal exists");
            final String head = clone.proposal(address, pull.number(), access);
            final Proposal proposal = judge(clone, before, tip, pull, head);
            if (!proposal.state().equals("ready")) {
                throw new Refused(
                        Refused.REFUSED_STATE, proposal.state() + ": " + proposal.reason(), tip);
            }
            if (!clone.pushLeased(address, head, "refs/heads/main", tip, access)) {
                throw new Refused(
                        Refused.MOVED_ON,
                        "main moved or the push was refused; read proposals again",
                        tip);
            }
            if (!forge.read(pull.number()).merged()) {
                forge.close(
                        pull.number(),
                        "Accepted the person's unchanged signed commit "
                                + head
                                + " through porelo org person accept.");
            }
            return new Result("accepted", pull.url());
        }
    }

    /** Checks the admin afresh before closing a person's proposal with a comment. */
    public Result decline(Acting acting, String login) throws IOException {
        try (TreeRepository clone = TreeRepository.fetch(address, runner, access)) {
            final TreeRecord before = walk(clone);
            reached(before, clone.tip());
            admin(before, acting);
            final Pull pull =
                    java.util.Objects.requireNonNull(own(login, true), "required proposal exists");
            forge.close(
                    pull.number(),
                    "Declined by an organization admin through porelo org person decline.");
            return new Result("declined", pull.url());
        }
    }

    private static String path(String login) {
        if (!PersonCard.isLogin(login))
            throw new Refused(Refused.USAGE, "expected a person's login", null);
        return "people/" + login + ".card";
    }

    private boolean candidate(Pull pull) {
        // A proposal comes from the person's own fork or the organization's repository, never from
        // a third account's fork or one the forge no longer names.
        return PersonCard.isLogin(pull.author())
                && (pull.sourceOwner().equals(pull.author())
                        || pull.sourceOwner().equals(organization))
                && pull.open()
                && pull.base().equals("main")
                && pull.branch().equals("porelo-card-" + pull.author());
    }

    private @Nullable Pull own(String login, boolean required) {
        path(login);
        Pull found = null;
        for (Pull listed : forge.list()) {
            if (candidate(listed) && listed.author().equals(login)) {
                final Pull fresh = forge.read(listed.number());
                if (!candidate(fresh) || !fresh.author().equals(login)) continue;
                if (found != null)
                    throw new Refused(
                            Refused.REFUSED_STATE, "more than one proposal for " + login, null);
                found = fresh;
            }
        }
        if (required && found == null)
            throw new Refused(Refused.NOT_FOUND, "no open proposal for " + login, null);
        return found;
    }

    private Proposal judge(
            TreeRepository clone, TreeRecord before, String tip, Pull pull, String head)
            throws IOException {
        String name = "";
        String fingerprint = "";
        final String login = pull.author();
        try {
            if (!head.equals(pull.commit()))
                throw new CardException("the pull request head changed; read proposals again");
            final List<String> parents = clone.parents(head);
            if (parents.size() != 1 || clone.commitsBeyond(head, tip) != 1) {
                throw new CardException(
                        "a proposal must contain exactly one commit with one parent");
            }
            final String parent = parents.getFirst();
            final String path = path(login);
            if (!clone.changed(head, parent).equals(List.of(path)))
                throw new CardException("a proposal must add only " + path);
            if (clone.object(tip, path).isPresent() || clone.object(parent, path).isPresent()) {
                throw new CardException(
                        "the card's path must be absent on main and added by the proposal");
            }
            final PersonCard card =
                    PersonCard.read(clone.text(clone.object(head, path).orElseThrow()), path);
            name = card.name();
            fingerprint = card.fingerprint();
            if (!card.login().equals(login))
                throw new CardException("the card names another login");
            if (!card.predecessor().isEmpty())
                throw new CardException("a first card must name no predecessor");
            final var holder = answers(before).keyHolder(card.key());
            if (holder.isPresent() && !holder.orElseThrow().equals(login))
                throw new CardException(
                        "the key is held by another login: " + holder.orElseThrow());
            final LocalDate today = LocalDate.now(clock);
            if (card.created().isAfter(today) || card.expires().isBefore(today))
                throw new CardException(
                        "today must lie between the card's created and expires days");
            if (!card.signedAsItMustBe())
                throw new CardException("the card's own signature does not verify");
            if (!clone.signedBy(head, fingerprint))
                throw new CardException("the commit is not signed by the card's own key");
            if (!parent.equals(tip))
                return new Proposal(
                        login,
                        name,
                        fingerprint,
                        pull,
                        "stale",
                        "main moved; run porelo org join again");
            clone.pointAt(head);
            final TreeRecord after = walk(clone);
            for (Organization.Refusal refusal : after.refusals()) {
                if (refusal.revision().equals(head)) throw new CardException(refusal.why());
            }
            reached(after, head);
            if (!answers(after)
                    .trusts(login, card.key())
                    .answer()
                    .equals(OrganizationQuestions.Trust.YES))
                throw new CardException("TreeWalk did not trust the proposed card");
            return new Proposal(login, name, fingerprint, pull, "ready", "");
        } catch (CardException | java.util.NoSuchElementException ex) {
            return new Proposal(
                    login, name, fingerprint, pull, "refused", String.valueOf(ex.getMessage()));
        }
    }

    private TreeRecord walk(TreeRepository clone) throws IOException {
        if (minimum != null && !clone.hasAncestor(clone.tip(), minimum)) {
            throw new Refused(
                    Refused.REFUSED_STATE,
                    "the organization went back behind the revision trusted here; ask an organization admin to restore its current main",
                    minimum);
        }
        final TreeWalk walk = new TreeWalk(clone, identities, organization);
        walk.readChains();
        return walk.read(null, null);
    }

    private static void reached(TreeRecord record, String tip) {
        if (!tip.equals(record.commit()))
            throw new Refused(
                    Refused.CANNOT_SAY,
                    "TreeWalk could not judge main or the proposal to its tip; check the forge token and read again",
                    record.commit());
    }

    private static OrganizationQuestions answers(TreeRecord record) {
        return new GitTreeOrganization(
                record.commit(),
                record.model(),
                record.refusals(),
                List.of(),
                new OrganizationQuestions.Description("", "", ""),
                true);
    }

    private static void admin(TreeRecord record, Acting acting) {
        final OrganizationQuestions answers = answers(record);
        if (!answers.isOrganizationAdmin(acting.login())
                || !answers.trusts(acting.login(), acting.key())
                        .answer()
                        .equals(OrganizationQuestions.Trust.YES)
                || record.model().revoked().contains(PersonCard.fingerprint(acting.key()))) {
            throw new Refused(
                    Refused.NOT_ALLOWED,
                    "accept and decline require an organization admin and that login's trusted, unrevoked key in the agent",
                    record.commit());
        }
    }
}
