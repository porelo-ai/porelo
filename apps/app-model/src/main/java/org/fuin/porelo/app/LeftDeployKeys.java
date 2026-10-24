package org.fuin.porelo.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.core.credential.CredentialRegistry;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.core.project.Repository;
import org.fuin.porelo.vault.VaultEntry;
import org.jspecify.annotations.Nullable;

/** Keys made by earlier versions, retained only to name and forget them when clearing. */
public final class LeftDeployKeys {

    /**
     * A deploy key, as a person or an interface registers it.
     *
     * @param entry Its vault entry.
     * @param repository The repository it is for.
     * @param upstream That repository's upstream.
     * @param publicKey The public half, {@code ssh-ed25519 AAAA... comment}.
     * @param fingerprint Its fingerprint, {@code SHA256:...}.
     * @param title What the forge should call it: {@code porelo <machine> <project>/<repository>}.
     * @param writeAccess Whether it is to be registered with write access.
     * @param made Always {@code false} for a key made by an earlier version.
     */
    public record Key(
            String entry,
            String repository,
            String upstream,
            String publicKey,
            String fingerprint,
            String title,
            boolean writeAccess,
            boolean made) {

        /**
         * Returns it as plain values, for a wire.
         *
         * @return The key.
         */
        public Map<String, Object> asMap() {
            final Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("entry", entry);
            map.put("repository", repository);
            map.put("upstream", upstream);
            map.put("publicKey", publicKey);
            map.put("fingerprint", fingerprint);
            map.put("title", title);
            map.put("writeAccess", writeAccess);
            map.put("made", made);
            return map;
        }
    }

    private LeftDeployKeys() {}

    /**
     * Forgets this machine's deploy keys for a project: their vault entries and declarations. What
     * they were is answered, so whoever registered them at the forge removes them there.
     *
     * @param context Where the vault and the declarations are.
     * @param project The project.
     * @return The keys that were forgotten, by title and fingerprint.
     */
    public static List<Key> forget(PoreloContext context, Project project) {
        return forget(context, project, null);
    }

    /**
     * Returns this machine's deploy keys for a project, as {@link #forget(PoreloContext, Project)}
     * would answer them, and forgets nothing: what a dry run of a clearing names for a person to
     * remove at the forge.
     *
     * @param context Where the vault is.
     * @param project The project.
     * @return The keys the vault holds for it; none when it is locked or there is none.
     */
    public static List<Key> held(PoreloContext context, Project project) {
        final List<Key> held = new ArrayList<>();
        final java.util.Optional<org.fuin.porelo.vault.VaultFile.Opener> opener = context.opener();
        if (opener.isEmpty() || !context.vault().exists()) {
            return held;
        }
        final String machine = "porelo@" + MachineName.hostName();
        try {
            final Map<String, VaultEntry> all = context.vault().read(opener.get());
            for (final Repository repository : repositories(project)) {
                final VaultEntry kept =
                        kept(all, project.name(), repository.name(), repository.upstream());
                if (kept == null) {
                    continue;
                }
                final String entry =
                        all.get(entry(project.name(), repository.name())) == kept
                                ? entry(project.name(), repository.name())
                                : legacy(project.name(), repository.name());
                final String line =
                        org.fuin.porelo.vault.StoredKey.of(kept.value(), machine)
                                .authorizedKeysLine();
                final String fingerprint = SignedBy.fingerprintOf(line);
                held.add(
                        new Key(
                                entry,
                                repository.name(),
                                kept.settings().getOrDefault("upstream", ""),
                                line,
                                fingerprint == null ? "" : fingerprint,
                                "porelo "
                                        + MachineName.hostName()
                                        + " "
                                        + project.name()
                                        + "/"
                                        + repository.name(),
                                !repository.name().equals(project.name()),
                                false));
            }
        } catch (org.fuin.porelo.vault.VaultException ex) {
            return List.of();
        }
        return held;
    }

    /**
     * Forgets this machine's deploy key for one repository of a project - one taken out of {@code
     * default}, say - and answers it, so whoever registered it at the forge removes it there.
     *
     * @param context Where the vault and the declarations are.
     * @param project The project.
     * @param only The repository, or {@code null} for all of them.
     * @return The keys that were forgotten.
     */
    public static List<Key> forget(PoreloContext context, Project project, @Nullable String only) {
        final List<Key> forgotten = new ArrayList<>();
        final java.util.Optional<org.fuin.porelo.vault.VaultFile.Opener> opener = context.opener();
        if (opener.isEmpty() || !context.vault().exists()) {
            return forgotten;
        }
        final String machine = "porelo@" + MachineName.hostName();
        try {
            final Map<String, VaultEntry> all = context.vault().read(opener.get());
            final List<String> names = new ArrayList<>();
            for (final Repository repository : repositories(project)) {
                if (only != null && !only.equals(repository.name())) {
                    continue;
                }
                final VaultEntry held =
                        kept(all, project.name(), repository.name(), repository.upstream());
                if (held == null) {
                    continue;
                }
                final String entry =
                        all.get(entry(project.name(), repository.name())) == held
                                ? entry(project.name(), repository.name())
                                : legacy(project.name(), repository.name());
                final String line =
                        org.fuin.porelo.vault.StoredKey.of(held.value(), machine)
                                .authorizedKeysLine();
                final String fingerprint = SignedBy.fingerprintOf(line);
                forgotten.add(
                        new Key(
                                entry,
                                repository.name(),
                                held.settings().getOrDefault("upstream", ""),
                                line,
                                fingerprint == null ? "" : fingerprint,
                                "porelo "
                                        + MachineName.hostName()
                                        + " "
                                        + project.name()
                                        + "/"
                                        + repository.name(),
                                !repository.name().equals(project.name()),
                                false));
                names.add(entry);
                if (repository.upstream() != null) {
                    try {
                        new CredentialDeclarations(context)
                                .forget(CredentialRegistry.normalise(repository.upstream()));
                    } catch (java.io.IOException | RuntimeException ex) {
                        // The record is harmless without its key; the key is what grants access.
                    }
                }
            }
            if (!names.isEmpty()) {
                context.vault()
                        .update(
                                opener.get(),
                                entries -> {
                                    names.forEach(entries::remove);
                                    return entries;
                                });
            }
        } catch (org.fuin.porelo.vault.VaultException ex) {
            // Locked or unreadable: nothing is forgotten, and nothing is said to have been.
            return List.of();
        }
        return forgotten;
    }

    private static List<Repository> repositories(Project project) {
        final List<Repository> all = new ArrayList<>();
        all.add(GateSupport.repository(project, null));
        project.repositories().stream()
                .filter(each -> !each.name().equals(project.name()))
                .forEach(all::add);
        return all;
    }

    /**
     * Returns the vault entry a repository's deploy key is kept under.
     *
     * <p>With a separator neither name can hold: joined with hyphens, 'web' with 'app-api' and
     * 'web-app' with 'api' were one entry - the second was given the first one's key, and clearing
     * 'web' deleted 'web-app''s.
     *
     * @param project The project.
     * @param repository The repository.
     * @return The entry's name.
     */
    static String entry(String project, String repository) {
        return "deploy:" + project + ":" + repository;
    }

    /** The name a key was kept under before, which two projects whose names meet could share. */
    private static String legacy(String project, String repository) {
        return "deploy-" + project + "-" + repository;
    }

    /**
     * Returns the key a repository has: under its own name, or under the old one only when it was
     * made for this repository's upstream - a project whose names only meet another's does not
     * inherit its key.
     */
    private static @Nullable VaultEntry kept(
            Map<String, VaultEntry> all,
            String project,
            String repository,
            @Nullable String upstream) {
        final VaultEntry own = all.get(entry(project, repository));
        if (own != null) {
            return own;
        }
        final VaultEntry old = all.get(legacy(project, repository));
        return old != null && upstream != null && upstream.equals(old.settings().get("upstream"))
                ? old
                : null;
    }
}
