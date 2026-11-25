package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.gate.GitGate;
import org.fuin.porelo.gate.review.ForgeCredential;
import org.fuin.porelo.gate.review.ForgePages;
import org.fuin.porelo.gate.review.ForgeReview;
import org.fuin.porelo.gate.review.PlaceUnreadable;
import org.fuin.porelo.gate.review.PullRequestFacts;
import org.fuin.porelo.gate.review.PullRequestRef;
import org.fuin.porelo.gate.review.PullRequestState;
import org.fuin.porelo.gate.review.Push;
import org.fuin.porelo.gate.review.WriteOutcome;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A run of a reviewer at a pull request fetches the pull request's head with the lent read token in
 * git's environment alone and pins it, with git and the forge stood in.
 */
class PullRequestPinsTest {

    private static final PullRequestRef REF =
            PullRequestRef.of(URI.create("https://forge.example/shop/app/pull/7"));

    private static final String REPOSITORY = "https://forge.example/shop/app";

    private static final String HEAD = "4e1d2c3b4a5f6e7d8c9b0a1f2e3d4c5b6a7f8e9d";

    private static final String BASE = "9f8e7d6c5b4a39281706f5e4d3c2b1a098765432";

    private static final String MERGE_BASE = "1a2b3c4d5e6f708192a3b4c5d6e7f80912345678";

    private static final String TASK = "0123456789abcdef0123";

    private static final String TOKEN = "ghp-read-5d1e";

    private static final String ENTRY = "git.token.forge.example";

    @TempDir Path mirror;

    @TempDir Path state;

    private final List<Command> ran = new ArrayList<>();

    private final List<String> lent = new ArrayList<>();

    private final Forge adapter = new Forge();

    private int fetchExit;

    private int headExit;

    private int ancestorExit;

    private boolean fetchTimesOut;

    private String fetchSaid = "fatal: Authorization: Bearer " + TOKEN;

    private final CommandRunner git =
            command -> {
                ran.add(command);
                final List<String> arguments = command.arguments();
                if (arguments.contains("fetch")) {
                    if (fetchTimesOut) {
                        throw CommandException.timedOut(command, Duration.ofSeconds(1), "");
                    }
                    return new CommandResult(command, fetchExit, "", fetchSaid);
                }
                if (arguments.contains("rev-parse")) {
                    return new CommandResult(command, headExit, HEAD, "");
                }
                if (arguments.contains("merge-base")) {
                    return new CommandResult(command, ancestorExit, "", "");
                }
                return new CommandResult(command, 0, "", "");
            };

    private PullRequestPins pins;

    @BeforeEach
    void reviewer() throws IOException {
        new RunPlace(RunPlace.REVIEWER, null, "shop-2").writeTo(state);
        pins =
                new PullRequestPins(
                        adapter,
                        entry -> {
                            lent.add(entry);
                            return new ForgeCredential(entry, TOKEN);
                        },
                        task ->
                                new PullRequestPins.TaskGate(
                                        mirror, state, "review-shop", "shop", "app", "shop-2"),
                        git,
                        url -> Map.of("GIT_SSL_CAINFO", "/trusted.pem"),
                        Duration.ofMinutes(2));
    }

    @Test
    void aRunFetchesTheHeadWithTheTokenInGitsEnvironmentAloneAndPinsIt() throws Exception {
        final Push push = pins.pin(REF, TASK);

        assertThat(push)
                .as("the run's push: the head reviewed against its merge base")
                .isEqualTo(new Push("shop", "app", "shop-2", HEAD, MERGE_BASE, TASK));
        assertThat(lent)
                .as("the owner's read credential, lent once for the facts and once for the fetch")
                .containsExactly(ENTRY, ENTRY);
        assertThat(adapter.asked).as("the facts read for the pull request").containsExactly(REF);
        final Command fetch = fetch();
        assertThat(fetch.arguments())
                .as("the fetch: the head alone from the forge's repository, no ref written")
                .endsWith(
                        "fetch",
                        "--quiet",
                        "--no-tags",
                        "--no-recurse-submodules",
                        "--no-write-fetch-head",
                        "--end-of-options",
                        REPOSITORY,
                        HEAD)
                .contains("protocol.https.allow=always", "--git-dir", mirror.toString());
        assertThat(String.join(" ", fetch.arguments()))
                .as("the token in no argument, the helper named by its variable")
                .doesNotContain(TOKEN)
                .contains(
                        "credential.https://forge.example.helper=!",
                        "vault credential --env " + PullRequestPins.TOKEN_VARIABLE);
        assertThat(fetch.environment())
                .as("the token in git's chosen environment, under its one variable")
                .containsEntry(PullRequestPins.TOKEN_VARIABLE, TOKEN)
                .containsEntry("GIT_CONFIG_GLOBAL", "/dev/null")
                .containsEntry("GIT_TERMINAL_PROMPT", "0")
                .containsEntry("GIT_SSL_CAINFO", "/trusted.pem");
        assertThat(fetch.inheritsEnvironment()).as("nothing inherited").isFalse();
        assertThat(fetch.timeout()).as("bounded").isEqualTo(Duration.ofMinutes(2));
        assertThat(ran.stream().filter(each -> each != fetch))
                .as("no other git command is given the token")
                .allSatisfy(
                        each ->
                                assertThat(each.environment().values())
                                        .as("%s", each.arguments())
                                        .doesNotContain(TOKEN));
        assertThat(ran.getLast().arguments())
                .as("the pin moves the task's pinned ref to the head")
                .containsExactly(
                        "git",
                        "-C",
                        mirror.toString(),
                        "update-ref",
                        GitGate.PINNED + "review-shop",
                        HEAD);
        assertThat(RunPlace.readFrom(state).pinned())
                .as("the run's state recorded")
                .isEqualTo(new RunPlace.Pin(HEAD, MERGE_BASE, REPOSITORY));
    }

    @Test
    void aRepositoryOnAnotherHostIsRefusedBeforeAnyFetch() {
        adapter.repository = "https://elsewhere.example/shop/app";

        assertThatThrownBy(() -> pins.pin(REF, TASK))
                .as("the owner's token is never sent to another host")
                .isInstanceOfSatisfying(
                        PlaceUnreadable.class,
                        refused ->
                                assertThat(refused.reason())
                                        .as("reason")
                                        .isEqualTo(PlaceUnreadable.Reason.UNREACHABLE));
        assertThat(ran).as("no git run").isEmpty();
    }

    @Test
    void aFailedFetchIsUnreachableAndSaysNothingTheForgeSaid() {
        fetchExit = 128;

        assertThatThrownBy(() -> pins.pin(REF, TASK))
                .as("the forge refused the fetch")
                .isInstanceOfSatisfying(
                        PlaceUnreadable.class,
                        refused -> {
                            assertThat(refused.reason())
                                    .as("reason")
                                    .isEqualTo(PlaceUnreadable.Reason.UNREACHABLE);
                            assertThat(refused.getMessage())
                                    .as("detail")
                                    .doesNotContain(TOKEN)
                                    .doesNotContain("Authorization");
                        });
        assertThat(RunPlace.readFrom(state).pinned()).as("nothing pinned").isNull();
    }

    @Test
    void aFetchThatTimesOutIsUnreachable() {
        fetchTimesOut = true;

        assertThatThrownBy(() -> pins.pin(REF, TASK))
                .as("the forge did not answer in time")
                .isInstanceOfSatisfying(
                        PlaceUnreadable.class,
                        refused ->
                                assertThat(refused.reason())
                                        .as("reason")
                                        .isEqualTo(PlaceUnreadable.Reason.UNREACHABLE));
    }

    @Test
    void aHeadTheFetchDidNotBringIsRefusedByItsCommit() {
        headExit = 1;

        assertThatThrownBy(() -> pins.pin(REF, TASK))
                .as("the forge's repository lacks the head its adapter named")
                .isInstanceOfSatisfying(
                        PlaceUnreadable.class,
                        refused -> {
                            assertThat(refused.reason())
                                    .as("reason")
                                    .isEqualTo(PlaceUnreadable.Reason.HEAD_NOT_FETCHED);
                            assertThat(refused.getMessage()).as("detail").contains(HEAD);
                        });
        assertThat(RunPlace.readFrom(state).pinned()).as("nothing pinned").isNull();
    }

    @Test
    void aFetchTheForgeRefusesForWantOfTheHeadIsRefusedByItsCommit() {
        fetchExit = 128;
        fetchSaid = "fatal: remote error: upload-pack: not our ref " + HEAD;

        assertThatThrownBy(() -> pins.pin(REF, TASK))
                .as("the forge holds no such commit")
                .isInstanceOfSatisfying(
                        PlaceUnreadable.class,
                        refused -> {
                            assertThat(refused.reason())
                                    .as("reason")
                                    .isEqualTo(PlaceUnreadable.Reason.HEAD_NOT_FETCHED);
                            assertThat(refused.getMessage())
                                    .as("detail")
                                    .contains(HEAD)
                                    .doesNotContain("upload-pack");
                        });
    }

    @Test
    void aMergeBaseNotUnderTheHeadIsRefusedByItsCommit() {
        ancestorExit = 1;

        assertThatThrownBy(() -> pins.pin(REF, TASK))
                .as("the merge base the adapter named is not under the head")
                .isInstanceOfSatisfying(
                        PlaceUnreadable.class,
                        refused -> {
                            assertThat(refused.reason())
                                    .as("reason")
                                    .isEqualTo(PlaceUnreadable.Reason.HEAD_NOT_FETCHED);
                            assertThat(refused.getMessage()).as("detail").contains(MERGE_BASE);
                        });
    }

    @Test
    void whatTheForgeOrTheVaultRefusedPassesThroughWithNothingFetched() {
        adapter.refusal =
                new PlaceUnreadable(PlaceUnreadable.Reason.NO_SUCH_PULL_REQUEST, "no such");

        assertThatThrownBy(() -> pins.pin(REF, TASK))
                .as("the adapter's refusal")
                .isInstanceOfSatisfying(
                        PlaceUnreadable.class,
                        refused ->
                                assertThat(refused.reason())
                                        .as("reason")
                                        .isEqualTo(PlaceUnreadable.Reason.NO_SUCH_PULL_REQUEST));
        assertThat(ran).as("no git run").isEmpty();
    }

    private Command fetch() {
        return ran.stream()
                .filter(each -> each.arguments().contains("fetch"))
                .findFirst()
                .orElseThrow();
    }

    /** The forge's adapter: fixed facts, or a refusal. */
    private static final class Forge implements ForgeReview {

        private final List<PullRequestRef> asked = new ArrayList<>();

        private String repository = REPOSITORY;

        private @Nullable PlaceUnreadable refusal;

        @Override
        public PullRequestFacts facts(final PullRequestRef ref, final ForgeCredential credential)
                throws PlaceUnreadable {
            asked.add(ref);
            if (refusal != null) {
                throw refusal;
            }
            return new PullRequestFacts(
                    ref, repository, HEAD, BASE, MERGE_BASE, PullRequestState.OPEN, null, null);
        }

        @Override
        public ForgePages conversation(final PullRequestRef ref, final ForgeCredential credential) {
            throw new UnsupportedOperationException("a pin reads no conversation");
        }

        @Override
        public WriteOutcome post(
                final PullRequestRef ref, final byte[] document, final ForgeCredential credential) {
            throw new UnsupportedOperationException("a pin posts nothing");
        }
    }
}
