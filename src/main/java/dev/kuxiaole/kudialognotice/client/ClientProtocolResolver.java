package dev.kuxiaole.kudialognotice.client;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class ClientProtocolResolver implements PluginMessageListener {
    public static final String VIA_PROXY_DETAILS_CHANNEL = "vv:proxy_details";

    private static final int MAX_PAYLOAD_BYTES = 4096;

    private final Plugin plugin;
    private final ConcurrentHashMap<UUID, Integer> proxyProtocols = new ConcurrentHashMap<>();
    private final ProtocolLookup backendLookup;
    private final AtomicBoolean backendLookupDisabled = new AtomicBoolean();

    public ClientProtocolResolver(Plugin plugin) {
        this.plugin = plugin;
        backendLookup = createBackendLookup(plugin);
    }

    public int protocolVersion(Player player) {
        Integer proxyProtocol = proxyProtocols.get(player.getUniqueId());
        if (proxyProtocol != null) {
            return proxyProtocol;
        }
        if (!backendLookupDisabled.get()) {
            try {
                return backendLookup.protocolVersion(player);
            } catch (LinkageError error) {
                if (backendLookupDisabled.compareAndSet(false, true)) {
                    plugin.getLogger().log(Level.WARNING,
                            "ViaVersion API became incompatible; using Paper protocol values", error);
                }
            } catch (RuntimeException exception) {
                plugin.getLogger().log(Level.FINE,
                        "Cannot query ViaVersion protocol; using Paper value", exception);
            }
        }
        return player.getProtocolVersion();
    }

    public void cleanup(UUID uniqueId) {
        proxyProtocols.remove(uniqueId);
    }

    @Override
    public void onPluginMessageReceived(
            @NotNull String channel,
            @NotNull Player player,
            byte @NotNull [] message
    ) {
        if (!VIA_PROXY_DETAILS_CHANNEL.equals(channel) || message.length == 0
                || message.length > MAX_PAYLOAD_BYTES) {
            return;
        }
        OptionalInt protocol = ViaProxyDetailsParser.parsePlayerProtocol(message);
        protocol.ifPresent(value -> proxyProtocols.put(player.getUniqueId(), value));
    }

    private static ProtocolLookup createBackendLookup(Plugin plugin) {
        if (plugin.getServer().getPluginManager().isPluginEnabled("ViaVersion")) {
            try {
                return new ViaVersionProtocolLookup();
            } catch (LinkageError error) {
                plugin.getLogger().log(Level.WARNING,
                        "ViaVersion is installed but its API is incompatible; using Paper protocol values", error);
            }
        }
        return Player::getProtocolVersion;
    }
}
