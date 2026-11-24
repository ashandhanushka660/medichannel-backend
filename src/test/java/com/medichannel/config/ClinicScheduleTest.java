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

    @Test
    void onlyTheTwoPublishedWindowsAreAllowed() {
        assertThat(ClinicSchedule.isAllowedWindow(
                LocalTime.of(7, 0),
                LocalTime.of(9, 30)
        )).isTrue();
        assertThat(ClinicSchedule.isAllowedWindow(
                LocalTime.of(12, 0),
                LocalTime.of(14, 30)
        )).isTrue();
        assertThat(ClinicSchedule.isAllowedWindow(
                LocalTime.of(7, 0),
                LocalTime.of(16, 0)
        )).isFalse();
        assertThat(ClinicSchedule.isAllowedWindow(
                LocalTime.of(9, 30),
                LocalTime.of(12, 0)
        )).isFalse();
    }

    @Test
    void periodFiltersEndAtThePublishedSessionEnd() {
        assertThat(ClinicSchedule.periodEnd(null))
                .isEqualTo(LocalTime.of(23, 59, 59));
        assertThat(ClinicSchedule.periodEnd(LocalTime.of(7, 0)))
                .isEqualTo(LocalTime.of(9, 30));
        assertThat(ClinicSchedule.periodEnd(LocalTime.of(12, 0)))
                .isEqualTo(LocalTime.of(14, 30));
    }

    @Test
    void dailyCapacityIsTwoSessionsOfThirtyPatients() {
        assertThat(ClinicSchedule.MAX_APPOINTMENTS_PER_SESSION).isEqualTo(30);
        assertThat(ClinicSchedule.MAX_APPOINTMENTS_PER_DAY).isEqualTo(60);
        assertThat(ClinicSchedule.MINUTES_PER_PATIENT).isEqualTo(5);
        assertThat(ClinicSchedule.MAX_SLOT_MINUTES).isEqualTo(5);
    }
}
