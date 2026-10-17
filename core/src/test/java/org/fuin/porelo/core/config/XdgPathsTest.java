package org.fuin.porelo.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Tests for {@link XdgPaths}. */
class XdgPathsTest {

    private static final Path HOME = Path.of("/home/tester");

    private static XdgPaths paths(Map<String, String> env) {
        return XdgPaths.of(env::get, HOME);
    }

    @Test
    void fallsBackToTheDefaultsWhenNothingIsSet() {

        final XdgPaths paths = paths(Map.of());

        assertThat(paths.config()).isEqualTo(Path.of("/home/tester/.config/porelo"));
        assertThat(paths.data()).isEqualTo(Path.of("/home/tester/.local/share/porelo"));
        assertThat(paths.state()).isEqualTo(Path.of("/home/tester/.local/state/porelo"));
    }

    @Test
    void usesTheEnvironmentWhenItIsSet() {

        final XdgPaths paths =
                paths(
                        Map.of(
                                "XDG_CONFIG_HOME", "/elsewhere/config",
                                "XDG_RUNTIME_DIR", "/run/user/4242"));

        assertThat(paths.config()).isEqualTo(Path.of("/elsewhere/config/porelo"));
        assertThat(paths.runtime()).isEqualTo(Path.of("/run/user/4242/porelo"));
    }

    @Test
    void ignoresBlankAndRelativeValues() {

        // The XDG specification says a relative value must be ignored rather than resolved
        // against the working directory.
        final XdgPaths paths =
                paths(
                        Map.of(
                                "XDG_CONFIG_HOME", "  ",
                                "XDG_DATA_HOME", "relative/path"));

        assertThat(paths.config()).isEqualTo(Path.of("/home/tester/.config/porelo"));
        assertThat(paths.data()).isEqualTo(Path.of("/home/tester/.local/share/porelo"));
    }

    @Test
    void resolvesTheRealEnvironmentWithoutFailing() {

        final XdgPaths paths = XdgPaths.current();

        assertThat(paths.config()).isAbsolute();
        assertThat(paths.runtime()).isAbsolute();
    }
}
