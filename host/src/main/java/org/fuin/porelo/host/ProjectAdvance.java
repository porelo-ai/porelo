package org.fuin.porelo.host;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Advances only the file Porelo verified; every container Git write has a private umask. */
final class ProjectAdvance {
    private static final String CENTRAL = "refs/porelo/central";
    private final ProjectFiles files;
    private final ProjectRuntime runtime;
    private final String account;
    private final String project;
    private final String name;
    private final String directory;

    ProjectAdvance(
            ProjectFiles files,
            ProjectRuntime runtime,
            String account,
            String project,
            String name) {
        this.files = files;
        this.runtime = runtime;
        this.account = account;
        this.project = project;
        this.name = name.toLowerCase(java.util.Locale.ROOT);
        directory = "/var/lib/gitea/git/repositories/" + project + "/" + this.name + ".git";
    }

    Map<String, @Nullable Object> advance(String named, String commit, Path copy, String reach)
            throws IOException {
        ProjectCopies.requireKey(new ProjectReadKeys(files, runtime).manifest(project), named);
        final Path bundle =
                Files.createTempFile(
                        files.at("/tmp"),
                        "porelo-project-bundle-",
                        "",
                        PosixFilePermissions.asFileAttribute(
                                PosixFilePermissions.fromString("rw-------")));
        try {
            Files.setAttribute(bundle, "unix:mode", 0600, LinkOption.NOFOLLOW_LINKS);
            try (var output = Files.newOutputStream(bundle, LinkOption.NOFOLLOW_LINKS)) {
                runtime.bundle(account, copy, output);
            }
            final String remoteBundle = directory + "/porelo-advance.bundle";
            try {
                require(
                        runtime.exec(
                                account,
                                List.of("install", "-m", "600", "/dev/null", remoteBundle)));
                try (var input = Files.newInputStream(bundle, LinkOption.NOFOLLOW_LINKS)) {
                    require(runtime.exec(account, List.of("tee", remoteBundle), input));
                }
                git("fetch", "-q", "--no-tags", remoteBundle, "+refs/heads/main:" + CENTRAL);
                return ProjectRecords.object("state", move(commit, reach));
            } finally {
                // Cleanup is part of the verdict, including on a refused or failed comparison.
                require(runtime.exec(account, List.of("rm", "-f", remoteBundle)));
                git("update-ref", "-d", CENTRAL);
            }
        } catch (HostRefusal ex) {
            throw ex;
        } catch (IOException ex) {
            throw failure();
        } finally {
            files.delete(bundle);
        }
    }

    private String move(String commit, String reach) throws IOException {
        if (!gitResult("rev-parse", "--verify", "--quiet", CENTRAL + "^{commit}")
                .output()
                .strip()
                .equals(commit)) {
            throw new HostRefusal(65, "the copy no longer holds the commit Porelo verified");
        }
        String signed =
                git("log", "-1", "--first-parent", "--format=%H", CENTRAL, "--", "project.yml")
                        .strip();
        if (signed.isEmpty()) {
            throw new HostRefusal(65, "the copy no longer holds the commit Porelo verified");
        }
        final String trailers =
                git(
                        "log",
                        "--first-parent",
                        "--format=commit %H%n%(trailers:key=Porelo-Signs-Anew,valueonly,unfold)",
                        CENTRAL);
        String hash = "";
        String anew = "";
        for (String line : trailers.split("\n", -1)) {
            if (line.matches("commit [0-9a-f]+")) {
                hash = line.substring(7);
            } else if (line.equals("project.yml") && !hash.isEmpty()) {
                anew = hash;
                break;
            }
        }
        if (!anew.isEmpty() && !anew.equals(signed) && above(signed, anew) && above(anew, commit)) {
            signed = anew;
        }
        final var signedFile =
                gitResult("rev-parse", "--verify", "--quiet", signed + ":project.yml");
        final var verifiedFile =
                gitResult("rev-parse", "--verify", "--quiet", commit + ":project.yml");
        if (signedFile.exitCode() != 0
                || verifiedFile.exitCode() != 0
                || signedFile.output().isBlank()
                || !signedFile.output().strip().equals(verifiedFile.output().strip())) {
            // The shell predecessor equates two missing blobs; neither is a verified file.
            throw new HostRefusal(65, "the copy no longer holds the commit Porelo verified");
        }
        final String local =
                git("rev-parse", "--verify", "--quiet", "refs/heads/main^{commit}").strip();
        if (local.isEmpty()) {
            throw failure();
        }
        boolean tip = reach.equals("tip");
        if (tip) {
            final String file = git("show", commit + ":project.yml");
            final String workflows =
                    git(
                            "ls-tree",
                            "--name-only",
                            commit,
                            "--",
                            ".forgejo/workflows",
                            ".gitea/workflows",
                            ".github/workflows");
            if (file.contains("snippet_file") || !workflows.isBlank()) {
                tip = false;
            }
        }
        final String target = tip ? commit : signed;
        if (local.equals(target)) {
            return "up-to-date";
        }
        if (above(local, target)) {
            if (!tip) {
                final String changed =
                        git("diff", "--name-only", "--no-renames", local, target)
                                .replaceFirst("\\n+$", "");
                if (!changed.isEmpty() && !changed.equals("project.yml")) {
                    return "other-changes";
                }
            }
            git("update-ref", "refs/heads/main", target, local);
            final String update = local + " " + target + " refs/heads/main\n";
            final int told;
            try (var input = new ByteArrayInputStream(update.getBytes(StandardCharsets.US_ASCII))) {
                told =
                        runtime.exec(
                                        account,
                                        List.of(
                                                "env",
                                                "-C",
                                                directory,
                                                "SSH_ORIGINAL_COMMAND=internal",
                                                "GITEA_REPO_USER_NAME=" + project,
                                                "GITEA_REPO_NAME=" + name,
                                                "GITEA_REPO_IS_WIKI=false",
                                                "GITEA_PUSHER_NAME=porelo-admin",
                                                "GITEA_PUSHER_ID=1",
                                                "GITEA_PR_ID=0",
                                                "forgejo",
                                                "hook",
                                                "post-receive"),
                                        input)
                                .exitCode();
            }
            return told == 0 ? "moved" : "moved-unseen";
        }
        return above(target, local) ? "ahead" : "diverged";
    }

    private boolean above(String first, String second) throws IOException {
        final int status = gitResult("merge-base", "--is-ancestor", first, second).exitCode();
        if (status != 0 && status != 1) {
            throw failure();
        }
        return status == 0;
    }

    private String git(String... arguments) throws IOException {
        return require(gitResult(arguments));
    }

    private HostPrograms.Result gitResult(String... arguments) throws IOException {
        final List<String> argv =
                new ArrayList<>(
                        List.of(
                                "sh",
                                "-c",
                                "umask 077; exec \"$@\"",
                                "porelo-project",
                                "git",
                                "-c",
                                "core.sharedRepository=0600",
                                "--git-dir=" + directory));
        argv.addAll(List.of(arguments));
        return runtime.exec(account, argv);
    }

    private String require(HostPrograms.Result result) {
        if (result.exitCode() != 0) {
            throw failure();
        }
        return result.output();
    }

    private HostRefusal failure() {
        return new HostRefusal(
                69, "the local forge could not bring " + name + " up to the commit verified");
    }
}
