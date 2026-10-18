package sk.verejnydohlad;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.jsoup.Jsoup;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import sk.verejnydohlad.Models.*;
import static org.junit.jupiter.api.Assertions.*;

class OfficialResearchTest {
    static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    @Test void fullArticleExtractionKeepsAllScopedParagraphsWithoutBannerAndDeduplicatesNestedAlternatives() {
        var page=Jsoup.parse("<p class='govuk-body'>Global site banner</p><main id='page-content'>"+
            "<p class='govuk-body'>Vláda oznámila pripravovanú transakciu.</p>"+
            "<p class='govuk-body'>Vlastníctvo sa má zmeniť po dokončení prevodu.</p>"+
            "<p class='govuk-body'>Financovanie a ďalšie míľniky zostávajú podmienkou.</p></main>");
        assertEquals("Vláda oznámila pripravovanú transakciu. Vlastníctvo sa má zmeniť po dokončení prevodu. Financovanie a ďalšie míľniky zostávajú podmienkou.",
            OfficialResearch.body(page,ResearchRegistry.site("uv-sr")));
        var nested=Jsoup.parse("<nav>Global menu</nav><article><div class='entry-content'><p>Prvý odsek.</p>"+
            "<nav>Inner menu</nav><p>Druhý odsek.</p></div><footer>Related links</footer></article><article><p>Tretí odsek.</p></article>");
        assertEquals("Prvý odsek. Druhý odsek. Tretí odsek.",OfficialResearch.body(nested,"article, .entry-content, article p"));
        assertTrue(nested.text().contains("Inner menu")); // Original DOM remains reusable for links.
        assertThrows(IllegalArgumentException.class,()->OfficialResearch.body(page,".missing-article"));
    }
    @Test void officialSearchLoadsScopedOriginalAndStoresItAsReusableContextOnly() {
        var store=new MemoryStore();var seed=Fixtures.release("lookup-primary");store.collect(seed);var requested=new ArrayList<String>();
        String url="https://www.rrz.sk/synthetic-budget-assessment/";String body="Syntetické hodnotenie rozpočtu upozorňuje na predpoklady príjmov a riziká verejného dlhu. ".repeat(3);
        var lookup=new OfficialResearch((u,hosts) -> {
            requested.add(u);assertTrue(hosts.contains("www.rrz.sk"));
            if(u.contains("feed=rss"))return bytes("<rss><channel><item><title>Syntetické hodnotenie rozpočtu</title><link>"+url+"</link><pubDate>Sun, 11 Oct 2026 10:00:00 GMT</pubDate><description>Rozpočet</description></item></channel></rss>");
            assertEquals(url,u);return bytes("<html><nav>Untrusted navigation</nav><div class='entry-content'><p>"+body+"</p></div></html>");
        });
        try(var guard=store.lock()) {
            var found=lookup.find(new ResearchPlan("Test question",List.of("rozpočet"),List.of("rrz-sk")),new MockAi().generate(List.of(seed)),List.of(seed),store,guard,Instant.now().plusSeconds(120));
            assertEquals(2,found.documents().size());var r=found.documents().get(1);assertEquals(Normalize.text(body),r.body());assertFalse(r.body().contains("navigation"));
            assertEquals("CONTEXT",store.releaseStatus(r));assertEquals(1,store.pending(40).size());assertEquals(0,store.budget("2026-10-12").calls());
            assertTrue(requested.getFirst().contains("s=rozpo"));assertTrue(found.gaps().stream().anyMatch(g -> g.contains("úplné pokrytie")));
        }
    }
    @Test void unavailableAndUnsupportedSourcesBecomeExplicitGapsWithoutInventedDocuments() {
        var store=new MemoryStore();var seed=Fixtures.release("lookup-gaps");store.collect(seed);var lookup=new OfficialResearch((u,h) -> {throw new IllegalStateException("Synthetic outage");});
        try(var guard=store.lock()) {
            var found=lookup.find(new ResearchPlan("Test question",List.of("rozpočet"),List.of("rrz-sk","statdata-sr")),new MockAi().generate(List.of(seed)),List.of(seed),store,guard,Instant.now().plusSeconds(120));
            assertEquals(List.of(seed),found.documents());assertTrue(found.gaps().stream().anyMatch(g -> g.contains("nebol dostupný")));assertTrue(found.gaps().stream().anyMatch(g -> g.contains("nepodporuje")));
        }
    }
    @Test void discoveryRejectsExternalHostsHttpCredentialsAndPortsEvenWhenLinkedByOfficialPage() {
        var site=ResearchRegistry.site("rrz-sk");
        var page=Jsoup.parse("<a href='https://newspaper.invalid/budget'>Rozpočet hodnotenie</a><a href='http://www.rrz.sk/budget'>Rozpočet hodnotenie</a>"+
            "<a href='https://user@www.rrz.sk/budget'>Rozpočet hodnotenie</a><a href='https://www.rrz.sk:8080/budget'>Rozpočet hodnotenie</a>"+
            "<a href='https://evil.rrz.sk/budget'>Rozpočet hodnotenie</a><a href='/official-budget/'>Rozpočet hodnotenie</a>",site.entryUrl());
        assertEquals(List.of("https://www.rrz.sk/official-budget/"),OfficialResearch.links(page,site,"rozpočet").stream().map(OfficialResearch.Link::url).toList());
    }
    @Test void textPDFPreservesPageMarkersAndImageOnlyPDFCannotBecomeEvidence() throws Exception {
        byte[] text;
        try(var doc=new PDDocument();var out=new java.io.ByteArrayOutputStream()) {
            var font=new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for(int i=0;i<2;i++){var page=new PDPage();doc.addPage(page);try(var stream=new PDPageContentStream(doc,page)){
                stream.beginText();stream.setFont(font,12);stream.newLineAtOffset(40,700);stream.showText("Synthetic official budget assessment and documented fiscal context page "+(i+1));stream.endText();
            }}doc.save(out);text=out.toByteArray();
        }
        String extracted=OfficialResearch.pdf(text);assertTrue(extracted.contains("[PDF strana 1]"));assertTrue(extracted.contains("[PDF strana 2]"));
        try(var doc=new PDDocument();var out=new java.io.ByteArrayOutputStream()){doc.addPage(new PDPage());doc.save(out);byte[] blank=out.toByteArray();assertThrows(IllegalArgumentException.class,() -> OfficialResearch.pdf(blank));}
    }
}
