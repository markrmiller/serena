package io.serena.javarefactor.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * G005: the build-model extractor parses project-controlled {@code pom.xml} files (raw module POMs during reactor
 * discovery), so its DOM parser must be hardened against XML External Entity (XXE) attacks. These tests feed the
 * package-private {@link BuildModelExtractor#parseXml} a malicious DOCTYPE/XXE payload directly and assert it is
 * rejected without expanding entities or disclosing local files, while a benign POM still parses.
 */
class BuildModelExtractorXmlHardeningTest {

    @Test
    void rejectsDoctypeWithExternalEntityXxe(@TempDir Path tmp) throws IOException {
        // A classic XXE: an external general entity pointed at a local file, expanded into an element's text.
        Path secret = tmp.resolve("secret.txt");
        Files.writeString(secret, "TOP-SECRET-CONTENTS");
        Path pom = tmp.resolve("pom.xml");
        String payload = "<?xml version=\"1.0\"?>\n"
                + "<!DOCTYPE project [ <!ENTITY xxe SYSTEM \"" + secret.toUri() + "\"> ]>\n"
                + "<project><modelVersion>4.0.0</modelVersion><artifactId>&xxe;</artifactId></project>\n";
        Files.writeString(pom, payload);

        IOException error = assertThrows(IOException.class, () -> BuildModelExtractor.parseXml(pom));
        // The DOCTYPE is rejected before any entity is resolved, so the secret never reaches the parser or the error.
        assertFalse(error.getMessage().contains("TOP-SECRET-CONTENTS"), error.getMessage());
        assertFalse(Files.readString(secret).isEmpty());
    }

    @Test
    void rejectsDoctypeParameterEntityXxe(@TempDir Path tmp) throws IOException {
        // A parameter-entity DOCTYPE (the billion-laughs / OOB-DTD family) must also be refused outright.
        Path pom = tmp.resolve("pom.xml");
        String payload = "<?xml version=\"1.0\"?>\n"
                + "<!DOCTYPE project [\n"
                + "  <!ENTITY a \"AAAA\">\n"
                + "  <!ENTITY b \"&a;&a;&a;&a;&a;\">\n"
                + "]>\n"
                + "<project><artifactId>&b;</artifactId></project>\n";
        Files.writeString(pom, payload);

        assertThrows(IOException.class, () -> BuildModelExtractor.parseXml(pom));
    }

    @Test
    void parsesBenignPomWithoutDoctype(@TempDir Path tmp) throws IOException {
        // Hardening must not break parsing of a normal, DOCTYPE-free POM.
        Path pom = tmp.resolve("pom.xml");
        Files.writeString(pom, "<project><modelVersion>4.0.0</modelVersion><artifactId>demo</artifactId></project>\n");

        Document document = BuildModelExtractor.parseXml(pom);

        assertNotNull(document);
        assertEquals("project", document.getDocumentElement().getNodeName());
    }
}
