package ir.xenoncommunity.commands;

import ir.xenoncommunity.utils.Language;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.plugin.Command;

import java.util.Locale;

@SuppressWarnings({"unused", "deprecation"})
public class CommandAlert extends Command {

    public CommandAlert() {
        super("alert", "bungeecord.command.alert");
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ProxyServer.getInstance().getTranslation("message_needed"));
        } else {
            StringBuilder builder = new StringBuilder();
            if (args[0].toLowerCase(Locale.ROOT).startsWith("&h")) {
                // Remove &h
                args[0] = args[0].substring(2);
            } else {
                builder.append(Language.get("alert").replace("%message%", ""));
            }

            for (String s : args) {
                builder.append(ChatColor.translateAlternateColorCodes('&', s));
                builder.append(" ");
            }

            String message = builder.substring(0, builder.length() - 1);
            if (args[0].toLowerCase(Locale.ROOT).startsWith("&h")) {
                message = ChatColor.translateAlternateColorCodes('&', String.join(" ", args).substring(2));
            } else {
                message = Language.get("alert").replace("%message%", ChatColor.translateAlternateColorCodes('&', String.join(" ", args)));
            }

            ProxyServer.getInstance().broadcast(TextComponent.fromLegacyText(message));
        }
    }
}
