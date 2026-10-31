package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.gate.GateException;
import org.fuin.porelo.gate.GitGate;
import org.fuin.porelo.gate.GitHttpServer;
import org.fuin.porelo.gate.GitSubprocess;
import org.fuin.porelo.gate.SignedArrivals;
import org.fuin.porelo.gate.TaskToken;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Serves the project's mirror over git smart-HTTP for one task.
 *
 * <p>The agent is given the printed URL and token, and nothing else. It can push, and it cannot
 * reach the upstream: the token authenticates only to this endpoint, only for this task, and the
 * mirror forwards nothing without an explicit approval.
 */
@Command(
        name = "serve",
        mixinStandardHelpOptions = true,
        description = "Serves the project mirror for an agent to push to.")
public class GateServeCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Option(
            names = {"-p", "--project"},
            paramLabel = "<name>",
            required = true,
            description = "Project name, as 'porelo project list' prints it.")
    private String projectName;

    @Option(
            names = {"-r", "--repository"},
            paramLabel = "<name>",
            description = "Which of the project's repositories. Default: the project's own.")
    private @Nullable String repository;

    @Option(
            names = "--upstream",
            paramLabel = "<url>",
            description = "Upstream repository, used only when approving.")
    private @Nullable String upstream;

    @Option(
            names = "--address",
            paramLabel = "<ip>",
            description = "Address to bind. Default: ${DEFAULT-VALUE}")
    private String address = "127.0.0.1";

    @Option(
            names = "--port",
            paramLabel = "<n>",
            description = "Port to bind, or 0 to pick one. Default: ${DEFAULT-VALUE}")
    private int port;

    @Option(
            names = "--pid-file",
            paramLabel = "<file>",
            description = "Writes this process's id here, so the poststop hook can reap it.")
    private @Nullable Path pidFile;

    @Option(
            names = "--ref",
            paramLabel = "<ref>",
            description =
                    "The one ref a push may update, the task's own. Default: any ref under refs/porelo/incoming/.")
    private @Nullable String ref;

    @Option(
            names = "--pass-on",
            description =
                    "Passes a push to the task's own ref on to the upstream at once, as its branch, and fetches"
                            + " from the upstream before the agent fetches: an online task's gate. Needs --ref.")
    private boolean passOn;

    @Option(
            names = "--branch",
            paramLabel = "NAME",
            description =
                    "The task's branch at the forge, as its start settled it against the project file of"
                            + " its day; porelo/<task> when not given. Only with --pass-on, and only a name git takes.")
    private @Nullable String branchName;

    @Option(
            names = "--host-push",
            paramLabel = "<project>/<repo>",
            description =
                    "On a host: the task's branch, porelo/<task>, is pushed to this repository of the"
                            + " host's forge on its loopback, with the task's own token. Only with --pass-on, not with --branch.")
    private @Nullable String hostPush;

    @Option(
            names = "--principal",
            paramLabel = "task:<project>:<task>",
            description =
                    "The task's certificate principal. With --signers, every commit a push brings to --ref"
                            + " is judged at its arrival by its signature through the task's certificate.")
    private @Nullable String principal;

    @Option(
            names = "--signers",
            paramLabel = "FILE",
            description =
                    "The certifiers' cert-authority lines, in ssh-keygen's allowed signers format. Only"
                            + " with --principal and --ref.")
    private @Nullable Path signers;

    @Option(
            names = "--seconds",
            paramLabel = "<n>",
            description = "Stop after this long. Zero means run until killed.")
    private int seconds;

    @Spec private CommandSpec spec;

    /**
     * The pass-on of a task's gate on a host: a push to the host's forge on its loopback, with the
     * task's token from the vault, read at each push. Nothing is fetched from the forge here: the
     * push alone reaches it.
     */
    private GitHttpServer.Upstream hostPushing(
            final GitGate gate, final Project project, final String ref, final String repository) {
        final String task = ref.substring(GitGate.INCOMING.length());
        final String container = org.fuin.porelo.runtime.ContainerName.of(project, task);
        final TaskSecrets secrets = new TaskSecrets(context);
        final HostPush push =
                new HostPush(
                        context,
                        gate.mirror(),
                        task,
                        repository,
                        () -> secrets.forgeToken(container));
        return new GitHttpServer.Upstream() {
            @Override
            public void refresh() {}

            @Override
            public @Nullable String passOn(String taken, String commit) {
                return push.passOn(commit);
            }
        };
    }

    private void writePidFile(PrintWriter err) {
        if (pidFile == null) {
            return;
        }
        try {
            org.fuin.porelo.wire.HelperPid.record(pidFile);
        } catch (java.io.IOException ex) {
            err.println("porelo: cannot write " + pidFile + ": " + ex.getMessage());
            err.flush();
        }
    }

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() throws Exception {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        try {

            final Project project = GateSupport.byName(context, projectName);
            final String effectiveUpstream =
                    upstream != null ? upstream : System.getenv("PORELO_GATE_UPSTREAM");
            final GitGate gate =
                    GateSupport.gate(
                            context,
                            project,
                            GateSupport.repository(project, repository),
                            effectiveUpstream,
                            null);
            gate.initialize();

            // From the environment when a task started this gate, so the container and the gate
            // agree on it. Never from an argument: a command line is visible in the host's process
            // list, and this token authenticates pushes.
            final String supplied = System.getenv("PORELO_GATE_TOKEN");
            final TaskToken token =
                    supplied == null || supplied.isBlank()
                            ? TaskToken.mint()
                            : new TaskToken(supplied);
            if (passOn && (ref == null || !ref.startsWith(GitGate.INCOMING))) {
                err.println(
                        "porelo: --pass-on needs --ref "
                                + GitGate.INCOMING
                                + "<task>, the one ref passed on");
                err.flush();
                return 2;
            }
            if ((principal == null) != (signers == null)
                    || (principal != null && (ref == null || !ref.startsWith(GitGate.INCOMING)))) {
                // Half of it would serve a task's gate that judges nothing, silently.
                err.println(
                        "porelo: --principal and --signers go together, with --ref "
                                + GitGate.INCOMING
                                + "<task>");
                err.flush();
                return 2;
            }
            if (hostPush != null
                    && (!passOn
                            || branchName != null
                            || !hostPush.matches(
                                    "[A-Za-z0-9_][A-Za-z0-9._-]*/[A-Za-z0-9_][A-Za-z0-9._-]*"))) {
                err.println(
                        "porelo: --host-push takes <project>/<repo>, with --pass-on and without --branch");
                err.flush();
                return 2;
            }
            if (branchName != null && !passOn) {
                err.println("porelo: --branch names where work is passed on; it needs --pass-on");
                err.flush();
                return 2;
            }
            // Not judged by the project file's pattern again: the start settled the branch against
            // the file of its day and recorded it, and a resume after the file changed keeps it.
            // Only that git can name it, since this is what pushes with force to the forge.
            if (branchName != null
                    && !org.fuin.porelo.core.project.BranchScheme.refFormat(branchName)) {
                err.println("porelo: '" + branchName + "' is not a name git takes for a branch");
                err.flush();
                return 2;
            }
            final String branch =
                    passOn
                            ? branchName != null
                                    ? branchName
                                    : "porelo/"
                                            + java.util.Objects.requireNonNull(ref)
                                                    .substring(GitGate.INCOMING.length())
                            : null;
            final GitHttpServer.Upstream through =
                    branch == null
                            ? null
                            : hostPush != null
                                    ? hostPushing(
                                            gate,
                                            project,
                                            java.util.Objects.requireNonNull(ref),
                                            hostPush)
                                    : new GitHttpServer.Upstream() {
                                        @Override
                                        public void refresh() {
                                            final String behind = gate.refresh();
                                            if (!behind.isEmpty()) {
                                                System.err.println(behind);
                                            }
                                        }

                                        @Override
                                        public @Nullable String passOn(
                                                String taken, String commit) {
                                            return gate.passOn(branch, commit);
                                        }
                                    };
            try (GitHttpServer server =
                    new GitHttpServer(
                            new InetSocketAddress(InetAddress.getByName(address), port),
                            gate.mirror(),
                            token,
                            new GitSubprocess(),
                            ref,
                            through,
                            GitHttpServer.PACE)) {

                if (ref != null && ref.startsWith(GitGate.INCOMING)) {
                    // A task's commit is signed here, never in its container. Read at each
                    // signature: a vault unlocked after the gate started is read then.
                    final String container =
                            org.fuin.porelo.runtime.ContainerName.of(
                                    project, ref.substring(GitGate.INCOMING.length()));
                    final TaskSecrets secrets = new TaskSecrets(context);
                    server.signing(TaskSigner.of(() -> secrets.signing(container)));
                }
                if (principal != null && signers != null && ref != null) {
                    server.judging(
                            ArrivalRecords.recording(
                                    new SignedArrivals(
                                                    gate.mirror(),
                                                    principal,
                                                    signers,
                                                    new GitSubprocess(),
                                                    java.time.Clock.systemUTC())
                                            ::judge,
                                    projectName,
                                    ref.substring(ref.lastIndexOf('/') + 1),
                                    (kind, named, task, detail) ->
                                            EventLog.record(context, kind, named, task, detail),
                                    java.time.Clock.systemUTC()));
                }
                server.start();
                writePidFile(err);
                out.println("mirror    " + gate.mirror());
                out.println(
                        "mode      "
                                + gate.mode().name().toLowerCase()
                                + (branch == null ? "" : ", passing " + ref + " on as " + branch));
                out.println(
                        "url       http://"
                                + address
                                + ":"
                                + server.port()
                                + "/"
                                + project.name()
                                + ".git");
                // Abbreviated: this line goes to gate.log, which the daemon streams to whatever is
                // tailing it. The container is given the token through its environment.
                out.println("token     " + token.abbreviate());
                out.flush();

                if (seconds > 0) {
                    Thread.sleep(java.time.Duration.ofSeconds(seconds));
                } else {
                    Thread.currentThread().join();
                }
            }
            return 0;

        } catch (GateException ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return 70;
        }
    }
}
