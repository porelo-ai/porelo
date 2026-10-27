package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.wire.Json;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** Lets another machine into a project's conversation on this machine's server. */
@Command(
        name = "admit",
        mixinStandardHelpOptions = true,
        description =
                "Lets another machine into a project's conversation on this machine's server, and"
                        + " writes what it needs - a secret among it - to a new file of yours.")
public class TalkAdmitCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<project>", description = "Project name.")
    private String projectName;

    @Parameters(index = "1", paramLabel = "<machine>", description = "The machine let in.")
    private String machine;

    @Option(
            names = "--into",
            required = true,
            paramLabel = "FILE",
            description = "A new file, readable by you only, for the admission and its secret.")
    private Path into;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        final Project project = GateSupport.byName(context, projectName);
        // Before the transport is asked: a token handed out with nowhere to keep it is one too
        // many.
        final Path directory = into.toAbsolutePath().getParent();
        if (Files.exists(into, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            err.println("porelo: " + into + " exists; the admission goes into a new file");
            err.flush();
            return 73;
        }
        if (directory == null || !Files.isDirectory(directory) || !Files.isWritable(directory)) {
            err.println(
                    "porelo: " + into + " cannot be written: its directory is not writable by you");
            err.flush();
            return 73;
        }
        // Its name tried once, as the admission will be linked in: a name the file system refuses
        // is found before a token is minted, not after.
        try {
            final Path probe = Files.createTempFile(directory, ".admission", ".tmp");
            try {
                Files.createLink(into, probe);
                if (Files.isSameFile(into, probe)) {
                    Files.delete(into);
                }
            } finally {
                Files.deleteIfExists(probe);
            }
        } catch (IOException ex) {
            err.println("porelo: " + into + " cannot be written: " + ex.getMessage());
            err.flush();
            return 73;
        }
        final Map<?, ?> said;
        try {
            said = MachineTransports.admit(context, project, machine);
        } catch (Conversations.Refused ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return ex.code() == 0 ? 69 : ex.code();
        }
        // Written whole beside it, owner-only from the start, then linked in under its name, which
        // fails rather than replace a file that appeared meanwhile; nothing half-written is left.
        Path staged = null;
        try {
            staged =
                    Files.createTempFile(
                            directory,
                            ".admission",
                            ".tmp",
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rw-------")));
            Files.writeString(
                    staged, Json.write(said), StandardCharsets.UTF_8, StandardOpenOption.WRITE);
            Files.createLink(into, staged);
        } catch (FileAlreadyExistsException ex) {
            err.println(
                    "porelo: " + into + " appeared meanwhile; the machine is admitted, run again");
            err.flush();
            return 73;
        } catch (IOException ex) {
            err.println(
                    "porelo: the admission could not be written to "
                            + into
                            + ": "
                            + ex.getMessage()
                            + "; the machine is admitted, run again to get it once more");
            err.flush();
            return 73;
        } finally {
            if (staged != null) {
                try {
                    Files.deleteIfExists(staged);
                } catch (IOException ex) {
                    err.println(
                            "porelo: " + staged + " is left behind: remove it, it holds a secret");
                    err.flush();
                }
            }
        }
        out.println(
                "admitted  "
                        + machine
                        + " into "
                        + projectName
                        + "'s conversation; what it needs is in "
                        + into
                        + ", readable by you only");
        out.flush();
        return 0;
    }
}
