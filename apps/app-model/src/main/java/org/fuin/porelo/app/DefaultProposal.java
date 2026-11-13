package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.fuin.porelo.core.credential.Credential;
import org.jspecify.annotations.Nullable;

/**
 * A proposal for a repository of {@value DefaultProject#NAME}, as a window asks the daemon for it,
 * and the person's answer to it.
 *
 * <p><strong>The adapters are asked as {@code porelo project init} asks them</strong>, through
 * {@link ProjectProposal#propose}, so the two never differ. <strong>Only a checkout on this machine
 * is read</strong>, as the person sees it and never written; a repository added by address has only
 * its mirror, which is not read yet, and is refused as {@link #NO_CHECKOUT}. <strong>The answer
 * writes no file</strong>: it is recorded, so nothing asks again.
 */
public final class DefaultProposal {

    /** Why a repository cannot be read: it has no checkout on this machine. */
    public static final String NO_CHECKOUT = "no-checkout";

    /** Why a repository cannot be read: its project is not {@value DefaultProject#NAME}. */
    public static final String NOT_DEFAULT = "not-default";

    /** What the person takes of a proposal. Every one is recorded as asked. */
    public enum Choice {

        /** The tools. */
        TOOLS,

        /** The tools, and the project.yml draft answered for the person to file. */
        TOOLS_AND_DRAFT,

        /** Nothing. */
        NONE
    }

    /** The repository cannot be proposed; nothing was asked or recorded. */
    public static final class NotProposable extends Exception {

        private static final long serialVersionUID = 1L;

        private final String reason;

        /**
         * Constructor.
         *
         * @param reason {@link #NO_CHECKOUT} or {@link #NOT_DEFAULT}.
         */
        public NotProposable(final String reason) {
            super(reason);
            this.reason = reason;
        }

        /**
         * Returns why, as a named value.
         *
         * @return {@link #NO_CHECKOUT} or {@link #NOT_DEFAULT}.
         */
        public String reason() {
            return reason;
        }
    }

    /** {@value DefaultProject#NAME} has no repository of that name. */
    public static final class NoSuchRepository extends Exception {

        private static final long serialVersionUID = 1L;

        /**
         * Constructor.
         *
         * @param name The name asked for.
         */
        public NoSuchRepository(final String name) {
            super(name);
        }
    }

    /**
     * The answer recorded.
     *
     * @param entry The repository as it is now.
     * @param draft The project.yml draft, under {@link Choice#TOOLS_AND_DRAFT} alone.
     */
    public record Taken(DefaultProject.Entry entry, @Nullable String draft) {}

    private final PoreloContext context;

    private final EcosystemDetector detector;

    /**
     * Constructor.
     *
     * @param context This machine.
     * @param detector Who asks the adapters.
     */
    public DefaultProposal(final PoreloContext context, final EcosystemDetector detector) {
        this.context = context;
        this.detector = detector;
    }

    /**
     * Asks every adapter about a repository's checkout and merges their answers.
     *
     * @param project The project, {@value DefaultProject#NAME} alone.
     * @param repository The repository's name in it.
     * @param done Takes each adapter's answer as it comes.
     * @return The proposal, as the wire's {@code Proposal}.
     * @throws NotProposable When the repository cannot be read.
     * @throws NoSuchRepository When there is no such repository.
     * @throws IOException When an adapter cannot be asked at all.
     */
    public Map<String, Object> propose(
            final String project, final String repository, final Consumer<AdapterDone> done)
            throws NotProposable, NoSuchRepository, IOException {
        final DefaultProject.Entry entry = proposable(project, repository);
        final @Nullable String upstream =
                entry.remote().isBlank() || RepositoryAddress.same(entry.remote(), entry.checkout())
                        ? null
                        : entry.remote();
        final ProjectProposal made = new ProjectProposal(entry.name(), upstream, null, null);
        final List<String> notes =
                made.propose(context, Path.of(entry.checkout()), detector, true, done);
        final Map<String, @Nullable Object> answer = made.answer();
        final Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("repository", entry.name());
        if (upstream != null) {
            wire.put("upstream", upstream);
        }
        wire.put("draft", String.valueOf(answer.get("draft")));
        wire.put("values", listed(answer.get("values")).stream().map(made::wireValue).toList());
        final List<Map<String, Object>> disagreements = new ArrayList<>();
        for (final Object each : listed(answer.get("disagreements"))) {
            final Map<?, ?> disagreement = (Map<?, ?>) each;
            disagreements.add(
                    Map.of(
                            "key",
                            String.valueOf(disagreement.get("key")),
                            "values",
                            listed(disagreement.get("values")).stream()
                                    .map(made::wireValue)
                                    .toList()));
        }
        wire.put("disagreements", disagreements);
        final List<Map<String, Object>> unplaced = new ArrayList<>();
        for (final Object each : listed(answer.get("unplaced"))) {
            final Map<?, ?> one = (Map<?, ?>) each;
            final Map<String, Object> row = new LinkedHashMap<>();
            row.put("file", String.valueOf(one.get("file")));
            row.put("line", java.util.Objects.requireNonNull(one.get("line")));
            row.put("note", String.valueOf(one.get("note")));
            row.put("adapter", String.valueOf(one.get("plugin")));
            unplaced.add(row);
        }
        wire.put("unplaced", unplaced);
        wire.put(
                "notPlaced",
                listed(answer.get("not_placed")).stream().map(made::wireValue).toList());
        wire.put("credentials", made.credentialIds().stream().map(this::credential).toList());
        wire.put("refused", listed(answer.get("refused")).stream().map(String::valueOf).toList());
        wire.put("notes", List.copyOf(notes));
        return wire;
    }

    /**
     * Records the person's answer to a proposal: asked, whichever the choice. Writes no file.
     *
     * @param project The project, {@value DefaultProject#NAME} alone.
     * @param repository The repository's name in it.
     * @param choice What the person takes.
     * @return The repository as it is now, and the draft under {@link Choice#TOOLS_AND_DRAFT}.
     * @throws NotProposable When the repository cannot be read; nothing is recorded.
     * @throws NoSuchRepository When there is no such repository.
     * @throws IOException When an adapter cannot be asked; nothing is recorded.
     */
    public Taken take(final String project, final String repository, final Choice choice)
            throws NotProposable, NoSuchRepository, IOException {
        proposable(project, repository);
        // Asked again rather than remembered: nothing is kept between a proposal and its answer,
        // and a draft that fails records nothing.
        final String draft =
                choice == Choice.TOOLS_AND_DRAFT
                        ? String.valueOf(propose(project, repository, done -> {}).get("draft"))
                        : null;
        final DefaultProject.Entry asked = new DefaultProject(context).asked(repository);
        if (asked == null) {
            throw new NoSuchRepository(repository);
        }
        return new Taken(asked, draft);
    }

    /**
     * Returns a repository as the wire's {@code DefaultRepository} has it, but for {@code
     * claimedBy}: with its proposal worked out now.
     *
     * @param entry The repository.
     * @return Its row.
     */
    public Map<String, Object> row(final DefaultProject.Entry entry) {
        final Map<String, Object> row = new LinkedHashMap<>(entry.asMap());
        row.put("proposal", new DefaultProject(context).proposal(entry).name());
        return row;
    }

    private DefaultProject.Entry proposable(final String project, final String repository)
            throws NotProposable, NoSuchRepository {
        if (!DefaultProject.is(project)) {
            throw new NotProposable(NOT_DEFAULT);
        }
        final DefaultProject.Entry entry =
                new DefaultProject(context)
                        .usableEntries().stream()
                                .filter(each -> each.name().equals(repository))
                                .findFirst()
                                .orElseThrow(() -> new NoSuchRepository(repository));
        if (entry.source() != DefaultProject.Source.CHECKOUT
                || !Files.isDirectory(Path.of(entry.checkout()))) {
            throw new NotProposable(NO_CHECKOUT);
        }
        return entry;
    }

    /**
     * A credential by its id, with what stores it here: its declared record's, else the vault's.
     */
    private Map<String, Object> credential(final String id) {
        String command = "porelo vault put " + id;
        for (final Credential declared : context.credentialRegistry().all()) {
            if (declared.id().equals(id)) {
                command = CredentialDeclarations.storeCommandFor(declared);
                break;
            }
        }
        return Map.of(
                "id",
                id,
                "storeCommand",
                CredentialDeclarations.argumentsOf(command),
                "storeStdin",
                command.contains("<") ? "the private key file" : "");
    }

    private static List<?> listed(final @Nullable Object value) {
        return value instanceof List<?> list ? list : List.of();
    }
}
