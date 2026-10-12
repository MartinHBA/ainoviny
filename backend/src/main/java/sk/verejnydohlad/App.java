package sk.verejnydohlad;

import java.time.Clock;

public final class App {
    private static final class Holder { static final App INSTANCE=new App(); }
    public final Config config=Config.environment();
    public final Store store=new AzureStore(config);
    public final Ai ai=config.aiMode().equals("mock")?new MockAi():new AzureAi(config);
    public final Pipeline pipeline=new Pipeline(store,ai,config,Clock.systemUTC());
    public static App instance() { return Holder.INSTANCE; }
    private App() {}
}
