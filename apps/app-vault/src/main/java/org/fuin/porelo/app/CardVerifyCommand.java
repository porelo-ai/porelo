package org.fuin.porelo.app;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.PersonCardChain;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * Checks a person's card against a key already trusted, and writes nothing.
 *
 * <p>For a host that makes accounts from cards, and for anybody who must not take a card on its
 * word: the card is accepted only when it is the pinned key's, or a chain of changes of key, each
 * signed by both keys, leads back to it. A chain through a key on the revocation list is refused,
 * since a thief holding a stolen key could otherwise hand the identity on after an admin revoked
 * it. The card's own key on the list is answered, not refused, so the host can name what it drops.
 * It needs no vault, home or network. The answer is a contract for a program: its names are kept.
 */
@picocli.CommandLine.Command(
        name = "verify",
        mixinStandardHelpOptions = true,
        description = "Checks a person's card against a pinned key, and writes nothing.")
public class CardVerifyCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    @Parameters(index = "0", paramLabel = "CARD", description = "The card's file.")
    private Path card = Path.of("");

    @Option(
            names = "--pinned",
            required = true,
            paramLabel = "FINGERPRINT",
            description = "The key trusted for this person, SHA256:...")
    private String pinned = "";

    @Option(
            names = "--earlier",
            paramLabel = "CARD",
            description = "An earlier card of the same person, for a chain of changes; repeat.")
    private List<Path> earlier = new ArrayList<>();

    @Option(
            names = "--krl",
            paramLabel = "FILE",
            description = "A revocation list as ssh-keygen -k makes it.")
    private @Nullable Path krl;

    @Option(names = "--json", description = "Answers in JSON, for a program.")
    private boolean json;

    @Spec private CommandSpec spec;

    private PoreloContext context = PoreloContext.real();

    @Override
    public void setContext(final PoreloContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        final PrintWriter out = spec.commandLine().getOut();
        final PrintWriter err = spec.commandLine().getErr();
        if (!pinned.matches("SHA256:[A-Za-z0-9+/]{43}")) {
            return refuse(err, 65, "'" + printable(pinned) + "' is not a key's fingerprint");
        }
        final PersonCard checked;
        final List<PersonCard> before = new ArrayList<>();
        try {
            checked = read(card);
            for (final Path each : earlier) {
                before.add(read(each));
            }
        } catch (final CardException ex) {
            return refuse(err, 65, String.valueOf(ex.getMessage()));
        } catch (final IOException ex) {
            return refuse(err, 66, "cannot read " + ex.getMessage());
        }
        // Asked before the chain is sought, so a revoked key is walked around, not found after.
        Map<String, Boolean> listed = Map.of();
        final Path list = krl;
        if (list != null) {
            final Map<String, String> keys = new LinkedHashMap<>();
            keys.put(checked.fingerprint(), checked.key());
            before.forEach(each -> keys.put(each.fingerprint(), each.key()));
            if (!checked.predecessor().isEmpty()) {
                keys.put(PersonCard.fingerprint(checked.predecessor()), checked.predecessor());
            }
            before.stream()
                    .map(PersonCard::predecessor)
                    .filter(each -> !each.isEmpty())
                    .forEach(each -> keys.put(PersonCard.fingerprint(each), each));
            try {
                listed = revoked(list, keys);
            } catch (final IOException | IllegalStateException ex) {
                // Never skipped: a list that cannot be read would answer every key as kept.
                return refuse(
                        err,
                        66,
                        "cannot read the revocation list '"
                                + printable(list.toString())
                                + "': "
                                + ex.getMessage());
            }
        }
        final Map<String, Boolean> onList = listed;
        final List<String> chain;
        try {
            chain =
                    PersonCardChain.check(
                            checked,
                            before,
                            pinned,
                            fingerprint -> Boolean.TRUE.equals(onList.get(fingerprint)));
        } catch (final CardException ex) {
            return refuse(err, 65, String.valueOf(ex.getMessage()));
        }
        final boolean revoked = Boolean.TRUE.equals(onList.get(checked.fingerprint()));
        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("login", checked.login());
        answer.put("name", checked.name());
        answer.put("email", checked.email());
        answer.put("ssh", checked.key());
        answer.put("expires", checked.expires().toString());
        answer.put("revoked", revoked);
        answer.put("chain", chain);
        if (json) {
            out.println(Json.write(answer));
        } else {
            out.println(
                    checked.login()
                            + " ("
                            + checked.name()
                            + ") "
                            + checked.fingerprint()
                            + ", expires "
                            + checked.expires()
                            + (revoked ? ", REVOKED" : ""));
        }
        out.flush();
        return 0;
    }

    private static PersonCard read(final Path file) throws IOException {
        return PersonCard.read(Files.readString(file, StandardCharsets.UTF_8), file.toString());
    }

    /** Asks ssh-keygen about each key, one file per key in a directory of this command's own. */
    private Map<String, Boolean> revoked(final Path krl, final Map<String, String> keys)
            throws IOException {
        if (!Files.isRegularFile(krl)) {
            throw new IOException("there is no file");
        }
        final Path scratch = Files.createTempDirectory("porelo-card-");
        try {
            final Map<String, Boolean> listed = new LinkedHashMap<>();
            int index = 0;
            for (final Map.Entry<String, String> entry : keys.entrySet()) {
                final Path pub = scratch.resolve("key" + index++ + ".pub");
                Files.writeString(pub, entry.getValue() + "\n", StandardCharsets.UTF_8);
                final CommandResult result =
                        context.runner()
                                .run(
                                        Command.of(
                                                "ssh-keygen",
                                                "-Q",
                                                "-f",
                                                krl.toAbsolutePath().toString(),
                                                pub.toString()));
                // 0 is not on the list, 1 is on it; anything else is a list ssh-keygen cannot read.
                if (result.exitCode() != 0 && result.exitCode() != 1) {
                    throw new IllegalStateException(
                            result.trimmedOutput() + result.standardError().strip());
                }
                listed.put(entry.getKey(), result.exitCode() == 1);
            }
            return listed;
        } finally {
            try (java.util.stream.Stream<Path> entries = Files.walk(scratch)) {
                entries.sorted(java.util.Comparator.reverseOrder())
                        .forEach(
                                path -> {
                                    try {
                                        Files.deleteIfExists(path);
                                    } catch (final IOException ex) {
                                        // A scratch directory that will not go is not worth
                                        // failing.
                                    }
                                });
            }
        }
    }

    private static String printable(final String value) {
        return value.replaceAll("\\p{Cntrl}", "?");
    }

    private static int refuse(final PrintWriter err, final int code, final String message) {
        err.println("porelo: " + message);
        err.flush();
        return code;
    }
}
