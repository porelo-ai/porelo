package org.fuin.porelo.host;

import static org.fuin.porelo.host.HostJson.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Pure task-account policy and validation of the confined commit verifier's answer. */
final class PushJudgement {
    static final int MAX_COMMITS = 10000;
    private static final int MAX_TEXT = 16 * 1024;
    private static final Set<String> SIGNER_KEYS =
            Set.of("kind", "fingerprint", "principal", "certificate", "signed_by");
    private static final java.util.regex.Pattern TIME =
            java.util.regex.Pattern.compile(
                    "([0-9]{4})-([0-9]{1,2})-([0-9]{1,2})T([0-9]{1,2}):([0-9]{1,2}):([0-9]{1,2})Z");

    @FunctionalInterface
    interface People {
        boolean contains(String login);
    }

    @FunctionalInterface
    interface Tasks {
        Set<String> names(String login);
    }

    @FunctionalInterface
    interface Verifier {
        HostPrograms.Result judge(List<PushRequest.Update> updates) throws IOException;
    }

    record Answer(int status, String text, List<Map<String, @Nullable Object>> entries) {
        Answer {
            entries = List.copyOf(entries);
        }

        Answer(int status, String text) {
            this(status, text, List.of());
        }

        byte[] http() {
            final byte[] body = text.getBytes(StandardCharsets.UTF_8);
            final String head =
                    "HTTP/1.1 "
                            + status
                            + " "
                            + GateHttp.reason(status)
                            + "\r\nContent-Type: text/plain\r\nContent-Length: "
                            + body.length
                            + "\r\nConnection: close\r\n\r\n";
            final byte[] bytes = head.getBytes(StandardCharsets.US_ASCII);
            final byte[] all = java.util.Arrays.copyOf(bytes, bytes.length + body.length);
            System.arraycopy(body, 0, all, bytes.length, body.length);
            return all;
        }
    }

    private PushJudgement() {}

    static Answer judge(
            List<PushRequest.Update> updates,
            String pusher,
            People people,
            Tasks tasks,
            Verifier verifier)
            throws IOException {
        if (!pusher.matches("[A-Za-z0-9._-]{0,64}"))
            throw new HostRefusal(64, "the hook names no valid pusher");
        final String login = pusher.startsWith("porelo-") ? pusher.substring(7) : pusher;
        final boolean hostAccount =
                pusher.equals("porelo-admin")
                        || pusher.matches("porelo-publisher-" + HostRecords.PROJECT);
        if (!login.equals(pusher) && !hostAccount) {
            if (!login.matches(HostRecords.USER) || !people.contains(login))
                return new Answer(403, "porelo: " + pusher + " names no person of this host\n");
            final Set<String> own = tasks.names(login);
            for (var update : updates) {
                if (!own.contains(update.ref().replaceFirst("^refs/heads/porelo/", ""))
                        || !update.ref().startsWith("refs/heads/porelo/"))
                    return new Answer(
                            403,
                            "porelo: "
                                    + pusher
                                    + " pushes only porelo/<task> of "
                                    + login
                                    + "'s own tasks, and "
                                    + update.ref()
                                    + " is none\n");
            }
        }
        if (updates.stream().allMatch(PushRequest.Update::deletion))
            return new Answer(200, "porelo: the push brings no new commit\n");
        return verified(
                verifier.judge(updates),
                updates.stream()
                        // A deleted ref brings no commit, so no verdict entry may name it.
                        .filter(update -> !update.deletion())
                        .map(PushRequest.Update::ref)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    static Answer refusal(HostRefusal error) {
        return new Answer(
                error.exitCode() == 64 || error.exitCode() == 65 ? 403 : 500,
                "porelo: " + error.getMessage() + "\n");
    }

    static Answer verified(HostPrograms.Result result, Set<String> refs) {
        if (result.exitCode() == 65) {
            final String words =
                    result.error()
                            .lines()
                            .map(line -> line.startsWith("porelo: ") ? line.substring(8) : line)
                            .filter(line -> line.matches("[ -~]{1,512}"))
                            .limit(100)
                            .map(line -> "porelo: " + line + "\n")
                            .collect(java.util.stream.Collectors.joining());
            return new Answer(403, words.isEmpty() ? "porelo: the push is refused\n" : words);
        }
        try {
            if (result.exitCode() != 0) return failed();
            final var verdict = object(parse(result.output()));
            if (!Boolean.TRUE.equals(verdict.get("accepted"))) return failed();
            final String checked = string(verdict.get("checked_at"));
            if (!checkedAt(checked)) return failed();
            final var commits = array(verdict.get("commits"));
            if (commits.size() > MAX_COMMITS) return failed();
            final Set<String> seen = new HashSet<>();
            final List<Map<String, @Nullable Object>> entries = new ArrayList<>();
            for (Object value : commits) {
                final var item = object(value);
                final var signer = object(item.get("signer"));
                if (!"accepted".equals(item.get("verdict"))
                        || !string(item.get("commit")).matches(HostRecords.COMMIT)
                        // Git's null object name, a deletion's new side, is no commit that arrived.
                        || string(item.get("commit")).chars().allMatch(digit -> digit == '0')
                        || !seen.add(string(item.get("commit")))
                        || !refs.contains(string(item.get("ref")))
                        || !signer.keySet().equals(SIGNER_KEYS)
                        || !Set.of("person", "task").contains(string(signer.get("kind")))
                        || !nullableText(signer.get("principal"))
                        || !nullableText(signer.get("certificate"))
                        || !nullableText(signer.get("signed_by"))
                        || signer.get("certificate") != null
                                && !Set.of("certificate", "recorded")
                                        .contains(signer.get("certificate"))
                        || signer.get("signed_by") != null
                                && !string(signer.get("signed_by"))
                                        .matches("SHA256:[A-Za-z0-9+/]{43}")
                        || !string(signer.get("fingerprint")).matches("SHA256:[A-Za-z0-9+/]{43}"))
                    return failed();
                entries.add(
                        fields(
                                "commit",
                                item.get("commit"),
                                "ref",
                                item.get("ref"),
                                "verdict",
                                "accepted",
                                "arrived",
                                checked,
                                "signer",
                                signer));
            }
            return new Answer(
                    200,
                    "porelo: " + entries.size() + " new commit(s) judged and recorded\n",
                    entries);
        } catch (IllegalArgumentException ex) {
            return failed();
        }
    }

    private static boolean nullableText(@Nullable Object value) {
        return value == null || value instanceof String text && text.length() <= MAX_TEXT;
    }

    private static Answer failed() {
        return new Answer(500, "porelo: the host could not judge this push\n");
    }

    private static boolean checkedAt(String value) {
        final var match = TIME.matcher(value);
        if (!match.matches()) return false;
        try {
            return LocalDateTime.of(
                                    Integer.parseInt(match.group(1)),
                                    Integer.parseInt(match.group(2)),
                                    Integer.parseInt(match.group(3)),
                                    Integer.parseInt(match.group(4)),
                                    Integer.parseInt(match.group(5)),
                                    Integer.parseInt(match.group(6)))
                            .getYear()
                    > 0;
        } catch (java.time.DateTimeException ex) {
            return false;
        }
    }
}
