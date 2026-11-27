package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.fuin.porelo.gate.review.CommentReading;
import org.fuin.porelo.gate.review.Conversation;
import org.fuin.porelo.gate.review.ForgeAccount;
import org.fuin.porelo.gate.review.ForgeCredential;
import org.fuin.porelo.gate.review.ForgeFileUnreadable;
import org.fuin.porelo.gate.review.ForgeInstance;
import org.fuin.porelo.gate.review.ForgeInstances;
import org.fuin.porelo.gate.review.Held;
import org.fuin.porelo.gate.review.Pinned;
import org.fuin.porelo.gate.review.PlaceUnreadable;
import org.fuin.porelo.gate.review.PullRequestRef;
import org.fuin.porelo.gate.review.PushEnd;
import org.fuin.porelo.gate.review.ReviewPlace;
import org.fuin.porelo.gate.review.Right;
import org.fuin.porelo.gate.review.Task;
import org.fuin.porelo.gate.review.TaskParts;
import org.fuin.porelo.gate.review.WriteOutcome;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a task's recorded parts allow it, per parts and place, read from its state and else its
 * durable record: push rights, the entry and account its items go out under, thread rights and
 * pinning.
 */
class RecordedTaskRightsTest {

    private static final String TASK = "shop-2";

    private static final String HEAD = "a".repeat(40);

    private static final String OTHER_HEAD = "b".repeat(40);

    private static final String FORGE = "github.com";

    private static final ReviewPlace PULL_REQUEST =
            new NamedPlace("https://github.com/acme/shop/pull/7");

    private static final ReviewPlace GATE_PUSH = new NamedPlace("shop/shop-2");

    private static final String TASK_ENTRY = "porelo-task/shop-2-container/forge-token";

    private static final String BUILDS_ENTRY = "shop-builds";

    private static final String PERSON_ENTRY = "git.token.github.com";

    private static final String TOKEN_PREFIX = "ghp_secret_";

    @TempDir Path states;

    /** Where the durable records lie, apart from the states. */
    private Path records() {
        return states.resolve("durable");
    }

    private final Map<String, String> vault = new HashMap<>();

    private final Map<String, PushEnd> ends = new HashMap<>();

    private boolean onHost;

    private @Nullable String projectBuilds;

    private boolean endUnreadable;

    private static final String UPSTREAM = "https://github.com/shop/app.git";

    private static final String BRANCH = "porelo/shop-2";

    /** What each task's gate last forwarded, by task, upstream and branch. */
    private final Map<String, String> forwarded = new HashMap<>();

    private boolean forwardedUnreadable;

    private final List<ForgeInstance> forges =
            new ArrayList<>(
                    List.of(new ForgeInstance(FORGE, "github", CommentReading.EXTENDED, null)));

    private boolean forgeFileUnreadable;

    @BeforeEach
    void fillVault() {
        vault.put(TASK_ENTRY, TOKEN_PREFIX + "task");
        vault.put(BUILDS_ENTRY, TOKEN_PREFIX + "builds");
        vault.put(PERSON_ENTRY, TOKEN_PREFIX + "person");
    }

    /**
     * Today's author, as a start naming no part records it; a test of other parts records over it.
     */
    @BeforeEach
    void recordOwnWork() throws IOException {
        recorded(TaskParts.ownWork());
    }

    @Test
    void aTaskThatRecordedNoPartsIsRefusedEveryAnswerNeverTakenForAnAuthor() throws IOException {
        Files.delete(states.resolve(TASK).resolve(RunPlace.FILE));
        refusesEveryAnswer(RunPlace.FILE);
    }

    @Test
    void aTaskAnswersThePartsItsStartRecorded() throws IOException {
        recorded(
                org.fuin.porelo.gate.review.TaskParts.anothersWork(
                        java.util.List.of(
                                org.fuin.porelo.gate.review.Right.REPLY,
                                org.fuin.porelo.gate.review.Right.OPEN_THREADS)));
        assertThat(role().parts(TASK))
                .as("recorded reviewer")
                .isEqualTo(TaskParts.anothersWork(List.of(Right.REPLY, Right.OPEN_THREADS)));
        recorded(TaskParts.ownWork());
        assertThat(role().parts(TASK)).as("recorded author").isEqualTo(TaskParts.ownWork());
    }

    @Test
    void aTaskWhoseStateHoldsNoRecordAnswersAsItsDurableRecordHoldsAnothersWork()
            throws IOException {
        // After a reboot the state is gone and the durable record still holds the parts.
        Files.delete(states.resolve(TASK).resolve(RunPlace.FILE));
        final Path record = Files.createDirectories(records().resolve(TASK));
        new RunPlace(
                        org.fuin.porelo.gate.review.TaskParts.anothersWork(
                                java.util.List.of(
                                        org.fuin.porelo.gate.review.Right.REPLY,
                                        org.fuin.porelo.gate.review.Right.OPEN_THREADS)),
                        null,
                        "shop-1")
                .writeTo(record);

        final RecordedTaskRights rights = role();

        assertThat(rights.parts(TASK))
                .as("the durable record's parts")
                .isEqualTo(
                        org.fuin.porelo.gate.review.TaskParts.anothersWork(
                                java.util.List.of(
                                        org.fuin.porelo.gate.review.Right.REPLY,
                                        org.fuin.porelo.gate.review.Right.OPEN_THREADS)));
        assertThat(rights.mayPush(TASK)).as("another task's work pushes").isFalse();
        assertThat(rights.mayOpenThread(TASK, GATE_PUSH)).as("it opens a thread").isTrue();
        assertThat(rights.mayReply(TASK, GATE_PUSH)).as("it replies").isTrue();
        assertThat(rights.mayPin(TASK, OTHER_HEAD)).as("pinned to any head").isTrue();
    }

    @Test
    void aTaskAtItsOwnWorkWithOpenThreadsMayOpenAThreadAtItsOwnPullRequest() throws IOException {
        // The refusal of a task at its own work is gone with the role: the right alone decides.
        recorded(
                new TaskParts(
                        TaskParts.Workspace.OWN,
                        List.of(),
                        true,
                        TaskParts.Account.AUTHOR,
                        Set.of(Right.PUSH, Right.REPLY, Right.OPEN_THREADS)));

        final RecordedTaskRights rights = role();

        assertThat(rights.mayOpenThread(TASK, PULL_REQUEST))
                .as("opens a thread at its own pull request")
                .isTrue();
        assertThat(rights.mayReply(TASK, PULL_REQUEST)).as("replies there").isTrue();
        assertThat(rights.mayPush(TASK)).as("pushes").isTrue();
        assertThat(rights.mayResolve(TASK, PULL_REQUEST))
                .as("resolves without the right")
                .isFalse();
        assertThat(rights.mayReject(TASK, PULL_REQUEST)).as("rejects without the right").isFalse();
    }

    @Test
    void onlyAReaderHoldingRejectMayRejectAndOnlyAtAWaitingPush() throws IOException {
        final ReviewPlace waiting =
                new WaitingPushPlace(
                        "shop",
                        null,
                        "shop-1",
                        ReviewStandIns.parts(
                                new ReviewStandIns.Check(),
                                new ReviewStandIns.Store(),
                                new ReviewStandIns.Writer()),
                        (task, push) -> {});
        recorded(TaskParts.anothersWork(List.of(Right.REPLY, Right.REJECT)));
        assertThat(role().mayReject(TASK, waiting)).as("a reader at a waiting push").isTrue();
        assertThat(role().mayReject(TASK, PULL_REQUEST)).as("a reader at a pull request").isFalse();
        assertThat(role().mayReject(TASK, GATE_PUSH))
                .as("at a place that is no waiting push of another task")
                .isFalse();

        // A record no start makes: own work holding reject, planted beside the task.
        recorded(
                new TaskParts(
                        TaskParts.Workspace.OWN,
                        List.of(),
                        true,
                        TaskParts.Account.AUTHOR,
                        Set.of(Right.REPLY, Right.REJECT)));
        assertThat(role().mayReject(TASK, waiting)).as("own work holding reject").isFalse();
    }

    @Test
    void anAuthorMayPushAndAReviewerNever() throws IOException {
        assertThat(role().mayPush(TASK)).as("author pushes").isTrue();
        recorded(
                org.fuin.porelo.gate.review.TaskParts.anothersWork(
                        java.util.List.of(
                                org.fuin.porelo.gate.review.Right.REPLY,
                                org.fuin.porelo.gate.review.Right.OPEN_THREADS)));
        assertThat(role().mayPush(TASK)).as("reviewer pushes").isFalse();
    }

    @Test
    void anAuthorOnlyRepliesAndAReviewerOpensThreadsAtEitherPlace() throws IOException {
        assertThat(role().mayOpenThread(TASK, PULL_REQUEST))
                .as("author opens a thread at a pull request")
                .isFalse();
        assertThat(role().mayOpenThread(TASK, GATE_PUSH))
                .as("author opens a thread at a gate push")
                .isFalse();
        recorded(
                org.fuin.porelo.gate.review.TaskParts.anothersWork(
                        java.util.List.of(
                                org.fuin.porelo.gate.review.Right.REPLY,
                                org.fuin.porelo.gate.review.Right.OPEN_THREADS)));
        assertThat(role().mayOpenThread(TASK, PULL_REQUEST))
                .as("reviewer opens a thread at a pull request")
                .isTrue();
        assertThat(role().mayOpenThread(TASK, GATE_PUSH))
                .as("reviewer opens a thread at a gate push")
                .isTrue();
    }

    @Test
    void anAuthorIsPinnedOnlyToAHeadItsOwnGateTook() {
        assertThat(role().mayPin(TASK, HEAD)).as("author with no push at its gate").isFalse();
        ends.put(TASK, PushEnd.waiting(TASK, HEAD, Instant.EPOCH));
        assertThat(role().mayPin(TASK, HEAD)).as("author at the head its gate took").isTrue();
        assertThat(role().mayPin(TASK, OTHER_HEAD))
                .as("author at a head its gate did not take")
                .isFalse();
        ends.put(TASK, PushEnd.rejected(TASK, HEAD, 2, Instant.EPOCH));
        assertThat(role().mayPin(TASK, HEAD))
                .as("author at its rejected head, whose threads it answers")
                .isTrue();
    }

    @Test
    void anAuthorAtAPullRequestIsPinnedOnlyToTheCommitItsGateForwardedToThatBranch() {
        // Taken at its gate but never forwarded: not the pull request's head.
        ends.put(TASK, PushEnd.waiting(TASK, HEAD, Instant.EPOCH));
        assertPinRefused(HEAD, BRANCH, "a head its gate took and did not forward");

        forwarded.put(TASK + " " + UPSTREAM + " " + BRANCH, HEAD);
        assertThat(role().mayPinAtPullRequest(TASK, UPSTREAM, BRANCH, HEAD))
                .as("the commit its gate forwarded to the branch")
                .isTrue();
        assertPinRefused(OTHER_HEAD, BRANCH, "another head");
        assertPinRefused(HEAD, "porelo/other", "its head at another branch");
    }

    @Test
    void anAuthorWhoseForwardedRecordCannotBeReadIsPinnedAtNoPullRequest() {
        forwarded.put(TASK + " " + UPSTREAM + " " + BRANCH, HEAD);
        forwardedUnreadable = true;
        assertPinRefused(HEAD, BRANCH, "an unreadable record");
    }

    @Test
    void aReviewerMayBePinnedAtAPullRequestToAnyHead() throws IOException {
        recorded(
                org.fuin.porelo.gate.review.TaskParts.anothersWork(
                        java.util.List.of(
                                org.fuin.porelo.gate.review.Right.REPLY,
                                org.fuin.porelo.gate.review.Right.OPEN_THREADS)));
        assertThat(role().mayPinAtPullRequest(TASK, UPSTREAM, BRANCH, OTHER_HEAD))
                .as("reviewer at any head")
                .isTrue();
    }

    private void assertPinRefused(final String head, final String branch, final String what) {
        final RunPlaceCheck.Refused refused =
                catchThrowableOfType(
                        () -> role().mayPinAtPullRequest(TASK, UPSTREAM, branch, head),
                        RunPlaceCheck.Refused.class);
        assertThat(refused).as("refused: %s", what).isNotNull();
        assertThat(refused.refusal().name()).as("by name: %s", what).isEqualTo("NotTheTasksPush");
        assertThat(refused.refusal().fields())
                .as("the head refused: %s", what)
                .isEqualTo(Map.of("head", head));
    }

    @Test
    void anAuthorWhoseGateRecordCannotBeReadIsPinnedNowhere() {
        ends.put(TASK, PushEnd.waiting(TASK, HEAD, Instant.EPOCH));
        endUnreadable = true;
        assertThat(role().mayPin(TASK, HEAD)).as("author with an unreadable record").isFalse();
    }

    @Test
    void aReviewerMayBePinnedToAnyHead() throws IOException {
        recorded(
                org.fuin.porelo.gate.review.TaskParts.anothersWork(
                        java.util.List.of(
                                org.fuin.porelo.gate.review.Right.REPLY,
                                org.fuin.porelo.gate.review.Right.OPEN_THREADS)));
        assertThat(role().mayPin(TASK, OTHER_HEAD)).as("reviewer at any head").isTrue();
    }

    @Test
    void anAuthorOnAHostPostsAsItsOwnTaskAccountNeverAsThePerson() throws PlaceUnreadable {
        onHost = true;
        assertThat(role().postingEntry(TASK, PULL_REQUEST))
                .as("author's entry on a host")
                .isEqualTo(TASK_ENTRY)
                .isNotEqualTo(PERSON_ENTRY);
        assertThat(role().postingAccount(TASK, PULL_REQUEST))
                .as("author's account on a host")
                .isEqualTo(account(TASK_ENTRY));
    }

    @Test
    void anAuthorElsewherePostsAsTheProjectsBuildsCredentialsAccount() throws PlaceUnreadable {
        projectBuilds = BUILDS_ENTRY;
        assertThat(role().postingEntry(TASK, PULL_REQUEST))
                .as("author's entry off a host")
                .isEqualTo(BUILDS_ENTRY);
        assertThat(role().postingAccount(TASK, PULL_REQUEST))
                .as("author's account off a host")
                .isEqualTo(account(BUILDS_ENTRY));
        onHost = true;
        assertThat(role().postingEntry(TASK, PULL_REQUEST))
                .as("author's entry on a host whose project names its own builds")
                .isEqualTo(BUILDS_ENTRY);
    }

    @Test
    void anAuthorOffAHostWithoutBuildsPostsUnderItsOwnAuthorEntryForTheForge()
            throws PlaceUnreadable {
        vault.put("git.author." + FORGE, TOKEN_PREFIX + "author");
        assertThat(role().postingEntry(TASK, PULL_REQUEST))
                .as("author's entry off a host, the project naming no builds")
                .isEqualTo("git.author." + FORGE)
                .isNotEqualTo(PERSON_ENTRY);
        assertThat(role().postingAccount(TASK, PULL_REQUEST))
                .as("author's account off a host")
                .isEqualTo(account("git.author." + FORGE));
    }

    @Test
    void anAuthorOffAHostWithoutItsAuthorEntryIsRefusedNamingTheEntryToStore() {
        final PlaceUnreadable refused =
                catchThrowableOfType(
                        PlaceUnreadable.class, () -> role().postingEntry(TASK, PULL_REQUEST));
        assertThat(refused).as("author off a host with no entry of its own").isNotNull();
        assertThat(refused.reason())
                .as("reason")
                .isEqualTo(PlaceUnreadable.Reason.MISSING_FORGE_TOKEN);
        assertThat(refused.getMessage())
                .as("the entry to add, and how")
                .contains("git.author." + FORGE, "porelo vault put git.author." + FORGE)
                .doesNotContain(TOKEN_PREFIX);
    }

    @Test
    void anAuthorAtItsGatePushPostsAsTheTaskWithNoForgeEntry() {
        vault.clear();
        assertThat(role().postingAccount(TASK, GATE_PUSH))
                .as("author's producer at a gate push off a host, no builds credential")
                .isEqualTo(new Task(TASK));
        onHost = true;
        assertThat(role().postingAccount(TASK, GATE_PUSH))
                .as("author's producer at a gate push on a host, its entry not held")
                .isEqualTo(new Task(TASK));
    }

    @Test
    void anAccountIsAnsweredOnlyForAnEntryTheVaultHolds() {
        onHost = true;
        vault.remove(TASK_ENTRY);
        assertThatThrownBy(() -> role().postingAccount(TASK, PULL_REQUEST))
                .as("author's account without its entry at a pull request")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(TASK_ENTRY);
    }

    @Test
    void anAuthorNeverPostsUnderAForgesCredentialWhateverTheProjectNames() {
        projectBuilds = PERSON_ENTRY;
        final PlaceUnreadable refused =
                catchThrowableOfType(
                        PlaceUnreadable.class, () -> role().postingEntry(TASK, PULL_REQUEST));
        assertThat(refused).as("author's entry naming a forge's credential").isNotNull();
        assertThat(refused.reason())
                .as("reason")
                .isEqualTo(PlaceUnreadable.Reason.MISSING_FORGE_TOKEN);
        assertThat(refused.getMessage())
                .as("the refusal's detail")
                .doesNotContain(PERSON_ENTRY)
                .doesNotContain(FORGE);
        assertThatThrownBy(() -> role().postingAccount(TASK, PULL_REQUEST))
                .as("author's account naming a forge's credential")
                .isInstanceOf(IllegalStateException.class)
                .message()
                .doesNotContain(PERSON_ENTRY);
    }

    @Test
    void aPlaceNameThatIsNoHttpsAddressWithAHostIsRefused() throws IOException {
        for (final TaskParts recordedRole :
                List.of(
                        TaskParts.ownWork(),
                        org.fuin.porelo.gate.review.TaskParts.anothersWork(
                                java.util.List.of(
                                        org.fuin.porelo.gate.review.Right.REPLY,
                                        org.fuin.porelo.gate.review.Right.OPEN_THREADS)))) {
            recorded(recordedRole);
            projectBuilds = BUILDS_ENTRY;
            for (final String name :
                    List.of(
                            "https://",
                            "https://git hub.com/acme/shop/pull/7",
                            "HTTPS://github.com/acme/shop/pull/7")) {
                final ReviewPlace place = new NamedPlace(name);
                final PlaceUnreadable refused =
                        catchThrowableOfType(
                                PlaceUnreadable.class, () -> role().postingEntry(TASK, place));
                assertThat(refused).as("%s's entry at '%s'", recordedRole, name).isNotNull();
                assertThat(refused.reason())
                        .as("reason at '%s'", name)
                        .isEqualTo(PlaceUnreadable.Reason.UNKNOWN_FORGE);
                assertThatThrownBy(() -> role().postingAccount(TASK, place))
                        .as("%s's account at '%s'", recordedRole, name)
                        .isExactlyInstanceOf(IllegalStateException.class);
            }
        }
    }

    @Test
    void aRunPlaceFileThatDoesNotReadIsNoAuthor() throws IOException {
        // An old record that holds a role and no parts reads as none of them.
        final Path state = Files.createDirectories(states.resolve(TASK));
        Files.writeString(state.resolve(RunPlace.FILE), "{\"role\": \"AUTH");
        refusesEveryAnswer("\"role\"");
        Files.writeString(
                state.resolve(RunPlace.FILE),
                "{\"role\": \"OWNER\", \"place\": {\"gatePush\": {\"name\": \"shop-2\"}}}");
        refusesEveryAnswer("OWNER");
        Files.delete(state.resolve(RunPlace.FILE));
        Files.createDirectory(state.resolve(RunPlace.FILE));
        refusesEveryAnswer(RunPlace.FILE + "/");
    }

    private void refusesEveryAnswer(final String content) {
        final RecordedTaskRights role = role();
        ends.put(TASK, PushEnd.waiting(TASK, HEAD, Instant.EPOCH));
        projectBuilds = BUILDS_ENTRY;
        final List<org.assertj.core.api.ThrowableAssert.ThrowingCallable> asked =
                List.of(
                        () -> role.parts(TASK),
                        () -> role.mayPush(TASK),
                        () -> role.mayOpenThread(TASK, PULL_REQUEST),
                        () -> role.mayReply(TASK, GATE_PUSH),
                        () -> role.mayResolve(TASK, GATE_PUSH),
                        () -> role.mayReject(TASK, GATE_PUSH),
                        () -> role.mayPin(TASK, HEAD),
                        () -> role.mayPinAtPullRequest(TASK, UPSTREAM, BRANCH, HEAD),
                        () -> role.postingEntry(TASK, PULL_REQUEST),
                        () -> role.postingAccount(TASK, PULL_REQUEST));
        for (final var call : asked) {
            assertThatThrownBy(call)
                    .as("an answer over a run-place file that does not read (%s)", content)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(TASK)
                    .message()
                    .doesNotContain(content);
        }
    }

    @Test
    void aReviewerAtAPullRequestPostsAsTheOwnerAccountsForgeAccount()
            throws IOException, PlaceUnreadable {
        recorded(
                org.fuin.porelo.gate.review.TaskParts.anothersWork(
                        java.util.List.of(
                                org.fuin.porelo.gate.review.Right.REPLY,
                                org.fuin.porelo.gate.review.Right.OPEN_THREADS)));
        assertThat(role().postingEntry(TASK, PULL_REQUEST))
                .as("reviewer's entry at a pull request")
                .isEqualTo(PERSON_ENTRY);
        assertThat(role().postingAccount(TASK, PULL_REQUEST))
                .as("reviewer's account at a pull request")
                .isEqualTo(account(PERSON_ENTRY));
    }

    @Test
    void aReviewerAtAGatePushPostsAsTheTaskWithNoForgeEntry() throws IOException {
        // At a gate push no account is used: the reading task writes as itself, as the pushing one.
        for (final TaskParts parts :
                List.of(
                        org.fuin.porelo.gate.review.TaskParts.anothersWork(
                                java.util.List.of(
                                        org.fuin.porelo.gate.review.Right.REPLY,
                                        org.fuin.porelo.gate.review.Right.OPEN_THREADS)),
                        TaskParts.ownWork())) {
            recorded(parts);
            final PlaceUnreadable refused =
                    catchThrowableOfType(
                            PlaceUnreadable.class, () -> role().postingEntry(TASK, GATE_PUSH));
            assertThat(refused).as("%s's entry at a gate push", parts.workspace()).isNotNull();
            assertThat(refused.reason())
                    .as("reason of the refusal")
                    .isEqualTo(PlaceUnreadable.Reason.FORGE_ADAPTER_MISSING);
            assertThat(role().postingAccount(TASK, GATE_PUSH))
                    .as("%s's producer at a gate push", parts.workspace())
                    .isEqualTo(new Task(TASK));
        }
    }

    @Test
    void aMissingTaskEntryRefusesNamingItAndNeverThePersonsEntry() {
        onHost = true;
        vault.remove(TASK_ENTRY);
        final PlaceUnreadable refused =
                catchThrowableOfType(
                        PlaceUnreadable.class, () -> role().postingEntry(TASK, PULL_REQUEST));
        assertThat(refused).as("author's missing entry").isNotNull();
        assertThat(refused.reason())
                .as("reason")
                .isEqualTo(PlaceUnreadable.Reason.MISSING_FORGE_TOKEN);
        assertThat(refused.getMessage())
                .as("the refusal's detail")
                .contains(TASK_ENTRY)
                .doesNotContain(PERSON_ENTRY);
    }

    @Test
    void anAuthorOffAHostWithoutABuildsCredentialIsRefused() {
        final PlaceUnreadable refused =
                catchThrowableOfType(
                        PlaceUnreadable.class, () -> role().postingEntry(TASK, PULL_REQUEST));
        assertThat(refused).as("author without any entry").isNotNull();
        assertThat(refused.reason())
                .as("reason")
                .isEqualTo(PlaceUnreadable.Reason.MISSING_FORGE_TOKEN);
        assertThat(refused.getMessage()).as("the refusal's detail").doesNotContain(PERSON_ENTRY);
    }

    @Test
    void aMissingOwnerEntryRefusesAReviewerNamingIt() throws IOException {
        recorded(
                org.fuin.porelo.gate.review.TaskParts.anothersWork(
                        java.util.List.of(
                                org.fuin.porelo.gate.review.Right.REPLY,
                                org.fuin.porelo.gate.review.Right.OPEN_THREADS)));
        vault.remove(PERSON_ENTRY);
        final PlaceUnreadable refused =
                catchThrowableOfType(
                        PlaceUnreadable.class, () -> role().postingEntry(TASK, PULL_REQUEST));
        assertThat(refused).as("reviewer's missing entry").isNotNull();
        assertThat(refused.reason())
                .as("reason")
                .isEqualTo(PlaceUnreadable.Reason.MISSING_FORGE_TOKEN);
        assertThat(refused.getMessage()).as("the refusal's detail").contains(PERSON_ENTRY);
    }

    @Test
    void aReviewerAtAForgeTheMachineDoesNotKnowIsRefused() throws IOException {
        recorded(
                org.fuin.porelo.gate.review.TaskParts.anothersWork(
                        java.util.List.of(
                                org.fuin.porelo.gate.review.Right.REPLY,
                                org.fuin.porelo.gate.review.Right.OPEN_THREADS)));
        forges.clear();
        refusedAsUnknownForge("unknown forge", FORGE);
    }

    @Test
    void aReviewerAtAForgeWhoseEntryCannotBeReadIsRefused() throws IOException {
        recorded(
                org.fuin.porelo.gate.review.TaskParts.anothersWork(
                        java.util.List.of(
                                org.fuin.porelo.gate.review.Right.REPLY,
                                org.fuin.porelo.gate.review.Right.OPEN_THREADS)));
        forges.set(0, new ForgeInstance(FORGE, null, CommentReading.EXTENDED, "no adapter named"));
        refusedAsUnknownForge("unreadable forge entry", "no adapter named");
    }

    @Test
    void aReviewerWhileTheForgeFileCannotBeReadIsRefused() throws IOException {
        recorded(
                org.fuin.porelo.gate.review.TaskParts.anothersWork(
                        java.util.List.of(
                                org.fuin.porelo.gate.review.Right.REPLY,
                                org.fuin.porelo.gate.review.Right.OPEN_THREADS)));
        forgeFileUnreadable = true;
        refusedAs(
                PlaceUnreadable.Reason.FORGE_FILE_UNREADABLE,
                "unreadable forge file",
                "forge file cut off");
    }

    private void refusedAsUnknownForge(final String why, final String detail) {
        refusedAs(PlaceUnreadable.Reason.UNKNOWN_FORGE, why, detail);
    }

    private void refusedAs(
            final PlaceUnreadable.Reason reason, final String why, final String detail) {
        final PlaceUnreadable refused =
                catchThrowableOfType(
                        PlaceUnreadable.class, () -> role().postingEntry(TASK, PULL_REQUEST));
        assertThat(refused).as("reviewer's entry, %s", why).isNotNull();
        assertThat(refused.reason()).as("reason, %s", why).isEqualTo(reason);
        assertThat(refused.getMessage()).as("the refusal's detail, %s", why).contains(detail);
        assertThatThrownBy(() -> role().postingAccount(TASK, PULL_REQUEST))
                .as("reviewer's account, %s", why)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(detail);
    }

    @Test
    void theTasksOwnEntryIsNamedAsTheTasksSecretsKeepIt() {
        assertThat(RecordedTaskRights.taskForgeEntry("c0ffee"))
                .as("the task's own forge entry")
                .isEqualTo(TaskSecrets.PREFIX + "c0ffee" + TaskSecrets.FORGE);
    }

    private RecordedTaskRights role() {
        return new RecordedTaskRights(
                task -> states.resolve(task),
                task -> records().resolve(task),
                new StandInEntries(),
                new StandInForges(),
                RecordedTaskRightsTest::account,
                task -> {
                    if (endUnreadable) {
                        throw new IOException("cut off");
                    }
                    return Optional.ofNullable(ends.get(task));
                },
                (task, upstream, branch) -> {
                    if (forwardedUnreadable) {
                        throw new IOException("cut off");
                    }
                    return Optional.ofNullable(forwarded.get(task + " " + upstream + " " + branch));
                });
    }

    private void recorded(final TaskParts parts) throws IOException {
        final Path state = Files.createDirectories(states.resolve(TASK));
        new RunPlace(parts, null, TASK).writeTo(state);
    }

    private static ForgeAccount account(final PullRequestRef pullRequest, final String entry) {
        return account(pullRequest.forge(), entry);
    }

    private static ForgeAccount account(final String forge, final String entry) {
        return new ForgeAccount(forge, "id-" + entry, "account-" + entry.replace('/', '-'));
    }

    private static ForgeAccount account(final String entry) {
        return account(FORGE, entry);
    }

    /** The vault's entry names as a machine would answer them, with tokens behind them. */
    private final class StandInEntries implements RecordedTaskRights.Entries {

        @Override
        public boolean onHost() {
            return onHost;
        }

        @Override
        public String taskAccount(final String task) {
            return TASK_ENTRY;
        }

        @Override
        public Optional<String> projectBuilds(final String task) {
            return Optional.ofNullable(projectBuilds);
        }

        @Override
        public boolean held(final String entry) {
            return vault.containsKey(entry);
        }
    }

    /** The machine's forge file, or one that cannot be read. */
    private final class StandInForges implements ForgeInstances {

        @Override
        public Optional<ForgeInstance> of(final URI url) throws ForgeFileUnreadable {
            return all().stream().filter(each -> each.host().equals(url.getHost())).findFirst();
        }

        @Override
        public List<ForgeInstance> all() throws ForgeFileUnreadable {
            if (forgeFileUnreadable) {
                throw new ForgeFileUnreadable("forge file cut off");
            }
            return List.copyOf(forges);
        }

        @Override
        public void set(final String host, final String adapter, final CommentReading reading) {
            throw new UnsupportedOperationException("not asked");
        }
    }

    /** A place known only by its name; nothing else of it is asked here. */
    private record NamedPlace(String name) implements ReviewPlace {

        @Override
        public Pinned pin(final String task) {
            throw new UnsupportedOperationException("not asked");
        }

        @Override
        public Conversation conversation(final Pinned pinned, final ForgeCredential credential) {
            throw new UnsupportedOperationException("not asked");
        }

        @Override
        public List<byte[]> pages(final Pinned pinned, final Conversation conversation) {
            throw new UnsupportedOperationException("not asked");
        }

        @Override
        public WriteOutcome write(final Held held, final ForgeCredential credential) {
            throw new UnsupportedOperationException("not asked");
        }

        @Override
        public CommentReading reading() {
            throw new UnsupportedOperationException("not asked");
        }
    }
}
