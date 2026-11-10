package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.*;
import static org.fuin.porelo.host.ProjectFixture.*;

import java.nio.file.*;
import java.util.*;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class HostProjectCopiesTest {
    @TempDir Path root;
    ProjectFixture host;

    @BeforeEach
    void start() throws Exception {
        host = new ProjectFixture(root);
        host.add();
        host.readKeys();
        host.clear();
        host.program =
                argv ->
                        argv.contains("getent") || argv.getFirst().equals("/usr/bin/getent")
                                ? new HostPrograms.Result(
                                        0,
                                        "forge:x:"
                                                + uid()
                                                + ":"
                                                + gid()
                                                + "::/home/forge:/bin/false\n")
                                : null;
    }

    private int uid() {
        try {
            return (int) Files.getAttribute(root, "unix:uid");
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private int gid() {
        try {
            return (int) Files.getAttribute(root, "unix:gid");
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private Path copies() {
        return new ProjectCopies(host.files, host.runtime).directory();
    }

    private Answer copy(String address, String known) {
        return host.run("project-copy", "alpha", address, known);
    }

    private void noClone() {
        assertThat(host.commands).noneMatch(argv -> argv.contains("clone"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ahead",
                "moved-on",
                "unreachable",
                "local",
                "no-central-main",
                "no-read-key",
                "unlisted"
            })
    void workNotKnownAtCentralHoldsTheHost(String state) throws Exception {
        switch (state) {
            case "ahead" -> host.localMain = "b".repeat(40);
            case "moved-on" -> {
                host.localMain = "b".repeat(40);
                host.ancestor = 1;
            }
            case "unreachable" -> host.gitFailure = 128;
            case "local" -> host.localMain = null;
            case "no-central-main" -> host.centralMain = null;
            case "no-read-key" ->
                    Files.writeString(
                            host.manifest(),
                            Files.readString(host.manifest())
                                    .replace("\"key_id\":1", "\"key_id\":0"));
            case "unlisted" -> host.repositories.put("alpha/unlisted", Map.of("name", "unlisted"));
        }
        final Answer answer = host.run("give-back-check");
        assertThat(answer.code()).as(answer.err()).isEqualTo(65);
        assertThat(answer.err())
                .contains(
                        "alpha/" + (state.equals("unlisted") ? "unlisted" : "code"),
                        "give it back anyway");
        assertThat(answer.out()).isEmpty();
        host.noMutation();
    }

    @Test
    void allCentralAllowsReturningWithoutRemovingTheProject() {
        final Answer answer = host.run("give-back-check");
        assertThat(answer.code()).as(answer.err()).isZero();
        assertThat(answer.out()).isEqualTo("{\"projects\":[\"alpha\"],\"at_central\":true}\n");
        assertThat(host.records.record("alpha")).exists();
        host.noMutation();
        assertThat(host.commands).noneMatch(argv -> argv.contains("generate-access-token"));
    }

    @Test
    void aMissingForgeFolderIsNotListed() {
        host.repositories.clear();
        assertThat(host.run("give-back-check").code()).isZero();
        assertThat(host.commands).noneMatch(argv -> argv.contains("ls"));
    }

    @Test
    void aCopyHasPrivateOwnershipAndAnUnlistableParent() throws Exception {
        final Answer answer = copy(ADDRESS, "-");
        assertThat(answer.code()).as(answer.err()).isZero();
        final Map<?, ?> json = (Map<?, ?>) Json.parseExact(answer.out());
        final Path path = Path.of((String) json.get("copy"));
        assertThat(json.get("commit")).isEqualTo(host.centralMain);
        assertThat(path.getFileName().toString()).isEqualTo("central.git");
        assertThat(path.getParent().getParent()).isEqualTo(copies());
        assertThat(path.getParent().getFileName().toString()).matches("[0-9a-f]{32}");
        assertThat((int) Files.getAttribute(copies(), "unix:mode") & 0777).isEqualTo(0711);
        assertThat((int) Files.getAttribute(path.getParent(), "unix:mode") & 0777).isEqualTo(0700);
        assertThat((int) Files.getAttribute(path, "unix:mode") & 0777).isEqualTo(0700);
        assertThat(Files.getAttribute(path, "unix:uid")).isEqualTo(uid());
        assertThat(Files.getAttribute(path, "unix:gid")).isEqualTo(gid());
    }

    @Test
    void aCopyCannotComeFromTheAddressTheProjectLeft() {
        assertThat(copy(ADDRESS.replace("/acme/", "/new/"), "-").code()).isEqualTo(65);
        noClone();
    }

    @Test
    void anAlreadyKnownMainRemovesInterruptedCopiesWithoutCloning() throws Exception {
        Files.createDirectories(copies().resolve("left/central.git"));
        ProjectFixture.mode(copies(), 0711);
        final Answer answer = copy(ADDRESS, host.centralMain);
        assertThat(answer.code()).as(answer.err()).isZero();
        assertThat(answer.out())
                .isEqualTo("{\"commit\":\"" + host.centralMain + "\",\"copy\":null}\n");
        assertThat(copies().resolve("left")).doesNotExist();
        noClone();
    }

    @Test
    void cleanupNeverFollowsACopyOwnersLinkOutOfTheCopy() throws Exception {
        Files.createDirectories(copies().resolve("left"));
        ProjectFixture.mode(copies(), 0711);
        final Path outside = Files.writeString(root.resolve("keep"), "other work");
        Files.createSymbolicLink(copies().resolve("left/link"), outside);
        assertThat(copy(ADDRESS, host.centralMain).code()).isZero();
        assertThat(Files.readString(outside)).isEqualTo("other work");
        assertThat(copies().resolve("left")).doesNotExist();
    }

    @Test
    void aCopyReadsOnlyMainWithItsOwnKey() {
        assertThat(copy(ADDRESS, "-").code()).isZero();
        final int index =
                java.util.stream.IntStream.range(0, host.commands.size())
                        .filter(i -> host.commands.get(i).contains("clone"))
                        .findFirst()
                        .orElseThrow();
        final var argv = host.commands.get(index);
        assertThat(argv.subList(argv.indexOf("clone"), argv.indexOf("clone") + 6))
                .containsExactly("clone", "--bare", "--single-branch", "--branch", "main", "--");
        assertThat(host.environments.get(index).get("GIT_SSH_COMMAND"))
                .contains("-i " + host.keyPath());
        assertThat(host.timeouts.get(index).toSeconds()).isLessThanOrEqualTo(30);
    }

    @ParameterizedTest
    @CsvSource({"no-read-key,69", "no-main,69", "bad-address,65"})
    void aForeignOrAbsentMainIsNoCopy(String defect, int code) {
        String address = ADDRESS;
        if (defect.equals("no-read-key")) {
            address = ADDRESS.replace("code.git", "other.git");
        }
        if (defect.equals("no-main")) {
            host.centralMain = null;
        }
        if (defect.equals("bad-address")) {
            address = "--upload-pack=x";
        }
        assertThat(copy(address, "-").code()).isEqualTo(code);
        noClone();
    }

    @Test
    void gitOptionsAreNotKnownCommits() {
        assertThat(copy(ADDRESS, "--all").code()).isEqualTo(64);
        host.noCommands();
    }

    @ParameterizedTest
    @ValueSource(strings = {"commit", "name", "path", "traversal", "reach", "missing"})
    void advanceRejectsEveryForeignArgumentBeforeAnyProgram(String defect) {
        final var args =
                new ArrayList<>(
                        List.of(
                                "alpha",
                                "code",
                                "c".repeat(40),
                                copies() + "/" + "d".repeat(32) + "/central.git",
                                "signed"));
        switch (defect) {
            case "commit" -> args.set(2, "not-a-commit");
            case "name" -> args.set(1, "../alpha");
            case "path" -> args.set(3, "/etc/passwd");
            case "traversal" -> args.set(3, copies() + "/../../etc/central.git");
            case "reach" -> args.set(4, "anything");
            case "missing" -> args.removeLast();
        }
        assertThat(host.run("project-advance", args.toArray(String[]::new)).code()).isEqualTo(64);
        host.noCommands();
    }

    @Test
    void advanceNeedsAnInstalledReadKey() {
        final Answer answer =
                host.run(
                        "project-advance",
                        "alpha",
                        "other",
                        "c".repeat(40),
                        copies() + "/" + "d".repeat(32) + "/central.git",
                        "signed");
        assertThat(answer.code()).as(answer.err()).isEqualTo(69);
        host.noCommands();
    }

    @Test
    void anOldCopyThatCannotBeCleanedDoesNotStopTheNextQuestion() throws Exception {
        final Path left = copies().resolve("left");
        Files.createDirectories(left);
        ProjectFixture.mode(copies(), 0711);
        Files.writeString(left.resolve("held"), "old work");
        ProjectFixture.mode(left, 0000);
        try {
            final var answer = copy(ADDRESS, host.centralMain);
            assertThat(answer.code()).as(answer.err()).isZero();
            noClone();
        } finally {
            ProjectFixture.mode(left, 0700);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fullCommitInventoriesCanExceedOneMegabyte(boolean bounded) throws Exception {
        final String inventory =
                java.util.stream.IntStream.range(1, 30001)
                        .mapToObj(value -> "%040x\n".formatted(value))
                        .collect(java.util.stream.Collectors.joining());
        final var centralMissingLast = new java.util.concurrent.atomic.AtomicBoolean();
        final var runtime =
                new ProjectRuntime(
                        host.files,
                        (argv, input, env, timeout) -> {
                            if (argv.contains("rev-list")) {
                                return new HostPrograms(timeout, 1024 * 1024, Path::of)
                                        .run(
                                                List.of("/usr/bin/cat"),
                                                inventory.getBytes(
                                                        java.nio.charset.StandardCharsets.US_ASCII),
                                                env);
                            }
                            return host.runtime.run(argv, input, env, timeout);
                        },
                        Path::of,
                        path -> true,
                        (socket, token) -> {
                            throw new AssertionError();
                        },
                        (argv, input, output, env, timeout) -> {
                            if (argv.contains("rev-list")) {
                                final String text =
                                        centralMissingLast.get()
                                                        && argv.getFirst().equals("/usr/bin/git")
                                                ? inventory.substring(0, inventory.length() - 41)
                                                : inventory;
                                output.write(
                                        text.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                                return new HostPrograms.Result(0, "");
                            }
                            throw new AssertionError("Only inventories stream here");
                        });
        final var clock = java.time.Clock.systemUTC();
        final var questions =
                bounded
                        ? runtime.until(clock, clock.instant().plusSeconds(300))
                                .until(clock, clock.instant().plusSeconds(120))
                        : runtime;
        new ProjectReadKeys(host.files, questions)
                .check("forge", "alpha", List.of(Map.of("name", "code", "original_url", ADDRESS)));
        centralMissingLast.set(true);
        assertThatThrownBy(
                        () ->
                                new ProjectReadKeys(host.files, questions)
                                        .check(
                                                "forge",
                                                "alpha",
                                                List.of(
                                                        Map.of(
                                                                "name",
                                                                "code",
                                                                "original_url",
                                                                ADDRESS))))
                .isInstanceOfSatisfying(
                        ProjectReadKeys.Unpushed.class,
                        refusal ->
                                assertThat(refusal.commits)
                                        .containsEntry("code", List.of("%040x".formatted(30000))));
    }

    @Test
    void givingACopyUsesTheUnboundedPasswdLookupOfTheProjectScript() {
        assertThat(copy(ADDRESS, "-").code()).isZero();
        for (int index = 0; index < host.commands.size(); index++) {
            if (host.commands.get(index).getFirst().equals("/usr/bin/getent")) {
                assertThat(host.timeouts.get(index)).isEqualTo(java.time.Duration.ZERO);
            }
        }
    }
}
