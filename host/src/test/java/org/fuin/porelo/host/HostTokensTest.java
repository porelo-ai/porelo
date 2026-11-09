package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostTokensTest {
    @TempDir Path directory;
    private HostTaskFixture host;
    private static final String OLD = "porelo-host-lend-20261029T120000Z-old";
    private static final String YOUNG = "porelo-host-lend-20261029T120001Z-young";

    @BeforeEach
    void prepare() throws Exception {
        host = new HostTaskFixture(directory);
    }

    private HostReadFixture.Answer issue() {
        return host.run("", "forge", "issue-token");
    }

    private HostReadFixture.Answer lend() {
        return host.run("", "forge", "lend-token");
    }

    // py: test_forge_token_replaces_the_persons_one_token_and_answers_it_alone
    @Test
    void issuingReplacesThePersonsTokenAndAnswersOnlyTheNewSecret() throws Exception {
        final var first = issue();
        final var second = issue();
        assertThat(first.code()).as(first.err()).isZero();
        assertThat(second.code()).as(second.err()).isZero();
        assertThat(second.out()).matches("[0-9a-f]{40}").isNotEqualTo(first.out());
        assertThat(host.tokens.get("alice").values())
                .singleElement()
                .satisfies(
                        token -> {
                            assertThat(token.secret()).isEqualTo(second.out());
                            assertThat(token.scopes())
                                    .isEqualTo(
                                            "write:repository,write:issue,write:organization,read:user");
                        });
        host.noSecrets(second);
    }

    // py: test_a_lend_leaves_the_persons_token_valid_and_answers_its_own
    @Test
    void lendingLeavesThePersonTokenAndMakesAnIndependentOne() throws Exception {
        final var person = issue();
        final var lent = lend();
        assertThat(lent.code()).as(lent.err()).isZero();
        assertThat(host.tokens.get("alice").values())
                .extracting(HostTaskFixture.Token::secret)
                .containsExactlyInAnyOrder(person.out(), lent.out());
        assertThat(host.names("alice"))
                .anyMatch(name -> name.startsWith("porelo-host-lend-20261030T120000Z-"));
        assertThat(host.tokens.get("alice").values())
                .allSatisfy(
                        token ->
                                assertThat(token.scopes())
                                        .isEqualTo(
                                                "write:repository,write:issue,write:organization,read:user"));
        host.noSecrets(lent);
    }

    // py: test_a_second_lend_leaves_the_first
    @Test
    void concurrentCommandsKeepBothLentTokens() {
        final var first = lend();
        final var second = lend();
        assertThat(second.out()).isNotEqualTo(first.out());
        assertThat(host.tokens.get("alice").values())
                .extracting(HostTaskFixture.Token::secret)
                .containsExactlyInAnyOrder(first.out(), second.out());
    }

    // py: test_a_lend_a_day_old_is_deleted_by_the_next_lend_and_a_younger_one_stays
    @Test
    void lendingDeletesExpiredAndUnreadableLendsAndKeepsYoungerAndOrdinaryTokens() {
        for (String name :
                List.of(OLD, YOUNG, "porelo-host-lend-unreadable", "porelo-host-person-legacy"))
            host.add("alice", name);
        assertThat(lend().code()).isZero();
        assertThat(host.names("alice"))
                .contains(YOUNG, "porelo-host-person-legacy")
                .doesNotContain(OLD, "porelo-host-lend-unreadable")
                .hasSize(3);
    }

    // py: test_forge_token_replaces_every_token_of_the_login_but_its_lends
    @Test
    void issuingRemovesOrdinaryTokensButLeavesEveryYoungLend() {
        host.add("alice", "legacy");
        final var lent = lend();
        final var person = issue();
        assertThat(host.tokens.get("alice").values())
                .extracting(HostTaskFixture.Token::secret)
                .containsExactlyInAnyOrder(lent.out(), person.out());
        assertThat(host.names("alice")).doesNotContain("legacy");
    }

    // py: test_forge_token_also_deletes_a_lend_a_day_old
    @Test
    void issuingAlsoDeletesExpiredLends() {
        host.add("alice", OLD);
        host.add("alice", YOUNG);
        assertThat(issue().code()).isZero();
        assertThat(host.names("alice")).contains(YOUNG).doesNotContain(OLD).hasSize(2);
    }

    // py: test_a_wrong_lend_call_reaches_no_forge
    @ParameterizedTest
    @ValueSource(
            strings = {
                "forge lend-token x",
                "forge lend-token --lend",
                "forge --lent",
                "forge lend-token alice"
            })
    void malformedLendsMintAndDeleteNothing(String arguments) {
        assertThat(host.run("", arguments.split(" ")).code()).isEqualTo(64);
        assertThat(host.calls).isEmpty();
        assertThat(host.requests).isEmpty();
    }

    // py: test_forge_token_is_refused_to_root_a_locked_revoked_or_unknown_person
    @ParameterizedTest
    @ValueSource(strings = {"root", "locked", "pending", "revoked", "unknown"})
    void onlyAnUnlockedUnrevokedSudoPersonGetsTokens(String state) throws Exception {
        Map<String, String> environment = HostReadFixture.SUDO;
        switch (state) {
            case "root" -> environment = Map.of();
            case "locked" ->
                    host.tree.put(
                            "/etc/porelo-host/people/alice.conf",
                            "member=alpha developer\nlocked=true\nssh="
                                    + host.alice
                                    + "\nexpires=\n");
            case "pending" ->
                    host.tree.person("alice", "member=alpha developer\n", "lock=pending\n");
            case "revoked" -> host.organization(host.alice, true, false);
            default -> environment = Map.of("SUDO_USER", "carol", "SUDO_UID", "1000");
        }
        for (String verb : List.of("issue-token", "lend-token")) {
            final var answer = host.run(environment, "", "forge", verb);
            assertThat(answer.code()).as(answer.err()).isEqualTo(state.equals("root") ? 64 : 65);
            assertThat(answer.out()).isEmpty();
        }
        assertThat(host.calls).isEmpty();
        assertThat(host.requests).isEmpty();
    }

    // py: test_no_caller_can_request_a_named_person_token
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void neitherRootNorAPersonCanRequestAnotherPersonsToken(boolean sudo) {
        for (String verb : List.of("issue-token", "lend-token")) {
            assertThat(
                            host.run(
                                            sudo ? HostReadFixture.SUDO : Map.of(),
                                            "",
                                            "forge",
                                            verb,
                                            "--for",
                                            "bob")
                                    .code())
                    .isEqualTo(64);
        }
        assertThat(host.calls).isEmpty();
    }

    @Test
    void tokenPagingCompletesBeforeAnyDeletionShrinksThePages() {
        for (int index = 0; index < 123; index++) host.add("alice", "legacy-" + index);
        assertThat(issue().code()).isZero();
        assertThat(host.names("alice")).hasSize(1);
        assertThat(host.requests.subList(0, 4))
                .allMatch(request -> request.startsWith("GET /admin/users/alice/tokens?"));
    }

    @Test
    void anApiFailureStillDeletesTheTemporaryAdminAndRevealsNoToken() throws Exception {
        host.failIdentity = "alice";
        final var answer = issue();
        assertThat(answer.code()).isEqualTo(69);
        assertThat(answer.err()).startsWith("porelo-host-person:");
        assertThat(answer.out()).isEmpty();
        host.noSecrets(answer);
    }

    @Test
    void invalidGeneratedTokensNeverReachStdoutOrDiagnostics() {
        host.generated = "private invalid generated token";
        final var answer = issue();
        assertThat(answer.code()).isEqualTo(69);
        assertThat(answer.out()).isEmpty();
        assertThat(answer.err()).doesNotContain(host.generated);
    }
}
