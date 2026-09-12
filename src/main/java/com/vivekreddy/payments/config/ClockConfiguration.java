package com.vivekreddy.payments.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Makes the clock a dependency.
 *
 * <p>So that a test can fix "now" and assert on expiry boundaries directly,
 * instead of the alternative, which is a test that passes until the month
 * changes.
 */
@Configuration
public class ClockConfiguration {

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
