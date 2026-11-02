package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.organization.IdentitySource;
import org.fuin.porelo.organization.OrganizationException;
import org.fuin.porelo.organization.OrganizationQuestions;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.fuin.porelo.organization.git.HttpForgeDirectory;
import org.fuin.porelo.organization.git.RegistryOrigin;
import org.fuin.porelo.organization.gittree.FetchAccess;
import org.fuin.porelo.organization.gittree.GitTreeStore;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.PersonCard;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** How a command of the organization reaches it: this machine's settings, and the first read. */
final class OrgAccess {

    private static final String ADAPTER = "git";

    private OrgAccess() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * A store opened, and who it asks.
     *
     * @param store The store.
     * @param identities Who says whose keys are whose.
     */
    record Opened(GitTreeStore store, IdentitySource identities) {}

    /** Opens the store of an address; replaced by a test, which has no forge to ask. */
    @FunctionalInterface
    interface Opener {
        Opened open(PoreloContext context, String repository);
    }

    /**
     * What a read answered.
     *
     * @param answers The answers.
     * @param identities Who to ask about now, or {@code null} for a stale read, which reached
     *     nobody.
     */
    record Reading(OrganizationQuestions answers, @Nullable IdentitySource identities) {

        boolean stale() {
            return identities == null;
        }
    }

    /**
     * Reads the organization: fetched and walked, or, when the store cannot be reached, its last
     * trusted state, marked stale.
     *
     * @param context The machine.
     * @param opener How the store is opened.
     * @return What it answered.
     * @throws Refused When this machine has none, or nothing can be said.
     * @throws IOException When something unexpected happened.
     */
    static Reading read(final PoreloContext context, final Opener opener) throws IOException {
        final String repository =
                repository(context)
                        .orElseThrow(
                                () ->
                                        new Refused(
                                                Refused.NO_ORGANIZATION,
                                                "this machine has no organization: run porelo setup"
                                                        + " first",
                                                null));
        final Opened opened = opener.open(context, repository);
        try {
            return new Reading(opened.store().read(opened.identities()), opened.identities());
        } catch (GitTreeStore.Unreachable ex) {
            return new Reading(
                    opened.store()
                            .last()
                            .orElseThrow(
                                    () ->
                                            new Refused(
                                                    Refused.CANNOT_SAY,
                                                    "the organization cannot be reached, and nothing"
                                                            + " is known of it yet",
                                                    null)),
                    null);
        } catch (OrganizationException | CommandException ex) {
            throw new Refused(Refused.REFUSED_STATE, String.valueOf(ex.getMessage()), null);
        }
    }

    /**
     * Opens the store as this machine does: the person's own credentials for the fetch, and the
     * forge its address names for who holds which key.
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
            throw new Refused(
                    Refused.USAGE, "the organization's address in the settings is none", null);
        }
        return new Opened(
                GitTreeStore.open(context.runner(), repository, lent(context), state(context)),
                new HttpForgeDirectory(
                        origin.forge(), OrgForgeToken.of(context, origin), http(context)));
    }

    /** Where the adapter keeps what the last read trusted. */
    private static Path state(final PoreloContext context) {
        return context.paths().xdg().state().resolve("organization");
    }

    /** Lends what this person holds for the address, as a follow is lent it. */
    static FetchAccess lent(final PoreloContext context) {
        return address -> {
            final FollowCredential credential =
                    FollowCredential.open(context, address, "organization");
            return new FetchAccess.Lease() {
                @Override
                public Map<String, String> environment() {
                    return credential.environment();
                }

                @Override
                public void close() {
                    credential.close();
                }
            };
        };
    }

    static java.net.http.HttpClient http(final PoreloContext context) {
        return org.fuin.porelo.core.net.TrustedCertificates.builder(
                        org.fuin.porelo.core.net.TrustedCertificates.file(context.paths().xdg()))
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Returns this machine's {@code organization.yml}, whole, for a host that is handed it unread.
     *
     * @param context The machine.
     * @return Its text.
     * @throws IOException When it cannot be read.
     */
    static String settings(final PoreloContext context) throws IOException {
        return Files.readString(
                context.paths().xdg().config().resolve("organization.yml"), StandardCharsets.UTF_8);
    }

    /**
     * Reads the address this machine's settings name.
     *
     * @param context The machine.
     * @return The address, or empty when this machine has no organization.
     * @throws Refused When the settings name another adapter or do not read.
     * @throws IOException When they cannot be read.
     */
    static Optional<String> repository(final PoreloContext context) throws IOException {
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
            throw new Refused(Refused.USAGE, "organization.yml does not read", null);
        }
        if (loaded instanceof Map<?, ?> settings
                && ADAPTER.equals(settings.get("adapter"))
                && settings.get("settings") instanceof Map<?, ?> own
                && own.get("repository") instanceof String repository) {
            return Optional.of(repository);
        }
        throw new Refused(Refused.USAGE, "organization.yml names no adapter this Porelo has", null);
    }

    /**
     * Returns the login of this machine's person, as their own card says.
     *
     * @param context The machine.
     * @return The login, or empty when this machine holds no card.
     * @throws IOException When it cannot be read.
     */
    static Optional<String> ownLogin(final PoreloContext context) throws IOException {
        final Path card = context.paths().xdg().config().resolve("identity/card");
        if (!Files.isRegularFile(card, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            return Optional.of(
                    PersonCard.read(Files.readString(card, StandardCharsets.UTF_8), "own card")
                            .login());
        } catch (CardException ex) {
            return Optional.empty();
        }
    }

    /**
     * Returns the projects registered on this machine alone: name and file.
     *
     * @param context The machine.
     * @return Them, sorted by name, without {@code default}.
     */
    static Map<String, String> localProjects(final PoreloContext context) {
        final Map<String, String> local =
                new TreeMap<>(
                        new ProjectRegistry(context.paths().projects().projectRegistry()).all());
        local.remove(DefaultProject.NAME);
        return local;
    }
}
