package com.playnow.security;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

/**
 * Central place for command access rules.
 *
 * Environment variables:
 *  - ADMIN_USER_ID: Discord user id allowed to run write commands
 *  - TRACKER_CHANNEL_ID: channel id where write commands are allowed
 *
 * If either variable is missing/blank, that check is skipped (defaults to allow).
 */
public final class AccessControl {

    private static final String ADMIN_USER_ID = System.getenv("ADMIN_USER_ID");
    private static final String TRACKER_CHANNEL_ID = System.getenv("TRACKER_CHANNEL_ID");

    private AccessControl() {}

    /** Use this at the top of WRITE command handlers. */
    public static boolean allowWrite(SlashCommandInteractionEvent event) {
        return checkAdmin(event) && checkChannel(event);
    }

    private static boolean checkAdmin(SlashCommandInteractionEvent event) {
        if (ADMIN_USER_ID == null || ADMIN_USER_ID.isBlank()) return true;

        String callerId = event.getUser().getId();
        if (!ADMIN_USER_ID.equals(callerId)) {
            deny(event, "Only the tracker admin can use this command.");
            return false;
        }
        return true;
    }

    private static boolean checkChannel(SlashCommandInteractionEvent event) {
        if (TRACKER_CHANNEL_ID == null || TRACKER_CHANNEL_ID.isBlank()) return true;

        String channelId = event.getChannel().getId();
        if (!TRACKER_CHANNEL_ID.equals(channelId)) {
            deny(event, "Use this command in the tracker channel.");
            return false;
        }
        return true;
    }

    private static void deny(SlashCommandInteractionEvent event, String message) {
        // If already acknowledged (deferred/replied), must edit the existing response.
        if (event.isAcknowledged()) {
            event.getHook().editOriginal("⛔ " + message).queue();
        } else {
            event.reply("⛔ " + message).setEphemeral(true).queue();
        }
    }
}