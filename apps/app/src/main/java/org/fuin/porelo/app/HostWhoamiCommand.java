package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.porelo.core.process.SocketPeer;
import org.fuin.porelo.wire.Json;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Says which account it runs in and where that account's daemon answers, so an interface that
 * reached a host over SSH knows whose account it reached and where its tunnel returns to.
 */
@Command(
        name = "whoami",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description =
                "Answers this account's login and uid and the socket its running daemon answers"
                        + " on.")
public class HostWhoamiCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Option(names = "--json", description = "Answers in JSON, for a program.")
    private boolean json;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final Path socket = context.paths().daemonSocket();
        final PrintWriter out = spec.commandLine().getOut();
        try {
            // Asked, never found by its file: a socket outlives the daemon that made it.
            DoctorCommand.versionAt(socket);
        } catch (RuntimeException ex) {
            final String words =
                    "porelod does not answer at "
                            + socket
                            + " in this account; start it with 'systemctl --user start porelod'";
            if (json) {
                final Map<String, Object> refusal = new LinkedHashMap<>();
                refusal.put("reason", "no-daemon");
                refusal.put("words", words);
                out.println(Json.write(AnswerText.printable(refusal)));
                out.flush();
            }
            final PrintWriter err = spec.commandLine().getErr();
            err.println("porelo: " + AnswerText.printable(words));
            err.flush();
            return 66;
        }
        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("login", System.getProperty("user.name", ""));
        answer.put("uid", SocketPeer.ownUid());
        answer.put("return_socket", socket.toString());
        if (json) {
            out.println(Json.write(AnswerText.printable(answer)));
        } else {
            out.println(
                    AnswerText.printable(
                            answer.get("login")
                                    + " (uid "
                                    + answer.get("uid")
                                    + "), daemon at "
                                    + socket));
        }
        out.flush();
        return 0;
    }
}
