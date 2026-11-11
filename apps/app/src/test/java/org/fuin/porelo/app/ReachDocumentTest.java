package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.fuin.porelo.shield.NftRuleset;
import org.fuin.porelo.supervisor.VaultProxy;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Holds {@code doc/reach.md} to the bounds in the code.
 *
 * <p>The page tells an operator how far a task can get. A bound that widens in the code while the
 * page still states the old one is the worst shape this can take: an operator holding a belief the
 * system does not support. So each number the page states is read off the code here, and a change
 * to either fails the build until both say the same.
 */
@Tag("documents")
class ReachDocumentTest {

    private static String page() throws IOException {
        return Files.readString(Path.of("../../doc/reach.md"), StandardCharsets.UTF_8);
    }

    @Test
    void statesTheDefaultCpuBoundAndWhatStillReadsTheHost() throws IOException {
        assertThat(page())
                .as("an operator must see both the enforced quota and the core-count exposure")
                .contains(
                        "**"
                                + org.fuin.porelo.core.project.Limits.DEFAULT_CPUS
                                + " CPUs** by default")
                .contains("`getconf` and `sysconf` still read the host's cores");
    }

    @Test
    void statesHowMuchOneRequestToTheProviderCanCarry() throws IOException {
        assertThat(page()).contains("up to **" + VaultProxy.BODY_LIMIT / (1024 * 1024) + " MB**");
    }

    @Test
    void statesTheBoundOnOptionalUsageParsing() throws IOException {
        assertThat(page())
                .as("usage observation cannot grow to hold an entire stream")
                .contains(
                        "bounded to "
                                + VaultProxy.USAGE_LIMIT / (1024 * 1024)
                                + " MiB per body or event");
    }

    @Test
    void statesThePortsADeclaredHostIsOpenedOn() throws IOException {
        assertThat(page())
                .contains(
                        "on ports "
                                + NftRuleset.DEFAULT_PORTS.stream()
                                        .map(String::valueOf)
                                        .collect(Collectors.joining(" and "))
                                + " only");
    }

    @Test
    void statesThePortAHostServiceIsOpenedOn() throws IOException {
        assertThat(page())
                .as("the declared host-service port must agree with the firewall")
                .contains("loopback on TCP " + NftRuleset.HOST_SERVICE_PORT + " only");
    }

    @Test
    void namesEveryCredentialHeaderThatIsDropped() throws IOException {
        final String page = page();
        for (final String header : VaultProxy.CREDENTIAL_HEADERS) {
            assertThat(page).as(header).contains("`" + header + "`");
        }
    }

    @Test
    void statesHowMuchOfAnAnswerIsExaminedFirst() throws IOException {
        assertThat(page()).contains("The first " + VaultProxy.PEEK / 1024 + " KB of every answer");
    }

    @Test
    void namesEveryGrantThatIsRefused() throws IOException {
        final String page = page();
        for (final String grant : VaultProxy.REFUSED_GRANTS) {
            assertThat(page).as(grant).contains("`" + grant + "`");
        }
    }

    @Test
    void saysOfEveryGuardWhetherItIsAControlOrAnAccidentCatcher() throws IOException {
        // A summary of this that counted the accident-catchers as containment would be wrong.
        final String page = page();
        for (final String line :
                page.lines()
                        .filter(
                                each ->
                                        each.startsWith("| ")
                                                && !each.startsWith("| |")
                                                && !each.startsWith("|---"))
                        .toList()) {
            assertThat(line).as(line).containsAnyOf("| control |", "| **accident-catcher** |");
        }
        assertThat(page).contains("Porelo does not detect that.");
    }

    /** A table row as the page writes it, under the part its heading opens. */
    private record PageRow(Reach.Part part, String subject, String bound, Reach.Kind kind) {}

    /** The part a heading such as {@code ## Hold: ...} opens, or {@code null}. */
    private static Reach.@org.jspecify.annotations.Nullable Part part(String heading) {
        for (final Reach.Part part : Reach.Part.values()) {
            final String name = part.name().toLowerCase(Locale.ROOT);
            if (heading.startsWith(
                    "## " + Character.toUpperCase(name.charAt(0)) + name.substring(1) + ":")) {
                return part;
            }
        }
        return null;
    }

    private static List<PageRow> tableRows(String page) {
        final List<PageRow> rows = new ArrayList<>();
        Reach.Part part = null;
        for (final String line : page.lines().toList()) {
            if (line.startsWith("## ")) {
                part = part(line);
            } else if (line.startsWith("| ")
                    && !line.startsWith("| |")
                    && !line.startsWith("|---")) {
                final String[] cells = line.substring(2, line.length() - 2).split(" \\| ", -1);
                assertThat(cells).as(line).hasSize(3);
                assertThat(part).as("a row outside reach, hold, spend and produce").isNotNull();
                rows.add(
                        new PageRow(
                                part,
                                cells[0],
                                cells[1],
                                "**accident-catcher**".equals(cells[2])
                                        ? Reach.Kind.ACCIDENT_CATCHER
                                        : Reach.Kind.CONTROL));
            }
        }
        return rows;
    }

    /** The paths the page says are not bounded, each as its part and subject. */
    private static List<String> unbounded(String page) {
        final Pattern item = Pattern.compile("^- \\*\\*(.+?)\\.?\\*\\*");
        final Pattern paragraph = Pattern.compile("^\\*\\*Not bounded: (.+?)\\.\\*\\*");
        final List<String> found = new ArrayList<>();
        Reach.Part part = null;
        boolean list = false;
        for (final String line : page.lines().toList()) {
            if (line.startsWith("## ")) {
                part = part(line);
            }
            final Matcher bullet = item.matcher(line);
            final Matcher opening = paragraph.matcher(line);
            if ("**Not bounded:**".equals(line)) {
                list = true;
            } else if (list && bullet.find()) {
                found.add(part + " " + bullet.group(1));
            } else if (opening.find()) {
                final String subject = opening.group(1);
                found.add(
                        part
                                + " "
                                + Character.toUpperCase(subject.charAt(0))
                                + subject.substring(1));
            } else if (!line.isBlank() && !line.startsWith("  ")) {
                list = false;
            }
        }
        return found;
    }

    /** The text with line breaks as the page renders them, and case ignored. */
    private static String flat(String text) {
        return text.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static PoreloContext context(Path dir) {
        return new PoreloContext(
                new org.fuin.porelo.testing.FakeCommandRunner(),
                new PoreloPaths(
                        org.fuin.porelo.core.config.XdgPaths.of(
                                name ->
                                        switch (name) {
                                            case "XDG_CONFIG_HOME" ->
                                                    dir.resolve("config").toString();
                                            case "XDG_DATA_HOME" -> dir.resolve("data").toString();
                                            case "XDG_RUNTIME_DIR" -> dir.resolve("run").toString();
                                            default -> null;
                                        },
                                dir),
                        dir.resolve("bin")),
                arguments -> 0);
    }

    @Test
    void theDaemonAnswersEveryRowOfThePageWithTheSameBoundAndKind(@TempDir Path dir)
            throws IOException {
        // A client reading the daemon must be told exactly what an operator reading the page is.
        final List<PageRow> page = tableRows(page());
        assertThat(page)
                .as("the reference is every table row of reach, hold, spend and produce")
                .hasSize(29)
                .extracting(PageRow::part)
                .containsOnly(Reach.Part.values());
        assertThat(
                        new Reach(context(dir))
                                .machine().stream()
                                        .filter(row -> row.kind() != Reach.Kind.NOT_BOUNDED)
                                        .map(
                                                row ->
                                                        new PageRow(
                                                                row.part(),
                                                                row.subject(),
                                                                row.bound(),
                                                                row.kind()))
                                        .toList())
                .as("the daemon's rows versus the tables of doc/reach.md")
                .containsExactlyElementsOf(page);
    }

    @Test
    void theDaemonNamesEveryPathThePageLeavesOpenInThePagesWords(@TempDir Path dir)
            throws IOException {
        // An open path missing from the answer reads as a closed one.
        final String page = page();
        final List<String> named = unbounded(page);
        assertThat(named)
                .as("the reference is every path the page says is not bounded")
                .containsExactly(
                        "REACH The model provider",
                        "REACH Every declared host is a second such channel",
                        "REACH A DNS query sent past the resolver",
                        "SPEND A credential that cannot be read can still be spent",
                        "PRODUCE The work itself");
        final List<Reach.Row> open =
                new Reach(context(dir))
                        .machine().stream()
                                .filter(row -> row.kind() == Reach.Kind.NOT_BOUNDED)
                                .toList();
        assertThat(open.stream().map(row -> row.part() + " " + row.subject()).toList())
                .as("the daemon's open paths versus the page's")
                .containsExactlyElementsOf(named);
        for (final Reach.Row row : open) {
            for (final String sentence : row.bound().split("(?<=\\.)\\s+(?=[A-Z])")) {
                assertThat(flat(page)).as(row.subject()).contains(flat(sentence));
            }
        }
    }
}
