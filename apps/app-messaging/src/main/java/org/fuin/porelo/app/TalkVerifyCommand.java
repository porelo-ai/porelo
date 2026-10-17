package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Says whether a task's record still holds together.
 *
 * <p>The question an operator asks after something went wrong: was this mailbox tampered with. It
 * is answered by walking rather than by trusting a flag somebody could set.
 */
@Command(
        name = "verify",
        mixinStandardHelpOptions = true,
        description = "Walks the record and names the first entry that does not check out.")
public class TalkVerifyCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "<task>", description = "Container name of the task.")
    private String container;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() throws Exception {
        // By the container's name or the task's own, as every task command takes it.
        container = TaskTarget.spelled(context, container);
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        final Mailbox mailbox = new Mailbox(context.paths().messaging().mailbox(container));
        if (!mailbox.exists()) {
            err.println("porelo: " + container + " has no mailbox");
            err.flush();
            return 1;
        }
        final MessageVerify.Report report = new MessageVerify().check(mailbox, believed());
        out.println(
                "record    "
                        + report.lines()
                        + " line(s), "
                        + report.signatures()
                        + " signature(s) checked");
        if (report.faults().isEmpty()) {
            out.println("intact");
            out.flush();
            return 0;
        }
        out.flush();
        report.faults()
                .forEach(
                        fault -> err.println("broken    " + fault.what() + " - " + fault.detail()));
        err.flush();
        // A failure, because this command exists to be run by something that acts on the answer.
        return 1;
    }

    /**
     * Returns every key a delivery believes, as a pass gathers them: the operator's file alone
     * named every message from this machine's own tasks, from the account next door and from a
     * followed project's machines as signed by a key no peer may use, and the command failed.
     */
    private java.util.List<MessageDelivery.Peer> believed() throws java.io.IOException {
        final java.util.List<MessageDelivery.Peer> peers =
                new java.util.ArrayList<>(KnownPeers.of(context));
        final java.nio.file.Path own = context.paths().messaging().messageKey();
        if (java.nio.file.Files.isRegularFile(own)) {
            // This machine's own key, which signs what its tasks send each other: only read here,
            // never made - a machine without one has signed nothing.
            final var key = HostKey.loadOrCreate(own, "porelo");
            peers.add(new MessageDelivery.Peer(key.comment(), java.util.List.of(key.keyBlob())));
        }
        return peers;
    }
}
