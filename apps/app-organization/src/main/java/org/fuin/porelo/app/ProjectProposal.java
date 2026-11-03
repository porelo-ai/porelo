package org.fuin.porelo.app;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.core.project.Repository;
import org.fuin.porelo.ecosystem.api.Detection;
import org.fuin.porelo.ecosystem.api.Finding;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/** Combines detected settings into a draft without touching the checkout. */
final class ProjectProposal {
    private record Key(Finding.Part part, String key) {}

    private final String repository;
    private final String project;
    private final @Nullable String upstream;
    private final Map<String, Object> row = new LinkedHashMap<>();
    private final List<Map<String, Object>> values = new ArrayList<>();
    private final Map<Key, List<Map<String, Object>>> groups = new LinkedHashMap<>();
    private final List<Map<String, Object>> unplaced = new ArrayList<>();
    private final Map<String, Object> credentials = new LinkedHashMap<>();

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
        this.project =
                project == null ? (repository.equals("proposed") ? "draft" : "proposed") : project;
        if (!repository.matches(Repository.NAME_PATTERN))
            throw new IllegalArgumentException("invalid repository name");
        this.repository = repository;
        this.upstream = upstream;
        if (upstream != null) row.put("upstream", upstream);
        if (central != null) row.put("central", central);
        if (review != null) row.put("review", ProjectRepositoryCommand.review(review));
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

    Map<String, Object> row() {
        return Map.copyOf(row);
    }

    Map<String, @Nullable Object> answer() {
        final List<Map<String, Object>> disagreements = new ArrayList<>();
        final List<Map<String, Object>> notPlaced = new ArrayList<>();
        final List<String> domains = new ArrayList<>();
        final StringBuilder packages = new StringBuilder();
        final StringBuilder comments = new StringBuilder();
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
                if (Boolean.TRUE.equals(value.get("unknown"))) notPlaced.add(value);
            }
            if (known.isEmpty()) continue;
            final String key = group.getKey().key();
            final String value = String.valueOf(known.getFirst().get("value"));
            final String sources =
                    known.stream()
                            .map(ProjectProposal::source)
                            .collect(java.util.stream.Collectors.joining(", "));
            if (group.getKey().part() == Finding.Part.RULES && key.startsWith("host.")) {
                domains.add(key.substring(5));
                comments.append("      - ")
                        .append(quote(key.substring(5)))
                        .append(" # ")
                        .append(sources)
                        .append('\n');
            } else if (group.getKey().part() == Finding.Part.TOOLS
                    && key.startsWith("package.")
                    && key.substring(8).matches("[a-z0-9][a-z0-9+.-]*")
                    && value.matches("[a-z0-9][a-z0-9+.-]*|")) {
                packages.append(
                                "      RUN apt-get update && apt-get install -y --no-install-recommends ")
                        .append(value.isEmpty() ? key.substring(8) : value)
                        .append(" && rm -rf /var/lib/apt/lists/* # ")
                        .append(sources)
                        .append('\n');
            } else {
                notPlaced.addAll(known);
            }
        }
        if (!domains.isEmpty()) row.put("egress", Map.of("domains", domains));
        if (!packages.isEmpty())
            row.put(
                    "image",
                    Map.of(
                            "base_image",
                            "ubuntu:24.04",
                            "snippet",
                            packages.toString().stripIndent()));
        final StringBuilder draft =
                new StringBuilder("project:\n  name: ")
                        .append(quote(project))
                        .append(
                                "\n  security_class: guarded\nimage:\n  base_image: ubuntu:24.04\nrepositories:\n  ")
                        .append(quote(repository))
                        .append(":");
        if (row.isEmpty()) draft.append(" {}\n");
        else draft.append('\n');
        for (String field : List.of("upstream", "central", "review"))
            if (row.containsKey(field))
                draft.append("    ")
                        .append(field)
                        .append(": ")
                        .append(quote(String.valueOf(row.get(field))))
                        .append('\n');
        if (!domains.isEmpty())
            draft.append("    egress:\n      domains:\n")
                    .append(comments.toString().replace("      - ", "        - "));
        if (!packages.isEmpty())
            draft.append("    image:\n      base_image: ubuntu:24.04\n      snippet: |\n")
                    .append(packages.toString().indent(2));
        for (var value : notPlaced)
            draft.append("# ")
                    .append(plain(String.valueOf(value.get("key"))))
                    .append(" = ")
                    .append(plain(String.valueOf(value.get("value"))))
                    .append(" # ")
                    .append(source(value))
                    .append('\n');
        final String text = draft.toString();
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

    private static String source(Map<String, Object> value) {
        return plain(
                value.get("file")
                        + ":"
                        + value.get("line")
                        + " ("
                        + value.get("origin")
                        + ", "
                        + value.get("plugin")
                        + ")");
    }

    private static String plain(String text) {
        return text.replaceAll("[\\p{Cntrl}\\p{Zl}\\p{Zp}]", " ");
    }

    private static String quote(String text) {
        return Json.write(text);
    }
}
