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
import org.jspecify.annotations.Nullable;

/**
 * The daemon's methods for projects, following, backups and clearing.
 *
 * <p>One class per area, so a change to one area's methods is a change to its own file; {@link
 * PoreloDaemon} only says which areas there are.
 */
final class ProjectMethods {

    private ProjectMethods() {}

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

        // Following a project's repository. A repository URL is not a secret, so it may travel over
        // this socket where a credential may not.
        server.method(
                "Following",
                (parameters, replies) -> {
                    replies.last(
                            Map.of(
                                    "projects",
                                    new org.fuin.porelo.app.FollowedProjects(
                                                    context.paths().projects().followed())
                                            .all().stream()
                                                    .map(
                                                            each ->
                                                                    org.fuin.porelo.app
                                                                            .ProjectInventory
                                                                            .followAsMap(
                                                                                    each,
                                                                                    org.fuin.porelo
                                                                                            .app
                                                                                            .PinnedSigners
                                                                                            .of(
                                                                                                    context.paths()
                                                                                                            .projects()
                                                                                                            .configurationSigners())
                                                                                            .getOrDefault(
                                                                                                    each
                                                                                                            .name(),
                                                                                                    java
                                                                                                            .util
                                                                                                            .List
                                                                                                            .of())))
                                                    .toList()));
                });

        server.method(
                "RefreshProjects",
                (parameters, replies) -> {
                    final String name = empty(parameters, "project");
                    final Map<String, org.fuin.porelo.app.Reconcile.Result> done =
                            new org.fuin.porelo.app.ConfigurationWatch(
                                            context, java.time.Duration.ZERO)
                                    .refresh(name);
                    if (done == null) {
                        throw new VarlinkException(
                                INTERFACE + ".NoSuchProject",
                                Map.of("project", String.valueOf(name)));
                    }
                    final Map<String, java.util.List<String>> signers =
                            org.fuin.porelo.app.PinnedSigners.of(
                                    context.paths().projects().configurationSigners());
                    replies.last(
                            Map.of(
                                    "projects",
                                    new org.fuin.porelo.app.FollowedProjects(
                                                    context.paths().projects().followed())
                                            .all().stream()
                                                    .filter(each -> done.containsKey(each.name()))
                                                    .map(
                                                            each ->
                                                                    org.fuin.porelo.app
                                                                            .ProjectInventory
                                                                            .followAsMap(
                                                                                    each,
                                                                                    signers
                                                                                            .getOrDefault(
                                                                                                    each
                                                                                                            .name(),
                                                                                                    java
                                                                                                            .util
                                                                                                            .List
                                                                                                            .of())))
                                                    .toList()));
                });

        server.method(
                "Follow",
                (parameters, replies) -> {
                    final String name = text(parameters, "name");
                    final String url = address(parameters, "url");
                    final @Nullable String signedBy =
                            parameters.get("signedBy") instanceof String said && !said.isBlank()
                                    ? said
                                    : null;
                    // The same class the command line follows with: a follow over the socket took
                    // signedBy and never read it, and refused what the same follow at the terminal
                    // applied.
                    final org.fuin.porelo.app.FollowSignedBy following =
                            new org.fuin.porelo.app.FollowSignedBy(context);
                    final java.util.List<String> signers =
                            parameters.get("signers") instanceof java.util.List<?> given
                                    ? given.stream()
                                            .map(String::valueOf)
                                            .filter(each -> !each.isBlank())
                                            .toList()
                                    : java.util.List.of();
                    try {
                        org.fuin.porelo.app.FollowSignedBy.refuseConflicting(
                                signedBy, signers, flag(parameters, "unverified"));
                    } catch (org.fuin.porelo.app.FollowSignedBy.Refused ex) {
                        throw new VarlinkException(
                                INTERFACE + ".Failed",
                                Map.of("message", String.valueOf(ex.getMessage())));
                    }
                    if (flag(parameters, "dryRun")) {
                        // Records nothing, not even the named key: the whole point is the question
                        // asked BEFORE a project exists here. READY where a real follow would have
                        // applied, and otherwise the same named reason it would have given.
                        final org.fuin.porelo.app.Reconcile.Result would;
                        final java.util.List<String> wouldPin;
                        try {
                            final org.fuin.porelo.app.FollowSignedBy.Answer checked =
                                    signers.isEmpty()
                                            ? following.check(
                                                    name,
                                                    url,
                                                    signedBy,
                                                    flag(parameters, "unverified"))
                                            : following.check(name, url, signers);
                            would = checked.result();
                            final @Nullable String one =
                                    signedBy == null
                                                    || org.fuin.porelo.app.SignedBy.isFingerprint(
                                                            signedBy)
                                            ? null
                                            : org.fuin.porelo.app.SignedBy.fingerprintOf(signedBy);
                            wouldPin =
                                    !checked.signers().isEmpty()
                                            ? checked.signers()
                                            : one == null
                                                    ? java.util.List.of()
                                                    : java.util.List.of(one);
                        } catch (org.fuin.porelo.app.FollowSignedBy.Refused ex) {
                            throw new VarlinkException(
                                    INTERFACE + ".Failed",
                                    Map.of("message", String.valueOf(ex.getMessage())));
                        }
                        final boolean ready =
                                would.outcome() == org.fuin.porelo.app.Reconcile.Outcome.APPLIED
                                        || would.outcome()
                                                == org.fuin.porelo.app.Reconcile.Outcome.UNCHANGED;
                        replies.last(
                                Map.of(
                                        "outcome",
                                        ready ? "READY" : would.outcome().name(),
                                        "commit",
                                        would.commit(),
                                        "detail",
                                        would.detail(),
                                        "refused",
                                        would.refused(),
                                        "signer",
                                        would.signer(),
                                        "needsAPerson",
                                        would.needsAPerson(),
                                        // What to type to store the credential this machine would
                                        // need. Named by the machine, because which entry a follow
                                        // reads is the machine's knowledge and a client must never
                                        // guess it - nor ever carry the secret.
                                        "storeCommand",
                                        would.outcome()
                                                        == org.fuin.porelo.app.Reconcile.Outcome
                                                                .NO_CREDENTIAL
                                                ? org.fuin.porelo.app.FollowCredential.storeCommand(
                                                        url)
                                                : "",
                                        "recorded",
                                        false,
                                        "pinned",
                                        wouldPin));
                        return;
                    }
                    final org.fuin.porelo.app.FollowSignedBy.Answer answer;
                    try {
                        // Once, now: somebody who asked for this wants to know whether it works,
                        // and a refusal at the next tick is one nobody connects to what they did.
                        answer =
                                signers.isEmpty()
                                        ? following.follow(
                                                name,
                                                url,
                                                signedBy,
                                                flag(parameters, "unverified"),
                                                flag(parameters, "acceptRewrite"))
                                        : following.follow(
                                                name,
                                                url,
                                                signers,
                                                flag(parameters, "acceptRewrite"));
                    } catch (org.fuin.porelo.app.FollowSignedBy.Refused ex) {
                        throw new VarlinkException(
                                INTERFACE + ".Failed",
                                Map.of("message", String.valueOf(ex.getMessage())));
                    }
                    final org.fuin.porelo.app.Reconcile.Result result = answer.result();
                    // When the host's key is what stopped it, what it offered goes with the
                    // refusal: a person cannot confirm a fingerprint they have not been shown, and
                    // asking them to run a command to see it would be making them fetch what we
                    // already have.
                    final boolean aboutTheHost =
                            result.outcome()
                                            == org.fuin.porelo.app.Reconcile.Outcome
                                                    .UNKNOWN_HOST_KEY
                                    || result.outcome()
                                            == org.fuin.porelo.app.Reconcile.Outcome
                                                    .HOST_KEY_CHANGED;
                    // The host is answered rather than left to be read off the address again: a
                    // client that re-parsed it would have to know about 'user@host:path', a port,
                    // and an alias in somebody's ssh config - three readings of one thing, two of
                    // them ours already.
                    final String host =
                            aboutTheHost ? org.fuin.porelo.app.HostKeys.nameOf(url) : "";
                    final java.util.List<Map<String, Object>> offered =
                            aboutTheHost
                                    ? org.fuin.porelo.app.HostKeys.offeredBy(context, host).stream()
                                            .map(org.fuin.porelo.app.HostKeys.Offered::asMap)
                                            .toList()
                                    : java.util.List.of();
                    final Map<String, Object> reply =
                            new java.util.LinkedHashMap<>(
                                    Map.of(
                                            "outcome",
                                            result.outcome().name(),
                                            "commit",
                                            result.commit(),
                                            "detail",
                                            result.detail(),
                                            "refused",
                                            result.refused(),
                                            "host",
                                            host,
                                            "hostKeys",
                                            offered,
                                            "signer",
                                            result.signer(),
                                            "needsAPerson",
                                            result.needsAPerson(),
                                            "storeCommand",
                                            result.outcome()
                                                            == org.fuin.porelo.app.Reconcile.Outcome
                                                                    .NO_CREDENTIAL
                                                    ? org.fuin.porelo.app.FollowCredential
                                                            .storeCommand(url)
                                                    : "",
                                            // Whether anything was written. False for a first
                                            // follow that was refused: the machine is exactly as it
                                            // was.
                                            "recorded",
                                            answer.recorded()));
                    // Every key this call pinned, by fingerprint, so the interface can tell them in
                    // one place.
                    reply.put(
                            "pinned",
                            !answer.signers().isEmpty()
                                    ? answer.signers()
                                    : answer.pinned().isEmpty()
                                            ? java.util.List.of()
                                            : java.util.List.of(answer.pinned()));
                    replies.last(reply);
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
                            new org.fuin.porelo.app.DefaultProject(context).entries()) {
                        final Map<String, Object> row = new LinkedHashMap<>(entry.asMap());
                        final org.fuin.porelo.app.WorkOrigin.Choice claimed =
                                org.fuin.porelo.app.WorkOrigin.followedNaming(
                                        context, entry.upstream());
                        row.put("claimedBy", claimed == null ? "" : claimed.project());
                        rows.add(row);
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
                        final Map<String, Object> row = new LinkedHashMap<>(entry.asMap());
                        final org.fuin.porelo.app.WorkOrigin.Choice claimed =
                                org.fuin.porelo.app.WorkOrigin.followedNaming(
                                        context, entry.upstream());
                        row.put("claimedBy", claimed == null ? "" : claimed.project());
                        replies.last(Map.of("repository", row));
                    } catch (org.fuin.porelo.app.DefaultProject.Refused ex) {
                        throw new VarlinkException(
                                INTERFACE + ".Failed",
                                Map.of("message", String.valueOf(ex.getMessage())));
                    }
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
                "Unfollow", (parameters, replies) -> replies.last(unfollow(context, parameters)));

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

        server.method(
                "CheckProjectFile",
                (parameters, replies) ->
                        replies.last(
                                org.fuin.porelo.app.ProjectFileCheck.check(
                                                context, text(parameters, "text"))
                                        .asMap()));
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
                final org.fuin.porelo.app.FollowedProjects.Followed followed =
                        new org.fuin.porelo.app.FollowedProjects(
                                        context.paths().projects().followed())
                                .find(name);
                if (followed != null) {
                    upstream = followed.url();
                }
            }
        } catch (RuntimeException | java.io.IOException ex) {
            // Retirement refuses even an unknown project or an unreadable follow record.
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

    /** Removes a followed project only when every task removal succeeded. */
    static Map<String, Object> unfollow(PoreloContext context, Map<String, Object> parameters)
            throws java.io.IOException {
        final org.fuin.porelo.app.FollowedProjects projects =
                new org.fuin.porelo.app.FollowedProjects(context.paths().projects().followed());
        final String name = text(parameters, "name");
        final boolean following = projects.find(name) != null;
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
                        .unfollow(name, flag(parameters, "dryRun"), flag(parameters, "force"));
        // 'NoSuchProject' for a name this machine does not LIST - not for one it lists
        // but does not follow. Those are projects from before following, and answering
        // the error here left an interface offering to clear one and with nothing to
        // show for it, while force would have swept it blind. Found through the
        // interface; it was hit the same wall on a test machine the same hour.
        if (deleted.outcome() == org.fuin.porelo.app.ProjectDeletion.Outcome.BUILT_IN) {
            throw new VarlinkException(
                    INTERFACE + ".Failed", Map.of("message", String.valueOf(deleted.detail())));
        }
        if (!following
                && deleted.outcome()
                        == org.fuin.porelo.app.ProjectDeletion.Outcome.NO_SUCH_PROJECT) {
            throw new VarlinkException(INTERFACE + ".NoSuchProject", Map.of("project", name));
        }
        final boolean refused =
                deleted.outcome() == org.fuin.porelo.app.ProjectDeletion.Outcome.HOLDS_WORK
                        || deleted.outcome()
                                == org.fuin.porelo.app.ProjectDeletion.Outcome.TASKS_RUNNING
                        || deleted.outcome() == org.fuin.porelo.app.ProjectDeletion.Outcome.FAILED;
        final List<Map<String, Object>> keys = new java.util.ArrayList<>();
        if (following
                && !refused
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
                // Whether this account was following it at all. A client
                // drawing the confirmation says "stop following and remove" or
                // just "remove" from this, rather than guessing from the
                // project it asked about.
                "following",
                following,
                "detail",
                deleted.detail() == null ? "" : deleted.detail());
    }
}
