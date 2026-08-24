package ra1nyxin.mcchatdeepseekai2api;

import org.bukkit.configuration.ConfigurationSection;

import java.net.URI;
import java.time.Duration;

record PluginSettings(
        URI apiUrl,
        String apiKey,
        String model,
        String triggerPrefix,
        String aiName,
        int historyMessageCount,
        int maxContextTokens,
        int maxOutputTokens,
        int maxResponseCharacters,
        int maxChatLineCharacters,
        Duration requestTimeout,
        int maxQueueSize,
        boolean sendReasoningToChat,
        String systemPrompt) {

    private static final int MAX_HISTORY_MESSAGES = 500;

    static PluginSettings from(ConfigurationSection config) {
        String url = requiredText(config, "api-url", 2048);
        URI apiUrl;
        try {
            apiUrl = URI.create(url);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("api-url 不是有效的网址", exception);
        }
        if (!"http".equalsIgnoreCase(apiUrl.getScheme())
                && !"https".equalsIgnoreCase(apiUrl.getScheme())) {
            throw new IllegalArgumentException("api-url 必须使用 http 或 https");
        }

        int timeoutSeconds = requiredInt(config, "request-timeout-seconds", 5, 600);
        int maxContextTokens = requiredInt(config, "max-context-tokens", 1024, 524_288);
        int maxOutputTokens = requiredInt(
                config, "max-output-tokens", 1, Math.max(1, maxContextTokens - 1_024));
        return new PluginSettings(
                apiUrl,
                config.getString("api-key", "").strip(),
                requiredText(config, "model", 128),
                requiredText(config, "trigger-prefix", 32),
                requiredText(config, "ai-name", 32),
                requiredInt(config, "history-message-count", 1, MAX_HISTORY_MESSAGES),
                maxContextTokens,
                maxOutputTokens,
                requiredInt(config, "max-response-characters", 1, 131_072),
                requiredInt(config, "max-chat-line-characters", 32, 512),
                Duration.ofSeconds(timeoutSeconds),
                requiredInt(config, "max-queue-size", 1, 1_024),
                config.getBoolean("send-reasoning-to-chat", false),
                requiredText(config, "system-prompt", 16_384));
    }

    boolean hasApiKey() {
        return !apiKey.isEmpty();
    }

    private static String requiredText(ConfigurationSection config, String path, int maximumLength) {
        String value = config.getString(path);
        if (value == null || value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(
                    path + " 必须是长度为 1 到 " + maximumLength + " 的非空文本");
        }
        return value.strip();
    }

    private static int requiredInt(
            ConfigurationSection config, String path, int minimum, int maximum) {
        if (!config.isInt(path)) {
            throw new IllegalArgumentException(path + " 必须是整数");
        }
        int value = config.getInt(path);
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    path + " 必须在 " + minimum + " 到 " + maximum + " 之间");
        }
        return value;
    }
}
