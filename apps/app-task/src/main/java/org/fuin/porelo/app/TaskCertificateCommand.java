package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Traces a task key to its certificate: which task it signed for, who vouched for it and while.
 *
 * <p>A signature by a task's plain key names only the key's fingerprint. What this machine kept
 * answers first; on a host whose persons start tasks, the host's own record answers the rest, and
 * an answer about another key is not believed.
 */
@CommandLine.Command(
        name = "certificate",
        mixinStandardHelpOptions = true,
        description = "Says which task a key signed for, and who certified it.")
public class TaskCertificateCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Parameters(
            index = "0",
            paramLabel = "FINGERPRINT",
            description = "The task key's fingerprint, SHA256:...")
    private String fingerprint;

    @Option(names = "--json", description = "Answer one JSON object.")
    private boolean json;

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
        if (!fingerprint.matches("SHA256:[A-Za-z0-9+/]{43}")) {
            err.println("porelo: " + fingerprint + " is not a key fingerprint, SHA256:...");
            err.flush();
            return 64;
        }
        Map<String, Object> record = new TaskCertificateRecords(context).find(fingerprint);
        if (record == null) {
            final Object answer;
            try {
                answer = fromHost();
            } catch (IllegalArgumentException ex) {
                err.println("porelo: the host answered for another key, or not as a record");
                err.flush();
                return 70;
            }
            if (answer == null) {
                err.println("porelo: no certificate is kept for " + fingerprint);
                err.flush();
                return 66;
            }
            @SuppressWarnings("unchecked")
            final Map<String, Object> hosts = (Map<String, Object>) answer;
            record = hosts;
        }
        if (json) {
            out.println(Json.write(record));
        } else {
            for (final var entry : record.entrySet()) {
                out.printf("%-13s %s%n", entry.getKey(), entry.getValue());
            }
        }
        out.flush();
        return 0;
    }

    /**
     * The host's record of the key, or {@code null} when it keeps none or is no host.
     *
     * @throws IllegalArgumentException If it answered something else than a record of this key.
     */
    private @Nullable Object fromHost() {
        final CommandResult shown;
        try {
            shown =
                    context.runner()
                            .run(
                                    // The records are root's: sudo, which allows any admitted
                                    // person.
                                    Command.of(
                                            "sudo",
                                            "-n",
                                            HostPerson.PROGRAM.toString(),
                                            "task",
                                            "--show",
                                            fingerprint));
        } catch (CommandException ex) {
            return null;
        }
        if (!shown.successful() || shown.standardOutput().isBlank()) {
            return null;
        }
        final Object answer = Json.parse(shown.standardOutput().strip());
        if (!(answer instanceof Map<?, ?> map) || !fingerprint.equals(map.get("fingerprint"))) {
            throw new IllegalArgumentException("not a record of " + fingerprint);
        }
        return answer;
    }
}
