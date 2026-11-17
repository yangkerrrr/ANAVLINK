# Architecture & Automatum Discord Bridge

A Velocity plugin for a two-way Minecraft and Discord chat link. It also rotates the bot's activity through configured Velocity servers and displays each server's live player count.

The plugin runs on the proxy. With your current Velocity server keys (`main` and `creative`), both servers can share the Discord chat channel without installing a bridge mod on either NeoForge backend.

## Features

- Relays player chat to one Discord text channel.
- Broadcasts messages from that channel to players across the Velocity network.
- Rotates the bot's activity through configured servers, showing each server's current online count and configured capacity.
- Configurable message formats, bot filtering, command filtering, join/leave messages, mention suppression, activity type, and rotation interval.
- Creates a documented config file on first start.

## Requirements

- Velocity 3.4.x
- Java 21+
- Discord bot with **Message Content Intent** enabled in the Developer Portal
- Channel permissions: View Channel, Send Messages, Read Message History

## Build and install

Run `gradle build`, then copy `build/libs/ana-velocity-discord-0.2.0.jar` to Velocity's `plugins` directory. Start Velocity once to create `plugins/ana-discord/config.properties`, edit it, and restart.

Keep the config private because it contains your bot token. The bot does not need Administrator permission.

## Configuration

```properties
# Credentials
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

# Quality of life
quality.ignore-bots=true
quality.ignore-commands=true
quality.disable-discord-mentions=true
quality.max-message-length=500

# Bot activity rotation; keys must match Velocity's [servers] entries
presence.servers=main,creative
presence.activity=PLAYING
presence.interval-seconds=15
presence.label.main=Main
presence.label.creative=Creative
presence.max-players.main=100
presence.max-players.creative=100
presence.format={server} • {online}/{max} online
presence.idle=Create with A&A
```

Supported activity types are `PLAYING`, `WATCHING`, and `LISTENING`. Presence format placeholders are `{server}`, `{online}`, `{max}`, and `{network-online}`. Server labels and capacities use the Velocity server key as a suffix; add matching entries when changing `presence.servers`.

The generated config is the source of truth for all bridge and presence settings. Restart Velocity after editing it.
