package sk.verejnydohlad;

import java.time.*;
import java.util.*;
import sk.verejnydohlad.Models.*;

/** Ad hoc means a private replacement cycle; only its completed, checked batch becomes public. */
final class Reedition {
    final Store store;final Ai ai;final Config config;final Clock clock;final ResearchLookup lookup;
    final Newsroom.Discovery discovery;final PublicationCheck freshness;
    Reedition(Store s,Ai ai,Config c,Clock clock){this(s,ai,c,clock,c.aiMode().equals("azure")?new OfficialResearch():ResearchLookup.archiveOnly(),
        c.aiMode().equals("azure")?new OfficialDiscovery():(st,g,d)->new Newsroom.Discovery.Result(0,0,List.of()),c.aiMode().equals("azure")?PublicationCheck.official():PublicationCheck.offline());}
    Reedition(Store s,Ai ai,Config c,Clock clock,ResearchLookup lookup,Newsroom.Discovery discovery,PublicationCheck freshness){this.store=s;this.ai=ai;this.config=c;this.clock=clock;this.lookup=lookup;this.discovery=discovery;this.freshness=freshness;}
    RunResult run(boolean collect,RunScope scope,boolean prepare){
        Instant started=clock.instant();
        if(scope.manual() && (!config.newsroomFlow() || !config.autoPublish() || prepare))throw new IllegalStateException("Reedition requires automatic newsroom publication");
        try(var guard=store.lock()) {
            String date=RunScope.date(clock);EditionRevision revision=scope.manual()?store.revision(scope.id()):store.activeRevision(date);
            if(scope.manual()) {
                if(revision!=null && revision.state().equals("ACTIVE"))return new RunResult(started,clock.instant(),0,0,0,0,List.of());
                if(revision!=null && (!revision.date().equals(date) || revision.state().equals("SUPERSEDED")))throw new IllegalStateException("Reedition is no longer current");
                var active=store.activeRevision(date);String previous=active==null?null:active.id();
                if(revision==null)revision=new EditionRevision(scope.id(),date,"BUILDING",previous,started,null,List.of(),List.of());
                else if(!Objects.equals(previous,revision.previousId()))throw new IllegalStateException("Another reedition has already replaced this edition");
                store.revision(new EditionRevision(revision.id(),revision.date(),"BUILDING",revision.previousId(),revision.startedAt(),null,revision.articleIds(),revision.errors()));
                // A resumed private batch must pass the current publication gate again. In the same
                // 15-minute slot its checkpoints are reusable; later retries refetch and reapprove.
                for(var a:store.editorial())if(revision.id().equals(a.editionRevision()) && a.status()==Status.PUBLISHED) {
                    guard.check();var row=store.article(a.id());store.updateArticle(a.state(Status.REVIEWED,a.reviewedAt(),a.reviewer(),a.approvedHash(),null,a.blobPath()),row.version());
                }
            }
            var work=new EditionWorkStore(store,revision,guard,scope.manual());
            var result=config.newsroomFlow()?new Newsroom(work,ai,config,clock,lookup,discovery,freshness).run(collect,scope,prepare):new Pipeline(work,ai,config,clock,lookup,scope).run(collect);
            if(scope.manual()) {
                try {
                    guard.check();
                    var ids=work.editorial().stream().filter(a->work.revisionId().equals(a.editionRevision()) && a.status()==Status.PUBLISHED).sorted(Comparator.comparingInt(Article::editorialOrder)).map(Article::id).toList();
                    var done=new EditionRevision(revision.id(),revision.date(),ids.isEmpty()?"NO_APPROVED_ARTICLES":"ACTIVE",revision.previousId(),revision.startedAt(),clock.instant(),ids,result.errors());
                    if(!RunScope.date(clock).equals(revision.date()))throw new IllegalStateException("Reedition crossed local midnight; original edition retained");
                    if(ids.isEmpty())store.revision(done);else store.activate(done);
                }catch(Exception e) {
                    guard.check();var committed=store.activeRevision(revision.date());
                    if(committed!=null && committed.id().equals(revision.id()))return result; // Lost response after the atomic commit.
                    store.revision(new EditionRevision(revision.id(),revision.date(),"FAILED",revision.previousId(),revision.startedAt(),clock.instant(),List.of(),List.of("Edition activation failed: "+e.getClass().getSimpleName())));
                    throw e;
                }
            }
            return result;
        }catch(Exception e){
            var result=new RunResult(started,clock.instant(),0,0,0,0,List.of("reedition: "+e.getClass().getSimpleName()));
            store.run(result,scope.label());return result;
        }
    }
    record Activation(List<Store.Versioned<Article>> fresh,List<Store.Versioned<Article>> old,List<Store.Versioned<Article>> cancelled){}
    static Activation activation(Store store,EditionRevision revision){
        if(!revision.state().equals("ACTIVE") || revision.articleIds().isEmpty() || revision.articleIds().size()>3 || revision.articleIds().stream().distinct().count()!=revision.articleIds().size())throw new IllegalStateException("Invalid replacement edition");
        var active=store.activeRevision(revision.date());
        if(!Objects.equals(active==null?null:active.id(),revision.previousId()))throw new IllegalStateException("Replacement edition changed");
        var fresh=revision.articleIds().stream().map(id->{
            var row=store.article(id);if(row==null)throw new IllegalStateException("Missing replacement article");var a=row.value();
            if(!revision.id().equals(a.editionRevision()) || !revision.date().equals(a.editionDate()) || a.status()!=Status.PUBLISHED || !a.factCheckPassed() || a.sensitive() || a.reviewedAt()==null ||
                !AutomaticEditor.isAutomatic(a) || !Editorial.approvalHash(a,store.blob("articles",a.blobPath())).equals(a.approvedHash()))throw new IllegalStateException("Unchecked replacement article");
            return new Store.Versioned<>(a.state(Status.PUBLISHED,a.reviewedAt(),a.reviewer(),a.approvedHash(),revision.completedAt(),a.blobPath()),row.version());
        }).toList();
        var old=new ArrayList<Store.Versioned<Article>>();var cancelled=new ArrayList<Store.Versioned<Article>>();
        for(var a:store.editorial())if(!revision.id().equals(a.editionRevision())) {
            boolean today=revision.date().equals(a.editionDate());
            if(today && a.status()==Status.PUBLISHED && store.bySlug(a.slug())!=null)old.add(store.article(a.id()));
            // Legacy reserve had no target edition identity and could have been prepared days ago.
            // Retire that entire private pool on the first replacement, so tomorrow cannot revive it.
            else if((today || revision.previousId()==null && a.editionRevision()==null) && Set.of(Status.DRAFT,Status.REVIEWED).contains(a.status()) ||
                today && a.status()==Status.PUBLISHED)cancelled.add(store.article(a.id()));
        }
        return new Activation(fresh,List.copyOf(old),List.copyOf(cancelled));
    }
}
