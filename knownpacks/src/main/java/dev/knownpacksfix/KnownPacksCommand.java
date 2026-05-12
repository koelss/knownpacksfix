package dev.knownpacksfix;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;

public class KnownPacksCommand implements SimpleCommand {

    private final KnownPacksFixPlugin plugin;

    public KnownPacksCommand(KnownPacksFixPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();

        if (args.length == 0) {
            sendHelp(source);
            return;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> plugin.reloadPlugin(source);
            case "status" -> plugin.sendStatus(source);
            default -> sendHelp(source);
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (invocation.arguments().length <= 1) {
            return List.of("reload", "status");
        }
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("knownpacksfix.admin");
    }

    private void sendHelp(CommandSource source) {
        source.sendMessage(Component.text("[KnownPacksFix] Commands:", NamedTextColor.GOLD));
        source.sendMessage(Component.text("  /knownpacksfix reload", NamedTextColor.YELLOW)
            .append(Component.text(" — Reload config and re-apply patch", NamedTextColor.GRAY)));
        source.sendMessage(Component.text("  /knownpacksfix status", NamedTextColor.YELLOW)
            .append(Component.text(" — Show current patch status", NamedTextColor.GRAY)));
    }
}
