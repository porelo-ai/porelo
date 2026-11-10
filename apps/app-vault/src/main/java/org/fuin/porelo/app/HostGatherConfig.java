package org.fuin.porelo.app;

import java.nio.file.Path;
import java.util.List;

/** The person's selected destinations for a gathered transfer. */
record HostGatherConfig(String local, String owner, Path ca, List<Repository> repositories) {
    record Repository(String name, String central) {}

    static HostGatherConfig parse(Path source, String text, String project, String local) {
        String owner = "";
        String authority = "";
        boolean configuredLocal = false;
        final var repos = new java.util.LinkedHashMap<String, Repository>();
        for (String raw : text.split("\n", -1)) {
            final String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            final String[] words = line.split("\\s+");
            switch (words[0]) {
                case "local" -> {
                    if (words.length != 2 || !words[1].equals(local))
                        throw bad(source, "names no matching local forge");
                    configuredLocal = true;
                }
                case "owner" -> {
                    if (words.length != 2) throw bad(source, "gives extra words on owner");
                    owner = words[1];
                }
                case "central-ca" -> {
                    if (words.length != 2 || !words[1].matches("/[^\\s]+"))
                        throw bad(source, "names no single absolute central-ca path");
                    authority = words[1];
                }
                case "repository" -> {
                    if (words.length < 2 || !repositoryName(words[1]))
                        throw bad(source, "names no valid repository name");
                    final String name = words[1];
                    if (words.length != 3
                            || !words[2].matches("https://[A-Za-z0-9.-]+(:[0-9]{1,5})?/[^\\s?#@]+"))
                        throw bad(source, "gives no valid https:// address for '" + name + "'");
                    if (repos.putIfAbsent(name, new Repository(name, words[2])) != null)
                        throw bad(source, "names repository '" + name + "' twice");
                }
                default -> throw bad(source, "names an unknown setting");
            }
        }
        if (!configuredLocal || owner.isEmpty() || authority.isEmpty())
            throw bad(source, "names no local forge, owner or central CA");
        if (!owner.equals(project)) throw bad(source, "is for another project");
        if (repos.isEmpty()) throw bad(source, "names no repositories to push");
        return new HostGatherConfig(local, owner, Path.of(authority), List.copyOf(repos.values()));
    }

    List<Repository> select(List<String> names) {
        if (names.isEmpty()) return repositories;
        final var selected = new java.util.ArrayList<Repository>();
        final var seen = new java.util.HashSet<String>();
        for (String name : names) {
            if (!repositoryName(name))
                throw new HostGatherRefusal(64, "'" + name + "' is not a repository name");
            final Repository repo =
                    repositories.stream()
                            .filter(item -> item.name().equals(name))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new HostGatherRefusal(
                                                    64,
                                                    "the config for "
                                                            + owner
                                                            + " holds no repository '"
                                                            + name
                                                            + "'; it holds: "
                                                            + String.join(
                                                                    " ",
                                                                    repositories.stream()
                                                                            .map(Repository::name)
                                                                            .toList())));
            if (!seen.add(name)) throw new HostGatherRefusal(64, "'" + name + "' is named twice");
            selected.add(repo);
        }
        return List.copyOf(selected);
    }

    void refuseCredentials(char[] central, char[] local) {
        final var values =
                new java.util.ArrayList<>(java.util.List.of(this.local, owner, ca.toString()));
        for (var repo : repositories) {
            values.add(repo.name());
            values.add(repo.central());
        }
        for (String value : values)
            if (!value.equals(HostGatherFlow.redact(value, central, local)))
                throw new HostGatherRefusal(
                        65,
                        "the push configuration contains a credential; remove it from the settings and retry");
    }

    static boolean repositoryName(String name) {
        return name.matches("[A-Za-z0-9][A-Za-z0-9._-]*");
    }

    private static HostGatherRefusal bad(Path source, String reason) {
        return new HostGatherRefusal(65, source + " " + reason);
    }
}
