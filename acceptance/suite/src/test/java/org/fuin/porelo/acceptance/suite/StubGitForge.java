package org.fuin.porelo.acceptance.suite;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.fuin.porelo.machines.Ssh;
import org.jspecify.annotations.Nullable;

/**
 * A forge's git side on the machine, for a reviewer's pin to fetch a pull request's head from: a
 * loopback https server of its own, its certificate signed by a throwaway authority, serving
 * repositories by git's smart http through {@code git http-backend} to the scenario's token alone.
 *
 * <p>Its authority is trusted the way a person adds one for Porelo's git: appended to the account's
 * {@code ca-certificates.pem}, which is put back as it was when the forge stops. The token never
 * reaches the machine: the server is given the SHA-256 of the Basic credential git sends for it.
 */
final class StubGitForge {

    /** Where the forge's files lie on the machine while it runs. */
    static final String WORK = "\"$HOME/porelo-stub-git-forge\"";

    /**
     * The shell's words for the forge's address while it runs, for a script that names a pull
     * request on it: {@code "$(cat …/origin)/shop/app/pull/42"}.
     */
    static final String ORIGIN_IN_SHELL = "$(cat \"$HOME/porelo-stub-git-forge/origin\")";

    /** The account's added authorities, which Porelo's git trusts beside the system's. */
    static final String AUTHORITIES =
            "\"${XDG_CONFIG_HOME:-$HOME/.config}/porelo/ca-certificates.pem\"";

    /** The server, as a resource beside this class. */
    private static final String SERVER = "stub-git-forge.py";

    /** The user name git sends with a lent token, as Porelo's credential helper answers it. */
    private static final String USER = "x-access-token";

    private final ForgeStub.Script script;

    private @Nullable String origin;

    /**
     * Creates the forge over a scenario's connection; nothing runs until {@link #start}.
     *
     * @param script What runs a command on the machine.
     */
    StubGitForge(ForgeStub.Script script) {
        this.script = Objects.requireNonNull(script, "script");
    }

    /**
     * Starts the forge: its authority and certificate, its authority added to the account's, and
     * the server, which answers the token alone.
     *
     * @param token The token the scenario stored as the forge's read credential.
     * @throws IOException If the machine cannot be reached.
     */
    void start(String token) throws IOException {
        must(
                script.run(
                        "set -e; w="
                                + WORK
                                + "; [ ! -e \"$w\" ] || { echo \"a stub git forge is there already: $w\" >&2; exit 1; };"
                                + " umask 077; mkdir -p \"$w/served\"; cat > \"$w/"
                                + SERVER
                                + "\"",
                        resource(SERVER)),
                "its server written");
        must(
                script.run(
                        "set -e; umask 077; cat > " + WORK + "/credential.sha256", digest(token)),
                "its credential's digest written");
        must(script.run(certificates()), "its authority and certificate made and trusted");
        must(
                script.run(
                        "set -e; cd "
                                + WORK
                                + "; setsid python3 -I "
                                + SERVER
                                + " \"$PWD\" > forge.log 2>&1 < /dev/null & echo $! > pid"),
                "its server started");
        final Ssh.Output port =
                script.run(
                        "cd "
                                + WORK
                                + "; for i in $(seq 1 200); do [ -s port ] && break;"
                                + " kill -0 \"$(cat pid)\" 2>/dev/null || break; sleep 0.05; done;"
                                + " if [ -s port ]; then cat port; else cat forge.log >&2; exit 1; fi");
        must(port, "its server listening");
        final String started = "https://127.0.0.1:" + port.out().strip();
        must(
                script.run("cat > " + WORK + "/origin", started),
                "its address written for the scenario's scripts");
        origin = started;
    }

    /**
     * Tells whether the forge was started and not stopped since.
     *
     * @return {@code true} while it runs.
     */
    boolean running() {
        return origin != null;
    }

    /**
     * Returns the forge's address, which a pull request's address on it begins with.
     *
     * @return {@code https://127.0.0.1:<port>}.
     */
    String origin() {
        final String started = origin;
        if (started == null) {
            throw new IllegalStateException("the stub git forge is not started");
        }
        return started;
    }

    /**
     * Serves a repository on the machine as the one a pull request on this forge names, and reads
     * its facts there: the head branch's commit, the base branch's, and their merge base.
     *
     * @param url The pull request, on {@link #origin}.
     * @param repository The repository on the machine, for the shell.
     * @param head The branch the pull request is opened from.
     * @param base The branch it is opened against.
     * @return The facts, the repository named as the pin fetches it.
     * @throws IOException If the machine cannot be reached.
     */
    ForgeStub.Facts serve(String url, String repository, String head, String base)
            throws IOException {
        final URI address = URI.create(url);
        if (!("https://" + address.getRawAuthority()).equals(origin())) {
            throw new AssertionError(
                    "the pull request " + url + " is not on the stub git forge at " + origin());
        }
        final String path = repositoryPath(address.getRawPath());
        final Ssh.Output read =
                script.run(
                        "set -e; r="
                                + repository
                                + "; s="
                                + WORK
                                + "/served"
                                + path
                                + "; mkdir -p \"$(dirname \"$s\")\"; ln -sfn \"$r\" \"$s\";"
                                + " git -C \"$r\" rev-parse --verify "
                                + head
                                + "^{commit}; git -C \"$r\" rev-parse --verify "
                                + base
                                + "^{commit}; git -C \"$r\" merge-base "
                                + base
                                + " "
                                + head);
        must(read, "the pull request's repository served");
        final List<String> commits = read.out().strip().lines().toList();
        if (commits.size() != 3) {
            throw new AssertionError("the repository's commits could not be read:\n" + read.all());
        }
        return new ForgeStub.Facts(origin() + path, commits.get(0), commits.get(1), commits.get(2));
    }

    /**
     * Stops the server by its process id, puts the account's authorities back as they were and
     * removes the forge's files.
     *
     * @throws IOException If the machine cannot be reached.
     */
    void stop() throws IOException {
        must(
                script.run(
                        "w="
                                + WORK
                                + "; a="
                                + AUTHORITIES
                                + "; [ -d \"$w\" ] || exit 0;"
                                + " if [ -s \"$w/pid\" ]; then kill \"$(cat \"$w/pid\")\" 2>/dev/null || true; fi;"
                                + " if [ -e \"$w/authorities.kept\" ]; then cp -p -- \"$w/authorities.kept\" \"$a\";"
                                + " elif [ -e \"$w/authorities.made\" ]; then rm -f -- \"$a\"; fi;"
                                + " rm -rf -- \"$w\""),
                "the stub git forge stopped");
        origin = null;
    }

    /**
     * The command that makes the forge's authority and its server's certificate for 127.0.0.1,
     * drops the authority's key, and adds the authority to the account's, keeping what was there.
     */
    private static String certificates() {
        return "set -e; umask 077; cd "
                + WORK
                + "; a="
                + AUTHORITIES
                + "; openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 2"
                + " -subj /CN=porelo-stub-git-forge-authority -keyout ca.key -out ca.pem 2> openssl.log;"
                + " openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -subj /CN=127.0.0.1"
                + " -keyout server.key -out server.csr 2>> openssl.log;"
                + " printf 'subjectAltName=IP:127.0.0.1\\nbasicConstraints=CA:FALSE\\nextendedKeyUsage=serverAuth\\n'"
                + " > server.ext;"
                + " openssl x509 -req -in server.csr -CA ca.pem -CAkey ca.key -CAcreateserial -days 2"
                + " -extfile server.ext -out server.pem 2>> openssl.log;"
                + " rm -f ca.key;"
                + " mkdir -p \"$(dirname \"$a\")\";"
                + " if [ -e \"$a\" ]; then cp -p -- \"$a\" authorities.kept; else : > authorities.made; fi;"
                + " cat ca.pem >> \"$a\"; chmod 600 \"$a\"";
    }

    /**
     * Returns a pull request's repository path: its address's path without the pull request's two
     * last segments, as the review profile writes it.
     *
     * @param path The pull request's path, such as {@code /shop/app/pull/42}.
     * @return The repository's, such as {@code /shop/app}.
     */
    static String repositoryPath(String path) {
        final int number = path.lastIndexOf('/');
        final int kind = number <= 0 ? -1 : path.lastIndexOf('/', number - 1);
        if (kind <= 0 || !path.substring(0, kind).matches("(/[A-Za-z0-9._~-]{1,100}){2,8}")) {
            throw new AssertionError("no repository's path in " + path);
        }
        return path.substring(0, kind);
    }

    /**
     * Returns the SHA-256, in hex, of the Basic credential git sends for a token.
     *
     * @param token The token.
     * @return The digest the server compares with.
     */
    static String digest(String token) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest((USER + ":" + token).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("every Java platform has SHA-256", ex);
        }
    }

    private static String resource(String name) {
        try (InputStream in = StubGitForge.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new AssertionError("no " + name + " on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new AssertionError(name + " could not be read", ex);
        }
    }

    private static void must(Ssh.Output output, String what) {
        if (output.status() != 0) {
            throw new AssertionError("the stub git forge failed: " + what + "\n" + output.all());
        }
    }
}
