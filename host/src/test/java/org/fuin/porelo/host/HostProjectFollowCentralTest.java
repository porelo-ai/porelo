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

/** The follow brings a carried repository's copy up to central's main where it only lags. */
class HostProjectFollowCentralTest {
    static final String FORGE = "/var/lib/gitea/git/repositories";
    @TempDir Path root;
    ProjectFixture host;
    Path central;
    Path forge;
    String told = "";
    String fail = "";
    String slowAt = "";
    long slowBy;
    final List<List<String>> commands = new ArrayList<>();
    final java.util.concurrent.atomic.AtomicReference<java.time.Instant> now =
            new java.util.concurrent.atomic.AtomicReference<>(
                    java.time.Instant.parse("2026-11-15T10:00:00Z"));
    final java.time.Clock clock =
            new java.time.Clock() {
                @Override
                public java.time.ZoneId getZone() {
                    return java.time.ZoneOffset.UTC;
                }

                @Override
                public java.time.Clock withZone(java.time.ZoneId zone) {
                    return this;
                }

                @Override
                public java.time.Instant instant() {
                    return now.get();
                }
            };

    @BeforeEach
    void start() throws Exception {
        host = new ProjectFixture(root);
        host.add();
        host.readKeys();
        host.write(
                "/etc/porelo-host/organization/applied.json",
                "{\"revoked\":[],\"persons\":[{\"login\":\"alice\",\"revoked\":false,\"expires\":null},{\"login\":\"bob\",\"revoked\":false,\"expires\":null}],\"projects\":[{\"project\":\"alpha\",\"members\":[{\"login\":\"alice\",\"locked\":false}]},{\"project\":\"beta\",\"members\":[{\"login\":\"bob\",\"locked\":false}]}]}");
        final Path read = host.manifest().getParent();
        Files.writeString(
                host.manifest(),
                "[{\"name\":\"code\",\"address\":\""
                        + ProjectFixture.ADDRESS
                        + "\",\"key_id\":1},{\"name\":\"planning\",\"address\":\"https://forge.example.org/acme/planning.git\",\"key_id\":2}]\n");
        Files.writeString(read.resolve("planning"), "fixture private material");
        ProjectFixture.mode(read.resolve("planning"), 0600);
        Files.writeString(read.resolve("planning.pub"), ProjectFixture.KEY + "\n");
        ProjectFixture.mode(read.resolve("planning.pub"), 0644);
        central = root.resolve("central");
        forge = root.resolve("forge/alpha");
        Files.createDirectories(forge);
        for (String name : List.of("code", "planning")) {
            final Path work = central.resolve(name);
            Files.createDirectories(work);
            ProjectTestGit.git(work, "init", "-q", "-b", "main");
            commit(name, "project.yml", "first");
            ProjectTestGit.git(
                    root,
                    "clone",
                    "-q",
                    "--bare",
                    work.toString(),
                    forge.resolve(name + ".git").toString());
        }
    }

    String commit(String repository, String file, String text) throws Exception {
        final Path work = central.resolve(repository);
        final Path path = work.resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, text);
        ProjectTestGit.git(work, "add", ".");
        ProjectTestGit.git(
                work, "-c", "commit.gpgsign=false", "commit", "-q", "-m", file + " " + text);
        return ProjectTestGit.git(work, "rev-parse", "HEAD");
    }

    String main(String repository) throws Exception {
        return ProjectTestGit.git(
                forge.resolve(repository + ".git"), "rev-parse", "refs/heads/main");
    }

    String localCommit(String repository) throws Exception {
        final Path copy = forge.resolve(repository + ".git");
        final String tree = ProjectTestGit.git(copy, "rev-parse", "main^{tree}");
        final String made =
                ProjectTestGit.git(copy, "commit-tree", tree, "-p", main(repository), "-m", "own");
        ProjectTestGit.git(copy, "update-ref", "refs/heads/main", made);
        return made;
    }

    List<String> map(List<String> argv) {
        final int start =
                argv.contains("exec") ? argv.indexOf("forgejo") + 1 : argv.indexOf("/usr/bin/git");
        final List<String> result = new ArrayList<>();
        for (String value : argv.subList(start, argv.size())) {
            String mapped = value.replace(FORGE, forge.getParent().toString());
            for (String name : List.of("code", "planning", "betacode")) {
                mapped =
                        mapped.replace(
                                "ssh://git@forge.example.org/acme/" + name + ".git",
                                central.resolve(name).toString());
            }
            result.add(mapped);
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
            return new HostPrograms.Result(
                    0,
                    List.of("alice", "bob").contains(argv.getLast())
                            ? argv.getLast() + ":x:1001:1000::/home/test:/bin/false\n"
                            : "forge:x:1234:1234::/home/forge:/bin/false\n");
        }
        if (argv.getFirst().equals("/usr/bin/setfacl")) {
            return new HostPrograms.Result(0, "");
        }
        final var mapped = map(argv);
        slow(mapped);
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
        commands.add(List.copyOf(argv));
        if (argv.contains("post-receive")) {
            told = new String(input.readAllBytes(), StandardCharsets.US_ASCII);
            return new HostPrograms.Result(0, "");
        }
        final var mapped = map(argv);
        final var result =
                new HostPrograms(timeout, 1024 * 1024, Path::of)
                        .transfer(mapped, input, output, env);
        slow(mapped);
        return result;
    }

    /** A command that takes long on the host: the clock moves while it runs. */
    void slow(List<String> mapped) {
        if (!slowAt.isEmpty() && String.join(" ", mapped).contains(slowAt)) {
            now.set(now.get().plusSeconds(slowBy));
        }
    }

    ProjectRuntime runtime() {
        return new ProjectRuntime(
                host.files,
                this::run,
                Path::of,
                path -> true,
                (socket, token) -> {
                    throw new AssertionError("the follow's move makes no HTTP request");
                },
                this::transfer);
    }

    void measure(List<String> projects, Map<String, String> owns) throws Exception {
        new ProjectMeasurements(host.files, runtime(), clock)
                .measure("forge", projects, List.of(), owns);
    }

    Map<String, Object> published(String project) throws Exception {
        final Map<String, Object> rows = new LinkedHashMap<>();
        for (Object value :
                HostJson.array(
                        HostJson.object(
                                        ProjectRecords.json(
                                                Files.readString(
                                                        host.files.etc.resolve(
                                                                "organization/central/"
                                                                        + project
                                                                        + ".json")),
                                                "fixture",
                                                65))
                                .get("repositories"))) {
            final var row = HostJson.object(value);
            rows.put(HostJson.string(row.get("repository")), row.get("state"));
        }
        return rows;
    }

    Map<String, Object> follow(@org.jspecify.annotations.Nullable String own) throws Exception {
        measure(List.of("alpha"), own == null ? Map.of() : Map.of("alpha", own));
        return published("alpha");
    }

    void beta() throws Exception {
        final Path read = host.files.etc.resolve("organization/read/projects/beta");
        Files.createDirectories(read);
        ProjectFixture.mode(read, 0700);
        Files.writeString(
                read.resolve("repositories.json"),
                "[{\"name\":\"betacode\",\"address\":\"https://forge.example.org/acme/betacode.git\",\"key_id\":3}]\n");
        ProjectFixture.mode(read.resolve("repositories.json"), 0600);
        Files.writeString(read.resolve("betacode"), "fixture private material");
        ProjectFixture.mode(read.resolve("betacode"), 0600);
        Files.writeString(read.resolve("betacode.pub"), ProjectFixture.KEY + "\n");
        ProjectFixture.mode(read.resolve("betacode.pub"), 0644);
        final Path work = central.resolve("betacode");
        Files.createDirectories(work);
        ProjectTestGit.git(work, "init", "-q", "-b", "main");
        commit("betacode", "project.yml", "first");
        Files.createDirectories(forge.resolveSibling("beta"));
        ProjectTestGit.git(
                root,
                "clone",
                "-q",
                "--bare",
                work.toString(),
                forge.resolveSibling("beta").resolve("betacode.git").toString());
    }

    void clean() throws Exception {
        for (String name : List.of("code", "planning")) {
            final Path copy = forge.resolve(name + ".git");
            assertThat(ProjectTestGit.git(copy, "for-each-ref", "--format=%(refname)"))
                    .isEqualTo("refs/heads/main");
            assertThat(copy.resolve("porelo-advance.bundle")).doesNotExist();
        }
        try (var files = Files.list(host.files.at("/tmp"))) {
            assertThat(files.toList()).isEmpty();
        }
    }

    @Test
    void aCarriedRepositoryBehindCentralFollowsIt() throws Exception {
        final String before = main("planning");
        final String tip = commit("planning", "issues/I1-An-Idea.md", "saved by the planner");
        assertThat(follow("code")).containsEntry("planning", "up-to-date");
        assertThat(main("planning")).isEqualTo(tip);
        assertThat(told).isEqualTo(before + " " + tip + " refs/heads/main\n");
        clean();
    }

    @ParameterizedTest
    @ValueSource(strings = {".forgejo/workflows", ".gitea/workflows", ".github/workflows"})
    void aWorkflowChangeIsLeftForAPerson(String folder) throws Exception {
        final String before = main("planning");
        commit("planning", "issues/I1-An-Idea.md", "saved by the planner");
        commit("planning", folder + "/run.yml", "on: push");
        assertThat(follow("code")).containsEntry("planning", "moved-on");
        assertThat(main("planning")).isEqualTo(before);
        assertThat(told).isEmpty();
        clean();
    }

    @Test
    void aCopyWithWorkOfItsOwnReadsDivergedAndStays() throws Exception {
        final String made = localCommit("planning");
        commit("planning", "issues/I1-An-Idea.md", "saved by the planner");
        assertThat(follow("code")).containsEntry("planning", "diverged");
        assertThat(main("planning")).isEqualTo(made);
        assertThat(told).isEmpty();
        clean();
    }

    @Test
    void theProjectsOwnRepositoryKeepsItsSignedPath() throws Exception {
        final String before = main("code");
        commit("code", "issues/I1-An-Idea.md", "saved by the planner");
        assertThat(follow("code")).containsEntry("code", "moved-on");
        assertThat(main("code")).isEqualTo(before);
        assertThat(commands)
                .noneMatch(argv -> argv.contains("fetch") || argv.contains("post-receive"));
        clean();
    }

    @Test
    void withoutAnOwnRepositoryNamedNothingMoves() throws Exception {
        final String before = main("planning");
        commit("planning", "issues/I1-An-Idea.md", "saved by the planner");
        assertThat(follow(null)).containsEntry("planning", "moved-on");
        assertThat(main("planning")).isEqualTo(before);
        clean();
    }

    @Test
    void aRefusedLandingLeavesNoBundleRefOrScratch() throws Exception {
        final String before = main("planning");
        commit("planning", "issues/I1-An-Idea.md", "saved by the planner");
        fail = "porelo-advance.bundle +refs/heads/main";
        assertThat(follow("code")).containsEntry("planning", "moved-on");
        assertThat(main("planning")).isEqualTo(before);
        assertThat(commands).anyMatch(argv -> argv.contains("tee"));
        clean();
    }

    @Test
    void anUnreadableCentralLeavesNoScratch() throws Exception {
        final String before = main("planning");
        commit("planning", "issues/I1-An-Idea.md", "saved by the planner");
        fail = "--no-recurse-submodules -- " + central.resolve("planning");
        assertThat(follow("code")).containsEntry("planning", "moved-on");
        assertThat(main("planning")).isEqualTo(before);
        assertThat(commands).noneMatch(argv -> argv.contains("tee"));
        clean();
    }

    @Test
    void aSlowFollowLeavesItsRowMovedOnAndTheNextProjectMeasured() throws Exception {
        beta();
        final String before = main("planning");
        commit("planning", "issues/I1-An-Idea.md", "saved by the planner");
        slowAt = "bundle create - refs/heads/main";
        slowBy = 130;
        measure(List.of("alpha", "beta"), Map.of("alpha", "code", "beta", "betacode"));
        assertThat(published("beta")).containsEntry("betacode", "up-to-date");
        assertThat(published("alpha"))
                .containsEntry("code", "up-to-date")
                .containsEntry("planning", "moved-on");
        assertThat(main("planning")).isEqualTo(before);
        clean();
    }

    @Test
    void aDeadlineOutDuringTheLandingStillRemovesBundleAndRef() throws Exception {
        final String before = main("planning");
        commit("planning", "issues/I1-An-Idea.md", "saved by the planner");
        slowAt = "porelo-advance.bundle +refs/heads/main";
        slowBy = 130;
        final var rows = follow("code");
        assertThat(commands).anyMatch(argv -> argv.contains("tee"));
        clean();
        assertThat(rows).containsEntry("planning", "moved-on");
        assertThat(main("planning")).isEqualTo(before);
    }

    @Test
    void everyFetchOfTheFollowChecksObjectsAndSkipsSubmodules() throws Exception {
        commit("planning", "issues/I1-An-Idea.md", "saved by the planner");
        assertThat(follow("code")).containsEntry("planning", "up-to-date");
        final var fetches =
                commands.stream()
                        .filter(argv -> argv.contains("fetch"))
                        .map(argv -> String.join(" ", argv))
                        .toList();
        assertThat(fetches).hasSize(3);
        assertThat(fetches)
                .allSatisfy(
                        line ->
                                assertThat(line)
                                        .contains(
                                                "-c transfer.fsckObjects=true",
                                                "--no-recurse-submodules"));
    }
}
