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
import org.fuin.porelo.organization.gittree.ForgeAddress;
import org.fuin.porelo.organization.gittree.GitTreeStore;
import org.fuin.porelo.organization.gittree.HttpForgeDirectory;
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

    /**
     * Says why a forge was not reached where ssh gave its reason: a person cannot get from "cannot
     * be reached" to an unknown host key, which stays their question to answer.
     *
     * @param unreached What was thrown, holding git's own words.
     * @param otherwise What to say for any other cause.
     * @param address The organization's address.
     * @return The refusal, {@code unknown-host-key} or {@code cannot-say}.
     */
    static Refused unreached(
            final Exception unreached, final String otherwise, final String address) {
        return String.valueOf(unreached.getMessage()).contains("Host key verification failed")
                ? new Refused(
                        Refused.UNKNOWN_HOST_KEY,
                        "the forge's ssh host key is not trusted on this machine: see what it"
                                + " offers with 'porelo credentials trust-host "
                                + HostKeys.nameOf(address)
                                + "'",
                        null)
                : new Refused(Refused.CANNOT_SAY, otherwise, null);
    }

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
                                            unreached(
                                                    ex,
                                                    "the organization cannot be reached, and"
                                                            + " nothing is known of it yet",
                                                    repository)),
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
        final ForgeAddress origin;
        try {
            origin = ForgeAddress.of(repository);
        } catch (IllegalArgumentException ex) {
            throw new Refused(
                    Refused.USAGE, "the organization's address in the settings is none", null);
        }
        return new Opened(
                GitTreeStore.open(
                        context.runner(),
                        repository,
                        OrganizationAccess.lent(context),
                        state(context)),
                new HttpForgeDirectory(
                        origin.forge(),
                        OrganizationAccess.forgeToken(context, origin),
                        OrganizationAccess.http(context)));
    }

    /** Where the adapter keeps what the last read trusted. */
    private static Path state(final PoreloContext context) {
        return context.paths().xdg().state().resolve("organization");
    }

    /**
     * Where this machine remembers what it accepted of projects' own files.
     *
     * @param context The machine.
     * @return The directory.
     */
    static Path projectFiles(final PoreloContext context) {
        return context.paths().projects().projectFileMemory();
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
     * Returns an address settings may name. They are handed to hosts, and the address forms hold no
     * place for a password or token, so none travels with them.
     *
     * @param address The address a person named.
     * @return The address.
     * @throws Refused When it is none of the forms.
     */
    static String settable(final String address) {
        try {
            ForgeAddress.of(address);
        } catch (IllegalArgumentException ex) {
            throw new Refused(Refused.USAGE, "that address is none", null);
        }
        return address;
    }

    /**
     * Writes this machine's {@code organization.yml}, whole, so that no reader sees half of it.
     *
     * @param context The machine.
     * @param address The address.
     * @throws IOException When it cannot be written.
     */
    static void settle(final PoreloContext context, final String address) throws IOException {
        final Path file = context.paths().xdg().config().resolve("organization.yml");
        final org.yaml.snakeyaml.DumperOptions block = new org.yaml.snakeyaml.DumperOptions();
        block.setDefaultFlowStyle(org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK);
        final Map<String, Object> settings = new java.util.LinkedHashMap<>();
        settings.put("adapter", ADAPTER);
        settings.put("settings", Map.of("repository", address));
        Files.createDirectories(file.getParent());
        final Path temporary = Files.createTempFile(file.getParent(), "organization", ".yml");
        try {
            Files.writeString(temporary, new Yaml(block).dump(settings), StandardCharsets.UTF_8);
            Files.move(
                    temporary,
                    file,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
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
        for (final String written : ProjectSource.writtenNames(context)) {
            local.putIfAbsent(
                    written, context.paths().projects().defaultProjectFile(written).toString());
        }
        local.remove(DefaultProject.NAME);
        return local;
    }
}
