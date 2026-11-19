package com.seatlock.payment.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Every 30 s: send due refunds. Safe on two instances (SKIP LOCKED + lease); off in tests (they call it directly). */
@Component
public class RefundWorker {

    private static final Logger log = LoggerFactory.getLogger(RefundWorker.class);

    private final RefundProcessor processor;

    public RefundWorker(RefundProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(initialDelayString = "${seatlock.refunds.initial-delay:PT20S}",
               fixedDelayString = "${seatlock.refunds.interval:PT30S}")
    public void run() {
        try {
            int submitted = processor.processDue();
            if (submitted > 0) {
                log.info("Submitted {} refunds", submitted);
            }
        } catch (RuntimeException e) {
            log.warn("Refund run failed; will retry next run ({})", e.toString()); // keep the schedule alive
        }
    }
}
