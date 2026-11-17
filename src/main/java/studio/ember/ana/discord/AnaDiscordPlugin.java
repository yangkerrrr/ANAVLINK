package studio.ember.ana.discord;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

@Plugin(id = "ana-discord", name = "A&A Discord", version = "0.2.0",
        description = "Discord chat link and network presence for Architecture & Automatum")
public final class AnaDiscordPlugin {
    private static final Pattern CONTROL_CODES = Pattern.compile("[\\p{Cntrl}]");
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "ana-discord-presence");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Config config;
    private volatile JDA jda;
    private volatile TextChannel channel;
    private final AtomicInteger presenceIndex = new AtomicInteger();

    @Inject
    public AnaDiscordPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onInitialize(com.velocitypowered.api.event.proxy.ProxyInitializeEvent event) {
        try {
            config = Config.load(dataDirectory.resolve("config.properties"));
        } catch (IOException exception) {
            logger.error("Could not load A&A Discord configuration", exception);
            return;
        }
        if (!config.isConfigured()) {
            logger.warn("Set discord.token and discord.channel-id in plugins/ana-discord/config.properties, then restart.");
            return;
        }

        worker.execute(() -> connect(config));
    }

    private void connect(Config settings) {
        try {
            EnumSet<GatewayIntent> intents = EnumSet.of(GatewayIntent.GUILD_MESSAGES);
            if (settings.discordToMinecraft()) intents.add(GatewayIntent.MESSAGE_CONTENT);
            JDA bot = JDABuilder.createDefault(settings.token(), intents)
                    .addEventListeners(new DiscordListener())
                    .build();
            jda = bot;
            bot.awaitReady();
            TextChannel target = bot.getTextChannelById(settings.channelId());
            if (target == null) {
                logger.error("Discord channel {} is unavailable; verify its ID and bot access.", settings.channelId());
                bot.shutdownNow();
                jda = null;
                return;
            }
            channel = target;
            logger.info("Connected chat bridge to Discord channel {}", target.getName());
            updatePresence();
            worker.scheduleAtFixedRate(this::updatePresence, settings.presenceIntervalSeconds(),
                    settings.presenceIntervalSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logger.error("Interrupted while connecting to Discord", exception);
        } catch (RuntimeException exception) {
            logger.error("Could not connect A&A Discord bot", exception);
        }
    }

    @Subscribe
    public void onMinecraftChat(PlayerChatEvent event) {
        Config settings = config;
        TextChannel target = channel;
        if (settings == null || target == null || !settings.minecraftToDiscord()
                || !event.getResult().isAllowed()) return;
        String message = limit(event.getMessage().trim(), settings.maxMessageLength());
        if (message.isEmpty() || (settings.ignoreCommands() && message.startsWith("/"))) return;
        String player = event.getPlayer().getUsername();
        String formatted = settings.discordMessageFormat().replace("{player}", markdown(player))
                .replace("{message}", markdown(message)).replace("{server}", markdown(settings.serverName()));
        var action = target.sendMessage(formatted);
        if (settings.disableDiscordMentions()) action.setAllowedMentions(List.of());
        action.queue(null, error -> logger.warn("Could not relay Minecraft chat: {}", error.getMessage()));
    }

    @Subscribe
    public void onPlayerJoin(PostLoginEvent event) {
        sendConnectionNotice(event.getPlayer().getUsername(), true);
    }

    @Subscribe
    public void onPlayerLeave(DisconnectEvent event) {
        sendConnectionNotice(event.getPlayer().getUsername(), false);
    }

    private void sendConnectionNotice(String username, boolean joined) {
        Config settings = config;
        TextChannel target = channel;
        if (settings == null || target == null || !settings.joinLeaveMessages()) return;
        String word = joined ? "joined" : "left";
        target.sendMessage("**" + markdown(username) + "** " + word + " the server.")
                .setAllowedMentions(List.of()).queue();
    }

    private void updatePresence() {
        Config settings = config;
        JDA bot = jda;
        if (settings == null || bot == null || bot.getStatus() != JDA.Status.CONNECTED) return;
        List<String> servers = settings.presenceServers();
        if (servers.isEmpty()) {
            bot.getPresence().setActivity(Activity.playing(settings.idlePresence()));
            return;
        }
        String key = servers.get(Math.floorMod(presenceIndex.getAndIncrement(), servers.size()));
        RegisteredServer server = proxy.getServer(key).orElse(null);
        if (server == null) {
            logger.warn("Presence server '{}' is not registered in Velocity", key);
            return;
        }
        int online = server.getPlayersConnected().size();
        int networkOnline = proxy.getPlayerCount();
        String label = settings.serverLabels().getOrDefault(key, key);
        String maxPlayers = settings.serverMaxPlayers().getOrDefault(key, "?");
        String status = settings.presenceFormat()
                .replace("{server}", label)
                .replace("{online}", Integer.toString(online))
                .replace("{max}", maxPlayers)
                .replace("{network-online}", Integer.toString(networkOnline));
        Activity activity = switch (settings.presenceType()) {
            case "WATCHING" -> Activity.watching(status);
            case "LISTENING" -> Activity.listening(status);
            default -> Activity.playing(status);
        };
        bot.getPresence().setActivity(activity);
    }

    @Subscribe
    public void onShutdown(com.velocitypowered.api.event.proxy.ProxyShutdownEvent event) {
        worker.shutdownNow();
        JDA bot = jda;
        if (bot != null) bot.shutdownNow();
    }

    private final class DiscordListener extends ListenerAdapter {
        @Override
        public void onMessageReceived(MessageReceivedEvent event) {
            Config settings = config;
            if (settings == null || !settings.discordToMinecraft() || !event.isFromGuild()
                    || !event.getChannel().getId().equals(settings.channelId())) return;
            if (settings.ignoreBots() && event.getAuthor().isBot()) return;
            String content = limit(event.getMessage().getContentDisplay().trim(), settings.maxMessageLength());
            if (content.isEmpty()) return;
            String author = event.getMember() == null ? event.getAuthor().getName()
                    : event.getMember().getEffectiveName();
            if (settings.ignoreCommands() && content.startsWith("/")) return;
            String output = settings.minecraftMessageFormat()
                    .replace("{player}", minecraftText(author))
                    .replace("{message}", minecraftText(content))
                    .replace("{server}", minecraftText(settings.serverName()));
            proxy.getScheduler().buildTask(AnaDiscordPlugin.this,
                    () -> proxy.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(output)))
                    .schedule();
        }
    }

    private static String limit(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    private static String markdown(String text) {
        return text.replace("\\", "\\\\").replace("*", "\\*").replace("_", "\\_")
                .replace("`", "\\`").replace("~", "\\~").replace("@", "@\u200B");
    }

    private static String minecraftText(String text) {
        return CONTROL_CODES.matcher(text).replaceAll(" ").replace("<", "‹").replace(">", "›")
                .replace("@everyone", "@ everyone").replace("@here", "@ here").trim();
    }
}
