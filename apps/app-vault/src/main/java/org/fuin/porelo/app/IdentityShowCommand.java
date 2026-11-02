package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.fuin.porelo.core.project.ProjectSigners;
import org.fuin.porelo.organization.git.HttpForgeDirectory;
import org.fuin.porelo.organization.git.RegistryState;
import org.fuin.porelo.vault.AgentKey;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.PersonCardChain;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Answers who the person on this machine is, from the card kept here: public values only, and what
 * each registry this machine read, the agent and the forge say about it.
 *
 * <p>A card is answered whatever its state: the agent, the revocation and the card's own signatures
 * are fields of the answer, so an interface can show the card while it waits for the key.
 */
@Command(
        name = "show",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description =
                "Shows your card, whether your agent holds its key, and where it is filed and"
                        + " listed.")
public class IdentityShowCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Option(names = "--json", description = "Answers the card and where it stands as JSON.")
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
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        final IdentityStore store = new IdentityStore(machine);
        final Map<String, @Nullable Object> answer;
        try {
            final Optional<PersonCard> kept = store.card();
            if (kept.isEmpty()) {
                return IdentityFacts.refuse(
                        out,
                        err,
                        json,
                        65,
                        "no-identity",
                        "no card is kept here; make one with porelo identity create",
                        Map.of());
            }
            answer = answer(machine, kept.get());
        } catch (IOException | CardException ex) {
            err.println(
                    "porelo: cannot read "
                            + RegistryVerifyCommand.printable(String.valueOf(ex.getMessage())));
            err.flush();
            return 66;
        }
        if (json) {
            out.println(Json.write(IdentityFacts.printable(answer)));
        } else {
            lines(out, answer);
        }
        out.flush();
        return 0;
    }

    /** The answer, in the order the contract names its fields. */
    private Map<String, @Nullable Object> answer(final PoreloContext machine, final PersonCard card)
            throws IOException {
        final List<IdentityFacts.Registry> registries = IdentityFacts.registries(machine);
        final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
        answer.put("login", card.login());
        answer.put("name", card.name());
        answer.put("email", card.email().isEmpty() ? null : card.email());
        answer.put("fingerprint", card.fingerprint());
        answer.put("key", card.key());
        answer.put("created", card.created().toString());
        answer.put("expires", card.expires().toString());
        answer.put(
                "predecessor",
                card.predecessor().isEmpty() ? null : PersonCard.fingerprint(card.predecessor()));
        answer.put("card", card.body() + String.join("", card.signatures()));
        final String agent = agent(machine, card);
        answer.put("agent", agent);
        answer.put("agent_holds_key", agent.equals("holds"));
        answer.put("revoked", IdentityFacts.revoked(machine, registries, card.key()));
        answer.put("card_refused", refused(card));
        final List<Map<String, @Nullable Object>> filed = new ArrayList<>();
        for (final IdentityFacts.Registry registry : registries) {
            filed.add(filed(registry, card));
        }
        answer.put("registry", filed);
        answer.put(
                "forge",
                IdentityFacts.forgeAnswer(forges, registries, card.login(), card.fingerprint()));
        answer.put("pending", pending(machine));
        final List<Map<String, Object>> sole = sole(registries, card.login());
        sole.addAll(soleInProjects(machine, card));
        answer.put("sole_without_backup", sole);
        return answer;
    }

    /**
     * Each registry and host this machine reads where the person is the one owner or admin and has
     * no backup key, so the interface can offer to make one.
     */
    private static List<Map<String, Object>> sole(
            final List<IdentityFacts.Registry> registries, final String login) {
        final List<Map<String, Object>> sole = new ArrayList<>();
        for (final IdentityFacts.Registry registry : registries) {
            for (final RegistryState.Sole one : registry.state().soleWithoutBackup()) {
                if (one.login().equals(login)) {
                    final boolean host = one.host() != null;
                    sole.add(
                            Map.of(
                                    "role",
                                    one.role(),
                                    "scope",
                                    Map.of(
                                            "kind",
                                            host ? "host" : "registry",
                                            "name",
                                            host
                                                    ? java.util.Objects.requireNonNull(one.host())
                                                    : registry.known().origin())));
                }
            }
        }
        return sole;
    }

    /**
     * Each followed project whose signers in force are the person's key alone, with no backup key
     * behind it.
     */
    private static List<Map<String, Object>> soleInProjects(
            final PoreloContext machine, final PersonCard card) throws IOException {
        final List<Map<String, Object>> sole = new ArrayList<>();
        for (final FollowedSigners.Signers signers : FollowedSigners.all(machine)) {
            final String key = ProjectSigners.soleWithoutBackup(signers.entries());
            if (key != null && ProjectSigners.fingerprint(key).equals(card.fingerprint())) {
                sole.add(
                        Map.of(
                                "role",
                                "project-admin",
                                "scope",
                                Map.of("kind", "project", "name", signers.project())));
            }
        }
        return sole;
    }

    /**
     * Whether the agent holds the card's key: {@code holds}, {@code not-held} or {@code absent}.
     */
    private static String agent(final PoreloContext machine, final PersonCard card) {
        final Optional<List<AgentKey>> served = IdentityFacts.served(machine);
        if (served.isEmpty()) {
            return "absent";
        }
        for (final AgentKey key : served.get()) {
            if (IdentityFacts.fingerprint(key.keyBlob()).equals(card.fingerprint())) {
                return "holds";
            }
        }
        return "not-held";
    }

    /** Why the card does not verify, as {@code card verify} says it, or {@code null}. */
    private static @Nullable String refused(final PersonCard card) {
        try {
            PersonCardChain.check(card, List.of(), card.fingerprint());
            return null;
        } catch (CardException ex) {
            return String.valueOf(ex.getMessage());
        }
    }

    /** Where the card stands in one registry. */
    private static Map<String, @Nullable Object> filed(
            final IdentityFacts.Registry registry, final PersonCard card) {
        final RegistryState state = registry.state();
        final RegistryState.@Nullable Person person =
                state.people().stream()
                        .filter(each -> each.login().equals(card.login()))
                        .findFirst()
                        .orElse(null);
        final Map<String, @Nullable Object> entry = new LinkedHashMap<>();
        entry.put("address", registry.known().origin());
        if (person == null) {
            entry.put("filed", "absent");
        } else if (person.state().equals(RegistryState.REFUSED)) {
            entry.put("filed", "refused");
            entry.put("why", person.why());
        } else if (person.state().equals(RegistryState.TRUSTED)
                && person.fingerprint().equals(card.fingerprint())) {
            entry.put("filed", "done");
        } else {
            entry.put("filed", "pending");
        }
        entry.put("revision", state.commit());
        // A card with no predecessor replacing an accepted one of another key: a lost key, which
        // only an owner may file.
        final boolean mayFile =
                !(card.predecessor().isEmpty()
                        && person != null
                        && !person.state().equals(RegistryState.REFUSED)
                        && !person.fingerprint().equals(card.fingerprint()));
        entry.put("may_file", mayFile);
        entry.put("who_may", mayFile ? List.of() : IdentityFacts.owners(state));
        return entry;
    }

    /**
     * The identity operations started and not finished, read leniently: a record that does not
     * parse is no operation this can name.
     */
    private static List<Map<String, @Nullable Object>> pending(final PoreloContext machine)
            throws IOException {
        final Path directory = machine.paths().xdg().state().resolve("identity").resolve("ops");
        final List<Map<String, @Nullable Object>> pending = new ArrayList<>();
        if (!IdentityStore.privateDirectoryFound(machine.paths().xdg().state(), directory)) {
            return pending;
        }
        final List<Path> records;
        try (var listed = Files.list(directory)) {
            records =
                    listed.filter(path -> path.getFileName().toString().endsWith(".json"))
                            .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                            .sorted()
                            .toList();
        }
        for (final Path record : records) {
            final Object read;
            try {
                read = Json.parse(Files.readString(record, StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException ex) {
                continue;
            }
            if (read instanceof Map<?, ?> map
                    && Boolean.FALSE.equals(map.get("done"))
                    && map.get("id") instanceof String id
                    && map.get("action") instanceof String action) {
                final Map<String, @Nullable Object> operation = new LinkedHashMap<>();
                operation.put("id", id);
                operation.put("action", action);
                pending.add(operation);
            }
        }
        return pending;
    }

    /** The answer as lines, a field and its value on each. */
    private static void lines(final PrintWriter out, final Map<String, @Nullable Object> answer) {
        for (final String field :
                List.of(
                        "login",
                        "name",
                        "email",
                        "fingerprint",
                        "created",
                        "expires",
                        "predecessor",
                        "agent",
                        "revoked",
                        "card_refused")) {
            final Object value = answer.get(field);
            if (value != null) {
                out.println(field + " " + RegistryVerifyCommand.printable(value.toString()));
            }
        }
        if (answer.get("registry") instanceof List<?> registries) {
            for (final Object each : registries) {
                if (each instanceof Map<?, ?> entry) {
                    out.println(
                            "registry "
                                    + RegistryVerifyCommand.printable(
                                            entry.get("address") + " " + entry.get("filed")));
                }
            }
        }
        if (answer.get("forge") instanceof Map<?, ?> forge) {
            out.println(
                    "forge "
                            + RegistryVerifyCommand.printable(
                                    forge.get("keys_url") + " listed " + forge.get("key_listed")));
        }
        if (answer.get("pending") instanceof List<?> operations) {
            for (final Object each : operations) {
                if (each instanceof Map<?, ?> operation) {
                    out.println(
                            "pending "
                                    + RegistryVerifyCommand.printable(
                                            operation.get("id") + " " + operation.get("action")));
                }
            }
        }
    }
}
