package dev.kuxiaole.kudialognotice.client;

import com.viaversion.viaversion.api.Via;
import org.bukkit.entity.Player;

final class ViaVersionProtocolLookup implements ProtocolLookup {
    @Override
    public int protocolVersion(Player player) {
        return Via.getAPI().getPlayerProtocolVersion(player.getUniqueId()).getOriginalVersion();
    }
}

