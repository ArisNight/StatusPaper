package net.nightium.statusplugin;

import de.maxhenkel.configbuilder.ConfigBuilder;
import de.nightium.status.config.ServerConfig;
import io.netty.buffer.Unpooled;
import de.nightium.status.playerstate.PlayerState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class Status extends JavaPlugin implements Listener, PluginMessageListener {
    private final ConcurrentHashMap<UUID, PlayerState> states = new ConcurrentHashMap<>();;

    public static Logger log;
    public static Status PLUGIN;

    public static ServerConfig SERVER_CONFIG;

    @Override
    public void onEnable() {
        PLUGIN = this;
        log = PLUGIN.getLogger();
        SERVER_CONFIG = ConfigBuilder
                .builder(ServerConfig::new)
                .path(PLUGIN.getServer().getPluginsFolder().toPath().resolve("status-plugin").resolve("status-server.properties"))
                .build();

        Messenger msg = Bukkit.getMessenger();
        msg.registerOutgoingPluginChannel(this, "status:state");
        msg.registerIncomingPluginChannel(this, "status:state", this);
        msg.registerOutgoingPluginChannel(this, "status:states");
        super.onEnable();
    }

    @EventHandler
    public void onSleep(PlayerBedEnterEvent event){
        ServerPlayer player = MinecraftServer.getServer().getPlayerList().getPlayer(event.getPlayer().getUniqueId());
        assert player != null;
        List<ServerPlayer> noSleepPlayers = getNoSleepPlayers(player.server);

        if (noSleepPlayers.isEmpty()) {
            return;
        }

        player.connection.send(new ClientboundSetTitleTextPacket(Component.literal(Status.SERVER_CONFIG.noSleepTitle.get())));
        if (noSleepPlayers.size() > 1) {
            player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(Status.SERVER_CONFIG.noSleepMultipleSubtitle.get())));
        } else {
            player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(String.format(Status.SERVER_CONFIG.noSleepPlayerSubtitle.get(), noSleepPlayers.get(0).getDisplayName().getString()))));
        }
    }

    @EventHandler
    private void notifyPlayer(PlayerJoinEvent event) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        playerStatesToBytes(states, buf);
        byte[] dataBytes = new byte[buf.writerIndex()];
        buf.getBytes(0, dataBytes);
        event.getPlayer().sendPluginMessage(this, "status:states", dataBytes);
        broadcastState(this, new PlayerState(event.getPlayer().getUniqueId()));
    }

    @EventHandler
    private void removePlayer(PlayerQuitEvent event){
        states.remove(event.getPlayer().getUniqueId());
        broadcastState(this, new PlayerState(event.getPlayer().getUniqueId()));
    }

    private void broadcastState(JavaPlugin plugin, PlayerState state) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        state.toBytes(buf);
        byte[] dataBytes = new byte[buf.writerIndex()];
        buf.getBytes(0, dataBytes);
        plugin.getServer().getOnlinePlayers().forEach(player ->
                player.sendPluginMessage(Status.PLUGIN, "status:state", dataBytes)
        );
    }

    private List<ServerPlayer> getNoSleepPlayers(MinecraftServer server) {
        List<ServerPlayer> players = new ArrayList<>();
        for (Map.Entry<UUID, PlayerState> entry : states.entrySet()) {
            if (entry.getValue().isNoSleep()) {
                ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
                if (player != null) {
                    players.add(player);
                }
            }
        }
        return players;
    }

    private void playerStatesToBytes(ConcurrentHashMap<UUID, PlayerState> playerStates, FriendlyByteBuf buf){
        buf.writeInt(playerStates.size());
        for (Map.Entry<UUID, PlayerState> entry : playerStates.entrySet()) {
            entry.getValue().toBytes(buf);
        }
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
        if (channel.equals("status:state")) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.copiedBuffer(message));
            PlayerState state = PlayerState.fromBytes(buf);
            state.setPlayer(player.getUniqueId());
            states.put(player.getUniqueId(), state);
            broadcastState(this, state);
        }
    }
}
