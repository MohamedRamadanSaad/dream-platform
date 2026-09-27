package com.saadat.youtube.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Parses a YouTube channel Atom feed ({@code /feeds/videos.xml?channel_id=…}) with a namespace-aware,
 * XXE-hardened DOM parser. Per {@code entry}: {@code yt:videoId}, {@code title}, {@code published},
 * {@code link[rel=alternate]/@href}, {@code media:group/media:thumbnail/@url}.
 */
public final class YoutubeFeedParser {

    static final String NS_ATOM = "http://www.w3.org/2005/Atom";
    static final String NS_YT = "http://www.youtube.com/xml/schemas/2015";
    static final String NS_MEDIA = "http://search.yahoo.com/mrss/";
    static final String WATCH_URL_PREFIX = "https://www.youtube.com/watch?v=";

    private YoutubeFeedParser() {
    }

    public static List<FeedEntry> parse(byte[] xml) {
        if (xml == null || xml.length == 0) {
            return List.of();
        }
        Document doc;
        try {
            doc = newBuilder().parse(new ByteArrayInputStream(xml));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new IllegalArgumentException("Invalid YouTube feed XML", e);
        }
        List<FeedEntry> out = new ArrayList<>();
        NodeList entries = doc.getElementsByTagNameNS(NS_ATOM, "entry");
        for (int i = 0; i < entries.getLength(); i++) {
            Element entry = (Element) entries.item(i);
            String videoId = text(entry, NS_YT, "videoId");
            String title = text(entry, NS_ATOM, "title");
            Instant published = parseInstant(text(entry, NS_ATOM, "published"));
            if (videoId == null || videoId.isBlank() || published == null) {
                continue;
            }
            String url = alternateLink(entry);
            if (url == null) {
                url = WATCH_URL_PREFIX + videoId;
            }
            out.add(new FeedEntry(videoId.trim(), title == null ? "" : title.trim(), published, url,
                    thumbnail(entry)));
        }
        return out;
    }

    private static DocumentBuilder newBuilder() throws ParserConfigurationException {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        return f.newDocumentBuilder();
    }

    /** Text of the first direct child element {ns}local, or null. */
    private static String text(Element parent, String ns, String local) {
        Element child = firstChild(parent, ns, local);
        return child == null ? null : child.getTextContent();
    }

    private static Element firstChild(Element parent, String ns, String local) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && ns.equals(n.getNamespaceURI())
                    && local.equals(n.getLocalName())) {
                return (Element) n;
            }
        }
        return null;
    }

    private static String alternateLink(Element entry) {
        NodeList children = entry.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && NS_ATOM.equals(n.getNamespaceURI())
                    && "link".equals(n.getLocalName())) {
                Element link = (Element) n;
                String rel = link.getAttribute("rel");
                String href = link.getAttribute("href");
                if ((rel.isEmpty() || "alternate".equals(rel)) && !href.isBlank()) {
                    return href.trim();
                }
            }
        }
        return null;
    }

    private static String thumbnail(Element entry) {
        NodeList thumbs = entry.getElementsByTagNameNS(NS_MEDIA, "thumbnail");
        if (thumbs.getLength() == 0) {
            return null;
        }
        String url = ((Element) thumbs.item(0)).getAttribute("url");
        return url.isBlank() ? null : url.trim();
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.trim()).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** One parsed video. */
    public record FeedEntry(String videoId, String title, Instant publishedAt, String url, String thumbnailUrl) {
    }
}
