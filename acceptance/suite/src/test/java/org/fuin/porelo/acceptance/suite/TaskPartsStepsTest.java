package org.fuin.porelo.acceptance.suite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class TaskPartsStepsTest {

    /** An agent's name as a step takes it from its text; no agent of this repository. */
    private static final String AGENT = "some-agent";

    // Harm: a scenario's step without glue fails only on the machine, minutes into a run.
    @Test
    void everyStepOfThePartsFeatureHasItsGlue() {
        final byte exit =
                io.cucumber.core.cli.Main.run(
                        new String[] {
                            "--dry-run",
                            "--glue",
                            "org.fuin.porelo.acceptance",
                            "--plugin",
                            "summary",
                            "classpath:org/fuin/porelo/acceptance/task/task-parts.feature"
                        },
                        Thread.currentThread().getContextClassLoader());

        assertThat(exit)
                .as("a dry run of task-parts.feature finds an undefined or ambiguous step")
                .isZero();
    }

    // Harm: a start naming no right sent as an empty list, which holds no right instead of the
    // default set.
    @Test
    void aDaemonStartNamesRightsOnlyWhereTheStepNamesThem() {
        final Map<?, ?> unnamed =
                parameters(TaskPartsSteps.startRequest("author", "parted", AGENT, null));
        final Map<?, ?> named =
                parameters(
                        TaskPartsSteps.startRequest(
                                "starter", "parted", AGENT, List.of("push", "reply", "start")));

        assertThat(unnamed.containsKey("rights"))
                .as("a start naming no part names rights")
                .isFalse();
        assertThat(unnamed.get("repository")).as("its repository").isEqualTo("parted");
        assertThat(unnamed.get("agent")).as("the agent the step names").isEqualTo(AGENT);
        assertThat(named.get("rights"))
                .as("the rights named")
                .isEqualTo(List.of("push", "reply", "start"));
    }

    // Harm: a name from a step closing the quote it stands in and running what follows it.
    @Test
    void aNameThatIsMoreThanANameIsRefusedBeforeAnyCommandIsBuilt() {
        assertThatThrownBy(() -> TaskPartsSteps.startCommand("x'; rm -rf ~; '", "parted", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TaskPartsSteps.pushCommand("porelo-parted_author", "a b"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // Harm: an option of the scenario's run together with the next, or read by the shell.
    @Test
    void eachOptionOfAStartByHandIsOneWord() {
        assertThat(TaskPartsSteps.startCommand("replier", "partless", " --right  reply "))
                .endsWith(" --agent stub --detach --clearance deny '--right' 'reply'");
    }

    // Harm: the token's value written into the command line, where every process reads it.
    @Test
    void theEndpointIsReachedWithTheTaskOwnVariablesExpandedInsideItsContainer() {
        final String command = TaskPartsSteps.endpointCommand("porelo-parted_author");

        assertThat(command)
                .contains("podman exec -i --user agent 'porelo-parted_author' sh -c ")
                .contains("Bearer $PORELO_TOKEN_PORELO_MCP")
                .contains("\"$PORELO_URL_PORELO_MCP\"")
                .contains("--data-binary @-");
    }

    // Harm: arguments that are no object sent on, so a scenario's typo reads as Porelo's refusal.
    @Test
    void aCallCarriesTheArgumentsAsTheToolsObject() {
        final Map<?, ?> request =
                (Map<?, ?>)
                        Json.parse(TaskPartsSteps.callRequest("Pending", "{\"project\": \"p\"}"));
        final Map<?, ?> params = (Map<?, ?>) request.get("params");

        assertThat(request.get("method")).isEqualTo("tools/call");
        assertThat(params.get("name")).isEqualTo("Pending");
        assertThat(params.get("arguments")).isEqualTo(Map.of("project", "p"));
        assertThatThrownBy(() -> TaskPartsSteps.callRequest("Pending", "[1]"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // Harm: a method that takes the socket's own task sent the scenario's short name, which the
    // daemon answers NotItsOwn, so a scenario never shows what the method does for the task.
    @Test
    void theCallingTasksContainerStandsWhereTheArgumentsSayContainer() {
        final Map<?, ?> request =
                (Map<?, ?>)
                        Json.parse(
                                TaskPartsSteps.callRequest(
                                        "Report",
                                        TaskPartsSteps.withContainer(
                                                "{\"task\": \"{container}\", \"kind\": \"status\"}",
                                                "porelo-parted_reporter")));
        final Map<?, ?> unnamed =
                (Map<?, ?>)
                        Json.parse(
                                TaskPartsSteps.callRequest(
                                        "Start",
                                        TaskPartsSteps.withContainer(
                                                "{\"task\": \"unstarted\"}",
                                                "porelo-parted_reporter")));

        assertThat(((Map<?, ?>) request.get("params")).get("arguments"))
                .as("the arguments, the calling task's container in place of {container}")
                .isEqualTo(Map.of("task", "porelo-parted_reporter", "kind", "status"));
        assertThat(((Map<?, ?>) unnamed.get("params")).get("arguments"))
                .as("arguments that do not say {container}, as written")
                .isEqualTo(Map.of("task", "unstarted"));
        assertThatThrownBy(
                        () -> TaskPartsSteps.withContainer("{\"task\": \"{container}\"}", "a\"b"))
                .as("a container that is no name, refused before it goes into the JSON")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // Harm: a refusal by HTTP read as an answer without the tool, which passes a "not offered".
    @Test
    void anAnswerOtherThan200FailsTheStepAndA200IsReadAsItsBody() {
        final Map<?, ?> answer =
                TaskPartsSteps.answer(
                        "{\"result\":{\"tools\":[{\"name\":\"List\"},{\"name\":\"Report\"}]}}\n200\n");

        assertThat(TaskPartsSteps.tools(answer)).containsExactly("List", "Report");
        assertThatThrownBy(() -> TaskPartsSteps.answer("unauthorized\n401"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("401");
    }

    // Harm: a child a start made left running after the scenario, or a refusal taken for a child.
    @Test
    void theChildOfAStartIsTakenFromItsStructuredContentAlone() {
        final String made =
                "{\"result\":{\"structuredContent\":{\"container\":\"porelo-parted_fewer\","
                        + "\"exitCode\":0}}}\n200";
        final String refused =
                "{\"result\":{\"isError\":true,\"structuredContent\":"
                        + "{\"error\":\"org.fuin.porelo.Tasks1.StartRuleBroken\"}}}\n200";

        assertThat(TaskPartsSteps.startedChild("Start", made)).isEqualTo("porelo-parted_fewer");
        assertThat(TaskPartsSteps.startedChild("Start", refused)).isNull();
        assertThat(TaskPartsSteps.startedChild("Pending", made)).isNull();
        assertThat(TaskPartsSteps.startedChild("Start", "no answer\n502")).isNull();
    }

    // Harm: another task's row, or one of another project of the same name, judged in its place.
    @Test
    void theRowIsTheTaskOfItsProject() {
        final String said =
                "{\"parameters\":{\"tasks\":["
                        + "{\"project\":\"other\",\"task\":\"plain\",\"workspace\":\"none\"},"
                        + "{\"project\":\"partless\",\"task\":\"plain\",\"workspace\":\"own\"}]}}\n";

        assertThat(TaskPartsSteps.row(said, "partless", "plain").get("workspace")).isEqualTo("own");
        assertThatThrownBy(() -> TaskPartsSteps.row(said, "partless", "absent"))
                .isInstanceOf(AssertionError.class);
    }

    // Harm: rights compared in the order a set happened to list them, or an empty cell read as
    // anything.
    @Test
    void rightsAreComparedAsASetAndAnEmptyCellIsNoRight() {
        final Map<String, Object> row = new LinkedHashMap<>();
        row.put("workspace", "none");
        row.put("rights", List.of("reply", "push"));
        row.put("projectServices", Boolean.TRUE);
        final Map<String, @Nullable String> expected = new LinkedHashMap<>();
        expected.put("rights", "push, reply");
        expected.put("projectServices", "true");
        final Map<String, @Nullable String> none = new LinkedHashMap<>();
        none.put("rights", null);
        final Map<String, @Nullable String> unknown = new LinkedHashMap<>();
        unknown.put("role", "reviewer");

        assertThat(TaskPartsSteps.differences(row, expected)).isEmpty();
        assertThat(TaskPartsSteps.differences(row, none)).hasSize(1);
        assertThat(TaskPartsSteps.differences(Map.of("rights", List.of()), none)).isEmpty();
        assertThat(TaskPartsSteps.differences(row, unknown))
                .containsExactly("role: no member of a task's parts");
    }

    // Harm: a message the filter cannot read, so a refusal for its reader is never what is shown.
    @Test
    void aMessageIsTheGuidesShapeAddressingOneName() {
        final Map<?, ?> message =
                (Map<?, ?>) Json.parse(TaskPartsSteps.message("m-1", "outside", "it's here"));

        assertThat(message.get("messageId")).isEqualTo("m-1");
        assertThat(message.get("role")).isEqualTo("ROLE_AGENT");
        assertThat(message.get("parts")).isEqualTo(List.of(Map.of("text", "it's here")));
        assertThat(((Map<?, ?>) message.get("metadata")).get("to")).isEqualTo("outside");
        assertThat(TaskPartsSteps.handInCommand("porelo-sender_reader", "m-1"))
                .contains("/run/porelo/mail/outbox/tmp/m-1.json")
                .contains("/run/porelo/mail/outbox/new/");
    }

    // Harm: a transport of the account's own overwritten, then removed by the scenario's cleanup.
    @Test
    void theTransportIsInstalledOnlyWhereNoneIsAndDescribesItsScheme() {
        assertThat(TaskPartsSteps.transportCommand("parked"))
                .contains("porelo-message-transport-parked")
                .contains("if [ -e \"$f\" ]; then")
                .contains("exit 1");
        assertThat(TaskPartsSteps.transportScript("parked"))
                .contains("\"scheme\": \"parked\"")
                .contains("sys.exit(75)");
    }

    private static Map<?, ?> parameters(final String request) {
        return (Map<?, ?>) ((Map<?, ?>) Json.parse(request)).get("parameters");
    }
}
