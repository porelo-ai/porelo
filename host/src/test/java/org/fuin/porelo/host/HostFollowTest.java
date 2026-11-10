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

    @Test
    void newlyAdmittedMembersReadExistingArrivalsInTheSameFollow() throws Exception {
        // Waiting for another push or follow hides judged work from a newly admitted reviewer.
        for (String login : List.of("bob", "charlie")) {
            final String path = "/etc/porelo-host/people/" + login + ".conf";
            f.write(path, f.read(path).replace("locked=false", "locked=true"));
        }
        ((List<Object>) f.answer.get("persons")).add(FollowFixture.card("dave", 4));
        ((List<Object>) f.project().get("members"))
                .add(fields("login", "dave", "role", "developer", "locked", false));
        assertThat(f.accounts).doesNotContainKey("dave");
        assertThat(f.path("/etc/porelo-host/people/dave.conf")).doesNotExist();
        f.write(
                "/var/lib/porelo-host/arrivals/alpha/code.jsonl",
                "{\"verdict\":\"armed\",\"arrived\":\"2026-11-09T10:00:00Z\"}\n");
        f.run();
        assertThat(f.readers(FollowFixture.ORG + "arrivals/alpha/code.jsonl"))
                .as("the current members receive read ACLs after their person records are admitted")
                .containsExactlyInAnyOrder(
                        "user:" + f.accounts.get("bob") + ":r--",
                        "user:" + f.accounts.get("charlie") + ":r--",
                        "user:" + f.accounts.get("dave") + ":r--")
                .doesNotContain("user:" + f.accounts.get("alice") + ":r--");
        assertThat(f.commands)
                .anyMatch(
                        argv ->
                                Path.of(argv.getFirst()).getFileName().toString().equals("useradd")
                                        && argv.getLast().equals("dave"));
    }

    // A public ACL failure must not erase pre-hook commits after the forge installs its hook.
    @Test
    void armingKeepsMigrationHistoryWithoutPublishingBeforeFollowIsReady() throws Exception {
        f.forge();
        f.write(FollowFixture.ORG + "applied.json", write(f.answer));
        f.override =
                argv -> {
                    if (argv.contains("sh")
                            && argv.stream().anyMatch(arg -> arg.contains("pre-receive.d/porelo")))
                        return new HostPrograms.Result(
                                0, "repository code\n" + "a".repeat(40) + "\n");
                    if (Path.of(argv.getFirst()).getFileName().toString().equals("setfacl"))
                        return new HostPrograms.Result(1, "");
                    return null;
                };
        final var context =
                new FollowContext(
                        f.host, f.project.files, f.runtime, f.clock, new PrintWriter(f.journal));
        assertThatCode(() -> new FollowPushes(context).arm("alpha", "forge"))
                .as("arming records privately without requiring public ACLs")
                .doesNotThrowAnyException();
        assertThat(f.read("/var/lib/porelo-host/arrivals/alpha/code.jsonl"))
                .contains("migrated", "armed", "a".repeat(40));
        assertThat(f.path(FollowFixture.ORG + "arrivals/alpha/code.jsonl")).doesNotExist();
    }

    // An oversized or partial ledger row must fail without allocating the whole history.
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void armingRefusesAnOversizedOrUnterminatedLedgerLine(boolean oversized) throws Exception {
        f.write(
                "/var/lib/porelo-host/arrivals/alpha/code.jsonl",
                oversized
                        ? " ".repeat(128 * 1024 + 1) + "{\"verdict\":\"armed\"}\n"
                        : "{\"verdict\":\"armed\"}");
        assertThatThrownBy(
                        () ->
                                new FollowPushes(
                                                new FollowContext(
                                                        f.host,
                                                        f.project.files,
                                                        f.runtime,
                                                        f.clock,
                                                        new PrintWriter(f.journal)))
                                        .arm("alpha", "forge"))
                .as("unbounded or partial records are refused")
                .isInstanceOf(HostRefusal.class)
                .hasMessageContaining("line");
    }

    // A failure before reconciliation must revoke the former central readers immediately.
    @Test
    void takingAnOrganizationClosesCentralBeforePublishingOtherTrustFiles() throws Exception {
        f.write(FollowFixture.ORG + "central/alpha.json", "old\n");
        Files.setAttribute(f.path(FollowFixture.ORG + "central"), "unix:mode", 0711);
        final var seen = new java.util.concurrent.atomic.AtomicBoolean();
        f.before =
                argv -> {
                    if (Path.of(argv.getFirst()).getFileName().toString().equals("setfacl")) {
                        seen.set(true);
                        try {
                            assertThat(
                                            (int)
                                                            Files.getAttribute(
                                                                    f.path(
                                                                            FollowFixture.ORG
                                                                                    + "central"),
                                                                    "unix:mode")
                                                    & 0001)
                                    .as("central is closed before fallible trust publication")
                                    .isZero();
                        } catch (IOException ex) {
                            throw new UncheckedIOException(ex);
                        }
                    }
                };
        f.run();
        assertThat(seen).isTrue();
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

    @Test
    void aFetchRefusalPrecedesEvenPendingLocks() throws Exception {
        f.run();
        final var before = snapshot();
        f.fetchCode = 65;
        f.fetchErrors = "porelo: refused-state: older state served again";
        refused(f::run, 65, "older state");
        same(before);
    }

    // The fetch may use the timer's whole 300 seconds; every other isolated operation gets 60.
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

    @Test
    void anUnreachableForgeDoesNotRefuseTheOrganization() throws Exception {
        f.run();
        f.fetchCode = 69;
        f.fetchErrors = "cannot-say: unreachable";
        refused(f::run, 69, "unreachable");
        assertThat(Files.exists(f.path(FollowFixture.ORG + "refused.json"))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 69, 124, 137})
    void aFailedUnitIsUnavailable(int code) throws Exception {
        f.run();
        f.fetchCode = code;
        refused(f::run, 69, "exit " + code);
        assertThat(Files.exists(f.path(FollowFixture.ORG + "refused.json"))).isFalse();
    }

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

    @Test
    void aPinAloneDoesNotStartAFetch() throws Exception {
        Files.delete(f.path(FollowFixture.ORG + "read/organization.added"));
        final var before = snapshot();
        assertThat(f.run()).containsEntry("organization", "waiting-for-token");
        same(before);
        assertThat(f.commands).noneMatch(a -> a.contains("fetch"));
    }

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

    @Test
    void aRefusedReadKeyPublishesNoMarker() throws Exception {
        Files.delete(f.path(FollowFixture.ORG + "read/organization.added"));
        f.readKeyCode = 65;
        refused(() -> f.refresh("private-token"), 65, "read key refused");
        assertThat(Files.exists(f.path(FollowFixture.ORG + "read/organization.added"))).isFalse();
        assertThat(f.commands).noneMatch(a -> a.contains("fetch"));
    }

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

    @Test
    void organizationAdminsJoinThePinWithoutGettingRootSsh() throws Exception {
        f.answer.put("organization_admins", List.of(FollowFixture.admin("owner", 7)));
        f.run();
        assertThat(f.read(FollowFixture.ORG + "pin"))
                .isEqualTo(
                        "alice " + FollowFixture.key(1) + "\nowner " + FollowFixture.key(7) + "\n");
        assertThat(f.read("/etc/ssh/authorized_keys/root")).isEqualTo(FollowFixture.key(1) + "\n");
    }

    @Test
    void aNewOrganizationAdminListIsTakenOnAnExistingHost() throws Exception {
        f.run();
        f.answer.put("organization_admins", List.of(FollowFixture.admin("owner", 7)));
        f.next("b");
        f.run();
        assertThat(f.read(FollowFixture.ORG + "pin")).contains("owner ");
    }

    @Test
    void absentAndEmptyAdminListsKeepTheHostAdminPin() throws Exception {
        f.run();
        final String before = f.read(FollowFixture.ORG + "pin");
        f.answer.put("organization_admins", List.of());
        f.run();
        assertThat(f.read(FollowFixture.ORG + "pin")).isEqualTo(before);
    }

    @Test
    void aKeyInBothAdminListsIsPinnedOnce() throws Exception {
        f.answer.put("organization_admins", List.of(FollowFixture.admin("alice", 1)));
        f.run();
        assertThat(f.read(FollowFixture.ORG + "pin").lines()).hasSize(1);
        assertThat(f.read("/etc/ssh/authorized_keys/root").lines()).hasSize(1);
    }

    @Test
    void aReplacedMachineAdminLosesRootSshAndThePin() throws Exception {
        f.run();
        f.answer.put("admins", List.of(FollowFixture.admin("bob", 2)));
        f.next("b");
        f.run();
        assertThat(f.read(FollowFixture.ORG + "pin") + f.read("/etc/ssh/authorized_keys/root"))
                .doesNotContain(FollowFixture.key(1));
    }

    @Test
    void rootAuthorizationIsReplacedWhole() throws Exception {
        f.write("/etc/ssh/authorized_keys/root", FollowFixture.key(7) + "\n");
        f.run();
        assertThat(f.read("/etc/ssh/authorized_keys/root")).isEqualTo(FollowFixture.key(1) + "\n");
    }

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

    @Test
    void organizationAdminsCannotStandInForAMissingMachineAdmin() throws Exception {
        f.answer.put("admins", List.of());
        f.answer.put("organization_admins", List.of(FollowFixture.admin("owner", 7)));
        final var before = snapshot();
        refused(f::run, 65, "no host admin keys");
        same(before);
    }

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
