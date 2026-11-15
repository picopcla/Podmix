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
    static String soap(String action,String body) {
        return "<s:Envelope xmlns:s='http://schemas.xmlsoap.org/soap/envelope/'><s:Body><u:"+action+"Response xmlns:u='"+BoseRendererContract.AV+"'>"+body+"</u:"+action+"Response></s:Body></s:Envelope>";
    }
    @Test public void rejectsStalePlaybackEvenWhenPlaying() throws Exception {
        String state=soap("GetTransportInfo","<CurrentTransportState>PLAYING</CurrentTransportState>");
        assertFalse(BoseRendererContract.confirms(state,soap("GetMediaInfo","<CurrentURI>http://host/old</CurrentURI>"),"http://host/new"));
        assertTrue(BoseRendererContract.confirms(state,soap("GetMediaInfo","<CurrentURI>http://host/new?a=1&amp;b=2</CurrentURI>"),"http://host/new?a=1&b=2"));
        assertFalse(BoseRendererContract.confirms(soap("GetTransportInfo","<CurrentTransportState>TRANSITIONING</CurrentTransportState>"),soap("GetMediaInfo","<CurrentURI>http://host/new</CurrentURI>"),"http://host/new"));
    }
    // SoundTouch 30 fw 27.0.6 (mesure reelle, 15/11/2026) : CurrentURI vide pendant la lecture,
    // l'URL lue est rapportee par /now_playing (port 8090).
    static String nowPlaying(String source,String itemSource,String location,String status) {
        return "<nowPlaying deviceID=\"000000000000\" source=\""+source+"\"><ContentItem source=\""+itemSource+"\" location=\""+location+"\" sourceAccount=\"UPnPUserName\" isPresetable=\"true\" /><playStatus>"+status+"</playStatus></nowPlaying>";
    }
    @Test public void confirmsViaNowPlayingWhenCurrentUriIsEmpty() throws Exception {
        String url="http://podmix.mb4.fr/podmix-cast/v1/cast/0123456789abcdef0123456789abcdef";
        String state=soap("GetTransportInfo","<CurrentTransportState>PLAYING</CurrentTransportState>");
        String emptyUri=soap("GetMediaInfo","<CurrentURI></CurrentURI>");
        assertTrue(BoseRendererContract.confirms(state,emptyUri,nowPlaying("UPNP","UPNP",url,"PLAY_STATE"),url));
    }
    @Test public void nowPlayingFallbackKeepsStrictIdentityAndState() throws Exception {
        String url="http://podmix.mb4.fr/podmix-cast/v1/cast/0123456789abcdef0123456789abcdef";
        String state=soap("GetTransportInfo","<CurrentTransportState>PLAYING</CurrentTransportState>");
        String emptyUri=soap("GetMediaInfo","<CurrentURI></CurrentURI>");
        // autre flux que celui demande (le symptome « autre radio que choisie »)
        assertFalse(BoseRendererContract.confirms(state,emptyUri,nowPlaying("UPNP","UPNP","http://direct.franceinfo.fr/live/franceinfo-midfi.mp3","PLAY_STATE"),url));
        // pas en lecture, mauvaise source, pas de now_playing
        assertFalse(BoseRendererContract.confirms(state,emptyUri,nowPlaying("UPNP","UPNP",url,"BUFFERING_STATE"),url));
        assertFalse(BoseRendererContract.confirms(state,emptyUri,nowPlaying("STANDBY","STANDBY",url,"PLAY_STATE"),url));
        assertFalse(BoseRendererContract.confirms(state,emptyUri,"",url));
        assertFalse(BoseRendererContract.confirms(state,emptyUri,null,url));
        // etat AVTransport non PLAYING
        assertFalse(BoseRendererContract.confirms(soap("GetTransportInfo","<CurrentTransportState>TRANSITIONING</CurrentTransportState>"),emptyUri,nowPlaying("UPNP","UPNP",url,"PLAY_STATE"),url));
        // URL a peine differente (pas de comparaison approximative)
        assertFalse(BoseRendererContract.confirms(state,emptyUri,nowPlaying("UPNP","UPNP",url+"x","PLAY_STATE"),url));
    }
    @Test public void staleNonEmptyCurrentUriIsNeverRescuedByNowPlaying() throws Exception {
        String url="http://podmix.mb4.fr/podmix-cast/v1/cast/0123456789abcdef0123456789abcdef";
        String state=soap("GetTransportInfo","<CurrentTransportState>PLAYING</CurrentTransportState>");
        assertFalse(BoseRendererContract.confirms(state,soap("GetMediaInfo","<CurrentURI>http://host/old</CurrentURI>"),nowPlaying("UPNP","UPNP",url,"PLAY_STATE"),url));
        assertTrue(BoseRendererContract.confirms(state,soap("GetMediaInfo","<CurrentURI>"+url+"</CurrentURI>"),"",url));
    }
    @Test public void malformedNowPlayingIsRejectedNotTrusted() throws Exception {
        String url="http://podmix.mb4.fr/podmix-cast/v1/cast/0123456789abcdef0123456789abcdef";
        String state=soap("GetTransportInfo","<CurrentTransportState>PLAYING</CurrentTransportState>");
        String emptyUri=soap("GetMediaInfo","<CurrentURI></CurrentURI>");
        String two="<nowPlaying source=\"UPNP\"><ContentItem source=\"UPNP\" location=\""+url+"\"/><ContentItem source=\"UPNP\" location=\"http://x/y\"/><playStatus>PLAY_STATE</playStatus></nowPlaying>";
        assertFalse(BoseRendererContract.confirms(state,emptyUri,two,url));
        assertFalse(BoseRendererContract.confirms(state,emptyUri,"<other source=\"UPNP\"/>",url));
    }
    @Test public void doesNotAcceptPlainXmlOrWrongSoapAction() throws Exception {
        for(String invalid:new String[]{"<r><CurrentTransportState>PLAYING</CurrentTransportState></r>",soap("WrongAction","<CurrentTransportState>PLAYING</CurrentTransportState>")})
            try{BoseRendererContract.confirms(invalid,soap("GetMediaInfo","<CurrentURI>http://host/new</CurrentURI>"),"http://host/new");fail();}catch(java.io.IOException expected){}
    }
    @Test public void httpFramingAndUtf8AreDecoded() throws Exception {
        String body="Été & test";byte[] data=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
        out.write(("HTTP/1.1 200 OK\r\nContent-Length: "+data.length+"\r\n\r\n").getBytes());out.write(data);
        assertEquals(body,BoseRendererContract.readHttpResponse(new java.io.ByteArrayInputStream(out.toByteArray())));
        out.reset();out.write(("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"+Integer.toHexString(data.length)+";extra=x\r\n").getBytes());out.write(data);out.write("\r\n0\r\n\r\n".getBytes());
        assertEquals(body,BoseRendererContract.readHttpResponse(new java.io.ByteArrayInputStream(out.toByteArray())));
    }
    @Test public void realFaultDetailsAreNotReplacedByGenericFailure() throws Exception {
        String wire="HTTP/1.1 500 Internal Server Error\r\n\r\n<errorCode>402</errorCode><errorDescription>URI must start with http://</errorDescription>";
        try{BoseRendererContract.readHttpResponse(new java.io.ByteArrayInputStream(wire.getBytes()));fail();}catch(java.io.IOException e){assertTrue(e.getMessage().contains("402"));assertTrue(e.getMessage().contains("URI must start with http://"));}
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
