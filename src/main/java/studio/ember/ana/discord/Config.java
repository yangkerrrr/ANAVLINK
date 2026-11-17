package studio.ember.ana.discord;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.HashMap;

record Config(
        String token,
        String channelId,
        String serverName,
        boolean minecraftToDiscord,
        boolean discordToMinecraft,
        boolean joinLeaveMessages,
        boolean ignoreBots,
        boolean ignoreCommands,
        boolean disableDiscordMentions,
        String discordMessageFormat,
        String minecraftMessageFormat,
        int maxMessageLength,
        List<String> presenceServers,
        String presenceType,
        int presenceIntervalSeconds,
        String presenceFormat,
        String idlePresence,
        Map<String, String> serverLabels,
        Map<String, String> serverMaxPlayers
) {
    static Config load(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        if (Files.notExists(path)) writeDefaults(path);

        Properties p = new Properties();
        try (InputStream input = Files.newInputStream(path);
             InputStreamReader reader = new InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8)) {
            p.load(reader);
        }

        return new Config(
                p.getProperty("discord.token", "").trim(),
                p.getProperty("discord.channel-id", "").trim(),
                p.getProperty("server.name", "Architecture & Automatum").trim(),
                bool(p, "bridge.minecraft-to-discord", true),
                bool(p, "bridge.discord-to-minecraft", true),
                bool(p, "bridge.join-leave-messages", false),
                bool(p, "quality.ignore-bots", true),
                bool(p, "quality.ignore-commands", true),
                bool(p, "quality.disable-discord-mentions", true),
                p.getProperty("format.discord-message", "**{player}**: {message}"),
                p.getProperty("format.minecraft-message", "<gold>[Discord]</gold> <white>{player}: {message}"),
                integer(p, "quality.max-message-length", 500, 1, 2000),
                Arrays.stream(p.getProperty("presence.servers", "main,creative").split(","))
                        .map(String::trim).filter(s -> !s.isBlank()).toList(),
                p.getProperty("presence.activity", "PLAYING").trim().toUpperCase(),
                integer(p, "presence.interval-seconds", 15, 5, 3600),
                p.getProperty("presence.format", "{server} • {online} online"),
                p.getProperty("presence.idle", "Create with A&A").trim(),
                valuesWithPrefix(p, "presence.label."),
                valuesWithPrefix(p, "presence.max-players.")
        );
    }

    private static boolean bool(Properties p, String key, boolean fallback) {
        return Boolean.parseBoolean(p.getProperty(key, Boolean.toString(fallback)).trim());
    }

    private static int integer(Properties p, String key, int fallback, int min, int max) {
        try {
            return Math.max(min, Math.min(max,
                    Integer.parseInt(p.getProperty(key, Integer.toString(fallback)).trim())));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static Map<String, String> valuesWithPrefix(Properties p, String prefix) {
        Map<String, String> values = new HashMap<>();
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith(prefix)) values.put(key.substring(prefix.length()), p.getProperty(key).trim());
        }
        return Map.copyOf(values);
    }

    private static void writeDefaults(Path path) throws IOException {
        String defaults = """
                # Architecture & Automatum Velocity Discord bridge
                # Keep this file private. The Discord bot token grants access to your bot.
                discord.token=PASTE_BOT_TOKEN_HERE
                discord.channel-id=PASTE_TEXT_CHANNEL_ID_HERE

                # Identity
                server.name=Architecture & Automatum

                # Chat bridge
                bridge.minecraft-to-discord=true
                bridge.discord-to-minecraft=true
                bridge.join-leave-messages=false
                format.discord-message=**{player}**: {message}
                format.minecraft-message=<gold>[Discord]</gold> <white>{player}: {message}

                # Quality of life and safety
                quality.ignore-bots=true
                quality.ignore-commands=true
                quality.disable-discord-mentions=true
                quality.max-message-length=500

                # Presence rotates through these keys from the Velocity [servers] section.
                # Each status shows that server's current online player count.
                presence.servers=main,creative
                # Optional display names and capacities. Names default to Velocity keys.
                presence.label.main=Main
                presence.label.creative=Creative
                presence.max-players.main=100
                presence.max-players.creative=100
                presence.activity=PLAYING
                presence.interval-seconds=15
                # Available placeholders: {server}, {online}, {max}, {network-online}
                presence.format={server} • {online} online
                presence.idle=Create with A&A
                """;
        try (OutputStream output = Files.newOutputStream(path)) {
            output.write(defaults.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    boolean isConfigured() {
        return !token.isBlank() && !token.equals("PASTE_BOT_TOKEN_HERE")
                && channelId.matches("\\d{17,20}");
    }
}
