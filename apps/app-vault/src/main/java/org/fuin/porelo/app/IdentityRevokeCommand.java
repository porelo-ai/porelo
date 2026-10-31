package org.fuin.porelo.app;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.porelo.vault.AgentKey;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Replaces a lost or stolen key: a new card signed by the new key alone, which only those who
 * accept it at each place take, naming the old key revoked as they do.
 */
@Command(
        name = "revoke",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description =
                "Replaces a lost or stolen key with one the ssh-agent serves, and names who must"
                        + " accept the new card at each place.")
public class IdentityRevokeCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Option(names = "--lost", description = "The old key is lost or stolen.")
    private boolean lost;

    @Option(
            names = "--key",
            paramLabel = "FINGERPRINT|PUBFILE",
            description = "The new key, as the ssh-agent serves it.")
    private @Nullable String key;

    @Option(
            names = "--keepassxc",
            paramLabel = "DATABASE",
            description = "Makes the new key into KeePassXC; not available yet.")
    private @Nullable Path keepassxc;

    @Option(
            names = "--entry",
            paramLabel = "GROUP/NAME",
            description = "The KeePassXC entry, with --keepassxc.")
    private @Nullable String entry;

    @Option(
            names = "--dry-run",
            description = "Makes nothing; answers the places and who must accept.")
    private boolean dryRun;

    @Option(
            names = "--id",
            paramLabel = "ID",
            description = "The operation's id; the same id again answers the same operation.")
    private @Nullable String id;

    @Option(names = "--json", description = "Answers the operation and its places as JSON.")
    private boolean json;

    @Spec private CommandSpec spec;

    private @Nullable PoreloContext context;

    @Override
    public void setContext(PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final PoreloContext machine =
                java.util.Objects.requireNonNull(context, "picocli's factory sets the context");
        try {
            if (!lost) {
                throw new IdentityChange.Refused(
                        64, null, "revoke replaces a lost or stolen key: give --lost");
            }
            IdentityChange.checkId(id);
            if (dryRun && id != null) {
                throw new IdentityChange.Refused(
                        64, null, "--dry-run keeps nothing, so it takes no --id");
            }
            final String named = IdentityChange.keyChosen(key, keepassxc, entry);
            final IdentityOperation again =
                    IdentityChange.kept(machine, id, IdentityOperation.REVOKE_LOST);
            if (again != null) {
                IdentityChange.print(spec, json, IdentityChange.finish(machine, again));
                return 0;
            }
            // A replacement files nothing with the person's own key, so no forge is asked.
            final IdentityChange change = new IdentityChange(machine, IdentityChange.NO_FORGE);
            final PersonCard card = change.card();
            final String socket = change.socket();
            final AgentKey successor = change.successor(named, card.login(), socket);
            final String fingerprint = PersonCard.fingerprint(PersonCard.keyLine(successor));
            if (fingerprint.equals(card.fingerprint())) {
                throw new IdentityChange.Refused(
                        64, null, "the key " + fingerprint + " is the lost key itself");
            }
            if (dryRun) {
                dryRun(change.places(card.login(), IdentityOperation.REVOKE_LOST, "planned"));
                return 0;
            }
            final String text =
                    IdentityChange.signed(
                            card.login(),
                            card.name(),
                            card.email(),
                            successor,
                            IdentityChange.today(),
                            null);
            final List<IdentityOperation.Place> places =
                    change.places(card.login(), IdentityOperation.REVOKE_LOST, "pending");
            final IdentityOperation operation =
                    change.begin(
                                    id,
                                    new IdentityOperation(
                                            "",
                                            IdentityOperation.REVOKE_LOST,
                                            fingerprint,
                                            card.fingerprint(),
                                            text,
                                            IdentityOperation.allDone(places),
                                            places,
                                            false))
                            .operation();
            IdentityChange.print(spec, json, operation);
            return 0;
        } catch (IdentityChange.Refused ex) {
            return IdentityChange.refuse(spec, json, ex);
        } catch (IOException | CardException ex) {
            return IdentityChange.refuse(
                    spec,
                    json,
                    new IdentityChange.Refused(66, null, String.valueOf(ex.getMessage())));
        }
    }

    /**
     * Answers what a replacement would reach: each place, each host whose vault opened by the old
     * key is made again, and the sign-ins each such vault loses.
     */
    private void dryRun(final List<IdentityOperation.Place> places) {
        final List<Map<String, @Nullable Object>> hosts = new ArrayList<>();
        final List<Map<String, @Nullable Object>> signIns = new ArrayList<>();
        for (final IdentityOperation.Place place : places) {
            if (place.where().equals("host")) {
                final Map<String, @Nullable Object> host = new LinkedHashMap<>();
                host.put("host", place.name());
                host.put("vault", "reset");
                hosts.add(host);
                // Which entries a host vault holds is not known here yet: none is named.
                final Map<String, @Nullable Object> signIn = new LinkedHashMap<>();
                signIn.put("host", place.name());
                signIn.put("entries", List.of());
                signIns.add(signIn);
            }
        }
        final var out = spec.commandLine().getOut();
        if (json) {
            final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
            answer.put("places", places.stream().map(IdentityOperation.Place::answer).toList());
            answer.put("hosts", hosts);
            answer.put("sign_ins", signIns);
            out.println(Json.write(RegistryVerifyCommand.printable(answer)));
        } else {
            for (final IdentityOperation.Place place : places) {
                out.println(
                        RegistryVerifyCommand.printable(
                                place.where()
                                        + " "
                                        + place.name()
                                        + " planned, accepted by "
                                        + (place.whoMay().isEmpty()
                                                ? "nobody named"
                                                : String.join(
                                                        ", ",
                                                        place.whoMay().stream()
                                                                .map(
                                                                        IdentityOperation.Contact
                                                                                ::login)
                                                                .toList()))));
            }
            for (final Map<String, @Nullable Object> host : hosts) {
                out.println(
                        RegistryVerifyCommand.printable(
                                "host " + host.get("host") + " vault made again"));
            }
        }
        out.flush();
    }
}
