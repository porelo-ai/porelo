package org.fuin.porelo.app;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.core.project.Repository;
import org.fuin.porelo.core.project.ToolRepository;
import org.fuin.porelo.core.project.file.Egress;
import org.fuin.porelo.core.project.file.Image;
import org.fuin.porelo.core.project.file.ProjectFile;
import org.fuin.porelo.core.project.file.ProjectFileWriter;
import org.fuin.porelo.core.project.file.ProjectInfo;
import org.fuin.porelo.core.project.file.RepositoryEntry;
import org.fuin.porelo.core.project.file.RepositoryFile;
import org.fuin.porelo.core.project.file.ToolRepositoryEntry;
import org.fuin.porelo.ecosystem.api.Detection;
import org.fuin.porelo.ecosystem.api.Finding;
import org.jspecify.annotations.Nullable;

/** Combines detected settings into a draft without touching the checkout. */
final class ProjectProposal {
    private record Key(Finding.Part part, String key) {}

    /** The most of a repository's name a proposed project's name takes. */
    private static final int PROPOSED_REPOSITORY_PART = 32;

    /** The image lines a plugin gives, joined in the order of the names. */
    private static final String RUN = "run.";

    /** A variable a plugin sets in the image. */
    private static final String ENV = "env.";

    /** A repository a plugin's tool reaches, its value the URL. */
    private static final String REPOSITORY = "repository.";

    /** A setting for the plugin's own block, the key's dots nesting. */
    private static final String EXTENSION = "extension.";

    private static final String BASE = "ubuntu:24.04";

    private static final java.util.regex.Pattern RUN_NAME =
            java.util.regex.Pattern.compile("[a-z0-9][a-z0-9._-]*");

    /** What a line may not hold beside its text: line breaks and tabs only. */
    private static final java.util.regex.Pattern HIDDEN =
            java.util.regex.Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}&&[^\\n\\t]]");

    /**
     * What fetched image lines may not hold: a tab too, as the question shows it as another
     * character and a person would say yes to a line other than the one that runs.
     */
    private static final java.util.regex.Pattern HIDDEN_IN_RUN =
            java.util.regex.Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}&&[^\\n]]");

    private final String repository;
    private final String project;
    private final @Nullable String upstream;

    /** A host's own forge: nothing else proposes an online project unasked. */
    private static final java.util.regex.Pattern HOST_FORGE =
            java.util.regex.Pattern.compile("http://127\\.0\\.0\\.1:[1-9][0-9]{0,4}/");

    private final Map<String, Object> row = new LinkedHashMap<>();
    private final List<Map<String, Object>> values = new ArrayList<>();
    private final Map<Key, List<Map<String, Object>>> groups = new LinkedHashMap<>();
    private final List<Map<String, Object>> unplaced = new ArrayList<>();
    private final Map<String, Object> credentials = new LinkedHashMap<>();

    /** The adapters that named each credential, so a credential goes to its own adapter's. */
    private final Map<String, java.util.Set<String>> credentialPlugins = new LinkedHashMap<>();

    private final List<Map<String, Object>> extensionValues = new ArrayList<>();
    private List<ToolRepositoryEntry> toolRepositories = List.of();
    private Map<String, Object> extensions = Map.of();
    private List<Placed> tools = List.of();
    private List<Placed> unknowns = List.of();
    private List<String> hosts = List.of();
    private List<String> checkoutHosts = List.of();
    private List<Placed> imageLines = List.of();
    private List<String> refusals = List.of();
    private Map<String, String> env = Map.of();
    private boolean applied;
    private String run = "";

    /**
     * A package or tool the last {@link #answer()} kept.
     *
     * @param what Its name, as the plugin keyed it.
     * @param value What it read, or "" when the name says all.
     * @param source The file it was read from.
     * @param note What the plugin says of it, or "".
     */
    record Placed(String what, String value, String source, String note) {}

    /**
     * Returns the name a project on one repository is proposed under. It differs from the
     * repository's: {@code OwnRepository} says why.
     *
     * @param repository The repository's name.
     * @return The project's name.
     */
    static String projectName(final String repository) {
        // At most 40 characters, so a task named after the repository still has 16 beside it.
        final String word = repository.replace('.', '-');
        final String atWord =
                org.fuin.porelo.runtime.ContainerName.shortened(word, PROPOSED_REPOSITORY_PART)
                        + "-project";
        // A cut back to a word can drop the repository's own '-project' and so take its name.
        return atWord.equals(word)
                ? word.substring(0, PROPOSED_REPOSITORY_PART).replaceAll("-+$", "") + "-project"
                : atWord;
    }

    /** The name a second unnamed task gets, the least a project's name must leave room for. */
    static final String SECOND_UNNAMED = "work-2";

    /**
     * Says why a project's name leaves a task too little room, or {@code null} when it leaves
     * enough: written by hand, a long one left even 'work-2' no room, and every start refused.
     *
     * @param project The project's name.
     * @return The refusal, naming the room, or {@code null}.
     */
    static @Nullable String crampedProject(final String project) {
        final int room = org.fuin.porelo.runtime.ContainerName.taskRoom(project);
        if (room >= SECOND_UNNAMED.length()) {
            return null;
        }
        return "'"
                + project
                + "' leaves a task "
                + Math.max(0, room)
                + " characters of a container name's "
                + org.fuin.porelo.runtime.ContainerName.MAX_LENGTH
                + ", too few for '"
                + SECOND_UNNAMED
                + "': give the project a name of at most "
                + (project.length() + room - SECOND_UNNAMED.length())
                + " characters";
    }

    ProjectProposal(
            String repository,
            @Nullable String upstream,
            @Nullable String central,
            @Nullable String review) {
        this(repository, upstream, central, review, null);
    }

    ProjectProposal(
            String repository,
            @Nullable String upstream,
            @Nullable String central,
            @Nullable String review,
            @Nullable String project) {
        if (!repository.matches(Repository.NAME_PATTERN))
            throw new IllegalArgumentException("invalid repository name");
        this.project = project == null ? projectName(repository) : project;
        this.repository = repository;
        this.upstream = upstream;
        if (upstream != null) row.put("upstream", upstream);
        if (central != null) row.put("central", central);
        if (review != null) row.put("review", review);
    }

    void add(String plugin, Detection detection) {
        for (var finding : detection.findings()) {
            final boolean credential =
                    finding.key().startsWith("credential.") || finding.key().equals("credential");
            if (credential) {
                final String id =
                        finding.key().equals("credential")
                                ? finding.value()
                                : finding.key().substring("credential.".length());
                if (!id.matches("[A-Za-z0-9][A-Za-z0-9_./-]*"))
                    throw new IllegalArgumentException("a credential needs a plain id");
                credentials.put(id, Map.of("id", id, "command", "porelo vault put " + id));
                credentialPlugins
                        .computeIfAbsent(id, unused -> new java.util.LinkedHashSet<>())
                        .add(plugin);
                continue;
            }
            final Map<String, Object> value = new LinkedHashMap<>();
            value.put("key", finding.key());
            value.put("value", finding.value());
            value.put("file", finding.file());
            value.put("line", finding.line());
            value.put("origin", finding.origin().name());
            value.put("unknown", finding.unknown());
            value.put("note", finding.note());
            value.put("plugin", plugin);
            values.add(value);
            if (finding.part() == Finding.Part.RULES && finding.key().startsWith(EXTENSION)) {
                // Each plugin's own: two plugins' blocks never disagree.
                extensionValues.add(value);
                continue;
            }
            // Placed by the prefix alone, so two plugins disagreeing in the part still disagree.
            final boolean image = finding.key().startsWith(RUN) || finding.key().startsWith(ENV);
            groups.computeIfAbsent(
                            new Key(image ? Finding.Part.TOOLS : finding.part(), finding.key()),
                            unused -> new ArrayList<>())
                    .add(value);
        }
        for (var each : detection.unplaced())
            unplaced.add(
                    Map.of(
                            "file",
                            each.file(),
                            "line",
                            each.line(),
                            "note",
                            each.note(),
                            "plugin",
                            plugin));
    }

    /**
     * Returns what each plugin says for a person that has no place of its own in a question: what
     * it read and could place nowhere, and why it names a host.
     *
     * @return One line for each, with its plugin, and its file and line when it has them.
     */
    List<String> notesForAPerson() {
        final List<String> notes = new ArrayList<>();
        for (var each : unplaced) {
            final String file = String.valueOf(each.get("file"));
            notes.add(
                    each.get("plugin")
                            + ": "
                            + (file.isEmpty() ? "" : file + ":" + each.get("line") + ": ")
                            + each.get("note"));
        }
        for (var each : values) {
            final String key = String.valueOf(each.get("key"));
            final String note = String.valueOf(each.get("note"));
            if (key.startsWith("host.")
                    && !note.isEmpty()
                    && !Boolean.TRUE.equals(each.get("unknown"))) {
                notes.add(each.get("plugin") + ": " + key.substring(5) + ": " + note);
            }
        }
        return notes;
    }

    /**
     * Returns the packages and tools the last {@link #answer()} kept.
     *
     * @return They, each with the file it was read from.
     */
    List<Placed> tools() {
        return tools;
    }

    /**
     * Returns what the last {@link #answer()} saw is set and could not read without running
     * something, which the draft holds only as a comment.
     *
     * @return Each with the plugin's note on why as its value, and the file it is set in.
     */
    List<Placed> unknowns() {
        return unknowns;
    }

    /**
     * Returns the image's lines and variables a plugin gave, which the last {@link #answer()} put
     * into the draft: they run as root of the build, so a person reads them whole.
     *
     * @return One for each line of {@code image.run} and each variable of {@code image.env} a
     *     plugin gave, with the file it was found for.
     */
    List<Placed> imageLines() {
        return imageLines;
    }

    /**
     * Returns what the last {@link #answer()} left out of the image, and why.
     *
     * @return One line for each, with its plugin.
     */
    List<String> refusals() {
        return refusals;
    }

    /**
     * Returns the hosts among {@link #hosts()} that the checkout alone names.
     *
     * @return They: no file of the person's or the machine's names them.
     */
    List<String> checkoutHosts() {
        return checkoutHosts;
    }

    /**
     * Returns the hosts the last {@link #answer()} opens.
     *
     * @return They, as the draft names them.
     */
    List<String> hosts() {
        return hosts;
    }

    /**
     * Says whether the last {@link #answer()}'s draft installs or opens anything.
     *
     * @return {@code false} when all it holds is a repository and comments.
     */
    boolean applies() {
        return applied;
    }

    /**
     * Returns the credentials the last detection named.
     *
     * @return Their ids.
     */
    List<String> credentialIds() {
        return List.copyOf(credentials.keySet());
    }

    /**
     * Returns what the draft puts in the project's own image and egress, which filing into an
     * existing project cannot carry: Porelo changes no project file, so a person adds them there.
     *
     * @return One line per package installation and per host, empty when nothing was found.
     */
    List<String> byHand() {
        final List<String> lines = new ArrayList<>();
        run.lines().forEach(line -> lines.add("image.run: " + line));
        env.forEach((name, value) -> lines.add("image.env: " + name + ": " + value));
        hosts.forEach(host -> lines.add("egress.domains: " + host));
        toolRepositories.forEach(
                each ->
                        lines.add(
                                "tool_repositories: "
                                        + each.ecosystem()
                                        + " "
                                        + each.name()
                                        + " "
                                        + each.url()));
        extensions.forEach((adapter, block) -> lines.add("extensions." + adapter + ": " + block));
        return List.copyOf(lines);
    }

    Map<String, Object> row() {
        return Map.copyOf(row);
    }

    /**
     * Returns the repository's entry, which a person adds under {@code repositories} in an existing
     * project's file: Porelo changes no project file that exists.
     *
     * @return The {@code repositories} section with this one entry, as the file holds it.
     */
    String entry() {
        return ProjectFileWriter.entry(
                new ProjectFile(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        Map.of(),
                        null,
                        Map.of(),
                        Map.of(
                                repository,
                                new RepositoryEntry(
                                        central(row),
                                        central(row),
                                        text(row.get("review")),
                                        null,
                                        List.of(),
                                        null,
                                        null,
                                        null,
                                        null))));
    }

    /** A row Porelo writes names one address, its central forge: 'upstream' when no other. */
    private static @Nullable String central(final Map<String, ?> row) {
        final String central = text(row.get("central"));
        return central != null ? central : text(row.get("upstream"));
    }

    Map<String, @Nullable Object> answer() {
        final List<Map<String, Object>> disagreements = new ArrayList<>();
        final List<ToolRepositoryEntry> repositories = new ArrayList<>();
        final List<Map<String, Object>> notPlaced = new ArrayList<>();
        final List<String> domains = new ArrayList<>();
        final List<Placed> found = new ArrayList<>();
        final List<Placed> unread = new ArrayList<>();
        final List<String> ownHosts = new ArrayList<>();
        final StringBuilder packages = new StringBuilder();
        final Map<String, Map<String, Object>> runs = new TreeMap<>();
        final Map<String, Map<String, Object>> variables = new TreeMap<>();
        final List<String> unbound = new ArrayList<>();
        for (var group : groups.entrySet()) {
            final var known =
                    group.getValue().stream()
                            .filter(value -> !Boolean.TRUE.equals(value.get("unknown")))
                            .toList();
            if (known.stream().map(value -> value.get("value")).distinct().count() > 1) {
                disagreements.add(Map.of("key", group.getKey().key(), "values", group.getValue()));
                continue;
            }
            for (var value : group.getValue()) {
                if (Boolean.TRUE.equals(value.get("unknown"))) {
                    notPlaced.add(value);
                    unread.add(
                            new Placed(
                                    group.getKey().key(),
                                    String.valueOf(value.get("note")),
                                    String.valueOf(value.get("file")),
                                    ""));
                }
            }
            if (known.isEmpty()) continue;
            final String key = group.getKey().key();
            final String value = String.valueOf(known.getFirst().get("value"));
            if (key.startsWith(RUN)) {
                runs.put(key.substring(RUN.length()), known.getFirst());
                continue;
            }
            if (key.startsWith(ENV)) {
                variables.put(key.substring(ENV.length()), known.getFirst());
                continue;
            }
            if (group.getKey().part() == Finding.Part.TOOLS) {
                found.add(
                        new Placed(
                                key,
                                value,
                                String.valueOf(known.getFirst().get("file")),
                                known.stream()
                                        .map(each -> String.valueOf(each.get("note")))
                                        .filter(each -> !each.isEmpty())
                                        .distinct()
                                        .collect(java.util.stream.Collectors.joining("; "))));
            }
            if (group.getKey().part() == Finding.Part.RULES && key.startsWith(REPOSITORY)) {
                final String plugin = String.valueOf(known.getFirst().get("plugin"));
                final String name = key.substring(REPOSITORY.length());
                final String host = ToolRepository.host(value);
                if (!name.matches(ToolRepository.NAME_PATTERN)
                        || !plugin.matches(ToolRepository.ECOSYSTEM_PATTERN)
                        || host == null
                        || !(ToolRepository.isLoopback(host)
                                || org.fuin.porelo.core.project.Egress.isDomain(host))) {
                    notPlaced.addAll(known);
                    continue;
                }
                // A repository the person's settings hold a credential for is read through the
                // task's broker, which adds it from the vault: the task gets the broker's address
                // and never the password. Without one it is reached at its own address.
                //
                // Only where the person's own files point that name at this address: a checkout
                // naming a repository like one of theirs would otherwise send their secret to an
                // address of its choosing. And only the same adapter's credential.
                final boolean theirs =
                        credentialPlugins.getOrDefault(name, java.util.Set.of()).contains(plugin)
                                && known.stream()
                                        .anyMatch(each -> !"CHECKOUT".equals(each.get("origin")));
                final boolean servable =
                        name.matches(ToolRepository.CREDENTIAL_PATTERN)
                                && ToolRepository.isPrefix(value);
                if (theirs && !servable) {
                    unbound.add(
                            plugin
                                    + ": the repository '"
                                    + name
                                    + "' is reached at its own address without its credential:"
                                    + " the broker serves only a lower-case vault name and an https"
                                    + " address without a query");
                }
                final String credential = theirs && servable ? name : null;
                repositories.add(new ToolRepositoryEntry(plugin, name, value, credential));
                if (!ToolRepository.isLoopback(host) && !domains.contains(host)) {
                    domains.add(host);
                    if (known.stream().allMatch(each -> "CHECKOUT".equals(each.get("origin")))) {
                        ownHosts.add(host);
                    }
                }
                continue;
            }
            if (group.getKey().part() == Finding.Part.RULES && key.startsWith("host.")) {
                if (!domains.contains(key.substring(5))) {
                    domains.add(key.substring(5));
                    if (known.stream().allMatch(each -> "CHECKOUT".equals(each.get("origin")))) {
                        ownHosts.add(key.substring(5));
                    }
                }
            } else if (group.getKey().part() == Finding.Part.TOOLS
                    && key.startsWith("package.")
                    && key.substring(8).matches("[a-z0-9][a-z0-9+.-]*")
                    && value.matches("[a-z0-9][a-z0-9+.-]*|")) {
                packages.append("RUN apt-get update && apt-get install -y --no-install-recommends ")
                        .append(value.isEmpty() ? key.substring(8) : value)
                        .append(" && rm -rf /var/lib/apt/lists/*\n");
            } else {
                notPlaced.addAll(known);
            }
        }
        final List<String> refused = new ArrayList<>(unbound);
        final List<Placed> lines = new ArrayList<>();
        final StringBuilder fetched = new StringBuilder();
        runs.forEach(
                (name, value) -> {
                    final String text = String.valueOf(value.get("value"));
                    final String why =
                            !RUN_NAME.matcher(name).matches()
                                    ? "'" + name + "' is no name for image lines"
                                    // A person reads them whole: nothing in them may hide.
                                    : HIDDEN_IN_RUN.matcher(text).find()
                                            ? "it holds a control character or a tab"
                                            : imageFault(new Image(BASE, Map.of(), text, null));
                    if (why != null) {
                        refused.add(
                                value.get("plugin") + ": " + RUN + name + " is left out: " + why);
                        return;
                    }
                    text.lines()
                            .forEach(
                                    line ->
                                            lines.add(
                                                    new Placed(
                                                            "image line",
                                                            line,
                                                            String.valueOf(value.get("file")),
                                                            "")));
                    fetched.append(text.endsWith("\n") ? text : text + "\n");
                });
        final Map<String, String> placedEnv = new java.util.LinkedHashMap<>();
        variables.forEach(
                (name, value) -> {
                    final String text = String.valueOf(value.get("value"));
                    // The model's own rule refuses a name Porelo sets in every task, PATH among
                    // them, and a value no ENV line can hold.
                    final String why = imageFault(new Image(BASE, Map.of(name, text), null, null));
                    if (why != null) {
                        refused.add(
                                value.get("plugin") + ": " + ENV + name + " is left out: " + why);
                        return;
                    }
                    placedEnv.put(name, text);
                    lines.add(
                            new Placed(
                                    "image env",
                                    name + "=" + text,
                                    String.valueOf(value.get("file")),
                                    ""));
                });
        final Map<String, Object> blocks = blocks(notPlaced);
        toolRepositories = List.copyOf(repositories);
        extensions = blocks;
        tools = List.copyOf(found);
        unknowns = List.copyOf(unread);
        hosts = List.copyOf(domains);
        checkoutHosts = List.copyOf(ownHosts);
        imageLines = List.copyOf(lines);
        refusals = List.copyOf(refused);
        env = java.util.Collections.unmodifiableMap(placedEnv);
        packages.append(fetched);
        applied =
                !domains.isEmpty()
                        || !packages.isEmpty()
                        || !placedEnv.isEmpty()
                        || !repositories.isEmpty()
                        || !blocks.isEmpty();
        run = packages.toString();
        // What was found and where each value was read is the answer's to say, beside the draft:
        // the draft is written from its model, which holds no comment.
        final String text = ProjectFileWriter.write(draft(domains, packages));
        ProjectReader.read(new StringReader(text), "project.yml draft");
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put("repository", repository);
        answer.put("upstream", upstream);
        answer.put("draft", text);
        answer.put("values", List.copyOf(values));
        answer.put("disagreements", disagreements);
        answer.put("unplaced", List.copyOf(unplaced));
        answer.put("not_placed", notPlaced);
        answer.put("credentials_needed", List.copyOf(credentials.values()));
        answer.put("refused", refusals);
        return answer;
    }

    /** What the model refuses of an image, or {@code null}: the one rule a file is held to. */
    private static @Nullable String imageFault(final Image image) {
        final var faults =
                org.fuin.porelo.core.project.file.ProjectFileCheck.check(
                        new RepositoryFile(image), null);
        return faults.isEmpty() ? null : faults.getFirst().message();
    }

    /**
     * The draft's model: the project, its one repository, and what was found for the image and for
     * what a task may reach, which are the project's.
     */
    private ProjectFile draft(List<String> domains, StringBuilder packages) {
        // A host's forge takes a task's work only from an online project's gate, which needs the
        // project's own address on the same forge beside it.
        final java.util.regex.Matcher forge =
                upstream == null ? null : HOST_FORGE.matcher(upstream);
        final String own =
                forge != null && forge.lookingAt()
                        ? forge.group() + project + "/" + project + ".git"
                        : null;
        return new ProjectFile(
                new ProjectInfo(project, null, own == null ? "guarded" : "online", own, null, null),
                new Image(BASE, env, packages.isEmpty() ? null : packages.toString(), null),
                null,
                domains.isEmpty() ? null : new Egress(List.of(), domains, List.of(), List.of()),
                null,
                null,
                null,
                Map.of(),
                null,
                Map.of(),
                Map.of(
                        repository,
                        new RepositoryEntry(
                                central(row),
                                central(row),
                                text(row.get("review")),
                                null,
                                List.of(),
                                null,
                                null,
                                null,
                                null)),
                null,
                null,
                Map.of(),
                toolRepositories,
                extensions);
    }

    /**
     * Each plugin's block from its {@code extension.<key>} findings, the key's dots nesting; a
     * finding that is unknown, or whose key runs into another's value, is left for a person.
     */
    private Map<String, Object> blocks(final List<Map<String, Object>> notPlaced) {
        final Map<String, Map<List<String>, String>> flat = new LinkedHashMap<>();
        for (var value : extensionValues) {
            final String plugin = String.valueOf(value.get("plugin"));
            final List<String> path =
                    List.of(
                            String.valueOf(value.get("key"))
                                    .substring(EXTENSION.length())
                                    .split("\\.", -1));
            final String text = String.valueOf(value.get("value"));
            final Map<List<String>, String> block =
                    flat.computeIfAbsent(plugin, unused -> new LinkedHashMap<>());
            if (Boolean.TRUE.equals(value.get("unknown"))
                    || !plugin.matches(ToolRepository.ECOSYSTEM_PATTERN)
                    || path.stream().anyMatch(String::isBlank)
                    || clashes(block, path, text)) {
                notPlaced.add(value);
            } else {
                block.put(path, text);
            }
        }
        final Map<String, Object> written = new LinkedHashMap<>();
        flat.forEach(
                (plugin, block) -> {
                    if (!block.isEmpty()) {
                        written.put(plugin, nested(List.copyOf(block.entrySet()), 0));
                    }
                });
        return java.util.Collections.unmodifiableMap(written);
    }

    /** Whether a path runs into one already placed: as its value, under it, or with another. */
    private static boolean clashes(
            final Map<List<String>, String> block, final List<String> path, final String value) {
        for (var placed : block.entrySet()) {
            final List<String> other = placed.getKey();
            final int shorter = Math.min(other.size(), path.size());
            if (other.subList(0, shorter).equals(path.subList(0, shorter))
                    && (other.size() != path.size() || !placed.getValue().equals(value))) {
                return true;
            }
        }
        return false;
    }

    /** The entries below one level, nested by their next key, in the order written. */
    private static Map<String, Object> nested(
            final List<Map.Entry<List<String>, String>> entries, final int depth) {
        final Map<String, List<Map.Entry<List<String>, String>>> byKey = new LinkedHashMap<>();
        for (var entry : entries) {
            byKey.computeIfAbsent(entry.getKey().get(depth), unused -> new ArrayList<>())
                    .add(entry);
        }
        final Map<String, Object> level = new LinkedHashMap<>();
        byKey.forEach(
                (key, below) ->
                        level.put(
                                key,
                                below.getFirst().getKey().size() == depth + 1
                                        ? below.getFirst().getValue()
                                        : nested(below, depth + 1)));
        return level;
    }

    private static @Nullable String text(@Nullable Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
