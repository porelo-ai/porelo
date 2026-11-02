package org.fuin.porelo.ecosystem.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.fuin.porelo.wire.Json;
import org.junit.jupiter.api.Test;

class EcosystemMainTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(final String... arguments) {
        return EcosystemMain.execute(
                new FakeEcosystem(),
                arguments,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @Test
    void describesItselfAsJson() {
        assertThat(run("describe")).isZero();

        final Object said = Json.parse(out.toString(StandardCharsets.UTF_8));
        assertThat(said)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("ecosystem", "fake");
        assertThat(((Number) ((Map<?, ?>) said).get("protocolVersion")).intValue())
                .isEqualTo(EcosystemProtocol.VERSION);
    }

    @Test
    void refusesAWrongCommandLine() {
        assertThat(run("serve")).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("usage: fake serve <socket>");

        assertThat(run("build")).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("unknown mode 'build'");
    }
}
