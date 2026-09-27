package com.saadat.common.util;

import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.Clock;

/** Age in whole years from a date of birth; null when unknown. */
public final class Ages {

    private Ages() {
    }

    public static Integer of(LocalDate birthDate) {
        return of(birthDate, Clock.systemUTC());
    }

    public static Integer of(LocalDate birthDate, Clock clock) {
        if (birthDate == null) {
            return null;
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (birthDate.isAfter(today)) {
            return null;
        }
        return Period.between(birthDate, today).getYears();
    }
}
