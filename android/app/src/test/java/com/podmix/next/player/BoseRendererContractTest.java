package com.podmix.next.player;
import org.junit.Test;
import static org.junit.Assert.*;
import java.net.*;
public class BoseRendererContractTest {
    static final String IP="192.168.1.50";
    static final URI ROOT=URI.create("http://"+IP+":8091/XD/device.xml");
    static String fixture() throws Exception {return new String(BoseRendererContractTest.class.getResourceAsStream("/soundtouch10-hardware-description.xml").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);}
    @Test public void realHardwareSt10Description() throws Exception {
        URI[] result=BoseRendererContract.description(fixture(),ROOT,IP);
        assertEquals("http://"+IP+":8091/AVTransport/Control",result[0].toString());
        assertEquals("http://"+IP+":8091/Xml/AVTransport3.xml",result[1].toString());
    }
    @Test public void followsAdvertisedPortAndPathNotHardcoded() throws Exception {
        String xml=fixture().replace("/AVTransport/Control","http://"+IP+":12345/different?transport=1");
        assertEquals(12345,BoseRendererContract.description(xml,ROOT,IP)[0].getPort());
    }
    @Test public void relativeUrlUsesDocumentLocation() throws Exception {
        assertEquals("/XD/transport",BoseRendererContract.description(fixture().replace("/AVTransport/Control","transport"),ROOT,IP)[0].getPath());
    }
    @Test public void urlBaseOverridesDocumentLocation() throws Exception {
        String f=fixture().replace("<specVersion>","<URLBase>http://"+IP+":5678/base/</URLBase><specVersion>").replace("/AVTransport/Control","transport");
        assertEquals("http://"+IP+":5678/base/transport",BoseRendererContract.description(f,ROOT,IP)[0].toString());
    }
    @Test public void rejectsOtherDeviceEndpoint() throws Exception {try{BoseRendererContract.description(fixture().replace("/AVTransport/Control","http://192.168.1.51/control"),ROOT,IP);fail();}catch(java.io.IOException expected){}}
    @Test public void refusesMissingService() throws Exception {try{BoseRendererContract.description(fixture().replace("service:AVTransport:1","service:Missing:1"),ROOT,IP);fail();}catch(java.io.IOException expected){}}
    @Test public void requiresPlaybackCapabilities() throws Exception {try{BoseRendererContract.actions("<scpd><actionList><action><name>Play</name></action></actionList></scpd>");fail();}catch(java.io.IOException expected){}}
    @Test public void permitsOnlyActualHttpMedia() {
        BoseRendererContract.requireHttpMedia("http://192.168.1.24:8099/relay/test?a=1&b=2");
        for(String u:new String[]{"https://example.org/audio.mp3","file:///audio.mp3","http://user:pass@host/audio","http://host/audio#fragment","http://"})
            try{BoseRendererContract.requireHttpMedia(u);fail(u);}catch(IllegalArgumentException expected){}
    }
    @Test public void ssdpLocationCaseInsensitiveAndBoundToSpeaker() throws Exception {
        assertEquals(ROOT.toString(),BoseRendererContract.location("HTTP/1.1 200 OK\r\nLoCaTiOn: "+ROOT+"\r\n",IP));
        try{BoseRendererContract.location("HTTP/1.1 200 OK\r\nLOCATION: http://attacker.invalid/desc.xml\r\n",IP);fail();}catch(java.io.IOException expected){}
    }
    @Test public void rejectsStalePlaybackEvenWhenPlaying() throws Exception {
        String state="<r><CurrentTransportState>PLAYING</CurrentTransportState></r>";
        assertFalse(BoseRendererContract.confirms(state,"<r><CurrentURI>http://host/old</CurrentURI></r>","http://host/new"));
        assertTrue(BoseRendererContract.confirms(state,"<r><CurrentURI>http://host/new?a=1&amp;b=2</CurrentURI></r>","http://host/new?a=1&b=2"));
        assertFalse(BoseRendererContract.confirms("<r><CurrentTransportState>TRANSITIONING</CurrentTransportState></r>","<r><CurrentURI>http://host/new</CurrentURI></r>","http://host/new"));
    }
    @Test public void scpdArgumentOrderIsCheckedBeforeSending() throws Exception {
        String setArgs="<argument><name>InstanceID</name><direction>in</direction></argument><argument><name>CurrentURI</name><direction>in</direction></argument><argument><name>CurrentURIMetaData</name><direction>in</direction></argument>";
        String playArgs="<argument><name>InstanceID</name><direction>in</direction></argument><argument><name>Speed</name><direction>in</direction></argument>";
        String scpd="<scpd><actionList><action><name>SetAVTransportURI</name><argumentList>"+setArgs+"</argumentList></action><action><name>Play</name><argumentList>"+playArgs+"</argumentList></action><action><name>GetTransportInfo</name></action><action><name>GetMediaInfo</name></action></actionList></scpd>";
        assertTrue(BoseRendererContract.actions(scpd).contains("Play"));
        try{BoseRendererContract.actions(scpd.replace("<name>CurrentURI</name>","<name>WrongURI</name>"));fail();}catch(java.io.IOException expected){}
    }
    @Test public void rejectsExternalEntities() throws Exception {try{BoseRendererContract.xml("<!DOCTYPE x [<!ENTITY d SYSTEM 'file:///etc/passwd'>]><x>&d;</x>");fail();}catch(java.io.IOException expected){}}
}
