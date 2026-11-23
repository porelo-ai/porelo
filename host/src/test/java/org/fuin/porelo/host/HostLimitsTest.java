package org.fuin.porelo.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.fuin.porelo.wire.LimitFactors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** porelo-host takes its time factors and day counts from root's host configuration alone. */
class HostLimitsTest {
    private static final String LINE = "services=2.0,machines=1.5,polling=3.0,retries=0.5";

    @TempDir Path directory;
    private HostReadFixture fixture;

    @BeforeEach
    void setUp() throws IOException {
        fixture = new HostReadFixture(directory);
    }

    private HostLimits read() {
        return HostLimits.read(fixture.files());
    }

    @Test
    void aHostWithoutTheFileKeepsEveryDefault() {
        assertThat(read())
                .as("a host installed before the role wrote host.yml keeps all four at 1.0")
                .isEqualTo(new HostLimits(LimitFactors.DEFAULT, 7, 1));
    }

    @Test
    void theRolesThreeKeysAreRead() throws IOException {
        fixture.put(
                HostLimits.FILE,
                "limits: " + LINE + "\ncertificate_days: 30\nlent_token_days: 2\n");
        assertThat(read()).isEqualTo(new HostLimits(LimitFactors.parse(LINE), 30, 2));
    }

    @Test
    void aKeyLeftOutKeepsItsDefault() throws IOException {
        fixture.put(HostLimits.FILE, "limits: " + LINE + "\n");
        assertThat(read()).isEqualTo(new HostLimits(LimitFactors.parse(LINE), 7, 1));
        fixture.put(HostLimits.FILE, "certificate_days: 9\n");
        assertThat(read()).isEqualTo(new HostLimits(LimitFactors.DEFAULT, 9, 1));
    }

    @Test
    void theLentTokensDaysAreReadAlone() throws IOException {
        fixture.put(HostLimits.FILE, "lent_token_days: 3650\n");
        assertThat(read().lentTokenDays()).isEqualTo(3650);
        assertThat(read().certificateDays()).isEqualTo(7);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "3651", "7.5", "'7'", "true", "-1", "99999999999"})
    void aCertificateDayCountThatIsNoWholeNumberInRangeIsRefusedByName(String value)
            throws IOException {
        fixture.put(HostLimits.FILE, "certificate_days: " + value + "\n");
        assertThatThrownBy(this::read)
                .isInstanceOf(HostRefusal.class)
                .hasMessageContaining(fixture.path(HostLimits.FILE).toString())
                .hasMessageContaining("certificate_days is no whole number from 1 to 3650");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "3651", "1.0", "'1'", "false"})
    void aLentTokenDayCountThatIsNoWholeNumberInRangeIsRefusedByName(String value)
            throws IOException {
        fixture.put(HostLimits.FILE, "lent_token_days: " + value + "\n");
        assertThatThrownBy(this::read)
                .isInstanceOf(HostRefusal.class)
                .hasMessageContaining(fixture.path(HostLimits.FILE).toString())
                .hasMessageContaining("lent_token_days is no whole number from 1 to 3650");
    }

    @Test
    void aFileThatIsNoYamlIsRefusedByName() throws IOException {
        fixture.put(HostLimits.FILE, "limits: [unclosed\n");
        assertThatThrownBy(this::read)
                .isInstanceOf(HostRefusal.class)
                .hasMessageContaining(fixture.path(HostLimits.FILE) + " is not YAML");
    }

    @Test
    void aFileThatHoldsNoMappingIsRefusedByName() throws IOException {
        fixture.put(HostLimits.FILE, LINE + "\n");
        assertThatThrownBy(this::read)
                .isInstanceOf(HostRefusal.class)
                .hasMessageContaining(fixture.path(HostLimits.FILE) + " holds no mapping");
    }

    @Test
    void anotherKeyIsRefusedByName() throws IOException {
        fixture.put(HostLimits.FILE, "limits: " + LINE + "\ntimeouts: 2.0\n");
        assertThatThrownBy(this::read)
                .isInstanceOf(HostRefusal.class)
                .hasMessageContaining(fixture.path(HostLimits.FILE) + " holds the key timeouts");
    }

    @Test
    void aLineTheWireRefusesIsRefusedWithWhatIsWrong() throws IOException {
        fixture.put(HostLimits.FILE, "limits: services=200.0\n");
        assertThatThrownBy(this::read)
                .isInstanceOf(HostRefusal.class)
                .hasMessageContaining(fixture.path(HostLimits.FILE) + ": limits: ")
                .hasMessageContaining("services 200.0 lies outside 0.1 to 100");
    }

    @Test
    void aFileOthersCanWriteIsRefusedByName() throws IOException {
        final Path file = fixture.put(HostLimits.FILE, "limits: " + LINE + "\n");
        fixture.overrides.put(file, Map.of("mode", 0100666));
        assertThatThrownBy(this::read)
                .isInstanceOf(HostRefusal.class)
                .hasMessageContaining(file + " is not a plain file of root's alone");
    }

    @Test
    void aRefusedFileStopsPoreloHostBeforeItDoesAnything() throws IOException {
        fixture.put(HostLimits.FILE, "limits: polling=0.01\n");
        final HostReadFixture.Answer answer = fixture.run(Map.of(), "org", "status");
        assertThat(answer.code()).as("fail closed and loud").isEqualTo(69);
        assertThat(answer.out()).as("nothing was done or answered").isEmpty();
        assertThat(answer.err())
                .contains(fixture.path(HostLimits.FILE).toString())
                .contains("polling");
    }

    @Test
    void aGoodFileLetsTheVerbRun() throws IOException {
        fixture.put(HostLimits.FILE, "limits: " + LINE + "\n");
        final HostReadFixture.Answer missing =
                new HostReadFixture(directory.resolve("other")).run(Map.of(), "org", "status");
        final HostReadFixture.Answer good = fixture.run(Map.of(), "org", "status");
        assertThat(good.code()).isEqualTo(missing.code());
        assertThat(good.err()).doesNotContain(HostLimits.FILE.substring(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"010", "0x1E", "1_0", "1:30"})
    void aDayCountInAnotherNumberFormThanPlainDecimalIsRefusedByName(String value)
            throws IOException {
        // YAML 1.1 reads these as 8, 30, 10 and 90 days; only a plain decimal is a day count.
        fixture.put(HostLimits.FILE, "certificate_days: " + value + "\n");
        assertThatThrownBy(this::read)
                .isInstanceOf(HostRefusal.class)
                .hasMessageContaining(fixture.path(HostLimits.FILE).toString())
                .hasMessageContaining("certificate_days is no whole number from 1 to 3650");
        fixture.put(HostLimits.FILE, "lent_token_days: " + value + "\n");
        assertThatThrownBy(this::read)
                .isInstanceOf(HostRefusal.class)
                .hasMessageContaining("lent_token_days is no whole number from 1 to 3650");
    }

    @Test
    void aDayCountHoldsFromOneTo3650Days() throws IOException {
        fixture.put(HostLimits.FILE, "certificate_days: 1\nlent_token_days: 3650\n");
        assertThat(read()).isEqualTo(new HostLimits(LimitFactors.DEFAULT, 1, 3650));
        fixture.put(HostLimits.FILE, "certificate_days: 3650\nlent_token_days: 1\n");
        assertThat(read()).isEqualTo(new HostLimits(LimitFactors.DEFAULT, 3650, 1));
        fixture.put(HostLimits.FILE, "certificate_days: 3651\n");
        assertThatThrownBy(this::read)
                .hasMessageContaining("certificate_days is no whole number from 1 to 3650");
    }

    @Test
    void aKeyWrittenTwiceIsRefusedByName() throws IOException {
        fixture.put(HostLimits.FILE, "certificate_days: 30\ncertificate_days: 3\n");
        assertThatThrownBy(this::read)
                .isInstanceOf(HostRefusal.class)
                .satisfies(ex -> assertThat(((HostRefusal) ex).exitCode()).isEqualTo(69))
                .hasMessageContaining(fixture.path(HostLimits.FILE) + " holds a key twice");
    }
}
