package org.fuin.porelo.app;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.core.process.CommandRunner;
import org.fuin.porelo.gate.SignedArrivals;
import org.fuin.porelo.runtime.ContainerName;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.RegistryText;
import org.fuin.porelo.vault.SshCertificate;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * Judges every commit a push brings to a host's forge, at the time it arrives.
 *
 * <p>Run from the forge's pre-receive hook, with the hook's lines. Each commit new to the
 * repository, merges included, must carry an SSH signature that holds now, whatever time the commit
 * names: by a person's key in force, by a task's key through a certificate a certifier issued, or
 * by a task's plain key whose recorded certificate would itself be accepted; the revocation list
 * applies to every key. Every commit is judged, so a refused push names all its refusals at once.
 *
 * <p><strong>The pushed objects are read through the hook's quarantine</strong>, so the three
 * variables git gives the hook for it are passed on; nothing else of the account's environment or
 * git configuration reaches git. Nothing is written but scratch files of public keys, allowed
 * signers lines and signatures, in a directory of their own under {@code TMPDIR} (else the system's
 * temporary directory), removed before the answer; one that cannot be written or removed is an
 * {@link UncheckedIOException}, since ssh-keygen then cannot be run.
 */
final class HostArrivals {

    /** The most commits one push is judged for; more is refused rather than walked. */
    static final int MAX_COMMITS = 10_000;

    /** The most the hook's input may hold. */
    static final int MAX_INPUT = 1024 * 1024;

    /** What git sets for a pre-receive hook so it sees the pushed objects, passed on as given. */
    static final List<String> QUARANTINE =
            List.of(
                    "GIT_QUARANTINE_PATH",
                    "GIT_OBJECT_DIRECTORY",
                    "GIT_ALTERNATE_OBJECT_DIRECTORIES");

    /** A reason is one line, cut here. */
    private static final int MAX_REASON = 16 * 1024;

    private static final Pattern ID = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");

    private static final Pattern PROJECT = Pattern.compile("default|[a-z0-9][a-z0-9-]{0,62}");

    private static final Pattern REF = Pattern.compile("refs/[\\x21-\\x7e]+");

    private static final DateTimeFormatter VERIFY_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    /** A time in an allowed-signers option, as ssh-keygen reads one. */
    private static final Pattern OPTION_TIME =
            Pattern.compile("(\\d{8}|\\d{12}|\\d{14})(?i:Z|UTC)?");

    /** The signature header of a commit, by the object format of its repository. */
    private static final Map<String, String> SIGNATURE_HEADER =
            Map.of("sha1", "gpgsig", "sha256", "gpgsig-sha256");

    /**
     * Who signed a commit, as far as it was found out.
     *
     * @param kind {@code person} or {@code task}.
     * @param fingerprint The signing key's, for a task its own key's.
     * @param principal The signers' principal for a person, {@code task:<project>:<task>} for a
     *     task.
     * @param certificate For a task, {@code certificate} when the signature carries it, {@code
     *     recorded} when the host's record supplied it; {@code null} for a person.
     * @param signedBy For a task, the fingerprint of the certifier's key; {@code null} for a
     *     person.
     */
    record Signer(
            String kind,
            String fingerprint,
            @Nullable String principal,
            @Nullable String certificate,
            @Nullable String signedBy) {

        Map<String, @Nullable Object> answer() {
            final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
            answer.put("kind", text(kind));
            answer.put("fingerprint", text(fingerprint));
            answer.put("principal", principal == null ? null : text(principal));
            answer.put("certificate", certificate == null ? null : text(certificate));
            answer.put("signed_by", signedBy == null ? null : text(signedBy));
            return answer;
        }
    }

    /**
     * What was decided about one commit.
     *
     * @param commit The commit.
     * @param ref The ref whose update brought it first.
     * @param accepted Whether it may arrive.
     * @param signer Who signed it, or {@code null} when no key was attributed to anybody.
     * @param reason Why it is refused, one printable line; "" when accepted.
     */
    record Verdict(
            String commit, String ref, boolean accepted, @Nullable Signer signer, String reason) {

        Map<String, @Nullable Object> answer() {
            final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
            answer.put("commit", text(commit));
            answer.put("ref", text(ref));
            answer.put("verdict", accepted ? "accepted" : "refused");
            answer.put("signer", signer == null ? null : signer.answer());
            answer.put("reason", text(reason));
            return answer;
        }
    }

    /**
     * What a push came to.
     *
     * @param checkedAt The time every commit was judged at.
     * @param commits Each new commit's verdict, in the order the hook named their refs.
     * @param reason Why the push is refused as a whole, before any commit was judged; "" when it
     *     was not.
     */
    record Answer(Instant checkedAt, List<Verdict> commits, String reason) {

        Answer {
            commits = List.copyOf(commits);
        }

        boolean accepted() {
            return reason.isEmpty() && commits.stream().allMatch(Verdict::accepted);
        }

        String json() {
            final Map<String, @Nullable Object> answer = new LinkedHashMap<>();
            answer.put("checked_at", checkedAt.toString());
            answer.put("accepted", accepted());
            answer.put("commits", commits.stream().map(Verdict::answer).toList());
            answer.put("reason", text(reason));
            return Json.write(answer);
        }
    }

    private final CommandRunner runner;

    private final Path repository;

    private final List<AllowedSigner> persons;

    private final List<AllowedSigner> certifiers;

    private final List<SshCertificate> recorded;

    private final @Nullable Path krl;

    private final UnaryOperator<String> environment;

    private final Clock clock;

    private final Path scratchRoot;

    private String signatureHeader = "gpgsig";

    /**
     * What one judgement goes by.
     *
     * @param now The arrival.
     * @param scratch Where its scratch files go.
     * @param certifiers The certifiers' lines in force at the arrival.
     * @param certifiersFile Those lines, as the file ssh-keygen reads.
     */
    private record Round(
            Instant now, Path scratch, List<AllowedSigner> certifiers, Path certifiersFile) {}

    private HostArrivals(
            CommandRunner runner,
            Path repository,
            List<AllowedSigner> persons,
            List<AllowedSigner> certifiers,
            List<SshCertificate> recorded,
            @Nullable Path krl,
            UnaryOperator<String> environment,
            Clock clock,
            Path scratchRoot) {
        this.runner = runner;
        this.repository = repository;
        this.persons = List.copyOf(persons);
        this.certifiers = List.copyOf(certifiers);
        this.recorded = List.copyOf(recorded);
        this.krl = krl;
        this.environment = environment;
        this.clock = clock;
        this.scratchRoot = scratchRoot;
    }

    /**
     * Reads every file a judgement needs, before any commit is looked at.
     *
     * @param runner Runs git and ssh-keygen.
     * @param repository The forge's repository, a git directory.
     * @param signers The persons' keys in force, in allowed-signers form.
     * @param certifiers The certifiers' {@code cert-authority} lines.
     * @param krl The revocation list in force, or {@code null}.
     * @param certificates The host's recorded task certificates, or {@code null}.
     * @param environment The hook's environment, read for the quarantine and {@code PATH} only.
     * @param clock Says when the push arrives.
     * @return What judges the push.
     * @throws IOException If a named file is missing or cannot be read as what it must be.
     * @throws UncheckedIOException If a scratch file cannot be written or removed.
     */
    static HostArrivals reading(
            CommandRunner runner,
            Path repository,
            Path signers,
            Path certifiers,
            @Nullable Path krl,
            @Nullable Path certificates,
            UnaryOperator<String> environment,
            Clock clock)
            throws IOException {
        for (final Path each :
                java.util.stream.Stream.of(signers, certifiers, krl, certificates)
                        .filter(java.util.Objects::nonNull)
                        .toList()) {
            if (!Files.isRegularFile(each) || !Files.isReadable(each)) {
                throw new IOException(each + " is not a readable file");
            }
        }
        final Path scratchRoot = scratchRoot(environment);
        final List<AllowedSigner> persons = new ArrayList<>();
        final List<AllowedSigner> certifierLines;
        final Path scratch = scratchDirectory(scratchRoot);
        try {
            Revocations.requireRevocations(runner, krl, keygenEnvironment(environment));
            for (final AllowedSigner each : signerLines(runner, signers, environment, scratch)) {
                // A person signs with a plain key, and only a key in force signs a new commit.
                if (!ended(each, clock.instant()) && !option(each, "cert-authority")) {
                    persons.add(each);
                }
            }
            certifierLines = signerLines(runner, certifiers, environment, scratch);
        } finally {
            remove(scratch);
        }
        final List<SshCertificate> recorded = new ArrayList<>();
        if (certificates != null) {
            int number = 0;
            for (final String line : Files.readAllLines(certificates, StandardCharsets.UTF_8)) {
                number++;
                if (!line.isBlank()) {
                    recorded.add(recordOf(line, certificates, number));
                }
            }
        }
        final HostArrivals arrivals =
                new HostArrivals(
                        runner,
                        repository,
                        persons,
                        certifierLines,
                        recorded,
                        krl,
                        environment,
                        clock,
                        scratchRoot);
        if (!Files.isDirectory(repository)
                || !arrivals.git("rev-parse", "--git-dir").successful()) {
            throw new IOException(repository + " is not a git repository");
        }
        final CommandResult format = arrivals.git("rev-parse", "--show-object-format");
        final String header = SIGNATURE_HEADER.get(format.standardOutput().strip());
        if (!format.successful() || header == null) {
            throw new IOException(
                    repository
                            + " has an object format Porelo does not read: "
                            + RegistryText.printable(
                                    (format.standardOutput() + format.standardError()).strip()));
        }
        arrivals.signatureHeader = header;
        return arrivals;
    }

    /**
     * Judges what a push brings.
     *
     * @param input The hook's standard input, as read, up to one byte more than {@link #MAX_INPUT}.
     * @return Each new commit's verdict.
     * @throws IOException If the revocation list cannot be asked.
     * @throws UncheckedIOException If a scratch file cannot be written or removed.
     */
    Answer judge(byte[] input) throws IOException {
        final Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        if (input.length > MAX_INPUT) {
            return new Answer(now, List.of(), "the hook's input is longer than 1 MiB");
        }
        final String text;
        try {
            text =
                    StandardCharsets.US_ASCII
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(input))
                            .toString();
        } catch (CharacterCodingException ex) {
            return new Answer(now, List.of(), "the hook's input is not '<old> <new> <ref>' lines");
        }
        final List<String[]> updates = new ArrayList<>();
        int number = 0;
        for (final String line : text.split("\n", -1)) {
            number++;
            if (line.isEmpty()) {
                continue;
            }
            final String[] fields = line.split(" ", -1);
            if (fields.length != 3
                    || !ID.matcher(fields[0]).matches()
                    || !ID.matcher(fields[1]).matches()
                    || !REF.matcher(fields[2]).matches()) {
                return new Answer(
                        now,
                        List.of(),
                        "line " + number + " of the hook's input is not '<old> <new> <ref>'");
            }
            updates.add(fields);
        }
        final Map<String, String> arrived = new LinkedHashMap<>();
        final List<Verdict> verdicts = new ArrayList<>();
        for (final String[] update : updates) {
            final String commit = update[1];
            final String ref = update[2];
            if (commit.chars().allMatch(c -> c == '0')) {
                // A deleted ref brings nothing.
                continue;
            }
            // Checked as hex above, and after --end-of-options: never an option to git.
            final CommandResult walked =
                    git(
                            "rev-list",
                            "--max-count=" + (MAX_COMMITS + 1),
                            "--not",
                            "--all",
                            "--not",
                            "--end-of-options",
                            commit);
            if (!walked.successful()) {
                verdicts.add(
                        refused(
                                commit,
                                ref,
                                null,
                                "git cannot walk it: " + walked.standardError().strip()));
                continue;
            }
            for (final String each : walked.standardOutput().lines().toList()) {
                if (!each.isBlank()) {
                    arrived.putIfAbsent(each.strip(), ref);
                }
            }
            if (arrived.size() > MAX_COMMITS) {
                return new Answer(
                        now,
                        List.of(),
                        "the push brings more than "
                                + MAX_COMMITS
                                + " new commits; push fewer at once");
            }
        }
        final Path scratch = scratchDirectory(scratchRoot);
        try {
            final List<AllowedSigner> inForce =
                    certifiers.stream().filter(each -> inForce(each, now)).toList();
            final Path file =
                    scratchFile(
                            scratch,
                            "certifiers-",
                            String.join("", inForce.stream().map(e -> line(e) + "\n").toList()));
            final Round round = new Round(now, scratch, inForce, file);
            for (final Map.Entry<String, String> each : arrived.entrySet()) {
                verdicts.add(judgeOne(each.getKey(), each.getValue(), round));
            }
        } finally {
            remove(scratch);
        }
        return new Answer(now, verdicts, "");
    }

    private Verdict judgeOne(String commit, String ref, Round round) throws IOException {
        if (!ID.matcher(commit).matches()) {
            return refused(commit, ref, null, "git named it, but it is no commit id");
        }
        final CommandResult read = git("cat-file", "commit", commit);
        if (!read.successful()) {
            return refused(
                    commit, ref, null, "git cannot read it: " + read.standardError().strip());
        }
        final var framing =
                ArrivalKeyJudgement.signature(
                        read.standardOutput().getBytes(StandardCharsets.UTF_8), signatureHeader);
        if (!framing.refusal().isEmpty()) return refused(commit, ref, null, framing.refusal());
        final var signed =
                java.util.Objects.requireNonNull(
                        framing.signed(), "Accepted framing carries a signature");
        final String key =
                java.util.Objects.requireNonNull(framing.key(), "Accepted framing names a key");
        final Path signature =
                scratchFile(
                        round.scratch(),
                        "commit-",
                        new String(signed.signature(), StandardCharsets.UTF_8));
        if (key.substring(0, key.indexOf(' ')).endsWith("-cert-v01@openssh.com")) {
            return byCertificate(commit, ref, key, signed, signature, round);
        }
        return byKey(commit, ref, key, signed, signature, round);
    }

    /** A task's signature that carries its certificate. */
    private Verdict byCertificate(
            String commit,
            String ref,
            String key,
            SignedArrivals.Signed signed,
            Path signature,
            Round round)
            throws IOException {
        final SshCertificate certificate;
        try {
            certificate = SshCertificate.read(key);
        } catch (CardException ex) {
            return refused(
                    commit, ref, null, "its certificate is not accepted: " + ex.getMessage());
        }
        final Signer signer = taskSigner(certificate, "certificate");
        final String refusal = refusalOf(certificate, round);
        if (refusal != null) {
            return refused(commit, ref, signer, refusal);
        }
        if (!verifies(
                signed,
                signature,
                round.certifiersFile(),
                certificate.principals().getFirst(),
                round)) {
            return refused(
                    commit,
                    ref,
                    signer,
                    "its signature does not verify by its certificate for "
                            + certificate.principals().getFirst());
        }
        return new Verdict(commit, ref, true, signer, "");
    }

    /** A person's signature, or a task's by its plain key. */
    private Verdict byKey(
            String commit,
            String ref,
            String key,
            SignedArrivals.Signed signed,
            Path signature,
            Round round)
            throws IOException {
        final String fingerprint = String.valueOf(SignedBy.fingerprintOf(key));
        final List<AllowedSigner> own =
                persons.stream().filter(each -> sameKey(each.key(), key)).toList();
        return ArrivalKeyJudgement.judge(
                commit,
                ref,
                fingerprint,
                new ArrivalKeyJudgement.Checks() {
                    @Override
                    public List<String> persons() {
                        return own.stream().map(AllowedSigner::principal).toList();
                    }

                    @Override
                    public boolean revoked() throws IOException {
                        return HostArrivals.this.revoked(key, round);
                    }

                    @Override
                    public @Nullable String person() throws IOException {
                        final Path allowed =
                                scratchFile(
                                        round.scratch(),
                                        "signers-",
                                        String.join(
                                                "",
                                                own.stream().map(e -> line(e) + "\n").toList()));
                        for (final String principal : principalsFor(signature, allowed, round)) {
                            if (verifies(signed, signature, allowed, principal, round)) {
                                return principal;
                            }
                        }
                        return null;
                    }

                    @Override
                    public ArrivalKeyJudgement.Recorded recorded() throws IOException {
                        SshCertificate kept = null;
                        String refusal = null;
                        for (final SshCertificate each : recorded) {
                            if (sameKey(each.key(), key)) {
                                final String why = refusalOf(each, round);
                                if (why == null) {
                                    kept = each;
                                    break;
                                }
                                if (refusal == null) {
                                    refusal = why;
                                }
                            }
                        }
                        return new ArrivalKeyJudgement.Recorded(
                                kept == null ? null : taskSigner(kept, "recorded"), refusal);
                    }

                    @Override
                    public boolean task() {
                        final Path allowed =
                                scratchFile(
                                        round.scratch(),
                                        "task-",
                                        "porelo namespaces=\"git\" " + key + "\n");
                        return verifies(signed, signature, allowed, "porelo", round);
                    }
                });
    }

    /**
     * Why a task's certificate is not accepted now, or {@code null} when it is: one principal of a
     * task, valid at the arrival, issued by a certifier in force for that principal, and nothing it
     * names revoked.
     */
    private @Nullable String refusalOf(SshCertificate certificate, Round round) throws IOException {
        final Instant now = round.now();
        final List<String> principals = certificate.principals();
        if (principals.size() != 1 || !isTaskPrincipal(principals.getFirst())) {
            return "its certificate must name exactly one principal, task:<project>:<task>";
        }
        final String principal = principals.getFirst();
        if (!certificate.validAt(now)) {
            return "its certificate for "
                    + principal
                    + " is not valid at its arrival "
                    + now
                    + " (valid from "
                    + certificate.validAfter()
                    + " to "
                    + certificate.validBefore()
                    + ")";
        }
        if (!certifies(certificate.signer(), principal, round)) {
            return "its certificate is signed by "
                    + SignedBy.fingerprintOf(certificate.signer())
                    + ", which is no certifier for "
                    + principal;
        }
        if (revoked(certificate.line(), round)) {
            return "its certificate, its key or its certifier's key is revoked by the KRL";
        }
        return null;
    }

    private static boolean isTaskPrincipal(String principal) {
        final String[] parts = principal.split(":", -1);
        return parts.length == 3
                && "task".equals(parts[0])
                && PROJECT.matcher(parts[1]).matches()
                // The container name task start would make, so its length bound holds too.
                && ContainerName.refusal(parts[1], parts[2]).isEmpty();
    }

    /** Whether a {@code cert-authority} line in force names this key for this principal. */
    private boolean certifies(String authority, String principal, Round round) {
        final List<String> lines = new ArrayList<>();
        for (final AllowedSigner each : round.certifiers()) {
            if (sameKey(each.key(), authority)) {
                lines.add(line(each) + "\n");
            }
        }
        if (lines.isEmpty()) {
            return false;
        }
        final Path file = scratchFile(round.scratch(), "certifier-", String.join("", lines));
        return keygen("-Y", "match-principals", "-I", principal, "-f", file.toString())
                .successful();
    }

    /**
     * Whether a certifier's line is in force at the arrival, as ssh-keygen would hold it then: a
     * {@code cert-authority} line, not ended, valid from no later than the arrival, and for the
     * {@code git} namespace.
     */
    static boolean inForce(AllowedSigner line, Instant now) {
        if (!option(line, "cert-authority") || ended(line, now)) {
            return false;
        }
        for (final String each : line.options()) {
            final String after = valueOf(each, "valid-after");
            if (after != null) {
                final Instant from = optionTime(after);
                if (from == null || from.isAfter(now)) {
                    return false;
                }
            }
            final String namespaces = valueOf(each, "namespaces");
            if (namespaces != null && !permits(namespaces, "git")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a line's end has passed at the arrival: a card's end date stands on its line from its
     * first day, and a time that cannot be read counts as passed.
     */
    private static boolean ended(AllowedSigner line, Instant now) {
        for (final String each : line.options()) {
            final String before = valueOf(each, "valid-before");
            if (before != null) {
                final Instant until = optionTime(before);
                if (until == null || !until.isAfter(now)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * An option's value without its quotes, each {@code \"} in them a quote, or {@code null} when
     * it is another option.
     */
    private static @Nullable String valueOf(String option, String name) {
        final String text = option.strip();
        if (!text.toLowerCase(Locale.ROOT).startsWith(name + "=")) {
            return null;
        }
        final String value = text.substring(name.length() + 1);
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1).replace("\\\"", "\"")
                : value;
    }

    /**
     * A time as an allowed-signers option gives it, {@code YYYYMMDD[HHMM[SS]][Z|UTC]} with the
     * suffix in any case, read as UTC as ssh-keygen reads it in the environment it is run in;
     * {@code null} when it is none. Like ssh-keygen, it takes any day up to 31 and a second up to
     * 61, rolling what a month or minute does not hold over into the next, and refuses a time not
     * after 1970 began.
     */
    private static @Nullable Instant optionTime(String value) {
        final Matcher matcher = OPTION_TIME.matcher(value);
        if (!matcher.matches()) {
            return null;
        }
        final String digits = (matcher.group(1) + "000000").substring(0, 14);
        final int month = field(digits, 4);
        final int day = field(digits, 6);
        final int hour = field(digits, 8);
        final int minute = field(digits, 10);
        final int second = field(digits, 12);
        if (month < 1
                || month > 12
                || day < 1
                || day > 31
                || hour > 23
                || minute > 59
                || second > 61) {
            return null;
        }
        final Instant time =
                LocalDate.of(Integer.parseInt(digits.substring(0, 4)), month, 1)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant()
                        .plusSeconds(((day - 1) * 24L + hour) * 3600 + minute * 60L + second);
        return time.isAfter(Instant.EPOCH) ? time : null;
    }

    private static int field(String digits, int at) {
        return Integer.parseInt(digits.substring(at, at + 2));
    }

    /**
     * Whether a pattern list admits a name, as ssh-keygen matches a {@code namespaces} option: some
     * pattern matches and no negated one does.
     */
    static boolean permits(String patterns, String name) {
        boolean matched = false;
        for (final String each : patterns.split(",", -1)) {
            final boolean negated = each.startsWith("!");
            if (glob(name, negated ? each.substring(1) : each)) {
                if (negated) {
                    return false;
                }
                matched = true;
            }
        }
        return matched;
    }

    private static boolean glob(String text, String pattern) {
        final StringBuilder regex = new StringBuilder();
        for (final char each : pattern.toCharArray()) {
            regex.append(
                    each == '*' ? ".*" : each == '?' ? "." : Pattern.quote(String.valueOf(each)));
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL).matcher(text).matches();
    }

    /** An allowed-signers line as ssh-keygen reads it: principals in quotes, options, key. */
    private static String line(AllowedSigner signer) {
        return "\""
                + signer.principal()
                + "\""
                + (signer.options().isEmpty() ? "" : " " + String.join(",", signer.options()))
                + " "
                + signer.key();
    }

    /** Whether the KRL revokes a key or certificate; never, without one. */
    private boolean revoked(String key, Round round) throws IOException {
        if (krl == null) {
            return false;
        }
        final Path file = scratchFile(round.scratch(), "key-", key + "\n");
        final CommandResult asked =
                keygen("-Q", "-f", krl.toAbsolutePath().toString(), file.toString());
        if (asked.exitCode() == 0) {
            return false;
        }
        if (asked.exitCode() == 1) {
            return true;
        }
        throw new IOException("the KRL could not be asked: " + asked.standardError().strip());
    }

    /** The principals git verifies a signature by: those ssh-keygen finds for its key. */
    private List<String> principalsFor(Path signature, Path allowed, Round round) {
        final CommandResult found =
                keygen(
                        "-Y",
                        "find-principals",
                        "-f",
                        allowed.toAbsolutePath().toString(),
                        "-s",
                        signature.toAbsolutePath().toString(),
                        "-Overify-time=" + VERIFY_TIME.format(round.now()));
        if (!found.successful()) {
            return List.of();
        }
        // Git splits them at a line feed alone: a quoted principal may hold a carriage return.
        return Arrays.stream(found.standardOutput().split("\n"))
                .filter(each -> !each.isEmpty())
                .toList();
    }

    /** Verifies the signature at the arrival, never at the time the commit names. */
    private boolean verifies(
            SignedArrivals.Signed signed,
            Path signature,
            Path allowed,
            String principal,
            Round round) {
        return runner.run(
                        Command.of(
                                        "ssh-keygen",
                                        "-Y",
                                        "verify",
                                        "-n",
                                        "git",
                                        "-I",
                                        principal,
                                        "-f",
                                        allowed.toAbsolutePath().toString(),
                                        "-s",
                                        signature.toAbsolutePath().toString(),
                                        "-Overify-time=" + VERIFY_TIME.format(round.now()))
                                .withBinaryInput(signed.payload())
                                .withChosenEnvironment(keygenEnvironment(environment))
                                .in(round.scratch()))
                .successful();
    }

    private CommandResult keygen(String... arguments) {
        final List<String> all = new ArrayList<>(List.of("ssh-keygen"));
        all.addAll(List.of(arguments));
        return runner.run(Command.of(all).withChosenEnvironment(keygenEnvironment(environment)));
    }

    /**
     * Runs git on the repository: the quarantine passed on, none of the account's or the system's
     * configuration, no replacements, no prompt.
     */
    private CommandResult git(String... arguments) {
        final List<String> all =
                new ArrayList<>(
                        List.of(
                                "git",
                                "--no-replace-objects",
                                "-c",
                                "core.hooksPath=/dev/null",
                                "--git-dir",
                                repository.toAbsolutePath().toString()));
        all.addAll(List.of(arguments));
        final Map<String, String> chosen = new LinkedHashMap<>(base(environment));
        chosen.put("GIT_CONFIG_NOSYSTEM", "1");
        chosen.put("GIT_CONFIG_GLOBAL", "/dev/null");
        chosen.put("GIT_ATTR_NOSYSTEM", "1");
        chosen.put("GIT_NO_REPLACE_OBJECTS", "1");
        chosen.put("GIT_TERMINAL_PROMPT", "0");
        for (final String each : QUARANTINE) {
            final String value = environment.apply(each);
            if (value != null && !value.isEmpty()) {
                chosen.put(each, value);
            }
        }
        return runner.run(Command.of(all).withChosenEnvironment(chosen));
    }

    private static Map<String, String> base(UnaryOperator<String> environment) {
        final String path = environment.apply("PATH");
        return Map.of(
                "PATH", path == null || path.isEmpty() ? "/usr/bin:/bin" : path, "LC_ALL", "C");
    }

    /**
     * ssh-keygen's: a time without {@code Z} is read as UTC here, on every OpenSSH; one ending in
     * {@code Z} is refused before 9.1.
     */
    private static Map<String, String> keygenEnvironment(UnaryOperator<String> environment) {
        final Map<String, String> chosen = new LinkedHashMap<>(base(environment));
        chosen.put("TZ", "UTC");
        return chosen;
    }

    /** {@code TMPDIR} as the hook has it, else the system's temporary directory. */
    private static Path scratchRoot(UnaryOperator<String> environment) {
        final String named = environment.apply("TMPDIR");
        return named != null && named.startsWith("/")
                ? Path.of(named)
                : Path.of(System.getProperty("java.io.tmpdir"));
    }

    private static Path scratchDirectory(Path root) {
        try {
            return Files.createTempDirectory(root, "porelo-arrival-");
        } catch (IOException ex) {
            throw new UncheckedIOException(
                    "no scratch directory for ssh-keygen can be made in " + root, ex);
        }
    }

    private static Path scratchFile(Path directory, String prefix, String content) {
        try {
            final Path file = Files.createTempFile(directory, prefix, "");
            Files.writeString(file, content, StandardCharsets.UTF_8);
            return file;
        } catch (IOException ex) {
            throw new UncheckedIOException(
                    "a scratch file for ssh-keygen cannot be written in " + directory, ex);
        }
    }

    private static void remove(Path scratch) {
        try (var files = Files.list(scratch)) {
            for (final Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
            Files.deleteIfExists(scratch);
        } catch (IOException ex) {
            throw new UncheckedIOException("the scratch directory " + scratch + " stays", ex);
        }
    }

    /** One printable line, as every text of the answer is, cut short only between characters. */
    static String text(String value) {
        final String line = RegistryText.printable(value.replaceAll("\\R", " "));
        if (line.length() <= MAX_REASON) {
            return line;
        }
        return line.substring(
                0,
                Character.isHighSurrogate(line.charAt(MAX_REASON - 1))
                        ? MAX_REASON - 1
                        : MAX_REASON);
    }

    private static Signer taskSigner(SshCertificate certificate, String how) {
        return new Signer(
                "task",
                certificate.fingerprint(),
                certificate.principals().size() == 1 ? certificate.principals().getFirst() : null,
                how,
                SignedBy.fingerprintOf(certificate.signer()));
    }

    private static Verdict refused(
            String commit, String ref, @Nullable Signer signer, String reason) {
        return new Verdict(commit, ref, false, signer, text(reason));
    }

    /** The two keys are one, compared by their blobs. */
    private static boolean sameKey(String one, String other) {
        final String[] a = one.strip().split("\\s+");
        final String[] b = other.strip().split("\\s+");
        return a.length >= 2 && b.length >= 2 && a[0].equals(b[0]) && a[1].equals(b[1]);
    }

    private static boolean option(AllowedSigner signer, String name) {
        return signer.options().stream()
                .anyMatch(each -> each.strip().toLowerCase(Locale.ROOT).equals(name));
    }

    private static List<AllowedSigner> signerLines(
            CommandRunner runner, Path file, UnaryOperator<String> environment, Path scratch)
            throws IOException {
        try {
            return AllowedSigner.read(runner, file, keygenEnvironment(environment), scratch);
        } catch (IOException ex) {
            throw new IOException(file + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Reads one recorded certificate: the line {@code porelo-host task show} answers, a JSON record
     * whose {@code certificate} is the certificate line, or that line alone.
     */
    private static SshCertificate recordOf(String line, Path file, int number) throws IOException {
        final String certificate;
        final String text = line.strip();
        if (text.startsWith("{")) {
            final Object parsed;
            try {
                parsed = Json.parse(text);
            } catch (IllegalArgumentException | org.fuin.porelo.wire.JsonException ex) {
                throw new IOException(file + ":" + number + " is not a JSON record", ex);
            }
            if (!(parsed instanceof Map<?, ?> record)
                    || !(record.get("certificate") instanceof String named)) {
                throw new IOException(file + ":" + number + " names no certificate");
            }
            certificate = named;
        } else {
            certificate = text;
        }
        try {
            return SshCertificate.read(certificate);
        } catch (CardException ex) {
            throw new IOException(file + ":" + number + ": " + ex.getMessage(), ex);
        }
    }

    /** The key type a public key blob names, or {@code null} when it names none. */
    static @Nullable String typeOf(byte[] blob) {
        if (blob.length < 4) {
            return null;
        }
        final int length = ByteBuffer.wrap(blob).getInt();
        if (length <= 0 || length > blob.length - 4) {
            return null;
        }
        final String type = new String(blob, 4, length, StandardCharsets.US_ASCII);
        return type.matches("[a-z0-9@.-]+") ? type : null;
    }
}
