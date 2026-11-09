package org.fuin.porelo.app;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.core.project.ModelDefault;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.core.project.Repository;
import org.jspecify.annotations.Nullable;

/**
 * Which model a task runs on: the one its start names, else its repository's own file's for its
 * provider, else its project file's, else none and the agent's own default. One answer for a start,
 * for the question whether it could start, and for a client showing it before.
 */
public final class ModelChoice {

    private ModelChoice() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * The model a task runs on, and where that came from.
     *
     * @param model The model's id, or {@code null} for the agent's own default.
     * @param label What a person is shown for it, or {@code ""}.
     * @param from {@code start}, {@code repository}, {@code project} or {@code agent}.
     */
    public record Settled(@Nullable String model, String label, String from) {

        /** Nothing named a model. */
        public static final Settled AGENTS = new Settled(null, "", ModelDefault.AGENT);
    }

    /**
     * One provider's model as a start would settle it, for a client to show before.
     *
     * @param provider The provider, by the name {@code porelo providers} lists.
     * @param model The model's id, or {@code ""} for the agent's own default.
     * @param label What a person is shown for it, or {@code ""}.
     * @param from {@code repository}, {@code project} or {@code agent}.
     */
    public record ProviderModel(String provider, String model, String label, String from) {

        /**
         * Returns it as plain values, for a wire.
         *
         * @return The fields by name.
         */
        public Map<String, Object> asMap() {
            return Map.of("provider", provider, "model", model, "label", label, "from", from);
        }
    }

    /**
     * Settles the model a start runs on.
     *
     * @param asked The model the start names, or {@code null}.
     * @param project The project, with its repository's own settings added.
     * @param provider The provider the start settled, or {@code null} when it is brokered to none.
     * @return The model and its source.
     */
    public static Settled settle(
            final @Nullable String asked, final Project project, final @Nullable String provider) {
        if (asked != null) {
            return new Settled(asked, "", ModelDefault.START);
        }
        final ModelDefault named = provider == null ? null : project.providers().get(provider);
        return named == null
                ? Settled.AGENTS
                : new Settled(named.model(), named.label(), named.from());
    }

    /**
     * Says why a model a start names cannot be taken: anything but one word.
     *
     * @param asked The model, or {@code null}.
     * @param option How the start named it, such as {@code --model}.
     * @return Why, or {@code null} when it may be taken.
     */
    public static @Nullable String refusal(final @Nullable String asked, final String option) {
        return asked == null || ModelDefault.isWord(asked)
                ? null
                : option + " is one word, a model's id, not '" + ModelDefault.shown(asked) + "'";
    }

    /**
     * Says which provider the project names a model for that this machine does not know: a typo
     * would otherwise leave every task on the agent's own default without a word.
     *
     * @param project The project, with its repository's own settings added.
     * @param known The providers this machine knows, by name.
     * @return Why it is refused, or {@code null} when every one is known.
     */
    public static @Nullable String unknownProvider(final Project project, final Set<String> known) {
        for (final String provider : project.providers().keySet()) {
            if (!known.contains(provider)) {
                return "'providers."
                        + provider
                        + "' names a provider this machine does not know; 'porelo providers'"
                        + " lists those it does";
            }
        }
        return null;
    }

    /**
     * Returns each provider's model as a start on the repository would settle it when it names
     * none.
     *
     * @param context This machine.
     * @param projectFile The project's file.
     * @param repository The repository's name, or {@code null} for the project's file alone.
     * @return One entry per provider this machine knows, in name order.
     * @throws org.fuin.porelo.core.project.ProjectException If a file cannot be read or names a
     *     provider this machine does not know.
     */
    public static List<ProviderModel> defaults(
            final PoreloContext context,
            final Path projectFile,
            final @Nullable String repository) {
        final Project read = ProjectReader.read(projectFile);
        final Project project =
                repository == null || repository.isBlank()
                        ? read
                        : withRepository(
                                context,
                                projectFile,
                                read,
                                GateSupport.repository(read, repository));
        final Set<String> known = context.providers().keySet();
        final String unknown = unknownProvider(project, known);
        if (unknown != null) {
            throw new org.fuin.porelo.core.project.ProjectException(unknown);
        }
        final List<ProviderModel> all = new ArrayList<>();
        for (final String provider : new java.util.TreeSet<>(known)) {
            final Settled settled = settle(null, project, provider);
            all.add(
                    new ProviderModel(
                            provider,
                            settled.model() == null ? "" : settled.model(),
                            settled.label(),
                            settled.from()));
        }
        return List.copyOf(all);
    }

    /**
     * Returns the project with a repository's own files added, as a start takes them: in {@code
     * default} the checkout's once this machine allowed what they ask, in an organization's project
     * the signed one its mirror holds.
     *
     * @param context This machine.
     * @param projectFile The project's file.
     * @param project The project as its file says it.
     * @param repository The repository.
     * @return The project a start on it runs under.
     */
    public static Project withRepository(
            final PoreloContext context,
            final Path projectFile,
            final Project project,
            final Repository repository) {
        final RepositoryOwnSettings.Found found =
                DefaultProject.is(project.name())
                        ? RepositoryOwnSettings.of(context, projectFile, repository)
                        : RepositoryOwnSettings.ofOrganization(
                                context, projectFile, project.name(), repository);
        // In default a file counts only once this machine allowed what it asks, as at the start.
        return found == null
                        || DefaultProject.is(project.name())
                                && !found.toAllow(context, repository.name()).isEmpty()
                ? project
                : found.project();
    }
}
