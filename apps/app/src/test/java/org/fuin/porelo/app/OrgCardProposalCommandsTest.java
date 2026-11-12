package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.process.ProcessCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * The proposal commands are available through the real command tree and fail before a forge call
 * without a card.
 */
class OrgCardProposalCommandsTest {
    @TempDir Path dir;

    PoreloContext context() {
        XdgPaths xdg = XdgPaths.of(name -> null, dir);
        return new PoreloContext(
                new ProcessCommandRunner(),
                new PoreloPaths(xdg, dir.resolve("bin")),
                args -> 0,
                name -> null);
    }

    @Test
    void joinNamesIdentityCreateWhenNoCardIsKept() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine line =
                PoreloCli.commandLine(context())
                        .setOut(new PrintWriter(out))
                        .setErr(new PrintWriter(err));
        assertThat(line.execute("org", "join", "--json"))
                .as("missing card is a named refusal")
                .isEqualTo(65);
        assertThat(err.toString()).as("available next action").contains("porelo identity create");
        assertThat(out.toString()).as("JSON refusal").contains("no-card");
    }

    @Test
    void allPersonProposalVerbsAreRegistered() {
        CommandLine person =
                PoreloCli.commandLine(context())
                        .getSubcommands()
                        .get("org")
                        .getSubcommands()
                        .get("person");
        assertThat(person.getSubcommands())
                .as("proposal CLI is assembled in the organization area")
                .containsKeys("proposals", "accept", "decline");
    }

    @Test
    void anOrganizationThatCannotBeReachedIsOneThatCannotSay() {
        StringWriter out = new StringWriter();
        CommandLine line =
                PoreloCli.commandLine(context())
                        .setOut(new PrintWriter(out))
                        .setErr(new PrintWriter(new StringWriter()));
        unreachable(line);
        assertThat(line.execute("org", "person", "proposals", "--json"))
                .as("unreached, not failed")
                .isEqualTo(69);
        assertThat(out.toString())
                .as("JSON refusal, naming the step that failed")
                .contains("cannot-say")
                .contains("cannot fetch the organization repository")
                .contains("a change may have been made: ask again");
    }

    private static void unreachable(CommandLine line) {
        if (line.getCommand() instanceof OrgProposing command) {
            command.proposals =
                    ignored -> {
                        throw new org.fuin.porelo.organization.gittree.GitTreeStore.Unreachable(
                                "cannot fetch the organization repository");
                    };
        }
        line.getSubcommands().values().forEach(OrgCardProposalCommandsTest::unreachable);
    }

    private static void wire(
            CommandLine line,
            org.fuin.porelo.organization.gittree.CardProposals service,
            RegistryCheckout machine,
            String[] actor) {
        if (line.getCommand() instanceof OrgProposing command) {
            command.proposals = ignored -> service;
            command.actor =
                    ignored ->
                            new org.fuin.porelo.organization.OrganizationWrites.Acting(
                                    actor[0], machine.key(actor[0]));
        }
        line.getSubcommands().values().forEach(child -> wire(child, service, machine, actor));
    }

    @Test
    void keptCardAndEveryProposalVerbRoundTripThroughTheCommandTreeAsJson() throws Exception {
        try (RegistryCheckout machine = new RegistryCheckout(dir)) {
            TestOrganization organization = new TestOrganization(machine);
            organization.filed("michael");
            machine.forge.keys.put("alice", java.util.List.of(machine.key("alice")));
            new IdentityStore(machine.context()).keep(machine.card("alice", "alice"));
            final boolean[] mayPushMain = {false};
            org.fuin.porelo.core.process.CommandRunner runner =
                    command -> {
                        if (!mayPushMain[0]
                                && command.arguments().contains("push")
                                && command.arguments().getLast().endsWith(":refs/heads/main")) {
                            return new org.fuin.porelo.core.process.CommandResult(
                                    command, 1, "", "refused");
                        }
                        return machine.runner.run(command);
                    };
            var forge =
                    new org.fuin.porelo.organization.gittree.CardProposals.Forge() {
                        boolean open;

                        public String login() {
                            return "alice";
                        }

                        public java.util.List<
                                        org.fuin.porelo.organization.gittree.CardProposals.Pull>
                                list() {
                            return open ? java.util.List.of(read(1)) : java.util.List.of();
                        }

                        public org.fuin.porelo.organization.gittree.CardProposals.Pull read(
                                long number) {
                            return new org.fuin.porelo.organization.gittree.CardProposals.Pull(
                                    1,
                                    "https://forge.example/acme/organization/pulls/1",
                                    "alice",
                                    "porelo-card-alice",
                                    "main",
                                    organization.git("rev-parse", "refs/pull/1/head"),
                                    "2026-11-12T12:00:00Z",
                                    open,
                                    false,
                                    "acme");
                        }

                        public String fork(String login) {
                            throw new AssertionError("a branch is enough");
                        }

                        public org.fuin.porelo.organization.gittree.CardProposals.Pull open(
                                String login, boolean fork) {
                            open = true;
                            organization.git(
                                    "update-ref",
                                    "refs/pull/1/head",
                                    organization.git("rev-parse", "refs/heads/porelo-card-alice"));
                            return read(1);
                        }

                        public void close(long number, String comment) {
                            open = false;
                        }
                    };
            java.util.Map<String, Path> files = new java.util.HashMap<>();
            machine.keyFiles.forEach((login, file) -> files.put(machine.key(login), file));
            var service =
                    new org.fuin.porelo.organization.gittree.CardProposals(
                            runner,
                            organization.repo.toString(),
                            "acme",
                            org.fuin.porelo.organization.gittree.FetchAccess.NONE,
                            machine.forge,
                            new org.fuin.porelo.organization.gittree.CommitSigning(
                                    java.util.Map.of(), files),
                            forge,
                            java.time.Clock.fixed(
                                    java.time.Instant.parse("2026-11-12T12:00:00Z"),
                                    java.time.ZoneOffset.UTC));
            StringWriter out = new StringWriter();
            StringWriter err = new StringWriter();
            CommandLine line =
                    PoreloCli.commandLine(machine.context())
                            .setOut(new PrintWriter(out))
                            .setErr(new PrintWriter(err));
            String[] actor = {"alice"};
            wire(line, service, machine, actor);
            assertThat(line.execute("org", "join", "--json"))
                    .as("join uses the kept card")
                    .isZero();
            assertThat(out.toString())
                    .as("join JSON shape")
                    .contains("\"state\":\"proposed\"", "\"pullRequest\":\"https://");
            out.getBuffer().setLength(0);
            assertThat(line.execute("org", "person", "proposals", "--json"))
                    .as("proposals command")
                    .isZero();
            assertThat(out.toString())
                    .as("judged proposal JSON shape")
                    .contains(
                            "\"proposals\":[",
                            "\"login\":\"alice\"",
                            "\"state\":\"ready\"",
                            "\"fingerprint\":",
                            "\"opened\":");
            out.getBuffer().setLength(0);
            OrgJoinCommand join =
                    (OrgJoinCommand)
                            line.getSubcommands()
                                    .get("org")
                                    .getSubcommands()
                                    .get("join")
                                    .getCommand();
            var signingActor = join.actor;
            join.actor =
                    ignored -> {
                        throw new AssertionError("withdrawal needs no agent signature");
                    };
            assertThat(line.execute("org", "join", "--withdraw", "--json"))
                    .as("own withdrawal")
                    .isZero();
            assertThat(out.toString()).as("withdraw JSON").contains("withdrawn");
            join.actor = signingActor;
            assertThat(line.execute("org", "join", "--json")).as("join after withdrawal").isZero();
            actor[0] = "michael";
            out.getBuffer().setLength(0);
            assertThat(line.execute("org", "person", "decline", "alice", "--json"))
                    .as("admin decline command")
                    .isZero();
            assertThat(out.toString()).as("decline JSON").contains("declined");
            actor[0] = "alice";
            assertThat(line.execute("org", "join", "--json")).as("join after decline").isZero();
            actor[0] = "michael";
            mayPushMain[0] = true;
            out.getBuffer().setLength(0);
            assertThat(line.execute("org", "person", "accept", "alice", "--json"))
                    .as("admin accept command: %s", err)
                    .isZero();
            assertThat(out.toString()).as("accept JSON").contains("accepted");
        }
    }
}
