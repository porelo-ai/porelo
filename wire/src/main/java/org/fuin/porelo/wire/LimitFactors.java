package org.fuin.porelo.wire;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The four factors every time limit of Porelo follows, and the one line they are handed on in.
 *
 * <p>Every time limit belongs to one group and is its default times that group's factor, so a slow
 * network or machine is met by one setting rather than a new build: {@code services} for an answer
 * from outside, {@code machines} for a program, container or machine starting, stopping or running
 * a command, {@code polling} for how often something is looked at again, and {@code retries} for
 * how often, and after what pause, a failed step is tried again.
 *
 * <p>The limits line, {@code services=1.0,machines=1.0,polling=1.0,retries=1.0}, is how they
 * travel: as {@value #OPTION} to every program {@code porelod} starts, as {@value #ENVIRONMENT} in
 * a task, in a task's sidecar for its hooks, and to a client through Tasks1. In {@code porelo-wire}
 * because every one of those reads it, and none may read it differently; a program in another
 * language keeps to the grammar {@link #parse} states, checked against the cases published beside
 * this class as {@value #CASES}.
 *
 * @param services The factor for an answer from outside.
 * @param machines The factor for a program, container or machine at work.
 * @param polling The factor for how long until something is looked at again.
 * @param retries The factor for how often, and after what pause, a step is tried again.
 */
public record LimitFactors(
        BigDecimal services, BigDecimal machines, BigDecimal polling, BigDecimal retries) {

    /** The option a program started by {@code porelod} takes the line with. */
    public static final String OPTION = "--limits";

    /** The variable a task is given the line in. */
    public static final String ENVIRONMENT = "PORELO_LIMITS";

    /** The resource beside this class holding the good lines and every refusal, as JSON. */
    public static final String CASES = "limit-factors-cases.json";

    private static final BigDecimal LEAST = new BigDecimal("0.1");

    private static final BigDecimal MOST = new BigDecimal("100");

    // A plain decimal with a dot and at most two digits after it: no sign, exponent or comma.
    private static final Pattern VALUE = Pattern.compile("(0|[1-9][0-9]{0,2})(\\.[0-9]{1,2})?");

    private static final Set<String> NAMES = Set.of("services", "machines", "polling", "retries");

    /** All four at 1.0: what a program started without the line uses. */
    public static final LimitFactors DEFAULT =
            new LimitFactors(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);

    /**
     * Constructor with validation.
     *
     * @param services The factor for an answer from outside.
     * @param machines The factor for a program, container or machine at work.
     * @param polling The factor for how long until something is looked at again.
     * @param retries The factor for how often, and after what pause, a step is tried again.
     * @throws IllegalArgumentException When one lies outside 0.1 to 100 or has more than two digits
     *     after its dot.
     */
    public LimitFactors {
        checked("services", services);
        checked("machines", machines);
        checked("polling", polling);
        checked("retries", retries);
        // 2.5 and 2.50 are one factor, and equal as one.
        services = services.stripTrailingZeros();
        machines = machines.stripTrailingZeros();
        polling = polling.stripTrailingZeros();
        retries = retries.stripTrailingZeros();
    }

    /**
     * Reads a limits line: comma-separated {@code name=value}, each of {@code services}, {@code
     * machines}, {@code polling} and {@code retries} at most once, in any order, with no spaces; a
     * name left out is 1.0. A value is a decimal from 0.1 to 100 with a dot and at most two digits
     * after it. An empty line names no factor and is refused: a line that is given is never taken
     * as the defaults, which only a program given no line at all uses.
     *
     * @param line The line as handed on.
     * @return The factors it names.
     * @throws IllegalArgumentException Naming what is wrong: an empty line, an unknown or repeated
     *     name, a part that is no {@code name=value}, or a value that is no such number or lies
     *     outside the range.
     */
    public static LimitFactors parse(String line) {
        if (line.isEmpty()) {
            throw new IllegalArgumentException("an empty line names no factor");
        }
        BigDecimal services = BigDecimal.ONE;
        BigDecimal machines = BigDecimal.ONE;
        BigDecimal polling = BigDecimal.ONE;
        BigDecimal retries = BigDecimal.ONE;
        final Set<String> seen = new HashSet<>();
        for (final String part : line.split(",", -1)) {
            final int equals = part.indexOf('=');
            if (equals <= 0) {
                throw new IllegalArgumentException("'" + part + "' is no name=value");
            }
            final String name = part.substring(0, equals);
            if (!NAMES.contains(name)) {
                throw new IllegalArgumentException(
                        "'"
                                + name
                                + "' is no limit; the limits are services, machines, polling and"
                                + " retries");
            }
            if (!seen.add(name)) {
                throw new IllegalArgumentException(name + " is given twice");
            }
            final BigDecimal value = value(name, part.substring(equals + 1));
            switch (name) {
                case "services" -> services = value;
                case "machines" -> machines = value;
                case "polling" -> polling = value;
                default -> retries = value;
            }
        }
        return new LimitFactors(services, machines, polling, retries);
    }

    /**
     * The factors a program's command line names before its mode, and where the rest begins.
     *
     * @param factors The factors named, or all four at 1.0 when none were.
     * @param next The index of the first argument after them.
     */
    public record Leading(LimitFactors factors, int next) {}

    /**
     * Takes {@value #OPTION} {@code <line>} from the front of a program's arguments, where Porelo
     * puts it: {@code <program> --limits <line> serve <socket>}.
     *
     * @param arguments The program's arguments.
     * @return The factors and the index of the mode; all four at 1.0 and 0 without the option.
     * @throws IllegalArgumentException Saying {@code --limits: <what is wrong>}, for a program to
     *     print and end with exit code 64.
     */
    public static Leading leading(String[] arguments) {
        if (arguments.length == 0 || !OPTION.equals(arguments[0])) {
            return new Leading(DEFAULT, 0);
        }
        if (arguments.length < 2) {
            throw new IllegalArgumentException(OPTION + ": needs a limits line");
        }
        try {
            return new Leading(parse(arguments[1]), 2);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(OPTION + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Writes the line as {@code porelod} hands it on: all four, in the order services, machines,
     * polling, retries, each with at least one digit after its dot.
     *
     * @return The limits line.
     */
    public String line() {
        return "services="
                + written(services)
                + ",machines="
                + written(machines)
                + ",polling="
                + written(polling)
                + ",retries="
                + written(retries);
    }

    /**
     * Returns a time limit for an answer from outside.
     *
     * @param limit Its default.
     * @return The default times {@code services}, rounded up to a whole millisecond.
     */
    public Duration services(Duration limit) {
        return times(limit, services);
    }

    /**
     * Returns a time limit for a program, container or machine at work.
     *
     * @param limit Its default.
     * @return The default times {@code machines}, rounded up to a whole millisecond.
     */
    public Duration machines(Duration limit) {
        return times(limit, machines);
    }

    /**
     * Returns how long until something is looked at again, or how long an answer is kept before it
     * is asked again.
     *
     * @param interval Its default.
     * @return The default times {@code polling}, rounded up to a whole millisecond: 2.0 looks half
     *     as often.
     */
    public Duration polling(Duration interval) {
        return times(interval, polling);
    }

    /**
     * Returns the pause before a failed step is tried again.
     *
     * @param pause Its default.
     * @return The default times {@code retries}, rounded up to a whole millisecond.
     */
    public Duration retryPause(Duration pause) {
        return times(pause, retries);
    }

    /**
     * Returns how often a failed step is tried.
     *
     * @param tries Its default number of tries, at least 1.
     * @return The default times {@code retries}, rounded up, and never less than 1.
     * @throws IllegalArgumentException When the default is less than 1.
     */
    public int tries(int tries) {
        if (tries < 1) {
            throw new IllegalArgumentException("A step is tried at least once, not " + tries);
        }
        final BigDecimal scaled =
                BigDecimal.valueOf(tries).multiply(retries).setScale(0, RoundingMode.CEILING);
        return Math.max(1, scaled.min(BigDecimal.valueOf(Integer.MAX_VALUE)).intValue());
    }

    private static BigDecimal value(String name, String text) {
        if (!VALUE.matcher(text).matches()) {
            throw new IllegalArgumentException(
                    name
                            + " '"
                            + text
                            + "' is no decimal from 0.1 to 100 with a dot and at most two digits"
                            + " after it");
        }
        final BigDecimal value = new BigDecimal(text);
        if (value.compareTo(LEAST) < 0 || value.compareTo(MOST) > 0) {
            throw new IllegalArgumentException(name + " " + text + " lies outside 0.1 to 100");
        }
        return value;
    }

    private static void checked(String name, BigDecimal value) {
        if (value.compareTo(LEAST) < 0 || value.compareTo(MOST) > 0) {
            throw new IllegalArgumentException(
                    name + " " + value.toPlainString() + " lies outside 0.1 to 100");
        }
        if (value.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(
                    name + " " + value.toPlainString() + " has more than two digits after its dot");
        }
    }

    private static String written(BigDecimal value) {
        final BigDecimal plain = value.stripTrailingZeros();
        return (plain.scale() < 1 ? plain.setScale(1) : plain).toPlainString();
    }

    private static Duration times(Duration limit, BigDecimal factor) {
        final BigDecimal seconds =
                BigDecimal.valueOf(limit.getSeconds())
                        .add(BigDecimal.valueOf(limit.getNano(), 9))
                        .multiply(factor);
        return Duration.ofMillis(
                seconds.movePointRight(3).setScale(0, RoundingMode.CEILING).longValueExact());
    }
}
