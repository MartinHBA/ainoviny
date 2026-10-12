package sk.verejnydohlad;

import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import java.io.*;
import java.time.*;
import java.util.*;
import sk.verejnydohlad.Models.*;

public final class FeedParser {
    public record Item(String title, String url, Instant publishedAt, String body) {}
    public List<Item> parse(byte[] bytes, Source source) {
        try {
            var factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
            NodeList nodes = document.getElementsByTagNameNS("*", "item"); boolean atom = nodes.getLength() == 0;
            if (atom) nodes = document.getElementsByTagNameNS("*", "entry");
            var result = new ArrayList<Item>();
            for (int i = 0; i < nodes.getLength() && i < 100; i++) {
                try {
                    Element entry = (Element)nodes.item(i);
                    String link = child(entry, "link");
                    if (atom) {
                        var links = entry.getElementsByTagNameNS("*", "link"); link = "";
                        for (int j = 0; j < links.getLength(); j++) { Element l = (Element)links.item(j); if (l.getAttribute("rel").isEmpty() || l.getAttribute("rel").equals("alternate")) { link = l.getAttribute("href"); break; } }
                    }
                    if(link.isBlank() || link.startsWith("?")) continue;
                    link = Normalize.canonical(java.net.URI.create(source.feedUrl()).resolve(link).toString());
                    if (!source.allowedHosts().contains(java.net.URI.create(link).getHost())) continue;
                    String title = Normalize.text(child(entry, "title"));
                    Instant date = Normalize.date(first(entry, "pubDate", "published", "updated", "date"));
                    if (title.isBlank() || date.isAfter(Instant.now().plusSeconds(3600))) continue;
                    String body = Normalize.text(first(entry, "encoded", "content", "description", "summary"));
                    result.add(new Item(title, link, date, body));
                } catch (IllegalArgumentException ignored) { /* malformed entry must not break a feed */ }
            }
            result.sort(Comparator.comparing(Item::publishedAt).reversed()); return result;
        } catch (Exception e) { throw new IllegalArgumentException("Invalid RSS/Atom feed", e); }
    }
    private static String child(Element parent, String name) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && (name.equals(e.getLocalName()) || name.equals(e.getTagName()))) return e.getTextContent().trim();
        }
        return "";
    }
    private static String first(Element e, String... names) { for (String n : names) { String s = child(e,n); if (!s.isBlank()) return s; } return ""; }
}
