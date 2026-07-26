package me.rimuru.ultraoptimize.utils;

import org.bukkit.command.CommandSender;

/**
 * Consistent chat formatting for all /uo command output. Centralizing this
 * avoids the per-command drift (missing prefixes, hardcoded box widths that
 * misalign with variable-length content) that plain sendMessage() calls
 * accumulate over time.
 */
public final class Msg {

    private static final String PREFIX = "§8[§6UltraOptimize§8]§r ";
    private static final String DIVIDER = "§8§m" + "-".repeat(46);

    private Msg() {
    }

    public static void send(CommandSender sender, String message) {
        sender.sendMessage(message);
    }

    public static void info(CommandSender sender, String message) {
        sender.sendMessage(PREFIX + "§f" + message);
    }

    public static void success(CommandSender sender, String message) {
        sender.sendMessage(PREFIX + "§a" + message);
    }

    public static void error(CommandSender sender, String message) {
        sender.sendMessage(PREFIX + "§c" + message);
    }

    public static void noPermission(CommandSender sender) {
        error(sender, "You don't have permission to use this command.");
    }

    public static void usage(CommandSender sender, String usage) {
        error(sender, "Usage: §f" + usage);
    }

    /** Divider / bold title / divider block, e.g. for "/uo stats" or "/uo info". */
    public static void header(CommandSender sender, String title) {
        sender.sendMessage(DIVIDER);
        sender.sendMessage("§6§l" + title);
        sender.sendMessage(DIVIDER);
    }

    /** Blank line + bold subheading, used to break a report into groups. */
    public static void section(CommandSender sender, String title) {
        sender.sendMessage("");
        sender.sendMessage("§6§l" + title);
    }

    public static void kv(CommandSender sender, String label, String value) {
        sender.sendMessage("§e" + label + "§8: §f" + value);
    }

    public static void kv(CommandSender sender, int indent, String label, String value) {
        sender.sendMessage(" ".repeat(indent) + "§7" + label + "§8: §f" + value);
    }

    public static String bool(boolean value) {
        return bool(value, "Enabled", "Disabled");
    }

    public static String bool(boolean value, String trueText, String falseText) {
        return value ? "§a" + trueText : "§c" + falseText;
    }
}
