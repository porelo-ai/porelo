package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Holds {@code doc/rights.md} to the contract's {@code Right}.
 *
 * <p>The page tells a reader what a task may do. A right the contract has and the page lacks is a
 * power nobody reads about; a right the page names and the contract lacks is one nobody can hold.
 * So the page's table of rights is read against the contract's type, its table of rules against the
 * rules the contract's {@code StartRuleBroken} names, and the two headings other pages link to must
 * stay. The page names no planning tool's role or file: those are the tool's, never Porelo's.
 */
@Tag("documents")
class RightsPageTest {

    private static final Pattern RIGHT_TYPE = Pattern.compile("type Right \\(([^)]*)\\)");

    private static final Pattern ROW = Pattern.compile("^\\| `([A-Za-z]+)` \\|");

    /** The comment the contract puts above {@code StartRuleBroken}, up to the error. */
    private static final Pattern RULE_COMMENT =
            Pattern.compile(
                    "'rule' is((?:[^\\n]*\\n# )*[^\\n]*?); 'detail'[^\\n]*\\nerror StartRuleBroken\\(");

    /** A rule's word: lower camel case, as the contract and the page write it. */
    private static final Pattern RULE = Pattern.compile("\\b[a-z]+(?:[A-Z][a-z]*)+\\b");

    /** A backticked word in the last cell of a row of the page's table of rules. */
    private static final Pattern NAMED = Pattern.compile("`([a-z]+(?:[A-Z][a-z]*)+)`");

    /** A planning tool's roles and file, which the page must not name. */
    private static final Pattern PLANNING =
            Pattern.compile(
                    "\\b(planner|work\\.yml|reviewer|screener|tester)s?\\b",
                    Pattern.CASE_INSENSITIVE);

    private static List<String> lines() throws IOException {
        return Files.readAllLines(Path.of("../../doc/rights.md"), StandardCharsets.UTF_8);
    }

    private static String contract() throws IOException {
        return Files.readString(
                Path.of(
                        "../../wire/src/main/resources/varlink/org.fuin.porelo.Tasks1/"
                                + "15-task.varlink"),
                StandardCharsets.UTF_8);
    }

    private static List<String> contractRights() throws IOException {
        final String contract = contract();
        final Matcher type = RIGHT_TYPE.matcher(contract);
        assertThat(type.find()).as("type Right in the contract").isTrue();
        return Arrays.stream(type.group(1).split(","))
                .map(String::strip)
                .filter(word -> !word.isEmpty())
                .toList();
    }

    /** The words of the first column of the table whose header cell is {@code Right}. */
    private static List<String> tableOfRights() throws IOException {
        final List<String> words = new ArrayList<>();
        boolean inTable = false;
        for (final String line : lines()) {
            if (line.startsWith("| Right |")) {
                inTable = true;
            } else if (inTable && !line.startsWith("|")) {
                break;
            } else if (inTable) {
                final Matcher row = ROW.matcher(line);
                if (row.find()) {
                    words.add(row.group(1));
                }
            }
        }
        return words;
    }

    /** The rules the contract's comment on {@code StartRuleBroken} names. */
    private static List<String> contractRules() throws IOException {
        final Matcher comment = RULE_COMMENT.matcher(contract());
        assertThat(comment.find()).as("the comment on StartRuleBroken in the contract").isTrue();
        return RULE.matcher(comment.group(1)).results().map(MatchResult::group).distinct().toList();
    }

    /** The rules the last column of the table whose header cell is {@code Rule} names. */
    private static List<String> tableOfRules() throws IOException {
        final List<String> rules = new ArrayList<>();
        boolean inTable = false;
        for (final String line : lines()) {
            if (line.startsWith("| Rule |")) {
                inTable = true;
            } else if (inTable && !line.startsWith("|")) {
                break;
            } else if (inTable && !line.startsWith("|---")) {
                final String last = line.substring(line.lastIndexOf('|', line.length() - 2));
                NAMED.matcher(last).results().map(found -> found.group(1)).forEach(rules::add);
            }
        }
        return rules;
    }

    /** The lines of a text that name a planning tool's role or file. */
    static List<String> planningWords(final List<String> text) {
        return text.stream().filter(line -> PLANNING.matcher(line).find()).toList();
    }

    @Test
    void namesEveryRuleTheContractHas() throws IOException {
        assertThat(contractRules()).as("rules in the contract").hasSizeGreaterThan(4);
        assertThat(tableOfRules()).as("rules in the page's table").containsAll(contractRules());
    }

    @Test
    void namesNoRuleTheContractLacks() throws IOException {
        assertThat(tableOfRules()).as("rules in the page's table").isNotEmpty();
        assertThat(contractRules()).containsAll(tableOfRules());
    }

    @Test
    void namesNoPlanningToolsRoleOrFile() throws IOException {
        // The search is shown to find what it looks for before its empty answer counts.
        assertThat(planningWords(List.of("a screener reads work.yml", "the task's review")))
                .as("a planted role and file")
                .containsExactly("a screener reads work.yml");
        assertThat(planningWords(lines())).as("lines of the page naming a planning role").isEmpty();
    }

    @Test
    void namesEveryRightTheContractHas() throws IOException {
        // A guard that finds nothing passes forever: assert what it read, then what it checks.
        assertThat(contractRights()).as("rights in the contract").hasSizeGreaterThan(5);
        assertThat(tableOfRights()).as("rights in the page's table").containsAll(contractRights());
    }

    @Test
    void namesNoRightTheContractLacks() throws IOException {
        assertThat(tableOfRights()).as("rights in the page's table").isNotEmpty();
        assertThat(contractRights()).containsAll(tableOfRights());
    }

    @Test
    void keepsTheHeadingsOtherPagesLinkTo() throws IOException {
        assertThat(lines())
                .contains("## The four parts of a start", "## The rules Porelo keeps itself");
    }
}
