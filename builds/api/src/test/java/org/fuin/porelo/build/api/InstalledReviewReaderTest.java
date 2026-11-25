package org.fuin.porelo.build.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstalledReviewReaderTest {

    private static final ForgeAccess ACCESS = new ForgeAccess("", "secret-token");

    private static final String OPEN = "https://forge.example/owner/name/pull/1";

    @TempDir Path directory;

    @Test
    void asksAnAdapterOverItsSocketAndHandsEachPageOnAsTheBytesItSent() throws IOException {
        try (InstalledReviewReader reader =
                new InstalledReviewReader(java(FakeReviewReader.class), "fake", socket())) {

            assertThat(reader.forge()).as("the forge it was asked for").isEqualTo("fake");
            final ConversationPage first = reader.comments(ACCESS, OPEN, null);
            assertThat(first.document())
                    .as("the first page's bytes, neither JSON nor ASCII, unread")
                    .isEqualTo(FakeReviewReader.FIRST_PAGE.getBytes(StandardCharsets.UTF_8));
            assertThat(first.cursor()).as("where the next page starts").isEqualTo("after-first");
            final ConversationPage last = reader.comments(ACCESS, OPEN, first.cursor());
            assertThat(last)
                    .as("the last page, without a cursor")
                    .isEqualTo(ConversationPage.of(FakeReviewReader.LAST_PAGE, null));
        }
    }

    @Test
    void readsAPullRequestsFactsAndTheOpenOneFromABranch() throws IOException {
        try (InstalledReviewReader reader =
                new InstalledReviewReader(java(FakeReviewReader.class), "fake", socket())) {

            assertThat(reader.pullRequest(ACCESS, OPEN))
                    .as("an open pull request, approved at its head")
                    .isEqualTo(
                            new ReviewFacts(
                                    "https://forge.example/owner/name",
                                    "a".repeat(40),
                                    "b".repeat(40),
                                    "c".repeat(40),
                                    ReviewFacts.State.OPEN,
                                    true,
                                    null,
                                    null,
                                    null));
            assertThat(reader.pullRequest(ACCESS, "https://forge.example/owner/name/pull/2"))
                    .as("a merged one names its merge commit and the branch it went onto")
                    .satisfies(
                            facts -> {
                                assertThat(facts.state())
                                        .as("state")
                                        .isEqualTo(ReviewFacts.State.MERGED);
                                assertThat(facts.mergeCommit())
                                        .as("merge commit")
                                        .isEqualTo("d".repeat(40));
                                assertThat(facts.mergedHead()).as("merged head").isEqualTo("main");
                                assertThat(facts.decidedAt())
                                        .as("when the forge merged it")
                                        .isEqualTo(java.time.Instant.parse("2026-11-20T10:15:30Z"));
                            });
            assertThat(reader.pullRequestOf(ACCESS, "git@forge.example:owner/name.git", "porelo/t"))
                    .as("the open pull request from the branch")
                    .isEqualTo(OPEN);
            assertThat(reader.pullRequestOf(ACCESS, "git@forge.example:owner/name.git", "other"))
                    .as("none open from another branch")
                    .isNull();
        }
    }

    @Test
    void postsASubmissionAsTheBytesItWasGivenAndHearsItsThread() throws IOException {
        final String submission = "{\"kind\": \"submission\", \"text\": \"Grüße\"}";
        try (InstalledReviewReader reader =
                new InstalledReviewReader(java(FakeReviewReader.class), "fake", socket())) {

            assertThat(reader.post(ACCESS, OPEN, submission.getBytes(StandardCharsets.UTF_8)))
                    .as("the thread the adapter derived from exactly what it received")
                    .isEqualTo(FakeReviewReader.threadOf(submission));
            assertThatThrownBy(() -> reader.post(ACCESS, OPEN, new byte[] {(byte) 0xc3}))
                    .as("a submission that is not UTF-8 is not sent")
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void hearsEachRefusalAsTheReasonTheContractNames() throws IOException {
        try (InstalledReviewReader reader =
                new InstalledReviewReader(java(FakeReviewReader.class), "fake", socket())) {

            assertThatThrownBy(() -> reader.post(ACCESS, OPEN, bytes("rate")))
                    .as("a rate limit, with when to ask again")
                    .isInstanceOfSatisfying(
                            ForgeReviewRefused.class,
                            refused -> {
                                assertThat(refused.reason())
                                        .as("reason")
                                        .isEqualTo(ForgeReviewRefused.Reason.RATE_LIMITED);
                                assertThat(refused.retryAfterSeconds())
                                        .as("retry after")
                                        .isEqualTo(30);
                            });
            assertThatThrownBy(() -> reader.post(ACCESS, OPEN, bytes("general")))
                    .as("what profile 2 cannot carry, naming it")
                    .isInstanceOfSatisfying(
                            ForgeReviewRefused.class,
                            refused -> {
                                assertThat(refused.reason())
                                        .as("reason")
                                        .isEqualTo(ForgeReviewRefused.Reason.NOT_IN_PROFILE);
                                assertThat(refused.field())
                                        .as("field")
                                        .isEqualTo("general comment");
                            });
            assertThatThrownBy(() -> reader.post(ACCESS, OPEN, bytes("orphan")))
                    .as("a thread the pull request does not hold")
                    .isInstanceOfSatisfying(
                            ForgeReviewRefused.class,
                            refused ->
                                    assertThat(refused.reason())
                                            .as("reason")
                                            .isEqualTo(ForgeReviewRefused.Reason.NO_SUCH_THREAD));
            assertThatThrownBy(
                            () ->
                                    reader.pullRequest(
                                            ACCESS, "https://forge.example/owner/name/pull/404"))
                    .as("no such pull request")
                    .isInstanceOfSatisfying(
                            ForgeReviewRefused.class,
                            refused ->
                                    assertThat(refused.reason())
                                            .as("reason")
                                            .isEqualTo(
                                                    ForgeReviewRefused.Reason
                                                            .NO_SUCH_PULL_REQUEST));
            assertThatThrownBy(() -> reader.comments(new ForgeAccess("", "wrong"), OPEN, null))
                    .as("a refused token")
                    .isInstanceOfSatisfying(
                            ForgeReviewRefused.class,
                            refused ->
                                    assertThat(refused.reason())
                                            .as("reason")
                                            .isEqualTo(
                                                    ForgeReviewRefused.Reason.CREDENTIAL_REFUSED));
            assertThatThrownBy(() -> reader.post(ACCESS, OPEN, bytes("broken")))
                    .as(
                            "the adapter's own failure is a fault of the installation, without the token")
                    .isInstanceOf(BuildReaderException.class)
                    .hasMessageContaining(ReviewProtocol.INTERFACE + ".Failed")
                    .hasMessageContaining("the adapter broke")
                    .hasMessageNotContaining(ACCESS.token());
        }
    }

    @Test
    void aPostWhoseAdapterEndedBeforeItAnsweredIsAnUnknownOutcomeNeverSentAgain()
            throws IOException {
        try (InstalledReviewReader reader =
                new InstalledReviewReader(java(FakeReviewReader.class), "fake", socket())) {

            assertThatThrownBy(() -> reader.post(ACCESS, OPEN, bytes("halt")))
                    .as("sent, and no answer says whether it was written")
                    .isInstanceOfSatisfying(
                            ForgeReviewRefused.class,
                            refused ->
                                    assertThat(refused.reason())
                                            .as("reason")
                                            .isEqualTo(
                                                    ForgeReviewRefused.Reason
                                                            .WRITE_OUTCOME_UNKNOWN));
            assertThat(reader.comments(ACCESS, OPEN, null).cursor())
                    .as("the adapter is started again for the next question")
                    .isEqualTo("after-first");
        }
    }

    @Test
    void refusesAnAdapterThatSpeaksAnotherReviewVersion() throws IOException {
        final Path executable = java(OtherReviewVersion.class);
        assertThatThrownBy(() -> new InstalledReviewReader(executable, "fake", socket()))
                .as("another version is refused before anything is asked")
                .isInstanceOf(BuildReaderException.class)
                .hasMessageContaining(executable.toString())
                .hasMessageContaining(ReviewProtocol.INTERFACE + " version 99")
                .hasMessageContaining("this Porelo speaks version " + ReviewProtocol.VERSION);
        assertThat(socket()).as("a refused adapter leaves no socket").doesNotExist();
    }

    @Test
    void refusesAnAdapterThatReadsNoReviewByName() throws IOException {
        final Path executable = java(FakeReader.class);
        assertThatThrownBy(() -> new InstalledReviewReader(executable, "fake", socket()))
                .as("a build reader alone serves no review")
                .isInstanceOf(BuildReaderException.class)
                .hasMessageContaining(executable.toString())
                .hasMessageContaining("serves no " + ReviewProtocol.INTERFACE);
    }

    @Test
    void refusesAnAdapterOfAnotherForge() throws IOException {
        assertThatThrownBy(
                        () ->
                                new InstalledReviewReader(
                                        java(FakeReviewReader.class), "github", socket()))
                .as("the forge it reads is the one asked for")
                .isInstanceOf(BuildReaderException.class)
                .hasMessageContaining("reads 'fake', not 'github'");
    }

    @Test
    void anAccessNeverPrintsItsToken() {
        assertThat(new ForgeAccess("https://forge.example/api", "secret-token").toString())
                .as("an access names its API, never its token")
                .contains("https://forge.example/api")
                .doesNotContain("secret-token");
    }

    /** An adapter that serves the build contract and another version of the review contract. */
    public static final class OtherReviewVersion {

        /**
         * Serves.
         *
         * @param arguments {@code --limits <line> serve <socket>}: the socket comes last.
         */
        public static void main(final String[] arguments) {
            try (VarlinkServer server =
                    new VarlinkServer(
                            Path.of(arguments[arguments.length - 1]), BuildProtocol.INTERFACE)) {
                server.serving(ReviewProtocol.INTERFACE, "interface " + ReviewProtocol.INTERFACE);
                server.method(
                        ReviewProtocol.INTERFACE,
                        "Describe",
                        (parameters, replies) ->
                                replies.last(Map.of("protocolVersion", 99, "forge", "fake")));
                System.out.println("ready " + arguments[arguments.length - 1]);
                System.out.flush();
                server.run();
            }
        }
    }

    @Test
    void aPostPastOneMebibyteIsRefusedBeforeItIsSent() throws IOException {
        final Path posts = directory.resolve("posts.txt");
        try (InstalledReviewReader reader =
                new InstalledReviewReader(recording(posts), "fake", socket())) {

            assertThatThrownBy(
                            () ->
                                    reader.post(
                                            ACCESS,
                                            OPEN,
                                            new byte[ReviewProtocol.MOST_PAGE_BYTES + 1]))
                    .as("a submission past the bound a document has")
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(posts).as("nothing reached the adapter").doesNotExist();
    }

    @Test
    void aPostThatTimesOutIsAnUnknownOutcomeAndIsNeverSentAgain() throws IOException {
        final Path posts = directory.resolve("posts.txt");
        try (InstalledReviewReader reader =
                new InstalledReviewReader(
                        recording(posts),
                        "fake",
                        socket(),
                        org.fuin.porelo.wire.LimitFactors.parse("services=0.1"))) {

            assertThatThrownBy(() -> reader.post(ACCESS, OPEN, bytes("slow")))
                    .as("sent, and no answer came in time")
                    .isInstanceOfSatisfying(
                            ForgeReviewRefused.class,
                            refused ->
                                    assertThat(refused.reason())
                                            .as("reason")
                                            .isEqualTo(
                                                    ForgeReviewRefused.Reason
                                                            .WRITE_OUTCOME_UNKNOWN));
            assertThat(reader.comments(ACCESS, OPEN, null).cursor())
                    .as("a new adapter answers the next question")
                    .isEqualTo("after-first");
        }
        assertThat(Files.readAllLines(posts))
                .as("the post reached the adapter once")
                .containsExactly("slow");
    }

    @Test
    void anAdaptersOwnUnknownOutcomeAndUnreachableAreHeardAsThemselves() throws IOException {
        try (InstalledReviewReader reader =
                        new InstalledReviewReader(java(FakeReviewReader.class), "fake", socket());
                InstalledReviewReader raw =
                        new InstalledReviewReader(
                                java(RawReviewAdapter.class),
                                "fake",
                                directory.resolve("x.sock"))) {

            assertThatThrownBy(() -> reader.post(ACCESS, OPEN, bytes("unreachable")))
                    .as("the forge did not answer")
                    .isInstanceOfSatisfying(
                            ForgeReviewRefused.class,
                            refused ->
                                    assertThat(refused.reason())
                                            .as("reason")
                                            .isEqualTo(ForgeReviewRefused.Reason.UNREACHABLE));
            assertThatThrownBy(() -> raw.post(ACCESS, OPEN, bytes("outcome-unknown")))
                    .as("the adapter says it cannot tell")
                    .isInstanceOfSatisfying(
                            ForgeReviewRefused.class,
                            refused ->
                                    assertThat(refused.reason())
                                            .as("reason")
                                            .isEqualTo(
                                                    ForgeReviewRefused.Reason
                                                            .WRITE_OUTCOME_UNKNOWN));
        }
    }

    @Test
    void aThreadThatIsNoDecimalIdIsTheAdaptersFault() throws IOException {
        try (InstalledReviewReader raw =
                new InstalledReviewReader(java(RawReviewAdapter.class), "fake", socket())) {
            for (final String answer :
                    new String[] {
                        "thread-empty", "thread-letters", "thread-65", "thread-missing"
                    }) {
                assertThatThrownBy(() -> raw.post(ACCESS, OPEN, bytes(answer)))
                        .as("%s is no thread", answer)
                        .isInstanceOf(BuildReaderException.class);
            }
        }
    }

    @Test
    void aMalformedAnswerIsAnUnknownOutcomeForAPostAndTheAdaptersFaultOtherwise()
            throws IOException {
        try (InstalledReviewReader raw =
                new InstalledReviewReader(java(RawReviewAdapter.class), "fake", socket())) {

            assertThatThrownBy(() -> raw.post(ACCESS, OPEN, bytes("garbage")))
                    .as("a post answered with no frame")
                    .isInstanceOfSatisfying(
                            ForgeReviewRefused.class,
                            refused ->
                                    assertThat(refused.reason())
                                            .as("reason")
                                            .isEqualTo(
                                                    ForgeReviewRefused.Reason
                                                            .WRITE_OUTCOME_UNKNOWN));
            assertThatThrownBy(() -> raw.comments(ACCESS, OPEN, "garbage"))
                    .as("a page answered with no frame")
                    .isInstanceOf(BuildReaderException.class);
            assertThatThrownBy(() -> raw.post(ACCESS, OPEN, bytes("thread-letters")))
                    .as("a started-again adapter answers the next question")
                    .isInstanceOf(BuildReaderException.class)
                    .hasMessageContaining("thread");
        }
    }

    @Test
    void aCallInterruptedOnItsWayLeavesNoBrokenConnectionForTheNextPost() throws Exception {
        try (InstalledReviewReader reader =
                new InstalledReviewReader(java(FakeReviewReader.class), "fake", socket())) {
            final Thread asking =
                    Thread.ofPlatform()
                            .start(
                                    () -> {
                                        try {
                                            reader.comments(ACCESS, OPEN, "slow");
                                        } catch (RuntimeException ex) {
                                            // Interrupted: what it threw is not the point.
                                        }
                                    });
            Thread.sleep(1500);
            asking.interrupt();
            asking.join(30_000);

            assertThat(reader.post(ACCESS, OPEN, bytes("after")))
                    .as("the post reaches a live adapter and is answered")
                    .isEqualTo(FakeReviewReader.threadOf("after"));
        }
    }

    @Test
    void aPageCursorOrFieldPastItsBoundIsRefused() throws IOException {
        try (InstalledReviewReader raw =
                new InstalledReviewReader(java(RawReviewAdapter.class), "fake", socket())) {
            for (final String cursor : new String[] {"huge", "long-cursor", "control-cursor"}) {
                assertThatThrownBy(() -> raw.comments(ACCESS, OPEN, cursor))
                        .as("%s is refused", cursor)
                        .isInstanceOf(BuildReaderException.class);
            }
            assertThatThrownBy(() -> raw.post(ACCESS, OPEN, bytes("field-long")))
                    .as("a field past 128 characters")
                    .isInstanceOf(BuildReaderException.class);
            assertThatThrownBy(() -> raw.post(ACCESS, OPEN, bytes("field-null")))
                    .as("a null field is an absent one, never the word null")
                    .isInstanceOfSatisfying(
                            ForgeReviewRefused.class,
                            refused -> assertThat(refused.field()).as("field").isEmpty());
        }
    }

    @Test
    void aPullRequestsAddressOrFactsOutOfShapeAreRefused() throws IOException {
        try (InstalledReviewReader raw =
                new InstalledReviewReader(java(RawReviewAdapter.class), "fake", socket())) {
            for (final String branch : new String[] {"evil", "file", "blank", "number", "dots"}) {
                assertThatThrownBy(() -> raw.pullRequestOf(ACCESS, "git@x:o/n.git", branch))
                        .as("the address answered for %s", branch)
                        .isInstanceOf(BuildReaderException.class);
            }
            for (int pull = 1; pull <= 8; pull++) {
                final String url = "https://forge.example/o/n/pull/" + pull;
                assertThatThrownBy(() -> raw.pullRequest(ACCESS, url))
                        .as("the facts answered for %s", url)
                        .isInstanceOf(BuildReaderException.class);
            }
        }
    }

    @Test
    void theLentTokenNeverReachesAMessageAndAnAdaptersTextIsBounded() throws IOException {
        try (InstalledReviewReader reader =
                new InstalledReviewReader(java(FakeReviewReader.class), "fake", socket())) {

            assertThatThrownBy(() -> reader.post(ACCESS, OPEN, bytes("leak")))
                    .as("a failure that names the token")
                    .isInstanceOf(BuildReaderException.class)
                    .hasMessageContaining("[token]")
                    .hasMessageNotContaining(ACCESS.token())
                    .satisfies(
                            failed ->
                                    assertThat(failed.getMessage().length())
                                            .as("bounded")
                                            .isLessThan(1024));
            assertThatThrownBy(() -> reader.post(ACCESS, OPEN, bytes("leak-field")))
                    .as("a field that names the token")
                    .isInstanceOfSatisfying(
                            ForgeReviewRefused.class,
                            refused -> {
                                assertThat(refused.field())
                                        .as("field")
                                        .doesNotContain(ACCESS.token())
                                        .contains("[token]");
                                assertThat(refused.getMessage())
                                        .as("message")
                                        .doesNotContain(ACCESS.token());
                            });
        }
    }

    private Path recording(final Path posts) throws IOException {
        final Path file = directory.resolve("recording-" + System.nanoTime());
        Files.writeString(
                file,
                "#!/bin/sh\n"
                        + FakeReviewReader.POSTS
                        + "='"
                        + posts
                        + "' exec '"
                        + Path.of(System.getProperty("java.home"), "bin", "java")
                        + "' -cp '"
                        + System.getProperty("java.class.path")
                        + "' '"
                        + FakeReviewReader.class.getName()
                        + "' \"$@\"\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
        return file;
    }

    private static byte[] bytes(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private Path socket() {
        return directory.resolve("r.sock");
    }

    private Path java(final Class<?> main) throws IOException {
        final Path file = directory.resolve("adapter-" + System.nanoTime());
        Files.writeString(
                file,
                "#!/bin/sh\nexec '"
                        + Path.of(System.getProperty("java.home"), "bin", "java")
                        + "' -cp '"
                        + System.getProperty("java.class.path")
                        + "' '"
                        + main.getName()
                        + "' \"$@\"\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
        return file;
    }
}
