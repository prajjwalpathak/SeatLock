package com.seatlock.catalog.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** W8·D4: the startup run happens only where scheduled jobs and the demo upkeep are both on. */
class DemoShowTopUpJobTest {

    final TransactionTemplate tx = mock(TransactionTemplate.class);

    DemoShowTopUpJob job(boolean enabled, boolean jobsEnabled) {
        return new DemoShowTopUpJob(mock(JdbcClient.class), tx, mock(ApplicationEventPublisher.class),
                Clock.systemUTC(), enabled, jobsEnabled, 3);
    }

    @Test
    void runsAtStartupWhenJobsAndUpkeepAreOn() {
        job(true, true).onStartup();
        verify(tx).execute(any());
    }

    @Test
    void skipsWhenJobsAreOffAsInTests() {
        job(true, false).onStartup();
        verify(tx, never()).execute(any());
    }

    @Test
    void skipsWhenTheUpkeepKillSwitchIsOff() {
        job(false, true).onStartup();
        verify(tx, never()).execute(any());
    }
}
