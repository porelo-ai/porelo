package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.project.Mail;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** Test for {@link TalkPeersCommand}: whom a project's tasks reach, including each other. */
class TalkPeersCommandTest {

    @Test
    void aProjectWithAConversationSaysItsTasksReachEachOther() {

        // It listed only the written peers, and "no peers - this project's tasks address nobody"
        // when there were none, while its tasks reached each other by name through the
        // conversation.
        assertThat(TalkPeersCommand.tasks(new Mail(List.of(), Map.of("matrix", Map.of()))))
                .contains("each other")
                .contains("matrix");
    }

    @Test
    void peersForDefaultAreRefusedNamingAProjectRepository(@TempDir Path dir) {
        // Answered as a standalone project, 'default' was pointed at a mail.transports it cannot
        // have.
        final FakeCommandRunner runner = new FakeCommandRunner();
        final PoreloContext context =
                new PoreloContext(
                        runner,
                        new PoreloPaths(
                                XdgPaths.of(
                                        name ->
                                                switch (name) {
                                                    case "XDG_CONFIG_HOME" ->
                                                            dir.resolve("config").toString();
                                                    case "XDG_DATA_HOME" ->
                                                            dir.resolve("data").toString();
                                                    case "XDG_STATE_HOME" ->
                                                            dir.resolve("state").toString();
                                                    case "XDG_RUNTIME_DIR" ->
                                                            dir.resolve("run").toString();
                                                    default -> null;
                                                },
                                        dir),
                                dir.resolve("bin")),
                        arguments -> 0);
        final StringWriter err = new StringWriter();
        final CommandLine cli = new CommandLine(new TalkCommand(), new PoreloFactory(context));
        cli.setOut(new PrintWriter(new StringWriter()));
        cli.setErr(new PrintWriter(err));

        assertThat(cli.execute("peers", "-p", DefaultProject.NAME)).isEqualTo(78);
        assertThat(err.toString()).contains("project repository", "project.yml");
        assertThat(runner.invocations()).as("no transport was run").isEmpty();
    }

    @Test
    void aStandaloneProjectSaysItsTasksDoNot() {
        assertThat(TalkPeersCommand.tasks(Mail.none())).contains("do not message each other");
    }

    @Test
    void listsThePeopleOfTheConversationAndEachMemberBesideTheFilesPeers() {

        // Found on 2026-10-04: 'people' and the room's members worked as addresses and were not
        // listed, so a person reading the list could not tell they were there.
        final List<Mail.Peer> listed =
                TalkPeersCommand.listed(
                        new Mail(
                                List.of(
                                        new Mail.Peer(
                                                "ops", "mail:ops@example.org", Mail.Peer.EXTERNAL)),
                                Map.of("matrix", Map.of())),
                        Map.of("alice", "@alice:localhost"));

        assertThat(listed).extracting(Mail.Peer::name).containsExactly("ops", Mail.PEOPLE, "alice");
        assertThat(listed.get(2).destination()).isEqualTo("@alice:localhost");
    }
}
