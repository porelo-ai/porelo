package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.*;
import static org.fuin.porelo.host.HostJson.*;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class HostFollowProjectsTest {
    @TempDir Path directory;
    FollowFixture f;

    @BeforeEach
    void host() throws Exception {
        f = new FollowFixture(directory);
    }

    private void gate() throws Exception {
        f.write("/etc/systemd/system/porelo-matrix-gate.socket", "fixture gate");
        f.accounts.put("porelo-matrix", f.accounts.get("alice"));
    }

    private Map<String, Object> publication() {
        return f.json(FollowFixture.ORG + "projects/alpha.json");
    }

    private static Map<String, Object> row(Object value) {
        return (Map<String, Object>) value;
    }

    private void item(Map<String, Object> change) throws Exception {
        final var entries = f.progress();
        final var item = row(entries.get("alpha"));
        item.putAll(change);
        f.write(FollowFixture.ORG + "host-projects.json", HostJson.write(entries));
    }

    // A project waiting before its first migration must not be presented as already carried.
    @Test
    void aNeverMigratedProjectPublishesItsWaitingState() throws Exception {
        f.forge();
        f.run();
        assertThat(f.path(FollowFixture.ORG + "central/alpha.json")).exists();
        assertThat(f.json(FollowFixture.ORG + "central/alpha.json"))
                .containsEntry("state", "waiting-for-token");
        assertThat(f.project.centralCalls).isEmpty();
    }

    // A person cannot complete first migration without knowing which credential the host needs.
    @Test
    void aFirstMigrationWaitExplainsTheTokenAction() throws Exception {
        f.forge();
        f.run();
        assertThat(f.json(FollowFixture.ORG + "central/alpha.json").get("why"))
                .asString()
                .contains("forge token", "read key", "copy", "keeps no token");
    }

    // An interrupted follow must not reopen old carrying progress after a project starts waiting.
    @Test
    void replacingProgressKeepsOldCarryingStatusClosed() throws Exception {
        f.migrated();
        f.run();
        item(fields("state", "waiting-for-token", "why", "needs a forge token"));
        final var original = f.project.files;
        final Path central = f.path(FollowFixture.ORG + "central");
        new ProjectMeasurements(original, f.runtime, f.clock).revoke();
        f.project.files =
                new ProjectFiles(
                        original.root,
                        original.owner,
                        Files::deleteIfExists,
                        path -> {
                            final var attributes = original.attributes(path);
                            if (path.equals(central)) {
                                assertThat((int) attributes.get("mode") & 0001)
                                        .as(
                                                "old carried progress stays unreachable until replacement succeeds")
                                        .isZero();
                            }
                            return attributes;
                        },
                        original::owner);
        new FollowProjects(
                        f.context(),
                        (origin, token) -> {
                            throw new AssertionError();
                        },
                        () -> HostJson.object(f.progress()))
                .measure(HostJson.object(f.applied()));
        assertThat(f.json(FollowFixture.ORG + "central/alpha.json"))
                .containsEntry("state", "waiting-for-token");
    }

    // Refreshing one distance must not erase another project's carrying decision or token action.
    @Test
    void aStandaloneMeasurementKeepsOtherProjectsProgress() throws Exception {
        f.forge();
        f.answer.put("projects", List.of(f.project(), FollowFixture.project("beta")));
        f.run();
        final Path beta = f.path(FollowFixture.ORG + "central/beta.json");
        final byte[] before = Files.readAllBytes(beta);
        new ProjectMeasurements(f.project.files, f.runtime, f.clock).measure("forge", "alpha");
        assertThat(beta).exists();
        assertThat(Files.readAllBytes(beta)).isEqualTo(before);
    }

    // Grouped repository reasons must not make an ordinary waiting publication unreadable.
    @Test
    void aWaitingPublicationBoundsReasonsAndKeepsEveryRepositoryStatus() throws Exception {
        f.migrated();
        final var repositories = new LinkedHashMap<String, Object>();
        for (int i = 0; i < 18; i++) {
            repositories.put(
                    "repository-" + i,
                    fields(
                            "review",
                            "required",
                            "central",
                            "https://forge.example.org/acme/repository-" + i + ".git"));
        }
        f.verified.put("repositories", repositories);
        f.run();
        final var answer = f.json(FollowFixture.ORG + "central/alpha.json");
        assertThat(answer).containsEntry("state", "waiting-for-token");
        assertThat(answer.get("why")).asString().isNotEmpty().hasSizeLessThanOrEqualTo(200);
        assertThat(answer.get("host_repositories")).asList().hasSize(18);
    }

    // A standalone distance refresh must not erase the host's withholding state.
    @Test
    void aStandaloneMeasurementKeepsPublishedWaitingProgress() throws Exception {
        f.migrated();
        f.verified.put(
                "repositories",
                fields(
                        "extra",
                        fields(
                                "review",
                                "required",
                                "central",
                                "https://forge.example.org/acme/extra.git")));
        f.run();
        new ProjectMeasurements(f.project.files, f.runtime, f.clock).measure("forge", "alpha");
        assertThat(f.json(FollowFixture.ORG + "central/alpha.json"))
                .containsEntry("state", "waiting-for-token");
    }

    @Test
    void aHostWithoutServicesCarriesItsProjectWithoutAForgeOrGate() throws Exception {
        f.run();
        assertThat(f.item()).containsEntry("state", "carried");
        assertThat(f.commands)
                .noneMatch(a -> a.contains("podman") || a.contains(FollowRooms.TRANSPORT));
    }

    @Test
    void aNewForgeProjectRegistersItsCurrentProjectAdminAndWaitsForAToken() throws Exception {
        f.forge();
        f.run();
        assertThat(f.read("/etc/porelo-host/projects/alpha.conf"))
                .contains("admin=bob", "signed_by=" + FollowFixture.key(2));
        assertThat(f.item()).containsEntry("state", "waiting-for-token");
        assertThat(f.project.centralCalls).isEmpty();
    }

    @Test
    void aPhysicalProjectRecordPreventsDuplicateRegistration() throws Exception {
        f.forge();
        f.run();
        final long count =
                f.project.calls.stream()
                        .filter(
                                call ->
                                        call.method().equals("POST")
                                                && call.path().endsWith("/orgs"))
                        .count();
        f.run();
        assertThat(
                        f.project.calls.stream()
                                .filter(
                                        call ->
                                                call.method().equals("POST")
                                                        && call.path().endsWith("/orgs")))
                .hasSize((int) count);
    }

    @Test
    void refreshCanRegisterAndMigrateBeforeTheFirstTimerPass() throws Exception {
        f.forge();
        f.refresh(ProjectFixture.CENTRAL_TOKEN);
        assertThat(f.item()).containsEntry("migrated", true).containsEntry("state", "carried");
        assertThat(f.project.repositories).containsKey("alpha/code");
    }

    @Test
    void aCompletedMigrationIsNotRepeatedByRefresh() throws Exception {
        f.migrated();
        f.refresh(ProjectFixture.CENTRAL_TOKEN);
        final long before =
                f.project.calls.stream()
                        .filter(call -> call.path().equals("/api/v1/repos/migrate"))
                        .count();
        f.refresh(ProjectFixture.CENTRAL_TOKEN);
        assertThat(
                        f.project.calls.stream()
                                .filter(call -> call.path().equals("/api/v1/repos/migrate"))
                                .count())
                .isEqualTo(before);
        assertThat(f.commands.toString() + f.journal).doesNotContain(ProjectFixture.CENTRAL_TOKEN);
    }

    @Test
    void migrationFailureStillKeepsTheRoomForCurrentMembers() throws Exception {
        f.forge();
        gate();
        f.project.replies.put("POST /repos/migrate", new ProjectFixture.Reply(503, null));
        f.refresh(ProjectFixture.CENTRAL_TOKEN);
        assertThat(f.item().get("why")).asString().contains("exit 69");
        assertThat(f.commands).anyMatch(a -> a.contains("keep-room"));
        assertThat(publication()).containsEntry("room", "!alpha:host");
    }

    @Test
    void timerPassesPreserveTheLastMigrationRefusalUntilRetrySucceeds() throws Exception {
        f.forge();
        f.project.replies.put("POST /repos/migrate", new ProjectFixture.Reply(503, null));
        f.refresh(ProjectFixture.CENTRAL_TOKEN);
        final Object refusal = f.item().get("migrate_refused");
        f.run();
        assertThat(f.item()).containsEntry("migrate_refused", refusal);
        f.project.replies.remove("POST /repos/migrate");
        f.refresh(ProjectFixture.CENTRAL_TOKEN);
        assertThat(f.item()).doesNotContainKey("migrate_refused");
    }

    @Test
    void anOldMigrationRefusalIsCombinedWithCurrentOrganizationReasons() throws Exception {
        f.forge();
        f.project.replies.put("POST /repos/migrate", new ProjectFixture.Reply(503, null));
        f.refresh(ProjectFixture.CENTRAL_TOKEN);
        f.project().put("address", "bad-address");
        f.run();
        assertThat(f.item().get("why")).asString().contains("exit 69", "unusable address");
        f.write("/etc/porelo-host/forge.conf", "host=build-host\naccount=\n");
        f.run();
        assertThat(f.item()).doesNotContainKey("migrate_refused");
    }

    @Test
    void projectRegistrationRequiresAnAdminSigningKeyThatStandsNow() throws Exception {
        f.forge();
        row(((List<?>) f.project().get("signing_keys")).getFirst())
                .put("valid_until", "2026-11-03");
        f.run();
        assertThat(Files.exists(f.path("/etc/porelo-host/projects/alpha.conf"))).isFalse();
        assertThat(f.item().get("why")).asString().contains("no unlocked admin");
    }

    @Test
    void aGateWithoutAConfirmedRoomPublishesNeitherRoomNorHomeserver() throws Exception {
        gate();
        f.roomCode = 75;
        f.run();
        assertThat(publication()).containsEntry("room", null).containsEntry("homeserver", null);
    }

    @Test
    void roomMembershipIsComputedAfterCardLocks() throws Exception {
        gate();
        f.run();
        f.person(1).put("revoked", true);
        f.next("b");
        f.run();
        final var command =
                f.commands.stream().filter(a -> a.contains("keep-room")).toList().getLast();
        assertThat(command.get(command.indexOf("--persons") + 1)).isEqualTo("charlie");
    }

    @ParameterizedTest
    @CsvSource({"75, keep-room", "78, keep-room", "75, drop-room", "78, drop-room"})
    void onlyTransientRoomRefusalsAreRetried(int code, String verb) throws Exception {
        gate();
        if (verb.equals("drop-room")) {
            f.run();
            f.answer.put("projects", List.of());
        }
        f.roomCode = code;
        f.run();
        final int before = (int) f.commands.stream().filter(a -> a.contains(verb)).count();
        f.roomCode = 0;
        f.run();
        assertThat(f.commands.stream().filter(a -> a.contains(verb)))
                .hasSize(before + (code == 75 ? 1 : 0));
        if (code == 78) {
            assertThat(f.item())
                    .containsEntry("why", "the gate refused the room (exit 78); left alone");
        }
    }

    @Test
    void aTransientRoomErrorEndsAfterTheNextSuccessfulKeep() throws Exception {
        gate();
        f.roomCode = 75;
        f.run();
        assertThat(f.item().get("why")).isNotNull();
        f.roomCode = 0;
        f.run();
        assertThat(f.item()).containsEntry("why", null);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 75, 78})
    void roomCommandsSwitchAccountAndCarryNoSecretInArguments(int code) throws Exception {
        gate();
        f.roomCode = code;
        if (code == 0) {
            FollowRooms.room(f.context(), "keep-room", "alpha", List.of("bob", "charlie"));
        } else {
            assertThatThrownBy(
                            () ->
                                    FollowRooms.room(
                                            f.context(),
                                            "keep-room",
                                            "alpha",
                                            List.of("bob", "charlie")))
                    .isInstanceOf(HostRefusal.class)
                    .extracting("exitCode")
                    .isEqualTo(code);
        }
        final int index =
                java.util.stream.IntStream.range(0, f.commands.size())
                        .filter(i -> f.commands.get(i).contains("keep-room"))
                        .findFirst()
                        .orElseThrow();
        assertThat(f.commands.get(index)).containsSequence("runuser", "-u", "porelo-matrix", "--");
        assertThat(f.inputs.get(index))
                .contains("PORELO_MATRIX_ADMIN_TOKEN", "matrix-private-token");
        assertThat(f.commands.toString() + f.journal).doesNotContain("matrix-private-token");
    }

    @Test
    void removedRoomMembersAreNamedInTheJournal() throws Exception {
        gate();
        f.override =
                argv ->
                        argv.contains("keep-room")
                                ? new HostPrograms.Result(
                                        0, "{\"room\":\"!alpha:host\",\"removed\":[\"bob\"]}")
                                : null;
        FollowRooms.room(f.context(), "keep-room", "alpha", List.of());
        assertThat(f.journal.toString()).contains("alpha: out of its room: bob");
    }

    @Test
    void anUninstalledGateIsNeverAskedToDropItsOldRoom() throws Exception {
        gate();
        f.run();
        final long before = f.commands.stream().filter(a -> a.contains("keep-room")).count();
        Files.delete(f.path("/etc/systemd/system/porelo-matrix-gate.socket"));
        f.answer.put("projects", List.of());
        f.run();
        assertThat(f.commands.stream().filter(a -> a.contains("keep-room"))).hasSize((int) before);
        assertThat(f.commands).noneMatch(a -> a.contains("drop-room"));
    }

    @Test
    void aBadAddressIsAProjectReasonAndDoesNotSpareARevokedCard() throws Exception {
        f.project().put("address", "bad");
        f.person(1).put("revoked", true);
        f.run();
        assertThat(new HostRecords(f.host).read().get("bob").locked()).isTrue();
        assertThat(publication()).containsEntry("address", null);
        assertThat(f.item().get("why")).asString().contains("unusable address");
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate", "members", "signing_keys", "repositories", "null"})
    void aMalformedNeighborCannotPreventAccessRemoval(String damage) throws Exception {
        final var beta = FollowFixture.project("beta");
        beta.put("members", List.of());
        if (damage.equals("duplicate")) {
            f.answer.put("projects", List.of(f.project(), beta, beta));
        } else if (damage.equals("null")) {
            f.answer.put("projects", Arrays.asList(f.project(), null));
        } else {
            beta.put(damage, "invalid");
            f.answer.put("projects", List.of(f.project(), beta));
        }
        f.person(1).put("revoked", true);
        f.run();
        assertThat(new HostRecords(f.host).read().get("bob").locked()).isTrue();
        assertThat(f.applied().get("project_refusals"))
                .asString()
                .contains(damage.equals("null") ? "a project" : "beta");
    }

    @Test
    void signersSeeOnlyTheAcceptedProjectContracts() throws Exception {
        f.answer.put("projects", Arrays.asList(f.project(), null));
        f.run();
        final int index =
                java.util.stream.IntStream.range(0, f.commands.size())
                        .filter(i -> f.commands.get(i).contains("signers"))
                        .findFirst()
                        .orElseThrow();
        final String credential =
                f.commands.get(index).stream()
                        .filter(a -> a.startsWith("LoadCredential=state:"))
                        .findFirst()
                        .orElseThrow();
        assertThat(credential).contains("porelo-org-signers");
        assertThat(f.read("/etc/porelo-host/signers/alpha.certifiers")).isNotEmpty();
    }

    @Test
    void projectPublicationUsesTheCentralNameUnderTheProjectsOrganization() throws Exception {
        f.migrated();
        f.run();
        assertThat(publication())
                .containsEntry("fetch_from", "http://127.0.0.1:3000/alpha/code.git");
        assertThat(row(publication().get("builds")))
                .containsEntry("credential", "forge.example.org.person");
    }

    @Test
    void noCentralAddressMeansNoForgeCopyOrBuilds() throws Exception {
        f.migrated();
        f.run();
        f.project().put("address", null);
        f.run();
        assertThat(publication()).containsEntry("fetch_from", null).doesNotContainKey("builds");
    }

    @Test
    void anUnusableForgeDomainLeavesTheCopyReadableWithoutBuilds() throws Exception {
        f.migrated();
        f.write(
                "/etc/porelo-host/forge.conf",
                "host=build-host\naccount=forge\ngit_port=3000\nname=\n");
        f.run();
        assertThat(publication())
                .containsEntry("fetch_from", "http://127.0.0.1:3000/alpha/code.git")
                .doesNotContainKey("builds");
        assertThat(f.journal.toString()).contains("no builds named");
    }

    @Test
    void aHostWithoutAForgePublishesOnlyCertifiers() throws Exception {
        f.run();
        assertThat(Files.exists(f.path("/etc/porelo-host/signers/alpha.allowed"))).isFalse();
        assertThat(f.read("/etc/porelo-host/signers/alpha.certifiers")).isNotEmpty();
    }

    @Test
    void aProjectWaitingForItsFirstMigrationStillPublishes() throws Exception {
        f.forge();
        f.run();
        assertThat(Files.exists(f.path(FollowFixture.ORG + "projects/alpha.json"))).isTrue();
        assertThat(f.item()).containsEntry("state", "waiting-for-token");
    }

    @Test
    void inProcessProjectRefusalsKeepTheirSafeDiagnosticAndOriginalExit() throws Exception {
        f.forge();
        f.project.replies.put("POST /repos/migrate", new ProjectFixture.Reply(403, null));
        f.refresh(ProjectFixture.CENTRAL_TOKEN);
        assertThat(f.item().get("migrate_refused")).asString().contains("exit 65");
        assertThat(f.journal.toString()).contains("(exit 65)");
    }

    @ParameterizedTest
    @ValueSource(strings = {"porelo-host-project: SECRET", "porelo-host-project: x\u001bSECRET"})
    void aHelperCannotSmuggleTheCentralTokenInItsDiagnostic(String stderr) {
        assertThat(
                        FollowProjects.helperWords(
                                stderr.replace("SECRET", "private-token"),
                                fields("token", "private-token")))
                .isNull();
    }

    @Test
    void foreignDiagnosticLastLinesAreDiscarded() {
        assertThat(
                        FollowProjects.helperWords(
                                "porelo-host-project: safe\nforeign diagnostic", null))
                .isNull();
    }

    @Test
    void helperWordsArePrintableAndBounded() {
        assertThat(
                        FollowProjects.helperWords(
                                "porelo-host-project: \u001b" + "x".repeat(400), null))
                .hasSize(299)
                .doesNotContain("\u001b");
    }

    @Test
    void everyFollowSessionIncludesTheScopeForItsOwnDeletion() throws Exception {
        f.migrated();
        f.run();
        assertThat(f.commands.stream().filter(a -> a.contains("generate-access-token")))
                .allSatisfy(
                        a -> assertThat(a.get(a.indexOf("--scopes") + 1)).contains("write:admin"));
        assertThat(f.project.calls).anyMatch(call -> call.path().contains("/tokens?"));
    }
}
