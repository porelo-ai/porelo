package org.fuin.porelo.app;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks the secrets in a tool call's command line, the one place a line is masked before a task's
 * record stores it, whatever agent gave it.
 *
 * <p>A masked value is replaced by {@link #MASK}, its name and the rest of the line kept, so the
 * line still says what ran. Masked are: a value of the vault Porelo knows for the task; a private
 * key block; a string shaped like a known credential; the user information of a URL; an {@code
 * Authorization:} header's value, with its scheme unless it is a known one; the value of an
 * environment assignment whose name ends in {@code _TOKEN}, {@code _KEY}, {@code _SECRET} or {@code
 * _PASSWORD}; the value of an option naming a password, token, secret or key; a password given as
 * {@code -p} to a tool that takes one so; and the password of a {@code user:password} given to
 * {@code curl -u}.
 */
public final class CommandMask {

    /** What a masked value is replaced by. */
    public static final String MASK = "***";

    /** One value as a shell word gives it: quoted, or up to the next blank. */
    private static final String VALUE = "(?:'[^']*'|\"[^\"]*\"|[^\\s'\"]+)";

    private static final Pattern PRIVATE_KEY =
            Pattern.compile(
                    "-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*?(?:-----END [A-Z0-9 ]*PRIVATE"
                            + " KEY-----|$)",
                    Pattern.DOTALL);

    /**
     * Credentials known by their shape: forge, provider, cloud, registry and Porelo tokens. Each
     * matches inside a longer token too: a credential glued to other characters is still one, and
     * masking a run of base64 that holds {@code sk-} by chance costs only that run. Only command
     * lines are masked, never the stream's other text.
     */
    private static final Pattern SHAPED =
            Pattern.compile(org.fuin.porelo.wire.CredentialShapes.anyToken());

    private static final Pattern URL_USER =
            Pattern.compile("(\\b[A-Za-z][A-Za-z0-9+.-]*://)[^/\\s@'\"]+@");

    private static final Pattern AUTHORIZATION =
            Pattern.compile(
                    "(?i)(authorization:\\s*(?:(?:bearer|basic|token|digest)\\s+)?)"
                            // Any other scheme is masked with its value: which word is the secret
                            // cannot be told.
                            + "[^\\s'\"]+(?:[ \\t]+[^\\s'\"]+)*");

    private static final Pattern ENVIRONMENT =
            Pattern.compile("(?i)(\\b[A-Z_][A-Z0-9_]*(?:_TOKEN|_KEY|_SECRET|_PASSWORD)=)" + VALUE);

    private static final Pattern OPTION =
            Pattern.compile(
                    "(?i)((?<![\\w-])--?(?:[a-z0-9]+-)*(?:password|passwd|pass|token|secret"
                            + "|key|apikey)(?:=|\\s+))"
                            + VALUE);

    /**
     * {@code -p<password>} where the tool takes a password so, or {@code sshpass -p <password>}.
     */
    private static final Pattern SHORT_PASSWORD =
            Pattern.compile(
                    "(\\b(?:mysql|mysqldump|mysqladmin|mariadb|mariadb-dump)\\b.*?\\s-p)"
                            + VALUE
                            + "|(\\bsshpass\\b.*?\\s-p\\s*)"
                            + VALUE);

    private static final Pattern CURL_USER =
            Pattern.compile("(\\bcurl\\b.*?\\s(?:-u|--user)[\\s=]+['\"]?[^\\s:'\"]+:)[^\\s'\"]+");

    private final List<String> values;

    private final boolean whole;

    /**
     * Returns the mask for a task whose vault values cannot be read: every command line is masked
     * whole, since a value it holds cannot be looked for.
     *
     * @return The mask.
     */
    public static CommandMask whole() {
        return new CommandMask(List.of(), true);
    }

    /**
     * Creates the mask for one task.
     *
     * @param values The values of the vault Porelo knows for the task; a blank one is left out.
     */
    public CommandMask(final Collection<String> values) {
        this(values, false);
    }

    private CommandMask(final Collection<String> values, final boolean whole) {
        this.whole = whole;
        // The longest first: a value holding another is masked whole.
        this.values =
                values.stream()
                        .filter(value -> !value.isBlank())
                        .distinct()
                        .sorted(Comparator.comparingInt(String::length).reversed())
                        .toList();
    }

    /**
     * Masks one command line.
     *
     * @param line The whole command line, as the agent's stream gives it.
     * @return The line with every secret replaced by {@link #MASK}.
     */
    public String mask(final String line) {
        if (whole) {
            return MASK;
        }
        String masked = line;
        for (final String value : values) {
            masked = masked.replace(value, MASK);
        }
        masked = PRIVATE_KEY.matcher(masked).replaceAll(MASK);
        masked = SHAPED.matcher(masked).replaceAll(MASK);
        masked = keepFirst(URL_USER, masked, MASK + "@");
        masked = keepFirst(AUTHORIZATION, masked, MASK);
        masked = keepFirst(ENVIRONMENT, masked, MASK);
        masked = keepFirst(OPTION, masked, MASK);
        masked = shortPassword(masked);
        masked = keepFirst(CURL_USER, masked, MASK);
        return masked;
    }

    private static String keepFirst(final Pattern pattern, final String line, final String mask) {
        return pattern.matcher(line)
                .replaceAll(match -> Matcher.quoteReplacement(match.group(1) + mask));
    }

    private static String shortPassword(final String line) {
        return SHORT_PASSWORD
                .matcher(line)
                .replaceAll(
                        match -> {
                            final String kept = match.group(1);
                            return Matcher.quoteReplacement(
                                    (kept != null ? kept : match.group(2)) + MASK);
                        });
    }
}
