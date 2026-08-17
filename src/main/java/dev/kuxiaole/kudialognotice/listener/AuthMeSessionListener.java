package dev.kuxiaole.kudialognotice.listener;

import dev.kuxiaole.kudialognotice.client.ClientProtocolResolver;
import dev.kuxiaole.kudialognotice.flow.NoticeFlowService;
import fr.xephi.authme.events.LoginEvent;
import fr.xephi.authme.events.LogoutEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class AuthMeSessionListener implements Listener {
    private final NoticeFlowService flows;
    private final ClientProtocolResolver protocols;

    public AuthMeSessionListener(NoticeFlowService flows, ClientProtocolResolver protocols) {
        this.flows = flows;
        this.protocols = protocols;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAuthMeLogin(LoginEvent event) {
        flows.onAuthenticated(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAuthMeLogout(LogoutEvent event) {
        flows.cleanup(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        flows.cleanup(event.getPlayer().getUniqueId());
        protocols.cleanup(event.getPlayer().getUniqueId());
    }
}
