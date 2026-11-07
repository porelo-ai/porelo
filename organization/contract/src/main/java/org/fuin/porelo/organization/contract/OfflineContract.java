package org.fuin.porelo.organization.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.fuin.porelo.organization.OrganizationQuestions.Refused;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * What a host's offline check must guarantee against the key its admin was pinned by: a copy only
 * that pin vouches for, in which nobody changed what they were not allowed to, and which continues
 * what the host applied last. An adapter's test extends this and gives its fixture.
 */
public abstract class OfflineContract {

    private static final String OWNER = "mia";

    private static final String ADMIN = "hank";

    private static final String STRANGER = "mallory";

    private static final String LATER = "carol";

    private static final String HOST = "build";

    private static final String PROJECT = "demo";

    /**
     * Returns a new fixture, for one check alone.
     *
     * @return The fixture.
     * @throws Exception When it cannot be made.
     */
    protected abstract OfflineFixture fixture() throws Exception;

    /**
     * Runs every check against the adapter.
     *
     * @return One test per check.
     */
    @TestFactory
    Stream<DynamicTest> everyCheckHoldsForThisAdapter() {
        return checks().stream()
                .map(c -> DynamicTest.dynamicTest(c.name(), () -> c.body().run(fixture())));
    }

    /**
     * Returns the checks.
     *
     * @return Every check, by name.
     */
    public static List<Check<OfflineFixture>> checks() {
        return List.of(
                new Check<>("thePinnedKeyAnswersTheHostsState", OfflineContract::pinned),
                new Check<>("anotherPersonsKeyPinnedIsRefused", OfflineContract::anotherPin),
                new Check<>("anUnsignedHostChangeIsRefused", s -> unsigned(s, false)),
                new Check<>("aStrangersHostChangeIsRefused", s -> stranger(s, false)),
                new Check<>("anUnsignedHostChangeUnderALaterOneIsRefused", s -> unsigned(s, true)),
                new Check<>("aStrangersHostChangeUnderALaterOneIsRefused", s -> stranger(s, true)),
                new Check<>("aStrangersKeyBesideThePinSignsNothing", OfflineContract::besidePin),
                new Check<>("anOwnersChangeIsRefusedUnderThePin", OfflineContract::byOwner),
                new Check<>("thePinFollowsAHandoverItSigned", OfflineContract::handover),
                new Check<>("aHostIsGivenNoKeyOfAFirstSeenPerson", OfflineContract::firstSeen),
                new Check<>(
                        "aHostCarryingSomeRepositoriesIsToldWhichOnes",
                        OfflineContract::carriedSome),
                new Check<>("anOlderStateThanTheOneAppliedIsRefused", OfflineContract::older),
                new Check<>("aRewrittenHistoryIsRefused", OfflineContract::rewritten));
    }

    private static void arranged(final OfflineFixture store) throws IOException {
        store.hostWithAdmin(OWNER, HOST, ADMIN, PROJECT);
    }

    private static void bury(final OfflineFixture store, final boolean buried) throws IOException {
        if (buried) {
            store.fileCard(LATER);
        }
    }

    private static Set<String> pinOf(final OfflineFixture store) {
        return Set.of(store.fingerprint(ADMIN));
    }

    private static Map<String, Object> answered(final OfflineFixture store, final String since)
            throws IOException {
        try {
            return store.copy().check(HOST, pinOf(store), since);
        } catch (final Refused refused) {
            throw new AssertionError("the pinned key's own copy was refused", refused);
        }
    }

    private static String revision(final Map<String, Object> answer) {
        return String.valueOf(answer.get("revision"));
    }

    private static void refused(
            final OfflineFixture store, final Set<String> pin, final String since) {
        assertThatThrownBy(() -> store.copy().check(HOST, pin, since))
                .as("the host applies nothing this copy says")
                .isInstanceOfSatisfying(
                        Refused.class,
                        refusal -> assertThat(refusal.reason()).isEqualTo(Refused.REFUSED_STATE));
    }

    private static void pinned(final OfflineFixture store) throws Exception {
        arranged(store);

        final Map<String, Object> answer = answered(store, "");

        assertThat(revision(answer)).as("the revision it rests on").isNotBlank();
        assertThat(answer.get("admins"))
                .as("the host's admin, by the key that was pinned")
                .asInstanceOf(InstanceOfAssertFactories.list(Map.class))
                .anySatisfy(
                        admin -> {
                            assertThat(admin.get("login")).isEqualTo(ADMIN);
                            assertThat(admin.get("fingerprint"))
                                    .isEqualTo(store.fingerprint(ADMIN));
                        });
        assertThat(answer.get("host_card"))
                .as("what the host's card says")
                .asInstanceOf(InstanceOfAssertFactories.map(String.class, Object.class))
                .containsEntry("host", HOST)
                .containsEntry("address", HOST + ".example.org");
        assertThat(answer.get("projects"))
                .as("the project the host carries")
                .asInstanceOf(InstanceOfAssertFactories.list(Map.class))
                .anySatisfy(project -> assertThat(project.get("project")).isEqualTo(PROJECT));
        assertThat(answer.get("persons"))
                .as("the host's admin, not revoked")
                .asInstanceOf(InstanceOfAssertFactories.list(Map.class))
                .anySatisfy(
                        person -> {
                            assertThat(person.get("login")).isEqualTo(ADMIN);
                            assertThat(person.get("revoked")).isEqualTo(false);
                        });
    }

    private static void anotherPin(final OfflineFixture store) throws Exception {
        arranged(store);
        store.fileCard(STRANGER);

        refused(store, Set.of(store.fingerprint(STRANGER)), "");
    }

    private static void unsigned(final OfflineFixture store, final boolean buried)
            throws Exception {
        arranged(store);
        store.hostChangeNobodyAllowed(null, HOST);
        bury(store, buried);

        refused(store, pinOf(store), "");
    }

    private static void stranger(final OfflineFixture store, final boolean buried)
            throws Exception {
        arranged(store);
        store.fileCard(STRANGER);
        store.hostChangeNobodyAllowed(STRANGER, HOST);
        bury(store, buried);

        refused(store, pinOf(store), "");
    }

    /** Naming a stranger's key beside the right one must not make the stranger the host's. */
    private static void besidePin(final OfflineFixture store) throws Exception {
        arranged(store);
        store.fileCard(STRANGER);
        store.hostChangeNobodyAllowed(STRANGER, HOST);

        refused(store, Set.of(store.fingerprint(ADMIN), store.fingerprint(STRANGER)), "");
    }

    /** The walk allows an owner this, so only the pin tells a host about it. */
    private static void byOwner(final OfflineFixture store) throws Exception {
        arranged(store);
        store.fileCard(STRANGER);
        final String taken = revision(answered(store, ""));
        store.adminByOwner(OWNER, HOST, STRANGER);

        refused(store, pinOf(store), taken);
    }

    private static void handover(final OfflineFixture store) throws Exception {
        arranged(store);
        store.fileCard(LATER);
        final String taken = revision(answered(store, ""));
        store.handOverHost(ADMIN, HOST, LATER);

        final Map<String, Object> answer = answered(store, taken);

        assertThat(revision(answer))
                .as("the handover continues what was taken")
                .isNotEqualTo(taken);
        assertThat(answer.get("admins"))
                .as("the pin followed to the new admin")
                .asInstanceOf(InstanceOfAssertFactories.list(Map.class))
                .anySatisfy(admin -> assertThat(admin.get("login")).isEqualTo(LATER));
    }

    /**
     * Anyone with write access could file that card while the source says nothing, and whoever did
     * would get in as the host's admin, and their pushes be taken as that person's.
     */
    private static void firstSeen(final OfflineFixture store) throws Exception {
        arranged(store);
        store.firstSeenPerson(ADMIN, HOST, PROJECT, LATER);

        final Map<String, Object> answer = answered(store, "");

        assertThat(logins(answer.get("persons"))).as("no key of theirs").doesNotContain(LATER);
        assertThat(logins(answer.get("admins"))).as("no way in as admin").containsExactly(ADMIN);
        final Map<?, ?> project =
                assertThat(answer.get("projects"))
                        .asInstanceOf(InstanceOfAssertFactories.list(Map.class))
                        .hasSize(1)
                        .actual()
                        .get(0);
        assertThat(logins(project.get("signing_keys")))
                .as("no push taken as theirs")
                .contains(ADMIN)
                .doesNotContain(LATER);
        assertThat(logins(project.get("members"))).as("the role stays said").contains(ADMIN, LATER);
    }

    /**
     * A host told nothing of a subset takes every repository of the project, with a read key on
     * each central one it was never to carry.
     */
    private static void carriedSome(final OfflineFixture store) throws Exception {
        arranged(store);
        assertThat(onlyProject(answered(store, "")).get("repositories"))
                .as("a project carried whole names no repositories")
                .isNull();
        store.carrySome(ADMIN, HOST, PROJECT, List.of("one"));

        assertThat(onlyProject(answered(store, "")).get("repositories"))
                .as("the host is told exactly the repositories its card carries")
                .isEqualTo(List.of("one"));
    }

    private static Map<?, ?> onlyProject(final Map<String, Object> answer) {
        return assertThat(answer.get("projects"))
                .as("the host's one project")
                .asInstanceOf(InstanceOfAssertFactories.list(Map.class))
                .hasSize(1)
                .actual()
                .get(0);
    }

    private static List<Object> logins(final @Nullable Object rows) {
        return assertThat(rows)
                .asInstanceOf(InstanceOfAssertFactories.list(Map.class))
                .actual()
                .stream()
                .map(row -> row.get("login"))
                .toList();
    }

    private static void older(final OfflineFixture store) throws Exception {
        arranged(store);
        store.fileCard(LATER);
        final String applied = revision(answered(store, ""));
        store.serveOlderState();

        refused(store, pinOf(store), applied);
    }

    private static void rewritten(final OfflineFixture store) throws Exception {
        arranged(store);
        store.fileCard(LATER);
        final String applied = revision(answered(store, ""));
        store.rewriteHistory();

        refused(store, pinOf(store), applied);
    }
}
