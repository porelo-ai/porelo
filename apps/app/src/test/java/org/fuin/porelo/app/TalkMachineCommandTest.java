package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import org.fuin.porelo.core.config.XdgPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** What {@code porelo talk machine} prints, which a project admin lists to let an account in. */
class TalkMachineCommandTest {

    @TempDir Path dir;

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private int execute(final PoreloContext context) {
        // The whole command line, so a verb left out of 'talk' is caught too.
        final CommandLine command = PoreloCli.commandLine(context);
        command.setOut(new PrintWriter(out, true));
        command.setErr(new PrintWriter(err, true));
        return command.execute("talk", "machine");
    }

    private PoreloContext context() {
        return new PoreloContext(
                (org.fuin.porelo.core.process.CommandRunner)
                        command -> {
                            throw new AssertionError("nothing is run: " + command);
                        },
                new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                arguments -> 0);
    }

    @Test
    void printsTheNameATransportIsGiven() {
        // A name listed that differs from the one passed would leave the account out of the room.
        assertThat(execute(context())).as("exit").isZero();
        assertThat(out.toString().strip())
                .as("the very name the transport gets as --machine")
                .isEqualTo(PoreloContext.TEST_MACHINE);
    }

    @Test
    void anAccountThatMakesNoMachineNameExitsAsAConfigurationFault() {
        // A play reading exit 0 would list an empty name.
        final int code =
                execute(
                        context()
                                .namedAs(
                                        () -> {
                                            throw new IllegalArgumentException(
                                                    "'build-1-al ice' is not a machine's name");
                                        }));

        assertThat(code).as("78, what the transport verbs refuse such a name with").isEqualTo(78);
        assertThat(out.toString()).as("no name printed").isEmpty();
        assertThat(err.toString()).as("the refusal names it").contains("al ice");
    }
}
