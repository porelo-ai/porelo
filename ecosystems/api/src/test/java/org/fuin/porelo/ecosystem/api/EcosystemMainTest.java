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
    void describesItsOwnVersionBesideTheContractsVersion() {
        assertThat(run("describe")).isZero();

        assertThat(Json.parse(out.toString(StandardCharsets.UTF_8)))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .as("a person sees which release of an adapter answers")
                .containsEntry("version", FakeEcosystem.VERSION);
    }

    @Test
    void leavesTheVersionOutWhenThePluginSaysNone() {
        final Ecosystem unversioned =
                new Ecosystem() {
                    @Override
                    public String ecosystem() {
                        return "plain";
                    }

                    @Override
                    public Detection detect(
                            final java.nio.file.Path directory, final java.nio.file.Path home) {
                        return new Detection(java.util.List.of(), java.util.List.of());
                    }

                    @Override
                    public java.util.List<File> configure(
                            final java.util.List<Repository> repositories) {
                        return java.util.List.of();
                    }
                };

        assertThat(
                        EcosystemMain.execute(
                                unversioned,
                                new String[] {"describe"},
                                new PrintStream(out, true, StandardCharsets.UTF_8),
                                new PrintStream(err, true, StandardCharsets.UTF_8)))
                .isZero();
        assertThat(Json.parse(out.toString(StandardCharsets.UTF_8)))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .as("none is absent, never an empty version a reader would show")
                .doesNotContainKey("version");
    }

    @Test
    void refusesAWrongCommandLine() {
        assertThat(run("serve")).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("usage: fake serve <socket>");

        assertThat(run("build")).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("unknown mode 'build'");
    }
}
