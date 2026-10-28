package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.project.Project;
import org.jspecify.annotations.Nullable;

/**
 * Clears a project from this machine, or the whole account, in one step, and answers what it did.
 *
 * <p>Every piece existed on its own - removing a task, unfollowing, forgetting a deploy key,
 * clearing a transport - and a person had to know each and its order; clearing a test machine by
 * hand still left deploy keys at a forge (2026-10-02: "When I am done, I must be able to clear
 * everything on the server very simply").
 *
 * <p><strong>What only a person's credentials can remove is answered, never attempted:</strong> a
 * deploy key at a forge and this machine's line in a project's {@code machine-signers}. The machine
 * holds no forge token and not the person's signing key; whoever has them - the interface, or a
 * person at the forge - removes those.
 *
 * <p>The terminal and the daemon both call this, so the two cannot clear different things.
 */
public final class Clearing {

    /** What became of one thing. */
    public enum Status {

        /** It was removed. */
        REMOVED,

        /** A dry run: it would be removed. */
        WOULD_REMOVE,

        /** It could not be removed; {@link Item#why()} says why. */
        NOT_REMOVED,

        /** Only a person's credentials can remove it; it is named for them. */
        FOR_A_PERSON
    }

    /**
     * One thing cleared, or not.
     *
     * @param kind What sort of thing: {@code task}, {@code mirror}, {@code follow}, {@code
     *     conversation}, {@code deploy key}, {@code signer} and the like.
     * @param what Which one, for a person.
     * @param status What became of it.
     * @param why Why, where it was not removed; "" otherwise.
     * @param transport The transport whose account it is, for a reader that acts on it; "" for
     *     anything not a transport's.
     * @param project The project whose account it is; "" for anything not a project's account.
     */
    public record Item(
            String kind, String what, Status status, String why, String transport, String project) {

        /**
         * Constructor for anything that is not a transport's account of a project.
         *
         * @param kind What sort of thing.
         * @param what Which one, for a person.
         * @param status What became of it.
         * @param why Why, where it was not removed; "" otherwise.
         */
        public Item(String kind, String what, Status status, String why) {
            this(kind, what, status, why, "", "");
        }

        /**
         * Returns it as plain values, for a wire.
         *
         * @return The item.
         */
        public Map<String, Object> asMap() {
            return Map.of(
                    "kind",
                    kind,
                    "what",
                    what,
                    "status",
                    status.name(),
                    "why",
                    why,
                    "transport",
                    transport,
                    "project",
                    project);
        }
    }

    /** Why a clearing kept the vault, as a reader decides on it; {@link #NONE} when it did not. */
    public enum Kept {

        /** Nothing kept it. */
        NONE,

        /** A running task holds a token from it; stopping the task is the only way on. */
        TASKS,

        /** It is locked, so what it holds cannot be named; {@code --force} clears it unread. */
        LOCKED,

        /**
         * Only a transport could not clear an account it keeps; {@code --force} clears it anyway.
         */
        TRANSPORTS;

        /**
         * Returns the name a wire carries: "" for {@link #NONE}, else the lower-case name.
         *
         * @return The name.
         */
        public String wire() {
            return this == NONE ? "" : name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * What a clearing did.
     *
     * @param items Each thing, in the order it was handled.
     * @param keys The deploy keys this machine had at forges, for a person to remove there.
     * @param signer This machine's {@code allowed_signers} line, for a person to remove from each
     *     project that lists it; "" when this machine has no message key.
     * @param refused Whether it stopped before removing anything, because work nobody reviewed or a
     *     running task would go; {@code --force} clears it all the same.
     * @param kept Why a vault clearing kept the vault; {@link Kept#NONE} otherwise.
     */
    public record Result(
            List<Item> items,
            List<LeftDeployKeys.Key> keys,
            String signer,
            boolean refused,
            Kept kept) {

        /**
         * Constructor for a clearing that names no reason of its own beside {@code refused}.
         *
         * @param items Each thing, in the order it was handled.
         * @param keys The deploy keys this machine had at forges.
         * @param signer This machine's {@code allowed_signers} line, or "".
         * @param refused Whether it stopped before removing anything.
         */
        public Result(
                List<Item> items, List<LeftDeployKeys.Key> keys, String signer, boolean refused) {
            this(items, keys, signer, refused, Kept.NONE);
        }

        /**
         * Returns it as plain values, for a wire.
         *
         * @return The result.
         */
        public Map<String, Object> asMap() {
            return Map.of(
                    "items",
                    items.stream().map(Item::asMap).toList(),
                    "keys",
                    keys.stream().map(LeftDeployKeys.Key::asMap).toList(),
                    "signer",
                    signer,
                    "refused",
                    refused);
        }
    }

    private final PoreloContext context;

    /**
     * Constructor.
     *
     * @param context The machine.
     */
    public Clearing(final PoreloContext context) {
        this.context = context;
    }

    /**
     * Clears one project from this machine.
     *
     * @param name The project.
     * @param dryRun Whether to only say what would go.
     * @param force Whether to clear work nobody reviewed and running tasks too.
     * @return What it did; no items for a project this machine has nothing of.
     */
    public Result project(final String name, final boolean dryRun, final boolean force) {
        final List<Item> items = new ArrayList<>();
        @Nullable Project known = null;
        try {
            known = GateSupport.byName(context, name);
        } catch (final RuntimeException ex) {
            // A project whose file cannot be read has no conversation or keys this machine can
            // name.
        }
        final FollowedProjects followed =
                new FollowedProjects(context.paths().projects().followed());
        final @Nullable String url = followedUrl(followed, name);
        final ProjectDeletion.Result deleted =
                new ProjectDeletion(context).unfollow(name, dryRun, force);
        boolean refusedListing = false;
        switch (deleted.outcome()) {
            case BUILT_IN -> {
                items.add(
                        new Item(
                                "project",
                                name,
                                Status.NOT_REMOVED,
                                "it is built in; its tasks go with 'porelo clear', the project stays"));
                return new Result(items, List.of(), "", true);
            }
            case NO_SUCH_PROJECT -> {
                if (url == null) {
                    return new Result(List.of(), List.of(), "", false);
                }
            }
            case HOLDS_WORK, TASKS_RUNNING -> {
                items.add(
                        new Item(
                                "project",
                                name,
                                Status.NOT_REMOVED,
                                String.valueOf(deleted.detail())
                                        + "; nothing was removed - clear it with --force to remove that too"));
                if (!dryRun) {
                    return new Result(items, List.of(), "", true);
                }
                // Listed all the same: what --force would remove is what a person decides on.
                refusedListing = true;
            }
            case FAILED -> {
                if (url != null && noLongerFollowed(followed, name)) {
                    items.add(new Item("follow", url, Status.REMOVED, ""));
                }
                // Following has stopped, but a failed removal must keep the conversation and keys.
                items.add(
                        new Item(
                                "project",
                                name,
                                Status.NOT_REMOVED,
                                String.valueOf(deleted.detail())));
                return new Result(List.copyOf(items), List.of(), "", false);
            }
            default -> {
                // DELETED or PREVIEWED: what it removed, or would.
            }
        }
        for (final ProjectDeletion.Removal removal : deleted.removes()) {
            items.add(
                    new Item(
                            removal.kind(),
                            removal.what(),
                            dryRun ? Status.WOULD_REMOVE : Status.REMOVED,
                            ""));
        }
        if (url != null) {
            if (dryRun) {
                items.add(new Item("follow", url, Status.WOULD_REMOVE, ""));
            } else {
                items.add(new Item("follow", url, Status.REMOVED, ""));
            }
        }
        if (known == null) {
            return new Result(List.copyOf(items), List.of(), "", refusedListing);
        }
        conversations(known, dryRun, items);
        final List<LeftDeployKeys.Key> keys = keys(known, dryRun, items);
        final String signer = signer(name, items);
        return new Result(List.copyOf(items), keys, signer, refusedListing);
    }

    /**
     * Clears the whole account: every project on it, the built-in project's tasks, then what each
     * transport keeps for the account - a homeserver it runs, say. The vault, the person's keys and
     * the installed packages stay: clearing is not uninstalling.
     *
     * @param dryRun Whether to only say what would go.
     * @param force Whether to clear work nobody reviewed and running tasks too.
     * @return What it did; no items for an account with nothing to clear.
     */
    public Result account(final boolean dryRun, final boolean force) {
        // All or nothing: cleared project by project, it stopped at the first that held work, and
        // the ones before it were already gone. Without force, the listing goes first; a refusal in
        // it clears nothing.
        if (!dryRun && !force) {
            final Result listed = account(true, false);
            if (listed.refused()) {
                return listed;
            }
        }
        final List<Item> items = new ArrayList<>();
        final List<LeftDeployKeys.Key> keys = new ArrayList<>();
        String signer = "";
        boolean refused = false;
        final java.util.Set<String> names = new java.util.TreeSet<>();
        new ProjectInventory(context).projects().forEach(summary -> names.add(summary.name()));
        try {
            new FollowedProjects(context.paths().projects().followed())
                    .all()
                    .forEach(each -> names.add(each.name()));
        } catch (final IOException ex) {
            items.add(
                    new Item(
                            "follow",
                            "the projects this account follows",
                            Status.NOT_REMOVED,
                            "their list cannot be read: " + ex.getMessage()));
        }
        names.remove(DefaultProject.NAME);
        for (final String name : names) {
            final Result one = project(name, dryRun, force);
            items.addAll(one.items());
            keys.addAll(one.keys());
            signer = one.signer().isEmpty() ? signer : one.signer();
            refused |= one.refused();
        }
        if (refused && !dryRun) {
            return new Result(List.copyOf(items), List.copyOf(keys), signer, true);
        }
        for (final TaskInventory.Task task : new TaskInventory(context).tasks()) {
            if (!DefaultProject.NAME.equals(task.project())) {
                continue;
            }
            if (dryRun) {
                items.add(new Item("task", task.name(), Status.WOULD_REMOVE, ""));
                continue;
            }
            final TaskControl.Stopped stopped =
                    new TaskControl(context).remove(task.name(), false, force);
            items.add(
                    new Item(
                            "task",
                            task.name(),
                            stopped.removed() ? Status.REMOVED : Status.NOT_REMOVED,
                            stopped.removed() ? "" : String.valueOf(stopped.detail())));
        }
        if (!dryRun && items.stream().anyMatch(item -> item.status() == Status.NOT_REMOVED)) {
            items.add(
                    new Item(
                            "transport",
                            "account-wide transport cleanup",
                            Status.NOT_REMOVED,
                            "a project or task removal failed; kept so surviving tasks can be found and talk"));
        } else {
            transports(dryRun, items);
        }
        return new Result(List.copyOf(items), List.copyOf(keys), signer, refused);
    }

    /**
     * Clears what each transport keeps for the account, then what this machine kept of any
     * conversation: a project cleared before, or by hand, left its files behind, and the account
     * was said to have nothing to clear.
     */
    void transports(final boolean dryRun, final List<Item> items) {
        final java.nio.file.Path kept = context.paths().xdg().state().resolve("transport");
        final java.util.Set<String> schemes =
                new java.util.TreeSet<>(
                        context.paths().messaging().transportDirectory().byName().keySet());
        if (Files.isDirectory(kept)) {
            try (java.util.stream.Stream<java.nio.file.Path> found = Files.list(kept)) {
                found.filter(Files::isDirectory)
                        .forEach(each -> schemes.add(each.getFileName().toString()));
            } catch (final IOException ex) {
                items.add(
                        new Item(
                                "transport",
                                kept.toString(),
                                Status.NOT_REMOVED,
                                String.valueOf(ex.getMessage())));
            }
        }
        final TransportLifecycle lifecycle =
                new TransportLifecycle(context, context.paths().messaging().transportDirectory());
        for (final String scheme : schemes) {
            final java.nio.file.Path files = kept.resolve(scheme);
            final boolean installed =
                    context.paths().messaging().transportDirectory().find(scheme) != null;
            if (dryRun) {
                items.add(
                        new Item(
                                "transport",
                                "what the "
                                        + scheme
                                        + " transport and this machine keep for this"
                                        + " account",
                                Status.WOULD_REMOVE,
                                ""));
                continue;
            }
            if (installed) {
                try {
                    lifecycle
                            .clear(scheme, null, Map.of())
                            .forEach(
                                    said ->
                                            items.add(
                                                    new Item(
                                                            "transport",
                                                            said,
                                                            Status.REMOVED,
                                                            "")));
                } catch (final TransportLifecycle.Refused ex) {
                    items.add(
                            new Item(
                                    "transport",
                                    "what the " + scheme + " transport keeps for this account",
                                    Status.NOT_REMOVED,
                                    String.valueOf(ex.getMessage())));
                }
            }
            if (Files.isDirectory(files)) {
                try {
                    AgentStaging.deleteTree(files);
                    items.add(
                            new Item(
                                    "transport",
                                    "what this machine kept of its "
                                            + scheme
                                            + " conversations, "
                                            + files,
                                    Status.REMOVED,
                                    ""));
                } catch (final IOException ex) {
                    items.add(
                            new Item(
                                    "transport",
                                    files.toString(),
                                    Status.NOT_REMOVED,
                                    String.valueOf(ex.getMessage())));
                }
            }
        }
    }

    private void conversations(
            final Project project, final boolean dryRun, final List<Item> items) {
        final TransportLifecycle lifecycle =
                new TransportLifecycle(context, context.paths().messaging().transportDirectory());
        for (final String scheme : project.mail().conversations()) {
            final String what =
                    "the project's " + scheme + " conversation, and what this machine kept for it";
            if (dryRun) {
                items.add(new Item("conversation", what, Status.WOULD_REMOVE, ""));
                continue;
            }
            try {
                final Map<String, Object> settings =
                        project.mail().transports().get(scheme) instanceof Map<?, ?> said
                                ? cast(said)
                                : Map.of();
                lifecycle
                        .clear(scheme, project.name(), settings)
                        .forEach(
                                said ->
                                        items.add(
                                                new Item(
                                                        "conversation", said, Status.REMOVED, "")));
                items.add(new Item("conversation", what, Status.REMOVED, ""));
            } catch (final TransportLifecycle.Refused ex) {
                items.add(
                        new Item(
                                "conversation",
                                what,
                                Status.NOT_REMOVED,
                                String.valueOf(ex.getMessage())));
            }
        }
    }

    private List<LeftDeployKeys.Key> keys(
            final Project project, final boolean dryRun, final List<Item> items) {
        if (context.vault().exists() && context.opener().isEmpty()) {
            // Said as such: a locked vault answered no keys at all, silently, and a person took
            // that for none.
            items.add(
                    new Item(
                            "deploy key",
                            "this machine's deploy keys for " + project.name(),
                            Status.NOT_REMOVED,
                            "the vault is locked, so they can be neither read nor forgotten; 'porelo vault unlock' and clear"
                                    + " again"));
            return List.of();
        }
        final List<LeftDeployKeys.Key> keys =
                dryRun
                        ? LeftDeployKeys.held(context, project)
                        : LeftDeployKeys.forget(context, project);
        for (final LeftDeployKeys.Key key : keys) {
            items.add(
                    new Item(
                            "deploy key",
                            "'" + key.title() + "' " + key.fingerprint() + " on " + key.upstream(),
                            Status.FOR_A_PERSON,
                            "the forge still grants access by it; remove it there"));
        }
        return keys;
    }

    private String signer(final String project, final List<Item> items) {
        if (!Files.isRegularFile(context.paths().messaging().messageKey())) {
            return "";
        }
        final String machine = "porelo@" + MachineName.hostName();
        final String line;
        try {
            line =
                    HostKey.allowedSignersLine(
                            HostKey.loadOrCreate(context.paths().messaging().messageKey(), machine),
                            machine);
        } catch (final IOException ex) {
            items.add(
                    new Item(
                            "signer",
                            machine,
                            Status.NOT_REMOVED,
                            "this machine's message key cannot be read: " + ex.getMessage()));
            return "";
        }
        items.add(
                new Item(
                        "signer",
                        machine + " in " + project + "'s machine-signers",
                        Status.FOR_A_PERSON,
                        "where it is listed, a signed commit in the project's repository removes it"));
        return line;
    }

    private static boolean noLongerFollowed(FollowedProjects followed, String name) {
        try {
            return followed.find(name) == null;
        } catch (IOException | RuntimeException ex) {
            // A failed lookup cannot claim that the follow was removed.
            return false;
        }
    }

    private static @Nullable String followedUrl(
            final FollowedProjects followed, final String name) {
        try {
            final FollowedProjects.Followed one = followed.find(name);
            return one == null ? null : one.url();
        } catch (final IOException | RuntimeException ex) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(final Map<?, ?> said) {
        return (Map<String, Object>) said;
    }
}
