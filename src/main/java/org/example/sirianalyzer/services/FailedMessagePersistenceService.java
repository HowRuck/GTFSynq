package org.example.sirianalyzer.services;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.dataformat.xml.XmlMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
public class FailedMessagePersistenceService {

    private final Path failedMessagesDir;
    private final XmlMapper xmlMapper;
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    public FailedMessagePersistenceService(
            @Value("${siri.failed-messages.dir:./failed-messages}") String failedMessagesDirPath
    ) {
        this.failedMessagesDir = Paths.get(failedMessagesDirPath);
        this.xmlMapper = new XmlMapper();

        try {
            Files.createDirectories(failedMessagesDir);
            log.info("Failed messages directory ensured at: {}", failedMessagesDir.toAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to create failed messages directory: {}", failedMessagesDirPath, e);
            throw new RuntimeException("Cannot create failed messages directory", e);
        }
    }

    /**
     * Writes a failed message to disk for debugging and analysis
     *
     * @param payload The message payload that failed to parse
     * @param error The error message describing the failure
     */
    public void writeFailedMessage(String payload, String error) {
        if (payload == null) {
            log.warn("Attempted to write null payload as failed message");
            return;
        }

        try {
            String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
            String filename = String.format("failed-message_%s.xml", timestamp);
            Path filePath = failedMessagesDir.resolve(filename);

            // Prettify the XML payload
            String prettifiedPayload = prettifyXml(payload);

            // Prepare the content with error information header
            String content = """
                    <!-- Failed Message Report -->
                    <!-- Timestamp: %s -->
                    <!-- Error: %s -->
                    <!-- End Error Report -->
                    
                    %s""".formatted(
                    LocalDateTime.now(),
                    error != null ? error : "Unknown error",
                    prettifiedPayload
            );

            Files.writeString(filePath, content);

            log.info("Failed message written to disk: {}", filePath.toAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to write failed message to disk", e);
        }
    }

    /**
     * Prettifies XML content for better readability
     *
     * @param xmlPayload The XML string to prettify
     * @return The prettified XML, or the original if prettification fails
     */
    private String prettifyXml(String xmlPayload) {
        try {
            Object jsonObj = xmlMapper.readValue(xmlPayload, Object.class);
            return xmlMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(jsonObj);
        } catch (Exception e) {
            log.debug("Could not prettify XML payload, using as-is. Error: {}", e.getMessage());
            return xmlPayload;
        }
    }
}

