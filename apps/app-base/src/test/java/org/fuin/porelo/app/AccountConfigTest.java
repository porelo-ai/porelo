package org.fuin.porelo.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.fuin.porelo.wire.LimitFactors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link AccountConfig}: the account's config.yml, every default when it says nothing.
 */
class AccountConfigTest {

    private static AccountConfig read(String yaml) {
        return AccountConfig.read(new StringReader(yaml), "config.yml");
    }

    @Test
    void aMissingFileOrAnEmptyOneIsEveryDefault(@TempDir Path dir) throws IOException {
        assertThat(AccountConfig.read(dir.resolve(AccountConfig.FILE)))
                .as("no file at all")
                .isEqualTo(AccountConfig.DEFAULT);
        Files.writeString(dir.resolve(AccountConfig.FILE), "");
        assertThat(AccountConfig.read(dir.resolve(AccountConfig.FILE)))
                .isEqualTo(AccountConfig.DEFAULT);
        assertThat(AccountConfig.DEFAULT.limits().line())
                .isEqualTo("services=1.0,machines=1.0,polling=1.0,retries=1.0");
    }

    @Test
    void theFourFactorsAreReadAsWrittenAndAKeyLeftOutIsOne() {
        final AccountConfig config =
                read(
                        """
                        limits:
                          services: 2
                          polling: 0.25
                          retries: 1.5
                        """);

        assertThat(config.limits())
                .isEqualTo(LimitFactors.parse("services=2.0,polling=0.25,retries=1.5"));
        assertThat(config.limits().line())
                .isEqualTo("services=2.0,machines=1.0,polling=0.25,retries=1.5");
    }

    @Test
    void theSixNamedSettingsAreReadAndDefaultToTodaysValues() {
        final AccountConfig config =
                read(
                        """
                        task:
                          certificate_days: 14
                        records:
                          kept_days: 90
                        """);

        assertThat(config.certificateDays()).isEqualTo(14);
        assertThat(config.keptDays()).isEqualTo(90);
        assertThat(config.cardDays()).as("left out, today's value").isEqualTo(30);
        assertThat(config.renewNoticeDays()).isEqualTo(7);
        assertThat(config.askedAboutDays()).isEqualTo(7);
    }

    @Test
    void aSectionWrittenWithNothingUnderItIsThatSectionsDefaults() {
        assertThat(read("limits:\nrecords:\n")).isEqualTo(AccountConfig.DEFAULT);
        assertThat(read("limits: {}\n"))
                .as("a mapping that names no factor leaves each at 1.0")
                .isEqualTo(AccountConfig.DEFAULT);
    }

    @Test
    void theHostsTokenLifeIsNoSettingOfThePersonsFile() {
        assertThatThrownBy(() -> read("host:\n  lent_token_days: 2\n"))
                .as("porelo-host keeps lent tokens; its own host.yml sets how long")
                .hasMessageContaining("'host' is no setting");
    }

    @Test
    void anUnknownOrMisspeltKeyIsRefusedByName() {
        assertThatThrownBy(() -> read("limit:\n  services: 2\n"))
                .hasMessageContaining("'limit' is no setting");
        assertThatThrownBy(() -> read("limits:\n  timeouts: 2\n"))
                .hasMessageContaining("'limits.timeouts' is no setting");
        assertThatThrownBy(() -> read("task:\n  certificate_day: 3\n"))
                .hasMessageContaining("'task.certificate_day' is no setting");
    }

    @Test
    void aBadValueIsRefusedNamingItsKey() {
        assertThatThrownBy(() -> read("limits:\n  services: 0.05\n"))
                .as("below the range")
                .hasMessageContaining("limits: services 0.05 lies outside 0.1 to 100");
        assertThatThrownBy(() -> read("limits:\n  machines: 1.234\n"))
                .as("more than two digits after the dot")
                .hasMessageContaining("machines '1.234' is no decimal");
        assertThatThrownBy(() -> read("limits:\n  polling: \"2\"\n"))
                .as("text, not a number")
                .hasMessageContaining("limits.polling is no decimal");
        assertThatThrownBy(() -> read("identity:\n  card_days: 0\n"))
                .hasMessageContaining("identity.card_days is from 1 to 3650 days, not 0");
        assertThatThrownBy(() -> read("records:\n  kept_days: 1.5\n"))
                .hasMessageContaining("records.kept_days is a whole number of days");
        assertThatThrownBy(() -> read("- a list\n")).hasMessageContaining("is no YAML mapping");
    }

    @ParameterizedTest
    @ValueSource(strings = {"010", "0x1E", "1_0", "1:30"})
    void aDayCountInAnotherNumberFormThanPlainDecimalIsRefusedByItsKey(String value) {
        // YAML 1.1 reads these as 8, 30, 10 and 90 days; only a plain decimal is a day count.
        assertThatThrownBy(() -> read("task:\n  certificate_days: " + value + "\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "task.certificate_days is a whole number of days, not " + value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"010", "0x1E", "1_0", "1:30"})
    void aFactorInAnotherNumberFormThanPlainDecimalIsRefusedByItsKey(String value) {
        // YAML 1.1 reads these as 8, 30, 10 and 90, each inside 0.1 to 100.
        assertThatThrownBy(() -> read("limits:\n  services: " + value + "\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "limits.services is no decimal from 0.1 to 100, but " + value);
    }

    @Test
    void aPlainDecimalFactorAndDayCountAreStillNumbers() {
        assertThat(read("limits:\n  services: 2\n  machines: 0.5\n").limits())
                .isEqualTo(LimitFactors.parse("services=2,machines=0.5"));
    }

    @Test
    void aDayCountHoldsFromOneTo3650Days() {
        assertThat(read("task:\n  certificate_days: 1\n").certificateDays()).isEqualTo(1);
        assertThat(read("task:\n  certificate_days: 3650\n").certificateDays()).isEqualTo(3650);
        assertThatThrownBy(() -> read("task:\n  certificate_days: 3651\n"))
                .hasMessageContaining("task.certificate_days is from 1 to 3650 days, not 3651");
    }
}
