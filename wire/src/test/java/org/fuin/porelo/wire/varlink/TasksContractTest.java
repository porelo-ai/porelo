package org.fuin.porelo.wire.varlink;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The daemon's contract, carried by porelo-wire for a client outside porelo to read. */
class TasksContractTest {

    private static String read(final String name) throws IOException {
        try (InputStream in = TasksContractTest.class.getResourceAsStream(name)) {
            assertThat(in).as("%s on porelo-wire's class path", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void everyPartTheListNamesIsCarriedAndTheHeaderComesFirst() throws IOException {
        final List<String> parts =
                read("/varlink/org.fuin.porelo.Tasks1/parts")
                        .lines()
                        .map(String::strip)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .toList();

        assertThat(parts).first().isEqualTo("00-header");
        for (final String part : parts) {
            assertThat(read("/varlink/org.fuin.porelo.Tasks1/" + part + ".varlink"))
                    .as(part)
                    .isNotBlank();
        }
        assertThat(read("/varlink/org.fuin.porelo.Tasks1/00-header.varlink"))
                .contains("interface org.fuin.porelo.Tasks1");
    }
}
