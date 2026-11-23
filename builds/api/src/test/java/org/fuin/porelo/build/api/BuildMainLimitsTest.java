package org.fuin.porelo.build.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.fuin.porelo.wire.LimitFactors;
import org.junit.jupiter.api.Test;

/** Tests for how a build reader takes {@code --limits} before its mode. */
class BuildMainLimitsTest {

    private final List<LimitFactors> handed = new ArrayList<>();

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(final String... arguments) {
        final BuildReader reader = new FakeReader();
        return BuildMain.execute(
                new BuildReader() {
                    @Override
                    public String forge() {
                        return reader.forge();
                    }

                    @Override
                    public String head(final Target target, final String branch) {
                        return reader.head(target, branch);
                    }

                    @Override
                    public Build build(
                            final Target target, final String commit, final Build.Logs logs) {
                        return reader.build(target, commit, logs);
                    }

                    @Override
                    public BuildReader withLimits(final LimitFactors limits) {
                        handed.add(limits);
                        return this;
                    }
                },
                arguments,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @Test
    void theLineBeforeTheModeReachesTheReaderAndABadOneEndsWithSixtyFour() {
        assertThat(run("--limits", "machines=3", "describe")).isZero();
        assertThat(handed).containsExactly(LimitFactors.parse("machines=3.0"));
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("\"forge\"");

        assertThat(run("--limits", "machines=3,machines=4", "describe")).isEqualTo(64);
        assertThat(err.toString(StandardCharsets.UTF_8))
                .startsWith("--limits: machines is given twice");
        assertThat(handed).as("a refused line serves nothing").hasSize(1);
    }
}
