package com.podmix.next.player;

/** Wire format for the renderer control service, independent of Android. */
final class BoseAvTransport {
    private BoseAvTransport() {}

    static String setUriEnvelope(String url, String didlMetadata) {
        // UPnP AVTransport:1 specifies this argument order. Some renderers reject
        // metadata before URI with Invalid Args even when every value is present.
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
            "<InstanceID>0</InstanceID><CurrentURI>" + escape(url) + "</CurrentURI>" +
            "<CurrentURIMetaData>" + escape(didlMetadata) + "</CurrentURIMetaData>" +
            "</u:SetAVTransportURI></s:Body></s:Envelope>";
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;")
            .replace("<", "&lt;").replace(">", "&gt;");
    }
}
