package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.fuin.porelo.core.config.XdgPaths;
import org.fuin.porelo.core.credential.Credential;
import org.fuin.porelo.testing.FakeCommandRunner;
import org.fuin.porelo.vault.VaultEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

/**
 * {@code porelo vault credential --for <url>} answers git for an https address with the token
 * Porelo would lend for it itself, so a program reading a private repository passes only the
 * address: no entry name, no map from addresses to entries, and never a bare token it could keep.
 */
class VaultCredentialForTest {

    private static final char[] PASSPHRASE = "test-passphrase".toCharArray();

    private static final String FOR = "https://forge.example.org/acme/p.git";

    private static final String DECLARED = "declared-test-value";

    private static final String IMPLIED = "implied-test-value";

    private static final String FALLBACK = "fallback-test-value";

    @TempDir Path dir;

    private final StringWriter out = new StringWriter();

    private final StringWriter err = new StringWriter();

    private final AtomicInteger asked = new AtomicInteger();

    @AfterEach
    void forgetThePassphrase() {
        PoreloContext.forgetAsked();
    }

    private PoreloContext context() {
        return new PoreloContext(
                        new FakeCommandRunner(),
                        new PoreloPaths(XdgPaths.of(name -> null, dir), dir.resolve("bin")),
                        arguments -> 0,
                        name -> null)
                .asking(
                        prompt -> {
                            asked.incrementAndGet();
                            return null;
                        });
    }

    /** A vault holding these entries, opened in this process as a typed passphrase opens it. */
    private PoreloContext openVault(final Map<String, VaultEntry> entries) {
        final PoreloContext writing =
                context()
                        .asking(
                                prompt -> {
                                    asked.incrementAndGet();
                                    return PASSPHRASE.clone();
                                });
        writing.vault().write(entries, PASSPHRASE);
        assertThat(writing.openerAsking(false, new PrintWriter(Writer.nullWriter())))
                .as("the fixture opens its own vault")
                .isPresent();
        asked.set(0);
        return context();
    }

    /** A vault holding these entries that nothing in this process or account opens. */
    private PoreloContext shutVault(final Map<String, VaultEntry> entries) {
        final PoreloContext context = context();
        context.vault().write(entries, PASSPHRASE);
        return context;
    }

    private static void declare(final PoreloContext context, final String entry, final String match)
            throws Exception {
        new CredentialDeclarations(context)
                .declare(
                        new Credential(
                                entry,
                                Credential.Kind.TOKEN,
                                match,
                                null,
                                "git",
                                Credential.Source.VAULT));
    }

    private int run(final PoreloContext context, final String request, final String... arguments) {
        final VaultCredentialCommand command = new VaultCredentialCommand();
        command.setContext(context);
        command.standardOutputIsATerminal = () -> false;
        final InputStream before = System.in;
        System.setIn(new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)));
        try {
            return new CommandLine(command)
                    .setOut(new PrintWriter(out))
                    .setErr(new PrintWriter(err))
                    .execute(arguments);
        } finally {
            System.setIn(before);
        }
    }

    private static String request(final String host, final String path) {
        return "protocol=https\nhost="
                + host
                + "\n"
                + (path == null ? "" : "path=" + path + "\n")
                + "\n";
    }

    private PoreloContext declaredAndImplied() throws Exception {
        final PoreloContext context =
                openVault(
                        Map.of(
                                "forge-work", VaultEntry.of(DECLARED),
                                "git.token.forge.example.org", VaultEntry.of(IMPLIED),
                                "git.token", VaultEntry.of(FALLBACK)));
        declare(context, "forge-work", "https://forge.example.org/acme");
        return context;
    }

    private PoreloContext impliedOnly() {
        return openVault(
                Map.of(
                        "git.token.forge.example.org", VaultEntry.of(IMPLIED),
                        "git.token", VaultEntry.of(FALLBACK)));
    }

    private void assertNoTokenAnywhere() {
        for (final String token : List.of(DECLARED, IMPLIED, FALLBACK)) {
            assertThat(out.toString()).as("standard output").doesNotContain(token);
            assertThat(err.toString()).as("standard error").doesNotContain(token);
        }
    }

    @Test
    void aDeclaredCredentialAnswersWithItsToken() throws Exception {
        final PoreloContext context = declaredAndImplied();

        final int exit =
                run(context, request("forge.example.org", "acme/p.git"), "--for", FOR, "get");

        assertThat(exit).as(err.toString()).isZero();
        assertThat(out.toString())
                .as("what the account declared for the address wins over the names it implies")
                .isEqualTo("username=x-access-token\npassword=" + DECLARED + "\n");
        assertThat(err.toString()).doesNotContain(DECLARED);
        assertThat(asked).as("nobody is asked").hasValue(0);
    }

    @Test
    void withNothingDeclaredTheMostSpecificImpliedNameAnswers() throws Exception {
        final PoreloContext context = impliedOnly();

        final int exit = run(context, request("forge.example.org", "acme/p.git"), "--for", FOR);

        assertThat(exit).as(err.toString()).isZero();
        assertThat(out.toString()).isEqualTo("username=x-access-token\npassword=" + IMPLIED + "\n");
    }

    @Test
    void aDeclaredUserIsTheOneSent() throws Exception {
        final PoreloContext context = openVault(Map.of("forge-work", VaultEntry.of(DECLARED)));
        new CredentialDeclarations(context)
                .declare(
                        new Credential(
                                "forge-work",
                                Credential.Kind.TOKEN,
                                "https://forge.example.org",
                                "alice",
                                "git",
                                Credential.Source.VAULT));

        run(context, request("forge.example.org", "acme/p.git"), "--for", FOR);

        assertThat(out.toString()).contains("username=alice\n");
    }

    /** The address's own host and port, however git writes them, and its path or below it. */
    @ParameterizedTest
    @CsvSource({
        "forge.example.org,      acme/p.git",
        "FORGE.Example.ORG,      acme/p.git",
        "forge.example.org:443,  acme/p.git",
        "forge.example.org,      acme/p.git/info/refs",
        "forge.example.org,      /acme/p.git",
    })
    void theAddressItselfIsAnswered(final String host, final String path) throws Exception {
        final int exit = run(impliedOnly(), request(host, path), "--for", FOR);

        assertThat(exit).as(err.toString()).isZero();
        assertThat(out.toString()).contains("password=" + IMPLIED);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "other.example.org",
                "forge.example.org.other.example",
                "xforge.example.org",
                "forge.example.or",
                "forge.example.org:8443",
                "forge.example.org:444",
                "forge.exampIe.org",
                "forge.example.ırg",
                "forge.example.org@other.example.org",
            })
    void anotherHostOrPortGetsNothing(final String host) throws Exception {
        final int exit = run(declaredAndImplied(), request(host, "acme/p.git"), "--for", FOR);

        assertThat(out.toString()).as("a lookalike, another host or port gets no token").isEmpty();
        assertThat(exit).isZero();
        assertNoTokenAnywhere();
    }

    /** A path beside the address's, above it, or one that only climbs out of it gets nothing. */
    @ParameterizedTest
    @ValueSource(
            strings = {"acme/p2", "acme/p.git2", "acme", "other/p.git", "acme/p.git/../q.git", ""})
    void aPathBesideTheAddressGetsNothing(final String path) throws Exception {
        final int exit =
                run(
                        declaredAndImplied(),
                        request("forge.example.org", path.isEmpty() ? null : path),
                        "--for",
                        "https://forge.example.org/acme/p.git");

        assertThat(out.toString()).isEmpty();
        assertThat(exit).isZero();
        assertNoTokenAnywhere();
    }

    @Test
    void aPathBesideTheAddressWithoutItsSuffixGetsNothing() throws Exception {
        run(
                impliedOnly(),
                request("forge.example.org", "acme/p2"),
                "--for",
                "https://forge.example.org/acme/p");

        assertThat(out.toString()).isEmpty();
        assertNoTokenAnywhere();
    }

    @Test
    void anAddressOnItsOwnPortAnswersThatPortAlone() throws Exception {
        final String onItsPort = "https://forge.example.org:8443/acme/p.git";

        run(impliedOnly(), request("forge.example.org", "acme/p.git"), "--for", onItsPort);
        assertThat(out.toString()).as("443 is another port").isEmpty();

        run(impliedOnly(), request("forge.example.org:8443", "acme/p.git"), "--for", onItsPort);
        assertThat(out.toString()).contains("password=" + IMPLIED);
    }

    @Test
    void aRequestOverPlainHttpGetsNothing() throws Exception {
        final int exit =
                run(
                        impliedOnly(),
                        "protocol=http\nhost=forge.example.org\npath=acme/p.git\n\n",
                        "--for",
                        FOR);

        assertThat(exit).isZero();
        assertThat(out.toString()).isEmpty();
        assertNoTokenAnywhere();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ssh://git@forge.example.org/acme/p.git",
                "git@forge.example.org:acme/p.git",
                "http://forge.example.org/acme/p.git"
            })
    void anAddressOtherThanHttpsGetsNothing(final String address) throws Exception {
        final PoreloContext context =
                openVault(
                        Map.of(
                                "git.ssh.forge.example.org", VaultEntry.of(DECLARED),
                                "git.token.forge.example.org", VaultEntry.of(IMPLIED)));

        final int exit = run(context, request("forge.example.org", "acme/p.git"), "--for", address);

        assertThat(exit).isZero();
        assertThat(out.toString()).as("a key goes through the agent, not this helper").isEmpty();
        assertNoTokenAnywhere();
    }

    @Test
    void aShutVaultIsSaidOnOneLineAndFailsWithoutAPrompt() throws Exception {
        final PoreloContext context =
                shutVault(Map.of("git.token.forge.example.org", VaultEntry.of(IMPLIED)));

        final int exit = run(context, request("forge.example.org", "acme/p.git"), "--for", FOR);

        assertThat(exit).as("git's own failure follows").isNotZero();
        assertThat(out.toString()).isEmpty();
        assertThat(err.toString().strip().lines()).hasSize(1);
        assertThat(err.toString()).contains("shut");
        assertThat(asked).as("the caller is at no terminal: nobody is asked").hasValue(0);
        assertNoTokenAnywhere();
    }

    @Test
    void aShutVaultBehindADeclarationIsSaidTheSameWay() throws Exception {
        final PoreloContext context = shutVault(Map.of("forge-work", VaultEntry.of(DECLARED)));
        declare(context, "forge-work", "https://forge.example.org");

        final int exit = run(context, request("forge.example.org", "acme/p.git"), "--for", FOR);

        assertThat(exit).isNotZero();
        assertThat(out.toString()).isEmpty();
        assertThat(err.toString().strip().lines()).hasSize(1);
        assertThat(err.toString())
                .as("no entry name reaches the caller")
                .doesNotContain("forge-work");
        assertThat(asked).hasValue(0);
    }

    @Test
    void nothingHeldForTheAddressAnswersNothingAndNamesNoEntry() throws Exception {
        final PoreloContext context =
                openVault(Map.of("git.token.other.example.org", VaultEntry.of(IMPLIED)));

        final int exit = run(context, request("forge.example.org", "acme/p.git"), "--for", FOR);

        assertThat(exit).isZero();
        assertThat(out.toString()).isEmpty();
        assertThat(err.toString()).doesNotContain("git.token");
        assertNoTokenAnywhere();
    }

    @ParameterizedTest
    @ValueSource(strings = {"store", "erase"})
    void storeAndEraseAreIgnored(final String operation) throws Exception {
        final int exit =
                run(
                        declaredAndImplied(),
                        request("forge.example.org", "acme/p.git") + "password=x\n",
                        "--for",
                        FOR,
                        operation);

        assertThat(exit).isZero();
        assertThat(out.toString()).isEmpty();
        assertNoTokenAnywhere();
    }

    @ParameterizedTest
    @CsvSource({"--entry, forge-work", "--env, PORELO_TEST_TOKEN", "--file, /dev/null"})
    void forStandsAlone(final String option, final String value) throws Exception {
        final List<String> arguments = new ArrayList<>(List.of("--for", FOR, option, value));

        final int exit =
                run(
                        declaredAndImplied(),
                        request("forge.example.org", "acme/p.git"),
                        arguments.toArray(String[]::new));

        assertThat(exit).as("a usage error").isEqualTo(2);
        assertThat(out.toString()).isEmpty();
        assertThat(err.toString()).contains("--for");
        assertNoTokenAnywhere();
    }
}
