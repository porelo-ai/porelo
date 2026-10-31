package org.fuin.porelo.app;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.vault.SigningKey;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Certifies a running task's key anew, before its certificate ends.
 *
 * <p>The key stays: only its certificate is new, signed by the person's own agent, here or - with
 * {@code --host} - on their machine, over the same SSH connection a start uses. The task's gate
 * signs with the new one from its next signature on.
 */
@CommandLine.Command(
        name = "certify",
        mixinStandardHelpOptions = true,
        description = "Certifies a running task's key anew, for another week.")
public class TaskCertifyCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Parameters(
            index = "0",
            paramLabel = "TASK",
            description = "The task, by either of its names; with --host by its own name.")
    private String task;

    @Option(
            names = "--host",
            paramLabel = "<login>@<host>",
            description = "The host the task runs on; certified here by your own agent.")
    private @Nullable String host;

    @Option(
            names = {"-p", "--project"},
            paramLabel = "PROJECT",
            description =
                    "The task's project, which the certificate names; needed with --host. Here it"
                            + " picks the task of that project.")
    private @Nullable String projectOption;

    @Option(names = "--certify-stdin", hidden = true, description = "Asks over standard output.")
    private boolean certifyStdin;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
    }

    /** Says why a certificate cannot be asked for on a host as given, or {@code null}. */
    private @Nullable String hostRefusal(String host) {
        if (!host.matches(RemoteStart.HOST)) {
            return "--host takes <login>@<host>";
        }
        if (projectOption == null || projectOption.isBlank()) {
            return "--host needs --project: the certificate names the task's project";
        }
        if (!projectOption.matches(RemoteStart.PROJECT)) {
            return "--host needs a project's name, " + RemoteStart.PROJECT;
        }
        final Optional<String> misnamed =
                org.fuin.porelo.runtime.ContainerName.refusal(projectOption, task);
        if (misnamed.isPresent()) {
            return misnamed.get();
        }
        if (!org.fuin.porelo.runtime.ContainerName.taskFrom(projectOption, task).equals(task)) {
            return "--host needs the task's own name, not its container's";
        }
        return null;
    }

    @Override
    public Integer call() {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        if (host != null) {
            final String refused = hostRefusal(host);
            if (refused != null) {
                err.println("porelo: " + refused);
                err.flush();
                return 64;
            }
            final @Nullable Path agent = RemoteStart.agent(context);
            if (agent == null) {
                err.println("porelo: --host certifies with your agent, and none is running");
                err.flush();
                return 64;
            }
            final List<String> connection = new java.util.ArrayList<>(RemoteStart.SSH);
            connection.add(host);
            try {
                return new RemoteStart(connection, agent)
                        .run(
                                List.of(
                                        "task",
                                        "certify",
                                        "--certify-stdin",
                                        "--project=" + projectOption,
                                        "--",
                                        task),
                                java.util.Objects.requireNonNull(projectOption),
                                // The task's own name: the certificate the host asks for names it.
                                task,
                                out,
                                err);
            } catch (java.io.IOException ex) {
                err.println("porelo: could not reach " + host + ": " + ex.getMessage());
                err.flush();
                return 69;
            }
        }
        // Named, the project picks its own task: a task of the same name in another project is
        // never the one certified.
        final String container =
                projectOption == null
                        ? TaskTarget.spelled(context, task)
                        : org.fuin.porelo.runtime.ContainerName.PREFIX
                                + projectOption
                                + "-"
                                + org.fuin.porelo.runtime.ContainerName.taskFrom(
                                        projectOption, task);
        final String projectName = new TaskControl(context).projectOf(container);
        if (projectOption != null && !projectOption.equals(projectName)) {
            err.println(
                    "porelo: "
                            + task
                            + " is no task of project "
                            + (projectOption.matches(RemoteStart.PROJECT) ? projectOption : "given")
                            + " here");
            err.flush();
            return 66;
        }
        final TaskSecrets secrets = new TaskSecrets(context);
        final Optional<TaskSecrets.Signing> held = secrets.signing(container);
        if (projectName == null || held.isEmpty()) {
            err.println(
                    "porelo: "
                            + task
                            + " has no key of its own here to certify; it was started uncertified,"
                            + " or the vault is locked");
            err.flush();
            return 66;
        }
        final Project project = GateSupport.byName(context, projectName);
        final String name = org.fuin.porelo.runtime.ContainerName.taskIn(projectName, container);
        final List<String> certifiers =
                TaskCertifying.renewing(
                        projectName,
                        held.get().certificate(),
                        TaskCertification.NO_PROJECT.equals(projectName)
                                ? List.of()
                                : TaskCertifying.certifiers(context, project));
        if (certifiers.isEmpty()) {
            err.println(
                    "porelo: no key of project "
                            + projectName
                            + "'s access file may certify "
                            + name);
            err.flush();
            return 65;
        }
        final TaskCertification.Certifier certifier;
        if (certifyStdin) {
            certifier =
                    new StdinCertifier(
                            new java.io.BufferedReader(
                                    new java.io.InputStreamReader(
                                            System.in, java.nio.charset.StandardCharsets.UTF_8)),
                            out,
                            certifiers);
        } else {
            final @Nullable Path agent = RemoteStart.agent(context);
            final String key = agent == null ? null : TaskCertifying.agentHolds(agent, certifiers);
            if (key == null) {
                err.println(
                        "porelo: your agent holds no key that may certify "
                                + name
                                + " (ssh-add it)");
                err.flush();
                return 65;
            }
            certifier = new AgentCertifier(key, java.util.Objects.requireNonNull(agent));
        }
        final String login = System.getProperty("user.name", "");
        try {
            final TaskCertification.Issued issued =
                    TaskCertification.certify(
                            projectName,
                            name,
                            login,
                            new SigningKey(
                                    Base64.getDecoder().decode(held.get().seed()),
                                    "porelo task " + name),
                            certifier,
                            certifiers,
                            Clock.systemUTC());
            final String unkept =
                    secrets.keepSigning(
                            container,
                            new TaskSecrets.Signing(
                                    held.get().seed(), issued.certificate().line()));
            if (!unkept.isEmpty()) {
                err.println("porelo: " + unkept);
                err.flush();
                return 70;
            }
            new TaskCertificateRecords(context).keep(name, login, issued.certificate());
            out.println(
                    "certified "
                            + name
                            + " until "
                            + issued.certificate().validBefore()
                            + ", by "
                            + issued.certificate().signer().split(" ")[0]
                            + " "
                            + org.fuin.porelo.vault.PersonCard.fingerprint(
                                    issued.certificate().signer()));
            out.flush();
            return 0;
        } catch (TaskCertification.Refused ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return 65;
        } catch (java.io.IOException ex) {
            err.println("porelo: the certificate is kept, but not its record: " + ex.getMessage());
            err.flush();
            return 0;
        }
    }
}
