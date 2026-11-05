package org.fuin.porelo.app;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.core.project.Repository;
import org.fuin.porelo.core.project.file.Egress;
import org.fuin.porelo.core.project.file.Image;
import org.fuin.porelo.core.project.file.ProjectFile;
import org.fuin.porelo.core.project.file.ProjectFileWriter;
import org.fuin.porelo.core.project.file.ProjectInfo;
import org.fuin.porelo.core.project.file.RepositoryEntry;
import org.fuin.porelo.ecosystem.api.Detection;
import org.fuin.porelo.ecosystem.api.Finding;
import org.jspecify.annotations.Nullable;

/** Combines detected settings into a draft without touching the checkout. */
final class ProjectProposal {
    private record Key(Finding.Part part, String key) {}

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
    private List<Placed> tools = List.of();
    private List<Placed> unknowns = List.of();
    private List<String> hosts = List.of();
    private List<String> checkoutHosts = List.of();
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
        final String word = repository.replace('.', '-');
        final String cut = word.length() > 55 ? word.substring(0, 55) : word;
        return cut.replaceAll("-+$", "") + "-project";
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
            groups.computeIfAbsent(
                            new Key(finding.part(), finding.key()), unused -> new ArrayList<>())
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
        hosts.forEach(host -> lines.add("egress.domains: " + host));
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
                                        text(row.get("upstream")),
                                        text(row.get("central")),
                                        text(row.get("review")),
                                        null,
                                        List.of(),
                                        null,
                                        null,
                                        null,
                                        null))));
    }

    Map<String, @Nullable Object> answer() {
        final List<Map<String, Object>> disagreements = new ArrayList<>();
        final List<Map<String, Object>> notPlaced = new ArrayList<>();
        final List<String> domains = new ArrayList<>();
        final List<Placed> found = new ArrayList<>();
        final List<Placed> unread = new ArrayList<>();
        final List<String> ownHosts = new ArrayList<>();
        final StringBuilder packages = new StringBuilder();
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
            if (group.getKey().part() == Finding.Part.RULES && key.startsWith("host.")) {
                domains.add(key.substring(5));
                if (known.stream().allMatch(each -> "CHECKOUT".equals(each.get("origin")))) {
                    ownHosts.add(key.substring(5));
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
        tools = List.copyOf(found);
        unknowns = List.copyOf(unread);
        hosts = List.copyOf(domains);
        checkoutHosts = List.copyOf(ownHosts);
        applied = !domains.isEmpty() || !packages.isEmpty();
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
        return answer;
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
                new Image(
                        "ubuntu:24.04",
                        Map.of(),
                        packages.isEmpty() ? null : packages.toString(),
                        null),
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
                                text(row.get("upstream")),
                                text(row.get("central")),
                                text(row.get("review")),
                                null,
                                List.of(),
                                null,
                                null,
                                null,
                                null)));
    }

    private static @Nullable String text(@Nullable Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
