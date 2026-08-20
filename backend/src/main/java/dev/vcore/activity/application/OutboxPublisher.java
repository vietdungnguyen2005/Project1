package dev.vcore.activity.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
class OutboxPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxClaimService outboxClaimService;
    private final SseEventHub eventHub;

    OutboxPublisher(OutboxClaimService outboxClaimService, SseEventHub eventHub) {
        this.outboxClaimService = outboxClaimService;
        this.eventHub = eventHub;
    }

    @Scheduled(fixedDelayString = "${vcore.outbox.poll-interval:500}")
    void publishPendingEvents() {
        try {
            outboxClaimService.claimBatch().forEach(eventHub::publish);
        } catch (DataAccessException exception) {
            LOGGER.warn("Outbox polling failed; pending events remain durable for a later retry", exception);
        }
    }
}
