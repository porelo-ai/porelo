package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.*;
import static org.fuin.porelo.host.HostJson.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostFollowPeopleTest {
    @TempDir Path directory;
    FollowFixture f;

    @BeforeEach
    void host() throws Exception {
        f = new FollowFixture(directory);
    }

    private void locked(String login) {
        assertThat(new HostRecords(f.host).read().get(login).locked()).isTrue();
        assertThat(Files.exists(f.path("/etc/ssh/authorized_keys/" + login))).isFalse();
    }

    private void revoke() {
        f.person(1).put("revoked", true);
        f.next("b");
    }

    private void remove() {
        f.answer.put("projects", List.of());
        f.next("b");
    }

    @Test
    void aRevokedCardLosesSshSessionsAndGroupMembership() throws Exception {
        f.run();
        revoke();
        f.run();
        locked("bob");
        assertThat(f.peopleGroup).doesNotContain("bob");
        assertThat(f.commands)
                .anySatisfy(
                        a -> assertThat(a).containsSequence("loginctl", "terminate-user", "bob"));
        assertThat(f.commands).noneMatch(a -> a.contains("expel"));
        f.write("/run/revoked.pub", FollowFixture.key(2) + "\n");
        final var result =
                new HostPrograms(java.time.Duration.ofSeconds(60), 65536, Path::of)
                        .run(
                                List.of(
                                        "/usr/bin/ssh-keygen",
                                        "-Q",
                                        "-f",
                                        f.path("/etc/ssh/porelo-revoked.krl").toString(),
                                        f.path("/run/revoked.pub").toString()));
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("REVOKED");
    }

    @Test
    void losingOneProjectPreservesAnotherProjectsMembership() throws Exception {
        final var beta = FollowFixture.project("beta");
        beta.put(
                "members",
                List.of(fields("login", "charlie", "role", "developer", "locked", false)));
        f.answer.put("projects", List.of(f.project(), beta));
        f.run();
        f.answer.put("projects", List.of(beta));
        f.next("b");
        f.run();
        locked("bob");
        final var records = new HostRecords(f.host).read();
        assertThat(records.get("charlie").members()).containsExactly("beta developer");
        assertThat(records.get("charlie").locked()).isFalse();
        assertThat(records.get("alice").locked()).isFalse();
        assertThat(Files.exists(f.path(FollowFixture.ORG + "projects/alpha.json"))).isFalse();
    }

    @Test
    void theSevenDayGracePeriodEndsWithAccountAndRangeRemoval() throws Exception {
        f.run();
        remove();
        f.run();
        f.now = Instant.parse("2026-11-10T00:00:00Z");
        f.run();
        assertThat(f.accounts).containsKey("bob");
        assertThat(f.read("/etc/porelo-host/people/bob.conf")).contains("locked_on=2026-11-04");
        f.now = Instant.parse("2026-11-11T00:00:00Z");
        f.run();
        assertThat(f.accounts).doesNotContainKey("bob");
        assertThat(Files.exists(f.path("/etc/porelo-host/people/bob.conf"))).isFalse();
        assertThat(new HostRecords(f.host).purged()).contains("bob");
    }

    @Test
    void readmissionRestoresTheSameAccountDuringTheGracePeriod() throws Exception {
        final var project = f.project();
        f.run();
        remove();
        f.run();
        final long uid = f.accounts.get("bob");
        f.answer.put("projects", List.of(project));
        f.next("c");
        f.run();
        assertThat(f.accounts.get("bob")).isEqualTo(uid);
        assertThat(new HostRecords(f.host).read().get("bob").locked()).isFalse();
        assertThat(f.linger).contains("bob");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aListedLockedMemberHasNoPurgeClock(boolean revoked) throws Exception {
        f.run();
        if (revoked) {
            revoke();
        } else {
            ((Map<String, Object>) ((List<?>) f.project().get("members")).getFirst())
                    .put("locked", true);
        }
        f.run();
        f.now = Instant.parse("2027-01-01T00:00:00Z");
        f.run();
        assertThat(f.accounts).containsKey("bob");
        assertThat(f.read("/etc/porelo-host/people/bob.conf")).doesNotContain("locked_on=");
    }

    @Test
    void appearingAgainAsALockedMemberCancelsPurge() throws Exception {
        final var p = f.project();
        f.run();
        remove();
        f.run();
        ((Map<String, Object>) ((List<?>) p.get("members")).getFirst()).put("locked", true);
        f.answer.put("projects", List.of(p));
        f.run();
        assertThat(f.read("/etc/porelo-host/people/bob.conf")).doesNotContain("locked_on=");
    }

    @Test
    void failureToLockOnePersonDoesNotProtectTheNext() throws Exception {
        f.run();
        remove();
        f.override =
                argv ->
                        argv.contains("--lock") && argv.getLast().equals("bob")
                                ? new HostPrograms.Result(1, "")
                                : null;
        assertThatThrownBy(f::run)
                .isInstanceOf(HostRefusal.class)
                .hasMessageContaining("next follow retries");
        locked("charlie");
        assertThat(f.read("/etc/porelo-host/people/bob.conf")).contains("lock=pending");
    }

    @Test
    void anOmittedRevokedCardRetainsItsLastKnownKey() throws Exception {
        f.run();
        revoke();
        f.run();
        f.answer.put(
                "persons",
                List.of(
                        ((List<?>) f.answer.get("persons")).getFirst(),
                        ((List<?>) f.answer.get("persons")).get(2)));
        f.next("c");
        f.run();
        assertThat((List<Object>) f.applied().get("revoked")).contains("bob");
        locked("bob");
    }

    @Test
    void aNewKeyCanRestoreTheLoginWithoutUnrevokingTheOldKey() throws Exception {
        f.run();
        revoke();
        f.run();
        f.person(1).put("key", FollowFixture.key(9));
        f.person(1).put("revoked", false);
        f.next("c");
        f.run();
        assertThat(new HostRecords(f.host).read().get("bob").locked()).isFalse();
        assertThat(f.read("/etc/ssh/authorized_keys/bob")).contains(FollowFixture.key(9));
        f.person(1).put("key", FollowFixture.key(2));
        f.next("d");
        f.run();
        locked("bob");
    }

    @Test
    void aBooleanCannotUnrevokeTheSameCardKey() throws Exception {
        f.run();
        revoke();
        f.run();
        f.person(1).put("revoked", false);
        f.next("c");
        f.run();
        locked("bob");
        assertThat((List<Object>) f.applied().get("revoked")).contains("bob");
    }

    @Test
    void thePreviousRevokedKeyIsRecordedBeforeAReplacementState() throws Exception {
        f.run();
        revoke();
        f.run();
        f.person(1).put("key", FollowFixture.key(9));
        f.person(1).put("revoked", false);
        f.next("c");
        final List<String> ordered = new ArrayList<>();
        f.before =
                argv -> {
                    if (argv.contains("-k")) {
                        ordered.add("krl");
                    }
                    if (argv.contains("setfacl")) {
                        ordered.add("acl");
                    }
                };
        f.run();
        assertThat(ordered.indexOf("krl")).isLessThan(ordered.indexOf("acl"));
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 9})
    void aRevokedLoginReturnsOnlyWithANewKey(int number) throws Exception {
        f.run();
        revoke();
        f.run();
        f.person(1).put("revoked", false);
        f.person(1).put("key", FollowFixture.key(number));
        f.next("c");
        f.run();
        assertThat(new HostRecords(f.host).read().get("bob").locked()).isEqualTo(number == 2);
    }

    @Test
    void renewedCardsMoveTheSshExpiry() throws Exception {
        f.run();
        f.person(1).put("expires", "2029-01-15");
        f.run();
        assertThat(f.read("/etc/ssh/authorized_keys/bob"))
                .contains("expiry-time=\"20290115000000Z\"", FollowFixture.key(2));
    }

    @Test
    void renewalAfterExpiryRestoresMembership() throws Exception {
        f.run();
        f.person(1).put("expires", "2020-01-01");
        f.run();
        locked("bob");
        f.person(1).put("expires", "2029-01-15");
        f.run();
        assertThat(new HostRecords(f.host).read().get("bob").locked()).isFalse();
    }

    // A lock can end with readmission, so the Matrix account stays for the person brought back.
    @Test
    void membershipLocksNeverExpelTheMatrixLogin() throws Exception {
        f.run();
        remove();
        f.run();
        assertThat(f.commands).noneMatch(argv -> argv.contains("expel"));
    }

    @Test
    void purgeOnAHostWithNoHomeserverNeverStartsATransport() throws Exception {
        f.run();
        remove();
        f.run();
        f.now = f.now.plusSeconds(7 * 86400);
        f.run();
        assertThat(f.commands)
                .noneMatch(argv -> argv.stream().anyMatch(a -> a.contains("transport-matrix")));
    }

    @Test
    void aNewPersonGetsAnAccountAndTheSharedSignerPublication() throws Exception {
        Files.delete(f.path("/etc/porelo-host/people/bob.conf"));
        f.accounts.remove("bob");
        f.peopleGroup.remove("bob");
        f.run();
        assertThat(f.accounts).containsKey("bob");
        assertThat(f.read("/etc/porelo-host/signers/alpha.certifiers")).contains("task:alpha:*");
        assertThat(f.read("/etc/ssh/authorized_keys/bob")).contains(FollowFixture.key(2));
    }

    @Test
    void aPartialSubordinateAllocationIsLockedThenFinishedOnRetry() throws Exception {
        Files.delete(f.path("/etc/porelo-host/people/bob.conf"));
        f.accounts.remove("bob");
        final boolean[] first = {true};
        f.override =
                argv -> {
                    if (argv.contains("--add-subgids")
                            && argv.getLast().equals("bob")
                            && first[0]) {
                        first[0] = false;
                        throw new HostRefusal(69, "fixture subordinate GID failure");
                    }
                    return null;
                };
        assertThatThrownBy(f::run).hasMessageContaining("subordinate GID failure");
        locked("bob");
        f.linger.remove("bob");
        f.run();
        assertThat(new HostRecords(f.host).read().get("bob").locked()).isFalse();
        assertThat(f.linger).contains("bob");
        assertThat(f.read("/etc/subuid").lines().filter(line -> line.startsWith("bob:")))
                .hasSize(1);
        assertThat(f.read("/etc/subgid").lines().filter(line -> line.startsWith("bob:")))
                .hasSize(1);
    }

    @Test
    void anUnknownMembersRoleGrantsNoAccess() throws Exception {
        final List<Object> members = new ArrayList<>((List<?>) f.project().get("members"));
        members.add(fields("login", "stranger", "role", "developer", "locked", false));
        f.project().put("members", members);
        f.run();
        assertThat(f.accounts).doesNotContainKey("stranger");
        assertThat(f.read(FollowFixture.ORG + "projects/alpha.json")).doesNotContain("stranger");
    }

    @Test
    void projectAclsAreReplacedForUnlockedMembersOnly() throws Exception {
        f.run();
        assertThat(f.readers(FollowFixture.ORG + "projects/alpha.json")).isNotEmpty();
        ((Map<String, Object>) ((List<?>) f.project().get("members")).getFirst())
                .put("locked", true);
        ((Map<String, Object>) ((List<?>) f.project().get("members")).get(1)).put("locked", true);
        f.run();
        assertThat(f.readers(FollowFixture.ORG + "projects/alpha.json")).isEmpty();
    }

    @Test
    void eachPersonGetsTheirOwnSortedProjectList() throws Exception {
        final var beta = FollowFixture.project("beta");
        beta.put(
                "members",
                List.of(fields("login", "charlie", "role", "developer", "locked", false)));
        f.answer.put("projects", List.of(beta, f.project()));
        f.run();
        assertThat(f.json(FollowFixture.ORG + "persons/bob.json"))
                .containsEntry("projects", List.of("alpha"));
        assertThat(f.json(FollowFixture.ORG + "persons/charlie.json"))
                .containsEntry("projects", List.of("alpha", "beta"));
        assertThat(Files.exists(f.path(FollowFixture.ORG + "persons/alice.json"))).isFalse();
    }

    @Test
    void theLastMembershipRemovesThePersonsProjectList() throws Exception {
        f.run();
        remove();
        f.run();
        assertThat(Files.exists(f.path(FollowFixture.ORG + "persons/bob.json"))).isFalse();
    }

    @Test
    void anUnknownPersonPublicationIsNeverDeletedAsStaging() throws Exception {
        f.run();
        f.write(FollowFixture.ORG + "persons/stranger.txt", "keep");
        assertThatThrownBy(f::run).hasMessageContaining("unknown file");
        assertThat(f.read(FollowFixture.ORG + "persons/stranger.txt")).isEqualTo("keep");
    }

    @Test
    void oldReadersLoseAccessBeforeAFailedLockRuns() throws Exception {
        f.run();
        revoke();
        ((Map<String, Object>) ((List<?>) f.project().get("members")).getFirst())
                .put("locked", true);
        f.override =
                argv -> {
                    if (argv.contains("--lock") && argv.getLast().equals("bob")) {
                        assertThat(Files.exists(f.path(FollowFixture.ORG + "persons/bob.json")))
                                .isFalse();
                        return new HostPrograms.Result(1, "");
                    }
                    return null;
                };
        assertThatThrownBy(f::run).hasMessageContaining("next follow retries");
    }

    @Test
    void aNewAccountReadsItsProjectFileInTheSameFollow() throws Exception {
        Files.delete(f.path("/etc/porelo-host/people/bob.conf"));
        f.accounts.remove("bob");
        f.run();
        assertThat(f.readers(FollowFixture.ORG + "projects/alpha.json")).isNotEmpty();
        assertThat(f.json(FollowFixture.ORG + "persons/bob.json"))
                .containsEntry("projects", List.of("alpha"));
    }

    @Test
    void aFailedExpulsionKeepsTheLockedRecordUntilTheNextPurge() throws Exception {
        f.accounts.put("porelo-matrix", f.accounts.get("alice"));
        f.run();
        remove();
        f.run();
        f.now = f.now.plusSeconds(7 * 86400);
        f.override = argv -> argv.contains("expel") ? new HostPrograms.Result(1, "") : null;
        assertThatThrownBy(f::run).hasMessageContaining("could not expel");
        assertThat(Files.exists(f.path("/etc/porelo-host/people/bob.conf"))).isTrue();
        assertThat(f.accounts).containsKey("bob");
        f.override = argv -> null;
        f.run();
        assertThat(Files.exists(f.path("/etc/porelo-host/people/bob.conf"))).isFalse();
        assertThat(f.accounts).doesNotContainKey("bob");
    }

    @Test
    void theFirstOrganizationNeedsNoAdmittedPersonsAccount() throws Exception {
        for (String login : List.of("alice", "bob", "charlie")) {
            Files.delete(f.path("/etc/porelo-host/people/" + login + ".conf"));
            f.accounts.remove(login);
        }
        f.run();
        assertThat(f.accounts).containsKeys("alice", "bob", "charlie");
        assertThat(f.applied()).containsEntry("commit", "a".repeat(40));
    }

    @Test
    void unfinishedRevocationsKeepTheirAcceptedKeyAndRetryOnTheSameState() throws Exception {
        f.run();
        revoke();
        f.override =
                argv ->
                        argv.contains("--lock") && argv.getLast().equals("bob")
                                ? new HostPrograms.Result(1, "")
                                : null;
        assertThatThrownBy(f::run).hasMessageContaining("next follow retries");
        assertThat(f.applied().get("revoked")).asList().contains("bob");
        assertThat(f.read("/etc/porelo-host/people/bob.conf")).contains("lock=pending");
        f.override = argv -> null;
        f.run();
        assertThat(f.read("/etc/porelo-host/people/bob.conf")).doesNotContain("lock=pending");
        locked("bob");
    }

    @Test
    void aPendingLockNeverExecutesAnUncheckedMissingProgram() throws Exception {
        f.run();
        f.write(
                "/etc/porelo-host/people/bob.conf",
                f.read("/etc/porelo-host/people/bob.conf") + "lock=pending\n");
        f.missing.add("usermod");
        f.commands.clear();
        assertThatThrownBy(f::run).hasMessageContaining("usermod");
        assertThat(f.commands)
                .noneMatch(
                        argv ->
                                Path.of(argv.getFirst())
                                        .getFileName()
                                        .toString()
                                        .equals("usermod"));
    }

    @Test
    void aFailedAdmissionStillRemovesSshWhenALockDependencyIsMissing() throws Exception {
        Files.delete(f.path("/etc/porelo-host/people/bob.conf"));
        f.accounts.remove("bob");
        f.override =
                argv -> {
                    if (argv.contains("--add-subgids") && argv.getLast().equals("bob")) {
                        f.missing.add(Path.of(FollowRooms.TRANSPORT).getFileName().toString());
                        throw new HostRefusal(69, "fixture subordinate allocation failure");
                    }
                    return null;
                };
        assertThatThrownBy(f::run).hasMessageContaining("subordinate allocation failure");
        assertThat(Files.exists(f.path("/etc/ssh/authorized_keys/bob"))).isFalse();
        assertThat(f.read("/etc/porelo-host/people/bob.conf")).contains("locked=true");
        assertThat(f.peopleGroup).doesNotContain("bob");
    }
}
