package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectException;
import org.fuin.porelo.core.project.ProjectReader;
import org.fuin.porelo.core.project.Repository;
import org.fuin.porelo.runtime.ContainerName;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Writes the inventory a host's second play runs with: the account, the project with its key, the
 * tasks to start and the keys to put into the vault.
 *
 * <p>The play reads it as a contract, never text meant for a person: its names and {@code
 * porelo_inventory_version} are kept, and the version is raised on any change of a name or a
 * meaning. The project file is checked against the machine file's key exactly as {@code project
 * follow} checks it, and nothing is recorded: a play never follows what it did not check. A key
 * leaves by its name and path only; nothing here reads one.
 */
@Command(
        name = "inventory",
        mixinStandardHelpOptions = true,
        description =
                "Prints the inventory a host playbook's second play runs with, from a machine file"
                        + " and the project file it names, checked against its key.")
public class ProjectInventoryCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    /** Raised on any change of a name or a meaning the play reads. */
    static final int VERSION = 1;

    @Option(
            names = "--machine-file",
            required = true,
            paramLabel = "FILE",
            description = "The person's machine file, on their mounted drive.")
    private Path machineFile = Path.of("");

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        if (!Files.isRegularFile(machineFile)) {
            return refuse(err, 66, "there is no machine file at " + machineFile);
        }
        final MachineFile machine;
        try {
            machine = MachineFile.read(machineFile);
        } catch (final IOException ex) {
            return refuse(err, 66, "cannot read " + machineFile + ": " + ex.getMessage());
        } catch (final IllegalArgumentException ex) {
            return refuse(err, 65, String.valueOf(ex.getMessage()));
        }
        final Reconcile.Checked checked;
        try {
            checked =
                    new FollowSignedBy(context)
                            .checkedWithFile(machine.project(), machine.url(), machine.signedBy());
        } catch (final FollowSignedBy.Refused ex) {
            return refuse(err, 65, ex.getMessage() + " No inventory was written.");
        }
        final String text = checked.projectFile();
        if (text == null) {
            final Reconcile.Result result = checked.result();
            return refuse(
                    err,
                    65,
                    "the project file of "
                            + machine.project()
                            + " did not pass its check ("
                            + result.outcome().name().toLowerCase(java.util.Locale.ROOT)
                            + (result.detail().isEmpty() ? "" : ": " + result.detail())
                            + "). No inventory was written.");
        }
        final Project project;
        try {
            project = ProjectReader.read(new StringReader(text), "project.yml");
        } catch (final ProjectException ex) {
            return refuse(err, 65, "project.yml: " + ex.getMessage());
        }
        final Set<String> repositories =
                project.repositories().stream().map(Repository::name).collect(Collectors.toSet());
        final List<Object> tasks = new ArrayList<>();
        for (final MachineFile.Task task : machine.tasks()) {
            if (!repositories.contains(task.repository())) {
                return refuse(
                        err,
                        65,
                        "the task '"
                                + task.name()
                                + "' names the repository '"
                                + task.repository()
                                + "', which "
                                + project.name()
                                + "'s project file does not have: "
                                + String.join(", ", repositories.stream().sorted().toList()));
            }
            final var refused = ContainerName.refusal(project.name(), task.name());
            if (refused.isPresent()) {
                return refuse(err, 65, refused.get());
            }
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", task.name());
            entry.put("project", project.name());
            entry.put("repository", task.repository());
            entry.put("container", ContainerName.of(project, task.name()));
            if (task.agent() != null) {
                entry.put("agent", task.agent());
                // A default, not a rule: a vendor names its package freely, and the file says so.
                entry.put(
                        "agent_package",
                        task.agentPackage() != null
                                ? task.agentPackage()
                                : "porelo-agent-" + task.agent());
            }
            tasks.add(entry);
        }
        final List<Object> keys = new ArrayList<>();
        for (final MachineFile.Key key : machine.keys()) {
            keys.add(Map.of("name", key.name(), "path", key.path().toString()));
        }
        out.print(inventory(machine, project, tasks, keys));
        out.flush();
        return 0;
    }

    private static String inventory(
            final MachineFile machine,
            final Project project,
            final List<Object> tasks,
            final List<Object> keys) {
        final Map<String, Object> projectVars = new LinkedHashMap<>();
        projectVars.put("name", project.name());
        projectVars.put("url", machine.url());
        projectVars.put("signed_by", machine.signedBy());
        final Map<String, Object> host = new LinkedHashMap<>();
        host.put("ansible_connection", "local");
        host.put("porelo_account", System.getProperty("user.name"));
        host.put("porelo_project", projectVars);
        host.put("porelo_tasks", tasks);
        host.put("porelo_vault_keys", keys);
        final Map<String, Object> all = new LinkedHashMap<>();
        all.put("vars", Map.of("porelo_inventory_version", VERSION));
        all.put("hosts", Map.of("localhost", host));
        final DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(4);
        options.setIndicatorIndent(2);
        options.setIndentWithIndicator(true);
        return "# Written by 'porelo project inventory'; the play's contract is doc/hosts.md.\n"
                + new Yaml(options).dump(Map.of("all", all));
    }

    private static int refuse(final PrintWriter err, final int code, final String message) {
        err.println("porelo: " + message);
        err.flush();
        return code;
    }
}
