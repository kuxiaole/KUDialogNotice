package dev.kuxiaole.kudialognotice.client;

import org.bukkit.entity.Player;

@FunctionalInterface
interface ProtocolLookup {
    int protocolVersion(Player player);
}

