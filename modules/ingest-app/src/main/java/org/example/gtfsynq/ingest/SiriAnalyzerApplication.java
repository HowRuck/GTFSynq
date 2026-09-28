package org.example.gtfsynq.ingest;

import org.example.gtfsynq.ingest.config.ProtobufRuntimeHints;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "org.example.gtfsynq")
@EnableScheduling
@EnableAsync
@ConfigurationPropertiesScan
@ImportRuntimeHints(ProtobufRuntimeHints.class)
public class SiriAnalyzerApplication {

    static void main(String[] args) {
        SpringApplication.run(SiriAnalyzerApplication.class, args);
    }
}
