package org.fuin.porelo.host;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.fuin.porelo.organization.gittree.HttpForgeAdmin;
import org.jspecify.annotations.Nullable;

/** Migration and taking a repository off preserve deploy-key recovery information. */
final class ProjectMigration {
    private final ProjectFiles files;
    private final ProjectRuntime runtime;
    private final ProjectReadKeys keys;
    private final Central central;

    @FunctionalInterface
    interface Central {
        HttpForgeAdmin open(String origin, char[] token);
    }

    ProjectMigration(ProjectFiles files, ProjectRuntime runtime, Central central) {
        this.files = files;
        this.runtime = runtime;
        this.keys = new ProjectReadKeys(files, runtime);
        this.central = central;
    }

    void migrate(HttpForgeAdmin forge, String project, String supplied) throws IOException {
        final Object parsed =
                ProjectRecords.json(
                        supplied, "migration requires a JSON request on standard input", 64);
        if (!(parsed instanceof Map<?, ?> request)
                || !request.keySet().equals(Set.of("address", "token"))
                || !(request.get("address") instanceof String source)
                || !(request.get("token") instanceof String secret)
                || !secret.matches("[!-~]{1,4096}")) {
            throw new HostRefusal(
                    64, "migration requires an address and one printable token on standard input");
        }
        final char[] token = secret.toCharArray();
        try {
            final ProjectAddress address = ProjectAddress.parse(source);
            organizationOrigin(address.origin(), "migration");
            final ProjectReadKeys.Manifest manifest = keys.manifest(project);
            files.directory(files.etc.resolve("organization/read"), true, false);
            for (Path path :
                    List.of(ProjectFiles.parent(manifest.directory()), manifest.directory())) {
                files.mkdir(path, 0700);
            }
            Map<String, @Nullable Object> item = manifest.find(address.repository(), false);
            if (item == null) {
                item =
                        ProjectRecords.object(
                                "name", address.repository(), "address", source, "key_id", 0L);
                manifest.entries().add(item);
                keys.writeManifest(manifest);
            } else if (!ProjectAddress.parse(ProjectReadKeys.address(item))
                    .https()
                    .equals(address.https())) {
                throw new HostRefusal(
                        65,
                        "the project's recorded central address differs; keeping the existing repository");
            }
            final String publicKey = publicKey(manifest.directory(), address.repository());
            try (HttpForgeAdmin api = central.open(address.origin(), token)) {
                if (ProjectReadKeys.id(item) == 0) {
                    deployKey(api, project, address, publicKey, item, manifest);
                }
            }
            final String route = "/repos/" + project + "/" + address.repository();
            Object local = forge.request("GET", route, null, 200, 404);
            if (local == null) {
                forge.request(
                        "POST",
                        "/repos/migrate",
                        ProjectRecords.object(
                                "clone_addr",
                                address.https(),
                                "repo_name",
                                address.repository(),
                                "repo_owner",
                                project,
                                "service",
                                "git",
                                "mirror",
                                false,
                                "private",
                                true,
                                "auth_username",
                                "x-access-token",
                                "auth_password",
                                secret),
                        201);
                local = forge.request("GET", route, null, 200);
            }
            if (!(local instanceof Map<?, ?> record)
                    || !address.https().equals(record.get("original_url"))) {
                throw new HostRefusal(
                        69, "the local forge has no migration from the project's central address");
            }
            forge.request(
                    "PATCH",
                    route,
                    ProjectRecords.object(
                            "allow_fast_forward_only_merge",
                            true,
                            "default_merge_style",
                            "fast-forward-only",
                            "allow_merge_commits",
                            false,
                            "allow_rebase",
                            false,
                            "allow_rebase_explicit",
                            false,
                            "allow_squash_merge",
                            false,
                            "allow_manual_merge",
                            false),
                    200);
            if (forge.request("GET", route + "/branch_protections/main", null, 200, 404) == null) {
                forge.request(
                        "POST",
                        route + "/branch_protections",
                        ProjectRecords.object(
                                "rule_name",
                                "main",
                                "enable_push",
                                false,
                                "required_approvals",
                                1L,
                                "dismiss_stale_approvals",
                                true,
                                "enable_approvals_whitelist",
                                true,
                                "approvals_whitelist_teams",
                                List.of("Owners", "developers"),
                                "enable_merge_whitelist",
                                true,
                                "merge_whitelist_teams",
                                List.of("Owners", "developers"),
                                "enable_status_check",
                                false,
                                "apply_to_admins",
                                true),
                        201);
            }
        } finally {
            Arrays.fill(token, '\0');
        }
    }

    private String publicKey(Path directory, String name) throws IOException {
        final Path key = directory.resolve(ProjectReadKeys.lower(name));
        if (!files.exists(key)) {
            final HostPrograms.Result made =
                    runtime.run(
                            List.of(
                                    "/usr/bin/ssh-keygen",
                                    "-q",
                                    "-t",
                                    "ed25519",
                                    "-N",
                                    "",
                                    "-C",
                                    "",
                                    "-f",
                                    key.toString()),
                            new byte[0],
                            ProjectRuntime.SAFE_ENV,
                            Duration.ofSeconds(30));
            if (made.exitCode() != 0) {
                throw new HostRefusal(69, "the project's read key could not be made");
            }
            // ssh-keygen inherits the caller's umask; normalize both halves before any read.
            ProjectFiles.write(
                    key,
                    () -> {
                        java.nio.file.Files.setAttribute(key, "unix:mode", 0600);
                        java.nio.file.Files.setAttribute(Path.of(key + ".pub"), "unix:mode", 0600);
                    });
        }
        files.trusted(key, true);
        final Path publicPath = Path.of(key + ".pub");
        if (!files.exists(publicPath)) {
            final HostPrograms.Result recovered =
                    runtime.run(
                            List.of("/usr/bin/ssh-keygen", "-y", "-P", "", "-f", key.toString()),
                            new byte[0],
                            ProjectRuntime.SAFE_ENV,
                            Duration.ofSeconds(30));
            if (recovered.exitCode() != 0) {
                throw new HostRefusal(69, "the project's public read key could not be recovered");
            }
            final String publicKey =
                    ProjectRecords.signingKey(recovered.output().strip(), "signing key", 65);
            files.publish(publicPath, ".repositories-", publicKey + "\n");
        }
        return ProjectRecords.signingKey(files.text(publicPath, false).strip(), "signing key", 65);
    }

    private void deployKey(
            HttpForgeAdmin api,
            String project,
            ProjectAddress address,
            String publicKey,
            Map<String, @Nullable Object> item,
            ProjectReadKeys.Manifest manifest)
            throws IOException {
        final ProjectRecords records = new ProjectRecords(files);
        final String host =
                ProjectRecords.setting(
                        records.values(records.conf(), 69),
                        "host",
                        "[a-z0-9]([a-z0-9-]{0,30}[a-z0-9])?",
                        records.conf(),
                        69);
        final String title = "porelo host " + host + " project " + project;
        final String route = "/repos/" + address.owner() + "/" + address.repository() + "/keys";
        final List<Map<?, ?>> existing = new ArrayList<>();
        final List<Map<?, ?>> stale = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        boolean finished = false;
        for (int page = 1; page <= 100; page++) {
            final Object answer =
                    api.request(
                            "GET", route + "?page=" + page + "&per_page=50&limit=50", null, 200);
            if (!(answer instanceof List<?> entries)
                    || entries.stream().anyMatch(value -> !(value instanceof Map<?, ?>))) {
                throw new HostRefusal(69, "the central forge answered malformed deploy keys");
            }
            if (entries.isEmpty()) {
                finished = true;
                break;
            }
            if (!seen.add(ProjectRecords.pythonJson(entries, false))) {
                throw new HostRefusal(69, "the central forge repeated a deploy-key page");
            }
            for (Object entry : entries) {
                final Map<?, ?> value = (Map<?, ?>) entry;
                if (publicKey.equals(value.get("key"))) {
                    existing.add(value);
                }
                if (title.equals(value.get("title")) && !publicKey.equals(value.get("key"))) {
                    stale.add(value);
                }
            }
        }
        if (!finished) {
            throw new HostRefusal(69, "the central forge did not finish listing deploy keys");
        }
        for (Map<?, ?> value : stale) {
            if (!Boolean.TRUE.equals(value.get("read_only"))
                    || !(value.get("id") instanceof Long id)
                    || id <= 0) {
                throw new HostRefusal(
                        65,
                        "the central forge holds a key with write access under this host's title; keeping it");
            }
        }
        for (Map<?, ?> value : stale) {
            api.request("DELETE", route + "/" + value.get("id"), null, 204);
        }
        final Object made =
                existing.size() == 1
                        ? existing.getFirst()
                        : existing.isEmpty()
                                ? api.request(
                                        "POST",
                                        route,
                                        ProjectRecords.object(
                                                "title",
                                                title,
                                                "key",
                                                publicKey,
                                                "read_only",
                                                true),
                                        201)
                                : null;
        if (!(made instanceof Map<?, ?> value)
                || !(value.get("id") instanceof Long id)
                || id <= 0
                || !Boolean.TRUE.equals(value.get("read_only"))
                || !publicKey.equals(value.get("key"))) {
            throw new HostRefusal(
                    69, "the central forge confirmed no matching read-only deploy key");
        }
        item.put("key_id", id);
        item.put("title", value.get("title") instanceof String text ? text : title);
        keys.writeManifest(manifest);
    }

    void organizationOrigin(String origin, String what) throws IOException {
        final String text = files.text(files.etc.resolve("organization/organization.yml"), true);
        final var matcher =
                Pattern.compile(
                                "^\\s+repository:\\s*(['\"]?)([^\\s'\"]+)\\1\\s*$",
                                Pattern.MULTILINE)
                        .matcher(text);
        final List<String> repositories = new ArrayList<>();
        while (matcher.find()) {
            repositories.add(matcher.group(2));
        }
        if (repositories.size() != 1
                || !ProjectAddress.parse(repositories.getFirst()).origin().equals(origin)) {
            throw new HostRefusal(
                    65,
                    what
                            + "'s central forge differs from the organization's forge or its settings do not read");
        }
    }

    Map<String, @Nullable Object> takeOff(
            HttpForgeAdmin forge, String account, String project, String supplied)
            throws IOException {
        final Object parsed =
                ProjectRecords.json(
                        supplied,
                        "taking a repository off requires a JSON request on standard input",
                        64);
        if (!(parsed instanceof Map<?, ?> request)
                || !request.keySet().equals(Set.of("repository", "token"))
                || !(request.get("repository") instanceof String name)
                || !name.matches(ProjectRecords.REPOSITORY)
                || request.get("token") != null
                        && (!(request.get("token") instanceof String secret)
                                || !secret.matches("[!-~]{1,4096}"))) {
            throw new HostRefusal(
                    64,
                    "taking a repository off requires a repository's name and one printable token or null");
        }
        final ProjectReadKeys.Manifest manifest = keys.manifest(project);
        final Map<String, @Nullable Object> item = manifest.find(name, true);
        if (item != null) {
            keys.paths(project);
        }
        final Object found = forge.request("GET", "/repos/" + project + "/" + name, null, 200, 404);
        if (found != null
                && (!(found instanceof Map<?, ?> local)
                        || !(local.get("name") instanceof String actual)
                        || !actual.equalsIgnoreCase(name))) {
            throw new HostRefusal(
                    69, "the forge answered another repository for " + project + "/" + name);
        }
        if (found instanceof Map<?, ?> local) {
            final Map<String, @Nullable Object> row = new java.util.LinkedHashMap<>();
            local.forEach((key, value) -> row.put((String) key, value));
            row.put("name", item == null ? local.get("name") : ProjectReadKeys.name(item));
            keys.check(account, project, List.of(row));
        }
        final Path arrivals =
                files.at(
                        "/var/lib/porelo-host/arrivals/"
                                + project
                                + "/"
                                + ProjectReadKeys.lower(name)
                                + ".jsonl");
        if (files.exists(arrivals)) {
            files.trusted(arrivals, true);
        }
        final List<Map<String, @Nullable Object>> left = new ArrayList<>();
        if (item != null && ProjectReadKeys.id(item) > 0) {
            if (request.get("token") == null) {
                left.add(
                        ProjectRecords.object(
                                "address",
                                ProjectReadKeys.address(item),
                                "title",
                                item.getOrDefault("title", "porelo-host-" + project)));
            } else {
                final ProjectAddress address = ProjectAddress.parse(ProjectReadKeys.address(item));
                organizationOrigin(address.origin(), "taking off");
                final String route =
                        "/repos/"
                                + address.owner()
                                + "/"
                                + address.repository()
                                + "/keys/"
                                + ProjectReadKeys.id(item);
                final char[] token = ((String) request.get("token")).toCharArray();
                try (HttpForgeAdmin api = central.open(address.origin(), token)) {
                    api.request("DELETE", route, null, 204, 404);
                    if (api.request("GET", route, null, 200, 404) != null) {
                        throw new HostRefusal(
                                69,
                                "the central forge still holds the read key of "
                                        + project
                                        + "/"
                                        + name);
                    }
                } finally {
                    Arrays.fill(token, '\0');
                }
            }
        }
        if (found instanceof Map<?, ?> local) {
            forge.request("DELETE", "/repos/" + project + "/" + local.get("name"), null, 204);
            if (forge.request("GET", "/repos/" + project + "/" + name, null, 200, 404) != null) {
                throw new HostRefusal(
                        69,
                        "the forge still holds " + project + "/" + name + "; keeping its read key");
            }
        }
        if (files.exists(arrivals)) {
            files.delete(arrivals);
        }
        if (item != null) {
            for (String suffix : List.of(".pub", "")) {
                files.delete(
                        manifest.directory()
                                .resolve(
                                        ProjectReadKeys.lower(ProjectReadKeys.name(item))
                                                + suffix));
            }
            manifest.entries().remove(item);
            keys.writeManifest(manifest);
        }
        final Map<String, @Nullable Object> answer =
                ProjectRecords.object("project", project, "repository", name, "removed", true);
        if (!left.isEmpty()) {
            answer.put("left_at_central", left);
        }
        return answer;
    }
}
