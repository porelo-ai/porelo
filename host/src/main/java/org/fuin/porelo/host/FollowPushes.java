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
            script("/var/lib/gitea", "/usr/local/libexec/porelo/pre-receive");

    /**
     * The arming as the forge's account runs it: it links the judge into each repository of the
     * project and lists the history of each it was not armed for yet.
     *
     * <p>It never passes over a link: a linked {@code git} directory, organization or repository
     * stops it, and so does a forge hook or a judge that is a link rather than a plain executable
     * file. A link it made is removed when it stops, so a stop leaves the repositories as they
     * were.
     *
     * @param gitea The forge's own directory.
     * @param judge The judge each repository's hook links to.
     * @return The script.
     */
    static String script(final String gitea, final String judge) {
        return """
                made=""
                stop() { for link in $made; do rm -f "$link"; done; exit "$1"; }
                [ ! -L GITEA/git ] || exit 6
                [ -e GITEA/git/repositories ] || [ -L GITEA/git/repositories ] || exit 0
                [ -d GITEA/git/repositories ] && [ ! -L GITEA/git/repositories ] || exit 6
                organization="GITEA/git/repositories/$1"
                [ -e "$organization" ] || [ -L "$organization" ] || exit 0
                [ -d "$organization" ] && [ ! -L "$organization" ] && cd "$organization" || exit 6
                hooks="$(git config --file GITEA/home/.gitconfig core.hooksPath)" && [ -n "$hooks" ] || exit 4
                [ -f "$hooks/pre-receive" ] && [ ! -L "$hooks/pre-receive" ] && [ -x "$hooks/pre-receive" ] || exit 4
                grep -qxF 'custom_hooks_dir="./hooks/${hookname}.d"' "$hooks/pre-receive" || exit 4
                [ -f JUDGE ] && [ ! -L JUDGE ] && [ -x JUDGE ] || exit 5
                shift
                for repository in *.git .*.git; do
                  [ -e "$repository" ] || [ -L "$repository" ] || continue
                  [ -d "$repository" ] && [ ! -L "$repository" ] || stop 5
                  case "$repository" in *[!a-z0-9._-]*) stop 5 ;; esac
                  [ ! -L "$repository/hooks" ] && [ ! -L "$repository/hooks/pre-receive.d" ] || stop 5
                  git config --file "$repository/config" --get core.hooksPath > /dev/null; [ "$?" -eq 1 ] || stop 4
                  hook="$repository/hooks/pre-receive.d/porelo"
                  if [ ! -e "$hook" ] && [ ! -L "$hook" ]; then
                    mkdir -p "$repository/hooks/pre-receive.d" || stop 5
                    ln -s JUDGE "$hook" || stop 5
                    made="$made $hook"
                  fi
                  [ -L "$hook" ] && [ "$(readlink "$hook")" = JUDGE ] || stop 5
                  name="${repository%.git}"
                  for known in "$@"; do [ "$known" = "$name" ] && continue 2; done
                  printf "repository %s\\n" "$name"
                  git --git-dir="$repository" rev-list --all || stop 3
                done
                """
                .replace("GITEA", gitea)
                .replace("JUDGE", judge);
    }

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
