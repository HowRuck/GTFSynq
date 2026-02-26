package org.example.sirianalyzer;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.sirianalyzer.parsers.SiriParserService;
import org.example.sirianalyzer.producers.SiriMessageProducer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Component responsible for scheduling and executing periodic tasks related to SIRI data polling and processing.
 */
@Component
@AllArgsConstructor
@Slf4j
public class ScheduledTasks {

    private final SiriLitePollingService siriLiteService;
    private final SiriParserService siriParserService;
    private final SiriMessageProducer siriMessageProducer;

    /**
     * Periodically polls SIRI Lite service, parses the response, and sends the processed message.
     * This method is scheduled to run every 15 seconds.
     */
    @Scheduled(fixedRate = 15, timeUnit = TimeUnit.SECONDS)
    public void pollSiriLite() {
        var response = siriLiteService.poll();

        var message = siriParserService.parseEtMessage(response);

        var success = siriMessageProducer.send(message);

        if (success) {
            log.info("Successfully processed and sent SIRI ET message.");
        } else {
            log.warn("Failed to process or send SIRI ET message.");
        }
    }
}
