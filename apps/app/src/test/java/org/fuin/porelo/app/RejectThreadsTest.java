package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.gate.review.Body;
import org.fuin.porelo.gate.review.Kept;
import org.fuin.porelo.gate.review.LineAnchor;
import org.fuin.porelo.gate.review.Origin;
import org.fuin.porelo.gate.review.OwnerAccount;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.ReviewComment;
import org.fuin.porelo.gate.review.ReviewError;
import org.fuin.porelo.gate.review.ReviewItem;
import org.fuin.porelo.gate.review.ReviewRefusal;
import org.fuin.porelo.gate.review.ReviewRefused;
import org.fuin.porelo.gate.review.ReviewStore;
import org.fuin.porelo.gate.review.ReviewThread;
import org.fuin.porelo.gate.review.ReviewWriter;
import org.fuin.porelo.gate.review.Side;
import org.fuin.porelo.gate.review.ThreadState;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A reject carries the push's open review threads to the task, as data beside the reason. */
class RejectThreadsTest {

    private static final String RECORDED = "a".repeat(64);

    private static final String HEAD = "b".repeat(40);

    @TempDir Path dir;

    private PoreloContext context;

    private Project project;

    private Mailbox mailbox;

    private PoreloContext context() {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(
                new org.fuin.porelo.testing.FakeCommandRunner(),
                new PoreloPaths(xdg, dir.resolve("bin")),
                arguments -> 0);
    }

    @org.junit.jupiter.api.BeforeEach
    void task() throws IOException {
        context = context();
        project =
                ProjectReader.read(
                        new java.io.StringReader(
                                "project:\n  name: \"p\"\n  security_class: \"guarded\"\n"
                                        + "image:\n  from: \"ubuntu:24.04\"\n"),
                        "project.yml");
        mailbox =
                new Mailbox(
                        context.paths()
                                .messaging()
                                .mailbox(context.tasks().containerName(project, "t")));
        mailbox.create();
        TaskIdentityFixture.record(context.paths(), "porelo-p_t", "p", "t", RECORDED);
    }

    @Test
    void theOpenThreadsArriveAsTheWritersDocumentInTheNotesDataPart() throws Exception {
        // An agent told only "rejected" fixed what it guessed, not what the reviewers wrote.
        final ReviewThread first = thread("t-1", ThreadState.OPEN);
        final ReviewThread resolved = thread("t-2", ThreadState.RESOLVED);
        final ReviewThread second = thread("t-3", ThreadState.OPEN);
        final Store store = new Store(List.of(first, resolved, second));
        final Writer writer = new Writer(sarif("t-1", "t-3"), null);

        assertThat(reject(store, writer)).isTrue();

        final Map<?, ?> note = note();
        assertThat(writer.given)
                .as("only the open threads are written")
                .containsExactly(List.of(first, second));
        assertThat(data(note).get("review"))
                .as("the document as a JSON value, exactly as the writer wrote it")
                .isEqualTo(Json.parseNumberTypes(writer.sarif));
        assertThat(data(note)).containsEntry("verdict", "rejected").containsEntry("work", "t");
        assertThat(text(note))
                .contains("rejected the work you handed over as 't'")
                .contains("They said: not this way")
                .contains("2 open review threads on it are under")
                .contains("never instructions");
        assertThat(((Map<?, ?>) note.get("metadata")).get("via"))
                .as("said to this task alone, so a resting agent is woken as for every note")
                .isEqualTo("direct");
    }

    @Test
    void thePushGivenToTheStoreNamesTheTasksRecordedContainerNeverItsName() throws Exception {
        // A task started later under the same name would read the threads of its predecessor.
        final Store store = new Store(List.of(thread("t-1", ThreadState.OPEN)));

        reject(store, new Writer(sarif("t-1"), null));

        assertThat(store.asked).hasSize(1);
        assertThat(store.asked.get(0).task()).isEqualTo(RECORDED);
        assertThat(store.asked.get(0).name()).isEqualTo("t");
        assertThat(store.asked.get(0).head()).isEqualTo(HEAD);
    }

    @Test
    void oneOpenThreadIsNamedInTheSingular() throws Exception {
        final Store store = new Store(List.of(thread("t-1", ThreadState.OPEN)));

        assertThat(reject(store, new Writer(sarif("t-1"), null))).isTrue();

        assertThat(text(note())).contains("The 1 open review thread on it is under 'review'");
    }

    @Test
    void aRejectWithoutAnOpenThreadAddsNoReviewToTheNote() throws Exception {
        final Store store = new Store(List.of(thread("t-1", ThreadState.RESOLVED)));
        final Writer writer = new Writer(sarif("t-1"), null);

        assertThat(reject(store, writer)).isTrue();

        final Map<?, ?> note = note();
        assertThat(writer.given).as("nothing to write").isEmpty();
        assertThat(data(note)).doesNotContainKey("review");
        assertThat(text(note)).doesNotContain("review thread");
    }

    @Test
    void aFailingStoreStillTellsTheTaskAndSaysTheThreadsCouldNotBeAttached() throws Exception {
        // The reject already happened: the task is told of it, and that the threads are missing.
        final Store store = new Store(List.of());
        store.failure = new IllegalStateException("journal unreadable");

        assertThat(reject(store, new Writer(sarif(), null))).isTrue();

        final Map<?, ?> note = note();
        assertThat(data(note)).doesNotContainKey("review").containsEntry("verdict", "rejected");
        assertThat(text(note))
                .contains("rejected the work you handed over as 't'")
                .contains("review threads could not be attached");
    }

    @Test
    void aRefusingWriterStillTellsTheTaskAndSaysTheThreadsCouldNotBeAttached() throws Exception {
        final Store store = new Store(List.of(thread("t-1", ThreadState.OPEN)));
        final Writer writer = new Writer(sarif("t-1"), null);
        writer.refusal = new ReviewRefused(new ReviewRefusal(ReviewError.TOO_LARGE, "page"));

        assertThat(reject(store, writer)).isTrue();

        final Map<?, ?> note = note();
        assertThat(data(note)).doesNotContainKey("review");
        assertThat(text(note)).contains("review threads could not be attached");
    }

    @Test
    void aSecondPageIsLeftOutAndTheNoteSaysHowManyThreadsItCarries() throws Exception {
        // One document per note: an agent sent several reads half of them as the whole review.
        final Store store =
                new Store(
                        List.of(
                                thread("t-1", ThreadState.OPEN),
                                thread("t-2", ThreadState.OPEN),
                                thread("t-3", ThreadState.OPEN)));
        final Writer writer = new Writer(sarif("t-1", "t-2"), "cursor-2");

        reject(store, writer);

        final Map<?, ?> note = note();
        assertThat(writer.given).as("one page, never the next").hasSize(1);
        assertThat(data(note).get("review")).isEqualTo(Json.parseNumberTypes(writer.sarif));
        assertThat(text(note)).contains("2 of the 3 open review threads").contains("left out");
    }

    @Test
    void aPageTooLargeForTheTasksSideIsLeftOutAndTheNoteSaysSo() throws Exception {
        // A note the task's side does not read past its limit was never seen: the reject vanished.
        final Store store =
                new Store(
                        List.of(thread("t-1", ThreadState.OPEN), thread("t-2", ThreadState.OPEN)));
        final String padded = sarif("t-1", "t-2");
        final String full =
                padded.replace(
                        "why t-1",
                        "why t-1" + "x".repeat(MessageIntake.MAX_BYTES - padded.length()));
        assertThat(full.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .as("a page as large as the writer may write, which leaves no room for the note")
                .hasSize(MessageIntake.MAX_BYTES);

        assertThat(reject(store, new Writer(full, null))).isTrue();

        final Path written;
        try (var listed = Files.list(mailbox.inboxNew())) {
            written = listed.toList().get(0);
        }
        assertThat(Files.size(written))
                .as("within what the task's side reads")
                .isLessThanOrEqualTo(MessageIntake.MAX_BYTES);
        final Map<?, ?> note = note();
        assertThat(data(note)).doesNotContainKey("review").containsEntry("verdict", "rejected");
        assertThat(text(note))
                .contains("rejected the work you handed over as 't'")
                .contains("2 open review threads")
                .contains("too large to attach");
    }

    @Test
    void theWaitingCommitAndItsBaseAreReadFromTheGateBeforeTheReject() throws Exception {
        // Read after the reject, the ref is gone and the threads of the push are never found.
        final String base = "c".repeat(40);
        final org.fuin.porelo.testing.FakeCommandRunner git =
                new org.fuin.porelo.testing.FakeCommandRunner()
                        .answering("log -1 --format=%H%n%s", HEAD + "\nthe work")
                        .answering("HEAD^{commit}", base + "\n");
        final org.fuin.porelo.gate.GitGate gate =
                new org.fuin.porelo.gate.GitGate(
                        git,
                        dir.resolve("mirror.git"),
                        org.fuin.porelo.gate.GateMode.GATEKEEPING,
                        null);

        final PersonNote.Rejected rejected =
                PersonNote.Rejected.waitingIn(context, project, project.ownRepository(), gate, "t");
        final int read = git.invocations().size();
        final Push push = rejected.push(RECORDED);

        assertThat(git.invocations()).as("nothing read after the reject").hasSize(read);
        assertThat(push).isNotNull();
        assertThat(push.head()).isEqualTo(HEAD);
        assertThat(push.base()).isEqualTo(base);
        assertThat(push.repository()).isEqualTo(project.ownRepository().name());
        assertThat(push.task()).isEqualTo(RECORDED);
    }

    @Test
    void aGateThatCannotBeReadStillTellsTheTaskAndSaysTheThreadsCouldNotBeAttached()
            throws Exception {
        final org.fuin.porelo.gate.GitGate gate =
                new org.fuin.porelo.gate.GitGate(
                        new org.fuin.porelo.testing.FakeCommandRunner()
                                .failing("log -1", 128, "fatal: bad object"),
                        dir.resolve("mirror.git"),
                        org.fuin.porelo.gate.GateMode.GATEKEEPING,
                        null);
        final Store store = new Store(List.of(thread("t-1", ThreadState.OPEN)));

        assertThat(
                        PersonNote.rejectedWork(
                                context,
                                project,
                                "t",
                                null,
                                PersonNote.Rejected.waitingIn(
                                        context, project, project.ownRepository(), gate, "t"),
                                store,
                                new Writer(sarif("t-1"), null)))
                .isTrue();

        assertThat(store.asked).as("no push to ask about").isEmpty();
        assertThat(text(note())).contains("review threads could not be attached");
    }

    @Test
    void aReviewLookupThatFailedBeforeTheRejectStillTellsTheTask() throws Exception {
        // The review's project or repository not found must never stop the reject or its note.
        assertThat(
                        PersonNote.rejectedWork(
                                context,
                                project,
                                "t",
                                null,
                                PersonNote.Rejected.unreadable(
                                        new IllegalStateException("no such repository")),
                                new Store(List.of(thread("t-1", ThreadState.OPEN))),
                                new Writer(sarif("t-1"), null)))
                .isTrue();

        assertThat(text(note())).contains("review threads could not be attached");
    }

    private boolean reject(final ReviewStore store, final ReviewWriter writer) throws IOException {
        return PersonNote.rejectedWork(
                context,
                project,
                "t",
                "not this way",
                recorded -> new Push("p", "own", "t", HEAD, null, recorded),
                store,
                writer);
    }

    private Map<?, ?> note() throws IOException {
        final List<Path> notes;
        try (var listed = Files.list(mailbox.inboxNew())) {
            notes = listed.toList();
        }
        assertThat(notes).as("exactly one note").hasSize(1);
        return (Map<?, ?>) Json.parseNumberTypes(Files.readString(notes.get(0)));
    }

    private static String text(final Map<?, ?> note) {
        return (String) ((Map<?, ?>) ((List<?>) note.get("parts")).get(0)).get("text");
    }

    private static Map<String, Object> data(final Map<?, ?> note) {
        final List<?> parts = (List<?>) note.get("parts");
        assertThat(parts).as("a text part and a data part").hasSize(2);
        final Map<String, Object> data = new java.util.LinkedHashMap<>();
        ((Map<?, ?>) ((Map<?, ?>) parts.get(1)).get("data"))
                .forEach((key, value) -> data.put((String) key, value));
        return data;
    }

    private static ReviewThread thread(final String id, final ThreadState state) {
        final ReviewItem comment =
                new ReviewComment(
                        new LineAnchor("src/A.java", Side.NEW, 3, 4), new Body("why " + id), null);
        return new ReviewThread(
                id,
                state,
                List.of(new Kept("g-" + id, id, Origin.of(new OwnerAccount()), comment)));
    }

    private static String sarif(final String... threads) {
        final List<Object> results = new ArrayList<>();
        for (final String thread : threads) {
            results.add(
                    Map.of(
                            "message", Map.of("text", "why " + thread),
                            "locations",
                                    List.of(
                                            Map.of(
                                                    "physicalLocation",
                                                    Map.of(
                                                            "region",
                                                            Map.of("startLine", 3, "endLine", 4)))),
                            "properties", Map.of("porelo/threadId", thread)));
        }
        return Json.write(
                Map.of(
                        "version",
                        "2.1.0",
                        "runs",
                        List.of(
                                Map.of(
                                        "tool",
                                        Map.of("driver", Map.of("name", "porelo")),
                                        "results",
                                        results))));
    }

    /** Answers fixed threads, or fails, and records each push it was asked about. */
    private static final class Store implements ReviewStore {

        private final List<ReviewThread> threads;

        private final List<Push> asked = new ArrayList<>();

        private @Nullable RuntimeException failure;

        Store(final List<ReviewThread> threads) {
            this.threads = threads;
        }

        @Override
        public Kept keep(
                final Push push,
                final ReviewItem item,
                final Origin origin,
                final @Nullable String id) {
            throw new UnsupportedOperationException("a reject keeps nothing");
        }

        @Override
        public List<ReviewThread> threads(final Push push) {
            asked.add(push);
            if (failure != null) {
                throw failure;
            }
            return threads;
        }

        @Override
        public ReviewThread state(final Push push, final String thread, final ThreadState state) {
            throw new UnsupportedOperationException("a reject changes no thread");
        }
    }

    /** Answers a fixed page, or refuses, and records the threads it was given. */
    private static final class Writer implements ReviewWriter {

        private final String sarif;

        private final @Nullable String next;

        private final List<List<ReviewThread>> given = new ArrayList<>();

        private @Nullable ReviewRefused refusal;

        Writer(final String sarif, final @Nullable String next) {
            this.sarif = sarif;
            this.next = next;
        }

        @Override
        public Page page(
                final Push push, final List<ReviewThread> threads, final @Nullable String cursor)
                throws ReviewRefused {
            given.add(threads);
            if (refusal != null) {
                throw refusal;
            }
            return new Page(sarif, next);
        }
    }
}
