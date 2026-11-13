package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import org.jspecify.annotations.Nullable;

/**
 * Reports what reads a stored vault entry and warns when its name or kind cannot serve that use.
 */
final class VaultEntryUse {

    private VaultEntryUse() {}

    /**
     * Says what a name nothing asks for yet is: an agent's name stored by mistake, or a name that
     * waits for whatever will ask for it.
     *
     * @param name The name given.
     * @param providers The providers declared here.
     * @param agents The agents installed here.
     * @return One sentence: the warning for an agent's name, without the "porelo: " prefix; what
     *     may ask for any other name, to follow "unused ", never "used for ", which a reader takes
     *     for a use.
     */
    static String unknownName(
            final String name,
            final java.util.Set<String> providers,
            final java.util.Set<String> agents) {
        final String declared =
                providers.isEmpty()
                        ? "none"
                        : String.join(", ", new java.util.TreeSet<>(providers));
        if (agents.contains(name)) {
            // A task running this agent still finds it, as the fallback for vaults written before
            // credentials were keyed by provider - so "nothing will find it" would be untrue.
            return "'"
                    + name
                    + "' is an agent's name, not a provider's. A task running "
                    + name
                    + " still finds this credential, but it belongs under the provider that issued"
                    + " it: store it with 'porelo vault put <provider>' (declared: "
                    + declared
                    + "; 'porelo providers' shows which agent uses which), then 'porelo vault"
                    + " remove "
                    + name
                    + "'";
        }
        // Not a mistake: a credential is commonly stored before the project or the provider that
        // asks for it. Said once, so a typo is findable among the providers named.
        return "until something asks for it: a provider of that name (declared: "
                + declared
                + "), a project's builds.credential or credentials, or a declaration with --vault "
                + name;
    }

    /**
     * What the declarations make of a vault entry.
     *
     * @param matches The destinations whose declaration reads it, in the order they are declared.
     * @param misfit Why what was stored does not fit one of them, or {@code null} when it does.
     */
    record Use(java.util.List<String> matches, @Nullable String misfit) {}

    /**
     * Finds the declarations that read a vault entry, and whether what was stored fits them.
     *
     * @param name The entry.
     * @param type What was stored - {@code ssh-key} for a private key - or {@code null} when not
     *     stated.
     * @param declared Every declaration on this machine.
     * @return The destinations, empty when none reads it.
     */
    static Use useOf(
            final String name,
            final @Nullable String type,
            final java.util.Collection<org.fuin.porelo.core.credential.Credential> declared) {
        return useOf(name, type, declared, java.util.Map.of());
    }

    /**
     * Finds the declarations and the projects that read a vault entry, and whether what was stored
     * fits them.
     *
     * @param name The entry.
     * @param type What was stored - {@code ssh-key} for a private key - or {@code null} when not
     *     stated.
     * @param declared Every declaration on this machine.
     * @param asked What the projects here ask for, by entry, as {@link #askedByProjects} says it.
     * @return The destinations, the declarations' first, empty when none reads it.
     */
    static Use useOf(
            final String name,
            final @Nullable String type,
            final java.util.Collection<org.fuin.porelo.core.credential.Credential> declared,
            final java.util.Map<String, java.util.List<String>> asked) {
        final java.util.List<String> matches = new java.util.ArrayList<>();
        String misfit = null;
        for (final org.fuin.porelo.core.credential.Credential credential : declared) {
            if (credential.source() != org.fuin.porelo.core.credential.Credential.Source.VAULT
                    || !credential.id().equals(name)) {
                continue;
            }
            matches.add(credential.match());
            final boolean wantsKey =
                    credential.kind() == org.fuin.porelo.core.credential.Credential.Kind.SSH_KEY;
            final boolean isKey = "ssh-key".equals(type);
            // Said now rather than at a start days later, which refuses it as unusable. A private
            // key is always recognised, so one stated as nothing is no key; a token is not, so no
            // kind stated is no sign of a misfit there.
            if (misfit == null && wantsKey != isKey && (type != null || wantsKey)) {
                misfit =
                        credential.match()
                                + " is declared to take "
                                + (wantsKey
                                        ? "an ssh key"
                                        : "a "
                                                + credential
                                                        .kind()
                                                        .name()
                                                        .toLowerCase(java.util.Locale.ROOT)
                                                        .replace('_', '-'))
                                + ", and what was stored is "
                                + (isKey ? "an ssh key" : type == null ? "no ssh key" : "a " + type)
                                + " - a start will refuse it";
            }
        }
        final java.util.List<String> askedFor = asked.getOrDefault(name, java.util.List.of());
        // What a project asks for is sent in a request, a build reader's to its forge and a
        // credentials: entry's to its destination: never an ssh key.
        if (misfit == null && !askedFor.isEmpty() && "ssh-key".equals(type)) {
            misfit =
                    askedFor.getFirst()
                            + " sends it as a token, and what was stored is an ssh key - it cannot"
                            + " use one";
        }
        matches.addAll(askedFor);
        return new Use(java.util.List.copyOf(matches), misfit);
    }

    /**
     * Returns the vault entries the projects on this machine ask for, with what each is for: a
     * project's {@code builds.credential}, as its file or its host names it, and the entries under
     * its {@code credentials:}.
     *
     * <p>A credential is commonly stored for a placed project's build reader, which no provider and
     * no declaration names: without these, storing it was told nothing would find it (found on
     * 2026-11-04 with {@code builds.credential: github-actions}).
     *
     * @param context The machine.
     * @return By entry name, what reads it, such as {@code sluice-dry's build reader}.
     */
    static java.util.Map<String, java.util.List<String>> askedByProjects(
            final PoreloContext context) {
        final java.util.Map<String, java.util.List<String>> asked = new java.util.LinkedHashMap<>();
        final OrganizationProjects organization = OrganizationProjects.of(context);
        for (final String project : ProjectSource.names(context)) {
            if (DefaultProject.is(project)) {
                // Generated from the repositories added to it: it names no credential of its own.
                continue;
            }
            org.fuin.porelo.core.project.Builds builds = null;
            try {
                final OrganizationProjects.Placed placed = organization.find(project);
                if (placed != null) {
                    builds = placed.builds();
                }
            } catch (final IOException ex) {
                // A record that does not read is the start's to report; here nothing is known.
            }
            try {
                final java.nio.file.Path file = ProjectSource.resolve(context, project).file();
                if (file != null) {
                    final org.fuin.porelo.core.project.Project read =
                            org.fuin.porelo.core.project.ProjectReader.read(file);
                    read.credentials()
                            .forEach(
                                    (entry, destination) ->
                                            asked.computeIfAbsent(
                                                            entry,
                                                            any -> new java.util.ArrayList<>())
                                                    .add(project + "'s " + destination));
                    // The project's own builds win over its host's, as a task wires them.
                    if (read.builds() != null) {
                        builds = read.builds();
                    }
                }
            } catch (final RuntimeException ex) {
                // A file that does not read is the start's to report, as above.
            }
            if (builds != null) {
                asked.computeIfAbsent(builds.credential(), any -> new java.util.ArrayList<>())
                        .add(project + "'s build reader");
            }
        }
        return asked;
    }

    /**
     * Says what reads this credential, or what may ask for it while nothing does yet; warns of an
     * agent's name stored for a provider's, and of a kind that cannot be brokered.
     *
     * <p>Checked here as well as at task start, because storing is where the choice is made and a
     * task may not be run for days. A name nobody asks for yet is said whether or not a kind was
     * given, so a typo is findable; only an agent's name is warned of, the commonest way to get
     * this wrong.
     *
     * @param context The machine whose declarations are checked.
     * @param name The stored entry's name.
     * @param type The stored entry's kind, or {@code null} when unstated.
     * @param out Where the destinations it is used for are said.
     * @param err Where to report.
     */
    static void report(
            final PoreloContext context,
            final String name,
            final @Nullable String type,
            final PrintWriter out,
            final PrintWriter err) {
        // A declaration that reads this entry asks for it by that name: warning that nothing would
        // find it was untrue right after 'credentials declare ... --vault <name>' (found on
        // 2026-10-13).
        java.util.List<org.fuin.porelo.core.credential.Credential> declared;
        try {
            declared = context.credentialRegistry().all();
        } catch (RuntimeException ex) {
            // A registry that cannot be read is the start's to report; here it only means nothing
            // is known to use it.
            declared = java.util.List.of();
        }
        java.util.Map<String, java.util.List<String>> asked;
        try {
            asked = askedByProjects(context);
        } catch (RuntimeException ex) {
            // As with the registry: what cannot be read here only means nothing is known to ask.
            asked = java.util.Map.of();
        }
        final Use use = useOf(name, type, declared, asked);
        if (!use.matches().isEmpty()) {
            out.println("used for  " + String.join(", ", use.matches()));
            out.flush();
            if (use.misfit() != null) {
                err.println("porelo: " + use.misfit());
                err.flush();
            }
        }
        if (GitCredentialNames.isOne(name)
                || name.matches("[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?\\.(central|person)")) {
            // Not a provider's name and not meant to be: these are read by the git commands this
            // machine runs. Warning that "no provider is declared" for one sent people looking for
            // a mistake they had not made.
            return;
        }
        final var provider = context.providers().get(name);
        if (provider == null && !use.matches().isEmpty()) {
            return;
        }
        if (provider == null) {
            // Not refused: a credential may be stored before its provider is declared, and an
            // operator may have names of their own. Said once, so a typo is findable.
            java.util.Set<String> agents;
            try {
                agents =
                        context.paths().agents().agentDirectory().executables().stream()
                                .map(
                                        path ->
                                                path.getFileName()
                                                        .toString()
                                                        .substring(
                                                                org.fuin.porelo.agent.api
                                                                        .AgentDirectory.PREFIX
                                                                        .length()))
                                .collect(java.util.stream.Collectors.toSet());
            } catch (RuntimeException ex) {
                agents = java.util.Set.of();
            }
            if (agents.contains(name)) {
                err.println("porelo: " + unknownName(name, context.providers().keySet(), agents));
                err.flush();
            } else {
                // Not a warning: nothing is wrong with a name nothing asks for yet.
                out.println("unused    " + unknownName(name, context.providers().keySet(), agents));
                out.flush();
            }
            return;
        }
        if (type == null) {
            return;
        }
        final String reason = provider.unbrokerableReason(type);
        if (reason != null) {
            err.println(
                    "porelo: '"
                            + name
                            + "' cannot use a '"
                            + type
                            + "' credential"
                            + " through the proxy - "
                            + reason);
            err.flush();
        }
    }
}
