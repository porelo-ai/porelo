package org.fuin.porelo.app;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.gittree.ForgeAddress;
import org.fuin.porelo.organization.gittree.GitTreeStore;
import org.fuin.porelo.organization.gittree.GitTreeWrites;
import org.fuin.porelo.organization.gittree.HttpForgeDirectory;
import org.fuin.porelo.vault.PersonCard;
import org.jspecify.annotations.Nullable;

/**
 * What founding an organization at an address needs, and how each need stands, asked by the calls
 * the founding itself makes. It writes nothing, here or on the forge, and no answer holds a token.
 */
final class OrgCreateCheck {

    private static final String READY = "ready";

    private static final String MISSING = "missing";

    private static final String CANNOT_TELL = "cannot-tell";

    private static final String NOT_KNOWN = "not-known-until-created";

    /** What is asked of the forge; a test has one of its own. */
    interface Forge {
        /**
         * Reports what the repository holds.
         *
         * @return What it holds.
         * @throws IOException When it cannot be asked.
         */
        GitTreeWrites.RepositoryState repository() throws IOException;

        /**
         * Reports whose the token is.
         *
         * @return The answer.
         */
        HttpForgeDirectory.TokenAccount token();

        /**
         * Reports whether a login owns the organization.
         *
         * @param login The login.
         * @return The answer, empty when the forge did not say.
         */
        Optional<Boolean> owns(String login);

        /**
         * Lists a login's authentication keys.
         *
         * @param login The login.
         * @return The keys, empty when the forge did not say.
         */
        Optional<List<String>> authenticationKeys(String login);

        /**
         * Lists a login's signing keys.
         *
         * @param login The login.
         * @return The keys, empty when the forge did not say.
         */
        Optional<List<String>> signingKeys(String login);
    }

    /** Opens what is asked at an address; replaced by a test. */
    @FunctionalInterface
    interface Asker {
        Forge ask(PoreloContext machine, String address);
    }

    private OrgCreateCheck() {}

    /** The forge at an address, by the same calls a founding makes. */
    static Forge real(final PoreloContext machine, final String address) {
        final ForgeAddress origin = ForgeAddress.of(address);
        final HttpForgeDirectory directory =
                new HttpForgeDirectory(
                        origin.forge(),
                        OrganizationAccess.forgeToken(machine, origin),
                        OrganizationAccess.http(machine));
        return new Forge() {
            @Override
            public GitTreeWrites.RepositoryState repository() throws IOException {
                return GitTreeWrites.repositoryState(
                        address,
                        machine.runner(),
                        OrganizationAccess.lent(
                                machine, machine.environment().apply("SSH_AUTH_SOCK")));
            }

            @Override
            public HttpForgeDirectory.TokenAccount token() {
                return directory.tokenAccount();
            }

            @Override
            public Optional<Boolean> owns(final String login) {
                return directory.owns(origin.organization(), login);
            }

            @Override
            public Optional<List<String>> authenticationKeys(final String login) {
                return directory.authenticationKeys(login);
            }

            @Override
            public Optional<List<String>> signingKeys(final String login) {
                return directory.signingKeys(login);
            }
        };
    }

    /**
     * Asks each need in the order a person meets it.
     *
     * @param machine The machine.
     * @param address The address asked about.
     * @param replace Whether the founding would replace the organization this machine has.
     * @param forge What is asked.
     * @return The answer.
     * @throws IOException When this machine's own files cannot be read.
     */
    static Map<String, @Nullable Object> answer(
            final PoreloContext machine,
            final String address,
            final boolean replace,
            final Forge forge)
            throws IOException {
        final ForgeAddress origin = ForgeAddress.of(address);
        final boolean ssh = !address.startsWith("https://");
        final List<Map<String, String>> needs = new ArrayList<>();
        final String held = OrgAccess.repository(machine).orElse(null);
        needs.add(
                held != null && !held.equals(address) && !replace
                        ? need(
                                "this-machine",
                                MISSING,
                                "this machine has another organization",
                                "ask with --replace to put this one in its place")
                        : need("this-machine", READY, "it can follow this address", ""));
        final @Nullable PersonCard card = card(machine, needs);
        token(machine, origin, forge, card, needs);
        if (card != null) {
            listed(card, ssh, origin, forge, needs);
        }
        repository(address, ssh, forge, needs);
        needs.add(
                need(
                        "push",
                        NOT_KNOWN,
                        "only the push itself says whether the forge takes it",
                        ""));
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put("address", ForgeAddress.withoutUserinfo(address));
        answer.put("ready", needs.stream().noneMatch(each -> MISSING.equals(each.get("state"))));
        answer.put("followable", ssh);
        answer.put("needs", List.copyOf(needs));
        return answer;
    }

    /**
     * The lines of the answer for a person.
     *
     * @param needs The answer's needs.
     * @return One line for each.
     */
    static List<String> lines(final List<?> needs) {
        final List<String> lines = new ArrayList<>();
        for (final Object each : needs) {
            if (each instanceof Map<?, ?> need) {
                final Object next = need.get("next");
                lines.add(
                        need.get("name")
                                + " "
                                + need.get("state")
                                + ": "
                                + need.get("words")
                                + (next == null || "".equals(next) ? "" : "; next: " + next));
            }
        }
        return lines;
    }

    private static @Nullable PersonCard card(
            final PoreloContext machine, final List<Map<String, String>> needs) throws IOException {
        final PersonCard card;
        try {
            card = OrgWriting.ownCard(machine);
        } catch (Refused ex) {
            needs.add(
                    need(
                            "card",
                            MISSING,
                            String.valueOf(ex.getMessage()),
                            "porelo identity create"));
            return null;
        }
        needs.add(need("card", READY, "your card is here, for " + card.login(), ""));
        try {
            OrgWriting.served(machine.environment().apply("SSH_AUTH_SOCK"), card.fingerprint());
            needs.add(need("key", READY, "your agent serves " + card.fingerprint(), ""));
        } catch (Refused ex) {
            needs.add(
                    need(
                            "key",
                            MISSING,
                            "your agent does not serve " + card.fingerprint(),
                            "open the key store that holds it, or add it to your agent"));
        }
        return card;
    }

    private static void token(
            final PoreloContext machine,
            final ForgeAddress origin,
            final Forge forge,
            final @Nullable PersonCard card,
            final List<Map<String, String>> needs) {
        final String declare =
                "porelo credentials declare "
                        + origin.forge()
                        + " --kind token --vault NAME, porelo vault put NAME";
        final HttpForgeDirectory.TokenAccount account = forge.token();
        switch (account.state()) {
            case NONE ->
                    needs.add(
                            need(
                                    "token",
                                    MISSING,
                                    "the forge must say that you own this organization, and that"
                                            + " takes a token",
                                    declare));
            case REFUSED ->
                    needs.add(need("token", MISSING, "the forge refused the token", declare));
            case NOT_REACHED ->
                    needs.add(
                            need(
                                    "token",
                                    CANNOT_TELL,
                                    "the forge did not answer",
                                    "see that it can be reached"));
            case CANNOT_SAY ->
                    needs.add(
                            need(
                                    "token",
                                    CANNOT_TELL,
                                    "the forge will not say whose this token is",
                                    "see that it is your account's"));
            case ACCEPTED -> {
                final String login = String.valueOf(account.login());
                if (card != null && !login.equals(card.login())) {
                    needs.add(
                            need(
                                    "token",
                                    MISSING,
                                    "the token is "
                                            + login.replaceAll("[\\p{Cc}\\p{Cf}]", "?")
                                            + "'s, and your card is "
                                            + card.login()
                                            + "'s",
                                    "use a token of " + card.login()));
                    return;
                }
                needs.add(need("token", READY, "the forge takes it, as " + login + "'s", ""));
            }
        }
        if (card != null) {
            owner(card, origin, forge, needs);
        }
    }

    private static void owner(
            final PersonCard card,
            final ForgeAddress origin,
            final Forge forge,
            final List<Map<String, String>> needs) {
        final Optional<Boolean> owns = forge.owns(card.login());
        if (owns.isEmpty()) {
            needs.add(
                    need(
                            "owner",
                            CANNOT_TELL,
                            "the forge did not say who owns " + origin.organization(),
                            "see that the token may read the organization's members"));
        } else if (owns.orElseThrow()) {
            needs.add(need("owner", READY, card.login() + " owns " + origin.organization(), ""));
        } else {
            needs.add(
                    need(
                            "owner",
                            MISSING,
                            card.login() + " does not own " + origin.organization(),
                            "have an owner make " + card.login() + " one on the forge"));
        }
    }

    private static void listed(
            final PersonCard card,
            final boolean ssh,
            final ForgeAddress origin,
            final Forge forge,
            final List<Map<String, String>> needs) {
        final String page = "add it at " + origin.forge() + " for " + card.login();
        final Optional<List<String>> authentication = forge.authenticationKeys(card.login());
        final boolean authenticates =
                authentication.isPresent()
                        && PersonCard.lists(authentication.orElseThrow(), card.fingerprint());
        if (authenticates) {
            needs.add(need("key-listed", READY, "the forge lists it to log in", ""));
            return;
        }
        final Optional<List<String>> signing = forge.signingKeys(card.login());
        final boolean signs =
                signing.isPresent() && PersonCard.lists(signing.orElseThrow(), card.fingerprint());
        if (signs && !ssh) {
            needs.add(need("key-listed", READY, "the forge lists it to sign with", ""));
        } else if (signs) {
            needs.add(
                    need(
                            "key-listed",
                            MISSING,
                            "the forge lists it to sign with, and an ssh address also logs in"
                                    + " with it",
                            page + ", as an authentication key"));
        } else if (authentication.isEmpty() || signing.isEmpty()) {
            needs.add(
                    need(
                            "key-listed",
                            CANNOT_TELL,
                            "the forge did not say all it lists, as under a rate limit",
                            "ask again later"));
        } else {
            needs.add(need("key-listed", MISSING, "the forge does not list your key", page));
        }
    }

    private static void repository(
            final String address,
            final boolean ssh,
            final Forge forge,
            final List<Map<String, String>> needs)
            throws IOException {
        final GitTreeWrites.RepositoryState state;
        try {
            state = forge.repository();
        } catch (GitTreeStore.Unreachable ex) {
            final Refused said = OrgAccess.unreached(ex, "the repository cannot be read", address);
            if (Refused.UNKNOWN_HOST_KEY.equals(said.reason())) {
                needs.add(
                        need(
                                "host-key",
                                MISSING,
                                "the forge's ssh host key is not trusted on this machine",
                                "porelo credentials trust-host " + HostKeys.nameOf(address)));
            } else {
                needs.add(
                        need(
                                "repository",
                                MISSING,
                                "the repository cannot be read with what this machine has",
                                ssh
                                        ? "see that your key may read it"
                                        : "see that the token may read it"));
            }
            needs.add(need("empty", CANNOT_TELL, "it could not be read", ""));
            return;
        }
        if (ssh) {
            needs.add(need("host-key", READY, "the forge's ssh host key is trusted", ""));
        }
        if (state == GitTreeWrites.RepositoryState.NOT_THERE) {
            needs.add(
                    need(
                            "repository",
                            MISSING,
                            "the repository does not exist, or may not be seen",
                            "make it on the forge first, empty"));
            needs.add(need("empty", CANNOT_TELL, "it could not be read", ""));
            return;
        }
        needs.add(need("repository", READY, "it can be read", ""));
        needs.add(
                state == GitTreeWrites.RepositoryState.EMPTY
                        ? need("empty", READY, "it holds nothing", "")
                        : need(
                                "empty",
                                MISSING,
                                "the repository is not empty",
                                "name an empty one, or follow it with porelo setup --organization"));
    }

    private static Map<String, String> need(
            final String name, final String state, final String words, final String next) {
        final Map<String, String> need = new LinkedHashMap<>();
        need.put("name", name);
        need.put("state", state);
        need.put("words", words);
        need.put("next", next);
        return need;
    }
}
