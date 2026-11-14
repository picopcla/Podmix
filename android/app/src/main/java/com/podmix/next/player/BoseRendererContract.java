package com.podmix.next.player;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;

/** Resolves the renderer's own advertised contract, never a guessed control URL. */
final class BoseRendererContract {
    static final String AV = "urn:schemas-upnp-org:service:AVTransport:1";
    final URI control;
    final URI scpd;
    final Set<String> actions;
    BoseRendererContract(URI control, URI scpd, Set<String> actions) {
        this.control = control; this.scpd = scpd; this.actions = actions;
    }
    static void requireHttpMedia(String value) {
        try {
            URI uri = new URI(value);
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null ||
                uri.getUserInfo() != null || uri.getFragment() != null) throw new Exception();
        } catch (Exception e) {
            throw new IllegalArgumentException("La SoundTouch exige un flux HTTP direct. Le relais HTTPS ne peut pas être utilisé par ce mode de lecture.");
        }
    }
    static Document xml(String value) throws Exception {
        if (value.length() > 1048576 || value.toUpperCase(Locale.ROOT).contains("<!DOCTYPE") ||
            value.toUpperCase(Locale.ROOT).contains("<!ENTITY")) throw new IOException("Description XML refusée");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true); factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));
    }
    static String text(Element e, String name) {
        NodeList nodes = e.getElementsByTagNameNS("*", name);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }
    static URI sameSpeaker(URI base, String relative, String ip) throws Exception {
        URI uri = base.resolve(relative);
        if (!"http".equalsIgnoreCase(uri.getScheme()) || !ip.equals(uri.getHost()) ||
            uri.getUserInfo() != null || uri.getFragment() != null || uri.getPort() == 0)
            throw new IOException("Adresse UPnP étrangère à l'enceinte sélectionnée");
        return uri;
    }
    static URI[] description(String value, URI location, String ip) throws Exception {
        Document doc = xml(value);
        String urlBase = text(doc.getDocumentElement(), "URLBase");
        URI base = urlBase.isEmpty() ? location : sameSpeaker(location, urlBase, ip);
        NodeList services = doc.getElementsByTagNameNS("*", "service");
        for (int i = 0; i < services.getLength(); i++) {
            Element service = (Element) services.item(i);
            if (!AV.equals(text(service, "serviceType"))) continue;
            String control = text(service, "controlURL"), scpd = text(service, "SCPDURL");
            if (control.isEmpty() || scpd.isEmpty()) throw new IOException("Description AVTransport incomplète");
            return new URI[]{sameSpeaker(base, control, ip), sameSpeaker(base, scpd, ip)};
        }
        throw new IOException("Cette enceinte n'annonce pas de service AVTransport:1");
    }
    static Set<String> actions(String value) throws Exception {
        Document doc = xml(value); Set<String> actions = new HashSet<>();
        NodeList nodes = doc.getElementsByTagNameNS("*", "action");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element action = (Element)nodes.item(i); String name = text(action, "name"); actions.add(name);
            if (!"SetAVTransportURI".equals(name) && !"Play".equals(name)) continue;
            List<String> inputs = new ArrayList<>(); NodeList arguments = action.getElementsByTagNameNS("*", "argument");
            for (int j = 0; j < arguments.getLength(); j++) {
                Element argument = (Element)arguments.item(j);
                if ("in".equals(text(argument, "direction"))) inputs.add(text(argument, "name"));
            }
            List<String> expected = "Play".equals(name) ? Arrays.asList("InstanceID", "Speed") :
                Arrays.asList("InstanceID", "CurrentURI", "CurrentURIMetaData");
            if (!expected.equals(inputs)) throw new IOException("Signature AVTransport non prise en charge : " + name);
        }
        if (!actions.containsAll(Arrays.asList("SetAVTransportURI", "Play", "GetTransportInfo", "GetMediaInfo")))
            throw new IOException("Le service annoncé ne permet pas de transférer et confirmer le média");
        return actions;
    }
    static boolean confirms(String transportInfo, String mediaInfo, String expectedUri) throws Exception {
        String state = text(xml(transportInfo).getDocumentElement(), "CurrentTransportState");
        String uri = text(xml(mediaInfo).getDocumentElement(), "CurrentURI");
        return "PLAYING".equals(state) && expectedUri.equals(uri);
    }
    static String location(String response, String ip) throws Exception {
        if (!response.startsWith("HTTP/1.1 200") && !response.startsWith("HTTP/1.0 200")) return "";
        for (String line : response.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("location")) {
                String value = line.substring(colon + 1).trim();
                sameSpeaker(new URI(value), "", ip); return value;
            }
        }
        return "";
    }
    static String get(URI uri) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(2500); connection.setReadTimeout(2500);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Description UPnP HTTP " + connection.getResponseCode());
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int n;
                while ((n = input.read(buffer)) != -1) {
                    if (out.size() + n > 1048576) throw new IOException("Description UPnP trop volumineuse");
                    out.write(buffer, 0, n);
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally { connection.disconnect(); }
    }
    static BoseRendererContract discover(String ip) throws Exception {
        InetAddress target = InetAddress.getByName(ip);
        if (!target.isSiteLocalAddress() || target.isLoopbackAddress() || target.getAddress().length != 4)
            throw new IOException("L'adresse Bose doit être une IPv4 privée du réseau local");
        InetAddress local;
        try (DatagramSocket route = new DatagramSocket()) { route.connect(target, 1900); local = route.getLocalAddress(); }
        String found = "";
        try (DatagramSocket socket = new DatagramSocket(new InetSocketAddress(local, 0))) {
            byte[] search = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            socket.send(new DatagramPacket(search, search.length, InetAddress.getByName("239.255.255.250"), 1900));
            long deadline = System.nanoTime() + 3000000000L;
            while (System.nanoTime() < deadline) {
                socket.setSoTimeout((int)Math.max(1, (deadline - System.nanoTime()) / 1000000));
                DatagramPacket packet = new DatagramPacket(new byte[65507], 65507);
                try { socket.receive(packet); } catch (SocketTimeoutException e) { break; }
                if (!target.equals(packet.getAddress())) continue;
                found = location(new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8), ip);
                if (!found.isEmpty()) break;
            }
        }
        if (found.isEmpty()) throw new IOException("L'enceinte répond au contrôle Bose mais n'annonce pas son service de lecture UPnP. Aucun transfert n'a été envoyé.");
        URI location = new URI(found); URI[] urls = description(get(location), location, ip);
        return new BoseRendererContract(urls[0], urls[1], actions(get(urls[1])));
    }
}
