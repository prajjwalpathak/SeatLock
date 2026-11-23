package com.seatlock.assistant.ai;

import java.sql.Timestamp;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Daily: delete {@code ai_requests} rows older than {@code seatlock.ai.audit-retention}. Idempotent, safe on 2 instances. */
@Component
public class AiRequestCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(AiRequestCleanupJob.class);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final AiProperties props;

    public AiRequestCleanupJob(JdbcClient jdbc, Clock clock, AiProperties props) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.props = props;
    }

    @Scheduled(initialDelayString = "PT15M", fixedDelayString = "P1D")
    public int deleteOld() {
        int deleted = jdbc.sql("DELETE FROM ai_requests WHERE created_at < :cutoff")
                .param("cutoff", Timestamp.from(clock.instant().minus(props.auditRetention())))
                .update();
        if (deleted > 0) {
            log.info("Deleted {} AI request rows older than {}", deleted, props.auditRetention());
        }
        return deleted;
    }
}
