package org.example.sirianalyzer.parsers;

import lombok.extern.slf4j.Slf4j;
import org.example.sirianalyzer.models.SiriEtMessage;
import org.example.sirianalyzer.services.FailedMessagePersistenceService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.dataformat.xml.XmlMapper;

@Slf4j
@Service
/**
 * Service for parsing SIRI ET messages from XML to Java objects.
 */
public class SiriParserService {

    private final XmlMapper mapper;
    private final FailedMessagePersistenceService failedMessageService;

    public SiriParserService(
        FailedMessagePersistenceService failedMessageService
    ) {
        this.mapper = XmlMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.UPPER_CAMEL_CASE)
            //.enable(SerializationFeature.INDENT_OUTPUT)
            //.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            //.disable(DeserializationFeature.ACCEPT_EMPTY_ARRAY_AS_NULL_OBJECT)
            //.disable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
            .build();
        this.failedMessageService = failedMessageService;

        log.info(
            "SiriParserService initialized with Upper Camel Case strategy."
        );
    }

    /**
     * Parses a SIRI ET message from a given payload to a SiriEtMessage object
     *
     * @param payload the payload to parse
     * @return the parsed SiriEtMessage
     */
    public SiriEtMessage parseEtMessage(String payload) {
        if (payload == null || payload.isBlank()) {
            log.warn("Received an empty or null payload; skipping parsing");
            return null;
        }

        log.debug(
            "Attempting to parse SIRI ET message. Payload length: {} characters",
            payload.length()
        );

        long startTime = System.currentTimeMillis();

        try {
            var message = mapper.readValue(payload, SiriEtMessage.class);

            long duration = System.currentTimeMillis() - startTime;

            // Count the number of connections in the message
            int connectionCount = (message.getServiceDelivery() != null &&
                message.getServiceDelivery().getEstimatedTimetableDelivery() !=
                null)
                ? message
                      .getServiceDelivery()
                      .getEstimatedTimetableDelivery()
                      .getEstimatedJourneyVersionFrame()
                      .getEstimatedVehicleJourneys()
                      .size()
                : 0;

            log.info(
                "Parsed SIRI ET message with {} connections in {} ms",
                connectionCount,
                duration
            );
            return message;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error(
                "Failed to parse SIRI ET message after {} ms. Error: {}",
                duration,
                e.getMessage()
            );

            String snippet =
                payload.length() > 200
                    ? payload.substring(0, 200) + "..."
                    : payload;
            log.debug("Problematic payload snippet: {}", snippet);

            // Persist the failed message to disk for debugging
            failedMessageService.writeFailedMessage(payload, e.getMessage());

            return null;
        }
    }
}
