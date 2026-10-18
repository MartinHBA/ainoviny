package sk.verejnydohlad;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import sk.verejnydohlad.Models.*;
import static org.junit.jupiter.api.Assertions.*;

class NewsroomDiscoveryTest {
    @Test void currentUvPublicationEvidenceIncludesLaterParagraphsAndStillRequiresExactSourceSupport(){
        String url="https://www.vlada.gov.sk/tlacove-spravy/synthetic-construction-milestone/";
        String first="Úrad vlády informoval o dokončení hrubej stavby novej nemocnice. Ide o oznámený stavebný míľnik, po ktorom pokračujú ďalšie práce.";
        String last="Podľa oznámenia sa otvorenie plánuje na prelome rokov 2028 až 2029; nejde o už otvorenú nemocnicu.";
        var r=new Release(Normalize.hash(url),"uv-sr","Úrad vlády SR",url,Fixtures.CLOCK.instant(),Fixtures.CLOCK.instant(),"Stavebný míľnik",first+" "+last,Normalize.hash(first+" "+last),"fixture.json");
        var check=PublicationCheck.official((u,h)->("<p class='govuk-body'>Unrelated site banner</p><main id='page-content'>"+
            "<p class='govuk-body'>"+first+"</p><p class='govuk-body'>"+last+"</p></main>").getBytes(StandardCharsets.UTF_8));
        try(var guard=new MemoryStore().lock()) {
            var current=check.refresh(List.of(r),guard,Instant.now().plusSeconds(90)).getFirst();
            assertEquals(r.body(),current.body());assertFalse(current.body().contains("banner"));
            String title="Vláda oznámila stavebný míľnik",perex="Podľa oznámenia bola dokončená hrubá stavba nemocnice, ďalšie práce pokračujú.";
            var draft=new Draft(title,perex,"Slovensko",last,List.of(new Claim(title,r.id(),first),new Claim(perex,r.id(),first),new Claim(last,r.id(),last)));
            assertTrue(Validation.grounded(draft,List.of(current),new Verification(true,false,List.of())).passed());
            var truncated=new Release(r.id(),r.sourceId(),r.organization(),r.originalUrl(),r.publishedAt(),r.collectedAt(),r.title(),first,Normalize.hash(first),r.blobPath());
            assertFalse(Validation.grounded(draft,List.of(truncated),new Verification(true,false,List.of())).passed());
        }
    }
    @Test void publicationCanUseFreshOfficialFeedForUnloadedHtmlButCannotReuseArchivedOrDifferentArticleText(){
        var fixture=ResearchFlowTest.source("current-feed","ifp-sr");String url="https://ifp.sk/current-report/";
        var source=new Release(Normalize.hash(url),"ifp-sr","IFP",url,fixture.publishedAt(),fixture.collectedAt(),fixture.title(),fixture.body(),fixture.contentHash(),fixture.blobPath());
        String current="Aktuálny originál vo feede. "+body;
        var urls=new ArrayList<String>();
        var check=PublicationCheck.official((u,h)->{urls.add(u);return (u.endsWith("/feed/")?"<rss><channel><item><pubDate>Mon, 12 Oct 2026 03:00:00 GMT</pubDate><title>Aktuálny názov</title><link>"+url+"</link><description>"+current+"</description></item></channel></rss>":"<html><main role='main'>Unloaded</main></html>").getBytes(StandardCharsets.UTF_8);});
        var store=new MemoryStore();try(var guard=store.lock()) {
            var result=check.refresh(List.of(source),guard,Instant.now().plusSeconds(90)).getFirst();assertEquals(current,result.body());assertNotEquals(source.body(),result.body());assertEquals("Aktuálny názov",result.title());assertTrue(urls.contains("https://ifp.sk/feed/"));
            var absent=PublicationCheck.official((u,h)->(u.endsWith("/feed/")?"<rss><channel><item><pubDate>Mon, 12 Oct 2026 03:00:00 GMT</pubDate><title>Iná správa</title><link>https://ifp.sk/other/</link><description>"+current+"</description></item></channel></rss>":"<html><main role='main'>Unloaded</main></html>").getBytes(StandardCharsets.UTF_8));
            assertThrows(IllegalStateException.class,()->absent.refresh(List.of(source),guard,Instant.now().plusSeconds(90)));
        }
    }
    @Test void currentNbsAndRrzLayoutsExtractOnlyArticleTextAndKeepLegacyLayoutSupported(){
        var nbs=org.jsoup.Jsoup.parse("<nav>Unrelated menu</nav><article class='nbs-post'><h1>Aktuálna analýza</h1><p>"+body+"</p></article><aside>Related navigation</aside>");
        var rrz=org.jsoup.Jsoup.parse("<article class='article-component'><h1>Aktuálne hodnotenie</h1><div class='article-content'>"+body+"</div><aside>Related navigation</aside></article>");
        String nbsText=OfficialResearch.body(nbs,ResearchRegistry.site("nbs-sk"));String rrzText=OfficialResearch.body(rrz,ResearchRegistry.site("rrz-sk"));
        assertTrue(nbsText.contains(body));assertFalse(nbsText.contains("Unrelated menu"));assertFalse(nbsText.contains("Related navigation"));assertEquals(body,rrzText);
        assertEquals(body,OfficialResearch.body(org.jsoup.Jsoup.parse("<div class='entry-content'>"+body+"</div>"),ResearchRegistry.site("rrz-sk")));
    }
    @Test void analystUnitsCannotClaimUnrelatedMinistryNavigationAsTheirOwnDocuments(){var uhp=ResearchRegistry.site("uhp-sr");assertFalse(OfficialResearch.inScope("https://www.mfsr.sk/sk/financie/financna-kontrola/",uhp));assertTrue(OfficialResearch.inScope("https://www.mfsr.sk/uhp/hodnotenie-investicie/",uhp));assertTrue(OfficialResearch.inScope("https://www.mfsr.sk/sk/financie/hodnota-za-peniaze/hodnotenia/",uhp));assertFalse(OfficialResearch.inScope("https://www.minzp.sk/tlacove-spravy/",ResearchRegistry.site("iep-sr")));}
    @Test void unavailableHtmlCanUseActualFeedEvidenceWithItsKnownPublicationDate(){var store=new MemoryStore();store.operational("discovery-cursor","1");var discovery=new OfficialDiscovery((url,hosts)->{if(url.equals("https://ifp.sk/feed/"))return ("<rss><channel><item><title>Nová analýza rozpočtu</title><link>https://ifp.sk/novy-komentar/</link><pubDate>"+java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC))+"</pubDate><description>"+body+"</description></item></channel></rss>").getBytes(StandardCharsets.UTF_8);throw new IllegalStateException("Unavailable article");});try(var guard=store.lock()){assertEquals(1,discovery.scan(store,guard,Instant.now().plusSeconds(120)).documents());}var r=store.releases.values().iterator().next();assertEquals(body,r.body());assertNotEquals(Instant.EPOCH,r.publishedAt());assertEquals("CONTEXT",store.releaseStatus(r));}
    static String body="Oficiálna analýza vysvetľuje výdavky a financovanie verejného sektora. Dokument uvádza rozsah hodnotenia a upozorňuje na obmedzenia posudzovaných údajov.";
    @Test void discoveryVisitsTheCatalogBeforeAnyConceptAndKeepsOriginalsAsContext(){
        var store=new MemoryStore();store.operational("discovery-cursor","1");var urls=new ArrayList<String>();
        var discovery=new OfficialDiscovery((url,hosts)->{
            urls.add(url);String html;
            if(url.equals("https://ifp.sk/feed/"))html="<rss><channel><item><title>Nová analýza rozpočtu</title><link>https://ifp.sk/analyza-rozpoctu/</link><pubDate>"+java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC))+"</pubDate><description>"+body+"</description></item></channel></rss>";
            else if(url.equals("https://ifp.sk/analyza-rozpoctu/"))html="<html><h1>Nová analýza rozpočtu</h1><meta property='article:published_time' content='"+Instant.now()+"'><main role='main'><p>"+body+"</p></main></html>";
            else html="<html><main><p>Bez nových podkladov</p></main></html>";
            return html.getBytes(StandardCharsets.UTF_8);
        });
        try(var guard=store.lock()){var result=discovery.scan(store,guard,Instant.now().plusSeconds(120));assertEquals(1,result.documents());assertEquals(5,result.checked());}
        assertTrue(store.editorial().isEmpty());assertEquals("CONTEXT",store.releaseStatus(store.releases.values().iterator().next()));assertTrue(urls.contains("https://ifp.sk/feed/"));assertEquals("6",store.operational("discovery-cursor"));
    }
    @Test void discoveryMovesPastBrokenSitesAndRotatesRatherThanRestartingAtTheFirstSite(){var store=new MemoryStore();var checked=new ArrayList<String>();var discovery=new OfficialDiscovery((url,hosts)->{checked.add(url);throw new IllegalStateException("Unavailable");});try(var guard=store.lock()){assertEquals(5,discovery.scan(store,guard,Instant.now().plusSeconds(120)).checked());String cursor=store.operational("discovery-cursor");assertEquals("5",cursor);discovery.scan(store,guard,Instant.now().plusSeconds(120));assertEquals("10",store.operational("discovery-cursor"));}assertEquals(10,checked.stream().distinct().count());}
    @Test void publicationRefreshReadsCurrentOfficialBodyAndRejectsUnknownHosts(){var source=ResearchFlowTest.source("current-original","rrz-sk");var real=new Release(Normalize.hash("https://www.rrz.sk/current/"),"rrz-sk","RRZ","https://www.rrz.sk/current/",source.publishedAt(),source.collectedAt(),"Predošlý názov",source.body(),source.contentHash(),source.blobPath());var check=PublicationCheck.official((url,hosts)->("<html><h1>Aktuálne hodnotenie rozpočtu</h1><div class='entry-content'>"+body+"</div></html>").getBytes(StandardCharsets.UTF_8));var store=new MemoryStore();try(var guard=store.lock()){var refreshed=check.refresh(List.of(real),guard,Instant.now().plusSeconds(90)).getFirst();assertEquals(real.id(),refreshed.id());assertEquals("Aktuálne hodnotenie rozpočtu",refreshed.title());assertEquals(body,refreshed.body());assertEquals(Normalize.hash(body),refreshed.contentHash());var bad=new Release(real.id(),"rrz-sk","RRZ","https://newspaper.invalid/article",real.publishedAt(),real.collectedAt(),real.title(),real.body(),real.contentHash(),real.blobPath());assertThrows(IllegalStateException.class,()->check.refresh(List.of(bad),guard,Instant.now().plusSeconds(90)));}}
}
