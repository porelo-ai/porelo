package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.KernelKeyring;
import org.fuin.porelo.vault.VaultEntry;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for {@link VaultClearing} and {@code porelo vault clear}: the vault, its backup, its lock
 * and what the keyring caches of it go in one step, after the transports have cleared what they
 * keep with its secrets.
 *
 * <p>Every path is in a temporary directory, and the keyring descriptions derive from the vault's
 * path there, so nothing here touches the developer's own vault or keyring; whatever a test caches
 * is forgotten after it.
 */
class VaultClearingTest {

    private static final char[] PASSPHRASE = "correct horse battery staple".toCharArray();

    @TempDir Path dir;

    private final FakeCommandRunner runner = new FakeCommandRunner();

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private @Nullable PoreloContext used;

    private PoreloContext context() {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        final PoreloContext context =
                new PoreloContext(runner, new PoreloPaths(xdg, dir.resolve("bin")), arguments -> 0);
        used = context;
        return context;
    }

    @AfterEach
    void forgetWhatTheKeyringHolds() {
        PoreloContext.forgetAsked();
        // Every test's own description, from its own temporary vault: a passphrase left behind
        // outlives the test in the developer's account.
        if (used != null && KernelKeyring.available()) {
            new KernelKeyring(used.paths().vault().vaultKeyringKey()).forget();
            VaultShare.forget(used.paths());
        }
    }

    /**
     * A vault holding a credential, a grant, a transport's account and a stopped task's token,
     * unlocked.
     */
    private PoreloContext unlockedVault() throws IOException {
        assumeTrue(
                KernelKeyring.available(),
                "no kernel keyring here: libkeyutils is missing or the kernel refuses it");
        final PoreloContext context = context();
        Files.createDirectories(context.vault().path().getParent());
        final Map<String, VaultEntry> entries = new LinkedHashMap<>();
        entries.put("github", VaultEntry.of("not-a-real-token"));
        entries.put(VaultNames.GRANT_PREFIX + "mail", VaultEntry.of("not-a-real-refresh-token"));
        entries.put(VaultNames.CLIENT_SECRET_PREFIX + "mail", VaultEntry.of("not-a-real-secret"));
        entries.put(
                VaultNames.TRANSPORT_PREFIX + "room/account", VaultEntry.of("{\"ADMIN\":\"a-1\"}"));
        entries.put(VaultNames.TASK_PREFIX + "porelo-p-old/gate-token", VaultEntry.of("g-1"));
        context.vault().write(entries, PASSPHRASE);
        new KernelKeyring(context.paths().vault().vaultKeyringKey()).store(PASSPHRASE);
        return context;
    }

    private Path backup(final PoreloContext context) {
        return context.vault().path().resolveSibling(context.vault().path().getFileName() + ".old");
    }

    private Path lock(final PoreloContext context) {
        return context.vault()
                .path()
                .resolveSibling(context.vault().path().getFileName() + ".lock");
    }

    /** A project registered on this machine, from a file of the person's. */
    private void project(final String name, final String extra) throws IOException {
        Files.createDirectories(dir.resolve("data/porelo/projects"));
        final Path file = dir.resolve(name + "-project.yml");
        Files.writeString(
                file,
                "project:\n  name: \""
                        + name
                        + "\"\n  security_class: \"guarded\"\n"
                        + "image:\n  base_image: \"ubuntu:24.04\"\n"
                        + extra,
                StandardCharsets.UTF_8);
        Files.writeString(
                dir.resolve("data/porelo/projects").resolve(name),
                file + "\n",
                StandardCharsets.UTF_8);
    }

    /** An adapter the transport directory finds, answering what the runner is told to answer. */
    private void transport(final String scheme) throws IOException {
        final Path adapters = Files.createDirectories(dir.resolve("data/porelo/transports"));
        final Path adapter = adapters.resolve(TransportDirectory.PREFIX + scheme);
        Files.writeString(adapter, "#!/bin/sh\n");
        adapter.toFile().setExecutable(true);
        runner.answering(
                "describe",
                "{\"scheme\":\""
                        + scheme
                        + "\",\"lifecycle\":[\"setup\",\"enroll\",\"retire\","
                        + "\"clear\"]}");
    }

    private int execute(final PoreloContext context, final String... arguments) {
        final CommandLine command = PoreloCli.commandLine(context);
        command.setOut(new PrintWriter(out, true));
        command.setErr(new PrintWriter(err, true));
        return command.execute(arguments);
    }

    private static boolean names(
            final Clearing.Result result,
            final String kind,
            final String what,
            final Clearing.Status status) {
        return result.items().stream()
                .anyMatch(
                        item ->
                                item.kind().equals(kind)
                                        && item.what().contains(what)
                                        && item.status() == status);
    }

    @Test
    void aListingNamesTheFilesTheKeyringAndWhatTheVaultHoldsAndRemovesNothing() throws IOException {
        final PoreloContext context = unlockedVault();
        Files.writeString(backup(context), "an older copy");
        Files.writeString(lock(context), "");

        final Clearing.Result listed = new VaultClearing(context).clear(true, false);

        assertThat(listed.refused()).isFalse();
        assertThat(
                        names(
                                listed,
                                "vault",
                                context.vault().path().toString(),
                                Clearing.Status.WOULD_REMOVE))
                .isTrue();
        assertThat(
                        names(
                                listed,
                                "backup",
                                backup(context).toString(),
                                Clearing.Status.WOULD_REMOVE))
                .isTrue();
        assertThat(names(listed, "lock", lock(context).toString(), Clearing.Status.WOULD_REMOVE))
                .isTrue();
        assertThat(names(listed, "keyring", "passphrase", Clearing.Status.WOULD_REMOVE)).isTrue();
        assertThat(names(listed, "credential", "github", Clearing.Status.WOULD_REMOVE)).isTrue();
        assertThat(names(listed, "grant", "mail", Clearing.Status.WOULD_REMOVE)).isTrue();
        assertThat(names(listed, "client secret", "mail", Clearing.Status.WOULD_REMOVE))
                .as("a sign-in's client secret is named for what it is, not as a credential")
                .isTrue();
        assertThat(names(listed, "transport account", "room/account", Clearing.Status.WOULD_REMOVE))
                .isTrue();
        assertThat(names(listed, "task token", "porelo-p-old", Clearing.Status.WOULD_REMOVE))
                .isTrue();
        assertThat(context.vault().path()).exists();
        assertThat(backup(context)).exists();
        assertThat(lock(context)).exists();
        assertThat(new KernelKeyring(context.paths().vault().vaultKeyringKey()).read())
                .as("still cached")
                .isPresent();
    }

    @Test
    void clearingRemovesTheFilesAndForgetsThePassphraseAndEveryDevicesShare() throws IOException {
        final PoreloContext context = unlockedVault();
        Files.writeString(backup(context), "an older copy");
        Files.writeString(lock(context), "");
        VaultShare.keep(context.paths(), new byte[] {1, 2, 3}, Duration.ofMinutes(5));

        final Clearing.Result cleared = new VaultClearing(context).clear(false, false);

        assertThat(cleared.refused()).isFalse();
        assertThat(cleared.items()).noneMatch(item -> item.status() == Clearing.Status.NOT_REMOVED);
        assertThat(context.vault().path()).doesNotExist();
        assertThat(backup(context)).doesNotExist();
        assertThat(lock(context)).doesNotExist();
        assertThat(new KernelKeyring(context.paths().vault().vaultKeyringKey()).read()).isEmpty();
        assertThat(VaultShare.held(context.paths())).isEmpty();
        assertThat(new VaultClearing(context).clear(false, false).items())
                .as("nothing left to clear")
                .isEmpty();
    }

    @Test
    void afterAClearInitStartsAsOnANewMachineAndTheOldPassphraseIsNotCached() throws IOException {
        final PoreloContext context = unlockedVault();

        assertThat(execute(context, "vault", "clear", "--yes")).as("said: %s%s", out, err).isZero();
        assertThat(new KernelKeyring(context.paths().vault().vaultKeyringKey()).read()).isEmpty();

        final PoreloContext initializing =
                new PoreloContext(
                        new org.fuin.porelo.core.process.ProcessCommandRunner(),
                        context.paths(),
                        arguments -> 0);
        assertThat(execute(initializing, "vault", "init", "--passphrase-command", "printf fresh"))
                .as("said: %s%s", out, err)
                .isZero();
        assertThat(context.vault().read("fresh".toCharArray())).isEmpty();
        assertThat(new KernelKeyring(context.paths().vault().vaultKeyringKey()).read())
                .hasValueSatisfying(cached -> assertThat(new String(cached)).isEqualTo("fresh"));
    }

    @Test
    void withoutYesTheCommandListsAndSaysNothingWasRemoved() throws IOException {
        final PoreloContext context = unlockedVault();

        assertThat(execute(context, "vault", "clear")).as("said: %s%s", out, err).isZero();

        assertThat(out.toString())
                .contains("would go")
                .contains(context.vault().path().toString())
                .contains("--yes");
        assertThat(context.vault().path()).exists();
    }

    @Test
    void aRunningTaskThatHoldsATokenFromTheVaultIsNamedAndTheClearRefusedEvenWithForce()
            throws IOException {
        final PoreloContext context = unlockedVault();
        final Map<String, VaultEntry> entries =
                new LinkedHashMap<>(context.vault().read(PASSPHRASE));
        entries.put(
                VaultNames.TASK_PREFIX + "porelo-p-shell-1/provider-token", VaultEntry.of("p-1"));
        context.vault().write(entries, PASSPHRASE);
        org.fuin.porelo.wire.Sidecar.recordId(
                context.paths().tasks().containerState("porelo-p-shell-1"), "111111111111");
        runner.answering("ps", "111111111111\trunning\n");

        final Clearing.Result cleared = new VaultClearing(context).clear(false, true);

        assertThat(cleared.refused()).isTrue();
        assertThat(names(cleared, "task", "porelo-p-shell-1", Clearing.Status.NOT_REMOVED))
                .isTrue();
        assertThat(context.vault().path()).exists();
        assertThat(new KernelKeyring(context.paths().vault().vaultKeyringKey()).read()).isPresent();
    }

    @Test
    void aRunningTaskThatHoldsNothingFromTheVaultDoesNotStopTheClear() throws IOException {
        final PoreloContext context = unlockedVault();
        org.fuin.porelo.wire.Sidecar.recordId(
                context.paths().tasks().containerState("porelo-p-shell-2"), "222222222222");
        runner.answering("ps", "222222222222\trunning\n");

        final Clearing.Result cleared = new VaultClearing(context).clear(false, false);

        assertThat(cleared.refused()).isFalse();
        assertThat(context.vault().path()).doesNotExist();
    }

    @Test
    void anAccessProjectsAccountElsewhereIsClearedWithItsAppliedSettings() throws IOException {
        final PoreloContext context = context();
        transport("room");
        project(
                "p",
                "access: {repository: 'git@example.org:p/access.git'}\n"
                        + "mail:\n  peers:\n    people: {address: 'room:', trust: external}\n");
        final Path clone = Files.createDirectories(context.paths().projects().followedClone("p"));
        Files.copy(dir.resolve("p-project.yml"), clone.resolve("project.yml"));
        final Path accessClone =
                Files.createDirectories(context.paths().projects().followedAccessClone("p"));
        Files.writeString(
                accessClone.resolve("access.yml"),
                "transports: {room: {server: 'https://access.example.org'}}\n");
        runner.answering(
                "show a1:access.yml",
                "transports: {room: {server: 'https://access.example.org'}}\n");
        new FollowedProjects(context.paths().projects().followed())
                .write(
                        new FollowedProjects.Followed(
                                "p",
                                "git@example.org:p.git",
                                "c1",
                                "",
                                "APPLIED",
                                "",
                                "",
                                "",
                                false,
                                "a1",
                                "APPLIED"));
        runner.answering(" clear", "{\"removed\":[],\"kept\":[]}");

        final Clearing.Result cleared = new VaultClearing(context).clear(false, false);

        assertThat(cleared.items())
                .as("the access file's server is cleared before the vault goes")
                .anyMatch(
                        item ->
                                item.kind().equals("conversation")
                                        && item.project().equals("p")
                                        && item.status() == Clearing.Status.REMOVED);
        assertThat(runner.only("clear --project p").input())
                .as("the applied access settings reach the transport")
                .contains("https://access.example.org");
    }

    @Test
    void
            aProjectsAccountOnAServerElsewhereIsClearedWithTheVaultsSecretsBeforeItGoesAndTheAccountAfter()
                    throws IOException {
        final PoreloContext context = unlockedVault();
        transport("room");
        project("p", "mail:\n  transports:\n    room:\n      server: https://example.org\n");
        project("q", "");
        final Map<String, VaultEntry> entries =
                new LinkedHashMap<>(context.vault().read(PASSPHRASE));
        entries.put(
                VaultNames.TRANSPORT_PREFIX + "room/project/p",
                VaultEntry.of("{\"POLLER\":\"p-1\"}"));
        context.vault().write(entries, PASSPHRASE);
        runner.answering(" clear", "{\"removed\":[\"the room\"],\"kept\":[]}");

        final Clearing.Result listed = new VaultClearing(context).clear(true, false);
        assertThat(names(listed, "conversation", "p", Clearing.Status.WOULD_REMOVE)).isTrue();
        assertThat(names(listed, "transport", "room", Clearing.Status.WOULD_REMOVE)).isTrue();
        assertThat(runner.lines()).noneMatch(line -> line.contains(" clear"));

        final Clearing.Result cleared = new VaultClearing(context).clear(false, false);

        assertThat(cleared.refused()).isFalse();
        assertThat(cleared.items())
                .filteredOn(item -> item.kind().equals("conversation"))
                .as("each of the project's conversation items names its transport and project")
                .isNotEmpty()
                .allSatisfy(
                        item -> {
                            assertThat(item.transport()).isEqualTo("room");
                            assertThat(item.project()).isEqualTo("p");
                        });
        final List<String> lines = runner.lines();
        final int project = indexOf(lines, line -> line.contains(" clear --project p"));
        final int account = indexOf(lines, line -> line.endsWith(" clear"));
        assertThat(project).as("the project's clear ran: %s", lines).isNotNegative();
        assertThat(account)
                .as("the account's clear ran after it: %s", lines)
                .isGreaterThan(project);
        assertThat(lines)
                .as("a project with no server elsewhere is the account's to clear")
                .noneMatch(line -> line.contains(" clear --project q"));
        final Command projectClear = runner.only("clear --project p");
        assertThat(projectClear.environment())
                .as("it was given the vault's secrets, so the vault was still there")
                .containsEntry("POLLER", "p-1")
                .containsEntry("ADMIN", "a-1");
        assertThat(
                        runner.invocations().stream()
                                .filter(each -> each.describe().endsWith(" clear"))
                                .findFirst()
                                .orElseThrow()
                                .environment())
                .as("the account's clear runs after the vault went")
                .doesNotContainKey("ADMIN");
        assertThat(context.vault().path()).doesNotExist();
    }

    @Test
    void aVaultOtherThanTheAccountsOwnLeavesWhatTheTransportsKeepForTheAccount()
            throws IOException {
        // PORELO_VAULT names another vault so that a run does not touch the account's own; the
        // transports' account-wide state and the account's own homeserver belong to that one.
        final PoreloContext context = unlockedVault();
        transport("room");
        project("p", "mail:\n  transports:\n    room:\n      server: https://example.org\n");
        final Path kept = Files.createDirectories(dir.resolve("state/porelo/transport/room"));
        Files.writeString(kept.resolve("position"), "42");
        runner.answering(" clear", "{\"removed\":[\"the room\"],\"kept\":[]}");
        final Path own = dir.resolve("own/vault.bin");

        final Clearing.Result listed = new VaultClearing(context, own).clear(true, false);
        assertThat(listed.items())
                .as("nothing of the account's is listed")
                .noneMatch(item -> "transport".equals(item.kind()));

        final Clearing.Result cleared = new VaultClearing(context, own).clear(false, false);

        assertThat(cleared.refused()).isFalse();
        assertThat(cleared.items()).noneMatch(item -> item.status() == Clearing.Status.NOT_REMOVED);
        assertThat(context.vault().path()).doesNotExist();
        assertThat(kept.resolve("position")).as("the account's transport state").exists();
        assertThat(runner.lines())
                .as("the project's account was made with this vault's token, so it goes")
                .anyMatch(line -> line.contains(" clear --project p"))
                .as("the account-wide clear is the account's own vault's")
                .noneMatch(line -> line.endsWith(" clear"));
    }

    @Test
    void aTransportThatCannotClearAProjectKeepsTheVaultUnlessForced() throws IOException {
        final PoreloContext context = unlockedVault();
        transport("room");
        project("p", "mail:\n  transports:\n    room:\n      server: https://example.org\n");
        runner.failing("clear --project", 75, "the server did not answer");

        final Clearing.Result refused = new VaultClearing(context).clear(false, false);

        assertThat(refused.refused()).isTrue();
        assertThat(refused.items())
                .anyMatch(
                        item ->
                                item.status() == Clearing.Status.NOT_REMOVED
                                        && item.why().contains("the server did not answer"));
        assertThat(context.vault().path()).exists();
        assertThat(runner.lines()).noneMatch(line -> line.endsWith(" clear"));

        final Clearing.Result forced = new VaultClearing(context).clear(false, true);

        assertThat(forced.refused()).isFalse();
        assertThat(context.vault().path()).doesNotExist();
    }

    @Test
    void aTransportsRefusalIsNamedAsSuchForAProgramThatDecidesOnForce() throws IOException {
        // A play that guessed from the sentence would force past a running task's token, or stop a
        // give-back that could have gone on.
        final PoreloContext context = unlockedVault();
        transport("room");
        project("p", "mail:\n  transports:\n    room:\n      server: https://example.org\n");
        runner.failing("clear --project", 75, "the server did not answer");

        final Map<String, Object> json =
                VaultClearCommand.asJson(new VaultClearing(context).clear(false, false));

        assertThat(json)
                .as("the refusal and why the vault was kept are values")
                .containsEntry("refused", true)
                .containsEntry("kept", "transports");
        assertThat((List<?>) json.get("items"))
                .as("the item that was not cleared names its transport and project")
                .anySatisfy(
                        item ->
                                assertThat((Map<Object, Object>) item)
                                        .containsEntry("kind", "conversation")
                                        .containsEntry("status", "NOT_REMOVED")
                                        .containsEntry("transport", "room")
                                        .containsEntry("project", "p"));
    }

    @Test
    void aForcedJsonClearWhoseTransportCouldNotClearExitsAsAFailure() throws IOException {
        // A play reading exit 0 would tell the person that account is gone from its server.
        final PoreloContext context = unlockedVault();
        transport("room");
        project("p", "mail:\n  transports:\n    room:\n      server: https://example.org\n");
        runner.failing("clear --project", 75, "the server did not answer");

        final int exit = execute(context, "vault", "clear", "--yes", "--force", "--json");

        assertThat(exit).as("an account left on its server is a failure: %s%s", out, err).isOne();
        assertThat(out.toString())
                .as("not refused, since --force removed the vault, and the item says what stayed")
                .contains("\"refused\":false")
                .contains("NOT_REMOVED");
    }

    @Test
    void aLockedVaultOrATaskHoldingATokenIsKeptForItsOwnNamedReason() throws IOException {
        final PoreloContext locked = context();
        Files.createDirectories(locked.vault().path().getParent());
        locked.vault().write(Map.of("github", VaultEntry.of("not-a-real-token")), PASSPHRASE);

        assertThat(new VaultClearing(locked).clear(false, false).kept())
                .as("a locked vault is kept as locked, which --force clears")
                .isEqualTo(Clearing.Kept.LOCKED);
        assertThat(new VaultClearing(locked).clear(true, false).kept())
                .as("a dry run that would be refused names the same reason")
                .isEqualTo(Clearing.Kept.LOCKED);

        final PoreloContext holding = unlockedVault();
        final Map<String, VaultEntry> entries =
                new LinkedHashMap<>(holding.vault().read(PASSPHRASE));
        entries.put(
                VaultNames.TASK_PREFIX + "porelo-p-shell-1/provider-token", VaultEntry.of("p-1"));
        holding.vault().write(entries, PASSPHRASE);
        org.fuin.porelo.wire.Sidecar.recordId(
                holding.paths().tasks().containerState("porelo-p-shell-1"), "111111111111");
        runner.answering("ps", "111111111111\trunning\n");

        assertThat(new VaultClearing(holding).clear(false, true).kept())
                .as("a running task's token is named apart, since --force does not get past it")
                .isEqualTo(Clearing.Kept.TASKS);
        assertThat(new VaultClearing(holding).clear(true, false).kept())
                .as("a dry run names it too")
                .isEqualTo(Clearing.Kept.TASKS);
    }

    @Test
    void aForcedClearOfALockedVaultARunningTaskHoldsSaysTheVaultStayed() throws IOException {
        // Said removed, it sent the person to 'vault init' over a vault still on disk.
        final PoreloContext context = context();
        Files.createDirectories(context.vault().path().getParent());
        context.vault().write(Map.of("github", VaultEntry.of("not-a-real-token")), PASSPHRASE);
        org.fuin.porelo.wire.Sidecar.recordId(
                context.paths().tasks().containerState("porelo-p-shell-1"), "111111111111");
        runner.answering("ps", "111111111111\trunning\n");

        final Clearing.Result cleared = new VaultClearing(context).clear(false, true);

        assertThat(cleared.kept()).isEqualTo(Clearing.Kept.TASKS);
        assertThat(names(cleared, "vault", "holds", Clearing.Status.REMOVED))
                .as("a vault that stays is never answered removed")
                .isFalse();
        assertThat(context.vault().path()).exists();
    }

    @Test
    void aLockedVaultIsClearedOnlyWithForceBecauseWhatItHoldsCannotBeNamed() throws IOException {
        final PoreloContext context = context();
        Files.createDirectories(context.vault().path().getParent());
        context.vault().write(Map.of("github", VaultEntry.of("not-a-real-token")), PASSPHRASE);

        final Clearing.Result refused = new VaultClearing(context).clear(false, false);

        assertThat(refused.refused()).isTrue();
        assertThat(refused.items()).anyMatch(item -> item.why().contains("locked"));
        assertThat(context.vault().path()).exists();

        final Clearing.Result forced = new VaultClearing(context).clear(false, true);

        assertThat(forced.refused()).isFalse();
        assertThat(context.vault().path()).doesNotExist();
    }

    @Test
    void aProjectsDeployKeysAreNamedForAPersonToRemoveAtTheForge() throws Exception {
        // Erasing the vault must still name earlier forge access for a person to revoke.
        final PoreloContext context = context().asking(prompt -> PASSPHRASE.clone());
        context.vault().write(Map.of(), PASSPHRASE);
        context.openerAsking(false, new PrintWriter(new StringWriter()));
        project(
                "web",
                "repositories:\n  api:\n    upstream: \"git@forge.example.org:o/web.git\"\n");
        final LeftDeployKeys.Key made =
                LeftDeployKeysTest.earlierKey(
                        context, "web", "api", "git@forge.example.org:o/web.git", false);

        final Clearing.Result cleared = new VaultClearing(context).clear(false, false);

        assertThat(names(cleared, "deploy key", made.fingerprint(), Clearing.Status.FOR_A_PERSON))
                .as("the person is told which forge access remains")
                .isTrue();
        assertThat(cleared.keys())
                .as("the earlier key is named exactly once")
                .extracting(LeftDeployKeys.Key::fingerprint)
                .containsExactly(made.fingerprint());
        assertThat(context.vault().path())
                .as("the vault and its private halves are removed")
                .doesNotExist();
    }

    @Test
    void anAccountWithNoVaultHasNothingToClear() {
        assertThat(new VaultClearing(context()).clear(false, false).items()).isEmpty();
    }

    private static int indexOf(
            final List<String> lines, final java.util.function.Predicate<String> which) {
        for (int i = 0; i < lines.size(); i++) {
            if (which.test(lines.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
