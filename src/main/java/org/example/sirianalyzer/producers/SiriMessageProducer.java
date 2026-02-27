package org.example.sirianalyzer.producers;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.example.sirianalyzer.models.EstimatedVehicleJourney;
import org.example.sirianalyzer.models.SiriEtMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Getter
@Slf4j
@Service
/**
 * Class for producing SIRI messages to Kafka
 */
public class SiriMessageProducer {

    private final String topic;
    private final JsonMapper jsonMapper;
    private final KafkaTemplate<String, EstimatedVehicleJourney> kafkaTemplate;

    /**
     * Constructor for SiriMessageProducer
     *
     * @param topic The topic to send messages to
     * @param kafkaTemplate Kafka template for sending messages
     */
    public SiriMessageProducer(
        @Value("${siri.kafka.topic:siri-et-messages}") String topic,
        KafkaTemplate<String, EstimatedVehicleJourney> kafkaTemplate
    ) {
        this.topic = topic;
        this.jsonMapper = new JsonMapper();
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Send message to Kafka topic
     *
     * @param message The message to send
     * @return true if message was sent successfully, false otherwise
     */
    public boolean send(SiriEtMessage message) {
        if (message == null) {
            log.warn("Passed empty message, returning null");
            return false;
        }

        var includedConnections = message
            .getServiceDelivery()
            .getEstimatedTimetableDelivery()
            .getEstimatedJourneyVersionFrame()
            .getEstimatedVehicleJourneys();

        if (includedConnections == null || includedConnections.isEmpty()) {
            log.warn(
                "Passed message does not contain any conections, returning null"
            );
            return false;
        }

        try {
            log.debug(
                "Sending messages to topic {}: {}",
                topic,
                includedConnections.size()
            );

            // Send each connection to Kafka
            includedConnections.forEach(connection -> {
                kafkaTemplate
                    .send(topic, connection)
                    .thenAccept(result -> {
                        log.debug(
                            "Successfully sent message to Kafka topic {}",
                            topic
                        );
                    })
                    .exceptionally(ex -> {
                        log.error(
                            "Failed to send message to topic {}",
                            topic,
                            ex
                        );

                        return null; // Return null to indicate failure
                    });
            });

            log.debug("Successfully sent message to Kafka topic {}", topic);
            return true;
        } catch (Exception e) {
            log.error("Failed to send message to topic {}", topic, e);
            return false;
        }
    }
}
