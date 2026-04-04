package com.peekcart.global.outbox;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutboxPollingScheduler {

    private final OutboxPollingService outboxPollingService;

    @Scheduled(fixedDelay = 5000)
    @SchedulerLock(name = "outboxPollingJob", lockAtMostFor = "PT5M", lockAtLeastFor = "PT4S")
    public void pollAndPublish() {
        outboxPollingService.pollAndPublish();
    }
}
