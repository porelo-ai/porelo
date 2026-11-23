package org.fuin.porelo.agent.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.wire.LimitFactors;
import org.junit.jupiter.api.Test;

/** Tests for how an agent program takes {@code --limits} before its mode. */
class AgentMainLimitsTest {

    private static final AgentDefinition DEFINITION =
            AgentDefinitionReader.read(
                    new StringReader(
                            """
                            name: example
                            binary: example-cli

                            git_identity:
                              name: Example
                              email: noreply@example.com

                            headless:
                              prompt_flag: "-p"
                            """),
                    "test.yaml");

    private final List<LimitFactors> handed = new ArrayList<>();

    private final Agent agent =
            new Agent() {
                @Override
                public AgentDefinition definition() {
                    return DEFINITION;
                }

                @Override
                public Agent withLimits(LimitFactors limits) {
                    handed.add(limits);
                    return this;
                }
            };

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... arguments) {
        return AgentMain.execute(
                agent,
                arguments,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @Test
    void theLineBeforeTheModeReachesTheAgentOnceAndTheModeStillRuns() {
        assertThat(run("--limits", "services=2.0,retries=3", "describe")).isZero();

        assertThat(handed)
                .as("the factors Porelo named, handed to the agent before it serves")
                .containsExactly(LimitFactors.parse("services=2.0,retries=3.0"));
        assertThat(out.toString(StandardCharsets.UTF_8))
                .as("the mode after the line runs as without it")
                .contains("\"protocolVersion\"");
    }

    @Test
    void withoutTheLineAProgramRunByHandTakesAllFourAtOne() {
        assertThat(run("describe")).isZero();
        assertThat(run()).as("no mode is describe, as before").isZero();

        assertThat(handed).containsExactly(LimitFactors.DEFAULT, LimitFactors.DEFAULT);
    }

    @Test
    void aBadLineEndsWithSixtyFourNamingWhatIsWrongBeforeAnythingRuns() {
        assertThat(run("--limits", "timeouts=2.0", "describe")).isEqualTo(64);
        assertThat(err.toString(StandardCharsets.UTF_8))
                .startsWith("--limits: 'timeouts' is no limit");

        assertThat(run("--limits")).as("the option without its line").isEqualTo(64);

        assertThat(handed).as("the agent is never served on a refused line").isEmpty();
        assertThat(out.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void serveAfterTheLineStillWantsItsSocket() {
        assertThat(run("--limits", "polling=0.5", "serve")).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("serve <socket>");
    }
}
