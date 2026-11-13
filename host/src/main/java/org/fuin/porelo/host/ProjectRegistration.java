package org.fuin.porelo.host;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.organization.gittree.HttpForgeAdmin;
import org.jspecify.annotations.Nullable;

/** Project registration, rollback and resumable removal of just its own forge identities. */
final class ProjectRegistration {
    private final ProjectFiles files;
    private final ProjectRecords records;
    private final ProjectRuntime runtime;
    private final ProjectReadKeys keys;
    private final java.util.function.Supplier<ProjectCancellation> cancellations;

    ProjectRegistration(ProjectFiles files, ProjectRuntime runtime) {
        this(files, runtime, ProjectCancellation::new);
    }

    ProjectRegistration(
            ProjectFiles files,
            ProjectRuntime runtime,
            java.util.function.Supplier<ProjectCancellation> cancellations) {
        this.cancellations = cancellations;
        this.files = files;
        this.records = new ProjectRecords(files);
        this.runtime = runtime;
        this.keys = new ProjectReadKeys(files, runtime);
    }

    Map<String, @Nullable Object> add(
            HttpForgeAdmin forge,
            String account,
            String project,
            String admin,
            String key,
            ProjectRecords.Person person)
            throws IOException {
        final String publisher = "porelo-publisher-" + project;
        for (var identity :
                List.of(
                        List.of("organization", "/orgs/" + project),
                        List.of("user", "/users/" + project),
                        List.of("publisher", "/users/" + publisher))) {
            if (forge.request("GET", identity.get(1), null, 200, 404) != null) {
                throw new HostRefusal(
                        65,
                        "the forge already has "
                                + identity.getFirst()
                                + " "
                                + identity.get(1).substring(identity.get(1).lastIndexOf('/') + 1));
            }
        }
        if (forge.request("GET", "/users/" + admin, null, 200, 404) == null) {
            throw new HostRefusal(65, "the forge has no admin login " + admin);
        }
        for (String name : List.of("projects", "people")) {
            files.mkdir(files.etc.resolve(name), 0700);
        }
        final Path record = records.record(project);
        final Path temporary =
                files.stage(
                        record,
                        "." + project + "-",
                        "admin="
                                + admin
                                + "\nsigned_by="
                                + key
                                + "\npublisher="
                                + publisher
                                + "\n");
        final List<String> membership = new ArrayList<>(person.members());
        membership.add(project + " project-admin");
        try (ProjectCancellation cancellation = cancellations.get()) {
            synchronized (cancellation) {
                Path personTemporary = null;
                boolean organizationMade = false;
                boolean publisherMade = false;
                try {
                    personTemporary =
                            files.stage(
                                    records.person(admin),
                                    "." + admin + "-",
                                    person.format(membership));
                    forge.request(
                            "POST",
                            "/admin/users/porelo-admin/orgs",
                            Map.of("username", project, "visibility", "private"),
                            201);
                    organizationMade = true;
                    cancellation.check();
                    final List<Map<?, ?>> owners =
                            forge.listing("/orgs/" + project + "/teams").stream()
                                    .filter(team -> "Owners".equals(team.get("name")))
                                    .toList();
                    if (owners.size() != 1
                            || !(owners.getFirst().get("id") instanceof Long id)
                            || id <= 0) {
                        throw new HostRefusal(
                                65, "the forge has no unique Owners team for " + project);
                    }
                    forge.request("PUT", "/teams/" + id + "/members/" + admin, null, 204);
                    settings(forge, project, cancellation);
                    cancellation.check();
                    final HostPrograms.Result made =
                            runtime.admin(
                                    account,
                                    "create",
                                    "--username",
                                    publisher,
                                    "--email",
                                    publisher + "@porelo.invalid",
                                    "--random-password",
                                    "--must-change-password=false");
                    if (made.exitCode() != 0) {
                        throw new HostRefusal(
                                69,
                                "Forgejo made no publisher "
                                        + publisher
                                        + " (exit "
                                        + made.exitCode()
                                        + ")");
                    }
                    publisherMade = true;
                    cancellation.check();
                    final Object team =
                            forge.request(
                                    "POST",
                                    "/orgs/" + project + "/teams",
                                    team("publishers", Map.of("repo.packages", "write"), false),
                                    201);
                    if (!(team instanceof Map<?, ?> value)
                            || !(value.get("id") instanceof Long teamId)
                            || teamId <= 0) {
                        throw new HostRefusal(
                                65, "the forge returned no valid publishers team for " + project);
                    }
                    forge.request("PUT", "/teams/" + teamId + "/members/" + publisher, null, 204);
                    cancellation.check();
                    publisherSecret(
                            forge,
                            account,
                            project,
                            publisher,
                            "write:package",
                            "PORELO_PUBLISHER_TOKEN");
                    cancellation.check();
                    publisherSecret(
                            forge,
                            account,
                            project,
                            publisher,
                            "read:package",
                            "PORELO_PACKAGES_READ_TOKEN");
                    cancellation.check();
                    final Path pool = files.at("/var/lib/porelo-host/pool/run-key");
                    if (files.exists(pool)) {
                        forge.sendPoolKey(project, ProjectPoolKey.readKey(files));
                    }
                    cancellation.check();
                    try {
                        Files.createLink(record, temporary);
                    } catch (FileAlreadyExistsException ex) {
                        throw new HostRefusal(65, record + " exists");
                    } catch (IOException ex) {
                        throw new HostRefusal(
                                73,
                                "cannot write " + record + ": " + ex.getClass().getSimpleName());
                    }
                    try {
                        files.replace(personTemporary, records.person(admin));
                    } catch (RuntimeException | IOException ex) {
                        files.delete(record);
                        throw ex;
                    }
                } catch (RuntimeException | IOException ex) {
                    final boolean interrupted = Thread.interrupted();
                    try {
                        final List<String> left = new ArrayList<>();
                        if (organizationMade) {
                            cleanup(forge, "/orgs/" + project, "organization " + project, left);
                        }
                        if (publisherMade) {
                            cleanup(
                                    forge,
                                    "/admin/users/" + publisher,
                                    "publisher " + publisher,
                                    left);
                        }
                        if (!left.isEmpty()) {
                            final int code =
                                    ex instanceof HostRefusal refusal
                                            ? refusal.exitCode()
                                            : ex instanceof HttpForgeAdmin.Refusal refusal
                                                    ? refusal.exitCode()
                                                    : 69;
                            final String cause =
                                    ex instanceof HostRefusal
                                                    || ex instanceof HttpForgeAdmin.Refusal
                                            ? ex.getMessage()
                                            : ex.getClass().getSimpleName();
                            throw new HostRefusal(
                                    code, cause + "; cleanup left " + String.join(", ", left));
                        }
                        throw ex;
                    } finally {
                        if (interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                } finally {
                    if (personTemporary != null) {
                        files.delete(personTemporary);
                    }
                    files.delete(temporary);
                }
            }
        }
        return ProjectRecords.object(
                "project",
                project,
                "admin",
                admin,
                "organization",
                project,
                "publisher",
                publisher);
    }

    private static void cleanup(HttpForgeAdmin forge, String path, String name, List<String> left) {
        try {
            forge.request("DELETE", path, null, 204);
        } catch (HttpForgeAdmin.Refusal ex) {
            left.add(name);
        }
    }

    private void publisherSecret(
            HttpForgeAdmin forge,
            String account,
            String project,
            String publisher,
            String scope,
            String name)
            throws IOException {
        final char[] secret = runtime.token(account, publisher, scope);
        try {
            // The JSON API requires a String, created only for this synchronous request.
            forge.request(
                    "PUT",
                    "/orgs/" + project + "/actions/secrets/" + name,
                    Map.of("data", new String(secret)),
                    201,
                    204);
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    private void settings(HttpForgeAdmin forge, String project, ProjectCancellation cancellation)
            throws IOException {
        final Map<String, String> units = new LinkedHashMap<>();
        units.put("repo.code", "write");
        units.put("repo.pulls", "write");
        units.put("repo.issues", "write");
        units.put("repo.actions", "read");
        units.put("repo.packages", "read");
        for (String name : List.of("developers", "gates", "agents")) {
            cancellation.check();
            final Map<String, String> access = new LinkedHashMap<>(units);
            if (name.equals("gates")) {
                access.remove("repo.issues");
                access.remove("repo.packages");
            }
            forge.request("POST", "/orgs/" + project + "/teams", team(name, access, true), 201);
        }
        final Path path = files.etc.resolve("forge/organization.json");
        final Object settings =
                ProjectRecords.json(
                        files.text(path, false), "dependency or file read failed: ValueError", 69);
        if (!(settings instanceof Map<?, ?> value)
                || !(value.get("variables") instanceof Map<?, ?> variables)
                || !(value.get("placeholder_secrets") instanceof List<?> secrets)) {
            throw new HostRefusal(69, "dependency or file read failed: ValueError");
        }
        for (var variable : variables.entrySet()) {
            cancellation.check();
            if (!(variable.getKey() instanceof String name)
                    || !(variable.getValue() instanceof String text)
                    || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new HostRefusal(69, "invalid organization variable");
            }
            forge.request(
                    "POST",
                    "/orgs/" + project + "/actions/variables/" + name,
                    Map.of("value", text.replace("{project}", project)),
                    201,
                    204);
        }
        for (Object secret : secrets) {
            cancellation.check();
            if (!(secret instanceof String name) || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new HostRefusal(69, "invalid organization secret name");
            }
            forge.request(
                    "PUT",
                    "/orgs/" + project + "/actions/secrets/" + name,
                    Map.of("data", "porelo-host-placeholder"),
                    201,
                    204);
        }
    }

    private static Map<String, @Nullable Object> team(
            String name, Map<String, String> units, boolean allRepositories) {
        return ProjectRecords.object(
                "name",
                name,
                "permission",
                "write",
                "units",
                List.copyOf(units.keySet()),
                "units_map",
                units,
                "includes_all_repositories",
                allRepositories,
                "can_create_org_repo",
                false);
    }

    Map<String, @Nullable Object> remove(
            HttpForgeAdmin forge,
            String account,
            String project,
            String publisher,
            List<ProjectRecords.Affected> affected,
            boolean inherited,
            TokenInput tokenInput)
            throws IOException {
        final List<Map<String, @Nullable Object>> left = keys.left(project);
        final Path record = records.record(project);
        final String content = files.text(record, true);
        final boolean started = content.lines().anyMatch(line -> line.equals("removing=true"));
        final boolean organization =
                forge.request("GET", "/orgs/" + project, null, 200, 404) != null;
        if (!organization && !started) {
            throw new HostRefusal(
                    65, "the forge has no organization for registered project " + project);
        }
        final boolean user = forge.request("GET", "/users/" + publisher, null, 200, 404) != null;
        if (!user && !started) {
            throw new HostRefusal(
                    65, "the forge has no publisher for registered project " + project);
        }
        final List<Map<?, ?>> repositories =
                organization ? forge.listing("/orgs/" + project + "/repos") : List.of();
        final List<Map<?, ?>> packages =
                organization ? forge.listing("/packages/" + project) : List.of();
        final List<String> packagePaths = new ArrayList<>();
        for (Map<?, ?> item : packages) {
            final List<String> parts = new ArrayList<>();
            for (String field : List.of("type", "name", "version")) {
                if (!(item.get(field) instanceof String value)
                        || value.isEmpty()
                        || value.equals(".")
                        || value.equals("..")
                        || value.chars().anyMatch(c -> c < 32 || c == 127)) {
                    throw new HostRefusal(
                            65, "the forge returned an invalid package identity for " + project);
                }
                parts.add(encode(value));
            }
            final String path = "/packages/" + project + "/" + String.join("/", parts);
            if (packagePaths.contains(path)) {
                throw new HostRefusal(
                        65, "the forge returned a duplicate package identity for " + project);
            }
            packagePaths.add(path);
        }
        final List<String> names = new ArrayList<>();
        for (Map<?, ?> repository : repositories) {
            if (!(repository.get("name") instanceof String name)
                    || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")
                    || names.contains(name)) {
                throw new HostRefusal(
                        65,
                        "the forge returned an invalid or duplicate repository name for "
                                + project);
            }
            names.add(name);
        }
        if (!names.isEmpty()) {
            final Path manifest =
                    files.etc.resolve(
                            "organization/read/projects/" + project + "/repositories.json");
            if (files.exists(manifest) || inherited) {
                keys.check(account, project, repositories);
            } else {
                checkPush(project, names, tokenInput);
            }
        }
        final List<Path> publications =
                List.of(
                        files.etc.resolve("projects/" + project + ".people.json"),
                        files.etc.resolve("signers/" + project + ".json"),
                        files.etc.resolve("signers/" + project + ".allowed"));
        for (Path path : publications) {
            files.directory(ProjectFiles.parent(path), false, true);
            if (files.exists(path)) {
                files.trusted(path, false);
            }
        }
        final Path arrivals = files.at("/var/lib/porelo-host/arrivals/" + project);
        final List<Path> recorded = new ArrayList<>();
        // Decided once, by looks that follow no link: the removal below takes what this found and
        // never looks again.
        final boolean arrived =
                files.exists(ProjectFiles.parent(arrivals))
                        && files.directory(arrivals, true, true);
        if (arrived) {
            for (Path path : files.list(arrivals)) {
                files.trusted(path, true);
                recorded.add(path);
            }
        }
        keys.paths(project);
        final Map<Path, Path> staged = new LinkedHashMap<>();
        try {
            for (ProjectRecords.Affected person : affected) {
                staged.put(
                        person.path(),
                        files.stage(
                                person.path(),
                                "." + person.path().getFileName() + "-",
                                person.content()));
            }
            if (!started) {
                files.publish(record, "." + project + "-removing-", content + "removing=true\n");
            }
            for (String name : names) {
                forge.request("DELETE", "/repos/" + project + "/" + name, null, 204);
            }
            for (String path : packagePaths) {
                forge.request("DELETE", path, null, 204);
            }
            if (organization) {
                forge.request("DELETE", "/orgs/" + project, null, 204);
            }
            if (user) {
                forge.request("DELETE", "/admin/users/" + publisher, null, 204);
            }
            for (String path : List.of("/orgs/" + project, "/users/" + publisher)) {
                if (forge.request("GET", path, null, 200, 404) != null) {
                    throw new HostRefusal(
                            65,
                            "the forge still holds an identity for "
                                    + project
                                    + "; keeping its record");
                }
            }
            for (var entry : staged.entrySet()) {
                files.replace(entry.getValue(), entry.getKey());
            }
            for (Path path : publications) {
                files.delete(path);
            }
            ProjectArrivals.removeProject(files, project);
            for (Path path : recorded) {
                files.delete(path);
            }
            if (arrived) {
                files.delete(arrivals);
            }
            keys.drop(project);
            files.delete(record);
        } finally {
            for (Path temporary : staged.values()) {
                files.delete(temporary);
            }
        }
        final var answer = ProjectRecords.object("project", project, "removed", true);
        if (!left.isEmpty()) {
            answer.put("left_at_central", left);
        }
        return answer;
    }

    @FunctionalInterface
    interface TokenInput {
        char[] read() throws IOException;
    }

    private void checkPush(String project, List<String> names, TokenInput tokenInput)
            throws IOException {
        final Path push = files.at("/usr/sbin/porelo-host");
        files.trusted(push, false);
        final char[] token = tokenInput.read();
        byte[] input = new byte[0];
        try {
            final java.nio.ByteBuffer encoded =
                    java.nio.charset.StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(token));
            try {
                input = new byte[encoded.remaining() + 1];
                encoded.get(input, 0, input.length - 1);
                input[input.length - 1] = '\n';
            } finally {
                Arrays.fill(encoded.array(), (byte) 0);
            }
            final List<String> argv =
                    new ArrayList<>(List.of(push.toString(), "project", "check-central", project));
            argv.addAll(names);
            final HostPrograms.Result checked =
                    runtime.run(argv, input, ProjectRuntime.SAFE_ENV, Duration.ofSeconds(600));
            if (checked.exitCode() != 0) {
                final int code =
                        switch (checked.exitCode()) {
                            case 65 -> 65;
                            case 73 -> 73;
                            case 75, 130, 143 -> 64;
                            default -> 69;
                        };
                throw new HostRefusal(
                        code,
                        project
                                + " has unpushed or unverifiable commits in "
                                + String.join(", ", names)
                                + "; gathered push check exited "
                                + checked.exitCode());
            }
        } finally {
            Arrays.fill(token, '\0');
            Arrays.fill(input, (byte) 0);
        }
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }
}
