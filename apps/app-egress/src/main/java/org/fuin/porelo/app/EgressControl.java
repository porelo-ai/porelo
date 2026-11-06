package org.fuin.porelo.app;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.agent.api.InstalledAgent;
import org.fuin.porelo.agent.api.InstalledAgents;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.core.project.file.Egress;
import org.fuin.porelo.core.project.file.ProjectFile;
import org.fuin.porelo.core.project.file.ProjectFileReader;
import org.fuin.porelo.core.project.file.ProjectFileWriter;
import org.fuin.porelo.shield.EgressSetDirectory;
import org.fuin.porelo.shield.EgressSetException;
import org.jspecify.annotations.Nullable;

/**
 * Reading what a project may reach, and saying what a change to it opens and how to write it.
 *
 * <p>The CLI renders this and the daemon serializes it, so the most consequential change in the
 * product is judged in one place. Two implementations would differ exactly where it matters - which
 * sets are refused, whether a class forbids the change, what a change is reported to open - and the
 * one that drifted would be the one nobody was watching.
 *
 * <p><strong>Porelo never changes an existing project file.</strong> The file is the person's, and
 * reviewed as theirs: a change answers the {@code egress} section as it must read, and the person
 * writes it.
 */
public final class EgressControl {

    /** What a change to the egress of 'default' or one of its tasks is told. */
    static final String DEFAULT_REFUSED =
            "'"
                    + DefaultProject.NAME
                    + "' reaches nothing beyond what its tasks' agents"
                    + " need, and that is not changed; a project repository is the way to give work more: write its"
                    + " project.yml";

    /** What to change about a project's declaration. Every list may be empty. */
    public record Change(
            List<String> addSets,
            List<String> removeSets,
            List<String> addDomains,
            List<String> removeDomains) {

        /**
         * Constructor with defensive copies.
         *
         * @param addSets Sets to declare.
         * @param removeSets Sets to stop declaring.
         * @param addDomains Hosts to declare directly.
         * @param removeDomains Hosts to stop declaring.
         */
        public Change {
            addSets = List.copyOf(addSets);
            removeSets = List.copyOf(removeSets);
            addDomains = List.copyOf(addDomains);
            removeDomains = List.copyOf(removeDomains);
        }

        /**
         * Tells whether this asks for anything.
         *
         * @return {@code true} when every list is empty.
         */
        public boolean isEmpty() {
            return addSets.isEmpty()
                    && removeSets.isEmpty()
                    && addDomains.isEmpty()
                    && removeDomains.isEmpty();
        }
    }

    /** What became of a change. */
    public enum Outcome {

        /** What it would do, and the section a person writes for it. */
        PREVIEWED,

        /** Right, and not written: Porelo changes no project file; the person writes the entry. */
        BY_HAND,

        /** The file already said that. */
        NO_CHANGE,

        /** A named set is not installed on this machine, so the file would name nothing real. */
        NO_SUCH_SET,

        /** The project's security class forbids declaring egress at all. */
        REFUSED_BY_CLASS,

        /** The project file could not be read. */
        UNREADABLE
    }

    /**
     * What a change does, in hosts rather than in set names.
     *
     * @param outcome What became of it.
     * @param opens Hosts it adds, each with the origin that grants it.
     * @param closes Hosts it takes away, each with the origin that granted it.
     * @param cost What reaching a forge costs a guarded project, when this change opens one.
     * @param detail Why it was refused, or {@code null}.
     * @param entry The {@code egress} section as the file must read for the change, or {@code null}
     *     when there is nothing to write.
     */
    public record Effect(
            Outcome outcome,
            Map<String, String> opens,
            Map<String, String> closes,
            @Nullable String cost,
            @Nullable String detail,
            @Nullable String entry) {

        /**
         * Constructor with defensive copies.
         *
         * @param outcome What became of it.
         * @param opens Hosts it adds.
         * @param closes Hosts it takes away.
         * @param cost The forge sentence, or {@code null}.
         * @param detail Why it was refused, or {@code null}.
         * @param entry The section to write, or {@code null}.
         */
        public Effect {
            // Order is the report: hosts come out grouped by the set that granted them, which is
            // how an operator checks that a set is what they thought it was.
            opens = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(opens));
            closes = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(closes));
        }

        static Effect refused(Outcome outcome, String detail) {
            return new Effect(outcome, Map.of(), Map.of(), null, detail, null);
        }
    }

    /**
     * Says what a person writes, and where, for a change Porelo does not make itself.
     *
     * @param file The project file.
     * @param entry The {@code egress} section as it must read.
     * @return The words, the section's lines after them.
     */
    public static String byHand(final Path file, final String entry) {
        return "Porelo does not change "
                + file
                + "; write its egress section as:\n"
                + entry.stripTrailing();
    }

    private final PoreloContext context;

    /**
     * Constructor with the context to act through.
     *
     * @param context Where the paths and the installed sets come from.
     */
    public EgressControl(PoreloContext context) {
        this.context = context;
    }

    /**
     * Returns what a task on one repository may reach, host to the origin that grants it.
     *
     * <p>A repository's grants are <strong>added</strong> to the project's, so asking without one
     * answers the project-level set - true of every repository - and asking with one answers that
     * plus what it adds. Each host carries which of the two granted it.
     *
     * @param projectFile The project file.
     * @param repository Which repository, or {@code null} for the project's own.
     * @param agentName Agent to include, or {@code null} for the repository's, else the only one
     *     installed.
     * @return Hosts and origins.
     */
    public Map<String, String> reachable(
            Path projectFile, @Nullable String repository, @Nullable String agentName) {
        final Project read = ProjectReader.read(projectFile);
        // What a task may reach is the project's, whichever repository; in 'default' with what the
        // repository's own files add, each named by the file that gave it.
        final org.fuin.porelo.core.project.Repository chosen =
                GateSupport.repository(read, repository);
        final RepositoryOwnSettings.Found own = own(projectFile, read, chosen);
        final Project project = own == null ? read : own.project();
        final Map<String, String> declared =
                labelled(
                        EgressReport.projectEgress(project, context.paths().egress().egressSets()),
                        own);
        try (InstalledAgents agents = context.agents()) {
            final AgentChoice.Choice choice =
                    AgentChoice.decided(context, agents, agentName, chosen);
            final InstalledAgent selected = choice.agent();
            final SelectedProvider serving =
                    selected == null
                            ? null
                            : SelectedProvider.choose(
                                    context.providers(), selected.definition(), choice.provider());
            // Without what is refused, as the CLI's report: an interface told a refused host is
            // reachable plans on a name the task's resolver answers NXDOMAIN.
            return EgressReport.reachable(
                    EgressReport.compose(selected, serving, declared).origins(),
                    EgressReport.refusals(selected, project));
        }
    }

    /**
     * Returns the names a task would refuse whatever allows them, each with who refused it.
     *
     * @param projectFile The project file.
     * @param repository Which repository, or {@code null} for the project's own.
     * @param agentName Agent to include, or {@code null} for the repository's, else the only one
     *     installed.
     * @return Host to who refused it, empty when nobody refuses anything.
     */
    public Map<String, String> refusals(
            Path projectFile, @Nullable String repository, @Nullable String agentName) {
        final Project read = ProjectReader.read(projectFile);
        final org.fuin.porelo.core.project.Repository chosen =
                GateSupport.repository(read, repository);
        final RepositoryOwnSettings.Found own = own(projectFile, read, chosen);
        final Project project = own == null ? read : own.project();
        try (InstalledAgents agents = context.agents()) {
            return EgressReport.refusals(
                    AgentChoice.select(context, agents, agentName, chosen), project);
        }
    }

    /**
     * Returns what a repository's own files took away from what a task may reach, each with the
     * file that did.
     *
     * @param projectFile The project file.
     * @param repository Which repository, or {@code null} for the project's own.
     * @return Each removal under {@code egress} as {@code key} and {@code by}, in order; empty
     *     outside the default project.
     */
    public List<Map<String, Object>> removed(Path projectFile, @Nullable String repository) {
        final Project read = ProjectReader.read(projectFile);
        final RepositoryOwnSettings.Found own =
                own(projectFile, read, GateSupport.repository(read, repository));
        if (own == null) {
            return List.of();
        }
        return own.removed().stream()
                .filter(each -> each.key().startsWith("egress."))
                .<Map<String, Object>>map(each -> Map.of("key", each.key(), "by", each.by()))
                .toList();
    }

    /**
     * What a repository's own files add, in the default project or, signed, in an organization's,
     * for a view: a file that does not read shows the project as it is, and the start says why.
     */
    private RepositoryOwnSettings.@Nullable Found own(
            final Path projectFile,
            final Project project,
            final org.fuin.porelo.core.project.@Nullable Repository chosen) {
        if (chosen == null) {
            return null;
        }
        try {
            return DefaultProject.is(project.name())
                    ? RepositoryOwnSettings.of(context, projectFile, chosen)
                    : RepositoryOwnSettings.ofOrganization(
                            context, projectFile, project.name(), chosen);
        } catch (ProjectException ex) {
            return null;
        }
    }

    /** Names a repository's file where it, not the project, gave a host or a set. */
    private static Map<String, String> labelled(
            final Map<String, String> origins, final RepositoryOwnSettings.@Nullable Found own) {
        if (own == null) {
            return origins;
        }
        final String project = org.fuin.porelo.core.project.file.RepositorySettings.PROJECT;
        final Map<String, String> labelled = new LinkedHashMap<>();
        origins.forEach(
                (host, origin) -> {
                    final boolean named = "project".equals(origin);
                    final String key =
                            named
                                    ? "egress.domains[" + host + "]"
                                    : origin.startsWith("set ")
                                            ? "egress.sets[" + origin.substring(4) + "]"
                                            : "";
                    final String source = own.sources().getOrDefault(key, project);
                    labelled.put(
                            host,
                            source.equals(project)
                                    ? origin
                                    : named ? source : origin + " (" + source + ")");
                });
        return labelled;
    }

    /**
     * Judges a change to a project's declaration and answers the section to write for it.
     *
     * @param projectFile The project file.
     * @param change What to change.
     * @param dryRun Whether only to preview it.
     * @return What it does, or why it was refused.
     */
    public Effect apply(Path projectFile, Change change, boolean dryRun) {
        return apply(projectFile, null, change, dryRun);
    }

    /**
     * Judges a change for a task on one repository and answers the section to write for it.
     *
     * <p><strong>The change is the project's.</strong> What a task may reach is the project's
     * alone, whichever of its repositories the task works on, so the section is the project's
     * {@code egress}. A repository the project does not name is refused, so a mistyped name is not
     * mistaken for a grant.
     *
     * <p>The section is checked in the whole file it makes, by the check that would read it later,
     * so an entry the reader would refuse is never shown.
     *
     * @param projectFile The project file, which is never written.
     * @param repository Which repository the task works on, or {@code null} for the project's own.
     * @param change What to change.
     * @param dryRun Whether only to preview it; {@link Outcome#PREVIEWED} instead of {@link
     *     Outcome#BY_HAND}.
     * @return What it does, or why it was refused.
     */
    public Effect apply(
            Path projectFile, @Nullable String repository, Change change, boolean dryRun) {

        final Project project;
        final ProjectFile file;
        try {
            project = ProjectReader.read(projectFile);
            file = ProjectFileReader.read(projectFile);
        } catch (ProjectException ex) {
            return Effect.refused(Outcome.UNREADABLE, CliErrors.reason(ex));
        }

        if (DefaultProject.is(project.name())) {
            // Its settings are Porelo's and cannot be changed; an edit to its file would not even
            // last, since it is written anew whenever it is read.
            return Effect.refused(Outcome.REFUSED_BY_CLASS, DEFAULT_REFUSED);
        }

        // The project's own repository is named after the project; a caller that passed the
        // project's name means that one.
        final String named =
                repository == null || repository.isBlank() || repository.equals(project.name())
                        ? null
                        : repository;
        try {
            // Only to be found: what a task may reach is the project's, whichever repository.
            GateSupport.repository(project, named);
        } catch (ProjectException ex) {
            return Effect.refused(Outcome.UNREADABLE, CliErrors.reason(ex));
        }

        final Egress declared =
                file.egress() == null
                        ? new Egress(List.of(), List.of(), List.of(), List.of())
                        : file.egress();
        final Egress wanted =
                new Egress(
                        edited(declared.sets(), change.addSets(), change.removeSets()),
                        edited(declared.domains(), change.addDomains(), change.removeDomains()),
                        declared.hostServices(),
                        declared.refused());
        final ProjectFile changed = withEgress(file, isEmpty(wanted) ? null : wanted);

        final EgressSetDirectory sets = context.paths().egress().egressSets();
        final Project after;
        final String entry;
        final Map<String, String> before;
        final Map<String, String> now;
        try {
            entry = ProjectFileWriter.entry(changed, onlyEgress(wanted));
            after =
                    ProjectReader.read(
                            new StringReader(ProjectFileWriter.write(changed)),
                            projectFile.toString());
            before = EgressReport.projectEgress(project, sets);
            now = EgressReport.projectEgress(after, sets);
        } catch (ProjectException ex) {
            // Where an offline project is refused, in the words that rule already chose.
            return Effect.refused(Outcome.REFUSED_BY_CLASS, CliErrors.reason(ex));
        } catch (EgressSetException ex) {
            // A set this machine does not have, whether the change added it or the file already
            // named one. Refused before any entry is shown: the file would otherwise name
            // something no task here could resolve, and every run would fail on it.
            return Effect.refused(Outcome.NO_SUCH_SET, CliErrors.reason(ex));
        }

        final Map<String, String> opens = new LinkedHashMap<>(now);
        before.keySet().forEach(opens::remove);
        final Map<String, String> closes = new LinkedHashMap<>(before);
        now.keySet().forEach(closes::remove);

        // Said at the moment it becomes true, and not again on a later change: a warning repeated
        // when nothing changed is one an operator learns to skip.
        final String cost =
                EgressReport.forgeNote(after, now) != null
                                && EgressReport.forgeNote(project, before) == null
                        ? EgressReport.forgeNote(after, now)
                        : null;

        if (opens.isEmpty() && closes.isEmpty()) {
            return new Effect(Outcome.NO_CHANGE, opens, closes, cost, null, null);
        }
        if (dryRun) {
            return new Effect(Outcome.PREVIEWED, opens, closes, cost, null, entry);
        }
        return new Effect(Outcome.BY_HAND, opens, closes, cost, byHand(projectFile, entry), entry);
    }

    private static boolean isEmpty(final Egress egress) {
        return egress.sets().isEmpty()
                && egress.domains().isEmpty()
                && egress.hostServices().isEmpty()
                && egress.refused().isEmpty();
    }

    /** The file with another egress section and all else as it was. */
    private static ProjectFile withEgress(final ProjectFile file, final @Nullable Egress egress) {
        return new ProjectFile(
                file.project(),
                file.image(),
                file.build(),
                egress,
                file.limits(),
                file.mail(),
                file.builds(),
                file.parameters(),
                file.branch(),
                file.credentials(),
                file.repositories(),
                file.baseBranch());
    }

    /** The egress section alone, which is what a person writes. */
    private static ProjectFile onlyEgress(final Egress egress) {
        return new ProjectFile(
                null, null, null, egress, null, null, null, Map.of(), null, Map.of(), Map.of());
    }

    /**
     * Returns the list with the additions appended and the removals gone.
     *
     * <p>Order is the file's own, and an addition goes at the end: rewriting the order of a list
     * somebody wrote would make the diff say more than the change did.
     */
    private static List<String> edited(
            List<String> current, List<String> additions, List<String> removals) {
        final List<String> wanted = new ArrayList<>(current);
        additions.forEach(
                value -> {
                    if (!wanted.contains(value)) {
                        wanted.add(value);
                    }
                });
        wanted.removeAll(removals);
        return List.copyOf(wanted);
    }
}
