package com.medichannel.config;

import java.time.LocalTime;

import org.junit.jupiter.api.Test;

import com.medichannel.enums.ConsultationType;

import static org.assertj.core.api.Assertions.assertThat;

class ClinicScheduleTest {

    @Test
    void bookingDeadlinesUseTheTypeSpecificClosingWindows() {
        assertThat(ClinicSchedule.bookingCloseMinutes(
                ConsultationType.PHYSICAL
        )).isEqualTo(60);
        assertThat(ClinicSchedule.bookingCloseMinutes(
                ConsultationType.ONLINE
        )).isEqualTo(30);

        LocalTime now = LocalTime.of(9, 0);
        assertThat(ClinicSchedule.bookingCutoff(
                now,
                ConsultationType.PHYSICAL
        )).isEqualTo(LocalTime.of(10, 0));
        assertThat(ClinicSchedule.bookingCutoff(
                now,
                ConsultationType.ONLINE
        )).isEqualTo(LocalTime.of(9, 30));
    }

    @Test
    void aRolledOverCutoffClosesAllRemainingSessionsForToday() {
        assertThat(ClinicSchedule.bookingCutoff(
                LocalTime.of(23, 30),
                ConsultationType.PHYSICAL
        )).isEqualTo(LocalTime.MAX);
    }
}
