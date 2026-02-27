package org.example.sirianalyzer.models;

import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import tools.jackson.dataformat.xml.annotation.JacksonXmlText;

/**
 * Represents a journey note with language and text content.
 * Used to provide additional information about a vehicle journey.
 *
 * Mapped so that nested XML elements like:
 *
 * <JourneyNote>
 *   <lang>NO</lang>
 *   <text>Rute A</text>
 * </JourneyNote>
 *
 * are bound to the corresponding fields.
 */
public record JourneyNote(
    /** Language code for the note text (e.g., "NO", "EN") */
    @JacksonXmlProperty(localName = "lang") String lang,

    /** The actual note text content */
    @JacksonXmlProperty(localName = "Text") String text
) {}
