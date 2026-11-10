package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;
import org.fuin.porelo.wire.RootOwnedFile;
import org.fuin.porelo.wire.SystemProgram;
import picocli.CommandLine;

/** Vault and system boundaries shared by the person's push and pull. */
abstract class HostGatherCommand implements Callable<Integer>, PoreloFactory.ContextAware {
    @CommandLine.Parameters(index = "0", paramLabel = "<project>")
    String project;

    @CommandLine.Parameters(index = "1..*", paramLabel = "<repository>")
    List<String> repositories = List.of();

    @CommandLine.Spec CommandLine.Model.CommandSpec spec;
    PoreloContext context = PoreloContext.real();
    Path forgeConf = HostForgeTokenCommand.FORGE_CONF;
    RootOwnedFile.Attributes attributes = HostCentral.ATTRIBUTES;
    UnaryOperator<Path> taken = SystemProgram::at;
    Function<String, Path> found = SystemProgram::find;
    BooleanSupplier terminal = Offer::atTerminal;
    LongSupplier uid = org.fuin.porelo.core.process.SocketPeer::ownUid;
    HostGatherFiles files = new HostGatherFiles();

    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final PrintWriter err = spec.commandLine().getErr();
        final HostGatherPush operation = new HostGatherPush(context);
        operation.forgeConf = forgeConf;
        operation.attributes = attributes;
        operation.taken = taken;
        operation.found = found;
        operation.terminal = terminal;
        operation.uid = uid;
        operation.files = files;
        try {
            operation.run(
                    pulling(),
                    project,
                    repositories,
                    spec.commandLine().getOut(),
                    err,
                    (repository, stage) -> {},
                    () -> true,
                    true);
            return 0;
        } catch (HostGatherRefusal ex) {
            if (!java.util.Objects.requireNonNull(
                            ex.getMessage(), "Gathered refusals have safe words")
                    .isBlank()) err.println("porelo: " + ex.getMessage());
            return ex.code();
        } catch (IOException ex) {
            err.println("porelo: the person's push configuration could not be read");
            return 66;
        } finally {
            err.flush();
            spec.commandLine().getOut().flush();
        }
    }

    abstract boolean pulling();
}
