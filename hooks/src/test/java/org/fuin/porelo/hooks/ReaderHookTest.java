package org.fuin.porelo.hooks;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.fuin.porelo.wire.LimitFactors;
import org.fuin.porelo.wire.Sidecar;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ReaderHook}: the reader it starts runs under the factors the task started with.
 */
class ReaderHookTest {

    @Test
    void theReaderIsHandedTheSidecarsLimitsLineBeforeItsVerb() {
        final Sidecar sidecar =
                new Sidecar(
                        Sidecar.VERSION,
                        "uc",
                        "guarded",
                        "/run/ruleset.nft",
                        "/run/dns.conf",
                        "/usr/bin/porelo",
                        "/run/state",
                        null,
                        null,
                        null,
                        LimitFactors.parse("machines=2.5"));

        assertThat(
                        ReaderHook.readerCommand(
                                4711,
                                Path.of("/usr/bin/porelo"),
                                sidecar,
                                Path.of("/usr/bin/nsenter")))
                .containsExactly(
                        "/usr/bin/nsenter",
                        "--target",
                        "4711",
                        "--net",
                        "/usr/bin/porelo",
                        "--limits",
                        "services=1.0,machines=2.5,polling=1.0,retries=1.0",
                        "shield",
                        "read");
    }
}
