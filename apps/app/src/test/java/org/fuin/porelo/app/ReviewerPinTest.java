package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.gate.GitGate;
import org.fuin.porelo.gate.PinnedRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pinning a reviewer moves its one ref in the task's mirror and records the state beside its role,
 * with git stood in; an author is never pinned.
 */
class ReviewerPinTest {

    private static final String TASK = "shop-1";

    private static final String CONTAINER = "porelo-shop-1";

    private static final String HEAD = "a".repeat(40);

    private static final String LATER = "c".repeat(40);

    private static final String BASE = "b".repeat(40);

    @TempDir Path state;

    @TempDir Path mirror;

    /** The task's durable record, as the state directory holds it. */
    @TempDir Path record;

    /** Where the served repositories lie, as the state directory holds them. */
    @TempDir Path pinned;

    private final List<List<String>> asked = new ArrayList<>();

    private int exitCode;

    /** What rev-parse peels an id to, where it is not the id itself. */
    private final java.util.Map<String, String> peeled = new java.util.HashMap<>();

    /** Runs before git answers a command that names this, as a fault at that step. */
    private final java.util.Map<String, Runnable> at = new java.util.HashMap<>();

    private final CommandRunner git =
            command -> {
                asked.add(command.arguments());
                at.forEach(
                        (word, fault) -> {
                            if (command.arguments().contains(word)) {
                                fault.run();
                            }
                        });
                String out = "";
                if (command.arguments().contains("rev-parse")) {
                    final String asked = command.arguments().getLast().replace("^{commit}", "");
                    out = peeled.getOrDefault(asked, asked) + "\n";
                }
                return new CommandResult(command, exitCode, out, "");
            };

    @Test
    void pinningMovesTheReviewersRefAndRecordsTheState() throws IOException {
        reviewer();
        ReviewerPin.pin(
                git, mirror, state, record, served(), TASK, new RunPlace.Pin(HEAD, BASE, null));
        assertThat(asked.getLast())
                .as("the last git the pin ran")
                .containsExactly(
                        "git", "-C", mirror.toString(), "update-ref", GitGate.PINNED + TASK, HEAD);
        assertThat(RunPlace.readFrom(state).pinned())
                .as("the recorded state")
                .isEqualTo(new RunPlace.Pin(HEAD, BASE, null));
    }

    @Test
    void pinningAfreshReplacesTheRefAndTheRecord() throws IOException {
        reviewer();
        ReviewerPin.pin(
                git, mirror, state, record, served(), TASK, new RunPlace.Pin(HEAD, BASE, null));
        ReviewerPin.pin(
                git, mirror, state, record, served(), TASK, new RunPlace.Pin(LATER, BASE, null));
        assertThat(asked.getLast()).as("the last git the pin ran").endsWith(LATER);
        assertThat(RunPlace.readFrom(state).pinned().head())
                .as("the recorded head")
                .isEqualTo(LATER);
        assertThat(RunPlace.readFrom(state).role()).as("the role").isEqualTo(RunPlace.REVIEWER);
    }

    @Test
    void aHeadTheMirrorDoesNotHoldIsPinnedNowhere() throws IOException {
        reviewer();
        exitCode = 128;
        assertThatThrownBy(
                        () ->
                                ReviewerPin.pin(
                                        git,
                                        mirror,
                                        state,
                                        record,
                                        served(),
                                        TASK,
                                        new RunPlace.Pin(HEAD, null, null)))
                .as("a pin git refused")
                .isInstanceOf(IOException.class);
        assertThat(RunPlace.readFrom(state).pinned()).as("the recorded state").isNull();
    }

    @Test
    void anAuthorIsNeverPinned() throws IOException {
        RunPlace.ownPush("shop", null, TASK).writeTo(state);
        assertThatThrownBy(
                        () ->
                                ReviewerPin.pin(
                                        git,
                                        mirror,
                                        state,
                                        record,
                                        served(),
                                        TASK,
                                        new RunPlace.Pin(HEAD, null, null)))
                .as("an author pinned")
                .isInstanceOf(IllegalStateException.class);
        assertThat(asked).as("what git was asked").isEmpty();
    }

    @Test
    void aTaskWithoutARecordedRoleIsNeverPinned() {
        assertThatThrownBy(
                        () ->
                                ReviewerPin.pin(
                                        git,
                                        mirror,
                                        state,
                                        record,
                                        served(),
                                        TASK,
                                        new RunPlace.Pin(HEAD, null, null)))
                .as("a task without a record pinned")
                .isInstanceOf(IllegalStateException.class);
        assertThat(asked).as("what git was asked").isEmpty();
    }

    @Test
    void pinningBuildsTheReviewersOwnRepositoryBesideTheServedOne() throws IOException {
        reviewer();
        ReviewerPin.pin(
                git, mirror, state, record, served(), TASK, new RunPlace.Pin(HEAD, BASE, null));
        final Path built = served().resolveSibling("shop-1.git.new");
        assertThat(asked)
                .as("what git was asked")
                .contains(List.of("git", "init", "--bare", "-q", built.toString()));
        assertThat(asked)
                .as("the fetch of the pinned head and base alone")
                .anySatisfy(
                        each ->
                                assertThat(each)
                                        .contains("fetch", "--no-tags")
                                        .endsWith(
                                                HEAD + ":" + GitGate.PINNED + TASK,
                                                BASE + ":" + PinnedRepository.BASE + TASK));
        assertThat(served()).as("the served repository").isDirectory();
    }

    @Test
    void aTagIsNeverPinnedOnlyTheCommitItself() throws IOException {
        reviewer();
        peeled.put(HEAD, LATER);
        assertThatThrownBy(
                        () ->
                                ReviewerPin.pin(
                                        git,
                                        mirror,
                                        state,
                                        record,
                                        served(),
                                        TASK,
                                        new RunPlace.Pin(HEAD, null, null)))
                .as("a pin of an id that names a tag")
                .isInstanceOf(IOException.class);
        assertThat(asked)
                .as("what git was asked")
                .noneMatch(each -> each.contains("update-ref") || each.contains("init"));
        assertThat(RunPlace.readFrom(state).pinned()).as("the recorded state").isNull();
    }

    @Test
    void aRecordThatCannotBeWrittenPutsBackTheEarlierRepositoryAndRef() throws IOException {
        reviewer();
        ReviewerPin.pin(
                git, mirror, state, record, served(), TASK, new RunPlace.Pin(HEAD, BASE, null));
        java.nio.file.Files.writeString(served().resolve("earlier"), "");
        at.put(
                "update-ref",
                () -> {
                    try {
                        // The record's place taken by a directory: its replacement cannot land.
                        final Path place = state.resolve(RunPlace.FILE);
                        if (java.nio.file.Files.isRegularFile(place)) {
                            java.nio.file.Files.delete(place);
                            java.nio.file.Files.createDirectory(place);
                            java.nio.file.Files.writeString(place.resolve("in-the-way"), "");
                        }
                    } catch (IOException ex) {
                        throw new java.io.UncheckedIOException(ex);
                    }
                });
        assertThatThrownBy(
                        () ->
                                ReviewerPin.pin(
                                        git,
                                        mirror,
                                        state,
                                        record,
                                        served(),
                                        TASK,
                                        new RunPlace.Pin(LATER, BASE, null)))
                .as("a pin whose record could not be written")
                .isInstanceOf(IOException.class);
        assertThat(served().resolve("earlier")).as("the earlier served repository's mark").exists();
        assertThat(RunPlace.readFrom(record).pinned().head())
                .as("the head the durable record names, put back")
                .isEqualTo(HEAD);
        assertThat(asked.getLast())
                .as("the last git the pin ran")
                .containsExactly(
                        "git", "-C", mirror.toString(), "update-ref", GitGate.PINNED + TASK, HEAD);
    }

    @Test
    void theServedRepositoryLiesInTheStateDirectoryAndNoneInTheRuntimeOne(@TempDir Path root)
            throws IOException {
        final TaskPaths paths = paths(root);
        final Path runtime = java.nio.file.Files.createDirectories(paths.containerState(CONTAINER));
        reviewer(runtime);
        ReviewerPin.pin(
                git,
                mirror,
                runtime,
                java.nio.file.Files.createDirectories(paths.taskRecord(CONTAINER)),
                paths.pinnedRepository(CONTAINER),
                TASK,
                new RunPlace.Pin(HEAD, BASE, null));
        assertThat(paths.pinnedRepository(CONTAINER))
                .as("the served repository, in the state directory")
                .isDirectory();
        try (java.util.stream.Stream<Path> left = java.nio.file.Files.list(runtime)) {
            assertThat(left.map(each -> each.getFileName().toString()))
                    .as("what the pin left in the runtime directory")
                    .containsExactly(RunPlace.FILE);
        }
    }

    @Test
    void theServedRepositoryOutlivesTheRuntimeDirectoryAsAfterAReboot(@TempDir Path root)
            throws IOException {
        final TaskPaths paths = paths(root);
        final Path runtime = java.nio.file.Files.createDirectories(paths.containerState(CONTAINER));
        reviewer(runtime);
        ReviewerPin.pin(
                git,
                mirror,
                runtime,
                java.nio.file.Files.createDirectories(paths.taskRecord(CONTAINER)),
                paths.pinnedRepository(CONTAINER),
                TASK,
                new RunPlace.Pin(HEAD, BASE, null));
        try (java.util.stream.Stream<Path> tree = java.nio.file.Files.walk(runtime)) {
            for (final Path each : tree.sorted(java.util.Comparator.reverseOrder()).toList()) {
                java.nio.file.Files.delete(each);
            }
        }
        assertThat(paths.pinnedRepository(CONTAINER))
                .as("the served repository once the runtime directory is gone")
                .isDirectory();
    }

    @Test
    void theServedRepositorysDirectoryIsTheAccountsAlone(@TempDir Path root) throws IOException {
        final TaskPaths paths = paths(root);
        final Path runtime = java.nio.file.Files.createDirectories(paths.containerState(CONTAINER));
        reviewer(runtime);
        ReviewerPin.pin(
                git,
                mirror,
                runtime,
                java.nio.file.Files.createDirectories(paths.taskRecord(CONTAINER)),
                paths.pinnedRepository(CONTAINER),
                TASK,
                new RunPlace.Pin(HEAD, BASE, null));
        assertThat(
                        java.nio.file.attribute.PosixFilePermissions.toString(
                                java.nio.file.Files.getPosixFilePermissions(
                                        paths.pinnedRepository(CONTAINER).getParent())))
                .as("the mode of the directory that holds the served repositories")
                .isEqualTo("rwx------");
    }

    private static TaskPaths paths(final Path root) {
        return new PoreloPaths(
                        new org.fuin.porelo.core.config.XdgPaths(
                                root.resolve("config"),
                                root.resolve("data"),
                                root.resolve("state"),
                                root.resolve("run"),
                                root),
                        root.resolve("bin"))
                .tasks();
    }

    private Path served() {
        return pinned.resolve("shop-1.git");
    }

    private void reviewer() throws IOException {
        reviewer(state);
    }

    private static void reviewer(final Path state) throws IOException {
        new RunPlace(RunPlace.REVIEWER, null, "shop-2").writeTo(state);
    }
}
