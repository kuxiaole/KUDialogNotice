package dev.kuxiaole.kudialognotice.listener;

import dev.kuxiaole.kudialognotice.KUDialogNoticePlugin;
import dev.kuxiaole.kudialognotice.flow.NoticeFlowService;
import dev.kuxiaole.kudialognotice.ui.NativeDialogAction;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.UUID;

public final class DialogClickListener implements Listener {
    private final KUDialogNoticePlugin plugin;
    private final NoticeFlowService flows;

    public DialogClickListener(KUDialogNoticePlugin plugin, NoticeFlowService flows) {
        this.plugin = plugin;
        this.flows = flows;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDialogClick(PlayerCustomClickEvent event) {
        NativeDialogAction action = NativeDialogAction.parse(event.getIdentifier()).orElse(null);
        if (action == null || !(event.getCommonConnection() instanceof PlayerGameConnection connection)) {
            return;
        }

        DialogResponseView response = event.getDialogResponseView();
        if (response == null) {
            return;
        }
        Boolean rulesRead;
        try {
            rulesRead = response.getBoolean("rules_read");
        } catch (RuntimeException exception) {
            return;
        }
        Player player = connection.getPlayer();
        UUID playerId = player.getUniqueId();
        plugin.scheduler().runAtEntity(
                player,
                () -> flows.handleNativeAction(
                        player, action.sessionToken(), action.action(), rulesRead),
                () -> flows.cleanup(playerId));
    }
}
