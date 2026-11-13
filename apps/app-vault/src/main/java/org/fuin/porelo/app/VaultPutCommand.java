package org.fuin.porelo.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;
import org.fuin.porelo.vault.VaultException;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Stores one credential in the vault.
 *
 * <p><strong>The value is read from standard input, never from an argument.</strong> A command-line
 * argument is visible in the host's process list to every user on the machine for as long as the
 * command runs, and it lands in the shell history afterwards. Neither is acceptable for the thing
 * this tool exists to protect.
 */
@Command(
        name = "put",
        mixinStandardHelpOptions = true,
        description = "Stores a credential, read from standard input.")
public class VaultPutCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Parameters(
            index = "0",
            paramLabel = "<name>",
            description =
                    "Name to store it under: the provider it is for, as 'porelo providers'"
                            + " lists them.")
    private String name;

    @picocli.CommandLine.Option(
            names = "--type",
            paramLabel = "<kind>",
            description =
                    "Kind of credential, as the agent names it, for example 'oauth'."
                            + " Stored with the entry, so no task has to repeat it.")
    private @Nullable String type;

    @picocli.CommandLine.Option(
            names = "--from-file",
            paramLabel = "<path>",
            description =
                    "Reads the value from this file ON THIS MACHINE, instead of from"
                            + " standard input. For a key that is already here: nothing has to be sent,"
                            + " and an interface can ask for this without ever holding the value.")
    private @Nullable String fromFile;

    @picocli.CommandLine.Option(
            names = "--setting",
            paramLabel = "<name>=<value>",
            description =
                    "A setting that goes with the credential and is not secret, for example"
                            + " 'token_url=https://auth.example.com/token'. Repeatable. Settings are configuration:"
                            + " they are listed and printed, so no secret belongs in one.")
    private java.util.Map<String, String> settings = new java.util.LinkedHashMap<>();

    @picocli.CommandLine.Option(
            names = "--json",
            description =
                    "Answers {\"name\", \"stored\"} alone on standard output, 'stored' false when the"
                            + " entry already held exactly this; every other line goes to standard error."
                            + " Never any part of the value.")
    private boolean json;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
    }

    /**
     * What a person types at: the console's reads without echo, as a test can hand them too.
     *
     * <p>{@link java.io.Console} cannot be made in a test, so the reads {@link #valueFrom} wires
     * are asked of this, and only {@link #of} touches the console itself.
     */
    interface Typing {

        /**
         * Reads one line without echo, after a prompt.
         *
         * @param prompt What to show first.
         * @return The line, or {@code null} at end of input.
         */
        char[] withPrompt(String prompt);

        /**
         * Reads one further line without echo and without a prompt.
         *
         * @return The line, or {@code null} at end of input.
         */
        char[] next();

        /**
         * Reads from a console.
         *
         * @param console The console.
         * @return Its reads.
         */
        static Typing of(final java.io.Console console) {
            return new Typing() {
                @Override
                public char[] withPrompt(final String prompt) {
                    return console.readPassword("%s", prompt);
                }

                @Override
                public char[] next() {
                    return console.readPassword();
                }
            };
        }
    }

    /**
     * Reads the credential from a person at a terminal or from standard input, and leaves standard
     * input open.
     *
     * <p><strong>Never closed.</strong> Closing a reader over standard input closes descriptor 0,
     * and the next file opened takes that number - here the vault itself. The passphrase prompt
     * that follows then switched echo off on a regular file and failed with "Inappropriate ioctl
     * for device", every time the vault was locked.
     *
     * <p><strong>At a terminal, echo is off from the prompt to the last line read</strong> ({@link
     * EchoOff}): a pasted key is many reads, and {@code readPassword} alone turned echo back on
     * between them.
     *
     * @param name The entry's name, for the prompt.
     * @param tty What a person types at, or {@code null} when standard input is no terminal.
     * @param in Standard input.
     * @param kind The entry's kind, or {@code null}.
     * @param terminal Runs {@code stty} to hold the terminal's echo off while {@code tty} is read.
     * @return The value, stripped, or empty when there was none.
     * @throws IOException If standard input cannot be read.
     * @throws VaultException If a pasted key stops before its end line.
     */
    static String valueFrom(
            String name,
            @org.jspecify.annotations.Nullable Typing tty,
            java.io.InputStream in,
            @org.jspecify.annotations.Nullable String kind,
            org.fuin.porelo.core.process.CommandRunner terminal)
            throws IOException {
        final BufferedReader piped =
                new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        if (tty == null) {
            return grantValue(readValue(false, () -> new char[0], () -> null, piped), kind);
        }
        try (EchoOff held = EchoOff.hold(terminal)) {
            return grantValue(
                    readValue(
                            true,
                            () -> tty.withPrompt(prompt(name, kind)),
                            // A pasted key's further lines: asked again, the prompt would land
                            // between them.
                            tty::next,
                            piped),
                    kind);
        }
    }

    /**
     * Returns what a person is asked for: the client secret for a grant, the value for anything
     * else, and how a pasted key that stopped short is ended.
     *
     * <p>A paste that stops before its {@code -----END} line leaves the read waiting, as the
     * terminal is still open; only end of input or a signal tells it the paste is over, and the
     * prompt is the one place a person sees before that.
     *
     * @param name The entry's name.
     * @param kind The entry's kind, or {@code null}.
     * @return The prompt.
     */
    static String prompt(String name, @org.jspecify.annotations.Nullable String kind) {
        return granted(kind)
                ? "Client secret for '" + name + "' (none for a public client: press Enter): "
                : "Value for '"
                        + name
                        + "' (a pasted key is read to its -----END line; Ctrl-D refuses one cut"
                        + " short, Ctrl-C quits): ";
    }

    /**
     * Stores an entry, or leaves the vault as it is when the entry already holds exactly this.
     *
     * @param vault The vault.
     * @param passphrase Its passphrase.
     * @param name The entry's name.
     * @param entry What to store.
     * @return Whether anything was stored.
     */
    static boolean put(
            final org.fuin.porelo.vault.VaultFile vault,
            final char[] passphrase,
            final String name,
            final org.fuin.porelo.vault.VaultEntry entry) {
        return vault.update(passphrase, entries -> replace(entries, name, entry));
    }

    /**
     * Puts an entry in place of whatever had its name.
     *
     * <p>A put never carries a sign-in's client secret, so one left from a sign-in it replaces is
     * that sign-in's, not this entry's, and goes with it.
     *
     * @param entries What the vault holds; changed in place.
     * @param name The entry's name.
     * @param entry The entry.
     * @return {@code entries}.
     */
    static java.util.Map<String, org.fuin.porelo.vault.VaultEntry> replace(
            final java.util.Map<String, org.fuin.porelo.vault.VaultEntry> entries,
            final String name,
            final org.fuin.porelo.vault.VaultEntry entry) {
        entries.put(name, entry);
        entries.remove(TaskSecrets.CLIENT_SECRET_PREFIX + name);
        return entries;
    }

    /**
     * Returns the value to store for a grant: nothing typed is a public client, which has no
     * secret.
     *
     * @param value What was typed or read.
     * @param kind The entry's kind, or {@code null}.
     * @return The value, {@value GrantEntry#NO_SECRET} for a grant's empty answer.
     */
    /**
     * Returns the value a file holds as standard input would give it: a private key whole, for the
     * vault to read; anything else stripped, as a file an editor saved ends in a newline that no
     * credential has, and an empty grant's file as a public client.
     *
     * <p>Read raw, the same token from a file and from standard input were two values, and a put of
     * the one after the other was a change.
     *
     * @param raw What the file holds.
     * @param kind The kind stated, or {@code null}.
     * @return The value to store.
     */
    static String fileValue(String raw, @org.jspecify.annotations.Nullable String kind) {
        return org.fuin.porelo.vault.OpenSshPrivateKey.looksLikeAnyPrivateKey(raw)
                ? raw
                : grantValue(raw.strip(), kind);
    }

    static String grantValue(String value, @org.jspecify.annotations.Nullable String kind) {
        return value.isEmpty() && granted(kind) ? GrantEntry.NO_SECRET : value;
    }

    private static boolean granted(@org.jspecify.annotations.Nullable String kind) {
        return org.fuin.porelo.supervisor.DeviceGrant.KIND.equals(kind)
                || org.fuin.porelo.supervisor.CodeGrant.KIND.equals(kind);
    }

    /**
     * Returns the key in a form this machine reads, converting it if it has to.
     *
     * <p><strong>The machine does this, not the person.</strong> {@code ssh-keygen} writes RSA keys
     * in its own container by default, which is not a format read here - and the first answer was a
     * message telling somebody to run {@code ssh-keygen -p -m PEM} themselves. That is our work to
     * do: the tool is on this machine, the conversion is one command, and what should be easy is
     * using your own key rather than knowing which of its formats we happened to implement.
     *
     * <p><strong>The original is never touched.</strong> {@code ssh-keygen -p} rewrites a key file
     * in place, so the conversion runs on a copy in a directory only this account can read, and the
     * copy is overwritten and removed afterwards. Somebody's daily key is not ours to rewrite.
     *
     * @param context The account and its process runner.
     * @param text The key as it arrived.
     * @param out Where to report what was done.
     * @param err Where to report what could not be.
     * @return The key to store, or {@code null} when it could not be converted.
     */
    static @org.jspecify.annotations.Nullable String converted(
            final PoreloContext context,
            final String text,
            final PrintWriter out,
            final PrintWriter err) {

        if (!org.fuin.porelo.vault.OpenSshPrivateKey.looksLikeOne(text)) {
            return text;
        }
        final org.fuin.porelo.vault.OpenSshPrivateKey.Described described =
                org.fuin.porelo.vault.OpenSshPrivateKey.describe(text);
        if (described == null || described.usable() || described.encrypted()) {
            // Ed25519 is read as it is, and an encrypted key is refused where that is explained.
            return text;
        }
        java.nio.file.Path directory = null;
        try {
            directory = java.nio.file.Files.createTempDirectory("porelo-key-");
            java.nio.file.Files.setPosixFilePermissions(
                    directory,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            final java.nio.file.Path copy = directory.resolve("key");
            java.nio.file.Files.writeString(copy, text);
            java.nio.file.Files.setPosixFilePermissions(
                    copy, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            final var result =
                    context.runner()
                            .run(
                                    org.fuin.porelo.core.process.Command.of(
                                            "ssh-keygen",
                                            "-p",
                                            "-m",
                                            "PEM",
                                            "-N",
                                            "",
                                            "-P",
                                            "",
                                            "-f",
                                            copy.toString()));
            if (!result.successful()) {
                err.println(
                        "porelo: this key is in OpenSSH's own format and converting it here"
                                + " failed: "
                                + result.standardError().strip());
                err.flush();
                return null;
            }
            out.println(
                    "read      an OpenSSH-wrapped key; converted a copy to PEM to store it"
                            + " (your file is untouched)");
            return java.nio.file.Files.readString(copy);
        } catch (final IOException ex) {
            err.println("porelo: cannot convert this key here: " + ex.getMessage());
            err.flush();
            return null;
        } finally {
            if (directory != null) {
                shred(directory);
            }
        }
    }

    /**
     * Removes a directory that held a key, overwriting what was in it first.
     *
     * <p>Not a guarantee on a copy-on-write filesystem, and worth doing anyway: it is the
     * difference between a key that is gone and a key that is merely unlinked.
     *
     * @param directory What to remove.
     */
    static void shred(final java.nio.file.Path directory) {
        try (var entries = java.nio.file.Files.list(directory)) {
            for (final java.nio.file.Path file : entries.toList()) {
                try {
                    final long size = java.nio.file.Files.size(file);
                    java.nio.file.Files.write(file, new byte[(int) Math.min(size, 1 << 20)]);
                } catch (final IOException ex) {
                    // Overwriting is the part that can fail harmlessly; removal below is what
                    // matters and is attempted either way.
                }
                java.nio.file.Files.deleteIfExists(file);
            }
            java.nio.file.Files.deleteIfExists(directory);
        } catch (final IOException ex) {
            return;
        }
    }

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
            String name, java.util.Set<String> providers, java.util.Set<String> agents) {
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
                    + "; 'porelo providers' shows which agent uses which), then 'porelo vault remove "
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
     * @param out Where the destinations it is used for are said.
     * @param err Where to report.
     */
    private void warnIfNothingWillUseIt(PrintWriter out, PrintWriter err) {
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

    /**
     * Reads the credential, without putting it on the screen when a person is typing it.
     *
     * <p><strong>This was a leak, and the product contradicted itself about it.</strong> The vault
     * passphrase has always been read through {@code Console.readPassword}, which does not echo. A
     * credential was read with a plain {@code readLine}, so pasting one into a terminal printed it
     * and left it in the scrollback - which many terminals persist to disk. Advice to "store it at
     * the machine rather than over the wire" was therefore recommending the path that wrote the
     * secret down.
     *
     * <p>The piped form stays exactly as it was. {@code printf %s ... | porelo vault put} is how
     * scripts do this and how the acceptance suite does it, and there is no terminal there to not
     * echo to.
     *
     * @param interactive Whether a person is typing at a terminal.
     * @param typed Reads without echo. Called only when {@code interactive}.
     * @param piped Standard input. Read only when not {@code interactive}.
     * @return The value, stripped, or empty when there was none.
     * @throws IOException If standard input cannot be read.
     */
    static String readValue(
            boolean interactive, java.util.function.Supplier<char[]> typed, BufferedReader piped)
            throws IOException {
        return readValue(interactive, typed, typed, piped);
    }

    /**
     * Reads the credential, and at a terminal reads a pasted key file to its last line.
     *
     * <p><strong>{@code readPassword} reads one line.</strong> A key pasted at the prompt therefore
     * arrived as its {@code -----BEGIN} line alone, and the vault called that a key of the wrong
     * algorithm. A first line that is armour is now followed by further reads, with no prompt, to
     * the {@code -----END} line - the same text a pipe or {@code --from-file} hands over.
     *
     * @param interactive Whether a person is typing at a terminal.
     * @param typed Reads the first line without echo, with the prompt. Called only when {@code
     *     interactive}.
     * @param typedOn Reads each further line of a pasted key without echo and without a prompt.
     * @param piped Standard input. Read only when not {@code interactive}.
     * @return The value, stripped, or empty when there was none; a key file whole.
     * @throws IOException If standard input cannot be read.
     * @throws VaultException If a pasted key ends, or runs past {@value #PASTED_KEY_LINES} lines,
     *     before its {@code -----END} line; nothing is stored then.
     */
    static String readValue(
            boolean interactive,
            java.util.function.Supplier<char[]> typed,
            java.util.function.Supplier<char[]> typedOn,
            BufferedReader piped)
            throws IOException {
        if (interactive) {
            final char[] secret = typed.get();
            if (secret == null) {
                return "";
            }
            try {
                if (!new String(secret).strip().startsWith("-----BEGIN")) {
                    return new String(secret).strip();
                }
                return pastedKey(secret, typedOn);
            } finally {
                // The String is what gets stored, so this only shortens one copy's life. Worth
                // doing anyway: it is the copy that exists for no reason after this point.
                java.util.Arrays.fill(secret, '\0');
            }
        }
        final String line = piped.readLine();
        if (line == null) {
            return "";
        }
        if (line.strip().startsWith("-----BEGIN")) {
            // A key file is many lines, and this read one. '< ~/.ssh/id_ed25519' therefore stored
            // the armour line alone, warned that it "contains spaces", and failed days later when
            // something tried to sign with it. The whole file is read when the first line says that
            // is what this is.
            final StringBuilder whole = new StringBuilder(line).append('\n');
            String next = piped.readLine();
            while (next != null) {
                whole.append(next).append('\n');
                next = piped.readLine();
            }
            return whole.toString();
        }
        return line.strip();
    }

    /** More lines than any key file has: a paste that never ends is not read forever. */
    static final int PASTED_KEY_LINES = 200;

    /**
     * Reads a key pasted at the prompt on to its {@code -----END} line, laid out as the file is.
     *
     * @param first The armour line, already read; the caller wipes it.
     * @param typedOn Reads the next line without echo or prompt; {@code null} at end of input.
     * @return The key file's text, each line ended by a newline.
     * @throws VaultException If the paste ends or runs past the bound first.
     */
    private static String pastedKey(
            final char[] first, final java.util.function.Supplier<char[]> typedOn) {
        final StringBuilder whole = new StringBuilder().append(first).append('\n');
        int read = 1;
        while (read < PASTED_KEY_LINES) {
            final char[] next = typedOn.get();
            if (next == null) {
                break;
            }
            read++;
            try {
                whole.append(next).append('\n');
                if (new String(next).strip().startsWith("-----END")) {
                    return whole.toString();
                }
            } finally {
                java.util.Arrays.fill(next, '\0');
            }
        }
        // Nothing is stored, so the lines read are not kept either.
        for (int at = 0; at < whole.length(); at++) {
            whole.setCharAt(at, '\0');
        }
        throw new VaultException(
                "the key pasted at the prompt is incomplete: "
                        + read
                        + (read == 1 ? " line" : " lines")
                        + " read"
                        + (read >= PASTED_KEY_LINES ? ", the most a key has," : "")
                        + " and no -----END line; nothing was stored. Paste it whole, or store the"
                        + " file with --from-file <file>.");
    }

    @Override
    public Integer call() throws IOException {

        final PrintWriter answer = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        // With --json, standard output holds the answer alone, so a play can read it whole.
        final PrintWriter out = json ? err : answer;

        if (TaskSecrets.reserved(name)) {
            // Refused before anything is asked for: a name here would look like a task's own token.
            err.println(
                    "porelo: names starting with '"
                            + TaskSecrets.PREFIX
                            + "' belong to tasks; choose another");
            err.flush();
            return 64;
        }

        String value;
        if (fromFile == null) {
            final java.io.Console console = System.console();
            if (context.input().terminal() && (console == null || !console.isTerminal())) {
                err.println(
                        "porelo: no console can hide the value; pipe the value on standard input to porelo vault put "
                                + name);
                err.flush();
                return 2;
            }
            try {
                value =
                        valueFrom(
                                name,
                                context.input().terminal() && console != null
                                        ? Typing.of(console)
                                        : null,
                                context.input().stream(),
                                type,
                                context.runner());
            } catch (final VaultException ex) {
                err.println("porelo: " + ex.getMessage());
                err.flush();
                return 65;
            }
        } else {
            // The machine reads its own disk. This is what lets an interface offer "use the key
            // that is already here" without the value crossing a socket or a person retyping it.
            try {
                value = java.nio.file.Files.readString(java.nio.file.Path.of(fromFile));
            } catch (final IOException ex) {
                err.println("porelo: cannot read " + fromFile + ": " + ex.getMessage());
                err.flush();
                return 70;
            }
            if (fromFile.endsWith(".pub")) {
                // The public half is not a credential. It is the commonest mistake there is here,
                // and it fails silently later rather than now.
                err.println(
                        "porelo: "
                                + fromFile
                                + " is a public key. A machine signs with the"
                                + " private half - the same path without '.pub'.");
                err.flush();
                return 70;
            }
            value = fileValue(value, type);
        }
        if (org.fuin.porelo.vault.OpenSshPrivateKey.looksLikeAnyPrivateKey(value)) {
            // What a person has is the file they use every day. An Ed25519 key is kept as the seed
            // inside it, which is what every entry written until now holds; anything else is kept
            // as the file, because there is nothing smaller that is still the key.
            //
            // It is READ BACK before it is stored. A value that goes in and cannot come out is what
            // happened to an RSA key this morning: stored whole, called present and ready by every
            // check, and undecodable at the first fetch - where the machine blamed the vault for
            // being empty.
            try {
                value = converted(context, value, out, err);
                if (value == null) {
                    return 70;
                }
                value = org.fuin.porelo.vault.StoredKey.toStore(value);
                final org.fuin.porelo.vault.AgentKey read =
                        org.fuin.porelo.vault.StoredKey.of(value, name);
                if (type == null) {
                    type = "ssh-key";
                }
                out.println(
                        "read      "
                                + read.authorizedKeysLine().split("\\s+")[0]
                                + "; this machine can sign with it");
            } catch (final VaultException ex) {
                err.println("porelo: " + ex.getMessage());
                err.flush();
                return 70;
            }
        }
        if (value.isEmpty()) {
            err.println(
                    "porelo: nothing on standard input. Use: echo <secret> | porelo vault put "
                            + name);
            err.flush();
            return 2;
        }
        final String stored = value;
        final org.fuin.porelo.vault.VaultEntry entry;
        try {
            entry = new org.fuin.porelo.vault.VaultEntry(stored, type, settings);
        } catch (IllegalArgumentException ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return 2;
        }

        final boolean written;
        if (!context.input().terminal() && context.opener().isEmpty()) {
            if (!context.vault().exists()) {
                err.println(
                        "porelo: there is no vault; create it with porelo vault init --passphrase-command first");
                err.flush();
                return 70;
            }
            try {
                context.requirePassphraseSlot();
            } catch (VaultException ex) {
                err.println("porelo: " + ex.getMessage());
                err.flush();
                return 70;
            }
            err.println(
                    "porelo: the vault is shut and there is no terminal to ask its passphrase; "
                            + "run porelo vault unlock --passphrase-command first (or --passphrase-stdin)");
            err.flush();
            return 70;
        }
        try {
            written =
                    context.vault()
                            .update(
                                    context.requireOpener(),
                                    entries -> replace(entries, name, entry));
        } catch (VaultException ex) {
            err.println("porelo: " + ex.getMessage());
            err.flush();
            return 70;
        }
        if (json) {
            final java.util.Map<String, Object> said = new java.util.LinkedHashMap<>();
            said.put("name", name);
            said.put("stored", written);
            answer.println(org.fuin.porelo.wire.Json.write(said));
            answer.flush();
        }
        if (written) {
            // The value is never echoed, not even truncated: a terminal scrollback is a file.
            out.println(
                    "stored    "
                            + name
                            + " ("
                            + (type == null ? "kind not stated" : type)
                            + ", "
                            + stored.length()
                            + " characters)");
            entry.settings()
                    .forEach((setting, text) -> out.println("setting   " + setting + " = " + text));
        } else {
            // Nothing written and nothing of the value said: only that the entry holds it already.
            out.println("unchanged " + name);
        }
        // Said either way: a put that changed nothing still names what reads the entry, or that
        // nothing will.
        warnIfNothingWillUseIt(out, err);
        final String suspicious = entry.suspicious();
        if (suspicious != null) {
            err.println("porelo: check what you stored - " + suspicious);
            err.flush();
        }
        out.flush();
        return 0;
    }
}
