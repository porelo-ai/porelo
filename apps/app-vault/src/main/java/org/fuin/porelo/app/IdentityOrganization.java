package org.fuin.porelo.app;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.organization.IdentitySource;
import org.fuin.porelo.organization.OrganizationException;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.OrganizationWrites;
import org.fuin.porelo.organization.git.HttpForgeDirectory;
import org.fuin.porelo.organization.git.RegistryOrigin;
import org.fuin.porelo.organization.gittree.CommitSigning;
import org.fuin.porelo.organization.gittree.FetchAccess;
import org.fuin.porelo.organization.gittree.GitTreeStore;
import org.fuin.porelo.organization.gittree.GitTreeWrites;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** How the identity verbs reach the organization this machine's settings name. */
final class IdentityOrganization {

    private static final String ADAPTER = "git";

    private static final Map<String, Integer> EXITS =
            Map.ofEntries(
                    Map.entry(OrganizationQuestions.Refused.USAGE, 64),
                    Map.entry(OrganizationQuestions.Refused.NOT_ALLOWED, 65),
                    Map.entry(OrganizationQuestions.Refused.MOVED_ON, 65),
                    Map.entry(OrganizationQuestions.Refused.LOCKED, 65),
                    Map.entry(OrganizationQuestions.Refused.EXISTS, 65),
                    Map.entry(OrganizationQuestions.Refused.LAST_OF_ITS_KIND, 65),
                    Map.entry(OrganizationQuestions.Refused.NO_CARD, 65),
                    Map.entry(OrganizationQuestions.Refused.REFUSED_STATE, 65),
                    Map.entry(OrganizationQuestions.Refused.NOT_FOUND, 66),
                    Map.entry(OrganizationQuestions.Refused.CANNOT_SAY, 69),
                    Map.entry(OrganizationQuestions.Refused.NO_KEY, 69),
                    Map.entry(OrganizationQuestions.Refused.NO_ORGANIZATION, 78));

    /** A reason nobody expects: the change may have been made. */
    private static final int FAILED = 70;

    /** How the adapter says its fetch failed, before anything could be pushed. */
    private static final String UNFETCHED = "cannot read the organization's repository";

    private IdentityOrganization() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** The organization's writes, signing as they are told. */
    @FunctionalInterface
    interface Writes {

        /**
         * Returns the writes.
         *
         * @param signing How the acting person signs.
         * @return The writes.
         */
        OrganizationWrites signingWith(CommitSigning signing);
    }

    /**
     * A store opened, and who it asks.
     *
     * @param store The store.
     * @param identities Who says whose keys are whose.
     * @param forge The forge a person's keys are listed on, or {@code null} when none is known.
     * @param writes Its writes.
     */
    record Opened(
            GitTreeStore store, IdentitySource identities, @Nullable URI forge, Writes writes) {}

    /** Opens the store of an address; replaced by a test, which has no forge to ask. */
    @FunctionalInterface
    interface Opener {
        Opened open(PoreloContext context, String repository);
    }

    /**
     * What a read answered.
     *
     * @param address The organization's address, with no user part.
     * @param answers The answers.
     * @param opened The store it came from.
     * @param stale Whether this is the last trusted state, the store out of reach.
     */
    record Reading(String address, OrganizationQuestions answers, Opened opened, boolean stale) {

        /** The revision the answers rest on, or {@code null} when nothing was accepted yet. */
        @Nullable String revision() {
            return answers.revision();
        }
    }

    /** A filing, which may throw what the adapter says. */
    @FunctionalInterface
    interface Filing {
        OrganizationWrites.Written file() throws IOException;
    }

    /**
     * Reads the organization for an answer: its last trusted state when the store cannot be
     * reached.
     *
     * @param context The machine.
     * @param opener How the store is opened.
     * @return What it answered, or {@code null} when this machine has no organization.
     * @throws IdentityChange.Refused By the contract's reasons, {@code cannot-say} with no state.
     * @throws IOException When something unexpected happened.
     */
    static @Nullable Reading read(final PoreloContext context, final Opener opener)
            throws IdentityChange.Refused, IOException {
        final Optional<String> repository = repository(context);
        if (repository.isEmpty()) {
            return null;
        }
        final Opened opened = opener.open(context, repository.get());
        try {
            return fetched(repository.get(), opened);
        } catch (GitTreeStore.Unreachable ex) {
            final Optional<OrganizationQuestions> last = opened.store().last();
            if (last.isEmpty()) {
                throw cannotSay(ex);
            }
            return new Reading(address(repository.get()), last.get(), opened, true);
        }
    }

    /**
     * Reads the organization for a change, which rests on nothing stale.
     *
     * @param context The machine.
     * @param opener How the store is opened.
     * @return What it answered.
     * @throws IdentityChange.Refused 78 {@code no-organization} when this machine has none, 69
     *     {@code cannot-say} when the store cannot be reached.
     * @throws IOException When something unexpected happened.
     */
    static Reading required(final PoreloContext context, final Opener opener)
            throws IdentityChange.Refused, IOException {
        final Optional<String> repository = repository(context);
        if (repository.isEmpty()) {
            throw refused(
                    new OrganizationQuestions.Refused(
                            OrganizationQuestions.Refused.NO_ORGANIZATION,
                            "this machine has no organization: run porelo setup first",
                            null));
        }
        final Opened opened = opener.open(context, repository.get());
        try {
            return fetched(repository.get(), opened);
        } catch (GitTreeStore.Unreachable ex) {
            throw cannotSay(ex);
        }
    }

    /**
     * Reads the organization for a change when this machine has one.
     *
     * @param context The machine.
     * @param opener How the store is opened.
     * @return What it answered, or {@code null} when this machine has none.
     * @throws IdentityChange.Refused 69 {@code cannot-say} when the store cannot be reached.
     * @throws IOException When something unexpected happened.
     */
    static @Nullable Reading requiredIfAny(final PoreloContext context, final Opener opener)
            throws IdentityChange.Refused, IOException {
        return repository(context).isEmpty() ? null : required(context, opener);
    }

    /**
     * Reads the organization for a first card, which needs no state: a repository holding nothing
     * yet answers as a machine with none does.
     *
     * @param context The machine.
     * @param opener How the store is opened.
     * @return What it answered, or {@code null} when there is nothing to file in.
     * @throws IdentityChange.Refused 69 {@code cannot-say} when the store cannot be reached, or
     *     holds nothing although this machine read a state of it.
     * @throws IOException When something unexpected happened.
     */
    static @Nullable Reading requiredUnlessEmpty(final PoreloContext context, final Opener opener)
            throws IdentityChange.Refused, IOException {
        final Optional<String> repository = repository(context);
        if (repository.isEmpty()) {
            return null;
        }
        final Opened opened = opener.open(context, repository.get());
        try {
            return fetched(repository.get(), opened);
        } catch (GitTreeStore.Empty ex) {
            if (opened.store().last().isPresent()) {
                throw cannotSay(ex);
            }
            return null;
        } catch (GitTreeStore.Unreachable ex) {
            throw cannotSay(ex);
        }
    }

    private static Reading fetched(final String repository, final Opened opened)
            throws IdentityChange.Refused, IOException {
        try {
            return new Reading(
                    address(repository), opened.store().read(opened.identities()), opened, false);
        } catch (OrganizationQuestions.Refused ex) {
            throw refused(ex);
        } catch (OrganizationException | CommandException ex) {
            throw refused(
                    new OrganizationQuestions.Refused(
                            OrganizationQuestions.Refused.REFUSED_STATE,
                            String.valueOf(ex.getMessage()),
                            null));
        }
    }

    private static String address(final String repository) {
        return org.fuin.porelo.vault.Userinfo.leftOutOf(repository);
    }

    private static IdentityChange.Refused cannotSay(final IOException ex) {
        return new IdentityChange.Refused(
                69,
                OrganizationQuestions.Refused.CANNOT_SAY,
                "the organization cannot be reached, so nothing can be said of it: "
                        + ex.getMessage());
    }

    /**
     * Files a change, each way it can end named as the contract names it.
     *
     * @param filing The change.
     * @return What it made.
     * @throws IdentityChange.Refused With the contract's code and reason.
     */
    static OrganizationWrites.Written filed(final Filing filing) throws IdentityChange.Refused {
        try {
            return filing.file();
        } catch (OrganizationQuestions.Refused ex) {
            // The adapter refuses before it pushes: what is left after a push is only a failure.
            throw refused(
                            OrganizationQuestions.Refused.NO_KEY.equals(ex.reason())
                                    ? new OrganizationQuestions.Refused(
                                            ex.reason(),
                                            ex.getMessage()
                                                    + ": serve it, then ask again with the same id,"
                                                    + " or begin again with a new id and another"
                                                    + " key",
                                            ex.revision())
                                    : ex)
                    .beforeAnyPush();
        } catch (GitTreeStore.Unreachable ex) {
            final IdentityChange.Refused lost =
                    new IdentityChange.Refused(
                            69,
                            OrganizationQuestions.Refused.CANNOT_SAY,
                            "the organization cannot be reached; the change may have been made, so"
                                    + " ask again with the same id");
            throw String.valueOf(ex.getMessage()).startsWith(UNFETCHED)
                    ? lost.beforeAnyPush()
                    : lost;
        } catch (OrganizationException | CommandException ex) {
            throw new IdentityChange.Refused(
                    65,
                    OrganizationQuestions.Refused.REFUSED_STATE,
                    String.valueOf(ex.getMessage()));
        } catch (IOException ex) {
            throw new IdentityChange.Refused(ex);
        }
    }

    /**
     * Names a refusal of the adapter by the contract's exit code.
     *
     * @param refused The refusal.
     * @return It, as the verbs answer it.
     */
    static IdentityChange.Refused refused(final OrganizationQuestions.Refused refused) {
        final Map<String, @Nullable Object> more = new LinkedHashMap<>();
        more.put("revision", refused.revision());
        return new IdentityChange.Refused(
                EXITS.getOrDefault(refused.reason(), FAILED),
                refused.reason(),
                String.valueOf(refused.getMessage()),
                more);
    }

    /**
     * Opens the store as this machine does: the person's own credentials for the fetch and the
     * push, and the forge its address names for who holds which key.
     *
     * @param context The machine.
     * @param repository The address.
     * @return The store and who it asks.
     */
    static Opened real(final PoreloContext context, final String repository) {
        final RegistryOrigin origin;
        try {
            origin = RegistryOrigin.of(repository);
        } catch (IllegalArgumentException ex) {
            throw new OrganizationQuestions.Refused(
                    OrganizationQuestions.Refused.USAGE,
                    "the organization's address in the settings is none",
                    null);
        }
        final FetchAccess access = lent(context);
        final IdentitySource identities =
                new HttpForgeDirectory(
                        origin.forge(),
                        RegistryReadCommand.token(context, origin),
                        RegistryReadCommand.http(context));
        return new Opened(
                GitTreeStore.open(
                        context.runner(),
                        repository,
                        access,
                        context.paths().xdg().state().resolve("organization")),
                identities,
                origin.forge(),
                signing ->
                        new GitTreeWrites(
                                context.runner(),
                                repository,
                                origin.organisation(),
                                access,
                                identities,
                                signing));
    }

    /**
     * Lends what this person holds for the address, and their agent, which a push over ssh goes
     * through when the vault lends no key of its own.
     */
    static FetchAccess lent(final PoreloContext context) {
        return address -> {
            final FollowCredential credential =
                    FollowCredential.open(context, address, "organization");
            return new FetchAccess.Lease() {
                @Override
                public Map<String, String> environment() {
                    final Map<String, String> environment =
                            new LinkedHashMap<>(credential.environment());
                    final String agent = context.environment().apply(IdentityChange.AGENT);
                    if (agent != null && !agent.isEmpty()) {
                        environment.putIfAbsent(IdentityChange.AGENT, agent);
                    }
                    return environment;
                }

                @Override
                public List<String> arguments() {
                    return credential.arguments();
                }

                @Override
                public void close() {
                    credential.close();
                }
            };
        };
    }

    /**
     * Reads the address this machine's settings name.
     *
     * @param context The machine.
     * @return The address, or empty when this machine has no organization.
     * @throws IdentityChange.Refused 64 when the settings name another adapter or do not read.
     * @throws IOException When they cannot be read.
     */
    static Optional<String> repository(final PoreloContext context)
            throws IdentityChange.Refused, IOException {
        final Path file = context.paths().xdg().config().resolve("organization.yml");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        final Object loaded;
        try {
            loaded =
                    new Yaml(new SafeConstructor(new LoaderOptions()))
                            .load(Files.readString(file, StandardCharsets.UTF_8));
        } catch (RuntimeException ex) {
            // The parser's own message quotes the line.
            throw new IdentityChange.Refused(
                    64, OrganizationQuestions.Refused.USAGE, "organization.yml does not read");
        }
        if (loaded instanceof Map<?, ?> settings
                && ADAPTER.equals(settings.get("adapter"))
                && settings.get("settings") instanceof Map<?, ?> own
                && own.get("repository") instanceof String repository) {
            return Optional.of(repository);
        }
        throw new IdentityChange.Refused(
                64,
                OrganizationQuestions.Refused.USAGE,
                "organization.yml names no adapter this Porelo has");
    }
}
