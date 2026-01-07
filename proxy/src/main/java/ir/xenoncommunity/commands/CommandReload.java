package ir.xenoncommunity.commands;

import ir.xenoncommunity.XenonCore;
import ir.xenoncommunity.utils.Language;
import net.md_5.bungee.BungeeCord;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.event.ProxyReloadEvent;
import net.md_5.bungee.api.plugin.Command;

public class CommandReload extends Command {

    public CommandReload() {
        super("greload", "bungeecord.command.reload");
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission(XenonCore.instance.getConfigData().getReload_permission())) {
            sender.sendMessage(TextComponent.fromLegacyText(Language.get("no_permission")));
            return;
        }

        long start = System.currentTimeMillis();
        sender.sendMessage(TextComponent.fromLegacyText(XenonCore.instance.getConfigData().getReload_message()));
        
        XenonCore.instance.setConfigData(XenonCore.instance.getConfiguration().init());
        Language.reload();
        
        sender.sendMessage(TextComponent.fromLegacyText(Language.get("reload_success")));
    }
}
