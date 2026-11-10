package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.VaultEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link TaskState} and {@link TaskSecrets}: what of a task a reboot must not take, and
 * that no secret is kept outside the vault.
 */
class TaskStateTest {

    @Test
    void aCanceledKnownReservationReleasesItsOwnScopesWithoutAContainerId() throws Exception {
        // An explicit cancellation must not keep its credential scopes alive indefinitely.
        final PoreloContext context = context().asking(shown -> "fixture-passphrase".toCharArray());
        context.vault().write(Map.of(), "fixture-passphrase".toCharArray());
        final String canceled = "porelo-p-pending";
        try {
            context.openerAsking(false, new java.io.PrintWriter(java.io.Writer.nullWriter()));
            final TaskReservations reservations = new TaskReservations(context);
            final TaskSecrets secrets = new TaskSecrets(context);
            try (var lease = reservations.reserve("p", "pending", canceled, "attempt")) {
                secrets.keep(
                        canceled,
                        new TaskSecrets.Tokens(null, "pending-token"),
                        java.util.Set.of(canceled));
            }
            reservations.retire(canceled);
            secrets.keep(TASK, new TaskSecrets.Tokens("gate", null), java.util.Set.of(TASK));
            assertThat(context.vault().read("fixture-passphrase".toCharArray()).keySet())
                    .as("only the proven canceled attempt's scope is released")
                    .doesNotContain(TaskSecrets.PREFIX + canceled + "/provider-token")
                    .contains(TaskSecrets.PREFIX + TASK + "/gate-token");
        } finally {
            context.forgetTyped();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "false,false",
        "false,true",
        "true,false",
        "true,true"
    })
    void retirementCannotAuthorizeUnknownOrContradictoryVaultScopes(boolean known, boolean forget)
            throws Exception {
        // A tombstone without proof, or with contradicted proof, must never authorize secret
        // deletion.
        final PoreloContext context = context().asking(shown -> "fixture-passphrase".toCharArray());
        final String owner = "porelo-p-retired";
        context.vault()
                .write(
                        Map.of(
                                TaskSecrets.PREFIX + owner + "/signing-key",
                                VaultEntry.of("sentinel")),
                        "fixture-passphrase".toCharArray());
        final TaskReservations reservations = new TaskReservations(context);
        if (known) {
            TaskIdentityFixture.record(context.paths(), owner, "p", "retired", ID);
            reservations.mutate(owner, () -> Boolean.TRUE);
            reservations.retire(owner);
            org.fuin.porelo.wire.Sidecar.recordId(
                    context.paths().tasks().taskRecord(owner), OTHER_ID);
        } else {
            reservations.retainBeforeRemoval(owner);
        }
        try {
            context.openerAsking(false, new java.io.PrintWriter(java.io.Writer.nullWriter()));
            final TaskSecrets secrets = new TaskSecrets(context);
            if (forget) {
                assertThat(secrets.forget(owner))
                        .as("unproven retirement refuses explicit secret deletion")
                        .isNotEmpty();
            } else {
                secrets.keep(TASK, new TaskSecrets.Tokens("gate", null), java.util.Set.of(TASK));
            }
            assertThat(context.vault().read("fixture-passphrase".toCharArray()).keySet())
                    .as("the unverified owner's sentinel scope survives")
                    .contains(TaskSecrets.PREFIX + owner + "/signing-key");
        } finally {
            context.forgetTyped();
        }
    }

    @Test
    void restoringContradictoryOwnershipWritesNoRuntimeFiles() throws IOException {
        // Restoring another recorded id must not recreate mounts for the wrong task.
        final PoreloContext context = context();
        TaskIdentityFixture.record(context.paths(), TASK, "p", "t", ID);
        Files.writeString(
                context.paths().tasks().taskRecord(TASK).resolve("ruleset.nft"), "original");
        org.fuin.porelo.wire.Sidecar.recordId(
                context.paths().tasks().containerState(TASK), OTHER_ID);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new TaskState(context).restore(TASK))
                .as("contradictory ids must refuse before copying any saved file")
                .isInstanceOf(IOException.class);
        assertThat(context.paths().tasks().containerState(TASK).resolve("ruleset.nft"))
                .as("no policy was restored for an unverified container")
                .doesNotExist();
    }

    @Test
    void immediateTokenPruningRetainsBothOwnersAndUnidentifiedVaultScopes() throws Exception {
        // An unrelated start must not destroy the only evidence of a legacy owner's secrets.
        final PoreloContext context = context().asking(shown -> "fixture-passphrase".toCharArray());
        final String sibling = "porelo-p_other";
        final String pending = "porelo-p-pending";
        final String unknown = "porelo-old-only";
        context.vault()
                .write(
                        Map.of(
                                TaskSecrets.PREFIX + TASK + "/route/forge",
                                        VaultEntry.of("first-route"),
                                TaskSecrets.PREFIX + sibling + "/route/forge",
                                        VaultEntry.of("second-route"),
                                TaskSecrets.PREFIX + unknown + "/signing-key",
                                        VaultEntry.of("unidentified-key")),
                        "fixture-passphrase".toCharArray());
        try {
            context.openerAsking(false, new java.io.PrintWriter(java.io.Writer.nullWriter()));
            try (var lease =
                    new TaskReservations(context).reserve("p", "pending", pending, "attempt")) {
                final TaskSecrets secrets = new TaskSecrets(context);
                secrets.keepSigning(
                        pending, new TaskSecrets.Signing("pending-key", "pending-certificate"));
                assertThat(
                                secrets.keep(
                                        TASK,
                                        new TaskSecrets.Tokens("gate", null),
                                        java.util.Set.of(TASK, sibling)))
                        .as("a start keeps exact scopes without reclaiming unknown ownership")
                        .isEmpty();
            }
            assertThat(context.vault().read("fixture-passphrase".toCharArray()).keySet())
                    .as("both owners, the pending attempt and unknown legacy scopes survive")
                    .contains(
                            TaskSecrets.PREFIX + TASK + "/route/forge",
                            TaskSecrets.PREFIX + sibling + "/route/forge",
                            TaskSecrets.PREFIX + pending + "/signing-key",
                            TaskSecrets.PREFIX + unknown + "/signing-key");
        } finally {
            context.forgetTyped();
        }
    }

    private static final String ID = "a".repeat(64);
    private static final String OTHER_ID = "b".repeat(64);

    private PoreloContext identities(String listed) {
        final PoreloContext base = context();
        return new PoreloContext(
                command -> new org.fuin.porelo.core.process.CommandResult(command, 0, listed, ""),
                base.paths(),
                arguments -> 0);
    }

    private void identityRecord(PoreloContext context, String container, String project)
            throws IOException {
        identityRecord(context, container, project, ID);
    }

    private void identityRecord(PoreloContext context, String container, String project, String id)
            throws IOException {
        final Path record = context.paths().tasks().taskRecord(container);
        org.fuin.porelo.wire.Sidecar.recordId(record, id);
        new org.fuin.porelo.wire.Sidecar(
                        3,
                        project,
                        "guarded",
                        "ruleset.nft",
                        "dns.conf",
                        "porelo",
                        context.paths().tasks().containerState(container).toString())
                .writeTo(record.resolve("sidecar.json"));
    }

    @Test
    void corruptOrContradictoryEncodedOwnershipIsARefusal() throws IOException {
        // Corrupt records must not let an action fall back to another task's ownership.
        final String name = "porelo-web_x";
        final PoreloContext context =
                identities(name + "\tUp 1 minute\t\t\tweb\tguarded\tr\t\t" + ID + "\twrong-task\n");
        identityRecord(context, name, "web");
        assertThat(new TaskIdentities(context).lookup(name, null))
                .isEqualTo(new TaskIdentities.Refused(TaskIdentities.Reason.INVALID_RECORD, name));
        Files.writeString(
                context.paths().tasks().taskRecord(name).resolve("sidecar.json"), "{broken");
        assertThat(new TaskIdentities(context).lookup(name, null))
                .isEqualTo(new TaskIdentities.Refused(TaskIdentities.Reason.INVALID_RECORD, name));
    }

    @Test
    void resolvesBothFormatsFromTheirRecordedOwnerWithoutDroppingNumericSuffixes()
            throws IOException {
        // Decoding the wrong logical name lends another task's gate or retained state.
        final PoreloContext context =
                identities(
                        "porelo-web-app-x-123\tUp 1 minute\t\t\tweb\tguarded\tr\t\t"
                                + ID
                                + "\n"
                                + "porelo-web-app_x-123\tUp 1 minute\t\t\tweb-app\tguarded\tr\t\t"
                                + OTHER_ID
                                + "\tx-123\n");
        identityRecord(context, "porelo-web-app-x-123", "web");
        identityRecord(context, "porelo-web-app_x-123", "web-app", OTHER_ID);
        final TaskIdentities lookup = new TaskIdentities(context);
        assertThat(lookup.lookup("app-x-123", "web"))
                .isEqualTo(
                        new TaskIdentities.Found(
                                new TaskIdentity(
                                        TaskIdentity.Format.LEGACY,
                                        "web",
                                        "app-x-123",
                                        "porelo-web-app-x-123",
                                        ID)));
        assertThat(lookup.lookup("x-123", "web-app"))
                .isEqualTo(
                        new TaskIdentities.Found(
                                new TaskIdentity(
                                        TaskIdentity.Format.ENCODED,
                                        "web-app",
                                        "x-123",
                                        "porelo-web-app_x-123",
                                        OTHER_ID)));
        assertThat(new TaskInventory(context).tasks())
                .extracting(TaskInventory.Task::shortName)
                .containsExactly("app-x-123", "x-123");
        assertThat(new TaskInventory(context).tasks().getLast().asMap())
                .containsEntry("task", "x-123")
                .containsEntry("name", "porelo-web-app_x-123");
    }

    @Test
    void contradictoryProjectAndIdAndMissingOwnershipRefuseActions() throws IOException {
        // A matching name must never authorize access to a replacement or another project.
        final String name = "porelo-web-app-x";
        final PoreloContext context =
                identities(name + "\tUp 1 minute\t\t\tweb\tguarded\tr\t\t" + ID + "\n");
        final TaskIdentities lookup = new TaskIdentities(context);
        assertThat(lookup.lookup(name, null))
                .isEqualTo(new TaskIdentities.Refused(TaskIdentities.Reason.MISSING_RECORD, name));
        identityRecord(context, name, "web-app");
        assertThat(lookup.lookup(name, null))
                .isEqualTo(
                        new TaskIdentities.Refused(TaskIdentities.Reason.PROJECT_MISMATCH, name));
        identityRecord(context, name, "web");
        org.fuin.porelo.wire.Sidecar.recordId(
                context.paths().tasks().containerState(name), "b".repeat(64));
        assertThat(lookup.lookup(name, null))
                .isEqualTo(new TaskIdentities.Refused(TaskIdentities.Reason.ID_MISMATCH, name));
        Files.delete(
                context.paths()
                        .tasks()
                        .containerState(name)
                        .resolve(org.fuin.porelo.wire.Sidecar.RECORDED_ID));
        assertThat(lookup.lookup(name, "web-app"))
                .isEqualTo(
                        new TaskIdentities.Refused(TaskIdentities.Reason.PROJECT_MISMATCH, name));
        final PoreloContext replaced =
                identities(name + "\tUp 1 minute\t\t\tweb\tguarded\tr\t\t" + "c".repeat(64) + "\n");
        assertThat(new TaskIdentities(replaced).lookup(name, null))
                .isEqualTo(new TaskIdentities.Refused(TaskIdentities.Reason.ID_MISMATCH, name));
    }

    @Test
    void durableLegacyOwnershipSurvivesRuntimeLossAndLookupWritesNothing() throws IOException {
        // A reboot must not orphan the old task or make it borrow a new task's paths.
        final String name = "porelo-porelo-login-123";
        final PoreloContext context = identities(name + "\tExited (0)\t\t\t\t\t\t\t" + ID + "\n");
        identityRecord(context, name, "porelo");
        final TaskIdentities.Result found =
                new TaskIdentities(context).lookup("login-123", "porelo");
        assertThat(found)
                .isEqualTo(
                        new TaskIdentities.Found(
                                new TaskIdentity(
                                        TaskIdentity.Format.LEGACY,
                                        "porelo",
                                        "login-123",
                                        name,
                                        ID)));
        assertThat(new TaskIdentities(context).newTaskRefusal("porelo", "login-123")).isEmpty();
        assertThat(new TaskIdentities(identities("")).newTaskRefusal("porelo", "login-123"))
                .isPresent();
        assertThat(context.paths().tasks().containerState(name)).doesNotExist();
        assertThat(context.podman().loginContainers("agent")).doesNotContainKey(name);
        assertThat(new TaskInventory(context).tasks())
                .singleElement()
                .satisfies(
                        task -> {
                            assertThat(task.name()).isEqualTo(name);
                            assertThat(task.shortName()).isEqualTo("login-123");
                            assertThat(task.asMap())
                                    .containsEntry("task", "login-123")
                                    .containsEntry("short", "login-123");
                        });
    }

    private static final String TASK = "porelo-p-t";

    private static final String GATE_TOKEN = "gate-secret-0123456789";

    @TempDir Path dir;

    private PoreloContext context() {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(
                new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
    }

    private Path runningTask(PoreloContext context) throws IOException {
        TaskIdentityFixture.record(context.paths(), TASK, "p", "t", ID);
        final Path runtime = Files.createDirectories(context.paths().tasks().containerState(TASK));
        org.fuin.porelo.wire.Sidecar.recordId(runtime, ID);
        Files.copy(
                context.paths().tasks().taskRecord(TASK).resolve("sidecar.json"),
                runtime.resolve("sidecar.json"));
        Files.writeString(runtime.resolve("task.json"), "{\"version\":3}");
        Files.writeString(runtime.resolve("ruleset.nft"), "table inet porelo {}");
        Files.writeString(runtime.resolve(TaskRunner.HOST_ADDRESS_FILE), "169.254.1.2");
        Files.writeString(runtime.resolve("dnsmasq.servers"), "server=/example.com/1.1.1.1\n");
        Files.writeString(runtime.resolve("vault.token"), "porelo_pt_phantom");
        Files.writeString(runtime.resolve("gate.pid"), "4242");
        new TaskHelpers(
                        List.of(
                                new TaskHelpers.Helper(
                                        "vault",
                                        List.of("porelo", "vault", "serve"),
                                        Map.of(),
                                        TaskHelpers.BEFORE),
                                new TaskHelpers.Helper(
                                        "gate",
                                        List.of("porelo", "gate", "serve"),
                                        Map.of(
                                                TaskState.GATE_TOKEN,
                                                GATE_TOKEN,
                                                "PORELO_GATE_UPSTREAM",
                                                "https://up.example/r.git"),
                                        TaskHelpers.AFTER)))
                .writeTo(runtime);
        return runtime;
    }

    @Test
    void savesWhatIsKnowledgeAboutTheTaskAndNothingLiveOrSecret() throws IOException {
        final PoreloContext context = context();
        runningTask(context);

        new TaskState(context).save(TASK);

        final Path durable = context.paths().tasks().taskRecord(TASK);
        assertThat(durable).startsWith(dir.resolve("state"));
        assertThat(durable.resolve("task.json")).exists();
        assertThat(durable.resolve("ruleset.nft")).hasContent("table inet porelo {}");
        assertThat(durable.resolve("dnsmasq.servers")).exists();
        assertThat(durable.resolve(TaskRunner.HOST_ADDRESS_FILE))
                .as("a resumed watcher still knows the host it never asks about")
                .hasContent("169.254.1.2");
        // Live, or secret: not saved.
        assertThat(durable.resolve("gate.pid")).doesNotExist();
        assertThat(durable.resolve("vault.token")).doesNotExist();
        // The resume record without the gate token, and with everything else.
        assertThat(Files.readString(durable.resolve(TaskHelpers.FILE)))
                .doesNotContain(GATE_TOKEN)
                .contains("PORELO_GATE_UPSTREAM");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(durable)))
                .isEqualTo("rwx------");
        assertThat(
                        PosixFilePermissions.toString(
                                Files.getPosixFilePermissions(durable.resolve("task.json"))))
                .isEqualTo("rw-------");
    }

    @Test
    void aFileThatIsGoneIsGoneFromTheSavedCopyToo() throws IOException {
        final PoreloContext context = context();
        final Path runtime = runningTask(context);
        Files.writeString(runtime.resolve("granted"), "example.org\n");
        new TaskState(context).save(TASK);
        Files.delete(runtime.resolve("granted"));

        new TaskState(context).save(TASK);

        assertThat(context.paths().tasks().taskRecord(TASK).resolve("granted")).doesNotExist();
    }

    @Test
    void whatARebootEmptiedIsPutBackAndTheGateIsGivenItsTokenAgain() throws IOException {
        final PoreloContext context = context();
        final Path runtime = runningTask(context);
        final TaskState state = new TaskState(context);
        state.save(TASK);
        deleteTree(runtime);

        assertThat(state.saved(TASK)).isTrue();
        state.restore(TASK);

        assertThat(runtime.resolve("ruleset.nft")).hasContent("table inet porelo {}");
        final TaskHelpers restored =
                TaskControl.withGateToken(TaskHelpers.readFrom(runtime), GATE_TOKEN);
        assertThat(restored.helpers())
                .filteredOn(helper -> "gate".equals(helper.name()))
                .singleElement()
                .satisfies(
                        gate ->
                                assertThat(gate.environment())
                                        .containsEntry(TaskState.GATE_TOKEN, GATE_TOKEN)
                                        .containsEntry(
                                                "PORELO_GATE_UPSTREAM",
                                                "https://up.example/r.git"));
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(runtime)))
                .isEqualTo("rwx------");
    }

    @Test
    void aRenewalAfterARebootReachesTheSavedCopy() throws IOException {
        final PoreloContext context = context();
        final String container = "porelo-uc-renewed";
        final Path record = context.paths().tasks().taskRecord(container);
        Files.createDirectories(record);

        new TaskState(context)
                .certified(container, java.time.Instant.parse("2026-11-11T00:00:00Z"));

        assertThat(TaskState.certifiedUntil(record))
                .as("no live state, and a list and a resume read this one")
                .isEqualTo("2026-11-11T00:00:00Z");
    }

    @Test
    void aTaskNothingWasSavedForCannotBeBroughtBack() {
        assertThat(new TaskState(context()).saved(TASK)).isFalse();
    }

    @Test
    void removingTheTaskForgetsWhatWasSaved() throws IOException {
        final PoreloContext context = context();
        runningTask(context);
        new TaskState(context).save(TASK);

        new TaskState(context).forget(TASK);

        assertThat(context.paths().tasks().taskRecord(TASK)).doesNotExist();
    }

    @Test
    void theTokensAreReadWhereTheLaunchLeftThem() throws IOException {
        final PoreloContext context = context();
        final Path runtime = runningTask(context);

        final TaskSecrets.Tokens tokens = TaskSecrets.fromRuntime(runtime);

        assertThat(tokens.gate()).isEqualTo(GATE_TOKEN);
        assertThat(tokens.provider()).isEqualTo("porelo_pt_phantom");
    }

    @Test
    void aTasksOwnEntriesAreNoCredentialAnyListingSees() {
        final Map<String, VaultEntry> entries =
                Map.of(
                        "anthropic", VaultEntry.of("sk-real"),
                        "task/porelo-p-t/gate-token", VaultEntry.of(GATE_TOKEN),
                        "task/porelo-p-t/provider-token", VaultEntry.of("porelo_pt_phantom"));

        assertThat(TaskSecrets.credentialsOnly(entries)).containsOnlyKeys("anthropic");
        assertThat(TaskSecrets.reserved("task/x/gate-token")).isTrue();
        assertThat(TaskSecrets.reserved("tasks")).isFalse();
    }

    @Test
    void withNoVaultAtAllNothingWasKeptWhichIsNotTheSameAsLocked() {
        assertThat(new TaskSecrets(context()).read(TASK))
                .contains(new TaskSecrets.Tokens(null, null));
    }

    @Test
    void keepingATasksTokensPrunesGoneTasksOnlyAndNeverTheAccountsGrantsOrTransportSecrets()
            throws IOException {

        // Found: every task start pruned every reserved name whose "owner" was no running container
        // - and a grant or a transport's secrets are owned by no task, so they went every time.
        org.junit.jupiter.api.Assumptions.assumeTrue(
                org.fuin.porelo.vault.KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");
        final PoreloContext context = context();
        final char[] passphrase = "correct horse battery staple".toCharArray();
        final org.fuin.porelo.vault.KernelKeyring keyring =
                new org.fuin.porelo.vault.KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            context.vault()
                    .write(
                            Map.of(
                                    TaskSecrets.GRANT_PREFIX + "forge-app",
                                    org.fuin.porelo.vault.VaultEntry.of("rt-1"),
                                    TaskSecrets.TRANSPORT_PREFIX + "matrix/account",
                                    org.fuin.porelo.vault.VaultEntry.of("{}"),
                                    TaskSecrets.PREFIX + "porelo-p-gone/gate-token",
                                    org.fuin.porelo.vault.VaultEntry.of("old"),
                                    TaskSecrets.PREFIX + "porelo-p-other/route/forge",
                                    org.fuin.porelo.vault.VaultEntry.of("rt-2"),
                                    "anthropic",
                                    org.fuin.porelo.vault.VaultEntry.of("sk-1")),
                            passphrase);
            keyring.store(passphrase);
            TaskIdentityFixture.record(context.paths(), "porelo-p-gone", "p", "gone", OTHER_ID);
            new TaskReservations(context).mutate("porelo-p-gone", () -> Boolean.TRUE);
            new TaskReservations(context).retire("porelo-p-gone");

            assertThat(
                            new TaskSecrets(context)
                                    .keep(
                                            TASK,
                                            new TaskSecrets.Tokens(GATE_TOKEN, null),
                                            java.util.Set.of(TASK, "porelo-p-other")))
                    .isEmpty();

            assertThat(context.vault().read(passphrase))
                    .containsKeys(
                            TaskSecrets.GRANT_PREFIX + "forge-app",
                            TaskSecrets.TRANSPORT_PREFIX + "matrix/account",
                            "anthropic")
                    // A route token is task/<container>/route/<name>: its owner was read up to the
                    // last slash, '<container>/route', which no task is called - so every start
                    // dropped every other task's.
                    .as("another task's route token")
                    .containsKey(TaskSecrets.PREFIX + "porelo-p-other/route/forge")
                    .as("a task that is gone")
                    .doesNotContainKey(TaskSecrets.PREFIX + "porelo-p-gone/gate-token");
        } finally {
            keyring.forget();
        }
    }

    @Test
    void theTasksThatExistAreAskedWhileTheVaultIsHeldNotBefore() throws IOException {

        // Taken before the vault was opened, the list missed a task whose start ran beside this
        // one: that start kept its tokens in between, this one pruned them as a gone task's, and it
        // could not come back after a reboot. Asked under the vault's lock, every container whose
        // tokens are in the file is in the list.
        org.junit.jupiter.api.Assumptions.assumeTrue(
                org.fuin.porelo.vault.KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");
        final PoreloContext context = context();
        final char[] passphrase = "correct horse battery staple".toCharArray();
        final org.fuin.porelo.vault.KernelKeyring keyring =
                new org.fuin.porelo.vault.KernelKeyring(context.paths().vault().vaultKeyringKey());
        try {
            Files.createDirectories(context.vault().path().getParent());
            context.vault()
                    .write(
                            Map.of("anthropic", org.fuin.porelo.vault.VaultEntry.of("sk-1")),
                            passphrase);
            keyring.store(passphrase);
            final Path lock =
                    context.vault()
                            .path()
                            .resolveSibling(context.vault().path().getFileName() + ".lock");
            final java.util.concurrent.atomic.AtomicBoolean heldWhenAsked =
                    new java.util.concurrent.atomic.AtomicBoolean();

            assertThat(
                            new TaskSecrets(context)
                                    .keep(
                                            TASK,
                                            new TaskSecrets.Tokens(GATE_TOKEN, null),
                                            () -> {
                                                heldWhenAsked.set(locked(lock));
                                                return java.util.Set.of(TASK);
                                            }))
                    .isEmpty();

            assertThat(heldWhenAsked)
                    .as("the vault's lock was held when the tasks were asked")
                    .isTrue();
        } finally {
            keyring.forget();
        }
    }

    /** Whether this process holds the file lock on the given file. */
    private static boolean locked(final Path file) {
        try (java.nio.channels.FileChannel channel =
                        java.nio.channels.FileChannel.open(
                                file,
                                java.nio.file.StandardOpenOption.CREATE,
                                java.nio.file.StandardOpenOption.WRITE);
                java.nio.channels.FileLock free = channel.tryLock()) {
            return free == null;
        } catch (final java.nio.channels.OverlappingFileLockException ex) {
            return true;
        } catch (final IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    @Test
    void aLockedVaultKeepsNothingAndSaysTheTaskCannotComeBack() {
        final String said =
                new TaskSecrets(context())
                        .keep(
                                TASK,
                                new TaskSecrets.Tokens(GATE_TOKEN, null),
                                java.util.Set.of(TASK));

        assertThat(said).contains("cannot come back after a reboot");
    }

    private static void deleteTree(Path root) throws IOException {
        try (var walk = Files.walk(root)) {
            for (final Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
