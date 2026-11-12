package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.organization.gittree.CommitSigning;
import org.fuin.porelo.organization.gittree.FetchAccess;
import org.fuin.porelo.organization.gittree.GitTreeWrites;
import org.fuin.porelo.vault.PersonCard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A refused first-card push leaves the card available to join, with the next action named. */
class IdentityJoinTest {
    @TempDir Path dir;
    boolean keptBeforePush;
    Path kept;

    void refusedPush() throws Exception {
        try (RegistryCheckout machine = new RegistryCheckout(dir)) {
            TestOrganization organization = new TestOrganization(machine);
            kept = machine.paths.xdg().config().resolve("identity/card");
            machine.forge.keys.put("alice", java.util.List.of(machine.key("alice")));
            Map<String, Path> files = new HashMap<>();
            machine.keyFiles.forEach((login, path) -> files.put(machine.key(login), path));
            CommandRunner runner =
                    command -> {
                        if (command.arguments().contains("push")) {
                            keptBeforePush = Files.isRegularFile(kept);
                            return new CommandResult(command, 1, "", "refused");
                        }
                        return machine.runner.run(command);
                    };
            IdentityOrganization.Opened original =
                    organization.opener().open(machine.context(), TestOrganization.ADDRESS);
            IdentityOrganization.Opened opened =
                    new IdentityOrganization.Opened(
                            original.store(),
                            original.identities(),
                            original.forge(),
                            ignored ->
                                    new GitTreeWrites(
                                            runner,
                                            organization.repo.toString(),
                                            "acme",
                                            FetchAccess.NONE,
                                            machine.forge,
                                            new CommitSigning(Map.of(), files)));
            IdentityOrganization.Reading reading =
                    new IdentityOrganization.Reading(
                            TestOrganization.ADDRESS,
                            opened.store().read(machine.forge),
                            opened,
                            false);
            String card =
                    PersonCard.write(
                            "alice",
                            "Alice",
                            "",
                            machine.keys.get("alice"),
                            LocalDate.of(2026, 11, 1),
                            LocalDate.of(2026, 12, 1),
                            null);
            IdentityOperation operation =
                    new IdentityOperation(
                            "first",
                            IdentityOperation.CREATE,
                            machine.pin("alice"),
                            "",
                            card,
                            false,
                            null,
                            false,
                            false);
            new IdentityChange(machine.context())
                    .file(reading, operation, null, "unused-test-agent");
        }
    }

    @Test
    void refusedCreateNamesJoin() {
        assertThatThrownBy(this::refusedPush)
                .as("a person without write access can propose")
                .isInstanceOf(IdentityChange.Refused.class)
                .hasMessageContaining("porelo org join");
    }

    @Test
    void aFirstCardTheRepositoryRefusedToTakeIsKeptToJoin() throws Exception {
        try {
            refusedPush();
        } catch (IdentityChange.Refused expected) {
            /* The fake forge refuses every push. */
        }
        assertThat(keptBeforePush).as("kept only once the push is refused, not before").isFalse();
        assertThat(kept).as("join reads identity/card once main refused it").isRegularFile();
    }
}
