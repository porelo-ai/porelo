package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.VaultEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link CredentialChange}. */
class CredentialChangeTest {

    private static final char[] PASSPHRASE = "correct horse battery staple".toCharArray();

    private static final String VALUE = "secret-value-3b9f";

    @TempDir Path dir;

    private PoreloContext context;

    private void openVault(final Map<String, VaultEntry> entries) {
        context =
                new PoreloContext(
                                new FakeCommandRunner(),
                                new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                                arguments -> 0)
                        .asking(prompt -> PASSPHRASE.clone());
        context.vault().write(entries, PASSPHRASE);
        assertThat(context.openerAsking(false, new PrintWriter(Writer.nullWriter())))
                .as("the fixture opens its own vault")
                .isPresent();
    }

    @AfterEach
    void forgetThePassphrase() {
        PoreloContext.forgetAsked();
    }

    private static TaskInventory.Task task(
            final String name,
            final boolean running,
            final String agent,
            final Map<String, String> credentials) {
        return new TaskInventory.Task(
                name,
                "uc",
                "guarded",
                running ? "Up 4 minutes" : "Exited",
                running,
                1,
                agent,
                "AGENT",
                null,
                null,
                "2026-11-08T12:00:00Z",
                TaskInventory.Activity.WORKING,
                null,
                null,
                null,
                0,
                "RUNNING",
                "",
                null,
                null,
                AgentWaiting.Derived.NOTHING,
                credentials);
    }

    /** The same task, its provider named. */
    private static TaskInventory.Task withProvider(
            final TaskInventory.Task t, final String provider) {
        return new TaskInventory.Task(
                t.name(),
                t.project(),
                t.securityClass(),
                t.state(),
                t.running(),
                t.helpers(),
                t.agent(),
                t.mode(),
                t.prompt(),
                t.branch(),
                t.since(),
                t.activity(),
                t.waitingFor(),
                t.clearance(),
                t.label(),
                t.waiting(),
                t.startAction(),
                t.startDetail(),
                t.repository(),
                t.commit(),
                t.derived(),
                t.credentials(),
                t.grants(),
                provider,
                t.agentEnded(),
                t.handIns(),
                t.builds(),
                t.parameters(),
                t.phase(),
                t.lastWrote(),
                t.inboxWaiting(),
                t.forgeBranch(),
                t.certified(),
                t.certifiedUntil());
    }

    @Test
    void anEntryUnderTheAgentsNameIsBorrowedWhenItsProviderIsNamedToo() {
        // The provider's credential falls back to the agent's name when only that is stored.
        openVault(Map.of("example", VaultEntry.of(VALUE)));
        final CredentialChange change =
                new CredentialChange(
                        context,
                        () ->
                                List.of(
                                        withProvider(
                                                task(
                                                        "porelo-uc-shell-1",
                                                        true,
                                                        "example",
                                                        Map.of()),
                                                "anthropic")));

        final CredentialChange.Refused refused =
                catchThrowableOfType(
                        CredentialChange.Refused.class, () -> change.remove("example"));

        assertThat(refused)
                .as("a remove of what a running task borrows is refused")
                .isNotNull()
                .extracting(CredentialChange.Refused::reason)
                .isEqualTo(CredentialChange.Reason.BORROWED);
        assertThat(context.vault().read(PASSPHRASE)).as("the entry stays").containsKey("example");
    }

    @Test
    void aBlankValueIsRefusedForAnEntryWithAGrantsKind() {
        // A grant's entry reads "-" for "no secret"; a blank replace must not quietly become that.
        openVault(Map.of("svc", new VaultEntry(VALUE, "oauth-code")));
        final CredentialChange change = new CredentialChange(context, List::of);

        final CredentialChange.Refused refused =
                catchThrowableOfType(
                        CredentialChange.Refused.class, () -> change.replace("svc", "  \n"));

        assertThat(refused)
                .as("refused as no value")
                .isNotNull()
                .extracting(CredentialChange.Refused::reason)
                .isEqualTo(CredentialChange.Reason.NO_VALUE);
        assertThat(context.vault().read(PASSPHRASE).get("svc").value())
                .as("the client secret stays")
                .isEqualTo(VALUE);
    }

    @Test
    void aShutVaultIsRefusedWithoutAskingAnybody() {
        context =
                new PoreloContext(
                                new FakeCommandRunner(),
                                new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                                arguments -> 0)
                        .asking(
                                prompt -> {
                                    throw new AssertionError("a daemon has nobody to ask");
                                });
        context.vault().write(Map.of("forge", VaultEntry.of(VALUE)), PASSPHRASE);
        final CredentialChange change = new CredentialChange(context, List::of);

        assertThat(
                        catchThrowableOfType(
                                        CredentialChange.Refused.class,
                                        () -> change.remove("forge"))
                                .reason())
                .as("shut, and said so")
                .isEqualTo(CredentialChange.Reason.VAULT_LOCKED);
    }

    @Test
    void noVaultIsSaid() {
        context =
                new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                        arguments -> 0);
        final CredentialChange change = new CredentialChange(context, List::of);

        assertThat(
                        catchThrowableOfType(
                                        CredentialChange.Refused.class,
                                        () -> change.replace("forge", "x"))
                                .reason())
                .as("nothing to change")
                .isEqualTo(CredentialChange.Reason.NO_VAULT);
    }

    @Test
    void anEntryARunningTaskBorrowsIsRefusedAndNamesTheTask() {
        openVault(Map.of("forge", VaultEntry.of(VALUE)));
        final CredentialChange change =
                new CredentialChange(
                        context,
                        () ->
                                List.of(
                                        task(
                                                "porelo-uc-shell-1",
                                                true,
                                                "example",
                                                Map.of("forge", "https://forge.example"))));

        final CredentialChange.Refused refused =
                catchThrowableOfType(
                        CredentialChange.Refused.class, () -> change.replace("forge", "other"));

        assertThat(refused.reason())
                .as("a borrowed entry is refused by name")
                .isEqualTo(CredentialChange.Reason.BORROWED);
        assertThat(refused.tasks())
                .as("the refusal names the task that borrows it")
                .containsExactly("porelo-uc-shell-1");
        assertThat(context.vault().read(PASSPHRASE).get("forge").value())
                .as("the value stays as it was")
                .isEqualTo(VALUE);
    }

    @Test
    void aStoppedTaskBorrowsNothingAndAnAgentNameIsStillBorrowed() {
        final List<TaskInventory.Task> tasks =
                List.of(
                        task("porelo-uc-shell-1", false, "example", Map.of("forge", "x")),
                        task("porelo-uc-shell-2", true, "example", Map.of()));

        assertThat(CredentialChange.borrowing("forge", tasks))
                .as("a stopped task holds no credential")
                .isEmpty();
        assertThat(CredentialChange.borrowing("example", tasks))
                .as("a task without a provider still falls back to its agent's name")
                .containsExactly("porelo-uc-shell-2");
    }

    @Test
    void anEntryWithAGrantIsRefusedSinceItsServiceIsToldFirst() {
        openVault(
                Map.of(
                        "svc",
                        new VaultEntry("-", "oauth-device"),
                        TaskSecrets.GRANT_PREFIX + "svc",
                        new VaultEntry(VALUE, VaultAuthorizeCommand.HELD)));
        final CredentialChange change = new CredentialChange(context, List::of);

        final CredentialChange.Refused refused =
                catchThrowableOfType(CredentialChange.Refused.class, () -> change.remove("svc"));

        assertThat(refused.reason())
                .as("a grant is revoked at its service, at the machine")
                .isEqualTo(CredentialChange.Reason.GRANTED);
        assertThat(refused.getMessage()).as("the refusal carries no value").doesNotContain(VALUE);
        assertThat(context.vault().read(PASSPHRASE))
                .as("the entry and its grant stay")
                .containsKeys("svc", TaskSecrets.GRANT_PREFIX + "svc");
    }

    @Test
    void removingTakesTheClientSecretASignInLeftBeside() {
        openVault(
                Map.of(
                        "forge",
                        VaultEntry.of(VALUE),
                        TaskSecrets.CLIENT_SECRET_PREFIX + "forge",
                        VaultEntry.of("client-secret")));

        new CredentialChange(context, List::of).remove("forge");

        assertThat(context.vault().read(PASSPHRASE))
                .as("the entry goes with what was kept for it, as 'vault remove' does")
                .isEmpty();
    }

    @Test
    void anEmptyValueIsRefusedAndNeverStored() {
        openVault(Map.of("forge", VaultEntry.of(VALUE)));

        final CredentialChange.Refused refused =
                catchThrowableOfType(
                        CredentialChange.Refused.class,
                        () -> new CredentialChange(context, List::of).replace("forge", "  \n"));

        assertThat(refused.reason())
                .as("an empty value is refused by name")
                .isEqualTo(CredentialChange.Reason.NO_VALUE);
        assertThat(refused.toString()).as("nothing of the old value is said").doesNotContain(VALUE);
    }
}
