package sk.verejnydohlad;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class FeedParserTest {
    private byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    @Test void parsesRssNormalizesHtmlAndCanonicalUrl() {
        String rss="""
            <rss><channel><item><title>[SYNTHETIC] Nadpis</title><link>https://example.invalid/story?utm_source=rss&amp;x=1</link>
            <pubDate>Mon, 12 Oct 2026 05:00:00 +0200</pubDate><description><![CDATA[<p>Testovací&nbsp; obsah.</p><script>bad()</script>]]></description></item></channel></rss>
            """;
        var item=new FeedParser().parse(bytes(rss),Fixtures.source()).getFirst();
        assertEquals("https://example.invalid/story?x=1",item.url());assertEquals("Testovací obsah.",item.body());assertEquals(Instant.parse("2026-10-12T03:00:00Z"),item.publishedAt());
    }
    @Test void parsesAtomAndFallsBackFromEmptyContentToSummary() {
        var xml="<feed xmlns='http://www.w3.org/2005/Atom'><entry><title>[SYNTHETIC] Atom</title><link rel='alternate' href='/story'/><updated>2026-10-12T03:00:00Z</updated><content/><summary>synthetic summary</summary></entry></feed>";
        var item=new FeedParser().parse(bytes(xml),Fixtures.source()).getFirst();assertEquals("synthetic summary",item.body());assertEquals("https://example.invalid/story",item.url());
    }
    @Test void rejectsXxe() {assertThrows(IllegalArgumentException.class,() -> new FeedParser().parse(bytes("<!DOCTYPE foo [<!ENTITY xxe SYSTEM 'file:///etc/passwd'>]><rss>&xxe;</rss>"),Fixtures.source()));}
    @Test void skipsMissingDateAndExternalHost() {
        String xml="<rss><channel><item><title>missing date</title><link>/a</link></item><item><title>external</title><link>https://evil.invalid/a</link><pubDate>2026-10-12</pubDate></item><item><title>missing URL</title><pubDate>2026-10-12</pubDate></item></channel></rss>";
        assertTrue(new FeedParser().parse(bytes(xml),Fixtures.source()).isEmpty());
    }
    @Test void parsesSlovakDateAndTrackingParameters() {assertEquals("2026-10-11T22:00:00Z",Normalize.date("12.10.2026").toString());assertEquals("https://example.invalid/a?b=2&z=1",Normalize.canonical("https://EXAMPLE.invalid:443/a?z=1&utm_campaign=x&b=2#fragment"));}
    @Test void robotsHonorsWildcardAndLongestAllowance() {String robots="User-agent: *\nDisallow: /private*\nAllow: /private/public\nDisallow: /*?*download=*";assertFalse(Robots.allowed(robots,"/private/doc"));assertTrue(Robots.allowed(robots,"/private/public/doc"));assertFalse(Robots.allowed(robots,"/doc?download=1"));}
}
