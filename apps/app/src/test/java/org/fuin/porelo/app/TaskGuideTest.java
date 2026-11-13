package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.fuin.porelo.agent.api.AgentDefinition;
import org.fuin.porelo.agent.api.AgentDefinitionReader;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.project.Mail;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Test for {@link TaskGuide} and {@link TaskGuideText}. */
class TaskGuideTest {

    private static final String TASK = "porelo-p-writer";

    private static final AgentDefinition CLAUDE =
            AgentDefinitionReader.read(
                    new StringReader(
                            """
            name: claude
            binary: claude
            git_identity: { name: C, email: c@example.com }
            headless: { prompt_flag: "-p" }
            instructions: { arguments: ["--append-system-prompt-file", "{file}"] }
            """),
                    "claude.yaml");

    @TempDir Path dir;

    private PoreloPaths paths() {
        return new PoreloPaths(
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir),
                dir.resolve("bin"));
    }

    @Test
    void tellsEveryAgentOfItsFilesAndTheBuildsOfItsPushes() {

        // An agent pushed and never looked at its red build, since nothing told it of
        // /porelo/files.
        assertThat(TaskGuideText.text(false))
                .contains(org.fuin.porelo.runtime.Containerfile.HAND_IN_FILES)
                .contains("build-<commit>.txt")
                .contains("build-<commit>-<n>.log")
                .contains("verdict")
                .contains("git push porelo")
                .contains("$PORELO_TASK_REF")
                .contains("name no branch")
                .contains("git fetch porelo")
                .contains("failure")
                .contains("whenever you come to rest")
                .contains("When your work is done, commit it and push it with `git push porelo`")
                .doesNotContain(Mailbox.MOUNT);
        assertThat(TaskGuideText.text(true))
                .as("the mailbox's part where there is one")
                .startsWith(TaskGuideText.text(false))
                .contains(MailboxGuide.text());
    }

    @Test
    void namesNoneAsFinalSoAPushWithNothingToBuildIsDone() {

        // An agent waited 30 minutes for a build of work held at the gate, since the guide had it
        // read the file until its verdict was final and never said which verdict says none comes.
        final String text = TaskGuideText.text(false).replaceAll("\\s+", " ");

        assertThat(text)
                .contains("The final verdicts are `success`, `failure`, `cancelled` and `none`.")
                .contains(
                        "With `none` no build comes while this task runs, and your work is done"
                                + " once it is pushed")
                .contains("held at the gate for a person")
                .contains("follows no builds");
    }

    @Test
    void doesNotCallUnknownFinalSinceTheWatchWritesItWhileABuildMayStillCome() {

        // The build watch writes unknown for a rate limit, an unreachable forge or a build not
        // shown yet, and replaces it later: an agent that stopped there never read the failure.
        final String text = TaskGuideText.text(false).replaceAll("\\s+", " ");
        final int start = text.indexOf("The final verdicts are");
        final String finals = text.substring(start, text.indexOf('.', start));

        assertThat(finals).as("the final verdicts").doesNotContain("unknown");
        assertThat(text).contains("`unknown` is not final");
    }

    @Test
    void isTheSameForEveryTaskSoAProvidersCacheOfThePromptHolds() {
        assertThat(TaskGuideText.text(true))
                .isEqualTo(TaskGuideText.text(true))
                .doesNotContain("porelo-");
    }

    @Test
    void aTaskThatReadsItsWholeProjectIsToldItPushesNothing() {
        final String text =
                TaskGuideText.text(true, java.util.Map.of(), List.of("back", "front", "shop"));

        assertThat(text)
                .as("after what every task shares, so a provider's cache of it still holds")
                .startsWith(TaskGuideText.text(true))
                .contains("`/workspace/back/`", "`/workspace/front/`", "`/workspace/shop/`")
                .contains("read only")
                .contains("Nothing in `/workspace` is pushed");
        assertThat(TaskGuideText.text(true, java.util.Map.of(), null))
                .as("a task on one repository is told nothing of it")
                .isEqualTo(TaskGuideText.text(true));
    }

    @Test
    void aTaskWhoseEndpointCarriesProposeIdeaIsToldHowToProposeOneWithinItsBound() {

        // An agent of a task with an endpoint carrying ProposeIdea and limits.ideas 1 wrote its
        // idea as mail: nothing told it the endpoint existed.
        final String text =
                TaskGuideText.text(
                        false,
                        java.util.Map.of(),
                        null,
                        null,
                        new TaskGuideText.Endpoint(TASK, List.of("List", "ProposeIdea"), 1));
        final String flat = text.replaceAll("\\s+", " ");

        assertThat(text)
                .as("after what every task shares, so a provider's cache of it still holds")
                .startsWith(TaskGuideText.text(false))
                .contains("$PORELO_URL_PORELO_MCP")
                .contains("Authorization: Bearer $PORELO_TOKEN_PORELO_MCP")
                .contains("`initialize`", "`tools/list`", "`tools/call`")
                .contains("- `List`\n", "- `ProposeIdea`\n")
                .contains("`" + TASK + "`")
                .contains("\"name\":\"ProposeIdea\"")
                .contains("NotItsOwn", "NotAllowed", "InvalidParameter");
        assertThat(flat)
                .contains("exactly as it stands")
                .contains("at most **1** idea")
                .contains("at most 120 characters")
                .contains("at most 1000 characters")
                .contains("A person takes it");
        // The worked call is copied into a shell as it stands: its body one single-quoted word
        // holding one JSON-RPC message that names this task.
        final int body = text.lastIndexOf("-d '") + 4;
        final String call = text.substring(body, text.indexOf("'\n```", body));
        assertThat(call).doesNotContain("'");
        assertThat(org.fuin.porelo.wire.Json.parse(call))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("method", "tools/call")
                .extractingByKey("params")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("name", "ProposeIdea")
                .extractingByKey("arguments")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("task", TASK)
                .containsOnlyKeys("task", "title", "whatMustBeTrue");
        assertThat(
                        TaskGuideText.text(
                                false,
                                java.util.Map.of(),
                                null,
                                null,
                                new TaskGuideText.Endpoint(TASK, List.of("ProposeIdea"), 3)))
                .as("the bound as the project sets it")
                .contains("at most **3** ideas");
        assertThat(text)
                .as("no token value, only its variable")
                .doesNotContainPattern("[0-9a-f]{32}")
                .doesNotContain("$PORELO_URL_PORELO_MCP/mcp");
    }

    @Test
    void aTaskWhoseEndpointCarriesNoProposeIdeaIsToldOfTheEndpointButNoIdeas() {
        final String text =
                TaskGuideText.text(
                        false,
                        java.util.Map.of(),
                        null,
                        null,
                        new TaskGuideText.Endpoint(TASK, List.of("List", "Stop"), 1));

        assertThat(text)
                .contains("$PORELO_URL_PORELO_MCP", "$PORELO_TOKEN_PORELO_MCP")
                .contains("- `List`\n", "- `Stop`\n")
                .doesNotContain("ProposeIdea")
                .doesNotContain("idea")
                .doesNotContainPattern("[0-9a-f]{32}");
        assertThat(
                        TaskGuideText.text(
                                false,
                                java.util.Map.of(),
                                null,
                                null,
                                new TaskGuideText.Endpoint(
                                        TASK, List.of("List", "ProposeIdea"), 0)))
                .as("one whose project allows no idea is told of none")
                .contains("$PORELO_URL_PORELO_MCP", "- `ProposeIdea`\n")
                .doesNotContain("\"name\":\"ProposeIdea\"")
                .doesNotContain("idea-1");
    }

    @Test
    void aTaskWithoutAnEndpointIsToldOfNone() {
        final String text = TaskGuideText.text(true, java.util.Map.of(), null, null, null);

        assertThat(text)
                .isEqualTo(TaskGuideText.text(true))
                .doesNotContain("PORELO_URL_PORELO_MCP")
                .doesNotContain("PORELO_TOKEN_PORELO_MCP")
                .doesNotContain("ProposeIdea")
                .doesNotContain("tools/call");
    }

    @Test
    void theEndpointsPartIsWrittenWithTheGuideAndNoTokenWithIt() throws IOException {
        final TaskGuide guide = new TaskGuide(paths(), TASK);
        final String token = McpWiring.token();
        final TaskGuideText.Endpoint endpoint =
                new TaskGuideText.Endpoint(TASK, List.of("ProposeIdea"), 1);

        TaskGuideText.write(guide, false, java.util.Map.of(), null, null, endpoint);

        assertThat(guide.file())
                .hasContent(TaskGuideText.text(false, java.util.Map.of(), null, null, endpoint));
        assertThat(Files.readString(guide.file()))
                .contains("$PORELO_TOKEN_PORELO_MCP")
                .doesNotContain(token);
    }

    @Test
    void isWrittenWhereTheTaskReadsItAndGivenToTheAgentFromThere() throws IOException {
        final TaskGuide guide = new TaskGuide(paths(), TASK);
        assertThat(guide.instructing(CLAUDE, List.of("claude")))
                .as("neither guide")
                .containsExactly("claude");

        TaskGuideText.write(guide, false);

        assertThat(guide.file()).hasContent(TaskGuideText.text(false));
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(guide.file())))
                .isEqualTo("rw-r--r--");
        assertThat(guide.directory())
                .as("in the state directory, so a reboot keeps what is mounted")
                .startsWith(dir.resolve("state"));
        assertThat(guide.instructing(CLAUDE, List.of("claude")))
                .containsExactly("claude", "--append-system-prompt-file", TaskGuide.GUIDE);
    }

    @Test
    void aTaskMadeBeforeTheGuideKeepsItsMailboxsGuide() throws IOException {
        final PoreloPaths paths = paths();
        final Mailbox mailbox = new Mailbox(paths.messaging().mailbox(TASK));
        mailbox.create();
        MailboxGuide.write(mailbox, Mail.none());

        assertThat(new TaskGuide(paths, TASK).instructing(CLAUDE, List.of("claude")))
                .as("its container has no mount for the guide")
                .containsExactly("claude", "--append-system-prompt-file", Mailbox.GUIDE);
    }

    @Test
    @Tag("documents")
    void theUserDocumentationShowsTheTextAgentsAreGivenWordForWord() throws IOException {
        final String documented = Files.readString(Path.of("..", "..", "doc", "running.md"));

        for (final String line : TaskGuideText.text(false).split("\n")) {
            assertThat(documented)
                    .as("doc/running.md")
                    .contains(line.startsWith("#") ? "#" + line : line);
        }
    }

    @Test
    @Tag("documents")
    void theUserDocumentationShowsTheEndpointsPartWordForWord() throws IOException {
        final String documented = Files.readString(Path.of("..", "..", "doc", "running.md"));
        final String before = TaskGuideText.text(false);
        final String part =
                TaskGuideText.text(
                                false,
                                java.util.Map.of(),
                                null,
                                null,
                                new TaskGuideText.Endpoint(
                                        "porelo-shop-fix",
                                        List.of("List", "Done", "ProposeIdea"),
                                        2))
                        .substring(before.length());

        for (final String line : part.split("\n")) {
            assertThat(documented)
                    .as("doc/running.md")
                    .contains(line.startsWith("#") ? "##" + line : line);
        }
    }
}
