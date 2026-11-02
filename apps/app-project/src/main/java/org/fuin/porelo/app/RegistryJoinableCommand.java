package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.porelo.core.credential.Credential;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.project.Access;
import org.fuin.porelo.core.project.AccessFile;
import org.fuin.porelo.core.project.AddressForm;
import org.fuin.porelo.organization.Organization;
import org.fuin.porelo.organization.OrganizationException;
import org.fuin.porelo.organization.git.HttpForgeDirectory;
import org.fuin.porelo.organization.git.RegistryGit;
import org.fuin.porelo.vault.AccessPersons;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.Userinfo;
import org.fuin.porelo.wire.Directories;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Answers the projects a person may join: the registry read as {@code registry read} reads it, and
 * each project a host's accepted access file names checked in its access repository as a first
 * follow checks one, against the key the registry pins for the project's admin.
 *
 * <p>It only reads. Each access repository is fetched into a scratch directory that is removed
 * before the answer; nothing is followed, pinned or kept but the registry's own trust record.
 */
@Command(
        name = "joinable",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description =
                "Answers the projects a person may join, from a registry checkout and each"
                        + " project's verified access file, and follows nothing.")
public class RegistryJoinableCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    /** A login, as a card and the access file name one. */
    private static final String LOGIN = "[a-z][a-z0-9-]{0,31}";

    @Parameters(index = "0", paramLabel = "CHECKOUT", description = "The registry checkout.")
    private Path checkout = Path.of("");

    @Option(
            names = "--login",
            required = true,
            paramLabel = "LOGIN",
            description = "The person whose admissions are answered.")
    private String login = "";

    @Option(names = "--json", description = "Answers projects and refused as JSON.")
    private boolean json;

    @Spec private CommandSpec spec;

    private @Nullable PoreloContext context;

    /** Replaced by a test, which has no forge to ask. */
    RegistryReadCommand.Forges forges =
            (forge, token) ->
                    new HttpForgeDirectory(
                            forge,
                            token,
                            RegistryReadCommand.http(
                                    java.util.Objects.requireNonNull(
                                            context, "picocli's factory sets the context")));

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final PoreloContext machine =
                java.util.Objects.requireNonNull(context, "picocli's factory sets the context");
        if (!login.matches(LOGIN)) {
            return refuse(64, "--login '" + login + "' is not a login; expected " + LOGIN);
        }
        final Organization state;
        try {
            state =
                    RegistryReadCommand.read(
                                    machine,
                                    machine.runner(),
                                    checkout,
                                    forges,
                                    spec.commandLine().getErr())
                            .organization();
        } catch (IllegalArgumentException ex) {
            return refuse(64, String.valueOf(ex.getMessage()));
        } catch (IOException ex) {
            return refuse(66, String.valueOf(ex.getMessage()));
        } catch (OrganizationException | CardException | CommandException ex) {
            return refuse(65, String.valueOf(ex.getMessage()));
        }
        // Owners are never empty once an owners.yml was accepted: a card accepted without one
        // names no project anybody may join.
        if (state.revision() == null || state.owners().isEmpty()) {
            return refuse(65, unaccepted(state));
        }
        final Path scratch;
        try {
            scratch = Files.createTempDirectory("porelo-joinable-");
        } catch (IOException ex) {
            return refuse(70, "cannot make a scratch directory: " + ex.getMessage());
        }
        final Map<String, @Nullable Object> answer;
        try {
            answer = joinable(machine, state, scratch);
        } finally {
            deleteTree(scratch);
        }
        final PrintWriter out = spec.commandLine().getOut();
        if (json) {
            out.println(Json.write(RegistryVerifyCommand.printable(answer)));
        } else {
            for (Object entry :
                    (List<?>) java.util.Objects.requireNonNull(answer.get("projects"))) {
                final Map<?, ?> project = (Map<?, ?>) entry;
                out.println(
                        RegistryVerifyCommand.printable(
                                "joinable "
                                        + project.get("project")
                                        + " on "
                                        + project.get("host")
                                        + " at "
                                        + project.get("revision")));
            }
            for (Object entry : (List<?>) java.util.Objects.requireNonNull(answer.get("refused"))) {
                final Map<?, ?> project = (Map<?, ?>) entry;
                out.println(
                        RegistryVerifyCommand.printable(
                                "refused "
                                        + (project.get("project") == null
                                                ? "the access file"
                                                : project.get("project"))
                                        + " on "
                                        + project.get("host")
                                        + ": "
                                        + project.get("why")));
            }
        }
        out.flush();
        return 0;
    }

    /** Checks every project of every host's accepted access file, each in a place of its own. */
    private Map<String, @Nullable Object> joinable(
            final PoreloContext machine, final Organization state, final Path scratch) {
        final List<Object> projects = new ArrayList<>();
        final List<Object> refused = new ArrayList<>();
        refusedAccessFiles(state, refused);
        int at = 0;
        for (Organization.Host host : state.hosts()) {
            for (Organization.Project project : host.projects()) {
                final Path place = scratch.resolve(Integer.toString(at++));
                final Object joined = check(machine, state, host, project, place, refused);
                if (joined != null) {
                    projects.add(joined);
                }
            }
        }
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put("projects", projects);
        answer.put("refused", refused);
        return answer;
    }

    /**
     * Checks one project's access repository; answers it when its verified access file admits the
     * login on this host, adds it to the refused when it cannot be checked, and answers nothing for
     * a file that does not admit the login.
     */
    private @Nullable Object check(
            final PoreloContext machine,
            final Organization state,
            final Organization.Host host,
            final Organization.Project project,
            final Path place,
            final List<Object> refused) {
        final String url = project.accessRepository();
        final Organization.@Nullable Card card = host.card();
        if (card == null) {
            refused.add(
                    refusal(state, host, project, "the registry holds no accepted card for it"));
            return null;
        }
        final String key = adminKey(state, host, project);
        if (key == null) {
            refused.add(
                    refusal(
                            state,
                            host,
                            project,
                            "the registry holds no accepted card of its admin "
                                    + project.admin()
                                    + " with the key "
                                    + host.host()
                                    + "'s access file pins"));
            return null;
        }
        final Reconcile.Checked checked;
        try {
            Directories.create(place);
            final Path signers = place.resolve("signers");
            Files.writeString(signers, project.name() + " " + key + "\n", StandardCharsets.UTF_8);
            final Path home = Files.createDirectory(place.resolve("home"));
            checked =
                    new Reconcile(sealed(machine, home), signers)
                            .accessAlone(project.name(), url, place.resolve("access"));
        } catch (IOException ex) {
            refused.add(refusal(state, host, project, "cannot check it: " + ex.getMessage()));
            return null;
        }
        final Reconcile.Result result = checked.result();
        final String text = checked.accessFile();
        if (text == null) {
            refused.add(
                    refusal(
                            state,
                            host,
                            project,
                            result.outcome().name().toLowerCase(Locale.ROOT)
                                    + (result.detail().isEmpty() ? "" : ": " + result.detail())));
            return null;
        }
        final AccessFile file = AccessFile.read(new StringReader(text), Access.FILE);
        final String source = file.source();
        if (source == null) {
            refused.add(
                    refusal(state, host, project, "the access file names no project repository"));
            return null;
        }
        final Object homeserver =
                file.transports().get("matrix") instanceof Map<?, ?> matrix
                        ? matrix.get("homeserver")
                        : null;
        if (homeserver != null
                && !(homeserver instanceof String named
                        && (AddressForm.isHttps(named) || AddressForm.isUnixSocket(named)))) {
            refused.add(
                    refusal(
                            state,
                            host,
                            project,
                            "the access file's transports.matrix.homeserver is not an https://"
                                    + " URL with a host, nor a unix: socket path"));
            return null;
        }
        final boolean admitted =
                checked.persons().stream()
                        .anyMatch(
                                person ->
                                        person.login().equals(login)
                                                && person.admitted()
                                                && person.hosts().contains(host.host()));
        if (!admitted) {
            return null;
        }
        return ordered(
                "project",
                project.name(),
                "host",
                host.host(),
                "host_account",
                login,
                "host_card",
                RegistryReadCommand.cardAnswer(host.host(), card),
                "homeserver",
                homeserver == null
                        ? null
                        : AddressForm.isUnixSocket((String) homeserver)
                                ? homeserver
                                : withoutUserinfo((String) homeserver),
                "source",
                withoutUserinfo(source),
                "access_repository",
                withoutUserinfo(url),
                "revision",
                result.commit(),
                "signers",
                checked.signers(),
                "admins",
                checked.persons().stream()
                        .filter(AccessPersons.Person::admitted)
                        .filter(person -> person.role().equals("admin"))
                        .map(person -> IdentityFacts.contact(state, person.login()))
                        .toList());
    }

    /**
     * An address as the answer prints it: of a URL, its authority up to its last {@code @} left out
     * but an ssh user, as {@link AddressForm#withoutUserinfo} cuts it for every address Porelo
     * keeps or prints; this one is never fetched.
     */
    private static String withoutUserinfo(final String address) {
        return AddressForm.withoutUserinfo(address);
    }

    /** Why a registry that accepted no commit, or no owners.yml, answers no project. */
    private static String unaccepted(final Organization state) {
        final Organization.@Nullable Refusal refused =
                state.refusals().stream()
                        .filter(refusal -> refusal.path().equals("owners.yml"))
                        .reduce((first, last) -> last)
                        .orElse(state.lastRefusal());
        final String why =
                refused != null
                        ? refused.revision() + " " + refused.path() + ": " + refused.why()
                        : state.warnings().isEmpty()
                                ? "nothing was accepted"
                                : String.join("; ", state.warnings());
        return Userinfo.leftOutOf(
                (state.revision() == null
                                ? "the registry accepted no commit"
                                : "the registry accepted no owners.yml")
                        + ", so it names no project to join: "
                        + why);
    }

    /** A host's access file in the registry, by the host's label. */
    private static final java.util.regex.Pattern ACCESS_FILE =
            java.util.regex.Pattern.compile("hosts/([^/]+)/access\\.yml");

    /**
     * Names each host whose access file a commit changed and the read refused: the projects it
     * names are not known, and leaving the host out would answer it as naming none.
     */
    private static void refusedAccessFiles(final Organization state, final List<Object> refused) {
        for (Organization.Refusal refusal : state.refusals()) {
            final java.util.regex.Matcher path = ACCESS_FILE.matcher(refusal.path());
            if (!path.matches()) {
                continue;
            }
            final String host = path.group(1);
            refused.add(
                    ordered(
                            "project",
                            null,
                            "host",
                            host,
                            "access_repository",
                            null,
                            "why",
                            Userinfo.leftOutOf(
                                    "the registry refused "
                                            + refusal.path()
                                            + " at "
                                            + refusal.revision()
                                            + ": "
                                            + refusal.why()),
                            // Who signs the host's files is who can file it again.
                            "admins",
                            state.hostAdmins().getOrDefault(host, List.of()).stream()
                                    .map(admin -> IdentityFacts.contact(state, admin.login()))
                                    .toList()));
        }
    }

    /**
     * The machine as this check runs it: every command in a chosen environment, git with none of
     * the caller's configuration, repository or replacements, and ssh's agent, the proxy, what the
     * fetch is lent and the variables of the account's git tokens kept, so the forge is reached as
     * a follow reaches it.
     *
     * <p>Each clone is made by the check's own {@code git init} without that configuration, so it
     * holds no hook, monitor or helper of anybody else's. Its home is an empty directory: libcurl
     * answers a forge from {@code $HOME/.netrc}, and from the password database's home without one,
     * and git reads attributes under it; the vault's helper is told the account's own.
     */
    static PoreloContext sealed(final PoreloContext machine, final Path home) {
        final Map<String, String> base = new LinkedHashMap<>(RegistryGit.ENVIRONMENT);
        base.put("HOME", home.toString());
        // The vault's helper is Java, which reads its variables and arguments as ASCII under C.
        base.put("LC_ALL", HelperEnvironment.LOCALE);
        final List<String> passed = new ArrayList<>(PASSED);
        passed.addAll(credentialVariables(machine));
        for (String name : passed) {
            final String value = machine.environment().apply(name);
            if (value != null) {
                base.put(name, value);
            }
        }
        final org.fuin.porelo.core.process.CommandRunner runner = machine.runner();
        return new PoreloContext(
                command -> runner.run(command.withChosenEnvironment(base)),
                machine.paths(),
                machine.exec(),
                machine.environment(),
                machine.recognizesTasks(),
                machine.prompt(),
                machine.input(),
                machine.machineName(),
                machine.hostVault());
    }

    /**
     * The variables the account's git tokens name, which {@code porelo vault credential} reads when
     * git starts it; never one git, libcurl, ssh, the loader, the shell or the JVM acts on.
     */
    private static List<String> credentialVariables(final PoreloContext machine) {
        return machine.credentialRegistry().all().stream()
                .filter(credential -> credential.source() == Credential.Source.ENVIRONMENT)
                .filter(credential -> credential.kind() != Credential.Kind.SSH_KEY)
                .filter(credential -> credential.serves("git"))
                .map(Credential::id)
                .filter(name -> name.matches("[A-Za-z_][A-Za-z0-9_]*"))
                .filter(name -> !ACTED_ON.matcher(name).matches())
                .toList();
    }

    /** Variables a program the fetch starts acts on, whatever a credential calls them. */
    private static final java.util.regex.Pattern ACTED_ON =
            java.util.regex.Pattern.compile(
                    "(?i)(?:GIT_|LD_|SSL|CURL_|SSH_|XDG_|LC_|MALLOC_|OPENSSL|GNUTLS|NSS_|KRB5"
                            + "|BASH_|JAVA_|JDK_|_JAVA_|GLIBC_)\\w*|\\w*_PROXY"
                            + "|PATH|HOME|PORELO_VAULT|IFS|ENV|SHELLOPTS|BASHOPTS|CDPATH|PS4|TMPDIR|LANG"
                            + "|LANGUAGE|TZ|TERM|NETRC|GCONV_PATH|LOCPATH|NLSPATH|HOSTALIASES"
                            + "|RES_OPTIONS|LOCALDOMAIN");

    /**
     * What of the caller's environment the fetch needs to reach the forge; nothing of git's, whose
     * global and system configuration stay unread, and nothing of the account's directories.
     */
    private static final List<String> PASSED =
            List.of(
                    "SSH_AUTH_SOCK",
                    "http_proxy",
                    "https_proxy",
                    "no_proxy",
                    "all_proxy",
                    "HTTP_PROXY",
                    "HTTPS_PROXY",
                    "NO_PROXY",
                    "ALL_PROXY");

    /**
     * The key the registry holds for the project's admin: the accepted card of the login the host's
     * access file names, whose key is the one that file pins for it.
     */
    private static @Nullable String adminKey(
            final Organization state,
            final Organization.Host host,
            final Organization.Project project) {
        final Organization.@Nullable Person card = accepted(state, project.admin());
        if (card == null) {
            return null;
        }
        return host.admins().stream()
                        .anyMatch(
                                admin ->
                                        admin.login().equals(project.admin())
                                                && admin.fingerprint().equals(card.fingerprint()))
                ? card.key()
                : null;
    }

    private static Organization.@Nullable Person accepted(
            final Organization state, final String login) {
        return state.people().stream()
                .filter(person -> person.login().equals(login))
                .filter(person -> !person.refused())
                .findFirst()
                .orElse(null);
    }

    private static Map<String, @Nullable Object> refusal(
            final Organization state,
            final Organization.Host host,
            final Organization.Project project,
            final String why) {
        final String url = project.accessRepository();
        return ordered(
                "project",
                project.name(),
                "host",
                host.host(),
                "access_repository",
                withoutUserinfo(url),
                "why",
                Userinfo.leftOutOf(why),
                // Nothing of the access file verified: the admin the registry names is who to ask.
                "admins",
                List.of(IdentityFacts.contact(state, project.admin())));
    }

    /** A JSON object whose keys stay in the order given: name, value, name, value. */
    private static Map<String, @Nullable Object> ordered(final @Nullable Object... pairs) {
        final Map<String, @Nullable Object> map = new LinkedHashMap<>();
        for (int at = 0; at < pairs.length; at += 2) {
            map.put(String.valueOf(pairs[at]), pairs[at + 1]);
        }
        return map;
    }

    private static void deleteTree(final Path root) {
        try (java.util.stream.Stream<Path> entries = Files.walk(root)) {
            entries.sorted(java.util.Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException ex) {
                                    // A scratch directory that will not go is not worth failing.
                                }
                            });
        } catch (IOException ex) {
            // As above.
        }
    }

    private int refuse(final int code, final String message) {
        final PrintWriter err = spec.commandLine().getErr();
        err.println("porelo: " + RegistryVerifyCommand.printable(message));
        err.flush();
        return code;
    }
}
