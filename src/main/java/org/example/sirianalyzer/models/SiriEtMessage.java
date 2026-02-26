package org.example.sirianalyzer.models;

import com.fasterxml.jackson.annotation.JsonRootName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;

/**
 * Root element representing a SIRI (Service Interface for Real-time Information) message.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonRootName("Siri")
public class SiriEtMessage {

    /** SIRI protocol version (e.g., "2.0") - represented as an attribute on the root element */
    @JacksonXmlProperty(isAttribute = true, localName = "version")
    private String version;

    /** Container for the actual ServiceDelivery element in the SIRI XML */
    private ServiceDelivery serviceDelivery;
}
