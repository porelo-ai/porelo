package org.fuin.porelo.app;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.fuin.porelo.wire.LimitFactors;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The account's own settings, from {@code $XDG_CONFIG_HOME/porelo/config.yml}: the four factors
 * every time limit follows, and the settings that are a person's decision about how long something
 * holds.
 *
 * <p>A missing file is every default. An unknown key or a bad value is refused by its name, so a
 * misspelt setting never silently stays at its default; {@code porelod} reads the file at its start
 * and does not start on a refusal.
 *
 * @param limits The four factors.
 * @param certificateDays How long the person's certification of a task holds.
 * @param cardDays How long a card holds from the day it is made.
 * @param renewNoticeDays How many days before its end a card is said to be due.
 * @param askedAboutDays How long a sent message is asked about.
 * @param keptDays How long a task's run records are kept.
 */
public record AccountConfig(
        LimitFactors limits,
        int certificateDays,
        int cardDays,
        int renewNoticeDays,
        int askedAboutDays,
        int keptDays) {

    /** The file's name in the account's configuration directory. */
    public static final String FILE = "config.yml";

    /** Every setting at its default. */
    public static final AccountConfig DEFAULT =
            new AccountConfig(LimitFactors.DEFAULT, 7, 30, 7, 7, 30);

    private static final int MOST_DAYS = 3650;

    // Each section and the keys it may hold; anything else is refused by name.
    private static final Map<String, Set<String>> KEYS =
            Map.of(
                    "limits", Set.of("services", "machines", "polling", "retries"),
                    "task", Set.of("certificate_days"),
                    "identity", Set.of("card_days", "renew_notice_days"),
                    "messages", Set.of("asked_about_days"),
                    "records", Set.of("kept_days"));

    /**
     * Constructor with checks.
     *
     * @param limits The four factors.
     * @param certificateDays How long the person's certification of a task holds.
     * @param cardDays How long a card holds from the day it is made.
     * @param renewNoticeDays How many days before its end a card is said to be due.
     * @param askedAboutDays How long a sent message is asked about.
     * @param keptDays How long a task's run records are kept.
     * @throws IllegalArgumentException When a number of days lies outside 1 to 3650.
     */
    public AccountConfig {
        days("task.certificate_days", certificateDays);
        days("identity.card_days", cardDays);
        days("identity.renew_notice_days", renewNoticeDays);
        days("messages.asked_about_days", askedAboutDays);
        days("records.kept_days", keptDays);
    }

    /**
     * Reads the account's file, or every default when there is none.
     *
     * @param file Where {@code config.yml} lies.
     * @return The settings.
     * @throws IOException When the file is there but cannot be read.
     * @throws IllegalArgumentException Naming the key, for an unknown key or a bad value.
     */
    public static AccountConfig read(Path file) throws IOException {
        final String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (NoSuchFileException absent) {
            return DEFAULT;
        }
        return read(new StringReader(text), file.toString());
    }

    /**
     * Reads settings from YAML.
     *
     * @param reader The YAML.
     * @param origin Where it came from, for the refusal.
     * @return The settings; an empty document is every default.
     * @throws IllegalArgumentException Naming the key, for an unknown key or a bad value.
     */
    public static AccountConfig read(Reader reader, String origin) {
        final LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        final Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(options)).load(reader);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(
                    "Cannot read " + origin + ": " + ex.getMessage(), ex);
        }
        if (loaded == null) {
            return DEFAULT;
        }
        if (!(loaded instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException(origin + " is no YAML mapping");
        }
        final Map<String, Map<?, ?>> sections = new LinkedHashMap<>();
        for (final Map.Entry<?, ?> each : root.entrySet()) {
            final String section = String.valueOf(each.getKey());
            final Set<String> keys = KEYS.get(section);
            if (keys == null) {
                throw new IllegalArgumentException(
                        origin + ": '" + section + "' is no setting; the settings are " + names());
            }
            if (each.getValue() == null) {
                // A section written with nothing under it is every default of that section.
                continue;
            }
            if (!(each.getValue() instanceof Map<?, ?> values)) {
                throw new IllegalArgumentException(origin + ": '" + section + "' is no mapping");
            }
            for (final Object key : values.keySet()) {
                if (!keys.contains(String.valueOf(key))) {
                    throw new IllegalArgumentException(
                            origin + ": '" + section + "." + key + "' is no setting");
                }
            }
            sections.put(section, values);
        }
        try {
            return new AccountConfig(
                    limits(sections.get("limits")),
                    days(sections, "task", "certificate_days", DEFAULT.certificateDays),
                    days(sections, "identity", "card_days", DEFAULT.cardDays),
                    days(sections, "identity", "renew_notice_days", DEFAULT.renewNoticeDays),
                    days(sections, "messages", "asked_about_days", DEFAULT.askedAboutDays),
                    days(sections, "records", "kept_days", DEFAULT.keptDays));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(origin + ": " + ex.getMessage(), ex);
        }
    }

    private static LimitFactors limits(@Nullable Map<?, ?> given) {
        if (given == null) {
            return LimitFactors.DEFAULT;
        }
        final StringBuilder line = new StringBuilder();
        for (final Map.Entry<?, ?> each : given.entrySet()) {
            final String key = String.valueOf(each.getKey());
            final String value =
                    switch (each.getValue()) {
                        case Integer whole -> whole.toString();
                        case Double decimal when Double.isFinite(decimal) ->
                                BigDecimal.valueOf(decimal).stripTrailingZeros().toPlainString();
                        case null, default ->
                                throw new IllegalArgumentException(
                                        "limits."
                                                + key
                                                + " is no decimal from 0.1 to 100, but "
                                                + each.getValue());
                    };
            line.append(line.isEmpty() ? "" : ",").append(key).append('=').append(value);
        }
        try {
            return LimitFactors.parse(line.toString());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("limits: " + ex.getMessage(), ex);
        }
    }

    private static int days(
            Map<String, Map<?, ?>> sections, String section, String key, int otherwise) {
        final Map<?, ?> values = sections.get(section);
        final Object given = values == null ? null : values.get(key);
        if (given == null && (values == null || !values.containsKey(key))) {
            return otherwise;
        }
        if (!(given instanceof Integer whole)) {
            throw new IllegalArgumentException(
                    section + "." + key + " is a whole number of days, not " + given);
        }
        return whole;
    }

    private static void days(String name, int value) {
        if (value < 1 || value > MOST_DAYS) {
            throw new IllegalArgumentException(
                    name + " is from 1 to " + MOST_DAYS + " days, not " + value);
        }
    }

    private static String names() {
        return String.join(", ", List.of("limits", "task", "identity", "messages", "records"));
    }
}
