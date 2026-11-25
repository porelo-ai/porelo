package org.fuin.porelo.app;

import java.nio.file.Files;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.fuin.porelo.gate.review.ForgeCredential;
import org.fuin.porelo.gate.review.ForgeFileUnreadable;
import org.fuin.porelo.gate.review.ForgeInstance;
import org.fuin.porelo.gate.review.PlaceUnreadable;
import org.fuin.porelo.gate.review.PushEnd;
import org.fuin.porelo.runtime.ContainerSummary;
import org.fuin.porelo.vault.VaultEntry;

/**
 * A machine's one assembly of a task's review role and of the vault's lending of a forge
 * credential, which the daemon's runs and every answer of what is open read alike. Tasks are named
 * by their container.
 */
public final class MachineReviewRole {

    private MachineReviewRole() {}

    /**
     * Returns the role of this machine's tasks: the role each run recorded, the vault entries its
     * items may go out under, the forge account each entry's token is, and where its last push
     * stands at its gate.
     *
     * @param context The machine.
     * @param forges Its forge file.
     * @param forwarded What a task's gate last forwarded, for an author's pin.
     * @return The role.
     */
    public static TaskReviewRole of(
            final PoreloContext context,
            final ForgeInstanceFile forges,
            final TaskReviewRole.Forwarded forwarded) {
        final GateEnds ends = new GateEnds(context);
        return new TaskReviewRole(
                container -> context.paths().tasks().containerState(container),
                new TaskReviewRole.Entries() {
                    @Override
                    public boolean onHost() {
                        return Files.isRegularFile(context.paths().hostProgram());
                    }

                    @Override
                    public String taskAccount(final String task) {
                        return TaskReviewRole.taskForgeEntry(task);
                    }

                    @Override
                    public Optional<String> projectBuilds(final String task) {
                        return summary(context, task)
                                .map(ContainerSummary::project)
                                .filter(Objects::nonNull)
                                .flatMap(
                                        project -> {
                                            try {
                                                final var builds =
                                                        GateSupport.byName(context, project)
                                                                .builds();
                                                return builds == null
                                                        ? Optional.empty()
                                                        : Optional.of(builds.credential());
                                            } catch (RuntimeException ex) {
                                                return Optional.empty();
                                            }
                                        });
                    }

                    @Override
                    public boolean held(final String entry) {
                        return context.readableCredentials()
                                .map(entries -> entries.containsKey(entry))
                                .orElse(false);
                    }
                },
                forges,
                new ForgeAccounts(
                        (ref, credential) -> {
                            // The adapter of the pull request's forge entry says whose token it is.
                            final Optional<ForgeInstance> found;
                            try {
                                found = forges.of(ref.url());
                            } catch (ForgeFileUnreadable ex) {
                                throw new PlaceUnreadable(
                                        PlaceUnreadable.Reason.FORGE_FILE_UNREADABLE,
                                        "this machine's forge file cannot be read");
                            }
                            final String adapter =
                                    found.filter(each -> each.unreadable() == null)
                                            .map(ForgeInstance::adapter)
                                            .orElse(null);
                            if (adapter == null) {
                                throw new PlaceUnreadable(
                                        PlaceUnreadable.Reason.UNKNOWN_FORGE,
                                        "this machine knows no readable forge at " + ref.forge());
                            }
                            return AdapterForgeReview.of(context, adapter).facts(ref, credential);
                        },
                        entry -> lend(context, entry)),
                task -> {
                    final Optional<String> id =
                            summary(context, task)
                                    .map(ContainerSummary::id)
                                    .filter(Objects::nonNull);
                    final Optional<PushEnd> end =
                            id.isEmpty() ? Optional.empty() : ends.store().read(id.get());
                    return end;
                },
                forwarded);
    }

    /**
     * Lends the credential a vault entry holds, for one call, read from the vault each time.
     *
     * @param context The machine.
     * @param entry The entry's name.
     * @return The credential.
     * @throws PlaceUnreadable As {@code MissingForgeToken} when the vault is shut or holds no such
     *     entry.
     */
    public static ForgeCredential lend(final PoreloContext context, final String entry)
            throws PlaceUnreadable {
        final Optional<Map<String, VaultEntry>> held = context.readableCredentials();
        final VaultEntry found = held.map(entries -> entries.get(entry)).orElse(null);
        if (found == null || found.value().isBlank()) {
            throw new PlaceUnreadable(
                    PlaceUnreadable.Reason.MISSING_FORGE_TOKEN,
                    held.isEmpty()
                            ? "the vault is shut, so '" + entry + "' cannot be lent"
                            : "the vault holds no '" + entry + "'");
        }
        return new ForgeCredential(entry, found.value());
    }

    private static Optional<ContainerSummary> summary(
            final PoreloContext context, final String container) {
        return context.podman().taskCandidates().stream()
                .filter(each -> container.equals(each.name()))
                .findFirst();
    }
}
