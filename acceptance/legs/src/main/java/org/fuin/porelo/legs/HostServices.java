package org.fuin.porelo.legs;

import java.io.Closeable;
import java.io.IOException;
import java.util.Map;
import org.fuin.porelo.machines.AgentLeg;

/** Prepares the host service only for the suite runner that uses it. */
final class HostServices {
    static final String SCRIPT = "acceptance/suite/src/test/host-services/listener.sh";

    @FunctionalInterface
    interface Root {
        void run(String command) throws IOException;
    }

    @FunctionalInterface
    interface Suite {
        void run(Map<String, String> environment) throws IOException;
    }

    static void around(Root root, Suite suite) throws IOException {
        final String command = "bash " + AgentLeg.quote("/home/build/porelo/" + SCRIPT);
        root.run(command + " up");
        try (Closeable prepared = () -> root.run(command + " down")) {
            suite.run(Map.of("PORELO_ACCEPTANCE_HOST_SERVICES", "1"));
        }
    }

    private HostServices() {}
}
