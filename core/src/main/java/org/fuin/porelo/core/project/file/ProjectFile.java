package org.fuin.porelo.core.project.file;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A {@code project.yml}, as it is written: each section its own type, and nothing it cannot hold.
 *
 * <p>A section the file leaves out is {@code null}, a named list it leaves out is empty; whether
 * the whole is usable is {@link ProjectFileCheck}'s question.
 *
 * @param project Who the project is ({@code project}).
 * @param image What a task's container is built from ({@code image}).
 * @param build What the image build itself fetches from ({@code build}).
 * @param egress What a task may reach ({@code egress}).
 * @param limits What one task may consume ({@code limits}).
 * @param mail Whom the project's tasks may write to ({@code mail}).
 * @param builds Where an online project's builds are read ({@code builds}).
 * @param parameters The values a task may be started with, by name, in the order written.
 * @param branch How a task's branch at the forge is named ({@code branch}).
 * @param credentials Vault entry to the destination it is for, in the order written.
 * @param repositories The repositories the work happens in, by name, in the order written.
 * @param baseBranch The branch a task starts from when neither a checkout nor {@code --from} names
 *     one ({@code base_branch}).
 * @param mcp What a task may do through its MCP endpoint ({@code mcp}); {@code null} allows it
 *     none.
 * @param providers Each provider's default model, by the provider's name, in the order written
 *     ({@code providers}).
 * @param toolRepositories The repositories a build tool in a task reaches, in the order written
 *     ({@code tool_repositories}).
 * @param extensions The block kept for each ecosystem adapter, by the adapter's name, as plain
 *     values: text, truth values, numbers, lists and maps ({@code extensions}).
 * @param readings Where a reading of an issue writes its findings ({@code readings}).
 */
public record ProjectFile(
        @Nullable ProjectInfo project,
        @Nullable Image image,
        @Nullable BuildSources build,
        @Nullable Egress egress,
        @Nullable Limits limits,
        @Nullable Mail mail,
        @Nullable Builds builds,
        Map<String, Parameter> parameters,
        @Nullable BranchScheme branch,
        Map<String, String> credentials,
        Map<String, RepositoryEntry> repositories,
        @Nullable String baseBranch,
        @Nullable Mcp mcp,
        Map<String, ProviderEntry> providers,
        java.util.List<ToolRepositoryEntry> toolRepositories,
        Map<String, Object> extensions,
        @Nullable Readings readings) {

    /**
     * Constructor.
     *
     * @param project Who the project is.
     * @param image What a task's container is built from.
     * @param build What the image build fetches from.
     * @param egress What a task may reach.
     * @param limits What one task may consume.
     * @param mail Whom its tasks may write to.
     * @param builds Where its builds are read.
     * @param parameters The values a task may be started with.
     * @param branch How a task's branch is named.
     * @param credentials Vault entries to their destinations.
     * @param repositories The repositories by name.
     * @param baseBranch The branch a task starts from by default.
     * @param mcp What a task may do through its MCP endpoint, or {@code null}.
     * @param providers Each provider's default model, by name.
     * @param toolRepositories The repositories a build tool reaches.
     * @param extensions The block kept for each ecosystem adapter, by its name.
     * @param readings Where a reading writes its findings, or {@code null}.
     */
    public ProjectFile {
        parameters = ordered(parameters);
        credentials = ordered(credentials);
        repositories = ordered(repositories);
        providers = ordered(providers);
        toolRepositories =
                toolRepositories == null
                        ? java.util.List.of()
                        : java.util.List.copyOf(toolRepositories);
        extensions = ordered(extensions);
    }

    /**
     * Constructor for a file that declares no tool's repository.
     *
     * @param project Who the project is.
     * @param image What a task's container is built from.
     * @param build What the image build fetches from.
     * @param egress What a task may reach.
     * @param limits What one task may consume.
     * @param mail Whom its tasks may write to.
     * @param builds Where its builds are read.
     * @param parameters The values a task may be started with.
     * @param branch How a task's branch is named.
     * @param credentials Vault entries to their destinations.
     * @param repositories The repositories by name.
     * @param baseBranch The branch a task starts from by default.
     * @param mcp What a task may do through its MCP endpoint, or {@code null}.
     * @param providers Each provider's default model, by name.
     */
    public ProjectFile(
            @Nullable ProjectInfo project,
            @Nullable Image image,
            @Nullable BuildSources build,
            @Nullable Egress egress,
            @Nullable Limits limits,
            @Nullable Mail mail,
            @Nullable Builds builds,
            Map<String, Parameter> parameters,
            @Nullable BranchScheme branch,
            Map<String, String> credentials,
            Map<String, RepositoryEntry> repositories,
            @Nullable String baseBranch,
            @Nullable Mcp mcp,
            Map<String, ProviderEntry> providers) {
        this(
                project,
                image,
                build,
                egress,
                limits,
                mail,
                builds,
                parameters,
                branch,
                credentials,
                repositories,
                baseBranch,
                mcp,
                providers,
                java.util.List.of(),
                Map.of());
    }

    /**
     * Constructor, for a file that names no readings directory.
     *
     * @param project Who the project is.
     * @param image What a task's container is built from.
     * @param build What the image build fetches from.
     * @param egress What a task may reach.
     * @param limits What one task may consume.
     * @param mail Whom its tasks may write to.
     * @param builds Where its builds are read.
     * @param parameters The values a task may be started with.
     * @param branch How a task's branch is named.
     * @param credentials Vault entries to their destinations.
     * @param repositories The repositories by name.
     * @param baseBranch The branch a task starts from by default.
     * @param mcp What a task may do through its MCP endpoint, or {@code null}.
     * @param providers Each provider's default model, by name.
     * @param toolRepositories The repositories a build tool reaches.
     * @param extensions The block kept for each ecosystem adapter, by its name.
     */
    public ProjectFile(
            @Nullable ProjectInfo project,
            @Nullable Image image,
            @Nullable BuildSources build,
            @Nullable Egress egress,
            @Nullable Limits limits,
            @Nullable Mail mail,
            @Nullable Builds builds,
            Map<String, Parameter> parameters,
            @Nullable BranchScheme branch,
            Map<String, String> credentials,
            Map<String, RepositoryEntry> repositories,
            @Nullable String baseBranch,
            @Nullable Mcp mcp,
            Map<String, ProviderEntry> providers,
            java.util.List<ToolRepositoryEntry> toolRepositories,
            Map<String, Object> extensions) {
        this(
                project,
                image,
                build,
                egress,
                limits,
                mail,
                builds,
                parameters,
                branch,
                credentials,
                repositories,
                baseBranch,
                mcp,
                providers,
                toolRepositories,
                extensions,
                null);
    }

    /**
     * Constructor for a file that names no provider's model.
     *
     * @param project Who the project is.
     * @param image What a task's container is built from.
     * @param build What the image build fetches from.
     * @param egress What a task may reach.
     * @param limits What one task may consume.
     * @param mail Whom its tasks may write to.
     * @param builds Where its builds are read.
     * @param parameters The values a task may be started with.
     * @param branch How a task's branch is named.
     * @param credentials Vault entries to their destinations.
     * @param repositories The repositories by name.
     * @param baseBranch The branch a task starts from by default.
     * @param mcp What a task may do through its MCP endpoint, or {@code null}.
     */
    public ProjectFile(
            @Nullable ProjectInfo project,
            @Nullable Image image,
            @Nullable BuildSources build,
            @Nullable Egress egress,
            @Nullable Limits limits,
            @Nullable Mail mail,
            @Nullable Builds builds,
            Map<String, Parameter> parameters,
            @Nullable BranchScheme branch,
            Map<String, String> credentials,
            Map<String, RepositoryEntry> repositories,
            @Nullable String baseBranch,
            @Nullable Mcp mcp) {
        this(
                project,
                image,
                build,
                egress,
                limits,
                mail,
                builds,
                parameters,
                branch,
                credentials,
                repositories,
                baseBranch,
                mcp,
                Map.of());
    }

    /**
     * Constructor for a file that allows no task an MCP endpoint.
     *
     * @param project Who the project is.
     * @param image What a task's container is built from.
     * @param build What the image build fetches from.
     * @param egress What a task may reach.
     * @param limits What one task may consume.
     * @param mail Whom its tasks may write to.
     * @param builds Where its builds are read.
     * @param parameters The values a task may be started with.
     * @param branch How a task's branch is named.
     * @param credentials Vault entries to their destinations.
     * @param repositories The repositories by name.
     * @param baseBranch The branch a task starts from by default.
     */
    public ProjectFile(
            @Nullable ProjectInfo project,
            @Nullable Image image,
            @Nullable BuildSources build,
            @Nullable Egress egress,
            @Nullable Limits limits,
            @Nullable Mail mail,
            @Nullable Builds builds,
            Map<String, Parameter> parameters,
            @Nullable BranchScheme branch,
            Map<String, String> credentials,
            Map<String, RepositoryEntry> repositories,
            @Nullable String baseBranch) {
        this(
                project,
                image,
                build,
                egress,
                limits,
                mail,
                builds,
                parameters,
                branch,
                credentials,
                repositories,
                baseBranch,
                null);
    }

    /**
     * Constructor for a file that names no starting branch.
     *
     * @param project Who the project is.
     * @param image What a task's container is built from.
     * @param build What the image build fetches from.
     * @param egress What a task may reach.
     * @param limits What one task may consume.
     * @param mail Whom its tasks may write to.
     * @param builds Where its builds are read.
     * @param parameters The values a task may be started with.
     * @param branch How a task's branch is named.
     * @param credentials Vault entries to their destinations.
     * @param repositories The repositories by name.
     */
    public ProjectFile(
            @Nullable ProjectInfo project,
            @Nullable Image image,
            @Nullable BuildSources build,
            @Nullable Egress egress,
            @Nullable Limits limits,
            @Nullable Mail mail,
            @Nullable Builds builds,
            Map<String, Parameter> parameters,
            @Nullable BranchScheme branch,
            Map<String, String> credentials,
            Map<String, RepositoryEntry> repositories) {
        this(
                project,
                image,
                build,
                egress,
                limits,
                mail,
                builds,
                parameters,
                branch,
                credentials,
                repositories,
                null);
    }

    /** An unmodifiable copy that keeps the order written. */
    static <V> Map<String, V> ordered(@Nullable Map<String, V> map) {
        return map == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }
}
