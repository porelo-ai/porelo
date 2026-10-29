package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import org.fuin.porelo.core.project.Access;
import org.fuin.porelo.core.project.AccessFile;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.ProjectException;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Writes the inventory a host's second play runs with: the account, the project with its key, the
 * keys to put into the vault.
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
    static final int VERSION = 2;

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
        final Path bundle = machine.accessBundle();
        if (bundle != null && !Files.isRegularFile(bundle)) {
            return refuse(err, 66, "there is no access bundle at " + bundle);
        }
        final Reconcile.Checked checked;
        try {
            checked =
                    new FollowSignedBy(context)
                            .checkedWithFile(
                                    machine.project(),
                                    machine.url(),
                                    machine.signedBy(),
                                    bundle == null ? "" : bundle.toString());
        } catch (final FollowSignedBy.Refused ex) {
            return refuse(err, 65, ex.getMessage() + " No inventory was written.");
        }
        final Reconcile.Result result = checked.result();
        final Project project = checked.project();
        if (checked.projectFile() == null || project == null) {
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
        if (bundle != null && !(project.access() instanceof Access.InRepository)) {
            return refuse(
                    err,
                    65,
                    "the project file names no access repository, so "
                            + bundle
                            + " was not checked. No inventory was written.");
        }
        final @Nullable AccessFile access;
        try {
            if (project.access() != null && checked.accessFile() == null) {
                return refuse(
                        err,
                        65,
                        "the access file of "
                                + machine.project()
                                + " did not pass its check ("
                                + result.accessOutcome().toLowerCase(java.util.Locale.ROOT)
                                + (result.detail().isEmpty() ? "" : ": " + result.detail())
                                + "). No inventory was written.");
            }
            access =
                    checked.accessFile() == null
                            ? null
                            : AccessFile.read(new StringReader(checked.accessFile()), "access.yml");
        } catch (final ProjectException ex) {
            return refuse(err, 65, "access.yml: " + ex.getMessage());
        }
        // The transports' settings are the access file's once the project names one: the public
        // project file then holds none, and the files a play writes are named only there.
        final Map<String, Object> settings =
                access == null ? project.mail().transports() : access.transports();
        final String notAPath =
                notAPath(settings, access == null ? "mail.transports." : "transports.");
        if (notAPath != null) {
            return refuse(
                    err,
                    65,
                    (access == null ? "project.yml: " : "access.yml: ")
                            + notAPath
                            + " No inventory was written.");
        }
        final List<Object> keys = new ArrayList<>();
        for (final MachineFile.Key key : machine.keys()) {
            keys.add(Map.of("name", key.name(), "path", key.path().toString()));
        }
        out.print(inventory(machine, project, settings, keys));
        out.flush();
        return 0;
    }

    private static String inventory(
            final MachineFile machine,
            final Project project,
            final Map<String, Object> settings,
            final List<Object> keys) {
        final Map<String, Object> projectVars = new LinkedHashMap<>();
        projectVars.put("name", project.name());
        projectVars.put("url", machine.url());
        projectVars.put("signed_by", machine.signedBy());
        final Map<String, Object> host = new LinkedHashMap<>();
        host.put("ansible_connection", "local");
        host.put("porelo_account", System.getProperty("user.name"));
        host.put("porelo_project", projectVars);
        host.put("porelo_transports", transports(project, settings));
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

    private static List<Object> transports(
            final Project project, final Map<String, Object> settings) {
        final Set<String> schemes = new LinkedHashSet<>(project.mail().conversations());
        project.mail().peers().forEach(peer -> schemes.add(peer.transport()));
        final List<Object> transports = new ArrayList<>();
        for (final String scheme : schemes) {
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("scheme", scheme);
            entry.put("package", TransportDirectory.PREFIX + scheme);
            final Map<String, Object> files = files(settings.get(scheme));
            if (!files.isEmpty()) {
                entry.put("files", files);
            }
            transports.add(entry);
        }
        return transports;
    }

    /**
     * Names the first setting named {@code *_file} that does not hold a path, never its value: a
     * value there may be a secret written under the wrong name, and the play would write a file
     * wherever it pointed.
     *
     * @param transports Each transport's settings, from the checked project or access file.
     * @param prefix Where they are in their file, for the message.
     * @return Why, or {@code null} when each is a path.
     */
    static @Nullable String notAPath(final Map<String, Object> transports, final String prefix) {
        for (final Map.Entry<String, Object> transport : transports.entrySet()) {
            if (!(transport.getValue() instanceof Map<?, ?> settings)) {
                continue;
            }
            for (final Map.Entry<?, ?> setting : settings.entrySet()) {
                final String name = String.valueOf(setting.getKey());
                if (!name.endsWith("_file")) {
                    continue;
                }
                final String where = prefix + transport.getKey() + "." + name;
                final String why = pathProblem(name, setting.getValue());
                if (why != null) {
                    return where + " " + why + ".";
                }
            }
        }
        return null;
    }

    private static @Nullable String pathProblem(final String name, final @Nullable Object value) {
        if (name.equals("_file")) {
            return "names no file";
        }
        if (!(value instanceof String path) || path.isBlank()) {
            return "must be a path and is not one";
        }
        if (path.chars().anyMatch(c -> c < 0x20 || c == 0x7f)) {
            return "must be one path on one line";
        }
        if (!path.startsWith("/") && !path.startsWith("~/")) {
            return "must be an absolute path or one under ~/";
        }
        if (("/" + path + "/").contains("/../")) {
            return "must not climb out of its directory with ..";
        }
        return null;
    }

    /**
     * The files a transport expects, by its settings named {@code *_file}: a path is never a
     * secret, while any other setting may be one, so nothing else of the settings is passed on.
     */
    private static Map<String, Object> files(final @Nullable Object settings) {
        final Map<String, Object> files = new LinkedHashMap<>();
        if (settings instanceof Map<?, ?> map) {
            map.forEach(
                    (name, value) -> {
                        if (String.valueOf(name).endsWith("_file")
                                && value instanceof String path) {
                            files.put(String.valueOf(name), path);
                        }
                    });
        }
        return files;
    }

    private static int refuse(final PrintWriter err, final int code, final String message) {
        err.println("porelo: " + message);
        err.flush();
        return code;
    }
}
