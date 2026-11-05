package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.fuin.porelo.organization.gittree.GitTreeStore;
import org.fuin.porelo.organization.gittree.GitTreeWrites;
import org.fuin.porelo.organization.gittree.HttpForgeDirectory;
import org.fuin.porelo.organization.gittree.HttpForgeDirectory.TokenAccount;
import org.fuin.porelo.organization.gittree.HttpForgeDirectory.TokenState;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * Tests for {@code org create --check}. Harm guarded: a need that looks ready and refuses at the
 * founding, or asking that changes something or says a token.
 */
class OrgCreateCheckTest {

    private static final String SSH = "git@github.com:acme/organization.git";

    private static final String HTTPS = "https://github.com/acme/organization";

    @TempDir Path dir;

    private RegistryCheckout registry;

    private String aliceKey;

    private boolean shut;

    private GitTreeWrites.RepositoryState state = GitTreeWrites.RepositoryState.EMPTY;

    private @Nullable IOException unreachable;

    private TokenAccount token = new TokenAccount(TokenState.ACCEPTED, "alice");

    private Optional<Boolean> owns = Optional.of(true);

    private Optional<List<String>> authentication = Optional.empty();

    private Optional<List<String>> signing = Optional.empty();

    private OrgCreateCheck.@Nullable Forge forge;

    private class StandIn implements OrgCreateCheck.Forge {
        @Override
        public GitTreeWrites.RepositoryState repository() throws IOException {
            if (unreachable != null) {
                throw unreachable;
            }
            return state;
        }

        @Override
        public boolean vaultShut() {
            return shut;
        }

        @Override
        public HttpForgeDirectory.TokenAccount token() {
            return token;
        }

        @Override
        public Optional<Boolean> owns(final String login) {
            return owns;
        }

        @Override
        public Optional<List<String>> authenticationKeys(final String login) {
            return authentication;
        }

        @Override
        public Optional<List<String>> signingKeys(final String login) {
            return signing;
        }
    }

    @BeforeEach
    void machine() throws Exception {
        registry = new RegistryCheckout(dir);
        final TestOrganization organization = new TestOrganization(registry);
        organization.filed("alice");
        IdentityRun.keep(registry, organization.card("alice"));
        // Its card, not its organization: this machine has none yet.
        Files.deleteIfExists(registry.context().paths().xdg().config().resolve("organization.yml"));
        registry.agent("alice");
        aliceKey = registry.key("alice");
        authentication = Optional.of(List.of(aliceKey));
        signing = Optional.of(List.of());
    }

    @AfterEach
    void tidyUp() throws Exception {
        registry.close();
        PoreloContext.forgetAsked();
    }

    private int check(final String... more) {
        registry.out.getBuffer().setLength(0);
        registry.err.getBuffer().setLength(0);
        final CommandLine cli = PoreloCli.commandLine(registry.context());
        cli.setOut(new PrintWriter(registry.out));
        cli.setErr(new PrintWriter(registry.err));
        final OrgCreateCommand create =
                (OrgCreateCommand)
                        cli.getSubcommands().get("org").getSubcommands().get("create").getCommand();
        create.asker = (machine, address) -> forge == null ? new StandIn() : forge;
        final List<String> all = new ArrayList<>(List.of("org", "create", "--check"));
        all.addAll(List.of(more));
        return cli.execute(all.toArray(String[]::new));
    }

    private Map<?, ?> need(final String name) {
        final Object needs = registry.answer().get("needs");
        return ((List<?>) needs)
                .stream()
                        .map(each -> (Map<?, ?>) each)
                        .filter(each -> name.equals(each.get("name")))
                        .findFirst()
                        .orElseThrow();
    }

    private void says(
            final String name, final String needState, final String next, final String... args) {
        final List<String> all =
                new ArrayList<>(List.of(args.length == 0 ? SSH : args[0], "--json"));
        all.addAll(Stream.of(args).skip(args.length == 0 ? 0 : 1).toList());
        assertThat(check(all.toArray(String[]::new)))
                .as("an answer, whatever it says: %s", registry.err)
                .isZero();
        final Map<?, ?> need = need(name);
        assertThat(need.get("state")).as("how %s stands: %s", name, need).isEqualTo(needState);
        assertThat(String.valueOf(need.get("next"))).as("what to do next").contains(next);
        assertThat(registry.answer().get("ready"))
                .as("only a missing need makes it not ready")
                .isEqualTo(!"missing".equals(needState));
    }

    @Test
    void everyNeedMetIsReadyAndThePushIsNotKnownUntilCreated() {
        assertThat(check(SSH, "--json")).as("what it said: %s", registry.err).isZero();

        assertThat(registry.answer().get("ready")).as("nothing is missing").isEqualTo(true);
        assertThat(need("key-listed").get("state")).as("to log in").isEqualTo("ready");
        assertThat(need("empty").get("state")).as("it holds nothing").isEqualTo("ready");
        assertThat(need("push").get("state"))
                .as("nothing but a push says this")
                .isEqualTo("not-known-until-created");
        assertThat(registry.answer().get("followable")).as("an ssh address").isEqualTo(true);
    }

    @Test
    void askingChangesNothing() throws Exception {
        final List<Path> before = files();

        assertThat(check(SSH, "--json")).as("what it said: %s", registry.err).isZero();

        assertThat(files()).as("no file of this machine is new").isEqualTo(before);
        assertThat(OrgAccess.repository(registry.context()))
                .as("the machine does not follow it")
                .isEmpty();
    }

    private List<Path> files() throws IOException {
        try (Stream<Path> found = Files.walk(dir)) {
            return found.filter(Files::isRegularFile).sorted().toList();
        }
    }

    @Test
    void theTextSaysEachNeedAndItsNextStep() {
        state = GitTreeWrites.RepositoryState.NOT_THERE;

        assertThat(check(SSH)).as("what it said: %s", registry.err).isZero();

        assertThat(registry.out.toString())
                .as("a line a person reads")
                .contains("repository missing: the repository does not exist")
                .contains("next: make it on the forge first, empty");
    }

    @Test
    void noCardNamesHowToMakeOne() throws Exception {
        Files.delete(new IdentityStore(registry.context()).cardFile());

        says("card", "missing", "porelo identity create");
    }

    @Test
    void aKeyTheAgentDoesNotServeIsMissing() throws Exception {
        registry.agent();

        says("key", "missing", "add it to your agent");
    }

    @Test
    void noTokenNamesHowToDeclareOne() {
        token = new TokenAccount(TokenState.NONE, null);

        says("token", "missing", "porelo credentials declare https://github.com");
    }

    @Test
    void aRefusedTokenIsMissing() {
        token = new TokenAccount(TokenState.REFUSED, null);

        says("token", "missing", "porelo credentials declare");
    }

    @Test
    void anotherPersonsTokenIsSaidAndWhoOwnsItStillDecides() {
        token = new TokenAccount(TokenState.ACCEPTED, "bob");

        says("token", "ready", "");
        assertThat(need("token").get("words")).asString().contains("bob").contains("alice");

        owns = Optional.of(false);
        says("owner", "missing", "make alice one");
    }

    @Test
    void aShutVaultIsNotALackOfToken() {
        token = new TokenAccount(TokenState.NONE, null);
        shut = true;

        says("token", "cannot-tell", "porelo vault unlock");
    }

    @Test
    void theRepositoryIsAskedBeforeTheTokenAndSaidAfterIt() {
        final List<String> asked = new ArrayList<>();
        final OrgCreateCheck.Forge order =
                new StandIn() {
                    @Override
                    public GitTreeWrites.RepositoryState repository() throws IOException {
                        asked.add("repository");
                        return super.repository();
                    }

                    @Override
                    public HttpForgeDirectory.TokenAccount token() {
                        asked.add("token");
                        return super.token();
                    }
                };
        forge = order;

        assertThat(check(SSH, "--json")).as("what it said: %s", registry.err).isZero();

        assertThat(asked)
                .as("reading it opens a shut vault at a terminal, which the token needs")
                .containsExactly("repository", "token");
        assertThat(registry.answer().get("needs").toString().indexOf("name=token"))
                .as("and a person still meets the token first")
                .isLessThan(registry.answer().get("needs").toString().indexOf("name=repository"));
    }

    @Test
    void aCardNotSignedAsItMustBeIsMissing() throws Exception {
        final Path file = new IdentityStore(registry.context()).cardFile();
        final String kept = Files.readString(file);
        Files.writeString(
                file,
                kept.replace(
                        "-----BEGIN SSH SIGNATURE-----",
                        "# a line more\n-----BEGIN SSH SIGNATURE-----"));

        says("card", "missing", "porelo identity show");
    }

    @Test
    void aForgeLoginInCapitalsIsTheCardsLogin() {
        token = new TokenAccount(TokenState.ACCEPTED, "Alice");

        says("token", "ready", "");
    }

    @Test
    void aForgeNotReachedCannotTell() {
        token = new TokenAccount(TokenState.NOT_REACHED, null);

        says("token", "cannot-tell", "can be reached");
    }

    @Test
    void anAppsTokenCannotSayWhose() {
        token = new TokenAccount(TokenState.CANNOT_SAY, null);

        says("token", "cannot-tell", "your account's");
    }

    @Test
    void notOwningIsMissing() {
        owns = Optional.of(false);

        says("owner", "missing", "make alice one");
    }

    @Test
    void anOwnerTheForgeDoesNotNameCannotTell() {
        owns = Optional.empty();

        says("owner", "cannot-tell", "members");
    }

    @Test
    void aKeyNotListedIsMissing() {
        authentication = Optional.of(List.of());

        says("key-listed", "missing", "github.com");
    }

    @Test
    void aSigningKeyIsListedOnEitherAddressAndSaysSo() {
        authentication = Optional.of(List.of());
        signing = Optional.of(List.of(aliceKey));

        says("key-listed", "ready", "");
        assertThat(need("key-listed").get("words")).asString().contains("to sign with");
        says("key-listed", "ready", "", HTTPS);
    }

    @Test
    void aSigningListUnderARateLimitCannotTell() {
        authentication = Optional.of(List.of());
        signing = Optional.empty();

        says("key-listed", "cannot-tell", "later");
    }

    @Test
    void aHostKeyNotTrustedNamesTrustHost() {
        unreachable = new GitTreeStore.Unreachable("Host key verification failed");

        says("host-key", "missing", "porelo credentials trust-host github.com");
    }

    @Test
    void aRepositoryThatCannotBeReadSaysWhatMayRead() {
        unreachable = new GitTreeStore.Unreachable("cannot read");

        says("repository", "missing", "your key may read it");
        says("repository", "missing", "the token may read it", HTTPS);
    }

    @Test
    void aRepositoryNotThereIsMissing() {
        state = GitTreeWrites.RepositoryState.NOT_THERE;

        says("repository", "missing", "make it on the forge");
    }

    @Test
    void aRepositoryThatHoldsSomethingIsNotEmpty() {
        state = GitTreeWrites.RepositoryState.HOLDS;

        says("empty", "missing", "name an empty one");
        assertThat(String.valueOf(need("empty").get("next")))
                .as("the whole way on, for an address a machine can follow")
                .contains("porelo setup --organization " + SSH);
        says("empty", "missing", "name an empty one", HTTPS);
        assertThat(String.valueOf(need("empty").get("next")))
                .as("no machine follows an https address")
                .doesNotContain("setup");
    }

    @Test
    void anotherOrganizationHereIsMissingUnlessItIsReplaced() throws Exception {
        OrgAccess.settle(registry.context(), "git@github.com:other/organization.git");

        says("this-machine", "missing", "--replace");
        says("this-machine", "ready", "", SSH, "--replace");
    }

    @Test
    void anHttpsAddressCannotBeFollowedAndHasNoHostKey() {
        assertThat(check(HTTPS, "--json")).as("what it said: %s", registry.err).isZero();

        assertThat(registry.answer().get("followable"))
                .as("a machine follows by an ssh key of its own")
                .isEqualTo(false);
        assertThat(registry.answer().get("needs").toString())
                .as("no ssh is asked")
                .doesNotContain("host-key");
    }

    @Test
    void anIdHasNoPlaceInAQuestion() {
        assertThat(check(SSH, "--id", "one", "--json"))
                .as("nothing is changed, so nothing is named")
                .isEqualTo(64);
    }
}
