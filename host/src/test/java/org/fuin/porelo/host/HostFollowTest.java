package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.*;
import static org.fuin.porelo.host.HostJson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class HostFollowTest {
    @TempDir Path directory;
    FollowFixture f;

    @BeforeEach
    void host() throws Exception {
        f = new FollowFixture(directory);
    }

    private static void refused(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int code, String words) {
        assertThatThrownBy(action)
                .isInstanceOf(HostRefusal.class)
                .hasMessageContaining(words)
                .extracting("exitCode")
                .isEqualTo(code);
    }

    private Map<String, byte[]> snapshot() throws IOException {
        final Map<String, byte[]> result = new TreeMap<>();
        try (var paths = Files.walk(f.path("/etc"))) {
            for (Path path :
                    paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                result.put(directory.relativize(path).toString(), Files.readAllBytes(path));
            }
        }
        result.remove("etc/porelo-host/organization/refused.json");
        return result;
    }

    private void same(Map<String, byte[]> before) throws IOException {
        assertThat(snapshot()).usingRecursiveComparison().isEqualTo(before);
    }

    // py: test_an_older_state_is_refused_without_changing_anything
    @Test
    void replayCannotRestoreAccessOrRollThePinBack() throws Exception {
        f.run();
        f.next("b");
        f.run();
        final var before = snapshot();
        f.next("a");
        refused(f::run, 65, "older organization");
        same(before);
        assertThat(f.commands.stream().filter(a -> a.contains("fetch")).toList().getLast())
                .containsSequence("--since", "b".repeat(40));
    }

    // py: test_follow_refuses_a_foreign_signer_or_an_older_or_forked_state_whole
    // py: test_a_fetch_refusal_leaves_everything_untouched
    @Test
    void aFetchRefusalPrecedesEvenPendingLocks() throws Exception {
        f.run();
        final var before = snapshot();
        f.fetchCode = 65;
        f.fetchErrors = "porelo: refused-state: older state served again";
        refused(f::run, 65, "older state");
        same(before);
    }

    // py: test_a_fetch_keeps_its_trust_record_across_follows
    @Test
    void theFetchKeepsItsTrustRecordAndGetsTheTimerWindow() throws Exception {
        f.run();
        final int i =
                java.util.stream.IntStream.range(0, f.commands.size())
                        .filter(n -> f.commands.get(n).contains("fetch"))
                        .findFirst()
                        .orElseThrow();
        assertThat(f.commands.get(i))
                .contains(
                        "StateDirectory=porelo-host-org-fetch",
                        "StateDirectoryMode=0700",
                        "Environment=XDG_STATE_HOME=/var/lib/porelo-host-org-fetch",
                        "DynamicUser=yes",
                        "PrivateNetwork=no",
                        "ProtectHome=yes");
        assertThat(f.timeouts.get(i))
                .isGreaterThan(Duration.ofSeconds(120))
                .isLessThanOrEqualTo(Duration.ofMinutes(5));
    }

    // py: test_a_refused_state_reaches_the_host_admin_and_keeps_the_state_taken
    @Test
    void aRefusalIsPrintableAndLeavesTheTakenState() throws Exception {
        f.run();
        f.fetchCode = 65;
        f.fetchErrors = "porelo: refused-state: changed key\u001b[2J";
        refused(f::run, 65, "changed key");
        assertThat(f.json(FollowFixture.ORG + "refused.json"))
                .containsEntry("words", "refused-state: changed key [2J");
        assertThat(f.applied()).containsEntry("commit", "a".repeat(40));
        f.fetchCode = 0;
        f.next("b");
        assertThat(f.run()).containsEntry("refused", null);
    }

    // py: test_a_fetch_that_cannot_say_is_no_refusal_for_the_host_admin
    @Test
    void anUnreachableForgeDoesNotRefuseTheOrganization() throws Exception {
        f.run();
        f.fetchCode = 69;
        f.fetchErrors = "cannot-say: unreachable";
        refused(f::run, 69, "unreachable");
        assertThat(Files.exists(f.path(FollowFixture.ORG + "refused.json"))).isFalse();
    }

    // py: test_a_fetch_that_says_nothing_is_no_refusal
    @ParameterizedTest
    @ValueSource(ints = {1, 69, 124, 137})
    void aFailedUnitIsUnavailable(int code) throws Exception {
        f.run();
        f.fetchCode = code;
        refused(f::run, 69, "exit " + code);
        assertThat(Files.exists(f.path(FollowFixture.ORG + "refused.json"))).isFalse();
    }

    // py: test_follow_refuses_an_answer_of_another_shape_or_host_whole
    // py: test_malformed_answers_change_nothing
    @ParameterizedTest
    @ValueSource(strings = {"host", "revision", "persons", "projects", "admins"})
    void malformedAnswersNeverChangeAccess(String damage) throws Exception {
        if (damage.equals("host")) {
            f.answer.put("host_card", Map.of("host", "other"));
        } else {
            f.answer.put(damage, null);
        }
        final var before = snapshot();
        refused(f::run, 65, damage.equals("host") ? "another host" : "malformed");
        same(before);
    }

    // py: test_follow_and_refresh_refuse_sudo
    @ParameterizedTest
    @ValueSource(strings = {"follow", "refresh"})
    void rootVerbsNeverInterpretTheSudoPerson(String verb) throws Exception {
        final var before = snapshot();
        refused(
                () ->
                        f.follow()
                                .run(
                                        verb,
                                        Map.of("SUDO_USER", "alice"),
                                        new InputStream() {
                                            public int read() {
                                                throw new AssertionError("stdin must stay unread");
                                            }
                                        }),
                65,
                "root's alone");
        same(before);
        assertThat(f.commands).isEmpty();
    }

    // py: test_a_renewal_is_roots_alone
    @Test
    void evenAnEmptySudoIdentityCannotRenewThePin() {
        refused(
                () ->
                        f.follow()
                                .run(
                                        "renew-pin",
                                        Map.of("SUDO_USER", ""),
                                        InputStream.nullInputStream()),
                65,
                "root's alone");
    }

    // py: test_nothing_is_followed_until_the_read_key_is_added
    @Test
    void aPinAloneDoesNotStartAFetch() throws Exception {
        Files.delete(f.path(FollowFixture.ORG + "read/organization.added"));
        final var before = snapshot();
        assertThat(f.run()).containsEntry("organization", "waiting-for-token");
        same(before);
        assertThat(f.commands).noneMatch(a -> a.contains("fetch"));
    }

    // py: test_refresh_adds_the_read_key_with_the_token_and_keeps_none
    @Test
    void refreshAddsTheReadKeyThroughStdinAlone() throws Exception {
        Files.delete(f.path(FollowFixture.ORG + "read/organization.added"));
        f.refresh("private-central-token\n");
        final int i =
                java.util.stream.IntStream.range(0, f.commands.size())
                        .filter(n -> f.commands.get(n).contains("add-read-key"))
                        .findFirst()
                        .orElseThrow();
        assertThat(f.inputs.get(i)).isEqualTo("private-central-token");
        assertThat(f.commands.toString() + f.journal).doesNotContain("private-central-token");
        assertThat(f.read(FollowFixture.ORG + "read/organization.added")).isEqualTo("added\n");
        assertThat(snapshot().values())
                .allSatisfy(
                        bytes ->
                                assertThat(new String(bytes, StandardCharsets.UTF_8))
                                        .doesNotContain("private-central-token"));
    }

    // py: test_a_refused_read_key_stays_waiting
    @Test
    void aRefusedReadKeyPublishesNoMarker() throws Exception {
        Files.delete(f.path(FollowFixture.ORG + "read/organization.added"));
        f.readKeyCode = 65;
        refused(() -> f.refresh("private-token"), 65, "read key refused");
        assertThat(Files.exists(f.path(FollowFixture.ORG + "read/organization.added"))).isFalse();
        assertThat(f.commands).noneMatch(a -> a.contains("fetch"));
    }

    // py: test_refresh_refuses_a_token_nobody_waits_for
    @Test
    void refreshNeverTakesAnUnneededCentralCredential() throws Exception {
        f.run();
        final var before = snapshot();
        refused(() -> f.refresh("private-token"), 65, "no project waits");
        same(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bad token", "one\ntwo", "é", "x"})
    void refreshBoundsAndValidatesTheInput(String damage) throws Exception {
        String token = damage.equals("x") ? "x".repeat(4097) : damage;
        refused(() -> f.refresh(token), 65, damage.equals("x") ? "too long" : "one printable line");
        assertThat(f.commands).isEmpty();
    }

    // py: test_follow_uses_the_renewed_pin_file_with_the_accepted_commit
    // py: test_root_renews_the_pin_and_the_next_follow_keeps_it
    @Test
    void rootCanRenewThePinWithoutDiscardingTheAcceptedRevision() throws Exception {
        f.run();
        f.write(FollowFixture.ORG + "refused.json", "{\"words\":\"old refusal\"}");
        final String pin = "bob " + FollowFixture.key(2) + "\n";
        final var result =
                f.follow()
                        .run(
                                "renew-pin",
                                Map.of(),
                                new ByteArrayInputStream(pin.getBytes(StandardCharsets.US_ASCII)));
        assertThat(f.read(FollowFixture.ORG + "pin")).isEqualTo(pin);
        assertThat(result).containsEntry("commit", "a".repeat(40)).containsEntry("refused", null);
        f.answer.put("admins", List.of(FollowFixture.admin("bob", 2)));
        f.run();
        assertThat(f.read(FollowFixture.ORG + "pin")).isEqualTo(pin);
    }

    // py: test_a_renewal_with_no_valid_key_changes_nothing
    @ParameterizedTest
    @ValueSource(strings = {"", "root KEY", "bob invalid", "bob KEY\nbob KEY", "bob KEY\n\n", "é"})
    void pinRenewalRejectsMalformedOrRepeatedKeys(String input) throws Exception {
        final var before = snapshot();
        final String text = input.replace("KEY", FollowFixture.key(2));
        refused(
                () ->
                        f.follow()
                                .run(
                                        "renew-pin",
                                        Map.of(),
                                        new ByteArrayInputStream(
                                                text.getBytes(StandardCharsets.UTF_8))),
                65,
                "pin");
        same(before);
    }

    // py: test_the_pin_holds_the_organization_admins_and_root_stays_with_the_machines
    @Test
    void organizationAdminsJoinThePinWithoutGettingRootSsh() throws Exception {
        f.answer.put("organization_admins", List.of(FollowFixture.admin("owner", 7)));
        f.run();
        assertThat(f.read(FollowFixture.ORG + "pin"))
                .isEqualTo(
                        "alice " + FollowFixture.key(1) + "\nowner " + FollowFixture.key(7) + "\n");
        assertThat(f.read("/etc/ssh/authorized_keys/root")).isEqualTo(FollowFixture.key(1) + "\n");
    }

    // py: test_a_list_that_arrives_on_a_server_already_set_up_is_taken_by_the_follow
    @Test
    void aNewOrganizationAdminListIsTakenOnAnExistingHost() throws Exception {
        f.run();
        f.answer.put("organization_admins", List.of(FollowFixture.admin("owner", 7)));
        f.next("b");
        f.run();
        assertThat(f.read(FollowFixture.ORG + "pin")).contains("owner ");
    }

    // py: test_a_build_with_no_list_keeps_todays_pin_and_an_empty_list_is_the_same
    @Test
    void absentAndEmptyAdminListsKeepTheHostAdminPin() throws Exception {
        f.run();
        final String before = f.read(FollowFixture.ORG + "pin");
        f.answer.put("organization_admins", List.of());
        f.run();
        assertThat(f.read(FollowFixture.ORG + "pin")).isEqualTo(before);
    }

    // py: test_a_person_in_both_lists_stands_in_the_pin_once_with_root
    @Test
    void aKeyInBothAdminListsIsPinnedOnce() throws Exception {
        f.answer.put("organization_admins", List.of(FollowFixture.admin("alice", 1)));
        f.run();
        assertThat(f.read(FollowFixture.ORG + "pin").lines()).hasSize(1);
        assertThat(f.read("/etc/ssh/authorized_keys/root").lines()).hasSize(1);
    }

    // py: test_a_machine_admin_replaced_leaves_root_and_the_pin_with_no_step_of_theirs
    @Test
    void aReplacedMachineAdminLosesRootSshAndThePin() throws Exception {
        f.run();
        f.answer.put("admins", List.of(FollowFixture.admin("bob", 2)));
        f.next("b");
        f.run();
        assertThat(f.read(FollowFixture.ORG + "pin") + f.read("/etc/ssh/authorized_keys/root"))
                .doesNotContain(FollowFixture.key(1));
    }

    // py: test_a_replaced_host_admin_loses_roots_login
    @Test
    void rootAuthorizationIsReplacedWhole() throws Exception {
        f.write("/etc/ssh/authorized_keys/root", FollowFixture.key(7) + "\n");
        f.run();
        assertThat(f.read("/etc/ssh/authorized_keys/root")).isEqualTo(FollowFixture.key(1) + "\n");
    }

    // py: test_a_malformed_list_changes_nothing
    @ParameterizedTest
    @ValueSource(
            strings = {
                "null",
                "key",
                "fingerprint",
                "backup",
                "root",
                "duplicate",
                "login",
                "entry",
                "list"
            })
    void malformedOrganizationAdminsNeverBecomeAuthority(String damage) throws Exception {
        final Map<String, Object> admin = FollowFixture.admin("owner", 7);
        final Object list;
        if (damage.equals("null")) {
            list = null;
        } else if (damage.equals("entry")) {
            list = List.of("owner");
        } else if (damage.equals("list")) {
            list = "none";
        } else {
            if (damage.equals("root")) {
                admin.put("login", "root");
            } else if (!damage.equals("duplicate")) {
                admin.put(damage, null);
            }
            list = damage.equals("duplicate") ? List.of(admin, admin) : List.of(admin);
        }
        f.answer.put("organization_admins", list);
        final var before = snapshot();
        refused(f::run, 65, "organization admin keys");
        same(before);
    }

    // py: test_a_machine_with_no_admin_left_is_refused_though_the_organization_has_admins
    @Test
    void organizationAdminsCannotStandInForAMissingMachineAdmin() throws Exception {
        f.answer.put("admins", List.of());
        f.answer.put("organization_admins", List.of(FollowFixture.admin("owner", 7)));
        final var before = snapshot();
        refused(f::run, 65, "no host admin keys");
        same(before);
    }

    // py: test_an_interrupted_follow_takes_the_same_state_again_with_its_new_pin
    @Test
    void aFailedApplicationKeepsTheNewPinAndCanReplayTheSameRevision() throws Exception {
        f.answer.put("organization_admins", List.of(FollowFixture.admin("owner", 7)));
        f.override =
                argv -> {
                    if (argv.getFirst().endsWith("setfacl")) {
                        throw new HostRefusal(69, "fixture publication stopped");
                    }
                    return null;
                };
        refused(f::run, 69, "publication stopped");
        assertThat(f.read(FollowFixture.ORG + "pin")).contains("owner ");
        assertThat(f.applied()).containsEntry("commit", "a".repeat(40));
        f.override = argv -> null;
        f.run();
    }

    // py: test_a_failed_application_keeps_the_accepted_revision_for_replay_checks
    @Test
    void anAppliedRevisionSurvivesAServiceFailure() throws Exception {
        f.run();
        f.person(1).put("revoked", true);
        f.next("b");
        f.override =
                argv -> {
                    if (argv.contains("--lock")) {
                        return new HostPrograms.Result(1, "");
                    }
                    return null;
                };
        refused(f::run, 69, "next follow retries");
        assertThat(f.applied()).containsEntry("commit", "b".repeat(40));
        f.next("a");
        refused(f::run, 65, "older organization");
    }

    // py: test_account_names_cannot_take_over_an_existing_account
    @ParameterizedTest
    @ValueSource(strings = {"root", "daemon", "bob"})
    void aForeignAccountOrKeyCannotBeTakenOver(String login) throws Exception {
        if (login.equals("bob")) {
            Files.delete(f.path("/etc/porelo-host/people/bob.conf"));
        } else {
            f.person(1).put("login", login);
            f.accounts.put(login, 1L);
        }
        final var before = snapshot();
        refused(f::run, 65, login.equals("root") ? "malformed" : "belongs to another account");
        same(before);
    }

    // py: test_a_taken_over_project_admin_can_admit_nobody
    @ParameterizedTest
    @ValueSource(strings = {"card-key", "key-holder"})
    void aLoginCannotBorrowAnotherPersonsCard(String damage) throws Exception {
        if (damage.equals("card-key")) {
            f.person(1).put("key", FollowFixture.key(9));
            f.write(
                    "/etc/porelo-host/people/charlie.conf",
                    FollowFixture.person("alpha developer", 9));
        } else {
            f.person(1).put("key", FollowFixture.key(1));
        }
        final var before = snapshot();
        refused(
                f::run,
                65,
                damage.equals("key-holder") ? "duplicate person" : "belongs to another account");
        same(before);
    }

    // py: test_follow_fetches_against_the_pin_and_records_it_root_only
    @Test
    void theFetchReadsOnlyThePinnedCredentialsAndPublishesPrivateState() throws Exception {
        f.run();
        final var fetch =
                f.commands.stream()
                        .filter(argv -> argv.contains("fetch"))
                        .findFirst()
                        .orElseThrow();
        assertThat(fetch)
                .containsSequence("--pin", "/run/credentials/porelo-host-org-fetch.service/pin");
        assertThat(
                        (int)
                                        Files.getAttribute(
                                                f.path(FollowFixture.ORG + "applied.json"),
                                                "unix:mode")
                                & 0777)
                .isEqualTo(0600);
        assertThat((int) Files.getAttribute(f.path(FollowFixture.ORG + "pin"), "unix:mode") & 0777)
                .isEqualTo(0600);
    }

    // py: test_failed_krl_publication_keeps_previous_org_keys_for_retry
    @Test
    void aFailedKrlUpdateCannotDiscardThePreviousKeyOrTakeTheNewState() throws Exception {
        f.run();
        f.person(1).put("key", FollowFixture.key(9));
        f.person(1).put("revoked", true);
        f.next("b");
        f.override = argv -> argv.contains("-k") ? new HostPrograms.Result(1, "") : null;
        refused(f::run, 69, "ssh-keygen failed");
        assertThat(f.applied()).containsEntry("commit", "a".repeat(40));
        assertThat(f.read("/etc/porelo-host/people/bob.conf")).contains(FollowFixture.key(2));
        f.override = argv -> null;
        f.run();
        assertThat(f.applied()).containsEntry("commit", "b".repeat(40));
    }

    // py: test_obsolete_commands_and_state_cannot_supply_host_authority
    @ParameterizedTest
    @ValueSource(strings = {"status", "pin", "bare", "--stdin", "old-state"})
    void obsoleteRegistryArgumentsAndFilesCannotSupplyAuthority(String argument) throws Exception {
        final var fixture = new HostReadFixture(directory);
        fixture.put(
                "/etc/porelo-host/registry/pin.conf",
                "admin=alice\nkey=" + FollowFixture.key(1) + "\n");
        fixture.put(
                "/etc/porelo-host/registry/applied.json",
                HostJson.write(
                        Map.of(
                                "commit",
                                "f".repeat(40),
                                "persons",
                                List.of(),
                                "projects",
                                List.of(),
                                "revoked",
                                List.of())));
        Files.delete(f.path(FollowFixture.ORG + "pin"));
        Files.delete(f.path(FollowFixture.ORG + "read/organization.added"));
        final var before = snapshot();
        if (argument.equals("old-state")) {
            assertThat(new HostOrganization(f.host).status())
                    .containsEntry("organization", null)
                    .containsEntry("commit", null)
                    .containsEntry("pinned", null);
            f.missing.add("systemd-run");
            refused(f::run, 69, "systemd-run");
        } else {
            final String[] args =
                    argument.equals("bare")
                            ? new String[] {"registry"}
                            : argument.equals("pin")
                                    ? new String[] {
                                        "registry", "pin", "alice", FollowFixture.key(1)
                                    }
                                    : new String[] {"registry", argument};
            assertThat(fixture.run(Map.of(), args).code()).isEqualTo(64);
        }
        same(before);
    }
}
