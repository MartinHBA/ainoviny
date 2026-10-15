package sk.verejnydohlad;

import com.microsoft.azure.functions.*;
import com.microsoft.azure.functions.annotation.*;
import java.util.*;
import java.util.function.Supplier;

public final class Functions {
    @FunctionName("Articles")
    public HttpResponseMessage list(@HttpTrigger(name="request",methods={HttpMethod.GET},authLevel=AuthorizationLevel.ANONYMOUS,route="articles") HttpRequestMessage<Optional<String>> request,ExecutionContext context) {
        return response(request,context,() -> { var q=request.getQueryParameters(); return new PublicApi(App.instance().store).list(q.get("pageSize"),q.get("cursor"),q.get("category")); });
    }
    @FunctionName("Article")
    public HttpResponseMessage detail(@HttpTrigger(name="request",methods={HttpMethod.GET},authLevel=AuthorizationLevel.ANONYMOUS,route="articles/{slug}") HttpRequestMessage<Optional<String>> request,@BindingName("slug") String slug,ExecutionContext context) {
        return response(request,context,() -> new PublicApi(App.instance().store).detail(slug));
    }
    @FunctionName("LatestEdition")
    public HttpResponseMessage latest(@HttpTrigger(name="request",methods={HttpMethod.GET},authLevel=AuthorizationLevel.ANONYMOUS,route="editions/latest") HttpRequestMessage<Optional<String>> request,ExecutionContext context) {
        return response(request,context,() -> new PublicApi(App.instance().store).latest());
    }
    @FunctionName("MorningEdition")
    public void timer(@TimerTrigger(name="timer",schedule="%DAILY_SCHEDULE%") String timer,ExecutionContext context) {
        execute(RunScope.scheduled(),context);
    }
    @FunctionName("PrepareEdition")
    public void prepare(@TimerTrigger(name="timer",schedule="0 0 9,15,21 * * *") String timer,ExecutionContext context) {
        var app=App.instance();if(!app.config.newsroomFlow())return;
        var result=new Newsroom(app.store,app.ai,app.config,java.time.Clock.systemUTC(),app.config.aiMode().equals("azure")?new OfficialResearch():ResearchLookup.archiveOnly()).run(true,RunScope.scheduled(),true);
        context.getLogger().info(Json.write(Map.of("mode","PREPARE","result",result)));
    }
    @FunctionName("AdHocEdition")
    public void manual(@QueueTrigger(name="input",queueName="editorial-adhoc",connection="AzureWebJobsStorage") String input,ExecutionContext context) {
        execute(RunScope.manualInput(input),context);
    }
    private void execute(RunScope scope,ExecutionContext context) {
        context.getLogger().info(Json.write(Map.of("event","pipeline-started","scope",scope.label())));
        var result=App.instance().pipeline.run(true,scope);context.getLogger().info(Json.write(Map.of("scope",scope.label(),"result",result)));
    }
    private HttpResponseMessage response(HttpRequestMessage<?> request,ExecutionContext context,Supplier<Object> action) {
        try {
            Object body=action.get();
            return request.createResponseBuilder(body==null?HttpStatus.NOT_FOUND:HttpStatus.OK).header("Content-Type","application/json; charset=utf-8")
                .header("Cache-Control","public, max-age=30").header("X-Content-Type-Options","nosniff").body(Json.write(body==null?Map.of("error","Article not found"):body)).build();
        } catch(IllegalArgumentException e) {
            return request.createResponseBuilder(HttpStatus.BAD_REQUEST).header("Content-Type","application/json").header("Cache-Control","no-store").body(Json.write(Map.of("error","Invalid request"))).build();
        } catch(Exception e) {
            context.getLogger().severe("Public API failed: "+e.getClass().getSimpleName());
            return request.createResponseBuilder(HttpStatus.SERVICE_UNAVAILABLE).header("Content-Type","application/json").header("Cache-Control","no-store").body(Json.write(Map.of("error","Service temporarily unavailable"))).build();
        }
    }
}
