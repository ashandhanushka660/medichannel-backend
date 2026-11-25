package com.medichannel.config;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Deterministic clock for integration tests that allocate appointment
 * numbers.
 *
 * The 11:00–13:00 break guard and the booking cutoffs read the Asia/Colombo
 * wall clock. Starting at 09:00 keeps those tests independent of the time of
 * day the suite happens to run, while still exercising the real
 * published-window and deadline rules.
 *
 * Each read advances by one millisecond. Real time is never frozen, so two
 * events in the same test still receive ordered timestamps and message
 * histories remain deterministic.
 */
@TestConfiguration
public class WorkHoursClockTestConfiguration {

    @Bean
    @Primary
    public Clock workHoursTestClock() {
        ZoneId zone = TimeConfiguration.APPLICATION_ZONE;

        return new TickingClock(
                LocalDate.now(zone)
                        .atTime(9, 0)
                        .atZone(zone)
                        .toInstant(),
                zone
        );
    }

    private static final class TickingClock extends Clock {

        private final Instant start;
        private final ZoneId zone;
        private final AtomicLong ticks;

        private TickingClock(Instant start, ZoneId zone) {
            this(start, zone, new AtomicLong());
        }

        private TickingClock(
                Instant start,
                ZoneId zone,
                AtomicLong ticks
        ) {
            this.start = start;
            this.zone = zone;
            this.ticks = ticks;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId newZone) {
            return new TickingClock(start, newZone, ticks);
        }

        @Override
        public Instant instant() {
            return start.plusMillis(ticks.getAndIncrement());
        }
    }
}
