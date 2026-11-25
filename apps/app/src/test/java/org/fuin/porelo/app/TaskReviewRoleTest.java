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
import org.fuin.porelo.gate.review.PushEnd;
import org.fuin.porelo.gate.review.ReviewPlace;
import org.fuin.porelo.gate.review.Role;
import org.fuin.porelo.gate.review.Task;
import org.fuin.porelo.gate.review.WriteOutcome;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a task's role allows it, per role and place, read from the role its run recorded: push
 * rights, the entry and account its items go out under, thread rights and pinning.
 */
class TaskReviewRoleTest {

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

    private final Map<String, String> vault = new HashMap<>();

    private final Map<String, PushEnd> ends = new HashMap<>();

    private boolean onHost;

    private @Nullable String projectBuilds;

    private boolean endUnreadable;

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

    @Test
    void aTaskWhoseRunRecordedNoRoleIsAnAuthor() {
        assertThat(role().role(TASK))
                .as("role without a recorded run place")
                .isEqualTo(Role.AUTHOR);
    }

    @Test
    void aTaskAnswersTheRoleItsRunRecorded() throws IOException {
        recorded(RunPlace.REVIEWER);
        assertThat(role().role(TASK)).as("recorded reviewer").isEqualTo(Role.REVIEWER);
        recorded(RunPlace.AUTHOR);
        assertThat(role().role(TASK)).as("recorded author").isEqualTo(Role.AUTHOR);
    }

    @Test
    void anAuthorMayPushAndAReviewerNever() throws IOException {
        assertThat(role().mayPush(TASK)).as("author pushes").isTrue();
        recorded(RunPlace.REVIEWER);
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
        recorded(RunPlace.REVIEWER);
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
    void anAuthorWhoseGateRecordCannotBeReadIsPinnedNowhere() {
        ends.put(TASK, PushEnd.waiting(TASK, HEAD, Instant.EPOCH));
        endUnreadable = true;
        assertThat(role().mayPin(TASK, HEAD)).as("author with an unreadable record").isFalse();
    }

    @Test
    void aReviewerMayBePinnedToAnyHead() throws IOException {
        recorded(RunPlace.REVIEWER);
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
        for (final String recordedRole : List.of(RunPlace.AUTHOR, RunPlace.REVIEWER)) {
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
        final TaskReviewRole role = role();
        ends.put(TASK, PushEnd.waiting(TASK, HEAD, Instant.EPOCH));
        projectBuilds = BUILDS_ENTRY;
        final List<org.assertj.core.api.ThrowableAssert.ThrowingCallable> asked =
                List.of(
                        () -> role.role(TASK),
                        () -> role.mayPush(TASK),
                        () -> role.mayOpenThread(TASK, PULL_REQUEST),
                        () -> role.mayPin(TASK, HEAD),
                        () -> role.postingEntry(TASK, PULL_REQUEST),
                        () -> role.postingAccount(TASK, GATE_PUSH));
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
        recorded(RunPlace.REVIEWER);
        assertThat(role().postingEntry(TASK, PULL_REQUEST))
                .as("reviewer's entry at a pull request")
                .isEqualTo(PERSON_ENTRY);
        assertThat(role().postingAccount(TASK, PULL_REQUEST))
                .as("reviewer's account at a pull request")
                .isEqualTo(account(PERSON_ENTRY));
    }

    @Test
    void aReviewerAtAGatePushHasNoPostingAccountYet() throws IOException {
        recorded(RunPlace.REVIEWER);
        final PlaceUnreadable refused =
                catchThrowableOfType(
                        PlaceUnreadable.class, () -> role().postingEntry(TASK, GATE_PUSH));
        assertThat(refused).as("reviewer's entry at a gate push").isNotNull();
        assertThat(refused.reason())
                .as("reason of the refusal")
                .isEqualTo(PlaceUnreadable.Reason.FORGE_ADAPTER_MISSING);
        assertThatThrownBy(() -> role().postingAccount(TASK, GATE_PUSH))
                .as("reviewer's account at a gate push")
                .isInstanceOf(IllegalStateException.class);
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
        recorded(RunPlace.REVIEWER);
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
        recorded(RunPlace.REVIEWER);
        forges.clear();
        refusedAsUnknownForge("unknown forge", FORGE);
    }

    @Test
    void aReviewerAtAForgeWhoseEntryCannotBeReadIsRefused() throws IOException {
        recorded(RunPlace.REVIEWER);
        forges.set(0, new ForgeInstance(FORGE, null, CommentReading.EXTENDED, "no adapter named"));
        refusedAsUnknownForge("unreadable forge entry", "no adapter named");
    }

    @Test
    void aReviewerWhileTheForgeFileCannotBeReadIsRefused() throws IOException {
        recorded(RunPlace.REVIEWER);
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
        assertThat(TaskReviewRole.taskForgeEntry("c0ffee"))
                .as("the task's own forge entry")
                .isEqualTo(TaskSecrets.PREFIX + "c0ffee" + TaskSecrets.FORGE);
    }

    private TaskReviewRole role() {
        return new TaskReviewRole(
                task -> states.resolve(task),
                new StandInEntries(),
                new StandInForges(),
                TaskReviewRoleTest::account,
                task -> {
                    if (endUnreadable) {
                        throw new IOException("cut off");
                    }
                    return Optional.ofNullable(ends.get(task));
                });
    }

    private void recorded(final String role) throws IOException {
        final Path state = Files.createDirectories(states.resolve(TASK));
        new RunPlace(role, null, TASK).writeTo(state);
    }

    private static ForgeAccount account(final String forge, final String entry) {
        return new ForgeAccount(forge, "id-" + entry, "account-" + entry.replace('/', '-'));
    }

    private static ForgeAccount account(final String entry) {
        return account(FORGE, entry);
    }

    /** The vault's entry names as a machine would answer them, with tokens behind them. */
    private final class StandInEntries implements TaskReviewRole.Entries {

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
