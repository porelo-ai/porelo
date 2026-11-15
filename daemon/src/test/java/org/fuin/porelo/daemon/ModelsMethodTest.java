package org.fuin.porelo.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.app.ModelLists;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.PoreloPaths;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A window's way to the models a provider offers this machine. */
class ModelsMethodTest {

    @TempDir Path dir;

    private PoreloContext context() {
        final XdgPaths xdg =
                XdgPaths.of(
                        name ->
                                switch (name) {
                                    case "XDG_STATE_HOME" -> dir.resolve("state").toString();
                                    case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                    case "XDG_CONFIG_HOME" -> dir.resolve("config").toString();
                                    case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                    default -> null;
                                },
                        dir);
        return new PoreloContext(
                new FakeCommandRunner(), new PoreloPaths(xdg, dir.resolve("bin")), args -> 0);
    }

    @Test
    void aProviderTheMachineDoesNotDeclareIsModelsFailedNamingIt() {
        assertThatThrownBy(
                        () ->
                                AgentMethods.models(
                                        context(), new ModelLists(), Map.of("provider", "nowhere")))
                .isInstanceOfSatisfying(
                        VarlinkException.class,
                        failure -> {
                            assertThat(failure.getErrorName())
                                    .as("the contract's named error, never an empty list")
                                    .isEqualTo(PoreloDaemon.INTERFACE + ".ModelsFailed");
                            assertThat(failure.getParameters())
                                    .as("with the provider asked for")
                                    .containsEntry("provider", "nowhere");
                            assertThat(String.valueOf(failure.getParameters().get("message")))
                                    .as("and where the known ones are listed")
                                    .contains("porelo providers");
                        });
    }
}
