package org.fuin.porelo.host;

import static org.fuin.porelo.host.HostJson.*;

import java.io.IOException;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Installs the existing forge hook before recording the repository's pre-judgment history. */
final class FollowPushes {
    private final FollowContext c;

    FollowPushes(FollowContext context) {
        c = context;
    }

    private static final String SCRIPT =
            "[ -e /var/lib/gitea/git/repositories ] || [ -L /var/lib/gitea/git/repositories ] || exit 0\n[ -d /var/lib/gitea/git/repositories ] && [ ! -L /var/lib/gitea/git/repositories ] || exit 6\norganization=\"/var/lib/gitea/git/repositories/$1\"\n[ -e \"$organization\" ] || [ -L \"$organization\" ] || exit 0\n[ -d \"$organization\" ] && [ ! -L \"$organization\" ] && cd \"$organization\" || exit 6\nhooks=\"$(git config --file /var/lib/gitea/home/.gitconfig core.hooksPath)\" && [ -n \"$hooks\" ] || exit 4\n[ -f \"$hooks/pre-receive\" ] && [ -x \"$hooks/pre-receive\" ] || exit 4\ngrep -qxF 'custom_hooks_dir=\"./hooks/${hookname}.d\"' \"$hooks/pre-receive\" || exit 4\nshift\nfor repository in *.git .*.git; do\n  [ -d \"$repository\" ] && [ ! -L \"$repository\" ] || continue\n  [ ! -L \"$repository/hooks\" ] && [ ! -L \"$repository/hooks/pre-receive.d\" ] || exit 5\n  git config --file \"$repository/config\" --get core.hooksPath > /dev/null; [ \"$?\" -eq 1 ] || exit 4\n  hook=\"$repository/hooks/pre-receive.d/porelo\"\n  if [ ! -e \"$hook\" ] && [ ! -L \"$hook\" ]; then\n    mkdir -p \"$repository/hooks/pre-receive.d\" || exit 5\n    ln -s /usr/local/libexec/porelo/pre-receive \"$hook\" || exit 5\n  fi\n  [ -L \"$hook\" ] && [ \"$(readlink \"$hook\")\" = /usr/local/libexec/porelo/pre-receive ] && [ -x \"$hook\" ] || exit 5\n  name=\"${repository%.git}\"\n  for known in \"$@\"; do [ \"$known\" = \"$name\" ] && continue 2; done\n  printf \"repository %s\\n\" \"$name\"\n  git --git-dir=\"$repository\" rev-list --all || exit 3\ndone\n";

    void arm(String project, String account) throws IOException {
        final Path directory = c.path("/var/lib/porelo-host/arrivals/" + project);
        c.host.check(ProjectFiles.parent(directory), 73, false, true, 0700);
        c.host.check(directory, 73, false, true, 0700);
        final Set<String> recorded = new java.util.TreeSet<>();
        if (c.files.exists(directory)) {
            for (Path path : c.files.list(directory)) {
                final String name = path.getFileName().toString();
                if (!name.matches("[a-z0-9._-]{1,100}\\.jsonl")) {
                    throw new HostRefusal(73, directory + " holds an unknown entry");
                }
                read(
                        path,
                        row -> {
                            if ("armed".equals(row.get("verdict")))
                                recorded.add(name.substring(0, name.length() - 6));
                        });
            }
        }
        final List<String> arguments = new ArrayList<>(List.of("sh", "-c", SCRIPT, "sh", project));
        arguments.addAll(recorded);
        final var result = c.runtime.exec(account, arguments);
        if (result.exitCode() == 4) {
            throw new HostRefusal(
                    69,
                    "Forgejo's shared pre-receive hook would not run the judge for a repository of "
                            + project);
        }
        if (result.exitCode() != 0) {
            throw new HostRefusal(69, "the forge could not arm the project's repositories");
        }
        final Map<String, List<String>> histories = new LinkedHashMap<>();
        String current = null;
        for (String line : result.output().lines().toList()) {
            if (line.startsWith("repository ")) {
                current = line.substring(11);
                if (!current.matches("[a-z0-9._-]{1,100}")
                        || histories.containsKey(current)
                        || recorded.contains(current)) {
                    throw new HostRefusal(69, "the forge lists an invalid repository");
                }
                histories.put(current, new ArrayList<>());
            } else if (current != null && line.matches(HostRecords.COMMIT)) {
                java.util.Objects.requireNonNull(
                                histories.get(current),
                                "A history line follows its repository header")
                        .add(line);
            } else {
                throw new HostRefusal(69, "the forge lists an invalid repository history");
            }
        }
        final String stamp =
                DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'")
                        .withZone(java.time.ZoneOffset.UTC)
                        .format(c.clock.instant());
        for (var entry : histories.entrySet()) {
            c.directories(directory, 0700);
            c.host.check(directory, 73, true, true, 0700);
            final Path path = directory.resolve(entry.getKey() + ".jsonl");
            final Set<String> pending = new java.util.LinkedHashSet<>(entry.getValue());
            if (c.files.exists(path)) {
                read(
                        path,
                        row -> {
                            if (row.get("commit") instanceof String commit) pending.remove(commit);
                        });
            }
            final StringBuilder text = new StringBuilder();
            for (String commit : pending) {
                text.append(
                                ProjectRecords.pythonJson(
                                        fields(
                                                "commit",
                                                commit,
                                                "verdict",
                                                "migrated",
                                                "arrived",
                                                stamp),
                                        true))
                        .append('\n');
            }
            text.append(
                            ProjectRecords.pythonJson(
                                    fields("verdict", "armed", "arrived", stamp), true))
                    .append('\n');
            new HostPush(c.host, c.runtime, c.clock)
                    .recordArming(
                            project,
                            entry.getKey(),
                            text.toString()
                                    .lines()
                                    .map(
                                            line ->
                                                    object(
                                                            ProjectRecords.json(
                                                                    line,
                                                                    "invalid arming record",
                                                                    73)))
                                    .toList());
        }
    }

    private void read(
            Path path,
            java.util.function.Consumer<Map<String, @org.jspecify.annotations.Nullable Object>>
                    consume)
            throws IOException {
        c.host.check(path, 73, true, false, 0600);
        try (var lock = HostConfigurationLock.acquire(path, true);
                var source =
                        new java.io.BufferedInputStream(
                                java.nio.file.Files.newInputStream(lock.descriptorPath()))) {
            c.host.checkOpened(lock.descriptorPath(), 0600);
            final var line = new java.io.ByteArrayOutputStream();
            int value;
            while ((value = source.read()) != -1) {
                if (value == '\n') {
                    final String content =
                            java.nio.charset.StandardCharsets.UTF_8
                                    .newDecoder()
                                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                                    .decode(java.nio.ByteBuffer.wrap(line.toByteArray()))
                                    .toString();
                    consume.accept(
                            object(
                                    ProjectRecords.json(
                                            content,
                                            path + " holds a malformed verdict line",
                                            73)));
                    line.reset();
                } else {
                    if (line.size() >= 128 * 1024)
                        throw new HostRefusal(73, path + " holds an oversized verdict line");
                    line.write(value);
                }
            }
            if (line.size() != 0)
                throw new HostRefusal(73, path + " holds an incomplete verdict line");
        }
    }
}
