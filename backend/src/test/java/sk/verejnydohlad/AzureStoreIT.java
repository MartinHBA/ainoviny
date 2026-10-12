package sk.verejnydohlad;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.time.*;
import java.util.*;
import sk.verejnydohlad.Models.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="storage.integration",matches="true")
class AzureStoreIT {
    @Test void failedTablePublicationRollsBackEveryPublicIndexAndCanBeRetried() {
        Config config=Config.from(Map.of("APP_STORAGE_CONNECTION_STRING","UseDevelopmentStorage=true"));var store=new AzureStore(config);store.initialize(SourceRegistry.load());
        var r=Fixtures.release("rollback-"+UUID.randomUUID());store.collect(r);var mock=new MockAi();var d=mock.generate(List.of(r));
        String id=Normalize.hash(r.id()),hash=Normalize.hash(d.markdown()),path="drafts/"+id+"/"+hash+".md";
        var a=new Article(id,"synthetic-"+id.substring(0,16),d.title(),d.perex(),d.category(),Fixtures.CLOCK.instant(),Fixtures.CLOCK.instant(),null,Status.DRAFT,path,
            List.of(r.id()),List.of(r.originalUrl()),mock.model(),null,null,null,hash,true,false,List.of(),d.claims(),"COMPLETE",0,"2026-10-12");
        store.putBlob("articles",path,d.markdown());store.createArticle(a);var editor=new Editorial(store,Fixtures.CLOCK);editor.approve(id,"Rollback test editor",false);
        var table=new com.azure.data.tables.TableClientBuilder().connectionString("UseDevelopmentStorage=true").tableName("Articles").buildClient();
        table.createEntity(new com.azure.data.tables.models.TableEntity("editorial","slug|"+a.slug()).addProperty("status","REVIEWED"));
        try {
            assertThrows(com.azure.data.tables.models.TableServiceException.class,() -> editor.publish(id));
            assertEquals(Status.REVIEWED,store.article(id).value().status());assertNull(new PublicApi(store).detail(a.slug()));
            assertTrue(store.published(30,null,null).articles().stream().noneMatch(s -> s.id().equals(id)));
        } finally { table.deleteEntity("editorial","slug|"+a.slug()); }
        editor.publish(id);assertNotNull(new PublicApi(store).detail(a.slug()));
    }
    @Test void azuriteCollectionCasApprovalPublicationAndPagination() {
        Config config=Config.from(Map.of("APP_STORAGE_CONNECTION_STRING","UseDevelopmentStorage=true"));var store=new AzureStore(config);store.initialize(SourceRegistry.load());
        var release=Fixtures.release("azurite-"+UUID.randomUUID());assertTrue(store.collect(release));assertFalse(store.collect(release));assertEquals(release.body(),store.release(release.id()).body());
        var mock=new MockAi();Draft d=mock.generate(List.of(release));Verification v=Validation.grounded(d,List.of(release),mock.verify(d,List.of(release)));
        String id=Normalize.hash(release.id()),hash=Normalize.hash(d.markdown()),path="drafts/"+id+"/"+hash+".md";
        var a=new Article(id,"synthetic-"+id.substring(0,16),d.title(),d.perex(),d.category(),Fixtures.CLOCK.instant(),Fixtures.CLOCK.instant(),null,Status.DRAFT,path,
            List.of(release.id()),List.of(release.originalUrl()),mock.model(),null,null,null,hash,v.passed(),v.sensitive(),v.issues(),d.claims(),"COMPLETE",0,"2026-10-12");
        store.putBlob("articles",path,d.markdown());assertTrue(store.createArticle(a));assertTrue(a.factCheckPassed());assertNull(new PublicApi(store).detail(a.slug()));
        String budgetKey="synthetic-test-"+UUID.randomUUID();assertTrue(store.reserveCall(budgetKey,1));assertFalse(store.reserveCall(budgetKey,1));assertTrue(store.reserveArticle(budgetKey,id,1));assertTrue(store.reserveArticle(budgetKey,id,1));assertFalse(store.reserveArticle(budgetKey,Normalize.hash("other"),1));
        var before=store.article(id);store.updateArticle(a,before.version());assertThrows(Exception.class,() -> store.updateArticle(a,before.version()));
        var editor=new Editorial(store,Fixtures.CLOCK);editor.approve(id,"Integration test reviewer",false);editor.publish(id);editor.publish(id);
        assertEquals(Status.PUBLISHED,store.article(id).value().status());assertEquals(a.title(),new PublicApi(store).detail(a.slug()).headline());
        Page page=new PublicApi(store).list("1",null,null);assertEquals(1,page.articles().size());
        if(page.nextCursor()!=null){Page next=new PublicApi(store).list("1",page.nextCursor(),null);assertNotEquals(page.articles().getFirst().id(),next.articles().getFirst().id());}
        assertNotNull(store.latest().featured());
        try(var lock=store.lock()){assertThrows(Exception.class,store::lock);lock.check();}
    }
}
