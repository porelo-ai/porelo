package org.fuin.porelo.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Private, short-lived copies of central main, handed to the forge account. */
final class ProjectCopies {
    private final ProjectFiles files;
    private final ProjectRuntime runtime;
    private final ProjectReadKeys keys;

    ProjectCopies(ProjectFiles files, ProjectRuntime runtime) {
        this.files = files;
        this.runtime = runtime;
        keys = new ProjectReadKeys(files, runtime);
    }

    Path directory() {
        return files.at("/run/porelo-host-project-copy");
    }

    Map<String, @Nullable Object> copy(String project, String address, String known)
            throws IOException {
        final ProjectAddress wanted = ProjectAddress.parse(address);
        final ProjectReadKeys.Manifest manifest = keys.manifest(project);
        final Map<String, @Nullable Object> item = requireKey(manifest, wanted.repository());
        if (!ProjectAddress.parse(ProjectReadKeys.address(item)).https().equals(wanted.https())) {
            throw new HostRefusal(65, "the project's recorded central address differs");
        }
        ProjectFiles.placing(
                "making the copies' directory",
                () -> {
                    files.mkdir(directory(), 0711);
                    Files.setAttribute(directory(), "unix:mode", 0711, LinkOption.NOFOLLOW_LINKS);
                });
        for (Path left : files.list(directory())) {
            try {
                files.removeTree(left);
            } catch (IOException ex) {
                // Like rmtree(ignore_errors=True), an old owner's racing tree cannot block a copy.
            }
        }
        final String commit = keys.centralMain(manifest.directory(), item);
        if (commit == null) {
            throw new HostRefusal(69, "the central forge holds no main of " + wanted.repository());
        }
        if (commit.equals(known)) {
            final var answer = ProjectRecords.object("commit", commit);
            answer.put("copy", null);
            return answer;
        }
        final byte[] random = new byte[16];
        new java.security.SecureRandom().nextBytes(random);
        final Path secret = directory().resolve(java.util.HexFormat.of().formatHex(random));
        ProjectFiles.placing("making the copy's directory", () -> files.mkdir(secret, 0700));
        final Path path = secret.resolve("central.git");
        keys.git(
                List.of(
                        "clone",
                        "--bare",
                        "--single-branch",
                        "--branch",
                        "main",
                        "--",
                        ProjectAddress.parse(ProjectReadKeys.address(item)).ssh(),
                        path.toString()),
                Map.of("GIT_SSH_COMMAND", keys.ssh(manifest.directory(), item)),
                Duration.ofSeconds(30));
        final ProjectRecords records = new ProjectRecords(files);
        final String account =
                ProjectRecords.setting(
                        records.values(records.conf(), 69),
                        "account",
                        ProjectRecords.ACCOUNT,
                        records.conf(),
                        69);
        final HostPrograms.Result passwd =
                runtime.run(
                        List.of(runtime.checked("/usr/bin/getent").toString(), "passwd", account),
                        new byte[0],
                        ProjectRuntime.SAFE_ENV,
                        Duration.ZERO);
        final String[] fields = passwd.output().strip().split(":", -1);
        if (passwd.exitCode() != 0
                || fields.length != 7
                || !fields[0].equals(account)
                || !fields[2].matches("[1-9][0-9]*")
                || !fields[3].matches("[0-9]+")) {
            throw new HostRefusal(
                    69, "the forge's account " + account + " is not known, or is root");
        }
        ProjectFiles.placing(
                "handing the copy to the forge's account",
                () ->
                        files.giveTree(
                                secret, Integer.parseInt(fields[2]), Integer.parseInt(fields[3])));
        return ProjectRecords.object("commit", commit, "copy", path.toString());
    }

    static Map<String, @Nullable Object> requireKey(
            ProjectReadKeys.Manifest manifest, String name) {
        final Map<String, @Nullable Object> item = manifest.find(name, false);
        if (item == null || ProjectReadKeys.id(item) <= 0) {
            throw new HostRefusal(69, "the project has no read key for " + name);
        }
        return item;
    }
}
