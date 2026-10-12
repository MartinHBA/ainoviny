package sk.verejnydohlad;

import org.junit.jupiter.api.Test;
import sk.verejnydohlad.Models.*;
import static org.junit.jupiter.api.Assertions.*;

class EditorialTest {
    @Test void onlyHumanApprovedArticlesReachPublicApi() {
        var store=new MemoryStore();var a=Fixtures.generated(store);var api=new PublicApi(store);var editor=new Editorial(store,Fixtures.CLOCK);
        assertNull(api.detail(a.slug()));assertTrue(api.list(null,null,null).articles().isEmpty());assertThrows(IllegalStateException.class,() -> editor.publish(a.id()));
        editor.approve(a.id(),"Test reviewer",false);assertEquals(Status.REVIEWED,store.article(a.id()).value().status());assertNull(api.detail(a.slug()));
        editor.publish(a.id());assertNotNull(api.detail(a.slug()));assertEquals(1,api.list(null,null,null).articles().size());assertTrue(api.detail(a.slug()).aiDisclosure().contains("ľudským"));
        editor.publish(a.id());assertEquals(1,api.list(null,null,null).articles().size());
    }
    @Test void failedPublicationLeavesDraftIsolated() {
        var store=new MemoryStore();var a=Fixtures.generated(store);var editor=new Editorial(store,Fixtures.CLOCK);editor.approve(a.id(),"Test reviewer",false);store.failPublish=true;
        assertThrows(IllegalStateException.class,() -> editor.publish(a.id()));assertEquals(Status.REVIEWED,store.article(a.id()).value().status());assertNull(new PublicApi(store).detail(a.slug()));
        store.failPublish=false;editor.publish(a.id());assertNotNull(new PublicApi(store).detail(a.slug()));
    }
    @Test void editsInvalidateApprovalAndRequireRecheck() {
        var store=new MemoryStore();var a=Fixtures.generated(store);var editor=new Editorial(store,Fixtures.CLOCK);editor.approve(a.id(),"Test reviewer",false);
        var draft=new Draft(a.title(),a.perex(),a.category(),store.blob("articles",a.blobPath()),a.claims());editor.edit(a.id(),draft);
        assertEquals(Status.DRAFT,store.article(a.id()).value().status());assertNull(store.article(a.id()).value().approvedHash());assertThrows(IllegalStateException.class,() -> editor.approve(a.id(),"Test reviewer",false));
        editor.recheck(a.id(),new MockAi(),Fixtures.config());editor.approve(a.id(),"Test reviewer",false);editor.publish(a.id());
    }
    @Test void rejectionIsTerminalAndNeverPublic() {
        var store=new MemoryStore();var a=Fixtures.generated(store);var editor=new Editorial(store,Fixtures.CLOCK);editor.reject(a.id(),"Test reviewer");
        assertEquals(Status.REJECTED,store.article(a.id()).value().status());assertThrows(IllegalStateException.class,() -> editor.publish(a.id()));assertNull(new PublicApi(store).detail(a.slug()));
    }
    @Test void staleEditsAndInvalidInputsFail() {
        var store=new MemoryStore();var a=Fixtures.generated(store);var old=store.article(a.id());store.updateArticle(a,old.version());assertThrows(IllegalStateException.class,() -> store.updateArticle(a,old.version()));
        var api=new PublicApi(store);assertThrows(IllegalArgumentException.class,() -> api.list("100",null,null));assertThrows(IllegalArgumentException.class,() -> api.list(null,"' or status eq 'DRAFT",null));assertThrows(IllegalArgumentException.class,() -> api.detail("../drafts"));
    }
    @Test void sensitiveClaimsRequireAdditionalHumanAcknowledgement() {
        var store=new MemoryStore();var a=Fixtures.generated(store);var d=new Draft(a.title(),a.perex(),a.category(),store.blob("articles",a.blobPath()),a.claims());
        var v=new Verification(true,true,java.util.List.of());store.updateArticle(a.revise(d,a.blobPath(),a.contentHash(),v,Fixtures.CLOCK.instant()),store.article(a.id()).version());
        var editor=new Editorial(store,Fixtures.CLOCK);assertThrows(IllegalStateException.class,() -> editor.approve(a.id(),"Test reviewer",false));editor.approve(a.id(),"Test reviewer",true);
    }
}
