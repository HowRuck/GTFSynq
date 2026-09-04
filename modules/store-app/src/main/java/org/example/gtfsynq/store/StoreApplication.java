package org.example.gtfsynq.store;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "org.example.gtfsynq")
@EnableScheduling
@EnableKafka
@ConfigurationPropertiesScan
public class StoreApplication {

    static void main(String[] args) {
        SpringApplication.run(StoreApplication.class, args);
    }
}
