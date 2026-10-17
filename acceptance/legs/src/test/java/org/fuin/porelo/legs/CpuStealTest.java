package org.fuin.porelo.legs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests how the leg reads, from the machine's {@code /proc/stat}, how much of its CPU time the host
 * took for others.
 */
class CpuStealTest {

    // The first line of /proc/stat: user nice system idle iowait irq softirq steal guest
    // guest_nice, in ticks.
    private static final String BEFORE = "cpu  1000 0 500 8000 100 0 50 350 0 0";

    private static final String AFTER = "cpu  3000 0 1000 9000 100 0 100 4150 0 0";

    @Test
    void saysWhatShareOfTheTimeBetweenTwoReadingsWasStolen() {

        // A build on a leased machine took five times its usual time with its threads busy
        // (2026-10-17), and the log could not say whether the host had taken the CPUs: the share
        // stolen is what says it.
        final CpuSteal before = CpuSteal.parse(BEFORE);
        final CpuSteal after = CpuSteal.parse(AFTER);

        // Between them: 2000 + 500 + 1000 + 50 + 3800 = 7350 ticks, 3800 of them stolen.
        assertThat(CpuSteal.between(before, after))
                .isEqualTo("3800 of 7350 ticks stolen by the host (51.7 %)");
    }

    @Test
    void aLineItCannotReadIsSaidAsSuch() {
        assertThat(CpuSteal.between(CpuSteal.parse("not a cpu line"), CpuSteal.parse(AFTER)))
                .isEqualTo("the host's steal could not be read");
    }
}
