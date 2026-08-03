package me.rimuru.ultraoptimize.utils;

import me.rimuru.ultraoptimize.UltraOptimize;

public class Logger {

    public static void info(String message) {
        log(LogLevel.INFO, message);
    }

    public static void warning(String message) {
        log(LogLevel.WARNING, message);
    }

    public static void severe(String message) {
        log(LogLevel.SEVERE, message);
    }

    public static void debug(String message) {
        log(LogLevel.DEBUG, message);
    }

    private static void log(LogLevel level, String message) {
        try {
            UltraOptimize plugin = UltraOptimize.getInstance();

            if (plugin != null) {
                java.util.logging.Logger logger = plugin.getLogger();

                switch (level) {
                    case INFO:
                        logger.info(message);
                        break;
                    case WARNING:
                        logger.warning(message);
                        break;
                    case SEVERE:
                        logger.severe(message);
                        break;
                    case DEBUG:
                        // Only log debug messages if configured
                        logger.fine(message);
                        break;
                }
            } else {
                // Fallback to console
                System.out.println("[UltraOptimize] " + level + ": " + message);
            }
        } catch (Exception e) {
            System.err.println("[UltraOptimize] Logging error: " + e.getMessage());
        }
    }

    private enum LogLevel {
        INFO, WARNING, SEVERE, DEBUG
    }
}