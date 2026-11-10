package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostProjectAdvanceTest {
    @TempDir Path root;
    ProjectFixture host;
    Path work;
    Path localPath;
    Path copy;
    Set<Path> originalObjects;
    String first;
    String second;
    String told = "";
    String fail = "";
    int hookStatus;
    final List<List<String>> commands = new ArrayList<>();
    final List<List<String>> transfers = new ArrayList<>();
    static final String FOLDER = "/var/lib/gitea/git/repositories/alpha/code.git";

    @BeforeEach
    void start() throws Exception {
        host = new ProjectFixture(root);
        host.add();
        host.readKeys();
        work = root.resolve("central");
        Files.createDirectory(work);
        ProjectTestGit.git(work, "init", "-q", "-b", "main");
        first = commit("project.yml", "first");
        localPath = root.resolve("local.git");
        ProjectTestGit.git(root, "clone", "-q", "--bare", work.toString(), localPath.toString());
        try (var objects = Files.walk(localPath.resolve("objects"))) {
            originalObjects = new HashSet<>(objects.toList());
        }
        second = commit("project.yml", "second");
        copy =
                new ProjectCopies(host.files, host.runtime)
                        .directory()
                        .resolve("d".repeat(32) + "/central.git");
        Files.createDirectories(copy.getParent());
    }

    String commit(String file, String text) throws Exception {
        final Path path = work.resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
        ProjectTestGit.git(work, "add", ".");
        ProjectTestGit.git(
                work, "-c", "commit.gpgsign=false", "commit", "-q", "-m", file + " " + text);
        return tip();
    }

    String trailer(String value) throws Exception {
        ProjectTestGit.git(
                work,
                "-c",
                "commit.gpgsign=false",
                "commit",
                "-q",
                "--allow-empty",
                "-m",
                "Sign anew\n\nPorelo-Signs-Anew: " + value + "\n");
        return tip();
    }

    String main() throws Exception {
        return ProjectTestGit.git(localPath, "rev-parse", "refs/heads/main");
    }

    String tip() throws Exception {
        return ProjectTestGit.git(work, "rev-parse", "HEAD");
    }

    void localAt(String commit) throws Exception {
        ProjectTestGit.git(localPath, "fetch", "-q", work.toString(), "main:main");
        ProjectTestGit.git(localPath, "update-ref", "refs/heads/main", commit);
    }

    String localCommit() throws Exception {
        final String tree = ProjectTestGit.git(localPath, "rev-parse", "main^{tree}");
        final String made =
                ProjectTestGit.git(localPath, "commit-tree", tree, "-p", main(), "-m", "local");
        ProjectTestGit.git(localPath, "update-ref", "refs/heads/main", made);
        return made;
    }

    List<String> map(List<String> argv) {
        final List<String> result = new ArrayList<>();
        final int start =
                argv.contains("exec") ? argv.indexOf("forgejo") + 1 : argv.indexOf("/usr/bin/git");
        for (String value : argv.subList(start, argv.size())) {
            result.add(value.replace(FOLDER, localPath.toString()));
        }
        if (!result.getFirst().startsWith("/")) {
            result.set(0, "/usr/bin/" + result.getFirst());
        }
        return result;
    }

    HostPrograms.Result run(
            List<String> argv, byte[] input, Map<String, String> env, Duration timeout)
            throws IOException {
        commands.add(List.copyOf(argv));
        if (argv.getFirst().equals("/usr/bin/id")) {
            return new HostPrograms.Result(0, "1234\n");
        }
        if (argv.getFirst().equals("/usr/bin/getent")) {
            return new HostPrograms.Result(0, "forge:x:1234:1234::/home/forge:/bin/false\n");
        }
        final var mapped = map(argv);
        if (!fail.isEmpty() && String.join(" ", mapped).contains(fail)) {
            return new HostPrograms.Result(128, "");
        }
        return new HostPrograms(timeout, 1024 * 1024, Path::of).run(mapped, input, env);
    }

    HostPrograms.Result transfer(
            List<String> argv,
            InputStream input,
            OutputStream output,
            Map<String, String> env,
            Duration timeout)
            throws IOException {
        transfers.add(List.copyOf(argv));
        assertThat(env).isEqualTo(ProjectRuntime.SAFE_ENV);
        if (argv.contains("post-receive")) {
            told = new String(input.readAllBytes(), StandardCharsets.US_ASCII);
            return new HostPrograms.Result(hookStatus, "");
        }
        return new HostPrograms(timeout, 1024 * 1024, Path::of)
                .transfer(map(argv), input, output, env);
    }

    ProjectFixture.Answer advance(String commit, String reach) throws Exception {
        ProjectTestGit.git(root, "clone", "-q", "--bare", work.toString(), copy.toString());
        final var runtime =
                new ProjectRuntime(
                        host.files,
                        this::run,
                        Path::of,
                        path -> true,
                        (socket, token) -> {
                            throw new AssertionError("advance makes no HTTP request or token");
                        },
                        this::transfer);
        final var command =
                new HostProject(
                        host.files,
                        runtime,
                        (origin, token) -> {
                            throw new AssertionError();
                        },
                        ProjectLock::acquire);
        final var out = new StringWriter();
        final var err = new StringWriter();
        final int code =
                command.run(
                        "project-advance",
                        List.of("alpha", "code", commit, copy.toString(), reach),
                        InputStream.nullInputStream(),
                        new PrintWriter(out),
                        new PrintWriter(err));
        return new ProjectFixture.Answer(code, out.toString(), err.toString());
    }

    void state(String expected, String commit, String reach) throws Exception {
        final var answer = advance(commit, reach);
        assertThat(answer.code()).as(answer.err()).isZero();
        assertThat(answer.out()).isEqualTo("{\"state\":\"" + expected + "\"}\n");
        clean();
    }

    void clean() throws Exception {
        assertThat(ProjectTestGit.git(localPath, "for-each-ref", "--format=%(refname)"))
                .isEqualTo("refs/heads/main");
        assertThat(localPath.resolve("porelo-advance.bundle")).doesNotExist();
        try (var files = Files.list(host.files.at("/tmp"))) {
            assertThat(files.toList()).isEmpty();
        }
    }

    @Test
    void theVerifiedFileMovesAndNotifiesForgejo() throws Exception {
        state("moved", second, "signed");
        assertThat(main()).isEqualTo(second);
        assertThat(
                        (int) Files.getAttribute(localPath.resolve("refs/heads/main"), "unix:mode")
                                & 0777)
                .isEqualTo(0600);
        try (var objects = Files.walk(localPath.resolve("objects"))) {
            assertThat(
                            objects.filter(Files::isRegularFile)
                                    .filter(path -> !originalObjects.contains(path))
                                    .toList())
                    .allSatisfy(
                            path -> {
                                try {
                                    assertThat((int) Files.getAttribute(path, "unix:mode") & 0077)
                                            .isZero();
                                } catch (IOException ex) {
                                    throw new AssertionError(ex);
                                }
                            });
        }
        assertThat(told).isEqualTo(first + " " + second + " refs/heads/main\n");
        assertThat(transfers.getLast())
                .contains(
                        "GITEA_REPO_NAME=code",
                        "GITEA_REPO_USER_NAME=alpha",
                        "GITEA_PUSHER_ID=1",
                        FOLDER);
    }

    @Test
    void codeAboveTheSignedFileIsLeftBehind() throws Exception {
        final String tip = commit("build.sh", "anyone's");
        state("moved", tip, "signed");
        assertThat(main()).isEqualTo(second).isNotEqualTo(tip);
    }

    @Test
    void codeBelowTheSignedFileHoldsMain() throws Exception {
        commit("build.sh", "anyone's");
        final String tip = commit("project.yml", "third");
        state("other-changes", tip, "signed");
        assertThat(main()).isEqualTo(first);
        assertThat(told).isEmpty();
    }

    @Test
    void aNewSignatureIsTheCommitJudged() throws Exception {
        final String signed = trailer("project.yml");
        state("moved", signed, "signed");
        assertThat(main()).isEqualTo(signed);
    }

    @Test
    void theLaterFileChangeWinsOverAnOlderSignature() throws Exception {
        trailer("project.yml");
        final String changed = commit("project.yml", "third");
        state("moved", changed, "signed");
        assertThat(main()).isEqualTo(changed);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "other.txt project.yml",
                "other.txt\n {below} project.yml",
                "project.yml\rnot-the-file"
            })
    void aSecondWordOrFoldedTrailerSignsNothing(String value) throws Exception {
        final String below = commit("build.sh", "anyone's");
        final String tip = trailer(value.replace("{below}", below));
        state("moved", tip, "signed");
        assertThat(main()).isEqualTo(second);
    }

    @Test
    void aDifferentFileWhoseNameHasSpacesIsNotTheSignedFile() throws Exception {
        localAt(second);
        commit(" project.yml ", "unjudged");
        final String signed = trailer("project.yml");
        state("other-changes", signed, "signed");
        assertThat(main()).isEqualTo(second);
    }

    @Test
    void aSignatureVouchesForNoCodeBelowIt() throws Exception {
        commit("build.sh", "anyone's");
        final String tip = trailer("project.yml");
        state("other-changes", tip, "signed");
        assertThat(main()).isEqualTo(first);
        assertThat(told).isEmpty();
    }

    @Test
    void anUnusedRepositoryFollowsTheTip() throws Exception {
        commit("plan.md", "by hand");
        commit("project.yml", "third");
        final String tip = commit("plan.md", "again");
        state("moved", tip, "tip");
        assertThat(main()).isEqualTo(tip);
        assertThat(told).contains(tip);
    }

    @ParameterizedTest
    @ValueSource(strings = {".forgejo/workflows", ".gitea/workflows", ".github/workflows"})
    void aWorkflowHoldsEvenAnUnusedRepositoryToTheFile(String folder) throws Exception {
        final String tip = commit(folder + "/run.yml", "on: push");
        state("moved", tip, "tip");
        assertThat(main()).isEqualTo(second).isNotEqualTo(tip);
    }

    @Test
    void aRemovedWorkflowAllowsFollowingAgain() throws Exception {
        commit(".forgejo/workflows/run.yml", "on: push");
        ProjectTestGit.git(work, "rm", "-q", "-r", ".forgejo");
        ProjectTestGit.git(work, "-c", "commit.gpgsign=false", "commit", "-q", "-m", "gone");
        state("moved", tip(), "tip");
        assertThat(main()).isEqualTo(tip());
    }

    @Test
    void anImageSnippetNeverFollowsUnjudgedCode() throws Exception {
        final String signed = commit("project.yml", "image:\n  snippet_file: fragment\n");
        final String tip = commit("fragment", "RUN anything");
        state("moved", tip, "tip");
        assertThat(main()).isEqualTo(signed).isNotEqualTo(tip);
    }

    @Test
    void followingTheTipNeverTakesLocalWorkBack() throws Exception {
        final String made = localCommit();
        state("diverged", tip(), "tip");
        assertThat(main()).isEqualTo(made);
    }

    @Test
    void aFailedNotificationDoesNotHideTheMovedRef() throws Exception {
        hookStatus = 1;
        state("moved-unseen", second, "signed");
        assertThat(main()).isEqualTo(second);
    }

    @Test
    void anAlreadyPresentCopyNeedsNoNotification() throws Exception {
        localAt(second);
        state("up-to-date", second, "signed");
        assertThat(main()).isEqualTo(second);
        assertThat(told).isEmpty();
    }

    @Test
    void approvedLocalWorkAheadOfCentralStays() throws Exception {
        localAt(second);
        final String made = localCommit();
        state("ahead", second, "signed");
        assertThat(main()).isEqualTo(made);
    }

    @Test
    void divergedLocalWorkIsKeptForAPersonToJoin() throws Exception {
        final String made = localCommit();
        state("diverged", second, "signed");
        assertThat(main()).isEqualTo(made);
    }

    @Test
    void aChangedCopyIsRefusedBeforeMoving() throws Exception {
        assertThat(advance("e".repeat(40), "signed").code()).isEqualTo(65);
        assertThat(main()).isEqualTo(first);
        clean();
    }

    @Test
    void gitFailureIsNoDivergence() throws Exception {
        fail = "merge-base";
        final var answer = advance(second, "signed");
        assertThat(answer.code()).as(answer.err()).isEqualTo(69);
        assertThat(answer.out()).doesNotContain("diverged");
        assertThat(main()).isEqualTo(first);
        clean();
    }

    @Test
    void aLeftoverTemporaryRefRefusesSuccess() throws Exception {
        fail = "update-ref -d";
        final var answer = advance(second, "signed");
        assertThat(answer.code()).as(answer.err()).isEqualTo(69);
        assertThat(answer.out()).isEmpty();
        assertThat(ProjectTestGit.git(localPath, "rev-parse", "refs/porelo/central"))
                .isEqualTo(second);
    }

    @Test
    void theOwnerBundlesAndOnlyTransfersInheritStdin() throws Exception {
        state("moved", second, "signed");
        assertThat(transfers.getFirst())
                .startsWith(
                        "/usr/sbin/runuser",
                        "-u",
                        "forge",
                        "--",
                        "/usr/bin/env",
                        "-C",
                        "/",
                        "/usr/bin/git")
                .contains("--git-dir=" + copy, "bundle", "create", "-", "refs/heads/main");
        assertThat(transfers.subList(1, transfers.size()))
                .allSatisfy(
                        argv ->
                                assertThat(
                                                argv.subList(
                                                        argv.indexOf("exec"),
                                                        argv.indexOf("exec") + 3))
                                        .containsExactly("exec", "-i", "forgejo"));
        assertThat(commands.stream().filter(argv -> argv.contains("exec")))
                .allSatisfy(argv -> assertThat(argv).doesNotContain("-i", "bash"));
        assertThat(commands.stream().filter(argv -> argv.contains("git")))
                .allSatisfy(
                        argv ->
                                assertThat(argv)
                                        .containsSubsequence(
                                                "sh",
                                                "-c",
                                                "umask 077; exec \"$@\"",
                                                "porelo-project",
                                                "git"));
    }

    @Test
    void aDeletedProjectFileCannotAdvanceMain() throws Exception {
        Files.delete(work.resolve("project.yml"));
        ProjectTestGit.git(work, "add", ".");
        ProjectTestGit.git(work, "commit", "-q", "-m", "remove project file");
        final var answer = advance(tip(), "signed");
        assertThat(answer.code()).as(answer.err()).isEqualTo(65);
        assertThat(main()).isEqualTo(first);
        assertThat(told).isEmpty();
        clean();
    }

    @Test
    void everyFileCreatedByAdvanceIsPrivateIncludingFetchHead() throws Exception {
        final Set<Path> before;
        try (var paths = Files.walk(localPath)) {
            before = new HashSet<>(paths.toList());
        }
        state("moved", second, "signed");
        try (var paths = Files.walk(localPath)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (!before.contains(path)) {
                    assertThat((int) Files.getAttribute(path, "unix:mode") & 0077)
                            .as("new file %s", localPath.relativize(path))
                            .isZero();
                }
            }
        }
        assertThat(localPath.resolve("FETCH_HEAD")).exists();
    }
}
