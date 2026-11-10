package org.fuin.porelo.host;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.organization.gittree.HttpForgeAdmin;

/** Root's full-ref comparison with central before a project can be removed. */
final class ProjectCentralCheck {
    private final ProjectFiles files;
    private final ProjectRuntime runtime;

    ProjectCentralCheck(ProjectFiles files, ProjectRuntime runtime) {
        this.files = files;
        this.runtime = runtime;
    }

    void check(
            List<String> arguments,
            Map<String, String> environment,
            InputStream input,
            PrintWriter err)
            throws IOException {
        if (!environment.getOrDefault("SUDO_USER", "").isEmpty()
                || !environment.getOrDefault("SUDO_UID", "").isEmpty()) {
            throw new HostRefusal(77, "--check runs only as root without sudo");
        }
        if (arguments.size() < 2) {
            throw new HostRefusal(64, "--check needs an organization and its repository names");
        }
        final String project = arguments.getFirst();
        if (!project.matches(ProjectRecords.PROJECT)) {
            throw new HostRefusal(64, "--check needs a valid project organization");
        }
        final var records = new ProjectRecords(files);
        final var values = records.values(records.conf(), 69);
        final String port =
                ProjectRecords.setting(values, "git_port", "[1-9][0-9]{0,4}", records.conf(), 69);
        if (Integer.parseInt(port) > 65535) {
            throw new HostRefusal(69, records.conf() + " names no valid git_port");
        }
        final List<String> targets = arguments.subList(1, arguments.size());
        final var named = new HashSet<String>();
        for (String name : targets) {
            if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
                throw new HostRefusal(64, "'" + name + "' is not a repository name");
            }
            if (!named.add(name)) {
                throw new HostRefusal(64, "'" + name + "' is named twice");
            }
        }
        final String account =
                ProjectRecords.setting(
                        values, "account", ProjectRecords.ACCOUNT, records.conf(), 69);
        final Path ca = authority();
        final var passwd =
                runtime.run(
                        List.of(runtime.checked("/usr/bin/getent").toString(), "passwd", account),
                        new byte[0],
                        ProjectRuntime.SAFE_ENV,
                        Duration.ZERO);
        final String[] fields = passwd.output().strip().split(":", -1);
        if (passwd.exitCode() != 0
                || fields.length != 7
                || !fields[2].matches("[1-9][0-9]*")
                || !fields[5].startsWith("/")
                || fields[5].equals("/")) {
            throw new HostRefusal(
                    69, "the forge's account " + account + " has no unprivileged home");
        }
        final String tokenPath = fields[5] + "/.config/porelo-forge/api.token";
        final HostPrograms.Result read;
        try {
            read =
                    runtime.run(
                            List.of(
                                    runtime.checked("/usr/sbin/runuser").toString(),
                                    "-u",
                                    account,
                                    "--",
                                    runtime.checked("/usr/bin/env").toString(),
                                    "-C",
                                    "/",
                                    "HOME=" + fields[5],
                                    runtime.checked("/usr/bin/cat").toString(),
                                    "--",
                                    tokenPath),
                            new byte[0],
                            ProjectRuntime.SAFE_ENV,
                            Duration.ofSeconds(10));
        } catch (IOException ex) {
            throw new HostRefusal(66, tokenPath + " could not be read");
        }
        if (read.exitCode() != 0) {
            throw new HostRefusal(66, tokenPath + " could not be read");
        }
        final String local = read.output().replaceFirst("\\n+$", "");
        if (local.isEmpty()) {
            throw new HostRefusal(66, tokenPath + " is empty");
        }
        if (local.contains("\r") || local.contains("\n")) {
            throw new HostRefusal(69, "invalid local forge token");
        }
        err.print("Token for the central forge (not shown): ");
        err.flush();
        final char[] central = centralToken(input);
        final char[] localToken = local.toCharArray();
        try {
            if (!(input == System.in && System.console() != null)) {
                err.println();
                err.flush();
            }
            if (central.length == 0) {
                throw new HostRefusal(75, "no token was given");
            }
            final Map<String, String> urls = new HashMap<>();
            try (var forge = runtime.open(files.at("/run/porelo-forge/git.sock"), localToken)) {
                for (Map<?, ?> repository : forge.listing("/orgs/" + project + "/repos")) {
                    if (!(repository.get("name") instanceof String name)
                            || badField(name)
                            || repository.get("original_url") != null
                                    && !(repository.get("original_url") instanceof String)) {
                        throw new HostRefusal(
                                69,
                                "the forge sent invalid JSON for /api/v1/orgs/"
                                        + project
                                        + "/repos");
                    }
                    final String url =
                            repository.get("original_url") instanceof String value ? value : "";
                    if (badField(url)) {
                        throw new HostRefusal(
                                69,
                                "the forge sent invalid JSON for /api/v1/orgs/"
                                        + project
                                        + "/repos");
                    }
                    urls.put(name, url);
                }
            } catch (HttpForgeAdmin.Refusal ex) {
                throw new HostRefusal(
                        69,
                        java.util.Objects.requireNonNull(
                                ex.getMessage(), "Forge refusals have a safe diagnostic"));
            }
            for (String name : targets) {
                if (!urls.containsKey(name)) {
                    throw new HostRefusal(
                            69, name + ": the forge holds no such repository in " + project);
                }
                if (!urls.get(name).matches("https://[A-Za-z0-9.-]+(:[0-9]{1,5})?/[^\\s?#@]+")) {
                    throw new HostRefusal(
                            69,
                            name
                                    + ": the forge holds no central https:// original_url from migration");
                }
            }
            final Path scratch =
                    Files.createTempDirectory(
                            files.at("/tmp"),
                            "porelo-check-central-",
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rwx------")));
            try {
                Files.setAttribute(
                        scratch, "unix:mode", 0700, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                for (String name : targets) {
                    final Path repo = scratch.resolve(name);
                    final String localUrl =
                            "http://127.0.0.1:" + port + "/" + project + "/" + name + ".git";
                    git(
                            name,
                            "local refs could not be read",
                            List.of(
                                    "clone",
                                    "--quiet",
                                    "--mirror",
                                    "--",
                                    localUrl,
                                    repo.toString()),
                            localToken,
                            null);
                    final List<String> localRefs = refs(name, repo, "local", "");
                    git(
                            name,
                            "central refs could not be read",
                            List.of(
                                    "-C",
                                    repo.toString(),
                                    "fetch",
                                    "--quiet",
                                    "--no-tags",
                                    urls.get(name),
                                    "+refs/*:refs/porelo-central/*"),
                            central,
                            ca);
                    final List<String> centralRefs =
                            refs(name, repo, "central", "refs/porelo-central/");
                    if (!localRefs.isEmpty()) {
                        final var argv =
                                new ArrayList<>(
                                        List.of(
                                                "-C",
                                                repo.toString(),
                                                "rev-list",
                                                "--max-count=1"));
                        argv.addAll(localRefs);
                        argv.add("--not");
                        argv.addAll(centralRefs);
                        if (!git(
                                        name,
                                        "local and central commits could not be compared",
                                        argv,
                                        null,
                                        null)
                                .isBlank()) {
                            throw new HostRefusal(
                                    65, name + ": unpushed commits remain on the local forge");
                        }
                    }
                }
            } finally {
                files.removeTree(scratch);
            }
        } finally {
            Arrays.fill(central, '\0');
            Arrays.fill(localToken, '\0');
        }
    }

    private static boolean badField(String text) {
        return text.chars().anyMatch(value -> value == '\t' || value == '\r' || value == '\n');
    }

    private static char[] centralToken(InputStream input) throws IOException {
        if (input == System.in && System.console() != null) {
            final char[] value =
                    java.util.Objects.requireNonNull(System.console(), "Terminal was checked")
                            .readPassword();
            if (value == null) {
                throw new HostRefusal(75, "no token was given");
            }
            return value;
        }
        final byte[] bytes = new byte[4097];
        int size = 0;
        try {
            int next;
            while ((next = input.read()) != -1 && next != '\n') {
                if (size == 4096) {
                    throw new HostRefusal(64, "central forge token exceeds read limit");
                }
                bytes[size++] = (byte) next;
            }
            if (size == 0 && next != '\n') {
                throw new HostRefusal(75, "no token was given");
            }
            return new String(bytes, 0, size, StandardCharsets.UTF_8).toCharArray();
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private Path authority() throws IOException {
        final Path conf = files.etc.resolve("forge/central-ca.conf");
        Path ca = files.at("/etc/ssl/certs/ca-certificates.crt");
        if (files.exists(conf)) {
            try {
                files.trusted(conf, false);
            } catch (HostRefusal ex) {
                throw new HostRefusal(66, conf + " is not a plain file only root can change");
            }
            final String content = files.text(conf, false).replaceFirst("\\n$", "");
            if (!content.matches("/[^\\s]+")) {
                throw new HostRefusal(65, conf + " names no single absolute CA path");
            }
            ca = Path.of(content);
        }
        try {
            ca =
                    org.fuin.porelo.wire.RootOwnedAuthority.resolve(
                            ca,
                            files.root,
                            files.owner,
                            path ->
                                    Files.readAttributes(
                                            path,
                                            "unix:uid,mode",
                                            java.nio.file.LinkOption.NOFOLLOW_LINKS));
            files.trusted(ca, false);
        } catch (HostRefusal | IOException | IllegalArgumentException ex) {
            throw new HostRefusal(
                    66, "the central forge's CA " + ca + " is not a file only root can change");
        }
        return ca;
    }

    private List<String> refs(String name, Path repo, String side, String prefix)
            throws IOException {
        final var arguments =
                new ArrayList<>(
                        List.of("-C", repo.toString(), "for-each-ref", "--format=%(objectname)"));
        if (!prefix.isEmpty()) {
            arguments.add(prefix);
        }
        final String text =
                git(name, side + " refs could not be enumerated", arguments, null, null);
        final List<String> refs = text.lines().toList();
        if (refs.stream().anyMatch(ref -> !ref.matches(ProjectRecords.COMMIT))) {
            throw new HostRefusal(69, name + ": " + side + " refs could not be enumerated");
        }
        return refs;
    }

    private String git(
            String name,
            String fault,
            List<String> arguments,
            char @org.jspecify.annotations.Nullable [] token,
            @org.jspecify.annotations.Nullable Path ca)
            throws IOException {
        final var env = new LinkedHashMap<>(ProjectRuntime.SAFE_ENV);
        env.putAll(
                Map.of(
                        "GIT_CONFIG_GLOBAL",
                        "/dev/null",
                        "GIT_CONFIG_NOSYSTEM",
                        "1",
                        "GIT_TERMINAL_PROMPT",
                        "0"));
        if (token != null) {
            final byte[] bytes =
                    ("x-access-token:" + new String(token)).getBytes(StandardCharsets.UTF_8);
            try {
                env.putAll(
                        Map.of(
                                "GIT_CONFIG_COUNT",
                                "2",
                                "GIT_CONFIG_KEY_0",
                                "http.extraHeader",
                                "GIT_CONFIG_VALUE_0",
                                "Authorization: Basic " + Base64.getEncoder().encodeToString(bytes),
                                "GIT_CONFIG_KEY_1",
                                "http.followRedirects",
                                "GIT_CONFIG_VALUE_1",
                                "false"));
            } finally {
                Arrays.fill(bytes, (byte) 0);
            }
        }
        if (ca != null) {
            env.put("GIT_SSL_CAINFO", ca.toString());
        }
        final var argv = new ArrayList<>(List.of(runtime.checked("/usr/bin/git").toString()));
        // The scratch repository is removed when the check ends; a maintenance run Git detaches
        // after a fetch
        // would still be writing into it while it is removed.
        argv.addAll(List.of("-c", "maintenance.auto=false", "-c", "gc.auto=0"));
        argv.addAll(arguments);
        final HostPrograms.Result result;
        try {
            result =
                    arguments.getFirst().equals("clone")
                            ? runtime.privateFiles(argv, new byte[0], env, Duration.ZERO)
                            : runtime.run(argv, new byte[0], env, Duration.ZERO);
        } catch (IOException ex) {
            throw new HostRefusal(69, name + ": " + fault);
        }
        if (result.exitCode() != 0) {
            throw new HostRefusal(69, name + ": " + fault);
        }
        return result.output();
    }
}
