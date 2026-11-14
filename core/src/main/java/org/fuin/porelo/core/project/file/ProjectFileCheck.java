package org.fuin.porelo.core.project.file;

import java.net.URI;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.Repository;
import org.fuin.porelo.core.project.SecurityClass;
import org.fuin.porelo.core.project.SnippetFiles;
import org.fuin.porelo.core.project.TaskVariables;
import org.fuin.porelo.core.project.ToolRepository;
import org.jspecify.annotations.Nullable;

/**
 * The one check of a file's model: whether every required field is filled and every value is one
 * Porelo can use. It returns every fault, each at its key, and throws nothing.
 */
public final class ProjectFileCheck {

    /**
     * The most a block under {@code extensions} may hold as compact JSON: what the ecosystem
     * contract carries to an adapter.
     */
    public static final int EXTENSION_BYTES = 16 * 1024;

    /** The deepest a block under {@code extensions} may nest, counting the block as the first. */
    public static final int EXTENSION_DEPTH = 8;

    /** What a project's name looks like. */
    public static final String PROJECT_NAME = "[a-z0-9][a-z0-9-]{0,62}";

    /** What a task's name looks like. */
    private static final String TASK_NAME = "[a-z0-9]([a-z0-9-]*[a-z0-9])?";

    /** What a repository's name looks like: it becomes a directory and part of an image's name. */
    public static final String REPOSITORY_NAME = "(?=.{1,63}$)[a-z0-9]+(?:(?:-+|\\.)[a-z0-9]+)*";

    /** What an agent's name looks like. */
    private static final String AGENT = "[a-z0-9]+(?:-[a-z0-9]+)*";

    /** What a varlink method's name looks like, without its interface. */
    private static final Pattern METHOD = Pattern.compile("[A-Z][A-Za-z0-9]*");

    /** What an issue prefix looks like. */
    private static final String ISSUES = "[A-Z]+";

    /** A merge needs another person's approval, or the author may merge their own. */
    private static final List<String> REVIEWS = List.of("required", "optional");

    /** How far a repository can be built on, from just hatched to kept only for those on it. */
    private static final List<String> MATURITIES =
            List.of("crazy-chicken", "early-bird", "stable", "deprecated");

    /** Every mode a message rule takes. */
    private static final List<String> MODES = List.of("allow", "deny", "off", "prompt");

    /** What a variable's name looks like. */
    private static final String VARIABLE = "[A-Za-z_][A-Za-z0-9_]{0,63}";

    private static final Pattern DOMAIN =
            Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+");

    private static final Pattern SET = Pattern.compile("[a-z0-9][a-z0-9-]*");

    private static final Pattern SIZE = Pattern.compile("(\\d+)([kKmMgG]?)");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-z0-9][a-z0-9-]*)}");

    private static final String PEOPLE = "people";

    private static final String PERSON = "person";

    /** A peer's name: one of the plain names, or a task of another project as its principal. */
    private static final Pattern PEER =
            Pattern.compile(
                    "[a-zA-Z0-9][a-zA-Z0-9_.@-]*|task:(?=[a-z0-9:-]{3,57}\\z)"
                            + PROJECT_NAME
                            + ":(?!(?:"
                            + PEOPLE
                            + "|"
                            + PERSON
                            + "|[0-9]+)\\z|porelo-)"
                            + TASK_NAME);

    private static final Pattern ADDRESS = Pattern.compile("([a-z0-9][a-z0-9-]*):(.+)");

    private static final Pattern CONVERSATION = Pattern.compile("[a-z0-9][a-z0-9-]*:");

    /** The instructions {@code run} may hold: what runs in the image, nothing that shapes it. */
    private static final Set<String> RUN_INSTRUCTIONS = Set.of("RUN", "ARG", "ENV");

    /**
     * The one {@code COPY} {@code run} takes: from an image pinned by its digest, never from the
     * repository, so what it copies is fixed before the line is written.
     */
    private static final Pattern PINNED_FROM =
            Pattern.compile("--from=[a-z0-9][a-z0-9._/:-]*@sha256:[0-9a-f]{64}");

    /** The most of a value a message shows. */
    private static final int SHOWN = 80;

    /** A quoted string that does not name a here-document's end, as {@code echo '<<EOF'} does. */
    private static final Pattern QUOTED = Pattern.compile("(?<!<<)(?<!<<-)(['\"])(?:(?!\\1).)*\\1");

    private static final Pattern HEREDOC = Pattern.compile("<<-?\\s*[\"']?([A-Za-z_][\\w]*)[\"']?");

    private final List<Fault> faults = new ArrayList<>();
    private final @Nullable SnippetFiles snippets;

    private ProjectFileCheck(final @Nullable SnippetFiles snippets) {
        this.snippets = snippets;
    }

    /**
     * Checks a project file.
     *
     * @param file The model.
     * @param snippets Where a {@code run_file} is read from, or {@code null} to check only how it
     *     is named.
     * @return Every fault; empty when the file is usable.
     */
    public static List<Fault> check(final ProjectFile file, final @Nullable SnippetFiles snippets) {
        final ProjectFileCheck check = new ProjectFileCheck(snippets);
        check.project(file);
        return List.copyOf(check.faults);
    }

    /**
     * Checks a repository's file.
     *
     * @param file The model.
     * @param snippets Where a {@code run_file} is read from, or {@code null} to check only how it
     *     is named.
     * @return Every fault; empty when the file is usable.
     */
    public static List<Fault> check(
            final RepositoryFile file, final @Nullable SnippetFiles snippets) {
        return check(file, snippets, true);
    }

    /**
     * Checks a repository's file.
     *
     * @param file The file.
     * @param snippets Where a {@code run_file} is read from, or {@code null}.
     * @param imageRequired Whether the file is there for its image alone, and so must name one.
     * @return Every fault, in order.
     */
    static List<Fault> check(
            final RepositoryFile file,
            final @Nullable SnippetFiles snippets,
            final boolean imageRequired) {
        final ProjectFileCheck check = new ProjectFileCheck(snippets);
        if (file.image() == null) {
            if (!imageRequired) {
                return List.of();
            }
            check.fault("image", "'image' is required");
        } else {
            // It adds to the project's image, so the base may stay the project's.
            check.image(file.image(), "image", false);
        }
        return List.copyOf(check.faults);
    }

    /**
     * Holds the readings directory inside the repository: a reading writes its findings there, so a
     * path that leaves it would write where no review of the repository looks.
     */
    private void readings(final Readings readings) {
        final String directory = readings.directory();
        if (directory == null) {
            return;
        }
        final String path = "readings.directory";
        if (directory.isBlank()) {
            fault(path, "'" + path + "' is empty; leave it out for the default 'readings'");
        } else if (directory.startsWith("/")) {
            fault(
                    path,
                    "'"
                            + path
                            + "' is relative to the repository's root, not '"
                            + shown(directory)
                            + "'");
        } else if (java.util.Arrays.asList(directory.split("/", -1)).contains("..")) {
            fault(path, "'" + path + "' leaves the repository: '" + shown(directory) + "'");
        } else if (directory.chars().anyMatch(Character::isISOControl)) {
            fault(path, "'" + path + "' holds a control character: '" + shown(directory) + "'");
        }
    }

    private void fault(final String path, final String message) {
        if (faults.size() > Binding.MOST) {
            return;
        }
        faults.add(new Fault(path, message));
    }

    private void project(final ProjectFile file) {
        final ProjectInfo info = file.project();
        if (info == null) {
            fault("project", "'project' is required");
        }
        final String name = info == null ? null : info.name();
        final SecurityClass securityClass = info == null ? null : projectInfo(info);
        final String label = name == null ? "" : name;
        if (file.image() == null) {
            fault("image", "'image' is required");
        } else {
            image(file.image(), "image");
        }
        if (file.build() != null) {
            build(file.build(), label);
        }
        if (file.egress() != null) {
            egress(file.egress(), label, securityClass);
        }
        if (file.limits() != null) {
            limits(file.limits());
        }
        if (file.mail() != null) {
            mail(file.mail());
        }
        if (file.builds() != null) {
            builds(file.builds(), securityClass);
        }
        parameters(file.parameters());
        if (file.branch() != null) {
            branch(file.branch(), "branch");
        }
        if (file.baseBranch() != null
                && !org.fuin.porelo.core.project.BranchScheme.refFormat(file.baseBranch())) {
            fault(
                    "base_branch",
                    "'base_branch' names no branch: '" + shown(file.baseBranch()) + "'");
        }
        if (file.readings() != null) {
            readings(file.readings());
        }
        credentials(file.credentials(), label, securityClass);
        repositories(file, label, securityClass);
        if (file.mcp() != null) {
            mcp(file.mcp());
        }
        providers(file.providers());
        toolRepositories(file, label, securityClass);
        extensions(file.extensions());
    }

    /**
     * Checks {@code tool_repositories}: each entry's adapter, name and URL, and that the project's
     * egress names its host, since what a task reaches for it follows from these alone.
     */
    private void toolRepositories(
            final ProjectFile file,
            final String label,
            final @Nullable SecurityClass securityClass) {
        if (file.toolRepositories().isEmpty()) {
            return;
        }
        if (securityClass == SecurityClass.OFFLINE) {
            fault(
                    "tool_repositories",
                    "Project '"
                            + label
                            + "' is offline, so it declares no tool repositories: its tasks reach"
                            + " none. Remove 'tool_repositories' or raise the security class.");
            return;
        }
        final Set<String> named = new java.util.HashSet<>();
        for (int at = 0; at < file.toolRepositories().size(); at++) {
            final ToolRepositoryEntry entry = file.toolRepositories().get(at);
            final String path = Binding.entry("tool_repositories", at);
            final String ecosystem = entry.ecosystem();
            if (ecosystem == null) {
                fault(
                        path + ".ecosystem",
                        "'"
                                + path
                                + ".ecosystem' is required: the adapter that writes the tool's"
                                + " settings, as 'porelo ecosystems' lists it");
            } else if (!ecosystem.matches(ToolRepository.ECOSYSTEM_PATTERN)) {
                fault(
                        path + ".ecosystem",
                        "'" + path + ".ecosystem' names no adapter: '" + shown(ecosystem) + "'");
            }
            final String name = entry.name();
            if (name == null) {
                fault(path + ".name", "'" + path + ".name' is required");
            } else if (!name.matches(ToolRepository.NAME_PATTERN)) {
                fault(
                        path + ".name",
                        "'"
                                + path
                                + ".name' is letters, digits, '_' and '-', at most 64 of them, not '"
                                + shown(name)
                                + "': a dot would split the key that names it in an adapter's"
                                + " block");
            } else if (!named.add(name)) {
                fault(
                        path + ".name",
                        "'tool_repositories' names the repository '" + name + "' twice");
            }
            final String url = entry.url();
            if (url == null) {
                fault(path + ".url", "'" + path + ".url' is required");
                continue;
            }
            final String credential = entry.credential();
            if (credential != null) {
                // Read through the task's repository broker, from the host: the task never reaches
                // it, so its host needs no egress line, and the broker holds requests to the
                // prefix.
                if (!credential.matches(ToolRepository.CREDENTIAL_PATTERN)) {
                    fault(
                            path + ".credential",
                            "'"
                                    + path
                                    + ".credential' names no vault entry: lower-case letters,"
                                    + " digits, '_' and '-', or two such names joined by '/', not '"
                                    + shown(credential)
                                    + "'");
                }
                if (!ToolRepository.isPrefix(url)) {
                    fault(
                            path + ".url",
                            "'"
                                    + path
                                    + ".url' of a repository with a credential must be https,"
                                    + " without user information, with no query and no fragment,"
                                    + " and with no '.' or '..' in its path, not '"
                                    + shown(url)
                                    + "'");
                }
                continue;
            }
            final String host = ToolRepository.host(url);
            if (host == null) {
                fault(
                        path + ".url",
                        "'"
                                + path
                                + ".url' must be an https:// address, or http:// on a literal"
                                + " loopback address (127.0.0.1, [::1]), without user information;"
                                + " the address is not repeated, as it may hold a secret");
                continue;
            }
            if (!ToolRepository.isLoopback(host) && refusedBy(host, file.egress())) {
                fault(
                        path + ".url",
                        "'"
                                + path
                                + ".url' reaches '"
                                + shown(host)
                                + "', which 'egress.refused' names; take it out of one of them");
            } else if (!ToolRepository.isLoopback(host) && !reached(host, file.egress())) {
                fault(
                        path + ".url",
                        "'"
                                + path
                                + ".url' reaches '"
                                + shown(host)
                                + "', which 'egress' does not name; add \""
                                + shown(host)
                                + "\" to 'egress.domains'");
            }
        }
    }

    /** Whether the egress names a host: as a domain or under one, or as a service on this host. */
    private static boolean reached(final String host, final @Nullable Egress egress) {
        if (egress == null) {
            return false;
        }
        return egress.hostServices().contains(host)
                || egress.domains().stream().anyMatch(domain -> under(host, domain));
    }

    /** Whether the egress refuses a host, as a domain or under one. */
    private static boolean refusedBy(final String host, final @Nullable Egress egress) {
        return egress != null && egress.refused().stream().anyMatch(domain -> under(host, domain));
    }

    /** Whether a host is a domain or under it; both are lower case, as the file is checked. */
    private static boolean under(final String host, final String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    /**
     * Checks {@code extensions}: each block is a mapping the ecosystem contract carries, by size
     * and depth. What it says is the adapter's to judge.
     */
    private void extensions(final Map<String, Object> extensions) {
        extensions.forEach(
                (adapter, block) -> {
                    final String path = Binding.join("extensions", adapter);
                    if (!adapter.matches(ToolRepository.ECOSYSTEM_PATTERN)) {
                        fault(path, "'" + path + "' names no adapter: '" + shown(adapter) + "'");
                    }
                    if (!(block instanceof Map<?, ?> map)) {
                        fault(
                                path,
                                "'"
                                        + path
                                        + "' must be a mapping, not "
                                        + (block instanceof List<?> ? "a list" : "a single value"));
                        return;
                    }
                    if (depth(map) > EXTENSION_DEPTH) {
                        fault(
                                path,
                                "'"
                                        + path
                                        + "' nests deeper than "
                                        + EXTENSION_DEPTH
                                        + " levels, which an adapter is not given");
                        return;
                    }
                    if (blankKey(map)) {
                        fault(path, "'" + path + "' holds a blank key");
                        return;
                    }
                    final int bytes =
                            org.fuin.porelo.wire.Json.write(map)
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                                    .length;
                    if (bytes > EXTENSION_BYTES) {
                        fault(
                                path,
                                "'"
                                        + path
                                        + "' is "
                                        + bytes
                                        + " bytes as JSON, and one may have "
                                        + EXTENSION_BYTES);
                    }
                });
    }

    /** How deep a value nests: a map or a list is one level more than what it holds. */
    private static int depth(final Object value) {
        if (value instanceof Map<?, ?> map) {
            return 1 + map.values().stream().mapToInt(ProjectFileCheck::depth).max().orElse(0);
        }
        if (value instanceof List<?> list) {
            return 1 + list.stream().mapToInt(ProjectFileCheck::depth).max().orElse(0);
        }
        return 0;
    }

    private static boolean blankKey(final Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.keySet().stream().anyMatch(key -> String.valueOf(key).isBlank())
                    || map.values().stream().anyMatch(ProjectFileCheck::blankKey);
        }
        return value instanceof List<?> list && list.stream().anyMatch(ProjectFileCheck::blankKey);
    }

    /**
     * Checks the {@code providers} section's shape. Whether this machine knows each provider is
     * asked where the machine's providers are: at a start and by {@code project check}.
     */
    private void providers(final Map<String, ProviderEntry> providers) {
        providers.forEach(
                (provider, entry) -> {
                    final String path = Binding.join("providers", provider);
                    if (!provider.matches(
                            org.fuin.porelo.core.project.ModelDefault.PROVIDER_NAME)) {
                        fault(path, "'" + path + "' is not a provider's name");
                    }
                    final String model = entry.model();
                    if (model == null) {
                        fault(
                                path + ".model",
                                "'" + path + ".model' is required: the model a task on it runs on");
                    } else if (!org.fuin.porelo.core.project.ModelDefault.isWord(model)) {
                        fault(
                                path + ".model",
                                "'"
                                        + path
                                        + ".model' is one word, a model's id, not '"
                                        + shown(model)
                                        + "'");
                    }
                    final String label = entry.label();
                    if (label != null && !org.fuin.porelo.core.project.ModelDefault.isLine(label)) {
                        fault(
                                path + ".label",
                                "'"
                                        + path
                                        + ".label' is one line of text, not '"
                                        + shown(label)
                                        + "'");
                    }
                });
    }

    /**
     * Checks the {@code mcp} section's shape. Whether each method is one the daemon has, and may
     * offer, is the daemon's to say: only it knows its interface.
     */
    private void mcp(final Mcp mcp) {
        final List<String> methods = mcp.methods();
        if (methods == null || methods.isEmpty()) {
            fault(
                    "mcp.methods",
                    "'mcp.methods' is required: the methods a task's endpoint carries, such as"
                            + " [\"List\", \"Start\", \"Stop\"]");
        } else {
            for (final String method : methods) {
                if (!METHOD.matcher(method).matches()) {
                    fault(
                            "mcp.methods",
                            "'mcp.methods' names a method by its name without the interface,"
                                    + " such as 'Start', not '"
                                    + shown(method)
                                    + "'");
                }
            }
        }
        final Map<String, @Nullable List<String>> agents = mcp.agents();
        if (agents == null || agents.isEmpty()) {
            // Porelo names no agent itself, so no default stands in for the file's word.
            fault(
                    "mcp.agents",
                    "'mcp.agents' is required: the agents a task started through the endpoint may"
                            + " use, each to the models it may be given");
        } else {
            agents.forEach(
                    (agent, models) -> {
                        final String path = Binding.join("mcp.agents", agent);
                        if (!agent.matches(AGENT)) {
                            fault(path, "'" + path + "' is not an agent's name");
                        }
                        if (models != null && models.isEmpty()) {
                            fault(
                                    path,
                                    "'"
                                            + path
                                            + "' lists no model; name the models it may use, or"
                                            + " leave it empty for any");
                        }
                    });
        }
        if (mcp.tasks() != null && mcp.tasks() < 1) {
            fault(
                    "mcp.tasks",
                    "'mcp.tasks' is at least 1, not "
                            + mcp.tasks()
                            + "; leave out the section to allow no endpoint");
        }
        if (mcp.minutes() != null && mcp.minutes() < 1) {
            fault("mcp.minutes", "'mcp.minutes' is at least 1, not " + mcp.minutes());
        }
    }

    private @Nullable SecurityClass projectInfo(final ProjectInfo info) {
        final String name = info.name();
        if (name == null || name.isBlank()) {
            fault("project.name", "'project.name' is required");
        } else if (!name.matches(PROJECT_NAME)) {
            // The name ends up in image tags, container names and nftables set names, all of which
            // are stricter than YAML.
            fault(
                    "project.name",
                    "Invalid project name '"
                            + shown(name)
                            + "' in 'project.name', expected lower-case letters, digits and"
                            + " hyphens");
        }
        SecurityClass securityClass = null;
        if (info.securityClass() == null) {
            fault("project.security_class", "'project.security_class' is required");
        } else {
            try {
                securityClass = SecurityClass.parse(info.securityClass());
            } catch (final ProjectException ex) {
                fault(
                        "project.security_class",
                        "Unknown security class '"
                                + shown(info.securityClass())
                                + "' in 'project.security_class', expected one of: offline,"
                                + " guarded, online");
            }
        }
        final String label = name == null ? "" : name;
        final String upstream = blankless(info.upstream());
        if (upstream != null) {
            refuseAsOption(upstream, "project.central");
        }
        if (securityClass == SecurityClass.OFFLINE && upstream != null) {
            // An offline project never connects: its repository comes in as a file.
            fault(
                    "project.central",
                    "Project '"
                            + label
                            + "' is offline, so it names no central address: nothing connects out"
                            + " for it, not even the host. Remove 'project.central' and bring the"
                            + " repository in as a file with 'porelo gate restore', or start from a"
                            + " checkout of it on this machine.");
        }
        if (securityClass == SecurityClass.ONLINE && upstream == null) {
            // The agent's remote is the upstream itself, so without one the class means nothing.
            fault(
                    "project.central",
                    "Project '"
                            + label
                            + "' is online, so it needs its central address in 'project.central'");
        }
        review(info.review(), "project.review");
        if (info.secrets() != null
                && !org.fuin.porelo.core.project.Project.SECRETS.contains(info.secrets())) {
            fault(
                    "project.secrets",
                    "'project.secrets' is warn or refuse, not '" + shown(info.secrets()) + "'");
        }
        nickname(info.nickname(), "project.nickname");
        org.fuin.porelo.core.project.ShortName.refusal(info.shortName())
                .ifPresent(reason -> fault("project.short", "project.short: " + reason));
        return securityClass;
    }

    private void image(final Image image, final String path) {
        image(image, path, true);
    }

    private void image(final Image image, final String path, final boolean baseRequired) {
        final String from = image.from();
        if (from == null || from.isBlank()) {
            if (baseRequired || from != null) {
                fault(path + ".from", "'" + path + ".from' is required");
            }
        } else {
            refuseAsOption(from, path + ".from");
        }
        image.env()
                .forEach(
                        (name, value) -> {
                            final String where = Binding.join(path + ".env", name);
                            if (!name.matches(VARIABLE)) {
                                fault(where, "'" + where + "' is no variable name");
                            } else if (TaskVariables.reserved(name)) {
                                // Porelo sets these in every task; an image replacing one would
                                // decide where the task's tools and its own token are found.
                                final String fixed = TaskVariables.value(name);
                                fault(
                                        where,
                                        "'"
                                                + where
                                                + "' is set by Porelo"
                                                + (fixed == null
                                                        ? " for each task; remove the line, and give"
                                                                + " a value the repository needs a"
                                                                + " name of its own"
                                                        : " to "
                                                                + fixed
                                                                + " in every task; whoever commits"
                                                                + " the file must remove the line -"
                                                                + " the task has that value without"
                                                                + " it"));
                            }
                            if (controls(value)) {
                                // One variable is one ENV line; a line break would write another.
                                fault(where, "'" + where + "' holds a control character");
                            }
                        });
        if (image.run() != null && image.runFile() != null) {
            fault(
                    path + ".run",
                    "'" + path + ".run' and '" + path + ".run_file' are mutually exclusive");
        }
        if (image.run() != null) {
            instructions(image.run(), path + ".run");
        }
        if (image.runFile() != null) {
            runFile(image.runFile(), path + ".run_file");
        }
    }

    private void runFile(final String named, final String path) {
        // From the repository and nowhere else: an absolute path or '../' read any file the
        // person can into the image the agent runs in.
        if (!insideRepository(named)) {
            fault(path, "'" + path + "' must name a file of the repository, not " + shown(named));
            return;
        }
        if (snippets == null) {
            return;
        }
        final String content;
        try {
            content = snippets.read(named, path);
        } catch (final ProjectException ex) {
            fault(path, "'" + path + "' cannot be used: " + ex.getMessage());
            return;
        }
        instructions(content, path);
    }

    /**
     * Refuses every instruction but {@code RUN}, {@code ARG} and {@code ENV}: a {@code FROM},
     * {@code COPY}, {@code USER} or {@code ENTRYPOINT} would change what the image is rather than
     * what is in it. The one {@code COPY} taken is from an image pinned by its digest.
     */
    private void instructions(final String run, final String path) {
        final List<String> lines = run.lines().toList();
        boolean continued = false;
        String heredoc = null;
        for (int index = 0; index < lines.size(); index++) {
            final String line = lines.get(index);
            if (heredoc != null) {
                if (line.strip().equals(heredoc)) {
                    heredoc = null;
                }
                continue;
            }
            final String stripped = line.strip();
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                continue;
            }
            if (!continued) {
                final String instruction = stripped.split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
                if ("COPY".equals(instruction)) {
                    if (!pinnedCopy(lines, index)) {
                        fault(
                                path,
                                "'"
                                        + path
                                        + "' line "
                                        + (index + 1)
                                        + ": COPY is not allowed but as one COPY"
                                        + " --from=<image>@sha256:<digest>");
                    }
                } else if (!RUN_INSTRUCTIONS.contains(instruction)) {
                    fault(
                            path,
                            "'"
                                    + path
                                    + "' line "
                                    + (index + 1)
                                    + ": "
                                    + shown(instruction)
                                    + " is not allowed; only RUN, ARG and ENV");
                }
                final Matcher here = HEREDOC.matcher(QUOTED.matcher(stripped).replaceAll(""));
                if ("RUN".equals(instruction) && here.find()) {
                    heredoc = here.group(1);
                }
            }
            continued = stripped.endsWith("\\");
        }
    }

    /**
     * Tells whether the {@code COPY} starting at a line, with the lines it continues on, copies
     * from one image pinned by its digest and from nothing else.
     */
    private static boolean pinnedCopy(final List<String> lines, final int start) {
        final StringBuilder whole = new StringBuilder();
        for (int index = start; index < lines.size(); index++) {
            final String stripped = lines.get(index).strip();
            if (!stripped.endsWith("\\")) {
                whole.append(stripped);
                break;
            }
            whole.append(stripped, 0, stripped.length() - 1).append(' ');
        }
        final String copy = whole.toString();
        if (copy.contains("<<")) {
            // A here-document is text of the line, not of the pinned image.
            return false;
        }
        final List<String> from =
                List.of(copy.split("\\s+")).stream()
                        .filter(word -> word.toLowerCase(Locale.ROOT).startsWith("--from"))
                        .toList();
        return from.size() == 1 && PINNED_FROM.matcher(from.get(0)).matches();
    }

    private void build(final BuildSources build, final String label) {
        final List<String> sources = build.packageSources();
        if (sources == null) {
            return;
        }
        if (sources.isEmpty()) {
            // Not "use the default": a project that named sources and named none would silently
            // keep the base image's own and read as configured.
            fault(
                    "build.package_sources",
                    "Project '"
                            + label
                            + "' declares 'build.package_sources' with nothing in it. Remove the"
                            + " key to use the default.");
        }
        for (final String source : sources) {
            if (!source.startsWith("http://") && !source.startsWith("https://")) {
                fault(
                        "build.package_sources",
                        "'build.package_sources' holds '"
                                + shown(source)
                                + "', which is not an http or https URL");
            }
        }
    }

    private void egress(
            final Egress egress, final String label, final @Nullable SecurityClass securityClass) {
        for (final String set : egress.sets()) {
            if (!SET.matcher(set).matches()) {
                fault(
                        "egress.sets",
                        "Invalid egress set name '"
                                + shown(set)
                                + "' in 'egress.sets', expected lower-case letters, digits and"
                                + " hyphens");
            }
        }
        // Each name becomes a line of the resolver's configuration, so one carrying a line break
        // or a slash would be configuration of its own.
        domains(egress.domains(), "egress.domains", "nexus.corp.example");
        domains(egress.refused(), "egress.refused", "telemetry.example");
        domains(egress.hostServices(), "egress.host_services", "nexus.corp.example");
        for (final String name : egress.hostServices()) {
            if (egress.domains().contains(name)) {
                fault(
                        "egress.host_services",
                        "Name '"
                                + shown(name)
                                + "' appears in both 'egress.domains' and"
                                + " 'egress.host_services'; declare it in only one key");
            }
        }
        if (securityClass == SecurityClass.OFFLINE
                && (!egress.sets().isEmpty()
                        || !egress.domains().isEmpty()
                        || !egress.refused().isEmpty())) {
            // Said rather than ignored: a dropped declaration would look configured.
            fault(
                    "egress",
                    "Project '"
                            + label
                            + "' is offline, so it can declare no egress beyond this host."
                            + " Remove 'egress.sets', 'egress.domains' and 'egress.refused' or"
                            + " raise the security class; 'egress.host_services' is allowed.");
        }
    }

    private void domains(final List<String> names, final String path, final String example) {
        for (final String name : names) {
            if (name.length() > 253 || !DOMAIN.matcher(name).matches()) {
                fault(
                        path,
                        "Invalid name '"
                                + shown(name)
                                + "' in '"
                                + path
                                + "', expected a host name such as "
                                + example);
            }
        }
    }

    private void limits(final Limits limits) {
        if (limits.pids() != null && limits.pids() < 1) {
            fault(
                    "limits.pids",
                    "'limits.pids' is "
                            + limits.pids()
                            + ", a process limit that leaves nothing to"
                            + " run");
        }
        if (limits.handIn() != null) {
            final Long bytes = bytes(limits.handIn(), "limits.hand_in");
            if (bytes != null && bytes < 1) {
                fault(
                        "limits.hand_in",
                        "'limits.hand_in' is " + bytes + " bytes, which lets no file in");
            }
        }
        if (limits.subagents() != null && limits.subagents() < 0) {
            fault(
                    "limits.subagents",
                    "'limits.subagents' must be a whole number of 0 or more, not '"
                            + limits.subagents()
                            + "'");
        }
        if (limits.passOn() != null) {
            try {
                org.fuin.porelo.core.project.Limits.passOn(limits.passOn(), "limits.pass_on");
            } catch (org.fuin.porelo.core.project.ProjectException ex) {
                // There is no 'none': the point is that a push ends.
                fault(
                        "limits.pass_on",
                        "'limits.pass_on' must be a time from 10s to 1h, such as 2m, not '"
                                + shown(limits.passOn())
                                + "'");
            }
        }
        if (limits.ideas() != null
                && (limits.ideas() < 0
                        || limits.ideas() > org.fuin.porelo.core.project.Limits.MOST_IDEAS)) {
            fault(
                    "limits.ideas",
                    "'limits.ideas' must be a whole number of 0 or more, at most "
                            + org.fuin.porelo.core.project.Limits.MOST_IDEAS
                            + ", not '"
                            + limits.ideas()
                            + "'");
        }
    }

    private @Nullable Long bytes(final String size, final String path) {
        final Matcher matcher = SIZE.matcher(size.strip());
        if (!matcher.matches()) {
            fault(path, "'" + path + "' must be a size such as 64m, not '" + shown(size) + "'");
            return null;
        }
        final long unit =
                switch (matcher.group(2).toLowerCase(Locale.ROOT)) {
                    case "k" -> 1024L;
                    case "m" -> 1024L * 1024;
                    case "g" -> 1024L * 1024 * 1024;
                    default -> 1L;
                };
        try {
            return Math.multiplyExact(Long.parseLong(matcher.group(1)), unit);
        } catch (final NumberFormatException | ArithmeticException ex) {
            fault(path, "'" + path + "' is too large: '" + shown(size) + "'");
            return null;
        }
    }

    private void mail(final Mail mail) {
        mail.peers()
                .forEach(
                        (name, peer) -> {
                            final String path = Binding.join("mail.peers", name);
                            if (!PEER.matcher(name).matches()) {
                                fault(
                                        path,
                                        "'"
                                                + path
                                                + "': a peer name is letters, digits, dash,"
                                                + " underscore, dot and at sign, starting with a"
                                                + " letter or digit, or a task of another project"
                                                + " as 'task:<project>:<task>'");
                            }
                            final String address = peer.address();
                            if (address == null) {
                                fault(path + ".address", "'" + path + ".address' is required");
                            } else if (!ADDRESS.matcher(address).matches()
                                    && !CONVERSATION.matcher(address).matches()) {
                                fault(
                                        path + ".address",
                                        "'"
                                                + path
                                                + ".address' is '<transport>:<address>', not '"
                                                + shown(address)
                                                + "'");
                            }
                            word(peer.trust(), List.of("vouched", "external"), path + ".trust");
                            if (peer.perDay() != null && peer.perDay() < 1) {
                                fault(
                                        path + ".per_day",
                                        "'"
                                                + path
                                                + ".per_day' is at least 1, not "
                                                + peer.perDay());
                            }
                            word(peer.mode(), MODES, path + ".mode");
                        });
        final Mail.Rules rules = mail.rules();
        if (rules != null) {
            word(rules.project(), MODES, "mail.rules.project");
            word(rules.room(), MODES, "mail.rules.room");
            word(rules.others(), MODES, "mail.rules.others");
        }
        word(mail.outgoingFilter(), List.of("blocking", "reporting"), "mail.outgoing_filter");
        for (final String scheme : mail.transports().keySet()) {
            // Named as a peer's transport is: it becomes a directory of this machine's state, and
            // clearing a project deletes files under it.
            if (!scheme.matches("[a-z0-9][a-z0-9-]*")) {
                fault(
                        Binding.join("mail.transports", scheme),
                        "'mail.transports."
                                + shown(scheme)
                                + "' is not a transport's name; expected lower-case letters,"
                                + " digits and hyphens");
            }
        }
    }

    private void builds(final Builds builds, final @Nullable SecurityClass securityClass) {
        if (securityClass != null && securityClass != SecurityClass.ONLINE) {
            fault(
                    "builds",
                    "'builds' is for online projects only - in guarded the forge builds nothing"
                            + " until a person approves the work at the gate, and offline has no"
                            + " build");
        }
        final String forge = builds.forge();
        if (forge == null || !forge.matches("[a-z][a-z0-9-]{0,62}")) {
            fault(
                    "builds.forge",
                    "'builds.forge' must name a build reader, such as the one its package"
                            + " installs, not '"
                            + shown(Objects.requireNonNullElse(forge, ""))
                            + "'");
        }
        if (builds.credential() == null || builds.credential().isBlank()) {
            fault(
                    "builds.credential",
                    "'builds.credential' must name the vault entry that reads the builds");
        }
        word(builds.logs(), List.of("failure", "all"), "builds.logs");
        final String api = builds.api();
        if (api != null && !api.isEmpty() && !permittedApi(api)) {
            fault(
                    "builds.api",
                    "'builds.api' must be an https:// address, or http:// on this machine's"
                            + " loopback (127.0.0.1, ::1, localhost), not '"
                            + shown(api)
                            + "'");
        }
    }

    private static boolean permittedApi(final String api) {
        final URI uri;
        try {
            uri = URI.create(api);
        } catch (final IllegalArgumentException ex) {
            return false;
        }
        // A host is required for https too: "https:forge.example" names none to trust.
        return ("https".equals(uri.getScheme()) && uri.getHost() != null)
                || ("http".equals(uri.getScheme())
                        && uri.getUserInfo() == null
                        && ("127.0.0.1".equals(uri.getHost())
                                || "[::1]".equals(uri.getHost())
                                || "localhost".equalsIgnoreCase(uri.getHost())));
    }

    private void parameters(final Map<String, Parameter> parameters) {
        final Map<String, String> envs = new LinkedHashMap<>();
        parameters.forEach(
                (name, parameter) -> {
                    final String path = Binding.join("parameters", name);
                    if (!name.matches("[a-z0-9][a-z0-9-]{0,62}")) {
                        fault(
                                path,
                                "'"
                                        + path
                                        + "' is no parameter name: lower-case letters, digits and"
                                        + " hyphens");
                        return;
                    }
                    parameter(parameter, name, path, envs);
                });
    }

    private void parameter(
            final Parameter parameter,
            final String name,
            final String path,
            final Map<String, String> envs) {
        final List<String> values = parameter.values();
        final String pattern = parameter.pattern();
        if (values != null && pattern != null) {
            fault(path, "'" + path + "' names both 'values' and 'pattern'; say one");
        }
        if (values != null && values.isEmpty()) {
            fault(path + ".values", "'" + path + ".values' names no value");
        }
        boolean patternWorks = true;
        if (pattern != null) {
            try {
                Pattern.compile(pattern);
            } catch (final PatternSyntaxException ex) {
                patternWorks = false;
                fault(
                        path + ".pattern",
                        "'" + path + ".pattern' is no regular expression: " + shown(pattern));
            }
        }
        final String env = parameter.env();
        if (env != null
                && !"none".equals(env)
                && (!env.matches(VARIABLE)
                        || TaskVariables.reserved(env) && !env.startsWith("PORELO_PARAM_"))) {
            // Porelo sets these in every task; a value under one of them would decide where the
            // task's tools and its own token are found.
            fault(
                    path + ".env",
                    "'"
                            + path
                            + ".env' cannot be '"
                            + shown(env)
                            + "': a variable name Porelo does not set itself, or 'none'");
        }
        final String variable =
                env == null
                        ? "PORELO_PARAM_" + name.toUpperCase(Locale.ROOT).replace('-', '_')
                        : "none".equals(env) ? null : env;
        if (variable != null) {
            final String other = envs.putIfAbsent(variable, name);
            if (other != null) {
                fault(
                        path + ".env",
                        "'parameters."
                                + other
                                + "' and '"
                                + path
                                + "' both name the variable "
                                + variable);
            }
        }
        final String defaultValue = parameter.defaultValue();
        if (defaultValue != null
                && patternWorks
                && (defaultValue.chars().anyMatch(c -> c < 0x20 || c == 0x7f)
                        || values != null && !values.contains(defaultValue)
                        || pattern != null && !Pattern.matches(pattern, defaultValue))) {
            fault(
                    path + ".default",
                    "'" + path + ".default' is not allowed by its own declaration");
        }
    }

    private void branch(final BranchScheme branch, final String path) {
        final String template = branch.template();
        final String pattern = branch.pattern();
        if (template != null && pattern == null) {
            // The pattern is also what the gate keeps out of the mirror an agent fetches from.
            fault(
                    path,
                    "'"
                            + path
                            + "' names a template and no pattern; name the pattern of the branches"
                            + " the forge accepts too");
        }
        if (template != null) {
            if (template.isBlank()) {
                fault(path + ".template", "'" + path + ".template' is empty");
            } else if (PLACEHOLDER.matcher(template).replaceAll("").matches(".*[{}].*")) {
                fault(
                        path + ".template",
                        "'"
                                + path
                                + ".template' is '"
                                + shown(template)
                                + "', which holds a brace that is not a {placeholder}");
            }
        }
        if (pattern != null) {
            if (pattern.isBlank()) {
                fault(path + ".pattern", "'" + path + ".pattern' is empty");
            } else {
                try {
                    Pattern.compile(pattern);
                } catch (final PatternSyntaxException ex) {
                    fault(
                            path + ".pattern",
                            "'"
                                    + path
                                    + ".pattern' is not a regular expression: "
                                    + ex.getDescription());
                }
            }
        }
    }

    private void credentials(
            final Map<String, String> credentials,
            final String label,
            final @Nullable SecurityClass securityClass) {
        credentials.forEach(
                (entry, destination) -> {
                    if (!org.fuin.porelo.core.project.CredentialName.allowed(entry)
                            || !destination.matches("[a-z0-9][a-z0-9-]{0,30}")) {
                        fault(
                                Binding.join("credentials", entry),
                                "'credentials."
                                        + shown(entry)
                                        + "' for '"
                                        + shown(destination)
                                        + "' - both are names: lower-case letters, digits and"
                                        + " hyphens");
                    }
                });
        for (final List<String> pair :
                org.fuin.porelo.core.project.CredentialName.clashes(credentials.keySet())) {
            fault(
                    Binding.join("credentials", pair.get(1)),
                    "'credentials."
                            + shown(pair.get(0))
                            + "' and 'credentials."
                            + shown(pair.get(1))
                            + "' become one variable in a task, PORELO_TOKEN_"
                            + org.fuin.porelo.core.project.CredentialName.variable(pair.get(0))
                            + "; rename one of them, in the vault too");
        }
        if (securityClass == SecurityClass.OFFLINE && !credentials.isEmpty()) {
            // The broker would reach the destination for the task, and offline reaches nothing.
            fault(
                    "credentials",
                    "Project '"
                            + label
                            + "' is offline, so it can declare no 'credentials'. Remove the"
                            + " section or raise the security class.");
        }
    }

    private void repositories(
            final ProjectFile file,
            final String label,
            final @Nullable SecurityClass securityClass) {
        final Map<String, String> owner = new LinkedHashMap<>();
        final Map<String, String> goesBy = new LinkedHashMap<>();
        final ProjectInfo info = file.project();
        if (info != null && info.name() != null) {
            goesBy.put(info.name(), Repository.goesBy(info.nickname(), info.name()));
        }
        file.repositories()
                .forEach(
                        (name, entry) -> {
                            final String path = Binding.join("repositories", name);
                            repository(name, entry, path, label, securityClass);
                            for (final String prefix : entry.issues()) {
                                // A prefix tells whose an issue is only while one repository
                                // owns it.
                                final String other = owner.putIfAbsent(prefix, name);
                                if (other != null) {
                                    fault(
                                            path + ".issues",
                                            "The issue prefix '"
                                                    + shown(prefix)
                                                    + "' in '"
                                                    + path
                                                    + ".issues' is named by both '"
                                                    + other
                                                    + "' and '"
                                                    + name
                                                    + "'; a prefix belongs to one repository");
                                }
                            }
                            final String goes = Repository.goesBy(entry.nickname(), name);
                            goesBy.forEach((other, its) -> apart(path, other, its, name, goes));
                            goesBy.putIfAbsent(name, goes);
                        });
    }

    /** Refuses two names a reader would take for one; it reports at the later's path. */
    private void apart(
            final String path,
            final String other,
            final String its,
            final String name,
            final String goes) {
        // People do not tell Agent Pi from Agent PI, or one space from two, apart in a room.
        final String first = Repository.compared(its);
        final String second = Repository.compared(goes);
        if (first.equals(second)) {
            fault(
                    path,
                    "'"
                            + other
                            + "' and '"
                            + name
                            + "' in '"
                            + path
                            + "' would both go by '"
                            + shown(goes)
                            + "'; a nickname may not repeat another repository's nickname or name,"
                            + " whatever the case or the spaces");
        } else if (second.startsWith(first + " ") || first.startsWith(second + " ")) {
            // A reader matching a whole phrase from the left would take the shorter for the longer.
            fault(
                    path,
                    "'"
                            + name
                            + "' in '"
                            + path
                            + "' goes by '"
                            + shown(goes)
                            + "' and '"
                            + other
                            + "' by '"
                            + shown(its)
                            + "'; a name may not be another's followed by a space and more");
        }
    }

    private void repository(
            final String name,
            final RepositoryEntry entry,
            final String path,
            final String label,
            final @Nullable SecurityClass securityClass) {
        if (!name.matches(REPOSITORY_NAME)) {
            // It becomes a mirror directory and part of an image's name.
            fault(
                    path,
                    "Invalid repository name '"
                            + shown(name)
                            + "' in '"
                            + path
                            + "', expected lower-case letters, digits, hyphens and dots between"
                            + " them, starting and ending with a letter or digit, at most 63"
                            + " characters");
        }
        if (name.equals(label)) {
            // A project where one name meant two repositories would hand a task the wrong mirror.
            fault(
                    path,
                    "project.name '"
                            + label
                            + "' is also the name of a repository this file names, '"
                            + path
                            + "', and they must differ. For a project of this one repository,"
                            + " write project.name as '"
                            + label
                            + "-project', for example; in a project already in use, rename that"
                            + " repository instead.");
        }
        final String upstream = blankless(entry.upstream());
        if (upstream != null && blankless(entry.central()) == null) {
            refuseAsOption(upstream, path + ".central");
        }
        if (securityClass == SecurityClass.OFFLINE && upstream != null) {
            fault(
                    path + ".central",
                    "Project '"
                            + label
                            + "' is offline, so its repository '"
                            + name
                            + "' names no central address either. Remove '"
                            + path
                            + ".central' and bring it in as a file with 'porelo gate restore'.");
        }
        if (securityClass == SecurityClass.ONLINE && upstream == null) {
            // Online means the agent's own remote is the upstream.
            fault(
                    path + ".central",
                    "Project '"
                            + label
                            + "' is online, so its repository '"
                            + name
                            + "' needs its central address in '"
                            + path
                            + ".central'");
        }
        final String central = blankless(entry.central());
        if (central != null) {
            refuseAsOption(central, path + ".central");
        }
        review(entry.review(), path + ".review");
        if (entry.agent() != null && !entry.agent().matches(AGENT)) {
            fault(
                    path + ".agent",
                    "'"
                            + path
                            + ".agent' is '"
                            + shown(entry.agent())
                            + "'; an agent is named as 'porelo agents' lists it: lower-case"
                            + " letters, digits and hyphens");
        }
        nickname(entry.nickname(), path + ".nickname");
        for (final String prefix : entry.issues()) {
            if (!prefix.matches(ISSUES)) {
                fault(
                        path + ".issues",
                        "'"
                                + path
                                + ".issues' holds '"
                                + shown(prefix)
                                + "'; an issue prefix is one or more capital letters, such as"
                                + " 'B' or 'PI'");
            }
        }
        if (entry.maturity() != null && !MATURITIES.contains(entry.maturity())) {
            fault(
                    path + ".maturity",
                    "'"
                            + path
                            + ".maturity' is one of "
                            + String.join(", ", MATURITIES)
                            + ", not '"
                            + shown(entry.maturity())
                            + "'");
        }
        if (entry.branch() != null) {
            branch(entry.branch(), path + ".branch");
        }
    }

    private void review(final @Nullable String review, final String path) {
        if (review != null && !REVIEWS.contains(review)) {
            fault(path, "'" + path + "' is required or optional, not '" + shown(review) + "'");
        }
    }

    private void nickname(final @Nullable String nickname, final String path) {
        if (nickname != null && !nickname.matches(Repository.NICKNAME_PATTERN)) {
            fault(path, "'" + path + "' is '" + shown(nickname) + "'; " + Repository.NICKNAME_RULE);
        }
    }

    private void word(final @Nullable String value, final List<String> words, final String path) {
        if (value != null && !words.contains(value)) {
            fault(
                    path,
                    "'"
                            + path
                            + "' is one of "
                            + String.join(", ", words)
                            + ", not '"
                            + shown(value)
                            + "'");
        }
    }

    /**
     * Refuses an address or image a program would read as something other than one: {@code
     * --upload-pack=<command>} ran that command at the next fetch, {@code ext::} and {@code fd::}
     * run or attach one, and a line break or a space makes one value two.
     */
    private void refuseAsOption(final String value, final String path) {
        if (value.startsWith("-")
                || value.startsWith("ext::")
                || value.startsWith("fd::")
                || value.chars()
                        .anyMatch(
                                c ->
                                        Character.isWhitespace(c)
                                                || Character.isSpaceChar(c)
                                                || Character.isISOControl(c))) {
            fault(
                    path,
                    "'"
                            + path
                            + "' is '"
                            + shown(value)
                            + "', which git or podman would read as an option or a command, not"
                            + " an address");
        }
    }

    private static boolean insideRepository(final String named) {
        try {
            final Path path = Path.of(named);
            return !named.isBlank() && !path.isAbsolute() && !path.normalize().startsWith("..");
        } catch (final InvalidPathException ex) {
            return false;
        }
    }

    private static @Nullable String blankless(final @Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean controls(final String value) {
        return value.chars().anyMatch(c -> c < 0x20 || c == 0x7f);
    }

    /**
     * Returns a value as a message shows it: no control character, and no more than a line of it,
     * so that a long value repeated through aliases cannot grow the message without end.
     *
     * @param value The value as written.
     * @return What a message shows of it.
     */
    static String shown(final String value) {
        final String plain = value.replaceAll("\\p{Cntrl}", "?");
        return plain.length() <= SHOWN ? plain : plain.substring(0, SHOWN) + "...";
    }
}
