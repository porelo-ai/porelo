package org.fuin.porelo.app;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import org.fuin.porelo.core.credential.Credential;
import org.fuin.porelo.vault.VaultEntry;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Answers git when it asks for the password of an https repository.
 *
 * <p><strong>git's own protocol, not an invention.</strong> git runs a credential helper with
 * {@code get}, writes {@code protocol=}, {@code host=} and a blank line to its standard input, and
 * reads {@code username=} and {@code password=} back. Porelo wires it up per command ({@code git -c
 * credential.https://host:port.helper=...}), so nothing is written into any git config and the
 * helper is offered to that scheme, host and port alone.
 *
 * <p><strong>Why the token is not an argument.</strong> {@code /proc/<pid>/cmdline} is world
 * readable on both supported distributions, so a token on a command line is readable by every
 * account on the machine. What travels as an argument here is the <em>name of the vault entry</em>;
 * the value is read from the vault by this process and written to one pipe.
 *
 * <p><strong>{@code --for <url>} names the address, not where its token lives.</strong> A program
 * that reads a private repository with this account's login passes git only the address; the token
 * is found by the rule Porelo's own git calls follow ({@link FollowCredential#tokenFor}), and git's
 * request is answered only for that address's scheme, host and port, and its path or below it.
 */
@Command(
        name = "credential",
        mixinStandardHelpOptions = true,
        description = "Answers git's credential protocol from the vault. Run by git, not by hand.")
public class VaultCredentialCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    /**
     * The most of git's request this reads, in bytes without the line ends: git sends a handful of
     * short fields, so a request past this is no request of git's, and reading it whole would put
     * all of it on the heap.
     */
    static final int REQUEST_BYTES = 64 * 1024;

    /** The most lines of git's request this reads, for the same reason. */
    static final int REQUEST_LINES = 256;

    @Parameters(
            index = "0",
            arity = "0..1",
            paramLabel = "<operation>",
            description = "What git asks: get, store or erase. Only 'get' answers.")
    private String operation = "get";

    @Option(
            names = "--entry",
            paramLabel = "<name>",
            description = "Vault entry holding the token, as 'porelo vault list' shows it.")
    private @Nullable String entry;

    @Option(
            names = "--env",
            paramLabel = "<variable>",
            description =
                    "Environment variable holding the token, for a value this machine did"
                            + " not store.")
    private @Nullable String variable;

    @Option(
            names = "--file",
            paramLabel = "<path>",
            description = "File holding the token, for a value this machine did not store.")
    private @Nullable String file;

    @Option(
            names = "--for",
            paramLabel = "<url>",
            description =
                    "The https address the token is for, found as Porelo finds its own: declared"
                            + " for it, else the vault entry its host implies. Answers only git's"
                            + " request for that scheme, host and port, and that path or below it,"
                            + " which git sends with credential.useHttpPath=true. Not with --entry,"
                            + " --env or --file.")
    private @Nullable String address;

    @Option(
            names = "--username",
            paramLabel = "<name>",
            description = "What to answer as the user. Default: ${DEFAULT-VALUE}")
    private String username = "x-access-token";

    /** Whether what this prints lands on a person's screen. */
    java.util.function.BooleanSupplier standardOutputIsATerminal =
            VaultCredentialCommand::outputIsATerminal;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() throws IOException {

        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();

        if (address != null && (entry != null || variable != null || file != null)) {
            throw new CommandLine.ParameterException(
                    spec.commandLine(),
                    "--for finds the token itself: it takes no --entry, --env or --file");
        }

        // 'store' and 'erase' are git offering to remember or forget. The vault is not git's to
        // write, so both are accepted and do nothing - which is what git expects of a read-only
        // helper, and is quieter than failing on every successful fetch.
        if (!"get".equals(operation)) {
            return 0;
        }
        // Its answer is for git's pipe. A person running it by hand would have the secret on their
        // screen and in their scrollback.
        if (standardOutputIsATerminal.getAsBoolean()) {
            err.println(
                    "porelo vault credential answers git, with the secret: standard output is a"
                            + " terminal, so nothing was printed. porelo vault list shows what is"
                            + " stored.");
            err.flush();
            return 2;
        }
        // Read git's request to its blank line even where every field is already known: git waits
        // for the helper to consume it, and a helper that answers without reading can deadlock on
        // a full pipe. Past what git ever sends, reading stops and nothing is answered.
        final Map<String, String> request = requestOf(System.in);
        if (request == null) {
            err.println(
                    "porelo: git's credential request is longer than git sends one, so nothing"
                            + " was answered");
            err.flush();
            return 0;
        }

        if (address != null) {
            return answerFor(address, request, out, err);
        }
        final String secret = read(err);
        if (secret == null) {
            // Answering nothing makes git ask its next helper or fail with its own message, which
            // is better than this inventing one - but the reason goes to standard error, where git
            // shows it.
            return 0;
        }
        // Git reads one line a field and warns of any other line by quoting it: a value of
        // several lines would put its second line on git's standard error, in the open.
        if (!oneLine(username) || !oneLine(secret)) {
            err.println(
                    "porelo: the value holds a line break or a NUL, which git's credential"
                            + " protocol cannot carry; nothing was handed to git");
            err.flush();
            return 0;
        }
        out.println("username=" + username);
        out.println("password=" + secret);
        out.flush();
        return 0;
    }

    /**
     * Reads git's request up to its blank line, a field a line; a field sent twice is dropped.
     *
     * @param in git's pipe.
     * @return The fields, or {@code null} past {@link #REQUEST_BYTES} or {@link #REQUEST_LINES}.
     * @throws IOException When the pipe cannot be read.
     */
    private static @Nullable Map<String, String> requestOf(final InputStream in)
            throws IOException {
        final Map<String, String> request = new HashMap<>();
        final Set<String> repeated = new HashSet<>();
        final ByteArrayOutputStream line = new ByteArrayOutputStream();
        int bytes = 0;
        int lines = 0;
        try (InputStream asked = new BufferedInputStream(in)) {
            while (true) {
                final int next = asked.read();
                if (next >= 0 && next != '\n') {
                    if (++bytes > REQUEST_BYTES) {
                        return null;
                    }
                    line.write(next);
                    continue;
                }
                String text = line.toString(StandardCharsets.UTF_8);
                line.reset();
                if (text.endsWith("\r")) {
                    text = text.substring(0, text.length() - 1);
                }
                if (text.isBlank()) {
                    break;
                }
                if (++lines > REQUEST_LINES) {
                    return null;
                }
                final int equals = text.indexOf('=');
                if (equals > 0
                        && request.put(text.substring(0, equals), text.substring(equals + 1))
                                != null) {
                    repeated.add(text.substring(0, equals));
                }
                if (next < 0) {
                    break;
                }
            }
        }
        repeated.forEach(request::remove);
        return request;
    }

    /**
     * Answers git's request for the address {@code --for} names, with the token Porelo would lend
     * for it. Nothing said on standard error names where the token lives: the caller is to know the
     * address alone.
     *
     * @param url The address.
     * @param request What git asked, a field a line.
     * @param out git's pipe.
     * @param err Where git shows a reason.
     * @return 0, and 1 when the vault is shut, so git fails rather than asks a terminal.
     */
    private int answerFor(
            final String url,
            final Map<String, String> request,
            final PrintWriter out,
            final PrintWriter err) {
        final Address own = Address.of(url);
        if (own == null) {
            err.println(unserved(url));
            err.flush();
            return 0;
        }
        if (!own.asks(request)) {
            err.println(
                    "porelo: git asked for another address than --for names, or one beside its"
                            + " path (is credential.useHttpPath=true set?); nothing was answered");
            err.flush();
            return 0;
        }
        final String shut =
                "porelo: this account's vault is shut, so nothing was answered for the --for"
                        + " address; 'porelo vault unlock' opens it";
        final FollowCredential.Token token = FollowCredential.tokenFor(context, url);
        if (token instanceof FollowCredential.Token.Shut) {
            err.println(shut);
            err.flush();
            return 1;
        }
        if (!(token instanceof FollowCredential.Token.Found found)) {
            err.println("porelo: no token is declared or stored for the --for address");
            err.flush();
            return 0;
        }
        final Credential credential = found.credential();
        final var readable =
                credential.source() == Credential.Source.VAULT
                        ? context.readableCredentials()
                        : java.util.Optional.of(Map.<String, VaultEntry>of());
        if (readable.isEmpty()) {
            err.println(shut);
            err.flush();
            return 1;
        }
        final @Nullable String secret =
                switch (credential.source()) {
                    case VAULT -> {
                        final VaultEntry held = readable.get().get(credential.id());
                        yield held == null ? null : held.value();
                    }
                    case FILE -> fileValue(credential.id());
                    case ENVIRONMENT -> {
                        final String value = context.environment().apply(credential.id());
                        yield value == null ? null : value.strip();
                    }
                    case AGENT -> null;
                };
        if (secret == null || secret.isEmpty()) {
            err.println(
                    "porelo: the token declared or stored for the --for address cannot be read");
            err.flush();
            return 0;
        }
        final String declaredUser = found.user();
        final String user = declaredUser == null ? username : declaredUser;
        if (!oneLine(user) || !oneLine(secret)) {
            err.println(
                    "porelo: the value holds a line break or a NUL, which git's credential"
                            + " protocol cannot carry; nothing was handed to git");
            err.flush();
            return 0;
        }
        out.println("username=" + user);
        out.println("password=" + secret);
        out.flush();
        return 0;
    }

    /**
     * Why an address {@code --for} named is not served, in fixed words that name no part of it.
     *
     * @param url The address.
     * @return The line for standard error.
     */
    private static String unserved(final String url) {
        final String trimmed = url.strip();
        final int separator = trimmed.indexOf("://");
        final String scheme =
                separator < 0
                        ? ""
                        : trimmed.substring(0, separator).toLowerCase(java.util.Locale.ROOT);
        if ("http".equals(scheme)) {
            return "porelo: --for answers https alone; a plain http address gets nothing, since"
                    + " its token would cross the network unencrypted";
        }
        if ("https".equals(scheme)) {
            final String rest = trimmed.substring(separator + 3);
            final java.util.regex.Matcher end = Address.AUTHORITY_END.matcher(rest);
            final String authority = end.find() ? rest.substring(0, end.start()) : rest;
            if (!authority.chars().allMatch(c -> c < 0x80)) {
                return "porelo: --for answers a host of plain ASCII alone; give an international"
                        + " name in its xn-- form";
            }
            return "porelo: --for takes an https address, written in lower case, of one host and a"
                    + " path without an empty, . or .. segment; nothing was answered";
        }
        return "porelo: --for answers an https address naming one host only; a key reaches git"
                + " through the agent, not this helper";
    }

    private static @Nullable String fileValue(final String path) {
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(path)).strip();
        } catch (final IOException | RuntimeException ex) {
            return null;
        }
    }

    /**
     * The scheme, host, effective port and path of an https address, as git's credential request is
     * compared with them.
     *
     * <p><strong>A host of ASCII alone, compared as ASCII.</strong> As the organization's forge is:
     * a case-insensitive comparison of anything else takes a lookalike for the host itself.
     *
     * @param host The host, lower-cased, an IPv6 one in its brackets.
     * @param port The port, 443 when none is named.
     * @param path The path without its leading and trailing {@code /}, empty for the whole host.
     */
    record Address(String host, int port, String path) {

        private static final String HTTPS = "https://";

        private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.-]+|\\[[0-9A-Fa-f:.]+]");

        private static final Pattern AUTHORITY_END = Pattern.compile("[/?#]");

        private static final Pattern SCHEME = Pattern.compile("[A-Za-z]+");

        /**
         * Reads an https address.
         *
         * @param url The address.
         * @return It, or {@code null} when it is not https, names more than one host, or holds a
         *     path that is not plain.
         */
        static @Nullable Address of(final String url) {
            final String scope = GitCredentialNames.helperScopeOf(url);
            if (scope == null || !GitCredentialNames.namesOneHost(url)) {
                return null;
            }
            final Address authority = authority(scope.substring(HTTPS.length()));
            if (authority == null) {
                return null;
            }
            final String rest = url.strip().substring(HTTPS.length());
            final java.util.regex.Matcher end = AUTHORITY_END.matcher(rest);
            @Nullable String path = "";
            if (end.find() && rest.charAt(end.start()) == '/') {
                final String after = rest.substring(end.start());
                final int cut = firstOf(after, '?', '#');
                path = decoded(cut < 0 ? after : after.substring(0, cut));
            }
            final String plain = path == null ? null : plain(path);
            return plain == null ? null : new Address(authority.host, authority.port, plain);
        }

        /**
         * Tells whether git's request is for this address: https, this host and port, and this path
         * or one below it at a {@code /}.
         *
         * @param request git's fields.
         * @return {@code true} when it is.
         */
        boolean asks(final Map<String, String> request) {
            final String protocol = request.get("protocol");
            final String host = request.get("host");
            if (protocol == null
                    || !SCHEME.matcher(protocol).matches()
                    || !"https".equals(protocol.toLowerCase(java.util.Locale.ROOT))
                    || host == null) {
                return false;
            }
            final Address asked = authority(host);
            if (asked == null || !asked.host.equals(this.host) || asked.port != port) {
                return false;
            }
            if (path.isEmpty()) {
                return true;
            }
            final String sent = request.get("path");
            final String plain = sent == null ? null : plain(sent);
            return plain != null && (plain.equals(path) || plain.startsWith(path + "/"));
        }

        /** A host and port as git writes them, the port 443 when none. */
        private static @Nullable Address authority(final String hostAndPort) {
            final int close = hostAndPort.startsWith("[") ? hostAndPort.indexOf(']') : -1;
            final int colon = close >= 0 ? close + 1 : hostAndPort.indexOf(':');
            final String host =
                    colon < 0 || colon >= hostAndPort.length()
                            ? hostAndPort
                            : hostAndPort.substring(0, colon);
            final String port =
                    host.length() == hostAndPort.length()
                            ? ""
                            : hostAndPort.substring(host.length());
            if (!HOST.matcher(host).matches()) {
                return null;
            }
            final int number;
            if (port.isEmpty()) {
                number = 443;
            } else if (port.matches(":[0-9]{1,5}")) {
                number = Integer.parseInt(port.substring(1));
            } else {
                return null;
            }
            return number < 1 || number > 65535
                    ? null
                    : new Address(host.toLowerCase(java.util.Locale.ROOT), number, "");
        }

        /**
         * A path without its leading and trailing {@code /}, or {@code null} when a segment is
         * empty, {@code .} or {@code ..}: below an address textually is not below it on the forge.
         */
        private static @Nullable String plain(final String path) {
            String trimmed = path;
            while (trimmed.startsWith("/")) {
                trimmed = trimmed.substring(1);
            }
            while (trimmed.endsWith("/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            if (trimmed.isEmpty()) {
                return "";
            }
            for (final String segment : trimmed.split("/", -1)) {
                if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                    return null;
                }
            }
            return trimmed;
        }

        /** A path with its {@code %} escapes decoded, as git sends it; {@code null} when broken. */
        private static @Nullable String decoded(final String path) {
            if (path.indexOf('%') < 0) {
                return path;
            }
            try {
                return java.net.URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8);
            } catch (final IllegalArgumentException ex) {
                return null;
            }
        }

        private static int firstOf(final String text, final char one, final char other) {
            final int first = text.indexOf(one);
            final int second = text.indexOf(other);
            return first < 0 ? second : second < 0 ? first : Math.min(first, second);
        }
    }

    private static boolean oneLine(final String value) {
        return value.indexOf('\n') < 0 && value.indexOf('\r') < 0 && value.indexOf('\0') < 0;
    }

    /**
     * Returns the secret from wherever this was told it lives.
     *
     * <p>Three sources because a person working on their own machine may already have the value -
     * exported, or in a file - and should not have to keep a second copy of it in a vault. What the
     * vault adds is being encrypted and shuttable; the other two are as protected as whatever holds
     * them, which is the person's choice to make and the machine's job to say.
     *
     * @param err Where to explain a miss.
     * @return The value, or {@code null} when there is none.
     */
    private @org.jspecify.annotations.Nullable String read(final PrintWriter err) {
        if (variable != null) {
            final String value = System.getenv(variable);
            if (value == null || value.isBlank()) {
                err.println("porelo: $" + variable + " is not set in this environment");
                err.flush();
                return null;
            }
            return value.strip();
        }
        if (file != null) {
            try {
                final String value =
                        java.nio.file.Files.readString(java.nio.file.Path.of(file)).strip();
                if (value.isEmpty()) {
                    err.println("porelo: " + file + " is empty");
                    err.flush();
                    return null;
                }
                return value;
            } catch (final IOException ex) {
                err.println("porelo: cannot read " + file + ": " + ex.getMessage());
                err.flush();
                return null;
            }
        }
        if (entry == null) {
            err.println("porelo: say where the value is: --entry, --env or --file");
            err.flush();
            return null;
        }
        final var readable = context.readableCredentials();
        if (readable.isEmpty()) {
            err.println("porelo: the vault is not open, so '" + entry + "' cannot be read");
            err.flush();
            return null;
        }
        final var held = readable.get().get(entry);
        if (held == null) {
            err.println("porelo: the vault has no entry named '" + entry + "'");
            err.flush();
            return null;
        }
        return held.value();
    }

    /**
     * Tells whether a secret may go where standard output goes: git's pipe, a socket, a file a
     * person chose, or nowhere. Anything else is taken for a screen, as a list of terminals is
     * missing the next kind of one, and so is not knowing where it goes.
     *
     * @param to Where it goes, as {@code /proc} says, or {@code null}.
     * @return Whether it may.
     */
    static boolean mayAnswer(final @org.jspecify.annotations.Nullable String to) {
        return to != null
                && (to.startsWith("pipe:")
                        || to.startsWith("socket:")
                        || "/dev/null".equals(to)
                        || java.nio.file.Files.isRegularFile(
                                java.nio.file.Path.of(to),
                                java.nio.file.LinkOption.NOFOLLOW_LINKS));
    }

    private static boolean outputIsATerminal() {
        try {
            return !mayAnswer(
                    java.nio.file.Files.readSymbolicLink(java.nio.file.Path.of("/proc/self/fd/1"))
                            .toString());
        } catch (final IOException | RuntimeException ex) {
            return true;
        }
    }
}
