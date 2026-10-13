package sk.verejnydohlad;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.time.*;
import java.util.*;
import sk.verejnydohlad.Models.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="storage.integration",matches="true")
class AzureStoreIT {
    static final Map<String,Set<String>> existingKeys=new HashMap<>();
    static com.azure.data.tables.models.TableEntity existingBudget;
    static com.azure.data.tables.TableClient table(String name) {
        return new com.azure.data.tables.TableClientBuilder().connectionString("UseDevelopmentStorage=true").tableName(name).buildClient();
    }
    @BeforeAll static void rememberEmulatorState() {
        for(String name:List.of("Articles","Sources","PressReleases")) {
            var client=table(name);try {client.createTable();}catch(com.azure.data.tables.models.TableServiceException e){if(e.getResponse().getStatusCode()!=409)throw e;}
            var keys=new HashSet<String>();
            for(var entity:client.listEntities()) {
                keys.add(entity.getPartitionKey()+"/"+entity.getRowKey());
                if(name.equals("Sources") && entity.getPartitionKey().equals("ops") && entity.getRowKey().equals("budget|2026-10-12")) existingBudget=entity;
            }
            existingKeys.put(name,keys);
        }
    }
    @AfterAll static void removeOnlyEntitiesCreatedByThisTestRun() {
        for(var entry:existingKeys.entrySet()) {
            var client=table(entry.getKey());var added=new ArrayList<com.azure.data.tables.models.TableEntity>();
            for(var entity:client.listEntities()) if(!entry.getValue().contains(entity.getPartitionKey()+"/"+entity.getRowKey()))added.add(entity);
            for(var entity:added)client.deleteEntity(entity.getPartitionKey(),entity.getRowKey());
        }
        if(existingBudget!=null)table("Sources").upsertEntity(existingBudget);
    }
    @Test void fivePublicationLimitIncludesManualPublishingAndLatestEditionReturnsAllFive() {
        var store=new AzureStore(Config.from(Map.of("APP_STORAGE_CONNECTION_STRING","UseDevelopmentStorage=true")));store.initialize(List.of());
        var clock=Clock.offset(Fixtures.CLOCK,Duration.ofDays(10));var editor=new Editorial(store,clock,5);String lastId=null;
        for(int i=0;i<6;i++) {
            var r=Fixtures.release("five-publications-"+UUID.randomUUID());store.collect(r);
            var d=new MockAi().generate(List.of(r));String id=Normalize.hash(r.id()),hash=Normalize.hash(d.markdown()),path="drafts/"+id+"/"+hash+".md";
            var a=new Article(id,"synthetic-"+id.substring(0,16),d.title(),d.perex(),d.category(),Fixtures.CLOCK.instant(),Fixtures.CLOCK.instant(),null,Status.DRAFT,path,
                List.of(r.id()),List.of(r.originalUrl()),"mock-offline-synthetic",null,null,null,hash,true,false,List.of(),d.claims(),"COMPLETE",i,"2026-10-12");
            store.putBlob("articles",path,d.markdown());store.createArticle(a);editor.approve(id,"Integration reviewer",false);
            if(i<5)editor.publish(id);else {assertThrows(IllegalStateException.class,() -> editor.publish(id));lastId=id;}
        }
        assertEquals(Status.REVIEWED,store.article(lastId).value().status());
        assertEquals("2026-10-22",store.latest().date());assertEquals(5,store.latest().articles().size()+1);
        editor.publish(store.latest().featured().id()); // Retry is idempotent even when the cap is full.
    }
    @Test void researchArchiveLoadsOriginalBodiesEvenAfterSourcesAreConsumed() {
        var store=new AzureStore(Config.from(Map.of("APP_STORAGE_CONNECTION_STRING","UseDevelopmentStorage=true")));store.initialize(List.of());
        var r=Fixtures.release("research-archive-"+UUID.randomUUID());store.collect(r);store.releaseStatus(r,"DONE",null);
        var archive=store.recent(Fixtures.CLOCK.instant().minusSeconds(1),100);
        assertTrue(archive.stream().anyMatch(item -> item.equals(r)));
    }
    @Test void sourceChecksInitializeMissingRegistryRowsWithoutHumanCliSetup() {
        var store=new AzureStore(Config.from(Map.of("APP_STORAGE_CONNECTION_STRING","UseDevelopmentStorage=true")));store.initialize(List.of());
        var fixture=Fixtures.source();String id="source-check-"+UUID.randomUUID();
        var source=new Source(id,fixture.name(),fixture.baseUrl(),fixture.feedUrl(),fixture.sourceType(),fixture.enabled(),fixture.notes(),fixture.bodySelector(),fixture.allowedHosts());
        var table=new com.azure.data.tables.TableClientBuilder().connectionString("UseDevelopmentStorage=true").tableName("Sources").buildClient();
        store.sourceChecked(source,Fixtures.CLOCK.instant());assertEquals(source,Json.read((String)table.getEntity("registry",id).getProperty("payload"),Source.class));
        Instant next=Fixtures.CLOCK.instant().plusSeconds(5);store.sourceChecked(source,next);
        assertEquals(next.toString(),table.getEntity("registry",id).getProperty("lastCheckedAt"));
    }
    @Test void automaticEditorUsesRealBlobAuditAndAtomicAzureTablePublication() {
        Config config=Config.from(Map.of("APP_STORAGE_CONNECTION_STRING","UseDevelopmentStorage=true","AUTO_PUBLISH_ENABLED","true"));
        var store=new AzureStore(config);store.initialize(SourceRegistry.load());var r=Fixtures.release("auto-integration-"+UUID.randomUUID());store.collect(r);
        var ai=new MockAi();var d=ai.generate(List.of(r));var v=Validation.grounded(d,List.of(r),ai.verify(d,List.of(r)));
        String id=Normalize.hash(r.id()),hash=Normalize.hash(d.markdown()),path="drafts/"+id+"/"+hash+".md";
        var a=new Article(id,"synthetic-"+id.substring(0,16),d.title(),d.perex(),d.category(),Fixtures.CLOCK.instant(),Fixtures.CLOCK.instant(),null,Status.DRAFT,path,
            List.of(r.id()),List.of(r.originalUrl()),ai.model(),null,null,null,hash,v.passed(),v.sensitive(),v.issues(),d.claims(),"COMPLETE",0,"2026-10-12");
        store.putBlob("articles",path,d.markdown());store.createArticle(a);assertNull(new PublicApi(store).detail(a.slug()));
        int before=store.budget("2026-10-12").calls();var errors=new ArrayList<String>();
        try(var guard=store.lock()) {
            var editor=new AutomaticEditor(store,ai,config,Fixtures.CLOCK);
            assertEquals(1,editor.run(guard,Instant.now().plusSeconds(90),errors).published());
            assertEquals(0,editor.run(guard,Instant.now().plusSeconds(90),errors).published());
        }
        assertTrue(errors.isEmpty());assertEquals(before+1,store.budget("2026-10-12").calls());
        var published=store.article(id).value();assertEquals(Status.PUBLISHED,published.status());assertEquals("AI editor: mock-offline-editor",published.reviewer());
        assertTrue(new PublicApi(store).detail(a.slug()).aiDisclosure().contains("neprešiel ľudskou"));
        String inputHash=Normalize.hash(Json.write(List.of(Editorial.approvalHash(a,d.markdown()),List.of(r),ai.editorModel(),AutomaticEditor.POLICY_VERSION)));
        var audit=Json.read(store.blob("articles","reviews/"+id+"/"+inputHash+".json"),AutomaticEditor.Audit.class);assertTrue(audit.decision().approved());
    }
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
