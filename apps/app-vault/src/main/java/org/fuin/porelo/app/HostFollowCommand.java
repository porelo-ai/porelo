package org.fuin.porelo.app;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.wire.SystemProgram;
import picocli.CommandLine;

/** Asks the host's fixed root service to follow now, for the person invoking sudo. */
@CommandLine.Command(
        name = "follow",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Follow the host's organization now and wait for one pass.")
public final class HostFollowCommand implements Callable<Integer>, PoreloFactory.ContextAware {
    private PoreloContext context = PoreloContext.real();
    @CommandLine.Spec private CommandLine.Model.CommandSpec spec;
    UnaryOperator<Path> taken = SystemProgram::at;
    Function<String, Path> found = SystemProgram::find;

    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
    }

    /** Keeps the root wrapper's completion and refusal status intact. */
    @Override
    public Integer call() {
        final var err = spec.commandLine().getErr();
        final Path host;
        final Path sudo;
        try {
            host = taken.apply(Path.of("/usr/sbin/porelo-host"));
            sudo = found.apply("sudo");
        } catch (UncheckedIOException ex) {
            err.println(
                    "porelo: host follow is unavailable; install or repair the root-owned host program and sudo");
            return 69;
        }
        try {
            final var command =
                    Command.of(sudo.toString(), "-n", host.toString(), "org", "follow-now")
                            .in(Path.of("/"))
                            .withChosenEnvironment(
                                    Map.of("PATH", "/usr/sbin:/usr/bin", "LC_ALL", "C"));
            final var answer =
                    HostGatherRunner.system(context.runner(), command, Duration.ofMinutes(12));
            if (answer.exitCode() == 64) {
                err.println(
                        "porelo: host follow is unavailable; update the host program to one supporting org follow-now");
                return 69;
            }
            spec.commandLine().getOut().print(answer.standardOutput());
            err.print(answer.standardError());
            return answer.exitCode();
        } catch (CommandException | HostGatherRefusal ex) {
            err.println(
                    "porelo: host follow could not run; check this account's exact sudo rule for org follow-now");
            return 69;
        } finally {
            spec.commandLine().getOut().flush();
            err.flush();
        }
    }
}
