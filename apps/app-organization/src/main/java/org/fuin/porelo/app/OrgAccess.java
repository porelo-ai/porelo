package org.fuin.porelo.app;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.comments.CommentType;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.events.CommentEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.Tag;

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
     * @param checkedAt The last complete check time, or null when unknown.
     */
    record Reading(
            OrganizationQuestions answers,
            @Nullable IdentitySource identities,
            @Nullable Instant checkedAt,
            @Nullable StaleWhy why) {

        Reading(
                OrganizationQuestions answers,
                @Nullable IdentitySource identities,
                @Nullable Instant checkedAt) {
            this(answers, identities, checkedAt, null);
        }

        Reading(OrganizationQuestions answers, @Nullable IdentitySource identities) {
            this(answers, identities, identities != null && answers.whole() ? Instant.now() : null);
        }

        boolean stale() {
            return identities == null;
        }

        void age(Map<String, @Nullable Object> answer) {
            answer.put("checked_at", checkedAt == null ? null : checkedAt.toString());
            answer.put(
                    "age_seconds",
                    checkedAt == null
                            ? null
                            : Math.max(0, Duration.between(checkedAt, Instant.now()).getSeconds()));
            answer.put("stale_why", stale() ? (why == null ? StaleWhy.KEPT : why).answer() : null);
        }
    }

    /**
     * Why an answer is the last state trusted rather than one checked now: a named cause, git's own
     * words where it wrote any, and when it was tried.
     *
     * @param cause {@code kept}, {@code incomplete}, {@code failed}, or a {@link
     *     GitTreeStore.Unreachable.Cause}'s word.
     * @param said Git's own lines, never the forge's, or {@code null}.
     * @param at When the check was tried, or {@code null}.
     */
    record StaleWhy(String cause, @Nullable String said, @Nullable String at) {

        /** The kept state was shown, and a check runs after the answer. */
        static final StaleWhy KEPT = new StaleWhy("kept", null, null);

        /** The check could not judge everything new. */
        static StaleWhy incomplete() {
            return new StaleWhy("incomplete", null, Instant.now().toString());
        }

        /**
         * The check failed for a reason whose words are not shown, since they may quote a secret.
         */
        static StaleWhy failed() {
            return new StaleWhy("failed", null, Instant.now().toString());
        }

        static StaleWhy of(final GitTreeStore.Unreachable unreachable) {
            return new StaleWhy(
                    unreachable.cause().word(), unreachable.said(), Instant.now().toString());
        }

        Map<String, @Nullable Object> answer() {
            final Map<String, @Nullable Object> answer = new java.util.LinkedHashMap<>();
            answer.put("cause", cause);
            answer.put("said", said);
            answer.put("at", at);
            return answer;
        }

        /**
         * Reads why the last display refresh failed, from its receipt; {@link #KEPT} unless it is
         * this organization's and tried after the kept check.
         */
        static StaleWhy lastRefresh(
                final PoreloContext context,
                final String repository,
                final @Nullable Instant checkedAt) {
            final Path receipt = state(context).resolve(REFRESH_RECEIPT);
            try {
                if (!Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(receipt) > 16384) {
                    return KEPT;
                }
                final Object parsed = org.fuin.porelo.wire.Json.parse(Files.readString(receipt));
                if (parsed instanceof Map<?, ?> held
                        && repository.equals(held.get("repository"))
                        && (checkedAt == null ? "" : checkedAt.toString()).equals(held.get("after"))
                        && ("failed".equals(held.get("outcome"))
                                || "incomplete".equals(held.get("outcome")))
                        && held.get("cause") instanceof String cause) {
                    return new StaleWhy(
                            cause,
                            held.get("said") instanceof String said ? said : null,
                            held.get("attempted_at") instanceof String at ? at : null);
                }
                return KEPT;
            } catch (IOException | RuntimeException ex) {
                return KEPT;
            }
        }
    }

    /** The display refresh's receipt in the organization's state directory. */
    static final String REFRESH_RECEIPT = "refresh.json";

    /** Reads only for display; nothing authorizing an action may use this answer. */
    static Reading show(final PoreloContext context, final Opener opener) throws IOException {
        final Optional<String> repository = repository(context);
        if (repository.isEmpty()) {
            return refresh(context, opener);
        }
        final Opened opened = opener.open(context, repository.orElseThrow());
        final Optional<GitTreeStore.Checked> last = opened.store().lastChecked();
        if (last.isEmpty()) {
            return fetched(context, repository.orElseThrow(), opened, true);
        }
        final GitTreeStore.Checked kept = last.orElseThrow();
        return new Reading(
                kept.answers(),
                null,
                kept.checkedAt(),
                StaleWhy.lastRefresh(context, repository.orElseThrow(), kept.checkedAt()));
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
        return read(context, opener, false);
    }

    /** A fresh replacement for a display; an incomplete walk never replaces its kept snapshot. */
    static Reading refresh(final PoreloContext context, final Opener opener) throws IOException {
        return read(context, opener, true);
    }

    private static Reading read(
            final PoreloContext context, final Opener opener, final boolean display)
            throws IOException {
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
        return fetched(context, repository, opened, display);
    }

    private static Reading fetched(
            final PoreloContext context,
            final String repository,
            final Opened opened,
            final boolean display)
            throws IOException {
        try {
            final OrganizationQuestions answers =
                    display
                            ? opened.store()
                                    .refresh(
                                            opened.identities(),
                                            () ->
                                                    repository.equals(
                                                            repository(context).orElse(null)))
                                    .orElseThrow(
                                            () ->
                                                    new Refused(
                                                            Refused.MOVED_ON,
                                                            "the configured organization changed during the refresh; ask again",
                                                            null))
                            : opened.store().read(opened.identities());
            return new Reading(answers, opened.identities());
        } catch (GitTreeStore.Unreachable ex) {
            final GitTreeStore.Checked last =
                    opened.store()
                            .lastChecked()
                            .orElseThrow(
                                    () ->
                                            unreached(
                                                    ex,
                                                    "the organization cannot be reached, and nothing is known of it yet",
                                                    repository));
            return new Reading(last.answers(), null, last.checkedAt(), StaleWhy.of(ex));
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
                new LazyIdentities(
                        () ->
                                new HttpForgeDirectory(
                                        origin.forge(),
                                        OrganizationAccess.forgeToken(context, origin),
                                        OrganizationAccess.http(context))));
    }

    /** A kept display must not unlock credentials or construct a network client. */
    private static final class LazyIdentities implements IdentitySource {
        private final java.util.function.Supplier<IdentitySource> opening;
        private @Nullable IdentitySource opened;

        LazyIdentities(java.util.function.Supplier<IdentitySource> opening) {
            this.opening = opening;
        }

        private synchronized IdentitySource get() {
            if (opened == null) opened = opening.get();
            return opened;
        }

        @Override
        public Optional<Keys> keys(String login) {
            return get().keys(login);
        }

        @Override
        public Optional<Keys> keys(String login, String fingerprint) {
            return get().keys(login, fingerprint);
        }

        @Override
        public Optional<Boolean> owns(String organization, String login) {
            return get().owns(organization, login);
        }
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
        final String original =
                Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                        ? Files.readString(file, StandardCharsets.UTF_8)
                        : "";
        final String written = settled(original, address);
        Files.createDirectories(file.getParent());
        final Path temporary = Files.createTempFile(file.getParent(), "organization", ".yml");
        try {
            Files.writeString(temporary, written, StandardCharsets.UTF_8);
            Files.move(
                    temporary,
                    file,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String settled(final String original, final String address) {
        final String written;
        try {
            final Node composed = TolerantComposer.compose(original);
            final MappingNode root;
            if (composed == null || Tag.COMMENT.equals(composed.getTag())) {
                root = mapping();
                if (composed != null) {
                    root.setBlockComments(composed.getBlockComments());
                }
            } else {
                root = mapping(composed);
            }
            put(root, "adapter", scalar(ADAPTER));
            final Node settings = value(root, "settings");
            final MappingNode own =
                    settings == null
                            ? mapping()
                            : new MappingNode(
                                    Tag.MAP,
                                    new ArrayList<>(mapping(settings).getValue()),
                                    ((MappingNode) settings).getFlowStyle());
            put(own, "repository", scalar(address));
            put(root, "settings", own);
            final DumperOptions options = new DumperOptions();
            options.setProcessComments(true);
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            final StringWriter output = new StringWriter();
            new Yaml(options).serialize(root, output);
            written = output.toString();
        } catch (RuntimeException ex) {
            // Parser messages quote the person's file, which may hold private text.
            throw new Refused(
                    Refused.USAGE,
                    "organization.yml cannot be updated: correct its YAML mappings first",
                    null);
        }
        if (!comments(original).equals(comments(written))) {
            throw new Refused(
                    Refused.USAGE,
                    "organization.yml cannot be updated without losing comments: expand commented YAML aliases first",
                    null);
        }
        return written;
    }

    private static List<String> comments(final String text) {
        final LoaderOptions options = new LoaderOptions();
        options.setProcessComments(true);
        final List<String> comments = new ArrayList<>();
        for (final Event event : new Yaml(options).parse(new StringReader(text))) {
            if (event instanceof CommentEvent comment
                    && comment.getCommentType() != CommentType.BLANK_LINE) {
                comments.add(comment.getValue());
            }
        }
        return comments;
    }

    private static MappingNode mapping() {
        return new MappingNode(Tag.MAP, new ArrayList<>(), DumperOptions.FlowStyle.BLOCK);
    }

    private static MappingNode mapping(final Node node) {
        if (!(node instanceof MappingNode mapping) || !Tag.MAP.equals(node.getTag())) {
            throw new IllegalArgumentException("Expected a mapping");
        }
        final var keys = new HashSet<String>();
        for (final NodeTuple tuple : mapping.getValue()) {
            if (!(tuple.getKeyNode() instanceof ScalarNode key)
                    || !Tag.STR.equals(key.getTag())
                    || !keys.add(key.getValue())) {
                throw new IllegalArgumentException("Expected distinct string keys");
            }
        }
        return mapping;
    }

    private static @Nullable Node value(final MappingNode mapping, final String name) {
        for (final NodeTuple tuple : mapping.getValue()) {
            if (((ScalarNode) tuple.getKeyNode()).getValue().equals(name)) {
                return tuple.getValueNode();
            }
        }
        return null;
    }

    private static ScalarNode scalar(final String value) {
        return new ScalarNode(Tag.STR, value, null, null, DumperOptions.ScalarStyle.PLAIN);
    }

    private static void put(final MappingNode mapping, final String name, final Node replacement) {
        final List<NodeTuple> entries = mapping.getValue();
        for (int index = 0; index < entries.size(); index++) {
            final NodeTuple tuple = entries.get(index);
            if (((ScalarNode) tuple.getKeyNode()).getValue().equals(name)) {
                final Node previous = tuple.getValueNode();
                replacement.setBlockComments(previous.getBlockComments());
                replacement.setInLineComments(previous.getInLineComments());
                replacement.setEndComments(previous.getEndComments());
                entries.set(index, new NodeTuple(tuple.getKeyNode(), replacement));
                return;
            }
        }
        entries.add(new NodeTuple(scalar(name), replacement));
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
