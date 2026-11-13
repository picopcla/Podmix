package com.podmix.next.player;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.*;

public class BoseAvTransportTest {
    private static Element action(String wire) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document d = f.newDocumentBuilder().parse(new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)));
        assertEquals("http://schemas.xmlsoap.org/soap/envelope/", d.getDocumentElement().getNamespaceURI());
        assertEquals("http://schemas.xmlsoap.org/soap/encoding/", d.getDocumentElement().getAttributeNS("http://schemas.xmlsoap.org/soap/envelope/", "encodingStyle"));
        NodeList bodies = d.getElementsByTagNameNS("http://schemas.xmlsoap.org/soap/envelope/", "Body");
        assertEquals(1, bodies.getLength());
        Element action = (Element)bodies.item(0).getFirstChild();
        assertEquals("SetAVTransportURI", action.getLocalName());
        assertEquals("urn:schemas-upnp-org:service:AVTransport:1", action.getNamespaceURI());
        return action;
    }
    @Test public void setUriWireHasAllArgumentsInScpdOrder() throws Exception {
        Element a = action(BoseAvTransport.setUriEnvelope("http://192.168.1.24:8099/clip", ""));
        String[] expected = {"InstanceID", "CurrentURI", "CurrentURIMetaData"};
        assertEquals(3, a.getChildNodes().getLength());
        for (int i=0;i<3;i++) {
            Node n = a.getChildNodes().item(i);
            assertEquals(expected[i], n.getNodeName());
            assertNull(n.getNamespaceURI());
        }
        assertEquals("0", a.getChildNodes().item(0).getTextContent());
        assertEquals("", a.getChildNodes().item(2).getTextContent());
    }
    @Test public void uriAndDidlRoundTripExactlyThroughUtf8Wire() throws Exception {
        String uri = "http://192.168.1.24:8099/clip?a=1&title=Été";
        String metadata = "<DIDL-Lite><title>Été &amp; &lt;titre&gt; \"test\"</title></DIDL-Lite>";
        Element a = action(BoseAvTransport.setUriEnvelope(uri, metadata));
        assertEquals(uri, a.getChildNodes().item(1).getTextContent());
        assertEquals(metadata, a.getChildNodes().item(2).getTextContent());
    }
    @Test public void metadataIsTextNotAnUnescapedNestedElement() throws Exception {
        Element a = action(BoseAvTransport.setUriEnvelope("http://host/clip", "<DIDL-Lite/>"));
        Node metadata = a.getChildNodes().item(2);
        assertEquals(1, metadata.getChildNodes().getLength());
        assertEquals(Node.TEXT_NODE, metadata.getFirstChild().getNodeType());
    }
}
