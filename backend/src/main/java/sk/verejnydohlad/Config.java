package sk.verejnydohlad;

import java.util.Map;

public record Config(String storageConnection, String accountName, String aiMode, String endpoint, String deployment,
                     String apiKey, int maxArticles, int maxPublished, int maxReleases, int maxCalls, int maxTokens, int aiTimeout,
                     int minScore, String origin, double inputPrice, double outputPrice,
                     boolean autoPublish, String editorDeployment, double editorInputPrice, double editorOutputPrice, boolean researchFlow,
                     boolean newsroomFlow,int readyTarget) {
    public static Config environment() { return from(System.getenv()); }
    public static Config from(Map<String, String> env) {
        var c = new Config(env.getOrDefault("APP_STORAGE_CONNECTION_STRING", ""), env.getOrDefault("STORAGE_ACCOUNT_NAME", ""),
            env.getOrDefault("AI_MODE", "mock"), env.getOrDefault("AZURE_OPENAI_ENDPOINT", ""),
            env.getOrDefault("AZURE_OPENAI_DEPLOYMENT", "gpt-4.1-mini"), env.getOrDefault("AZURE_OPENAI_API_KEY", ""),
            // Accept older deployed settings during rollout, but enforce the new six/three ceilings.
            Math.min(6,number(env, "MAX_ARTICLES_PER_DAY", 6, 0, 8)), Math.min(3,number(env, "MAX_PUBLISHED_ARTICLES_PER_DAY", 3, 0, 5)), number(env, "MAX_PRESS_RELEASES_PER_RUN", 40, 1, 50),
            number(env, "MAX_AI_CALLS_PER_DAY", 40, 0, 100), number(env, "MAX_TOKENS_PER_ARTICLE", 2200, 256, 4000),
            number(env, "AI_REQUEST_TIMEOUT", 45, 5, 60), number(env, "MIN_NEWS_SCORE", 65, 0, 100),
            env.getOrDefault("ALLOWED_ORIGIN", "http://localhost:5173"),
            price(env, "AI_INPUT_USD_PER_MILLION"), price(env, "AI_OUTPUT_USD_PER_MILLION"),
            flag(env, "AUTO_PUBLISH_ENABLED"), env.getOrDefault("AZURE_OPENAI_EDITOR_DEPLOYMENT", "gpt-4.1"),
            price(env, "EDITOR_INPUT_USD_PER_MILLION"), price(env, "EDITOR_OUTPUT_USD_PER_MILLION"),
            flag(env, "RESEARCH_FLOW_ENABLED", env.getOrDefault("AI_MODE","mock").equals("azure")),
            flag(env,"NEWSROOM_FLOW_ENABLED",env.getOrDefault("AI_MODE","mock").equals("azure")),number(env,"READY_ARTICLE_TARGET",9,3,12));
        if(c.newsroomFlow && !c.researchFlow)throw new IllegalArgumentException("Newsroom requires the research flow");
        if (!c.aiMode.equals("mock") && !c.aiMode.equals("azure")) throw new IllegalArgumentException("AI_MODE must be mock or azure");
        if (c.aiMode.equals("azure")) {
            var uri = java.net.URI.create(c.endpoint);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null)
                throw new IllegalArgumentException("Azure OpenAI endpoint must be HTTPS");
            if (!c.deployment.matches("[A-Za-z0-9._-]{1,128}")) throw new IllegalArgumentException("Invalid deployment name");
            if (!c.editorDeployment.matches("[A-Za-z0-9._-]{1,128}")) throw new IllegalArgumentException("Invalid editor deployment name");
            if (c.autoPublish && c.editorDeployment.equals(c.deployment)) throw new IllegalArgumentException("Automatic editor must use a separate stronger model deployment");
        }
        if (c.autoPublish && c.aiMode.equals("mock") && !c.storageConnection.equals("UseDevelopmentStorage=true"))
            throw new IllegalArgumentException("Automatic mock publication is allowed only in local Azurite");
        return c;
    }
    private static boolean flag(Map<String,String> env, String key) {
        return flag(env,key,false);
    }
    private static boolean flag(Map<String,String> env, String key, boolean fallback) {
        String value=env.getOrDefault(key,Boolean.toString(fallback));
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException(key+" must be true or false");
        return Boolean.parseBoolean(value);
    }
    private static int number(Map<String,String> env, String key, int fallback, int min, int max) {
        int n = Integer.parseInt(env.getOrDefault(key, "" + fallback));
        if (n < min || n > max) throw new IllegalArgumentException(key + " outside " + min + ".." + max);
        return n;
    }
    private static double price(Map<String,String> env, String key) {
        double n = Double.parseDouble(env.getOrDefault(key, "0"));
        if (!Double.isFinite(n) || n < 0) throw new IllegalArgumentException("Invalid " + key);
        return n;
    }
}
