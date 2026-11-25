package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.wire.varlink.VarlinkClient;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The held items' and forge instances' methods, described and registered before they are built. */
class GateUnbuiltMethodsTest {

    @Test
    void eachUnbuiltMethodIsDescribedAndAnswersThatItIsNotImplemented(@TempDir Path dir)
            throws Exception {
        try (VarlinkServer server =
                new VarlinkServer(dir.resolve("o.sock"), PoreloDaemon.INTERFACE)) {
            GateMethods.registerUnbuilt(server);
            Thread.ofVirtual().start(server);
            try (VarlinkClient client = new VarlinkClient(server.socketPath())) {
                for (final String method :
                        List.of("HeldItems", "DecideItem", "Forges", "ForgeSet")) {
                    assertThat(PoreloDaemon.description())
                            .as(method + " is described")
                            .containsPattern("(?m)^method " + method + "\\(");
                    final String name = PoreloDaemon.INTERFACE + "." + method;
                    assertThatThrownBy(() -> client.call(name, Map.of()))
                            .as(method + " answers that it is not built yet")
                            .isInstanceOfSatisfying(
                                    VarlinkException.class,
                                    refused -> {
                                        assertThat(refused.getErrorName())
                                                .as("the error")
                                                .isEqualTo(
                                                        "org.varlink.service.MethodNotImplemented");
                                        assertThat(refused.getParameters())
                                                .as("names the method")
                                                .containsEntry("method", name);
                                    });
                }
            }
        }
    }
}
