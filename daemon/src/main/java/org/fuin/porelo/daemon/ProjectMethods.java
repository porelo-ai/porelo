package org.fuin.porelo.daemon;

import static org.fuin.porelo.daemon.PoreloDaemon.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.app.GateSupport;
import org.fuin.porelo.app.PoreloContext;
import org.fuin.porelo.app.ProjectInventory;
import org.fuin.porelo.app.TaskControl;
import org.fuin.porelo.app.TaskInventory;
import org.fuin.porelo.wire.varlink.VarlinkException;
import org.fuin.porelo.wire.varlink.VarlinkServer;

/**
 * The daemon's methods for projects, following, backups and clearing.
 *
 * <p>One class per area, so a change to one area's methods is a change to its own file; {@link
 * PoreloDaemon} only says which areas there are.
 */
final class ProjectMethods {

    private ProjectMethods() {}

    /** Takes a project as a start does; replaced by a test, which has no forge. */
    @FunctionalInterface
    interface Taker {
        void take(PoreloContext context, String project)
                throws org.fuin.porelo.app.ProjectTaking.Refused;
    }

    /** Asks the installed ecosystem adapters; a test installs stub ones. */
    private static org.fuin.porelo.app.DefaultProposal proposals(final PoreloContext context) {
        return new org.fuin.porelo.app.DefaultProposal(
                context, org.fuin.porelo.app.EcosystemDetector.INSTALLED);
    }

    /** One step of a proposal, which may be refused. */
    @FunctionalInterface
    private interface Proposing<T> {
        T run()
                throws org.fuin.porelo.app.DefaultProposal.NotProposable,
                        org.fuin.porelo.app.DefaultProposal.NoSuchRepository,
                        java.io.IOException;
    }

    /** Runs a step of a proposal, each refusal its named error. */
    private static <T> T proposing(final Proposing<T> step) {
        try {
            return step.run();
        } catch (org.fuin.porelo.app.DefaultProposal.NotProposable ex) {
            throw new VarlinkException(INTERFACE + ".NotProposable", Map.of("reason", ex.reason()));
        } catch (org.fuin.porelo.app.DefaultProposal.NoSuchRepository ex) {
            throw new VarlinkException(
                    INTERFACE + ".NoSuchDefaultRepository",
                    Map.of("name", String.valueOf(ex.getMessage())));
        } catch (org.fuin.porelo.ecosystem.api.EcosystemException ex) {
            throw new VarlinkException(
                    INTERFACE + ".EcosystemsNotListed",
                    Map.of(
                            "detail",
                            org.fuin.porelo.vault.RegistryText.printable(
                                    String.valueOf(ex.getMessage()))));
        } catch (java.io.IOException ex) {
            throw new VarlinkException(
                    INTERFACE + ".Failed",
                    Map.of(
                            "message",
                            org.fuin.porelo.vault.RegistryText.printable(
                                    String.valueOf(ex.getMessage()))));
        }
    }

    /**
     * Returns a repository of 'default' as DefaultRepository: with the project that claims it and
     * its proposal, worked out now.
     */
    private static Map<String, Object> defaultRow(
            final PoreloContext context, final org.fuin.porelo.app.DefaultProject.Entry entry) {
        final Map<String, Object> row =
                new org.fuin.porelo.app.DefaultProposal(
                                context, org.fuin.porelo.app.EcosystemDetector.INSTALLED)
                        .row(entry);
        final org.fuin.porelo.app.WorkOrigin.Choice claimed =
                org.fuin.porelo.app.WorkOrigin.placedNaming(context, entry.upstream());
        row.put("claimedBy", claimed == null ? "" : claimed.project());
        return row;
    }

    /**
     * Takes a project a host carries for this person, for their one act and never for a list: it
     * fetches, and on a host replaces the person's forge token.
     *
     * @param context The machine.
     * @param project The project's name.
     * @param projects What the host says.
     * @param taker How it is taken.
     * @return The file, or why not: each field empty when it does not apply.
     */
    static Map<String, Object> taken(
            PoreloContext context,
            String project,
            org.fuin.porelo.app.OrganizationProjects projects,
            Taker taker) {
        try {
            if (!project.matches(org.fuin.porelo.core.project.Project.NAME_PATTERN)
                    || projects.host(project) == null) {
                return take(
                        "",
                        "not-carried",
                        "this machine carries no project of that name for this person");
            }
            taker.take(context, project);
            final org.fuin.porelo.app.ProjectSource.Found found =
                    org.fuin.porelo.app.ProjectSource.resolve(context, project);
            // A file of that name written here is not what was taken.
            return found.outcome() == org.fuin.porelo.app.ProjectSource.Outcome.ORGANIZATION
                    ? take(String.valueOf(found.file()), "", "")
                    : take("", "cannot-say", "the project was read, and its file is not here");
        } catch (final org.fuin.porelo.app.ProjectTaking.Refused ex) {
            return take("", ex.reason(), String.valueOf(ex.getMessage()));
        } catch (final java.io.IOException ex) {
            return take("", "cannot-say", "what the host says of the project does not read");
        }
    }

    private static Map<String, Object> take(String file, String reason, String words) {
        return Map.of("file", file, "reason", reason, "words", words);
    }

    /**
     * Registers this area's methods.
     *
     * @param server Where.
     * @param context The machine.
     * @param inventory The daemon's one task inventory.
     * @param control The daemon's one task control.
     */
    @SuppressWarnings("unused")
    static void register(
            VarlinkServer server,
            PoreloContext context,
            TaskInventory inventory,
            TaskControl control) {
        server.method(
                "WatchProjects",
                (parameters, replies) -> {
                    if (!replies.streaming()) {
                        // Same shape as Watch: asked without 'more' it answers once, so one method
                        // serves a client that streams and one that cannot.
                        replies.last(
                                Map.of(
                                        "projects",
                                        new ProjectInventory(context)
                                                .projects().stream()
                                                        .map(
                                                                summary ->
                                                                        projectRow(
                                                                                context, summary))
                                                        .toList()));
                        return;
                    }
                    List<Map<String, Object>> previous = null;
                    while (replies.open()) {
                        final List<Map<String, Object>> projects =
                                new ProjectInventory(context)
                                        .projects().stream()
                                                .map(summary -> projectRow(context, summary))
                                                .toList();
                        // The whole answer, unlike Watch, which has to strip an age first. Nothing
                        // here is derived from a clock: the counts are counts, and 'behindMeasured'
                        // moves only when the timer that measures it runs - which is a change worth
                        // pushing, because the age an interface draws beside the number resets with
                        // it.
                        if (!projects.equals(previous)) {
                            replies.more(Map.of("projects", projects));
                            previous = projects;
                        }
                        sleep(PROJECT_WATCH_INTERVAL);
                    }
                });

        server.method(
                "Projects",
                (parameters, replies) -> {
                    // The path in each answer is the one thing a client cannot work out: over a
                    // forwarded socket there is no filesystem on this side to look in, and every
                    // gate method takes one.
                    replies.last(
                            Map.of(
                                    "projects",
                                    new ProjectInventory(context)
                                            .projects().stream()
                                                    .map(summary -> projectRow(context, summary))
                                                    .toList()));
                });

        server.method(
                "TakeProject",
                (parameters, replies) ->
                        replies.last(
                                taken(
                                        context,
                                        text(parameters, "project"),
                                        org.fuin.porelo.app.OrganizationProjects.of(context),
                                        org.fuin.porelo.app.ProjectTaking::take)));

        server.method(
                "RestoreBackup",
                (parameters, replies) -> {
                    final org.fuin.porelo.app.BackupRestore.Result result =
                            org.fuin.porelo.app.BackupRestore.restore(
                                    context,
                                    text(parameters, "project"),
                                    empty(parameters, "repository"),
                                    java.nio.file.Path.of(text(parameters, "bundle")),
                                    flag(parameters, "dryRun"),
                                    flag(parameters, "force"));
                    replies.last(
                            Map.of(
                                    "outcome",
                                    result.outcome().name(),
                                    "mirror",
                                    result.mirror(),
                                    "unreviewed",
                                    result.unreviewed(),
                                    "detail",
                                    result.detail()));
                });

        server.method(
                "DeleteBackup",
                (parameters, replies) -> {
                    final org.fuin.porelo.app.BackupRecords records =
                            new org.fuin.porelo.app.BackupRecords(
                                    context.paths().gate().backupRecords());
                    final String project = text(parameters, "project");
                    final java.nio.file.Path bundle =
                            java.nio.file.Path.of(text(parameters, "bundle")).toAbsolutePath();
                    // Under every key Backups lists from: a bundle of the project's other
                    // repository is recorded under that one.
                    String key = project;
                    java.util.Optional<org.fuin.porelo.app.BackupRecords.Backup> known =
                            java.util.Optional.empty();
                    for (final String each : backupKeys(context, project)) {
                        known =
                                records.of(each).stream()
                                        .filter(backup -> backup.bundle().equals(bundle))
                                        .findFirst();
                        if (known.isPresent()) {
                            key = each;
                            break;
                        }
                    }
                    if (known.isEmpty()) {
                        replies.last(
                                Map.of(
                                        "outcome",
                                        "NO_SUCH_BACKUP",
                                        "fileRemoved",
                                        false,
                                        "refs",
                                        0,
                                        "detail",
                                        "no backup of '" + project + "' at " + bundle));
                        return;
                    }
                    if (flag(parameters, "dryRun")) {
                        replies.last(
                                Map.of(
                                        "outcome",
                                        "PREVIEWED",
                                        "fileRemoved",
                                        known.get().present(),
                                        "refs",
                                        known.get().refs(),
                                        "detail",
                                        ""));
                        return;
                    }
                    try {
                        final boolean removed = java.nio.file.Files.deleteIfExists(bundle);
                        records.forget(key, bundle);
                        replies.last(
                                Map.of(
                                        "outcome",
                                        "DELETED",
                                        "fileRemoved",
                                        removed,
                                        "refs",
                                        known.get().refs(),
                                        "detail",
                                        ""));
                    } catch (java.io.IOException | RuntimeException ex) {
                        // The record is left alone when the file could not go: forgetting it would
                        // hide a bundle that is still on disk, which is worse than an entry
                        // somebody retries.
                        replies.last(
                                Map.of(
                                        "outcome",
                                        "FAILED",
                                        "fileRemoved",
                                        false,
                                        "refs",
                                        known.get().refs(),
                                        "detail",
                                        String.valueOf(ex.getMessage())));
                    }
                });

        server.method(
                "SyncUpstream",
                (parameters, replies) -> {
                    final org.fuin.porelo.app.UpstreamSync.Result result =
                            org.fuin.porelo.app.UpstreamSync.sync(
                                    context,
                                    text(parameters, "project"),
                                    empty(parameters, "repository"));
                    replies.last(
                            Map.of(
                                    "outcome",
                                    result.outcome().name(),
                                    "behind",
                                    result.behind(),
                                    "measured",
                                    result.measured(),
                                    "reason",
                                    result.reason(),
                                    "detail",
                                    result.detail()));
                });

        server.method(
                "Backups",
                (parameters, replies) -> {
                    replies.last(
                            Map.of(
                                    "backups",
                                    new org.fuin.porelo.app.BackupRecords(
                                                    context.paths().gate().backupRecords())
                                                    .of(
                                                            org.fuin.porelo.app.GateSupport
                                                                    .recordKey(
                                                                            text(
                                                                                    parameters,
                                                                                    "project"),
                                                                            text(
                                                                                                    parameters,
                                                                                                    "repository")
                                                                                            .isEmpty()
                                                                                    ? text(
                                                                                            parameters,
                                                                                            "project")
                                                                                    : text(
                                                                                            parameters,
                                                                                            "repository")))
                                                    .stream()
                                                    .map(
                                                            backup ->
                                                                    Map.<String, Object>of(
                                                                            "taken",
                                                                                    backup.taken()
                                                                                            .toString(),
                                                                            "bundle",
                                                                                    backup.bundle()
                                                                                            .toString(),
                                                                            "refs", backup.refs(),
                                                                            "present",
                                                                                    backup
                                                                                            .present(),
                                                                            "bytes",
                                                                                    backup.bytes()))
                                                    .toList()));
                });

        server.method(
                "HostKeys",
                (parameters, replies) -> {
                    // What a host offers, so a person can compare it with what they were told.
                    // Shown, never recorded: recording is TrustHostKey, and it takes the
                    // fingerprint back.
                    final String host = hostName(parameters);
                    replies.last(
                            Map.of(
                                    "host",
                                    host,
                                    "known",
                                    org.fuin.porelo.app.HostKeys.known(context, host),
                                    "keys",
                                    org.fuin.porelo.app.HostKeys.offeredBy(context, host).stream()
                                            .map(org.fuin.porelo.app.HostKeys.Offered::asMap)
                                            .toList()));
                });

        server.method(
                "TrustHostKey",
                (parameters, replies) -> {
                    // The fingerprint comes from the person: they were shown what the host offered
                    // and compared it with what they were told out of band. Asking the host again
                    // here is not a second opinion - it stops a key that arrived in between from
                    // being the one written down.
                    final String host = hostName(parameters);
                    final org.fuin.porelo.app.HostKeys.Offered recorded;
                    try {
                        recorded =
                                org.fuin.porelo.app.HostKeys.trust(
                                        context, host, text(parameters, "fingerprint"));
                    } catch (java.io.IOException ex) {
                        throw new VarlinkException(
                                INTERFACE + ".Failed",
                                Map.of("message", String.valueOf(ex.getMessage())));
                    }
                    if (recorded == null) {
                        replies.last(
                                Map.of(
                                        "recorded",
                                        false,
                                        "type",
                                        "",
                                        "fingerprint",
                                        "",
                                        "detail",
                                        host
                                                + " offers no key with that fingerprint right now."
                                                + " Nothing was recorded."));
                        return;
                    }
                    replies.last(
                            Map.of(
                                    "recorded",
                                    true,
                                    "type",
                                    recorded.type(),
                                    "fingerprint",
                                    recorded.fingerprint(),
                                    "detail",
                                    host + " is known to this machine from now on"));
                });

        server.method(
                "DefaultRepositories",
                (parameters, replies) -> {
                    final java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
                    for (final org.fuin.porelo.app.DefaultProject.Entry entry :
                            new org.fuin.porelo.app.DefaultProject(context).usableEntries()) {
                        rows.add(defaultRow(context, entry));
                    }
                    replies.last(Map.of("repositories", rows));
                });

        server.method(
                "AddToDefault",
                (parameters, replies) -> {
                    try {
                        final org.fuin.porelo.app.DefaultProject.Entry entry =
                                new org.fuin.porelo.app.DefaultProject(context)
                                        .add(
                                                address(parameters, "upstream"),
                                                empty(parameters, "name"),
                                                "");
                        replies.last(Map.of("repository", defaultRow(context, entry)));
                    } catch (org.fuin.porelo.app.DefaultProject.Refused ex) {
                        throw new VarlinkException(
                                INTERFACE + ".Failed",
                                Map.of("message", String.valueOf(ex.getMessage())));
                    }
                });

        server.method(
                "ProposeProject",
                (parameters, replies) -> {
                    if (!replies.streaming()) {
                        throw new VarlinkException(
                                INTERFACE + ".StreamRequired", Map.of("method", "ProposeProject"));
                    }
                    final Map<String, Object> proposal =
                            proposing(
                                    () -> {
                                        try {
                                            return proposals(context)
                                                    .propose(
                                                            text(parameters, "project"),
                                                            text(parameters, "repository"),
                                                            done -> {
                                                                try {
                                                                    replies.more(
                                                                            Map.of(
                                                                                    "adapter",
                                                                                    done.asMap()));
                                                                } catch (java.io.IOException ex) {
                                                                    throw new java.io
                                                                            .UncheckedIOException(
                                                                            ex);
                                                                }
                                                            });
                                        } catch (java.io.UncheckedIOException ex) {
                                            throw ex.getCause();
                                        }
                                    });
                    replies.last(Map.of("proposal", proposal));
                });

        server.method(
                "TakeProposal",
                (parameters, replies) -> {
                    final org.fuin.porelo.app.DefaultProposal.Choice choice;
                    try {
                        choice =
                                org.fuin.porelo.app.DefaultProposal.Choice.valueOf(
                                        text(parameters, "choice"));
                    } catch (IllegalArgumentException ex) {
                        throw new VarlinkException(
                                "org.varlink.service.InvalidParameter",
                                Map.of("parameter", "choice"));
                    }
                    final org.fuin.porelo.app.DefaultProposal.Taken taken =
                            proposing(
                                    () ->
                                            proposals(context)
                                                    .take(
                                                            text(parameters, "project"),
                                                            text(parameters, "repository"),
                                                            choice));
                    final Map<String, Object> reply = new LinkedHashMap<>();
                    reply.put("repository", defaultRow(context, taken.entry()));
                    if (taken.draft() != null) {
                        reply.put("draft", taken.draft());
                    }
                    replies.last(reply);
                });

        server.method(
                "RemoveFromDefault",
                (parameters, replies) -> {
                    final List<org.fuin.porelo.app.LeftDeployKeys.Key> forgotten =
                            new org.fuin.porelo.app.DefaultProject(context)
                                    .remove(text(parameters, "name"));
                    replies.last(
                            Map.of(
                                    "removed",
                                    forgotten != null,
                                    "keys",
                                    forgotten == null
                                            ? List.of()
                                            : forgotten.stream()
                                                    .map(
                                                            org.fuin.porelo.app.LeftDeployKeys.Key
                                                                    ::asMap)
                                                    .toList()));
                });

        server.method(
                "NamedDefaultRepositories",
                (parameters, replies) ->
                        replies.last(
                                Map.of(
                                        "repositories",
                                        org.fuin.porelo.app.DefaultNamed.all(context).stream()
                                                .map(org.fuin.porelo.app.DefaultNamed.Named::asMap)
                                                .toList())));

        server.method(
                "LeaveDefault",
                (parameters, replies) ->
                        replies.last(leaveDefault(context, inventory, parameters)));

        server.method(
                "Prune",
                (parameters, replies) ->
                        replies.last(
                                new org.fuin.porelo.app.Prune(context)
                                        .run(
                                                flag(parameters, "apply"),
                                                flag(parameters, "includingWork"))
                                        .asMap()));

        // Everything of a project, or of the account, in one step: the same code as 'porelo clear',
        // so the terminal and an interface clear the same things and answer them in the same words.
        server.method(
                "Clear",
                (parameters, replies) -> {
                    final String project = empty(parameters, "project");
                    final org.fuin.porelo.app.Clearing clearing =
                            new org.fuin.porelo.app.Clearing(context);
                    final org.fuin.porelo.app.Clearing.Result result =
                            project == null
                                    ? clearing.account(
                                            flag(parameters, "dryRun"), flag(parameters, "force"))
                                    : clearing.project(
                                            project,
                                            flag(parameters, "dryRun"),
                                            flag(parameters, "force"));
                    replies.last(result.asMap());
                });

        server.method(
                "RemoveProject",
                (parameters, replies) -> replies.last(removeProject(context, parameters)));

        // Tasks1 retains retired methods so existing clients receive a named refusal.
        server.method(
                "DeployKey",
                (parameters, replies) -> {
                    final String host = deployKeyHost(context, parameters);
                    throw new VarlinkException(
                            INTERFACE + ".DeployKeyRefused",
                            Map.of(
                                    "message",
                                    "Porelo makes no deploy keys; git reaches the repository with your own ssh key (an ssh address) or your token stored as git.token."
                                            + host
                                            + " (an https address)"));
                });

        server.method(
                "ProjectFileSchema",
                (parameters, replies) ->
                        replies.last(
                                Map.of(
                                        "sections",
                                                org.fuin.porelo.core.project.ProjectReader.schema(),
                                        "open",
                                                org.fuin.porelo.core.project.ProjectReader
                                                        .openSections(),
                                        "keys",
                                                org.fuin.porelo.core.project.ProjectReader.keys()
                                                        .stream()
                                                        .map(
                                                                org.fuin.porelo.core.project
                                                                                .ProjectKey
                                                                        ::asMap)
                                                        .toList())));

        // What a start that names no model would run on, per provider, so a client shows it first.
        server.method(
                "ProviderModels",
                (parameters, replies) -> {
                    final java.nio.file.Path file = projectFile(parameters, context);
                    final List<org.fuin.porelo.app.ModelChoice.ProviderModel> models;
                    try {
                        models =
                                org.fuin.porelo.app.ModelChoice.defaults(
                                        context, file, empty(parameters, "repository"));
                    } catch (final org.fuin.porelo.core.project.ProjectException ex) {
                        throw new VarlinkException(
                                INTERFACE + ".Failed",
                                Map.of("message", String.valueOf(ex.getMessage())));
                    }
                    replies.last(
                            Map.of(
                                    "models",
                                    models.stream()
                                            .map(
                                                    org.fuin.porelo.app.ModelChoice.ProviderModel
                                                            ::asMap)
                                            .toList()));
                });

        server.method(
                "CheckProjectFile",
                (parameters, replies) ->
                        // Which methods an MCP endpoint may carry only the daemon knows.
                        replies.last(
                                McpTaskSockets.checked(
                                        org.fuin.porelo.app.ProjectFileCheck.check(
                                                        context, text(parameters, "text"))
                                                .asMap(),
                                        text(parameters, "text"))));
    }

    private static String deployKeyHost(
            final PoreloContext context, final Map<String, Object> parameters) {
        String upstream = text(parameters, "upstream");
        final String name = text(parameters, "project");
        try {
            final org.fuin.porelo.core.project.Project project = GateSupport.byName(context, name);
            final org.fuin.porelo.core.project.Repository repository =
                    GateSupport.repository(project, empty(parameters, "repository"));
            if (repository.upstream() != null) {
                upstream = repository.upstream();
            } else if (repository.name().equals(project.name())) {
                final org.fuin.porelo.app.OrganizationProjects.Placed placed =
                        org.fuin.porelo.app.OrganizationProjects.of(context).find(name);
                if (placed != null) {
                    upstream = placed.address();
                }
            }
        } catch (RuntimeException | java.io.IOException ex) {
            // Retirement refuses even an unknown project or an unreadable record.
        }
        final String host = org.fuin.porelo.app.GitCredentialNames.hostOf(upstream);
        return host == null ? "<host>" : host;
    }

    /**
     * Returns the keys a project's backups are recorded under: its own, then each of its
     * repositories'.
     *
     * @param context The machine.
     * @param project The project's name.
     * @return The keys; the project's own alone when its file cannot be read.
     */
    private static List<String> backupKeys(final PoreloContext context, final String project) {
        final List<String> keys = new ArrayList<>(List.of(project));
        try {
            for (final org.fuin.porelo.core.project.Repository repository :
                    GateSupport.byName(context, project).allRepositories()) {
                final String key = GateSupport.recordKey(project, repository.name());
                if (!keys.contains(key)) {
                    keys.add(key);
                }
            }
        } catch (RuntimeException ex) {
            // A project whose file cannot be read still has the backups recorded under its own
            // name.
        }
        return keys;
    }

    /**
     * Reads the host a call names, on its port, as every answer names it.
     *
     * @param parameters What the call carried.
     * @return {@code host}, or {@code host:port} off port 22.
     * @throws VarlinkException {@code InvalidParameter} for a value that is no host.
     */
    private static String hostName(final Map<String, Object> parameters) {
        try {
            return org.fuin.porelo.app.HostKeys.Address.of(address(parameters, "host")).name();
        } catch (IllegalArgumentException ex) {
            throw new VarlinkException(
                    "org.varlink.service.InvalidParameter", Map.of("parameter", "host"));
        }
    }

    /**
     * Takes a repository out of 'default' on the person's word, refusing one a task of 'default'
     * still works on and a name 'default' does not have.
     */
    static Map<String, Object> leaveDefault(
            final PoreloContext context,
            final TaskInventory inventory,
            final Map<String, Object> parameters) {
        final String name = text(parameters, "name");
        final org.fuin.porelo.app.DefaultNamed.Removal removal =
                org.fuin.porelo.app.DefaultNamed.remove(context, inventory.tasks(), name);
        return switch (removal) {
            case org.fuin.porelo.app.DefaultNamed.Removal.Removed removed -> {
                final Map<String, Object> row = new LinkedHashMap<>(removed.entry().asMap());
                final org.fuin.porelo.app.DefaultNamed.@org.jspecify.annotations.Nullable Named
                        named = removed.named();
                row.put("claimedBy", named == null ? "" : named.project());
                row.put(
                        "proposal",
                        new org.fuin.porelo.app.DefaultProject(context)
                                .proposal(removed.entry())
                                .name());
                yield Map.of(
                        "removed",
                        row,
                        "keys",
                        removed.keys().stream()
                                .map(org.fuin.porelo.app.LeftDeployKeys.Key::asMap)
                                .toList());
            }
            case org.fuin.porelo.app.DefaultNamed.Removal.InUse used ->
                    throw new VarlinkException(
                            INTERFACE + ".DefaultRepositoryInUse",
                            Map.of("name", used.repository(), "tasks", used.tasks()));
            case org.fuin.porelo.app.DefaultNamed.Removal.NotThere missing ->
                    throw new VarlinkException(
                            INTERFACE + ".NoSuchDefaultRepository",
                            Map.of("name", missing.repository()));
        };
    }

    /** Removes a project of this machine alone, refusing the organization's and the built-in. */
    static Map<String, Object> removeProject(PoreloContext context, Map<String, Object> parameters)
            throws java.io.IOException {
        final String name = text(parameters, "name");
        if (org.fuin.porelo.app.DefaultProject.is(name)
                || org.fuin.porelo.app.OrganizationProjects.of(context).organizations(name)) {
            throw new VarlinkException(
                    INTERFACE + ".NotAllowed",
                    Map.of(
                            "message",
                            org.fuin.porelo.app.DefaultProject.is(name)
                                    ? "'" + name + "' is always there and is never removed"
                                    : name + " is the organization's, and goes with it"));
        }
        // Read before anything goes: which of this machine's keys the forge holds for
        // it is answered below.
        org.fuin.porelo.core.project.Project known = null;
        try {
            known = org.fuin.porelo.app.GateSupport.byName(context, name);
        } catch (RuntimeException ex) {
            // A project whose file cannot be read has no keys this machine can name.
        }
        // The same refusal 'projects delete' gives, through the same code: a mirror may
        // hold work nobody reviewed, and this must not be the quiet way to destroy it.
        final org.fuin.porelo.app.ProjectDeletion.Result deleted =
                new org.fuin.porelo.app.ProjectDeletion(context)
                        .delete(name, flag(parameters, "dryRun"), flag(parameters, "force"));
        if (deleted.outcome() == org.fuin.porelo.app.ProjectDeletion.Outcome.BUILT_IN) {
            throw new VarlinkException(
                    INTERFACE + ".Failed", Map.of("message", String.valueOf(deleted.detail())));
        }
        if (deleted.outcome() == org.fuin.porelo.app.ProjectDeletion.Outcome.NO_SUCH_PROJECT) {
            throw new VarlinkException(INTERFACE + ".NoSuchProject", Map.of("project", name));
        }
        final boolean refused =
                deleted.outcome() == org.fuin.porelo.app.ProjectDeletion.Outcome.HOLDS_WORK
                        || deleted.outcome()
                                == org.fuin.porelo.app.ProjectDeletion.Outcome.TASKS_RUNNING
                        || deleted.outcome() == org.fuin.porelo.app.ProjectDeletion.Outcome.FAILED;
        final List<Map<String, Object>> keys = new java.util.ArrayList<>();
        if (!refused
                && deleted.outcome() != org.fuin.porelo.app.ProjectDeletion.Outcome.PREVIEWED) {
            // This machine's deploy keys for it go from the vault; what they were is
            // answered, so whoever registered them at the forge removes them there.
            if (known != null) {
                org.fuin.porelo.app.LeftDeployKeys.forget(context, known)
                        .forEach(key -> keys.add(key.asMap()));
            }
        }
        return Map.of(
                "outcome",
                deleted.outcome().name(),
                "keys",
                keys,
                "signer",
                messageKey(context),
                "unreviewed",
                deleted.unreviewed(),
                "running",
                deleted.running(),
                // What goes and what is deliberately left alone, filled for a
                // refusal too, so the cost stands beside the reason. The call
                // this replaced answered both, and a removal that cannot say
                // what it takes is weaker than the one it replaced.
                "removes",
                deleted.removes().stream()
                        .map(
                                removal ->
                                        Map.<String, Object>of(
                                                "kind", removal.kind(), "what", removal.what()))
                        .toList(),
                "keeps",
                deleted.keeps(),
                "detail",
                deleted.detail() == null ? "" : deleted.detail());
    }
}
