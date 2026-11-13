package org.fuin.porelo.core.project;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import org.fuin.porelo.core.project.file.BuildSources;
import org.fuin.porelo.core.project.file.Image;
import org.fuin.porelo.core.project.file.ProjectFile;
import org.fuin.porelo.core.project.file.ProjectFileReader;
import org.fuin.porelo.core.project.file.ProjectInfo;
import org.fuin.porelo.core.project.file.RepositoryEntry;
import org.jspecify.annotations.Nullable;

/**
 * Reads a {@code project.yml} into a {@link Project}: through {@link ProjectFileReader}, which
 * refuses a file with anything wrong in it, and then resolved into what a task runs under.
 *
 * <p>This reader never writes.
 */
public final class ProjectReader {

    private ProjectReader() {
        throw new UnsupportedOperationException("Utility class");
    }

    private static volatile UnaryOperator<Project> placement = UnaryOperator.identity();

    /**
     * Reads a project definition from a file.
     *
     * @param file Path to {@code project.yml}.
     * @return The project.
     * @throws ProjectException If the file cannot be read or does not describe a usable project.
     */
    public static Project read(Path file) {
        return placement.apply(fromFile(file));
    }

    /**
     * Says where this machine reaches a project's repositories, for every read of a file here. A
     * file judged for its signature is read from text and never comes through this.
     *
     * @param placed What this machine makes of a project as its file says it.
     */
    public static void placedBy(final UnaryOperator<Project> placed) {
        placement = placed;
    }

    private static Project fromFile(Path file) {
        if (!Files.isRegularFile(file)) {
            throw new ProjectException(
                    "No project file at "
                            + file
                            + ". Run 'porelo task run' in a terminal and it will offer to write one.");
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            // Absolute, because the message is read somewhere else than it was typed: "project.yml
            // has no 'project' section" leaves a reader guessing which project.yml, and a person
            // driving this over a socket or from a script has no working directory in front of them
            // at all.
            return read(reader, file.toAbsolutePath().toString());
        } catch (IOException ex) {
            throw new ProjectException("Cannot read " + file, ex);
        }
    }

    /**
     * Reads only the declared name before the project's signature is checked.
     *
     * @param reader The unverified project YAML.
     * @param origin The file named in diagnostics.
     * @return The declared name, whose shape the verifier still checks.
     * @throws ProjectException If the YAML cannot be read or names no project.
     */
    public static String name(final Reader reader, final String origin) {
        final String name = ProjectFileReader.name(reader, origin);
        if (name == null) {
            throw new ProjectException(origin + " names no project");
        }
        return name;
    }

    /**
     * Reads what a project's file allows a task's MCP endpoint.
     *
     * @param reader Source of the YAML.
     * @param origin Name used in error messages.
     * @return The endpoint's methods and limits, or empty when the file has no {@code mcp} section.
     * @throws ProjectException If the file is not usable.
     */
    public static java.util.Optional<McpEndpoint> endpoint(
            final Reader reader, final String origin) {
        return java.util.Optional.ofNullable(
                endpoint(ProjectFileReader.read(reader, origin).mcp()));
    }

    /**
     * Reads what a project's file on this machine allows a task's MCP endpoint.
     *
     * @param file Path to {@code project.yml}.
     * @return The endpoint's methods and limits, or empty when the file has no {@code mcp} section.
     * @throws ProjectException If the file cannot be read or is not usable.
     */
    public static java.util.Optional<McpEndpoint> endpoint(final Path file) {
        return java.util.Optional.ofNullable(endpoint(ProjectFileReader.read(file).mcp()));
    }

    private static @Nullable McpEndpoint endpoint(
            final org.fuin.porelo.core.project.file.@Nullable Mcp mcp) {
        if (mcp == null) {
            return null;
        }
        final Map<String, List<String>> agents = new LinkedHashMap<>();
        Objects.requireNonNull(mcp.agents(), "checked: mcp.agents")
                .forEach((agent, models) -> agents.put(agent, models == null ? List.of() : models));
        final Integer tasks = mcp.tasks();
        final Integer minutes = mcp.minutes();
        return new McpEndpoint(
                Objects.requireNonNull(mcp.methods(), "checked: mcp.methods"),
                agents,
                tasks == null ? McpEndpoint.DEFAULT_TASKS : tasks,
                minutes == null ? McpEndpoint.DEFAULT_MINUTES : minutes);
    }

    /**
     * Returns the keys each section of a project file may hold, as this Porelo knows them.
     *
     * @return Section to its keys, sorted; {@code ""} is the top level, and a name the file chooses
     *     is {@code <repository>}, {@code <peer>} or {@code <parameter>}.
     */
    public static Map<String, List<String>> schema() {
        final Map<String, List<String>> sections = new LinkedHashMap<>();
        ProjectFileReader.sections()
                .forEach(
                        (section, keys) ->
                                sections.put(
                                        ProjectSchema.placeholder(section),
                                        keys.stream().sorted().toList()));
        return sections;
    }

    /**
     * Returns every key a project file may hold, each described - what it means, its type, the
     * values it takes, its default and whether it is required - for an editor a person new to
     * Porelo can fill.
     *
     * @return The keys, in the order a first project file is written in.
     */
    public static List<ProjectKey> keys() {
        return ProjectSchema.keys();
    }

    /**
     * Returns the places where a project file holds what is not Porelo's to know: the project's own
     * names ({@code credentials}, {@code repositories}), each transport's settings ({@code
     * mail.transports.<scheme>}), the agents an MCP endpoint may start ({@code mcp.agents}), by the
     * names this machine's agents go by, and each ecosystem adapter's block ({@code extensions}).
     *
     * @return Their paths.
     */
    public static List<String> openSections() {
        return List.of(
                "credentials",
                "repositories",
                "mail.transports.<scheme>",
                "mcp.agents",
                "extensions");
    }

    /**
     * Reads a project definition from a reader.
     *
     * @param reader Source of the YAML.
     * @param origin Name used in error messages.
     * @return The project.
     * @throws ProjectException If the content does not describe a usable project.
     */
    public static Project read(Reader reader, String origin) {
        return read(reader, origin, SnippetFiles.beside(Path.of(origin)));
    }

    /**
     * Reads a project from YAML whose {@code run_file} lies somewhere other than beside its origin.
     *
     * @param reader Source of the YAML.
     * @param origin Name used in error messages.
     * @param snippets Where a {@code run_file} is read from.
     * @return The project.
     * @throws ProjectException If the content does not describe a usable project.
     */
    public static Project read(Reader reader, String origin, SnippetFiles snippets) {
        return project(ProjectFileReader.read(reader, origin, snippets), snippets, origin);
    }

    /**
     * A project with the files of a repository added to it.
     *
     * @param project The project a task runs under.
     * @param asked What the added files set otherwise than the project, outside the image.
     * @param sources Each value's path to the file that set it.
     * @param removed What the files took away, each with the file that did.
     */
    public record WithRepository(
            Project project,
            List<org.fuin.porelo.core.project.file.RepositorySettings.Asked> asked,
            java.util.Map<String, String> sources,
            List<org.fuin.porelo.core.project.file.RepositorySettings.Removal> removed) {}

    /**
     * Reads a project file on this machine and adds the files of a repository to it, each as {@link
     * org.fuin.porelo.core.project.file.RepositorySettings} merges them.
     *
     * @param reader Source of the project's YAML.
     * @param origin Name used in error messages.
     * @param snippets Where a {@code run_file} is read from.
     * @param layers The files that add to it, earliest first.
     * @return The project, and what the files ask of the machine.
     * @throws ProjectException If what they make does not describe a usable project.
     */
    public static WithRepository read(
            final Reader reader,
            final String origin,
            final SnippetFiles snippets,
            final List<org.fuin.porelo.core.project.file.RepositorySettings.Layer> layers) {
        final org.fuin.porelo.core.project.file.RepositorySettings.Read read =
                org.fuin.porelo.core.project.file.RepositorySettings.read(
                        reader, origin, snippets, layers);
        // A file on this machine, as read(Path) reads it: placed where this machine reaches it.
        return new WithRepository(
                placement.apply(
                        project(read.file(), snippets, origin)
                                .withProviders(providers(read.file(), read.sources()))),
                read.asked(),
                read.sources(),
                read.removed());
    }

    /**
     * Resolves a checked project file into the project a task runs under.
     *
     * @param file The file, checked.
     * @param snippets Where its {@code run_file} is read from.
     * @param origin The file, for messages.
     * @return The project.
     */
    private static Project project(ProjectFile file, SnippetFiles snippets, String origin) {
        final ProjectInfo info = Objects.requireNonNull(file.project(), "checked: project");
        final Image image = Objects.requireNonNull(file.image(), "checked: image");
        final SecurityClass securityClass =
                SecurityClass.parse(
                        Objects.requireNonNull(info.securityClass(), "checked: security_class"));
        final String runFile = image.runFile();
        final BuildSources build = file.build();
        return new Project(
                Objects.requireNonNull(info.name(), "checked: name"),
                text(info.description()),
                securityClass,
                Objects.requireNonNull(image.from(), "checked: from"),
                runFile == null ? image.run() : snippets.read(runFile, origin),
                blankless(info.upstream()),
                limits(file.limits()),
                egress(file.egress()),
                build == null ? null : build.packageSources(),
                mail(file.mail()),
                repositories(file.repositories()),
                file.credentials(),
                builds(file.builds()),
                null,
                parameters(file.parameters()),
                info.nickname(),
                branch(file.branch()),
                review(info.review()),
                image.env(),
                file.baseBranch(),
                providers(file, Map.of()),
                info.shortName(),
                toolRepositories(file),
                extensions(file),
                readingsDirectory(file));
    }

    private static List<ToolRepository> toolRepositories(final ProjectFile file) {
        return file.toolRepositories().stream()
                .map(
                        entry ->
                                new ToolRepository(
                                        Objects.requireNonNull(
                                                entry.ecosystem(), "checked: ecosystem"),
                                        Objects.requireNonNull(entry.name(), "checked: name"),
                                        Objects.requireNonNull(entry.url(), "checked: url"),
                                        entry.credential()))
                .toList();
    }

    private static Map<String, Map<String, Object>> extensions(final ProjectFile file) {
        final Map<String, Map<String, Object>> read = new LinkedHashMap<>();
        file.extensions()
                .forEach(
                        (adapter, block) -> {
                            final Map<String, Object> values = new LinkedHashMap<>();
                            ((Map<?, ?>) block)
                                    .forEach(
                                            (key, value) ->
                                                    values.put(
                                                            String.valueOf(key),
                                                            Objects.requireNonNull(value)));
                            read.put(adapter, java.util.Collections.unmodifiableMap(values));
                        });
        return read;
    }

    /**
     * Returns where a reading writes its findings: the file's directory, else the default. A
     * repository's own file is merged into the file before, so its directory wins.
     *
     * @param file The file, checked.
     */
    private static String readingsDirectory(final ProjectFile file) {
        final org.fuin.porelo.core.project.file.Readings readings = file.readings();
        return readings == null || readings.directory() == null
                ? Project.READINGS_DEFAULT
                : Objects.requireNonNull(readings.directory(), "checked: readings.directory");
    }

    /**
     * Returns each provider's model, with the file that named it: the project's, unless a
     * repository's own file set it. A label goes with its model, so a repository's model takes no
     * label from the project's.
     *
     * @param file The file, checked.
     * @param sources Each value's path to the file that set it; empty for the project's alone.
     */
    private static Map<String, ModelDefault> providers(
            final ProjectFile file, final Map<String, String> sources) {
        final Map<String, ModelDefault> read = new LinkedHashMap<>();
        file.providers()
                .forEach(
                        (provider, entry) -> {
                            final String path = "providers." + provider;
                            final String modelBy =
                                    sources.getOrDefault(path + ".model", PROJECT_SOURCE);
                            final String labelBy =
                                    sources.getOrDefault(path + ".label", PROJECT_SOURCE);
                            // A later file's label would name the model of an earlier one.
                            if (entry.label() != null
                                    && !labelBy.equals(modelBy)
                                    && !PROJECT_SOURCE.equals(labelBy)) {
                                throw new ProjectException(
                                        labelBy
                                                + ": '"
                                                + path
                                                + ".label' is set without '"
                                                + path
                                                + ".model'; a label belongs to the model"
                                                + " written beside it");
                            }
                            read.put(
                                    provider,
                                    new ModelDefault(
                                            Objects.requireNonNull(
                                                    entry.model(), "checked: " + path + ".model"),
                                            entry.label() == null || !labelBy.equals(modelBy)
                                                    ? ""
                                                    : entry.label(),
                                            PROJECT_SOURCE.equals(modelBy)
                                                    ? ModelDefault.PROJECT
                                                    : ModelDefault.REPOSITORY));
                        });
        return read;
    }

    /** Where the project's own values come from in a merge's sources. */
    private static final String PROJECT_SOURCE =
            org.fuin.porelo.core.project.file.RepositorySettings.PROJECT;

    private static Limits limits(org.fuin.porelo.core.project.file.@Nullable Limits limits) {
        if (limits == null) {
            return Limits.defaults();
        }
        final String memory = limits.memory();
        final String cpus = limits.cpus();
        final Integer pids = limits.pids();
        final String handIn = limits.handIn();
        final Integer subagents = limits.subagents();
        // 'none' is how a project opts out on purpose, which reads differently from having
        // forgotten to set one.
        return new Limits(
                memory == null ? Limits.DEFAULT_MEMORY : Limits.NONE.equals(memory) ? null : memory,
                cpus == null ? Limits.DEFAULT_CPUS : Limits.NONE.equals(cpus) ? null : cpus,
                pids == null ? Limits.DEFAULT_PIDS : pids,
                handIn == null ? Limits.DEFAULT_HAND_IN : Limits.bytes(handIn, "limits.hand_in"),
                subagents == null ? 0 : subagents);
    }

    private static Egress egress(org.fuin.porelo.core.project.file.@Nullable Egress egress) {
        return egress == null
                ? Egress.none()
                : new Egress(
                        egress.sets(), egress.domains(), egress.refused(), egress.hostServices());
    }

    private static Mail mail(org.fuin.porelo.core.project.file.@Nullable Mail mail) {
        if (mail == null) {
            return Mail.none();
        }
        final List<Mail.Peer> peers = new ArrayList<>();
        mail.peers()
                .forEach(
                        (name, peer) -> {
                            final Integer perDay = peer.perDay();
                            peers.add(
                                    new Mail.Peer(
                                            name,
                                            text(peer.address()),
                                            // Vouched is the narrower promise - it skips the check
                                            // on the way in - so an omitted trust level is the
                                            // wider one rather than the convenient one.
                                            peer.trust() == null
                                                    ? Mail.Peer.EXTERNAL
                                                    : text(peer.trust()),
                                            perDay == null ? Mail.Peer.DEFAULT_PER_DAY : perDay,
                                            peer.mode()));
                        });
        final Map<String, Object> transports = new LinkedHashMap<>();
        mail.transports()
                .forEach((scheme, transport) -> transports.put(scheme, transport.settings()));
        final org.fuin.porelo.core.project.file.Mail.Rules rules = mail.rules();
        return new Mail(
                List.copyOf(peers),
                java.util.Collections.unmodifiableMap(transports),
                "reporting".equals(mail.outgoingFilter()),
                rules == null
                        ? Mail.Rules.UNSAID
                        : new Mail.Rules(rules.project(), rules.room(), rules.others()));
    }

    private static List<Repository> repositories(Map<String, RepositoryEntry> entries) {
        final List<Repository> read = new ArrayList<>();
        // A repository names no image, egress or limits: its image is its own file's, and what a
        // task may reach or consume is the project's alone.
        entries.forEach(
                (name, entry) ->
                        read.add(
                                new Repository(
                                        name,
                                        blankless(entry.upstream()),
                                        text(entry.description()),
                                        entry.issues(),
                                        entry.agent(),
                                        entry.nickname(),
                                        branch(entry.branch()),
                                        review(entry.review()),
                                        blankless(entry.central()))));
        return List.copyOf(read);
    }

    private static @Nullable Builds builds(
            org.fuin.porelo.core.project.file.@Nullable Builds builds) {
        if (builds == null) {
            return null;
        }
        return new Builds(
                text(builds.forge()),
                text(builds.credential()),
                text(builds.api()),
                builds.logs() == null ? Builds.FAILURE : text(builds.logs()));
    }

    private static Parameters parameters(
            Map<String, org.fuin.porelo.core.project.file.Parameter> declared) {
        final Map<String, Parameter> read = new LinkedHashMap<>();
        declared.forEach(
                (name, one) -> {
                    final String env = one.env();
                    read.put(
                            name,
                            new Parameter(
                                    name,
                                    text(one.description()),
                                    one.values(),
                                    one.pattern(),
                                    one.defaultValue(),
                                    Boolean.TRUE.equals(one.required()),
                                    env == null
                                            ? Parameter.defaultEnv(name)
                                            : Parameter.NO_ENV.equals(env) ? null : env));
                });
        return new Parameters(read);
    }

    private static @Nullable BranchScheme branch(
            org.fuin.porelo.core.project.file.@Nullable BranchScheme branch) {
        if (branch == null) {
            return null;
        }
        final String template = branch.template();
        final String pattern = branch.pattern();
        return new BranchScheme(
                template == null ? BranchScheme.DEFAULT.template() : template,
                pattern == null ? BranchScheme.DEFAULT.pattern() : pattern);
    }

    private static String review(@Nullable String review) {
        return review == null ? Repository.REVIEW_REQUIRED : review;
    }

    /**
     * Tells whether a central address is one a row with this upstream may name: the host's forge
     * names a repository after its central one, so the two must end alike.
     *
     * @param central The central address.
     * @param upstream The upstream's address, or empty for none.
     * @return Whether it fits.
     */
    public static boolean centralFits(final String central, final String upstream) {
        return upstream.isEmpty() || lastName(upstream).equals(lastName(central));
    }

    private static String lastName(String address) {
        final String bare = address.replaceFirst("/+$", "").replaceFirst("\\.git$", "");
        return bare.substring(Math.max(bare.lastIndexOf('/'), bare.lastIndexOf(':')) + 1);
    }

    private static @Nullable String blankless(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String text(@Nullable String value) {
        return value == null ? "" : value;
    }
}
