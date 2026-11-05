package org.fuin.porelo.runtime;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.core.project.Project;
import org.fuin.porelo.wire.Json;
import org.jspecify.annotations.Nullable;

/**
 * What an image was built with and where each thing came from, recorded on the image as a label.
 *
 * <p>Only what the image's own layers settled: a value that could differ between two starts of the
 * same image, a time or how the agent was chosen, would give it a new id at each and report every
 * other task of the project as running an older image.
 *
 * @param baseImage The image it was built on.
 * @param baseImageSource Where that was chosen.
 * @param packages What the install commands among {@code lines} name, each with its line's source.
 * @param lines The project's own build lines, each with where it came from.
 * @param tools What Porelo asks for in every image.
 * @param agent The agent whose tooling it carries, or "" for none.
 * @param agentVersion That agent's version, or "" when its definition names none.
 * @param securityClass The security class of the project it was built for.
 * @param securityClassSource Where that class was set.
 */
public record ImageSettings(
        String baseImage,
        String baseImageSource,
        List<Sourced> packages,
        List<Sourced> lines,
        List<String> tools,
        String agent,
        String agentVersion,
        String securityClass,
        String securityClassSource) {

    /**
     * One thing recorded, with where it came from.
     *
     * @param value The thing.
     * @param source Where it came from.
     */
    public record Sourced(String value, String source) {}

    /** The source of what Porelo itself says or asks for. */
    public static final String PORELO = "porelo";

    /** What stands in a record where a value may be a secret. */
    public static final String WITHHELD = "<withheld>";

    /** The most bytes the value may take in a Containerfile. */
    static final int MOST_RENDERED = 64 * 1024;

    /** The most bytes of a label read: its reply must fit the wire's limit of 4 MiB. */
    static final int MOST_READ = 1024 * 1024;

    /** The label it is recorded in. */
    public static final String LABEL = "org.fuin.porelo.settings";

    /** A later release that changes the shape changes this, and reads as "does not know" here. */
    private static final int VERSION = 1;

    private static final Pattern ENV = Pattern.compile("(?i)\\s*ENV\\s+(.*)");

    private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");

    /** The older form, which sets one variable to the rest of its line. */
    private static final Pattern ENV_SPACED =
            Pattern.compile("\\s*([A-Za-z_][A-Za-z0-9_.-]*)\\s+\\S.*");

    private static final Pattern USERINFO = Pattern.compile("://[^\\s/'\"]*@");

    private static final Pattern RUN = Pattern.compile("(?i)\\s*RUN\\s+(.*)");

    private static final Pattern ASSIGNMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*=.*");

    /** A version as a definition pins one; its field is free text and shown to whoever asks. */
    private static final Pattern VERSION_SHAPE = Pattern.compile("v?[0-9][0-9A-Za-z.+~_-]{0,63}");

    private static final Map<String, String> INSTALLERS =
            Map.of(
                    "apt-get", "install",
                    "apt", "install",
                    "dnf", "install",
                    "yum", "install",
                    "microdnf", "install",
                    "apk", "add");

    private static final String MARK = Matcher.quoteReplacement(WITHHELD);

    /** A URL whose address before its path is cut by a line's continuation. */
    private static final Pattern AUTHORITY_OPEN = Pattern.compile("://[^/\\s@]*\\\\$");

    /** A line after such a cut that is all address, and cut again. */
    private static final Pattern AUTHORITY_ALL_OF_IT = Pattern.compile("\\s*[^/\\s@]*\\\\\\s*");

    /** The start of the line after such a cut, up to where that address ends. */
    private static final Pattern AUTHORITY_GOES_ON = Pattern.compile("^\\s*[^/\\s@]*");

    private static final Pattern STANDS_ALONE =
            Pattern.compile(
                    "-[yqfVUulmC]+|--(yes|assume-yes|assumeyes|quiet|no-install-recommends"
                            + "|no-install-suggests|install-recommends|force-yes|reinstall"
                            + "|fix-missing|fix-broken|ignore-missing|allow-unauthenticated"
                            + "|allow-downgrades|allow-change-held-packages|no-upgrade|only-upgrade"
                            + "|no-cache|update|update-cache|no-progress|latest|upgrade"
                            + "|allow-untrusted|nogpgcheck|best|nobest|allowerasing|skip-broken"
                            + "|refresh|cacheonly|verbose-versions)");

    /**
     * Constructor with all data.
     *
     * @param baseImage The image it was built on.
     * @param baseImageSource Where that was chosen.
     * @param packages What the install commands name.
     * @param lines The project's own build lines.
     * @param tools What Porelo asks for in every image.
     * @param agent The agent, or "".
     * @param agentVersion Its version, or "".
     * @param securityClass The security class.
     * @param securityClassSource Where that class was set.
     */
    public ImageSettings {
        packages = List.copyOf(packages);
        lines = List.copyOf(lines);
        tools = List.copyOf(tools);
    }

    /**
     * Reads what a project's build would record.
     *
     * @param project The project as it is built.
     * @param baseImageSource Where its base image was chosen.
     * @param lineSources Where each of its build lines came from, in their order.
     * @param securityClassSource Where its class was set.
     * @param agent The agent, or "".
     * @param agentVersion Its version, or "".
     * @return What to record.
     */
    public static ImageSettings of(
            Project project,
            String baseImageSource,
            List<String> lineSources,
            String securityClassSource,
            String agent,
            String agentVersion) {
        final List<Sourced> lines = recorded(project.imageSnippetLines(), lineSources);
        return fitted(
                new ImageSettings(
                        project.baseImage(),
                        baseImageSource,
                        packagesIn(lines),
                        lines,
                        Containerfile.TOOLS,
                        agent,
                        VERSION_SHAPE.matcher(agentVersion).matches() ? agentVersion : "",
                        project.securityClass().name().toLowerCase(java.util.Locale.ROOT),
                        securityClassSource));
    }

    /**
     * The lines as a label may hold them. An ENV line by its names, however it is continued, and a
     * URL's user and password covered; a secret written any other way cannot be told from the rest.
     */
    private static List<Sourced> recorded(final List<String> lines, final List<String> sources) {
        final List<Sourced> recorded = new ArrayList<>();
        boolean inAuthority = false;
        for (int index = 0; index < lines.size(); index++) {
            final String source = index < sources.size() ? sources.get(index) : "";
            final Matcher env = ENV.matcher(lines.get(index));
            if (!env.matches()) {
                String line = lines.get(index);
                if (inAuthority) {
                    inAuthority = AUTHORITY_ALL_OF_IT.matcher(line).matches();
                    line =
                            inAuthority
                                    ? WITHHELD + "\\"
                                    : AUTHORITY_GOES_ON.matcher(line).replaceFirst(MARK);
                }
                line = USERINFO.matcher(line).replaceAll("://" + MARK + "@");
                final Matcher open = AUTHORITY_OPEN.matcher(line);
                if (open.find()) {
                    line = open.replaceFirst("://" + MARK + "\\\\");
                    inAuthority = true;
                }
                recorded.add(new Sourced(line, source));
                continue;
            }
            inAuthority = false;
            final StringBuilder whole = new StringBuilder(env.group(1));
            while (whole.toString().endsWith("\\") && index + 1 < lines.size()) {
                final String next = lines.get(++index);
                // A builder drops a comment or an empty line inside a continuation and goes on.
                if (next.isBlank() || next.stripLeading().startsWith("#")) {
                    continue;
                }
                whole.setLength(whole.length() - 1);
                whole.append(' ').append(next);
            }
            recorded.add(new Sourced("ENV " + namesOf(whole.toString()), source));
        }
        return recorded;
    }

    /**
     * {@code A=1 B="2 3"} as {@code A=<mark> B=<mark>}; only the mark when a name cannot be told.
     */
    private static String namesOf(final String assignments) {
        final Matcher spaced = ENV_SPACED.matcher(assignments);
        if (spaced.matches()) {
            return spaced.group(1) + " " + WITHHELD;
        }
        final List<String> names = new ArrayList<>();
        int at = 0;
        final String text = assignments.strip();
        while (at < text.length()) {
            final int equals = text.indexOf('=', at);
            if (equals < 0 || !ENV_NAME.matcher(text.substring(at, equals)).matches()) {
                return WITHHELD;
            }
            names.add(text.substring(at, equals) + "=" + WITHHELD);
            at = afterValue(text, equals + 1);
        }
        return names.isEmpty() ? WITHHELD : String.join(" ", names);
    }

    /** Where the next assignment starts: past a value, its quotes and escapes, and the blanks. */
    private static int afterValue(final String text, final int start) {
        int at = start;
        char quote = 0;
        while (at < text.length()) {
            final char each = text.charAt(at);
            if (each == '\\') {
                at++;
            } else if (quote != 0) {
                quote = each == quote ? 0 : quote;
            } else if (each == '"' || each == '\'') {
                quote = each;
            } else if (Character.isWhitespace(each)) {
                break;
            }
            at++;
        }
        while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
            at++;
        }
        return at;
    }

    /** What install commands name, each with its line's source; read and never run. */
    private static List<Sourced> packagesIn(final List<Sourced> lines) {
        final Map<String, Sourced> found = new LinkedHashMap<>();
        final StringBuilder whole = new StringBuilder();
        String source = "";
        for (final Sourced line : lines) {
            if (line.value().stripLeading().startsWith("#")) {
                continue;
            }
            if (whole.isEmpty()) {
                source = line.source();
            }
            if (line.value().endsWith("\\")) {
                whole.append(line.value(), 0, line.value().length() - 1).append(' ');
                continue;
            }
            whole.append(line.value());
            final Matcher run = RUN.matcher(whole);
            if (run.matches()) {
                for (final List<String> command : commandsOf(run.group(1))) {
                    for (final String name : installedBy(command)) {
                        found.putIfAbsent(name, new Sourced(name, source));
                    }
                }
            }
            whole.setLength(0);
        }
        return List.copyOf(found.values());
    }

    /**
     * The words of each command a shell line runs after {@code &&}, {@code ||} or {@code ;}. What
     * follows a single {@code |} or {@code &} is no such command, and a quoted separator is a word.
     */
    private static List<List<String>> commandsOf(final String line) {
        final List<List<String>> commands = new ArrayList<>();
        List<String> words = new ArrayList<>();
        final StringBuilder word = new StringBuilder();
        boolean ended = false;
        char quote = 0;
        for (int at = 0; at < line.length(); at++) {
            final char each = line.charAt(at);
            if (quote != 0) {
                if (each == quote) {
                    quote = 0;
                } else {
                    word.append(each);
                }
                continue;
            }
            if (each == '\'' || each == '"') {
                quote = each;
            } else if (each == '\\' && at + 1 < line.length()) {
                word.append(line.charAt(++at));
            } else if (each == '#' && word.isEmpty()) {
                break;
            } else if (!Character.isWhitespace(each) && "&|;)".indexOf(each) < 0) {
                word.append(each);
            } else {
                if (!word.isEmpty() && !ended) {
                    words.add(word.toString());
                }
                word.setLength(0);
                final boolean doubled =
                        (each == '&' || each == '|')
                                && at + 1 < line.length()
                                && line.charAt(at + 1) == each;
                if (each == ';' || doubled) {
                    commands.add(words);
                    words = new ArrayList<>();
                    ended = false;
                    at += doubled ? 1 : 0;
                } else if ("&|)".indexOf(each) >= 0) {
                    ended = true;
                }
            }
        }
        if (!word.isEmpty() && !ended) {
            words.add(word.toString());
        }
        commands.add(words);
        return commands;
    }

    /**
     * What one command installs. An option not known to stand alone may take the next word as its
     * value, which then counts as no package: a wrong name is worse than a missing one, and the
     * line shows the text anyway.
     */
    private static List<String> installedBy(final List<String> words) {
        int at = 0;
        boolean sudo = false;
        while (at < words.size()) {
            final String word = words.get(at);
            if (word.equals("sudo")) {
                sudo = true;
            } else if (!ASSIGNMENT.matcher(word).matches() && !(sudo && word.startsWith("-"))) {
                break;
            }
            at++;
        }
        if (at == words.size() || !INSTALLERS.containsKey(words.get(at))) {
            return List.of();
        }
        final String verb = INSTALLERS.get(words.get(at));
        boolean installing = false;
        final List<String> named = new ArrayList<>();
        for (at++; at < words.size(); at++) {
            final String word = words.get(at);
            if (word.contains(">") || word.contains("<")) {
                // A redirection: what follows is a file, and the names stand before it.
                break;
            }
            if (word.startsWith("-")) {
                at += word.contains("=") || STANDS_ALONE.matcher(word).matches() ? 0 : 1;
            } else if (!installing) {
                if (!word.equals(verb)) {
                    return List.of();
                }
                installing = true;
            } else if (!word.matches(".*[$`(].*")) {
                named.add(word);
            }
        }
        return named;
    }

    /** The record, when it is too long with fewer lines from the end and a line saying so. */
    private static ImageSettings fitted(final ImageSettings whole) {
        ImageSettings fitted = whole;
        int kept = whole.lines().size();
        while (fitted.renderedBytes() > MOST_RENDERED && kept > 0) {
            kept = Math.min(kept - 1, (int) ((long) kept * MOST_RENDERED / fitted.renderedBytes()));
            fitted = whole.keeping(kept, whole.packages(), " more build lines are");
        }
        if (fitted.renderedBytes() > MOST_RENDERED) {
            fitted = whole.keeping(0, List.of(), " build lines and what they install are");
        }
        return fitted;
    }

    private ImageSettings keeping(
            final int kept, final List<Sourced> keptPackages, final String words) {
        final List<Sourced> cut = new ArrayList<>(lines.subList(0, kept));
        cut.add(
                new Sourced(
                        "["
                                + (lines.size() - kept)
                                + words
                                + " not recorded: this record holds "
                                + MOST_RENDERED / 1024
                                + " KiB]",
                        PORELO));
        return new ImageSettings(
                baseImage,
                baseImageSource,
                keptPackages,
                cut,
                tools,
                agent,
                agentVersion,
                securityClass,
                securityClassSource);
    }

    private int renderedBytes() {
        return rendered().getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * Returns the label's value.
     *
     * @return One line of JSON.
     */
    public String json() {
        final Map<String, Object> label = new LinkedHashMap<>();
        label.put("version", VERSION);
        label.put("baseImage", baseImage);
        label.put("baseImageSource", baseImageSource);
        label.put("packages", maps(packages));
        label.put("lines", maps(lines));
        label.put("tools", tools);
        label.put("agent", agent);
        label.put("agentVersion", agentVersion);
        label.put("securityClass", securityClass);
        label.put("securityClassSource", securityClassSource);
        return Json.write(label);
    }

    private static List<Map<String, String>> maps(final List<Sourced> values) {
        return values.stream()
                .map(each -> Map.of("value", each.value(), "source", each.source()))
                .toList();
    }

    /**
     * Returns the value as a Containerfile's {@code LABEL} writes it: podman expands {@code $} in
     * one and strips a backslash.
     *
     * @return The value, without its surrounding quotes.
     */
    String rendered() {
        return json().replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$");
    }

    /**
     * Reads a label's value.
     *
     * @param json What the image holds.
     * @return The settings, or empty for anything this version did not write.
     */
    public static Optional<ImageSettings> parse(String json) {
        if (json.getBytes(StandardCharsets.UTF_8).length > MOST_READ) {
            return Optional.empty();
        }
        final Object parsed;
        try {
            parsed = Json.parse(json);
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
        if (!(parsed instanceof Map<?, ?> label)
                || !(label.get("version") instanceof Number version)
                || version.intValue() != VERSION) {
            return Optional.empty();
        }
        final String baseImage = text(label, "baseImage");
        final String baseImageSource = text(label, "baseImageSource");
        final List<Sourced> packages = sourced(label, "packages");
        final List<Sourced> lines = sourced(label, "lines");
        final List<String> tools = texts(label, "tools");
        final String agent = text(label, "agent");
        final String agentVersion = text(label, "agentVersion");
        final String securityClass = text(label, "securityClass");
        final String securityClassSource = text(label, "securityClassSource");
        if (baseImage == null
                || baseImageSource == null
                || packages == null
                || lines == null
                || tools == null
                || agent == null
                || agentVersion == null
                || securityClass == null
                || securityClassSource == null) {
            return Optional.empty();
        }
        return Optional.of(
                new ImageSettings(
                        baseImage,
                        baseImageSource,
                        packages,
                        lines,
                        tools,
                        agent,
                        agentVersion,
                        securityClass,
                        securityClassSource));
    }

    private static @Nullable String text(final Map<?, ?> label, final String name) {
        return label.get(name) instanceof String value ? value : null;
    }

    private static @Nullable List<Sourced> sourced(final Map<?, ?> label, final String name) {
        if (!(label.get(name) instanceof List<?> values)) {
            return null;
        }
        final List<Sourced> read = new ArrayList<>();
        for (final Object value : values) {
            if (!(value instanceof Map<?, ?> each)
                    || !(each.get("value") instanceof String text)
                    || !(each.get("source") instanceof String source)) {
                return null;
            }
            read.add(new Sourced(text, source));
        }
        return read;
    }

    private static @Nullable List<String> texts(final Map<?, ?> label, final String name) {
        if (!(label.get(name) instanceof List<?> values)) {
            return null;
        }
        final List<String> read = new ArrayList<>();
        for (final Object value : values) {
            if (!(value instanceof String each)) {
                return null;
            }
            read.add(each);
        }
        return read;
    }
}
