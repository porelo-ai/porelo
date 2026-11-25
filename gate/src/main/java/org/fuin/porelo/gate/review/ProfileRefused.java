package org.fuin.porelo.gate.review;

import java.util.Objects;

/** A profile document refused as a whole; nothing of it was taken. */
public final class ProfileRefused extends Exception {

    private static final long serialVersionUID = 1L;

    private final String rule;

    /**
     * Constructor with the rule.
     *
     * @param rule The rule it broke, never its text.
     */
    public ProfileRefused(final String rule) {
        super(Objects.requireNonNull(rule, "rule"));
        this.rule = rule;
    }

    /**
     * Returns the rule it broke.
     *
     * @return For example {@code schema: porelo/sourceKind in a submission}.
     */
    public String rule() {
        return rule;
    }
}
