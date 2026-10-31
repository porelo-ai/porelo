package org.fuin.porelo.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.porelo.core.process.Command;
import org.fuin.porelo.core.process.CommandException;
import org.fuin.porelo.core.process.CommandResult;
import org.fuin.porelo.runtime.ContainerName;
import org.fuin.porelo.vault.CardException;
import org.fuin.porelo.vault.PersonCard;
import org.fuin.porelo.vault.SshCertificate;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * Judges a task's certificate for a host before the host records it, and writes nothing.
 *
 * <p>Run by the host's helper for its persons as root at a task's start, with no home, vault or
 * network: the certificate on standard input, every file it reads named by a flag. It is accepted
 * only as {@code porelo task start} issues it - for exactly this task's principal and this login's
 * key id, valid as the start judges it, with no critical option and only the default extensions -
 * and only signed by one of the person's keys the host has in force, neither that key nor the
 * task's revoked. The answer is a contract for the host: its names are kept.
 */
@picocli.CommandLine.Command(
        name = "check-certificate",
        mixinStandardHelpOptions = true,
        exitCodeOnInvalidInput = 64,
        description = "Checks a task certificate on standard input for a host, and writes nothing.")
public class TaskCheckCertificateCommand implements Callable<Integer>, PoreloFactory.ContextAware {

    /** A certificate line is a few hundred bytes; anything far longer is not one. */
    static final int MAX_LINE = 16 * 1024;

    /** What {@code ssh-keygen -s} grants by default, and so every task certificate Porelo makes. */
    static final Map<String, String> EXTENSIONS =
            Map.of(
                    "permit-X11-forwarding", "", // not-a-citation
                    "permit-agent-forwarding", "",
                    "permit-port-forwarding", "",
                    "permit-pty", "",
                    "permit-user-rc", "");

    @Option(
            names = "--task",
            required = true,
            paramLabel = "TASK",
            description = "The task the certificate must be for.")
    private String task = "";

    @Option(
            names = "--login",
            required = true,
            paramLabel = "LOGIN",
            description = "The person who started it.")
    private String login = "";

    @Option(
            names = "--keys",
            required = true,
            paramLabel = "FILE",
            description = "The person's keys in force, one OpenSSH public key line each.")
    private Path keys = Path.of("");

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
        if (ContainerName.refusal("", task).isPresent()) {
            return refuse(err, 64, "'" + printable(task) + "' is not a task name");
        }
        if (!PersonCard.isLogin(login)) {
            return refuse(err, 64, "'" + printable(login) + "' is not a login");
        }
        final List<String> inForce;
        try {
            inForce =
                    Files.readAllLines(keys, StandardCharsets.UTF_8).stream()
                            .map(TaskCheckCertificateCommand::keyOnly)
                            .filter(each -> !each.isEmpty())
                            .toList();
        } catch (final IOException | java.io.UncheckedIOException ex) {
            return refuse(err, 66, "cannot read the keys '" + printable(keys.toString()) + "'");
        }
        final String line;
        try {
            line = line(context.input().stream());
        } catch (final IOException ex) {
            return refuse(err, 65, "the certificate cannot be read: " + ex.getMessage());
        } catch (final TaskCertification.Refused ex) {
            return refuse(err, 65, String.valueOf(ex.getMessage()));
        }
        final SshCertificate certificate;
        try {
            certificate = SshCertificate.read(line);
            check(certificate, inForce);
        } catch (final CardException | TaskCertification.Refused ex) {
            return refuse(err, 65, String.valueOf(ex.getMessage()));
        }
        final Path list = krl;
        if (list != null) {
            try {
                for (final String key : List.of(certificate.signer(), certificate.key())) {
                    if (revoked(list, key)) {
                        return refuse(
                                err,
                                65,
                                "the key "
                                        + PersonCard.fingerprint(key)
                                        + " is revoked by the revocation list");
                    }
                }
            } catch (final IOException ex) {
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
        final String principal = certificate.principals().getFirst();
        final Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("task", task);
        answer.put("project", principal.substring("task:".length(), principal.lastIndexOf(':')));
        answer.put("principal", principal);
        answer.put("key_id", certificate.keyId());
        answer.put("fingerprint", certificate.fingerprint());
        answer.put("signed_by", PersonCard.fingerprint(certificate.signer()));
        answer.put("valid_after", certificate.validAfter().toString());
        answer.put("valid_before", certificate.validBefore().toString());
        if (json) {
            out.println(Json.write(answer));
        } else {
            out.println(
                    principal
                            + " "
                            + certificate.fingerprint()
                            + ", signed by "
                            + answer.get("signed_by")
                            + ", valid until "
                            + certificate.validBefore());
        }
        out.flush();
        return 0;
    }

    /** Every rule but revocation, which asks ssh-keygen. */
    private void check(final SshCertificate certificate, final List<String> inForce) {
        if (!inForce.contains(certificate.signer())) {
            throw new TaskCertification.Refused(
                    "the certificate is signed by "
                            + PersonCard.fingerprint(certificate.signer())
                            + ", not a key of "
                            + login
                            + " in force");
        }
        final List<String> principals = certificate.principals();
        final String principal = principals.size() == 1 ? principals.getFirst() : "";
        final String prefix = "task:";
        final String suffix = ":" + task;
        final String project =
                principal.length() > prefix.length() + suffix.length()
                                && principal.startsWith(prefix)
                                && principal.endsWith(suffix)
                        ? principal.substring(prefix.length(), principal.length() - suffix.length())
                        : "";
        if (!(TaskCertification.NO_PROJECT.equals(project)
                || project.matches(RemoteStart.PROJECT)
                        && ContainerName.refusal(project, task).isEmpty())) {
            throw new TaskCertification.Refused(
                    "the certificate must name exactly one principal, task:<project>:" + task);
        }
        final String keyId = "porelo task " + task + " by " + login;
        if (!certificate.keyId().equals(keyId)) {
            throw new TaskCertification.Refused("the certificate's key id is not '" + keyId + "'");
        }
        TaskCertification.checkValidity(certificate, Instant.now());
        if (!certificate.extensions().equals(EXTENSIONS)) {
            throw new TaskCertification.Refused(
                    "the certificate's extensions are not exactly "
                            + String.join(", ", new java.util.TreeSet<>(EXTENSIONS.keySet()))
                            + ", each empty");
        }
    }

    /** The one line on standard input, bounded before anything parses it. */
    private static String line(final InputStream in) throws IOException {
        final byte[] read = in.readNBytes(MAX_LINE + 2);
        int length = read.length;
        if (length > 0 && read[length - 1] == '\n') {
            length--;
        }
        if (length > MAX_LINE) {
            throw new TaskCertification.Refused(
                    "the certificate is longer than " + MAX_LINE + " bytes");
        }
        if (length == 0) {
            throw new TaskCertification.Refused("no certificate came on standard input");
        }
        for (int i = 0; i < length; i++) {
            if (read[i] == '\n') {
                throw new TaskCertification.Refused(
                        "standard input holds more than the certificate's one line");
            }
            // A certificate line is printable ASCII; the host keeps the bytes as they came.
            if (read[i] < 0x20 || read[i] > 0x7e) {
                throw new TaskCertification.Refused(
                        "the certificate's line holds a byte that is no printable ASCII");
            }
        }
        return new String(read, 0, length, StandardCharsets.US_ASCII);
    }

    /** Asks ssh-keygen about one key, handed on its input, so no file is written. */
    private boolean revoked(final Path list, final String key) throws IOException {
        if (!Files.isRegularFile(list)) {
            throw new IOException("there is no file");
        }
        final CommandResult result;
        try {
            result =
                    context.runner()
                            .run(
                                    Command.of(
                                                    "ssh-keygen",
                                                    "-Q",
                                                    "-f",
                                                    list.toAbsolutePath().toString(),
                                                    "/dev/stdin")
                                            .withInput(key + "\n"));
        } catch (final CommandException ex) {
            throw new IOException(ex.getMessage(), ex);
        }
        // 0 is not on the list, 1 is on it; anything else is a list ssh-keygen cannot read.
        if (result.exitCode() != 0 && result.exitCode() != 1) {
            throw new IOException(printable(result.standardError().strip()));
        }
        return result.exitCode() == 1;
    }

    /** A key line's type and key, without its comment; {@code ""} for a blank line. */
    private static String keyOnly(final String line) {
        final String[] fields = line.strip().split("\\s+");
        return fields.length < 2 ? "" : fields[0] + " " + fields[1];
    }

    private static String printable(final String value) {
        return value.replaceAll("[\\p{Cc}\\p{Cf}]", "?");
    }

    private static int refuse(final PrintWriter err, final int code, final String message) {
        String said = printable(message);
        if (said.length() > MAX_LINE) {
            said = said.substring(0, MAX_LINE);
        }
        err.println("porelo: " + said);
        err.flush();
        return code;
    }
}
