package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.fuin.porelo.gate.review.Body;
import org.fuin.porelo.gate.review.LineAnchor;
import org.fuin.porelo.gate.review.Origin;
import org.fuin.porelo.gate.review.OwnerAccount;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.PushEnd;
import org.fuin.porelo.gate.review.PushEnds;
import org.fuin.porelo.gate.review.ReviewComment;
import org.fuin.porelo.gate.review.ReviewComments;
import org.fuin.porelo.gate.review.Side;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

/**
 * {@code porelo review open}: its exit codes and outputs over a real journal and kept ends in a
 * temporary directory, with the gate stood in. It also writes the answers of each decision and of a
 * refusal under {@code target/handover/}, as the command prints them.
 */
class ReviewOpenCommandTest {

    private static final String CONTAINER = "c".repeat(64);

    private static final String HEAD = "a".repeat(40);

    private static final Push SHOP = new Push("uc", "shop", "shop-2", HEAD, null, CONTAINER);

    private static final Instant WRITTEN = Instant.parse("2026-11-25T09:30:00Z");

    private static final Instant ENDED = Instant.parse("2026-11-25T11:00:00Z");

    private static final Path HANDOVER = Path.of("target", "handover");

    @TempDir Path dir;

    private boolean waits = true;

    private @Nullable RuntimeException broken;

    private ReviewOpen.@Nullable Refused refusing;

    private ReviewComments journal;

    private PushEnds ends;

    private StringWriter out;

    /** The reader answering instead of the gate's, or null for the gate's. */
    private @Nullable ReviewOpen using;

    private StringWriter err;

    @BeforeEach
    void aGateWithAJournalAndKeptEnds() throws Exception {
        journal = new ReviewComments(dir.resolve("review"), Clock.fixed(WRITTEN, ZoneOffset.UTC));
        ends = new PushEnds(dir.resolve("review"));
        ends.keep(
                CONTAINER, PushEnd.waiting("shop-2", HEAD, Instant.parse("2026-11-25T08:00:00Z")));
    }

    private ReviewOpen open() {
        return new ReviewOpen(
                new ReviewOpen.Parts(
                        (project, repository, name) -> {
                            if (broken != null) {
                                throw broken;
                            }
                            if (refusing != null) {
                                throw refusing;
                            }
                            if (!name.equals("shop-2")) {
                                throw ReviewOpen.Refused.placeUnreadable(
                                        "NoSuchPush", "no task is recorded for " + name);
                            }
                            return new ReviewOpen.Standing(
                                    "uc",
                                    "shop",
                                    "shop-2",
                                    CONTAINER,
                                    "shop-2",
                                    waits ? SHOP : null);
                        },
                        journal,
                        journal,
                        ends::read,
                        url -> java.util.Optional.empty(),
                        instance -> java.util.Optional.empty()));
    }

    private int run(final String... arguments) {
        out = new StringWriter();
        err = new StringWriter();
        final ReviewOpenCommand command = new ReviewOpenCommand();
        command.setContext(NoAccount.context());
        command.use(using != null ? using : open());
        return new CommandLine(command)
                .setOut(new PrintWriter(out))
                .setErr(new PrintWriter(err))
                .execute(arguments);
    }

    private void opened() throws Exception {
        journal.keep(
                SHOP,
                new ReviewComment(
                        new LineAnchor("src/Shop.java", Side.NEW, 3, 5),
                        new Body("Keep the order."),
                        null),
                Origin.of(new OwnerAccount()),
                null);
    }

    @Test
    void anAnswerIsOneJsonObjectByTheDaemonsNamesAndExitsZero() throws Exception {
        opened();

        final int code = run("-p", "uc", "--gate-push", "shop-2", "--json");

        assertThat(code).as("answered").isZero();
        assertThat(err.toString()).as("nothing to say on standard error").isEmpty();
        assertThat(Json.parse(out.toString()))
                .as("the answer as the daemon gives it")
                .isInstanceOfSatisfying(
                        Map.class,
                        answer ->
                                assertThat(answer)
                                        .containsEntry("decision", "OPEN")
                                        .containsEntry("head", HEAD)
                                        .containsEntry("decisionSince", "2026-11-25T08:00:00Z")
                                        .containsEntry("complete", true)
                                        .containsKeys("points", "findings", "held", "version"));
        assertThat(out.toString().strip().lines()).as("one line, one object").hasSize(1);
    }

    @Test
    void theTextFormEscapesEveryStringFromOutside() throws Exception {
        journal.keep(
                SHOP,
                new ReviewComment(
                        new LineAnchor("src/\u001b[2JShop.java", Side.NEW, 3, 5),
                        new Body("Keep the order."),
                        null),
                Origin.of(new OwnerAccount()),
                null);

        final int code = run("-p", "uc", "--gate-push", "shop-2");

        assertThat(code).as("answered").isZero();
        assertThat(out.toString())
                .as("the decision, the point and the version")
                .contains("decision  OPEN  since 2026-11-25T08:00:00Z")
                .contains("owner account  2026-11-25T09:30:00Z")
                .contains("version   ")
                .doesNotContain("\u001b");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "--gate-push shop-2 --pull-request feature",
                "--gate-push shop-2 --own-pull-request --task shop-2",
                "--own-pull-request",
                "--gate-push shop-2 --role OWNER",
                "--pull-request feature --repository shop",
                "--pull-request feature --task shop-2",
                "--pull-request https://github.com/uc/shop/pull/7"
            })
    void aRefusedArgumentExitsSixtyFourWithASentenceAndNothingOnStandardOutput(
            final String arguments) {
        final String[] given = ("-p uc " + arguments).strip().split(" ");

        final int code = run(given);

        assertThat(code).as("a refused argument: %s", arguments).isEqualTo(64);
        assertThat(out.toString()).as("nothing on standard output").isEmpty();
        assertThat(err.toString()).as("a sentence on standard error").startsWith("porelo: ");
    }

    @Test
    void aTypedRefusalExitsSixtyNineWithItsNameAndFieldsAsJson() {
        final int code = run("-p", "uc", "--gate-push", "cart-1", "--json");

        assertThat(code).as("a typed refusal").isEqualTo(69);
        assertThat(Json.parse(out.toString()))
                .as("the error's name and fields")
                .isEqualTo(
                        Map.of(
                                "refused",
                                "PlaceUnreadable",
                                "reason",
                                "NoSuchPush",
                                "detail",
                                "no task is recorded for cart-1"));
        assertThat(err.toString()).as("a sentence").contains("NoSuchPush");
    }

    @Test
    void aHeadNotInTheGateIsRefusedWithTheHeadOnBothStreams() {
        refusing = new ReviewOpen.Refused("HeadNotInGate", Map.of("head", HEAD));

        final int code = run("-p", "uc", "--gate-push", "shop-2", "--json");

        assertThat(code).as("a typed refusal, never 70").isEqualTo(69);
        assertThat(Json.parse(out.toString()))
                .as("the error and the head looked for")
                .isEqualTo(Map.of("refused", "HeadNotInGate", "head", HEAD));
        assertThat(err.toString())
                .as("the sentence names the head")
                .contains("HeadNotInGate", HEAD);
    }

    @Test
    void aPullRequestsAnswerIsTheDaemonsShapeAsJson() throws Exception {
        final ReviewOpenPullRequestTest forge = new ReviewOpenPullRequestTest();
        forge.page(
                org.fuin.porelo.gate.review.Completeness.COMPLETE,
                ReviewOpenPullRequestTest.opening(
                        "101", ReviewOpenPullRequestTest.OCTOCAT, ReviewOpenPullRequestTest.AT));
        using = forge.open;

        final int code =
                run(
                        "-p",
                        "uc",
                        "--pull-request",
                        ReviewOpenPullRequestTest.ADDRESS,
                        "--task",
                        ReviewOpenPullRequestTest.TASK,
                        "--json");

        assertThat(code).as("answered").isZero();
        assertThat(Json.parse(out.toString()))
                .as("the fields the daemon writes for the same call")
                .isEqualTo(
                        Json.parse(
                                Json.write(
                                        forge.open
                                                .answer(
                                                        "uc",
                                                        ReviewOpenPullRequestTest.PLACE,
                                                        null,
                                                        ReviewOpenPullRequestTest.TASK)
                                                .fields())));
    }

    @Test
    void aPullRequestAtAnUnknownHostIsATypedRefusalNamingTheHost() {
        using = new ReviewOpenPullRequestTest().open;

        final int code =
                run(
                        "-p",
                        "uc",
                        "--pull-request",
                        "https://forge.example.org/uc/shop/pull/7",
                        "--task",
                        "shop-2",
                        "--json");

        assertThat(code).as("a typed refusal").isEqualTo(69);
        assertThat(Json.parse(out.toString()))
                .as("the error and its host")
                .isEqualTo(Map.of("refused", "UnknownForge", "host", "forge.example.org"));
    }

    @Test
    void anythingElseUnreadableExitsSeventyWithNothingOnStandardOutput() {
        broken = new IllegalStateException("the gate's mirror cannot be read");

        final int code = run("-p", "uc", "--gate-push", "shop-2", "--json");

        assertThat(code).as("unreadable").isEqualTo(70);
        assertThat(out.toString()).as("nothing on standard output").isEmpty();
        assertThat(err.toString()).as("a sentence").contains("could not be read");
    }

    @Test
    void theAnswersOfEachDecisionAndOfARefusalAreRecordedForTheHandOver() throws Exception {
        Files.createDirectories(HANDOVER);
        opened();
        record("open", "-p", "uc", "--gate-push", "shop-2", "--json");

        waits = false;
        ends.keep(CONTAINER, PushEnd.rejected("shop-2", HEAD, 1, ENDED));
        record("returned", "-p", "uc", "--gate-push", "shop-2", "--json");

        ends.keep(CONTAINER, PushEnd.rejected("shop-2", HEAD, 0, ENDED));
        journal.state(
                SHOP,
                journal.threads(SHOP).getFirst().id(),
                org.fuin.porelo.gate.review.ThreadState.RESOLVED);
        record("rejected", "-p", "uc", "--gate-push", "shop-2", "--json");

        ends.keep(CONTAINER, PushEnd.approved("shop-2", HEAD, "f".repeat(40), "main", ENDED));
        record("approved", "-p", "uc", "--gate-push", "shop-2", "--json");

        ends.keep(CONTAINER, PushEnd.landed("shop-2", HEAD, "main", ENDED));
        record("landed", "-p", "uc", "--gate-push", "shop-2", "--json");

        final int refused = run("-p", "uc", "--gate-push", "cart-1", "--json");
        Files.writeString(
                HANDOVER.resolve("review-open-refused.txt"),
                "$ porelo review open -p uc --gate-push cart-1 --json\nexit "
                        + refused
                        + "\nstdout: "
                        + out
                        + "stderr: "
                        + err,
                StandardCharsets.UTF_8);
        assertThat(refused).as("the refusal's exit").isEqualTo(69);
    }

    private void record(final String decision, final String... arguments) throws Exception {
        final int code = run(arguments);
        assertThat(code).as("%s answered", decision).isZero();
        assertThat(Json.parse(out.toString()))
                .as("%s's decision", decision)
                .isInstanceOfSatisfying(
                        Map.class,
                        answer ->
                                assertThat(answer)
                                        .containsEntry(
                                                "decision",
                                                decision.toUpperCase(java.util.Locale.ROOT)));
        Files.writeString(
                HANDOVER.resolve("review-open-" + decision + ".json"),
                out.toString(),
                StandardCharsets.UTF_8);
    }
}
