package org.fuin.porelo.app;

import java.nio.file.Path;
import java.util.List;

/** The person's selected destinations for a gathered transfer. */
record HostGatherConfig(String local, String owner, Path ca, List<Repository> repositories) {
    record Repository(String name, String central, List<String> keys) {
        Repository {
            keys = List.copyOf(keys);
        }

        Repository(String name, String central) {
            this(name, central, List.of(name));
        }
    }

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
                    if (words.length != 3 && words.length != 4)
                        throw bad(source, "gives no valid https:// address for '" + name + "'");
                    final String key = words.length == 4 ? words[2] : name;
                    final String address = words[words.length - 1];
                    if (!repositoryName(key)
                            || !address.matches("https://[A-Za-z0-9.-]+(:[0-9]{1,5})?/[^\\s?#@]+"))
                        throw bad(source, "gives no valid https:// address for '" + name + "'");
                    final Repository previous = repos.get(name);
                    if (previous == null)
                        repos.put(name, new Repository(name, address, List.of(key)));
                    else {
                        if (words.length != 4
                                || !previous.central().equals(address)
                                || previous.keys().contains(key))
                            throw bad(
                                    source,
                                    "names repository '"
                                            + name
                                            + "' twice with repeated or conflicting destinations; key/name pairs: "
                                            + String.join(
                                                    " ",
                                                    previous.keys().stream()
                                                            .map(value -> value + "/" + name)
                                                            .toList())
                                            + " "
                                            + key
                                            + "/"
                                            + name);
                        final var keys = new java.util.ArrayList<>(previous.keys());
                        keys.add(key);
                        repos.put(name, new Repository(name, address, keys));
                    }
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
        final String available =
                String.join(
                        " ",
                        repositories.stream()
                                .flatMap(
                                        repo ->
                                                repo.keys().stream()
                                                        .map(key -> key + "/" + repo.name()))
                                .toList());
        final var identities = new java.util.HashMap<String, Repository>();
        for (var repo : repositories) {
            final var aliases = new java.util.ArrayList<>(repo.keys());
            aliases.add(repo.name());
            for (String identity : aliases) {
                final var previous =
                        identities.putIfAbsent(identity.toLowerCase(java.util.Locale.ROOT), repo);
                if (previous != null && !previous.equals(repo))
                    throw HostGatherRefusal.host(
                            "selection",
                            64,
                            "repository '"
                                    + identity
                                    + "' is ambiguous; available key/name pairs: "
                                    + available);
            }
        }
        if (names.isEmpty()) return repositories;
        final var selected = new java.util.ArrayList<Repository>();
        final var seen = new java.util.HashSet<Repository>();
        for (String name : names) {
            if (!repositoryName(name))
                throw HostGatherRefusal.host(
                        "selection", 64, "'" + name + "' is not a repository name");
            final Repository repo = identities.get(name.toLowerCase(java.util.Locale.ROOT));
            if (repo == null)
                throw HostGatherRefusal.host(
                        "selection",
                        64,
                        "the config for "
                                + owner
                                + " holds no repository '"
                                + name
                                + "'; available key/name pairs: "
                                + available);
            if (!seen.add(repo))
                throw HostGatherRefusal.host(
                        "selection",
                        64,
                        "'"
                                + name
                                + "' names a repository twice; available key/name pairs: "
                                + available);
            selected.add(repo);
        }
        return List.copyOf(selected);
    }

    void refuseCredentials(char[] central, char[] local) {
        final var values =
                new java.util.ArrayList<>(java.util.List.of(this.local, owner, ca.toString()));
        for (var repo : repositories) {
            values.add(repo.name());
            values.addAll(repo.keys());
            values.add(repo.central());
        }
        for (String value : values)
            if (!value.equals(HostGatherFlow.redact(value, central, local)))
                throw HostGatherRefusal.host(
                        "configuration",
                        65,
                        "the push configuration contains a credential; remove it from the settings and retry");
    }

    static boolean repositoryName(String name) {
        return name.matches("[A-Za-z0-9][A-Za-z0-9._-]*");
    }

    private static HostGatherRefusal bad(Path source, String reason) {
        return HostGatherRefusal.host("configuration", 65, source + " " + reason);
    }
}
