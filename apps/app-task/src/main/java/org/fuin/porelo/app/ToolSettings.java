package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.fuin.porelo.agent.api.ContainerFile;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.TaskVariables;
import org.fuin.porelo.core.project.ToolRepository;
import org.fuin.porelo.ecosystem.api.EcosystemRefused;
import org.fuin.porelo.ecosystem.api.Extension;
import org.fuin.porelo.ecosystem.api.File;
import org.fuin.porelo.ecosystem.api.InstalledEcosystems;
import org.fuin.porelo.ecosystem.api.Repository;

/**
 * The settings files a new task's build tools need to reach the repositories its project declares:
 * each ecosystem adapter the project names is asked {@code Configure} with its entries and the
 * block the project keeps for it, before anything of the task is made.
 *
 * <p>An adapter's words are a stranger's input, so a refusal is said in Porelo's own: which
 * adapter, and what the person does.
 */
final class ToolSettings {

    private ToolSettings() {
        throw new UnsupportedOperationException("Utility class");
    }

    /** What asking the adapters came to. */
    sealed interface Answer {

        /**
         * The files to place, each at its path inside the task.
         *
         * @param files They, in the order the project names their adapters; empty for a project
         *     that declares no tool repository.
         */
        record Files(List<ContainerFile> files) implements Answer {}

        /**
         * The task must not start.
         *
         * @param reason Why, and what the person does, in Porelo's words.
         */
        record Refused(String reason) implements Answer {}
    }

    /**
     * Asks each adapter the project names for its tool's settings.
     *
     * @param project The project, read.
     * @param directories Where adapters are installed, the account's own first.
     * @param adapters Who asks an installed adapter.
     * @return The files, or why the task must not start.
     */
    static Answer ask(
            final Project project, final List<Path> directories, final EcosystemDetector adapters) {
        if (project.toolRepositories().isEmpty()) {
            return new Answer.Files(List.of());
        }
        final Map<String, List<Repository>> byAdapter = new LinkedHashMap<>();
        for (final ToolRepository each : project.toolRepositories()) {
            byAdapter
                    .computeIfAbsent(each.ecosystem(), unused -> new ArrayList<>())
                    .add(new Repository(each.name(), each.url()));
        }
        final List<InstalledEcosystems.Description> installed;
        try {
            installed = InstalledEcosystems.all(directories);
        } catch (RuntimeException ex) {
            return new Answer.Refused(
                    "the ecosystem adapters could not be listed, so the build tools' settings"
                            + " cannot be written; 'porelo ecosystems' says why");
        }
        final String home =
                Objects.requireNonNull(TaskVariables.fixed().get("HOME"), "every task sets HOME");
        final Map<String, String> writtenBy = new LinkedHashMap<>();
        final List<ContainerFile> files = new ArrayList<>();
        for (final Map.Entry<String, List<Repository>> each : byAdapter.entrySet()) {
            final String adapter = each.getKey();
            final InstalledEcosystems.Description plugin =
                    installed.stream()
                            .filter(description -> description.name().equals(adapter))
                            .findFirst()
                            .orElse(null);
            if (plugin == null) {
                return new Answer.Refused(
                        "the project's tool repositories need the ecosystem adapter '"
                                + adapter
                                + "', which is not installed here; install it, or take its"
                                + " entries out of 'tool_repositories'");
            }
            if (plugin.refusal() != null) {
                return new Answer.Refused(
                        "the ecosystem adapter '"
                                + adapter
                                + "' is installed but cannot be used; 'porelo ecosystems' says"
                                + " why");
            }
            final List<File> answered;
            try {
                answered =
                        adapters.configure(
                                plugin,
                                each.getValue(),
                                new Extension(
                                        project.extensions().getOrDefault(adapter, Map.of())));
            } catch (EcosystemRefused ex) {
                return new Answer.Refused(
                        "the ecosystem adapter '"
                                + adapter
                                + "' refused the project's settings for it; correct its entries in"
                                + " 'tool_repositories' or 'extensions."
                                + adapter
                                + "' as the adapter's documentation says, and start again");
            } catch (IOException | RuntimeException ex) {
                // Never its message: what failed may quote the adapter's answer.
                return new Answer.Refused(
                        "the ecosystem adapter '"
                                + adapter
                                + "' failed, took too long, or answered a file that cannot be"
                                + " placed; 'porelo ecosystems' shows whether it is installed as it"
                                + " should be, and a start after it is fixed asks it again");
            }
            for (final File file : answered) {
                final String path = home + "/" + file.path();
                final String earlier = writtenBy.putIfAbsent(path, adapter);
                if (earlier != null) {
                    return new Answer.Refused(
                            "the ecosystem adapters '"
                                    + earlier
                                    + "' and '"
                                    + adapter
                                    + "' both write "
                                    + path
                                    + "; keep the entries of one of them in"
                                    + " 'tool_repositories'");
                }
                files.add(new ContainerFile(path, file.content(), file.ownerOnly()));
            }
        }
        return new Answer.Files(List.copyOf(files));
    }
}
