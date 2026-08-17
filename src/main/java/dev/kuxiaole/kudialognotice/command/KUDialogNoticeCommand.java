package dev.kuxiaole.kudialognotice.command;

import dev.kuxiaole.kudialognotice.KUDialogNoticePlugin;
import dev.kuxiaole.kudialognotice.config.PluginConfiguration;
import dev.kuxiaole.kudialognotice.storage.PlayerStatus;
import dev.kuxiaole.kudialognotice.ui.UserAction;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class KUDialogNoticeCommand implements CommandExecutor, TabCompleter {
    private static final String ADMIN_PERMISSION = "kudialognotice.admin";

    private final KUDialogNoticePlugin plugin;

    public KUDialogNoticeCommand(KUDialogNoticePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (args.length == 3 && "_action".equals(args[0]) && sender instanceof Player player) {
            handleBookAction(player, args[1], args[2]);
            return true;
        }
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            reply(sender, message(plugin.configuration().messages().noPermission()));
            return true;
        }
        if (args.length == 0) {
            reply(sender, message(plugin.configuration().messages().invalidCommand()));
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> reload(sender);
            case "preview" -> preview(sender, args);
            case "status" -> status(sender, args);
            default -> reply(sender, message(plugin.configuration().messages().invalidCommand()));
        }
        return true;
    }

    private void handleBookAction(Player player, String tokenInput, String actionInput) {
        try {
            UUID token = UUID.fromString(tokenInput);
            UserAction action = UserAction.fromCommandValue(actionInput);
            if (action != null) {
                plugin.scheduler().runAtEntity(
                        player,
                        () -> plugin.flowService().handleBookAction(player, token, action),
                        () -> plugin.flowService().cleanup(player.getUniqueId()));
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid internal action tokens are intentionally ignored.
        }
    }

    private void reload(CommandSender sender) {
        plugin.reloadPlugin().whenComplete((ignored, throwable) -> {
            PluginConfiguration configuration = plugin.configuration();
            if (throwable == null) {
                reply(sender, message(configuration.messages().reloadSuccess()));
            } else {
                reply(sender, prefixed(plugin.textRenderer().render(
                        configuration.messages().reloadFailed(),
                        java.util.Map.of("error", Component.text(rootMessage(throwable))))));
            }
        });
    }

    private void preview(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || args.length != 2
                || !("rules".equalsIgnoreCase(args[1]) || "changelog".equalsIgnoreCase(args[1]))) {
            reply(sender, prefixed(Component.text("Usage: /kudialognotice preview <rules|changelog>")));
            return;
        }
        plugin.scheduler().runAtEntity(
                player,
                () -> plugin.flowService().startPreview(player, args[1]),
                () -> plugin.flowService().cleanup(player.getUniqueId()));
    }

    private void status(CommandSender sender, String[] args) {
        if (args.length != 2) {
            reply(sender, prefixed(Component.text("Usage: /kudialognotice status <player|uuid>")));
            return;
        }
        String streamId = plugin.configuration().main().serverId();
        plugin.stateStore().findStatus(args[1], streamId).whenComplete((status, throwable) -> {
            if (throwable != null) {
                reply(sender, prefixed(Component.text("Status query failed: " + rootMessage(throwable))));
                return;
            }
            if (status.isEmpty()) {
                reply(sender, message(plugin.configuration().messages().playerNotFound()));
                return;
            }
            PlayerStatus value = status.get();
            reply(sender, prefixed(Component.text(
                    value.player().name() + " (" + value.player().uniqueId() + ")"
                            + " rules=" + value.rulesAccepted()
                            + " " + streamId + "-revision=" + value.lastSeenRevision())));
        });
    }

    private Component message(String raw) {
        return prefixed(plugin.textRenderer().render(raw));
    }

    private Component prefixed(Component component) {
        return plugin.textRenderer().render(plugin.configuration().messages().prefix()).append(component);
    }

    private void reply(CommandSender sender, Component component) {
        if (sender instanceof Player player) {
            plugin.scheduler().runAtEntity(player, () -> player.sendMessage(component), () -> { });
        } else {
            plugin.scheduler().runGlobal(() -> sender.sendMessage(component));
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            return List.of();
        }
        if (args.length == 1) {
            return matching(args[0], List.of("reload", "preview", "status"));
        }
        if (args.length == 2 && "preview".equalsIgnoreCase(args[0])) {
            return matching(args[1], List.of("rules", "changelog"));
        }
        return List.of();
    }

    private static List<String> matching(String input, List<String> values) {
        String prefix = input.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.startsWith(prefix)).toList();
    }
}
