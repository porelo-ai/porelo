package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.core.project.Repository;
import org.fuin.porelo.ecosystem.api.Detection;
import org.fuin.porelo.ecosystem.api.EcosystemException;
import org.fuin.porelo.ecosystem.api.EcosystemRefused;
import org.fuin.porelo.ecosystem.api.File;
import org.fuin.porelo.ecosystem.api.Finding;
import org.fuin.porelo.ecosystem.api.InstalledEcosystems;
import org.fuin.porelo.ecosystem.api.Unplaced;
import org.fuin.porelo.gate.LocalRepository;
import org.fuin.porelo.shield.EgressSet;
import org.fuin.porelo.vault.RegistryText;
import org.jspecify.annotations.Nullable;

/**
 * What a first start in a checkout on the {@code default} path may take from the repository.
 *
 * <p><strong>A repository decides nothing by itself.</strong> A plugin's answer and a {@code
 * project.yml} are a stranger's input: a draft opens no host or chooses a package without a
 * person's yes to exactly that text, by its hash. A No has no call and keeps nothing. A new draft
 * is a repository's {@code porelo.yml} in {@code default}; existing local projects stay projects.
 *
 * <p>One question, one implementation: the terminal and the daemon only render it. An
 * organization's project is not asked about, since it takes its signed file.
 */
public final class FirstStart {

    /** What a checkout's first start is to do. */
    public enum Outcome {
        /** Nothing is configured and something was recognized. */
        ASK_NEW,
        /** A {@code project.yml} this machine has not approved. */
        ASK_UNKNOWN_FILE,
        /** Nothing to ask. */
        KNOWN,
        /** A repository of an organization's project. */
        NOT_DEFAULT,
        /** No usable plugin is installed. */
        NO_PLUGIN,
        /** The plugins recognized nothing. */
        NOTHING_RECOGNIZED
    }

    /** Why a yes cannot be taken. */
    public enum Reason {
        /** The directory is in no git checkout. */
        NOT_A_CHECKOUT,
        /** The name is {@code default} or another local project's. */
        NAME_TAKEN,
        /** The repository or its file gives no name a project can have. */
        NO_NAME,
        /** The file does not read as a project, is a link, or is too large. */
        FILE_UNREADABLE,
        /** The text is no longer what the hash named. */
        MOVED_ON,
        /** There is nothing here to say yes to. */
        NOTHING_ASKED,
        /** What the yes keeps could not be written. */
        NOT_WRITTEN
    }

    /** A yes that cannot be taken, as a named value. */
    public static final class Refused extends Exception {

        private static final long serialVersionUID = 1L;

        private final Reason reason;

        /**
         * Constructor.
         *
         * @param reason The named value.
         * @param detail What to tell the person.
         */
        public Refused(final Reason reason, final String detail) {
            super(detail);
            this.reason = reason;
        }

        /**
         * Returns the named value.
         *
         * @return It.
         */
        public Reason reason() {
            return reason;
        }

        /**
         * Returns what to tell the person.
         *
         * @return It.
         */
        public String detail() {
            return String.valueOf(getMessage());
        }
    }

    /**
     * One thing found.
     *
     * @param what A package, tool or other setting, by name.
     * @param value What it says; printable.
     * @param source The file it was read from; printable.
     * @param unknown Whether it is set where nothing can read it without running something; {@code
     *     value} then says why.
     * @param note What a plugin says of it, or ""; printable.
     */
    public record Found(String what, String value, String source, boolean unknown, String note) {

        /**
         * Returns this as the wire has it.
         *
         * @return Its fields.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("what", what);
            map.put("value", unknown ? "unknown: " + value : value);
            map.put("source", source);
            map.put("note", note);
            return map;
        }
    }

    /**
     * What a look at a checkout answers.
     *
     * @param outcome What to do.
     * @param found What was found; printable.
     * @param hosts Each host it opens; printable.
     * @param marked The hosts only the checkout names, in no curated set.
     * @param file The text that would be written; for a file already there, every line of it and of
     *     each file it names, made printable.
     * @param project The project's name, or "".
     * @param hash What the answer names, or "".
     * @param repository The repository in the project this checkout is, or "".
     * @param target The file's path, or {@code null} when there is none to approve.
     * @param refusal Why a yes cannot be taken, or {@code null}.
     * @param notes What else a person should read, such as a plugin that failed; printable.
     * @param staged This machine's copy of what is asked about, or {@code null}.
     * @param upstream The checkout's origin, or its own path when it has no usable one; or "".
     */
    public record Answer(
            Outcome outcome,
            List<Found> found,
            List<String> hosts,
            List<String> marked,
            String file,
            String project,
            String hash,
            String repository,
            @Nullable Path target,
            @Nullable Refused refusal,
            List<String> notes,
            @Nullable StagedProject staged,
            String upstream) {

        private static Answer plain(
                final Outcome outcome,
                final String project,
                final String repository,
                final List<String> notes) {
            return new Answer(
                    outcome,
                    List.of(),
                    List.of(),
                    List.of(),
                    "",
                    project,
                    "",
                    repository,
                    null,
                    null,
                    List.copyOf(notes),
                    null,
                    "");
        }

        /** Ends the question; a yes has already moved the copy away. */
        public void discard() {
            if (staged != null) {
                staged.discard();
            }
        }

        /**
         * Returns this as the wire has it.
         *
         * @return Its fields.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("outcome", outcome.name());
            map.put("found", found.stream().map(Found::asMap).toList());
            map.put("hosts", hosts);
            map.put("marked", marked);
            map.put("file", file);
            map.put("fileName", target == null ? "" : target.getFileName().toString());
            map.put("project", project);
            map.put("hash", hash);
            map.put("notes", notes);
            map.put("refusal", refusal == null ? "" : printable(refusal.detail()));
            return map;
        }
    }

    /** What one plugin's answer for a checkout is. */
    public enum Asking {
        /** It recognized something, which its detection holds. */
        DETECTED,
        /** It holds nothing of its tool there. */
        NOT_RECOGNIZED,
        /** A file it needs cannot be read. */
        UNREADABLE,
        /** It did not answer, or answered something that is no detection. */
        FAILED
    }

    /**
     * What one usable plugin answers for a checkout at a first start, before anything is looked up.
     *
     * @param plugin The plugin.
     * @param outcome What its answer is.
     * @param detection What it recognized; empty unless {@link Asking#DETECTED}.
     * @param files The settings files a first start places from it.
     * @param note What a person reads of a refusal or a failure, printable; or "".
     */
    public record Placing(
            InstalledEcosystems.Description plugin,
            Asking outcome,
            Detection detection,
            List<File> files,
            String note) {

        /** Constructor. */
        public Placing {
            files = List.copyOf(files);
        }

        /**
         * Returns this as the wire has it: a file by its path and whether it is owner-only, never
         * what it holds, which may be a credential.
         *
         * @return Its fields.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("ecosystem", plugin.name());
            map.put("outcome", outcome.name());
            map.put("findings", detection.findings().stream().map(FirstStart::finding).toList());
            map.put("unplaced", detection.unplaced().stream().map(FirstStart::unplaced).toList());
            map.put("files", files.stream().map(FirstStart::file).toList());
            map.put("note", note);
            return map;
        }
    }

    /**
     * The installed plugins and, for a checkout, what each answers there as a first start asks it.
     *
     * @param plugins Every installed plugin, a refused one with its refusal.
     * @param placing For a checkout, one entry per usable plugin in the order a first start asks
     *     them; {@code null} when no checkout was named.
     */
    public record Ecosystems(
            List<InstalledEcosystems.Description> plugins, @Nullable List<Placing> placing) {

        /** Constructor. */
        public Ecosystems {
            plugins = List.copyOf(plugins);
            placing = placing == null ? null : List.copyOf(placing);
        }

        /**
         * Returns this as the wire has it.
         *
         * @return Its fields; {@code placing} is absent without a checkout.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new LinkedHashMap<>();
            map.put("ecosystems", plugins.stream().map(FirstStart::plugin).toList());
            if (placing != null) {
                map.put("placing", placing.stream().map(Placing::asMap).toList());
            }
            return map;
        }
    }

    /** An existing local project's file, whose approval remains a project approval. */
    private static final String FILE_NAME = CheckoutProject.FILE_NAME;

    /** A new draft belongs to its repository while the project stays default. */
    private static final String REPOSITORY_FILE = ImageChoice.FILE;

    /** How many lookups a question keeps, for a daemon that is asked by many. */
    private static final int KEPT = 32;

    /** How long a kept lookup answers a yes: a question is answered within it. */
    private static final long KEPT_NANOS = java.util.concurrent.TimeUnit.HOURS.toNanos(1);

    private final PoreloContext context;

    private final EcosystemDetector detector;

    /** What one plugin's Detect answered for one checkout, which a lookup is kept by. */
    private record Asked(
            String plugin,
            Path checkout,
            Path home,
            Detection detected,
            Map<String, String> proxies) {}

    /** A lookup and when it was made. */
    private record Kept(EcosystemLookup.Looked looked, long at) {}

    /**
     * The lookups of the questions this asked, so that the yes, which looks again, meets the text
     * the person read: a plugin asked twice may answer a newer version.
     */
    private final Map<Asked, Kept> kept =
            java.util.Collections.synchronizedMap(
                    new LinkedHashMap<>(16, 0.75f, true) {
                        private static final long serialVersionUID = 1L;

                        @Override
                        protected boolean removeEldestEntry(final Map.Entry<Asked, Kept> eldest) {
                            return size() > KEPT;
                        }
                    });

    /**
     * Constructor.
     *
     * @param context This machine.
     * @param detector Who asks a plugin.
     */
    public FirstStart(final PoreloContext context, final EcosystemDetector detector) {
        this.context = context;
        this.detector = detector;
    }

    /**
     * Looks at a checkout, changing nothing there. A question about an existing project holds a
     * staged copy, which {@link Answer#discard()} or {@link #take(Answer)} ends; a new repository's
     * draft holds its text alone. Advice already said is remembered in this machine's state.
     *
     * @param directory Where the person stands.
     * @return What to do.
     * @throws Refused When it is in no checkout.
     */
    public Answer look(final Path directory) throws Refused {
        return look(directory, null);
    }

    /**
     * Looks at a checkout as {@link #look(Path)} does and, where a file would be made, has each
     * plugin look up what it read, which a person then answers: the one time a plugin may open a
     * connection. A start with a file asks none.
     *
     * @param directory Where the person stands.
     * @param proxies The asking person's proxy settings, which only the plugin's process gets; or
     *     {@code null} for a question nobody answers, which looks nothing up.
     * @return What to do.
     * @throws Refused When it is in no checkout.
     */
    public Answer look(final Path directory, final @Nullable Map<String, String> proxies)
            throws Refused {
        final Path here = real(directory);
        final Path top = topLevel(here);
        final String origin = origin(top);
        if (!origin.isEmpty()) {
            // Before any file in the checkout is read: a project.yml decides nothing for such a
            // project.
            final WorkOrigin.Choice placed = WorkOrigin.organizationNaming(context, origin);
            if (placed != null) {
                return Answer.plain(
                        Outcome.NOT_DEFAULT, placed.project(), placed.repository(), List.of());
            }
        }
        for (final Path place : List.of(here, top)) {
            final Path file = place.resolve(FILE_NAME);
            if (Files.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return ofFile(file, origin, top);
            }
        }
        if (!origin.isEmpty()) {
            final WorkOrigin.Choice recorded = new ProjectApprovals(context).naming(origin);
            if (recorded != null) {
                return Answer.plain(
                        Outcome.KNOWN,
                        recorded.project(),
                        recorded.repository(),
                        localProjectNote(top));
            }
        }
        final Path own = top.resolve(REPOSITORY_FILE);
        if (Files.exists(own, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return knownRepository(own, top, origin);
        }
        return detected(here, top, origin, proxies);
    }

    /**
     * Lists the installed plugins and, for a checkout, asks each usable one what it finds there as
     * a first start asks it: the same plugins, directory and home, nothing looked up, and nothing
     * written. Whatever project the checkout belongs to, it is asked.
     *
     * @param directory The checkout, or {@code null} to list the plugins alone.
     * @return What they are and answer.
     * @throws Refused When the directory is in no checkout.
     * @throws EcosystemException When a plugin directory cannot be listed.
     */
    public Ecosystems ecosystems(final @Nullable Path directory) throws Refused {
        final Path here = directory == null ? null : real(directory);
        if (here != null) {
            topLevel(here);
        }
        final List<InstalledEcosystems.Description> plugins = installed();
        if (here == null) {
            return new Ecosystems(plugins, null);
        }
        final Path home = context.paths().xdg().home();
        final List<Placing> placing = new ArrayList<>();
        for (final InstalledEcosystems.Description each : plugins) {
            if (each.refusal() == null) {
                placing.add(ask(each, here, home));
            }
        }
        return new Ecosystems(plugins, placing);
    }

    /** The plugins a first start finds: the account's own first, then the packaged ones. */
    private List<InstalledEcosystems.Description> installed() {
        return InstalledEcosystems.all(context.paths().ecosystemDirectories());
    }

    /**
     * Asks one usable plugin what it recognizes, as a first start does before it looks anything up;
     * a refusal or a failure becomes the note a first start gives for it.
     */
    private Placing ask(
            final InstalledEcosystems.Description plugin, final Path here, final Path home) {
        final Detection none = new Detection(List.of(), List.of());
        try {
            return new Placing(
                    plugin, Asking.DETECTED, detector.detect(plugin, here, home), List.of(), "");
        } catch (EcosystemRefused ex) {
            if (ex.reason() == EcosystemRefused.Reason.NOT_RECOGNIZED) {
                return new Placing(plugin, Asking.NOT_RECOGNIZED, none, List.of(), "");
            }
            return new Placing(
                    plugin,
                    Asking.UNREADABLE,
                    none,
                    List.of(),
                    printable(plugin.name() + " could not read: " + ex.detail()));
        } catch (EcosystemException | IOException ex) {
            return new Placing(
                    plugin,
                    Asking.FAILED,
                    none,
                    List.of(),
                    printable(plugin.name() + " failed to answer"));
        }
    }

    private static Path real(final Path directory) throws Refused {
        try {
            return directory.toRealPath();
        } catch (IOException ex) {
            throw new Refused(Reason.NOT_A_CHECKOUT, "that directory cannot be read");
        }
    }

    private Path topLevel(final Path here) throws Refused {
        final Path top = LocalRepository.topLevel(context.runner(), here);
        if (top == null) {
            throw new Refused(Reason.NOT_A_CHECKOUT, "that directory is in no git checkout");
        }
        return top;
    }

    /**
     * Registers the checkout on the default path when the terminal proceeds with an existing
     * repository file, as a start without a draft already does.
     *
     * @param directory The checkout the person starts in.
     * @return Its entry in default.
     */
    WorkOrigin.Choice defaultChoice(final Path directory) {
        return WorkOrigin.withoutApproved(context, directory);
    }

    /**
     * Takes a yes the daemon was given by hash.
     *
     * @param directory Where the person stands.
     * @param hash What they answered to.
     * @return The project.
     * @throws Refused When it cannot be taken.
     */
    public String confirm(final Path directory, final String hash) throws Refused {
        return confirm(directory, hash, null);
    }

    /**
     * Takes a yes the daemon was given by hash, to a question that looked up what the plugins read:
     * the lookup the question made answers it, and one no longer kept is made anew.
     *
     * @param directory Where the person stands.
     * @param hash What they answered to.
     * @param proxies The asking person's proxy settings, or {@code null} for a question that looked
     *     nothing up.
     * @return The project.
     * @throws Refused When it cannot be taken.
     */
    public String confirm(
            final Path directory, final String hash, final @Nullable Map<String, String> proxies)
            throws Refused {
        final Answer answer = look(directory, proxies);
        try {
            if (answer.outcome() == Outcome.KNOWN && answer.target() != null) {
                if (answer.hash().equals(hash)) {
                    return answer.project();
                }
                throw new Refused(Reason.MOVED_ON, "the file is not what that hash named");
            }
            if (answer.outcome() != Outcome.ASK_NEW
                    && answer.outcome() != Outcome.ASK_UNKNOWN_FILE) {
                throw new Refused(Reason.NOTHING_ASKED, "there is nothing to say yes to here");
            }
            if (!answer.hash().equals(hash)) {
                throw new Refused(Reason.MOVED_ON, "the file is not what that hash named");
            }
            return take(answer);
        } finally {
            answer.discard();
        }
    }

    /**
     * Takes a yes to what a look answered: a new repository file is written and allowed in {@code
     * default}; an existing project file's staged copy becomes the local project.
     *
     * @param answer What the person read.
     * @return The project.
     * @throws Refused When it cannot be taken: the name is checked again, as another yes may have
     *     taken it while this question waited.
     */
    public String take(final Answer answer) throws Refused {
        if (answer.refusal() != null) {
            throw answer.refusal();
        }
        final Path file = Objects.requireNonNull(answer.target(), "a question names its file");
        if (answer.outcome() == Outcome.ASK_NEW) {
            takeRepository(answer, file);
            forgetLookup(file);
            return DefaultProject.NAME;
        }
        final StagedProject staged =
                Objects.requireNonNull(
                        answer.staged(), "an existing project's question holds its copy");
        final Path place =
                Objects.requireNonNull(
                        context.paths().projects().defaultProjectFile(answer.project()).getParent(),
                        "a place has a parent");
        final Optional<Refused> refused;
        try {
            // Check, copy and record under one lock, for this process and any other: without it two
            // yeses both pass the check and the copy of one stands beside the record of the other.
            refused =
                    FileLocks.holding(
                            projects().resolve(".approving-" + answer.project() + ".lock"),
                            () -> install(answer, staged, place));
        } catch (IOException ex) {
            throw new Refused(Reason.NOT_WRITTEN, "cannot keep it: " + printable(ex.toString()));
        }
        if (refused.isPresent()) {
            throw refused.get();
        }
        forgetLookup(file);
        return answer.project();
    }

    /** Ends a question's lookup so a later draft cannot reuse an answered question. */
    private void forgetLookup(final Path file) {
        final @Nullable Path checkout = file.getParent();
        synchronized (kept) {
            kept.keySet().removeIf(each -> each.checkout().equals(checkout));
        }
    }

    /** Writes only a new file: a yes also allows exactly what the following start reads. */
    private void takeRepository(final Answer answer, final Path file) throws Refused {
        final Path top =
                Objects.requireNonNull(file.getParent(), "a repository file has a checkout");
        boolean wrote = false;
        try {
            write(file, answer.file());
            wrote = true;
            final DefaultProject fallback = new DefaultProject(context);
            final DefaultProject.Entry entry =
                    fallback.add(top.toString(), answer.repository(), top.toString(), origin(top));
            final Path projectFile = fallback.file();
            final Repository repository =
                    Objects.requireNonNull(
                            ProjectReader.read(projectFile).repository(entry.name()),
                            "the default project holds the checkout just added");
            final RepositoryOwnSettings.Found found =
                    Objects.requireNonNull(
                            RepositoryOwnSettings.of(context, projectFile, repository),
                            "the draft just written is a repository's own settings");
            if (!ProjectApprovals.hash(top, List.of(REPOSITORY_FILE)).equals(answer.hash())) {
                throw new Refused(Reason.MOVED_ON, "the file is not what that hash named");
            }
            new RepositoryAllowance(context).allow(entry.name(), found.asked());
        } catch (IOException | DefaultProject.Refused | RuntimeException ex) {
            if (wrote) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException cleanup) {
                    ex.addSuppressed(cleanup);
                }
            }
            throw new Refused(Reason.NOT_WRITTEN, "cannot keep it: " + printable(ex.toString()));
        }
    }

    /** Answers without detection; the launch still checks an existing file's allowance. */
    private Answer knownRepository(final Path file, final Path top, final String origin) {
        String hash = "";
        try {
            if (Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && Files.size(file) <= 1024 * 1024) {
                hash = ProjectApprovals.hash(top, List.of(REPOSITORY_FILE));
            }
        } catch (IOException ex) {
            // The launch reports an unreadable repository file; a look approves nothing here.
        }
        return new Answer(
                Outcome.KNOWN,
                List.of(),
                List.of(),
                List.of(),
                "",
                DefaultProject.NAME,
                hash,
                repositoryName(top, origin),
                file,
                null,
                List.of(),
                null,
                CheckoutProject.upstream(origin, top));
    }

    /** Keeps the draft and its allowance under the name a default start uses after collisions. */
    private String repositoryName(final Path top, final String origin) {
        final DefaultProject fallback = new DefaultProject(context);
        final DefaultProject.Entry entry = fallback.withUpstream(top.toString());
        if (entry != null) {
            return entry.name();
        }
        final String wanted = RepositoryAddress.nameOf(CheckoutProject.upstream(origin, top));
        final Set<String> taken = new LinkedHashSet<>();
        fallback.entries().forEach(each -> taken.add(each.name()));
        String candidate = wanted;
        for (int number = 2; DefaultProject.is(candidate) || taken.contains(candidate); number++) {
            candidate = wanted + "-" + number;
        }
        return candidate;
    }

    /** Keeps advice outside the checkout, so pulling or editing work cannot reset what was said. */
    private List<String> localProjectNote(final Path top) {
        final Path marks = context.paths().tasks().localProjectNotes();
        final Path mark = marks.resolve(hash("checkout", top.toString()));
        try {
            org.fuin.porelo.wire.Directories.create(marks);
            Files.writeString(mark, "", java.nio.file.StandardOpenOption.CREATE_NEW);
            return List.of(
                    "porelo.yml keeps a repository like this in 'default'; this checkout keeps its"
                            + " approved project.yml.");
        } catch (java.nio.file.FileAlreadyExistsException ex) {
            return List.of();
        } catch (IOException ex) {
            return List.of("cannot record the local project note: " + printable(ex.toString()));
        }
    }

    /** Hashes a single named text with the same framing as a project approval. */
    private static String hash(final String name, final String text) {
        try {
            final java.security.MessageDigest digest =
                    java.security.MessageDigest.getInstance("SHA-256");
            final byte[] named = name.getBytes(StandardCharsets.UTF_8);
            final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            digest.update((named.length + ":").getBytes(StandardCharsets.UTF_8));
            digest.update(named);
            digest.update((":" + bytes.length + ":").getBytes(StandardCharsets.UTF_8));
            digest.update(bytes);
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("every Java has SHA-256", ex);
        }
    }

    private Optional<Refused> install(
            final Answer answer, final StagedProject staged, final Path place) throws IOException {
        final Refused now = nameRefusal(answer.project(), answer.upstream());
        if (now != null) {
            return Optional.of(now);
        }
        final ProjectApprovals.Prepared record =
                new ProjectApprovals(context).prepare(answer.project(), answer.upstream());
        try {
            staged.install(place, record::commit);
        } catch (IOException ex) {
            record.abandon();
            throw ex;
        }
        return Optional.empty();
    }

    /** Never over what is there, and never through a link. */
    private static void write(final Path file, final String text) throws IOException, Refused {
        try {
            Files.writeString(
                    file,
                    text,
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE);
        } catch (java.nio.file.FileAlreadyExistsException ex) {
            throw new Refused(
                    Reason.MOVED_ON, "a " + file.getFileName() + " appeared since it was read");
        }
    }

    private String origin(final Path top) {
        final CommandResult remote =
                context.runner()
                        .run(
                                Command.of(
                                        "git",
                                        "-C",
                                        top.toString(),
                                        "remote",
                                        "get-url",
                                        "origin"));
        return RepositoryAddress.withoutCredential(
                remote.successful() ? remote.standardOutput().strip() : "");
    }

    private Path projects() {
        return context.paths().xdg().config().resolve("projects");
    }

    private Answer ofFile(final Path file, final String origin, final Path top) {
        final StagedProject staged;
        try {
            staged = StagedProject.read(projects(), file);
        } catch (StagedProject.Unreadable ex) {
            return refused(
                    file, new Refused(Reason.FILE_UNREADABLE, String.valueOf(ex.getMessage())));
        } catch (IOException ex) {
            return refused(
                    file,
                    new Refused(
                            Reason.NOT_WRITTEN, "cannot stage it: " + printable(ex.toString())));
        }
        final String hash;
        final String text;
        try {
            hash = staged.hash();
            text = staged.text();
        } catch (IOException ex) {
            staged.discard();
            return refused(
                    file,
                    new Refused(
                            Reason.NOT_WRITTEN, "cannot stage it: " + printable(ex.toString())));
        }
        final String declared;
        try {
            declared = staged.declaredName();
        } catch (IOException ex) {
            staged.discard();
            return refused(
                    file,
                    new Refused(
                            Reason.NOT_WRITTEN, "cannot stage it: " + printable(ex.toString())));
        }
        if (declared == null) {
            staged.discard();
            return refused(
                    file,
                    new Refused(
                            Reason.NO_NAME,
                            "project.name in "
                                    + FILE_NAME
                                    + " is no text, so no start could find that project by it;"
                                    + " write it as a name, in quotes if it is only digits"));
        }
        final Project project = staged.project();
        final String repository = CheckoutProject.repositoryOf(project, origin, top);
        final String upstream = CheckoutProject.upstream(origin, top);
        if (holdsTheCopy(project.name(), staged, upstream)) {
            staged.discard();
            return new Answer(
                    Outcome.KNOWN,
                    List.of(),
                    List.of(),
                    List.of(),
                    text,
                    project.name(),
                    hash,
                    repository,
                    file,
                    null,
                    localProjectNote(top),
                    null,
                    upstream);
        }
        final Set<String> hosts = new LinkedHashSet<>(project.egress().domains());
        final List<String> printed = hosts.stream().map(FirstStart::printable).toList();
        return new Answer(
                Outcome.ASK_UNKNOWN_FILE,
                foundIn(project),
                printed,
                // Nothing but the checkout names what a file in it opens.
                outsideCuratedSets(printed),
                text,
                project.name(),
                hash,
                repository,
                file,
                nameRefusal(project.name(), upstream),
                List.of(),
                staged,
                upstream);
    }

    /**
     * Same file, same project: the name finds this machine's copy, holding just what was hashed,
     * and approved for this upstream.
     */
    private boolean holdsTheCopy(
            final String name, final StagedProject staged, final String upstream) {
        final Path copy = context.paths().projects().defaultProjectFile(name);
        return copy.equals(ProjectSource.resolve(context, name).file())
                && new ProjectApprovals(context).approvedFor(name, upstream)
                && staged.sameAs(Objects.requireNonNull(copy.getParent(), "a place has a parent"));
    }

    private Answer refused(final Path file, final Refused refusal) {
        return new Answer(
                Outcome.ASK_UNKNOWN_FILE,
                List.of(),
                List.of(),
                List.of(),
                "",
                "",
                "",
                "",
                file,
                refusal,
                List.of(),
                null,
                "");
    }

    /**
     * What matters most in a stranger's file, ahead of its whole text, which the person also reads.
     */
    private static List<Found> foundIn(final Project project) {
        final List<Found> found = new ArrayList<>();
        found.add(found("security class", project.securityClass().name().toLowerCase(Locale.ROOT)));
        found.add(found("base image", project.baseImage()));
        project.imageSnippetLines().forEach(line -> found.add(found("image line", line)));
        if (project.packageSources() != null) {
            project.packageSources().forEach(each -> found.add(found("package source", each)));
        }
        project.egress().sets().forEach(each -> found.add(found("egress set", each)));
        project.egress().hostServices().forEach(each -> found.add(found("host service", each)));
        project.credentials()
                .forEach((id, where) -> found.add(found("credential", id + " for " + where)));
        for (final Repository each : project.allRepositories()) {
            if (each.upstream() != null) {
                found.add(found("repository", each.name() + " at " + each.upstream()));
            }
        }
        return List.copyOf(found);
    }

    private static Found found(final String what, final String value) {
        return new Found(printable(what), printable(value), FILE_NAME, false, "");
    }

    /**
     * Has a plugin look up what its Detect read, or answers with what this question already looked
     * up for the same Detect answer.
     */
    private EcosystemLookup.Looked lookedUp(
            final InstalledEcosystems.Description plugin,
            final Path here,
            final Path home,
            final Detection detected,
            final Map<String, String> proxies) {
        // A lookup made through one proxy is never handed to a caller asking through another.
        final Asked asked = new Asked(plugin.name(), here, home, detected, Map.copyOf(proxies));
        final Kept before = kept.get(asked);
        if (before != null && System.nanoTime() - before.at() < KEPT_NANOS) {
            return before.looked();
        }
        final EcosystemLookup.Looked looked =
                EcosystemLookup.resolve(detector, plugin, here, home, detected, proxies);
        kept.put(asked, new Kept(looked, System.nanoTime()));
        return looked;
    }

    private Answer detected(
            final Path here,
            final Path top,
            final String origin,
            final @Nullable Map<String, String> proxies) {
        final List<String> notes = new ArrayList<>();
        final List<InstalledEcosystems.Description> plugins;
        try {
            plugins = installed();
        } catch (EcosystemException ex) {
            return Answer.plain(
                    Outcome.NO_PLUGIN,
                    "",
                    "",
                    List.of("the installed ecosystem adapters could not be listed"));
        }
        final List<InstalledEcosystems.Description> usable = new ArrayList<>();
        for (final InstalledEcosystems.Description each : plugins) {
            if (each.refusal() == null) {
                usable.add(each);
            } else {
                notes.add(printable(each.name() + " is not used: " + each.refusal()));
            }
        }
        if (usable.isEmpty()) {
            return Answer.plain(Outcome.NO_PLUGIN, "", "", notes);
        }
        final String upstream = CheckoutProject.upstream(origin, top);
        final String repository = repositoryName(top, origin);
        final ProjectProposal proposal;
        try {
            // A repository with no origin is reached where a task in 'default' reaches it.
            proposal = new ProjectProposal(repository, upstream, null, null);
        } catch (IllegalArgumentException ex) {
            return Answer.plain(
                    Outcome.NOTHING_RECOGNIZED,
                    "",
                    "",
                    List.of(printable("'" + repository + "' can name no repository")));
        }
        final List<Found> found = new ArrayList<>();
        try {
            for (final InstalledEcosystems.Description each : usable) {
                final Path home = context.paths().xdg().home();
                final Placing asked = ask(each, here, home);
                if (asked.outcome() != Asking.DETECTED) {
                    if (!asked.note().isEmpty()) {
                        notes.add(asked.note());
                    }
                    continue;
                }
                Detection detection = asked.detection();
                if (proxies != null) {
                    final EcosystemLookup.Looked looked =
                            lookedUp(each, here, home, detection, proxies);
                    if (looked.note() != null) {
                        notes.add(printable(looked.note()));
                    }
                    detection = looked.detection();
                }
                proposal.add(each.name(), detection);
            }
            // Before the draft is made: one that cannot be made still says these.
            proposal.notesForAPerson().forEach(each -> notes.add(printable(each)));
            final String text = repositoryDraft(String.valueOf(proposal.answer().get("draft")));
            proposal.refusals().forEach(each -> notes.add(printable(each)));
            proposal.tools()
                    .forEach(
                            one ->
                                    found.add(
                                            new Found(
                                                    printable(one.what()),
                                                    printable(one.value()),
                                                    printable(one.source()),
                                                    false,
                                                    printable(one.note()))));
            // They run as root of the build: shown whole, never summed up.
            proposal.imageLines()
                    .forEach(
                            one ->
                                    found.add(
                                            new Found(
                                                    printable(one.what()),
                                                    printable(one.value()),
                                                    printable(one.source()),
                                                    false,
                                                    "")));
            proposal.unknowns()
                    .forEach(
                            one ->
                                    found.add(
                                            new Found(
                                                    printable(one.what()),
                                                    printable(one.value()),
                                                    printable(one.source()),
                                                    true,
                                                    "")));
            proposal.credentialIds()
                    .forEach(
                            id ->
                                    found.add(
                                            new Found(
                                                    "credential needed",
                                                    printable(id),
                                                    "",
                                                    false,
                                                    "")));
            if (!proposal.applies() && found.isEmpty()) {
                return Answer.plain(Outcome.NOTHING_RECOGNIZED, "", "", notes);
            }
            final List<String> hosts =
                    proposal.hosts().stream().map(FirstStart::printable).toList();
            final List<String> onlyHere =
                    proposal.checkoutHosts().stream().map(FirstStart::printable).toList();
            notes.add(
                    "this repository stays in 'default': its history comes from the checkout, and"
                            + " approved work goes back there");
            final String hash = hash(REPOSITORY_FILE, text);
            return new Answer(
                    Outcome.ASK_NEW,
                    List.copyOf(found),
                    hosts,
                    outsideCuratedSets(onlyHere),
                    text,
                    DefaultProject.NAME,
                    hash,
                    repository,
                    top.resolve(REPOSITORY_FILE),
                    null,
                    List.copyOf(notes),
                    null,
                    upstream);
        } catch (ProjectException | IllegalArgumentException ex) {
            // Its words may repeat what it was told: a plugin's answer is a stranger's input.
            notes.add(
                    "an ecosystem adapter's answer makes no repository file, so none is proposed");
            return Answer.plain(Outcome.NOTHING_RECOGNIZED, "", "", notes);
        }
    }

    /** Keeps the detected settings while leaving project identity and membership to the project. */
    private static String repositoryDraft(final String text) {
        final var project =
                org.fuin.porelo.core.project.file.ProjectFileReader.read(
                        new java.io.StringReader(text), "the detected draft", null);
        final String written =
                org.fuin.porelo.core.project.file.ProjectFileWriter.entry(
                        new org.fuin.porelo.core.project.file.ProjectFile(
                                null,
                                project.image(),
                                project.build(),
                                project.egress(),
                                project.limits(),
                                project.mail(),
                                project.builds(),
                                project.parameters(),
                                project.branch(),
                                project.credentials(),
                                Map.of(),
                                project.baseBranch()));
        org.fuin.porelo.core.project.file.RepositoryFileReader.readBesideSettings(
                new java.io.StringReader(written), REPOSITORY_FILE, null);
        return written;
    }

    private List<String> outsideCuratedSets(final List<String> hosts) {
        final Set<String> curated = new LinkedHashSet<>();
        for (final EgressSet set : context.paths().egress().egressSets().all().values()) {
            set.domains().forEach(each -> curated.add(each.toLowerCase(Locale.ROOT)));
        }
        return hosts.stream()
                .filter(each -> !curated.contains(each.toLowerCase(Locale.ROOT)))
                .toList();
    }

    /**
     * So a repository cannot take over a project this machine has: a name is free, or its copy was
     * approved for this upstream, whose yes may replace it.
     */
    private @Nullable Refused nameRefusal(final String name, final String upstream) {
        if (DefaultProject.is(name)) {
            return new Refused(Reason.NAME_TAKEN, "a project may not be called '" + name + "'");
        }
        if (!ProjectSource.names(context).contains(name)) {
            return null;
        }
        final Path copy = context.paths().projects().defaultProjectFile(name);
        if (copy.equals(ProjectSource.resolve(context, name).file())
                && new ProjectApprovals(context).approvedFor(name, upstream)) {
            return null;
        }
        return new Refused(
                Reason.NAME_TAKEN,
                "this machine already has a project '"
                        + name
                        + "' of another repository or other content; give this one another name in"
                        + " its "
                        + FILE_NAME);
    }

    private static Map<String, Object> plugin(final InstalledEcosystems.Description plugin) {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", plugin.name());
        map.put("executable", printable(plugin.executable().toString()));
        // Absent rather than "", as Describe leaves out a version nobody said.
        if (plugin.version() != null) {
            map.put("version", printable(plugin.version()));
        }
        if (plugin.protocolVersion() != null) {
            map.put("protocolVersion", plugin.protocolVersion());
        }
        if (plugin.refusal() != null) {
            map.put("refusal", printable(plugin.refusal()));
        }
        return map;
    }

    /** A plugin's words are a stranger's input: every text of a finding is made printable. */
    private static Map<String, Object> finding(final Finding finding) {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("part", finding.part().name());
        map.put("key", printable(finding.key()));
        map.put("value", printable(finding.value()));
        map.put("file", printable(finding.file()));
        map.put("line", Integer.valueOf(finding.line()));
        map.put("origin", finding.origin().name());
        map.put("unknown", Boolean.valueOf(finding.unknown()));
        map.put("note", printable(finding.note()));
        return map;
    }

    private static Map<String, Object> unplaced(final Unplaced unplaced) {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("file", printable(unplaced.file()));
        map.put("line", Integer.valueOf(unplaced.line()));
        map.put("note", printable(unplaced.note()));
        return map;
    }

    private static Map<String, Object> file(final File file) {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("path", printable(file.path()));
        map.put("ownerOnly", Boolean.valueOf(file.ownerOnly()));
        return map;
    }

    private static String printable(final String text) {
        return RegistryText.printable(text);
    }
}
