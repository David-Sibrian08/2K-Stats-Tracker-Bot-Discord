package com.playnow.player;

import com.playnow.db.Db;
import com.playnow.security.AccessControl;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class PlayerListener extends ListenerAdapter {

    private final Db db;

    public PlayerListener(Db db) {
        this.db = db;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!"player".equals(event.getName())) return;
        if (!"add".equals(event.getSubcommandName())) return;

        String raw = optString(event, "gamertag");
        if (raw == null) {
            event.reply("Missing required option: gamertag").setEphemeral(true).queue();
            return;
        }

        if (!AccessControl.allowWrite(event)) return;

        String gamertag = normalizeGamertag(raw);
        if (gamertag.isBlank()) {
            event.reply("Gamertag cannot be blank.").setEphemeral(true).queue();
            return;
        }

        try {
            long id = insertPlayerIfNotExists(gamertag);
            if (id == -1) {
                event.reply("`" + gamertag + "` already exists").setEphemeral(true).queue();
            } else {
                event.reply("Added player `" + gamertag + "` (id=" + id + ")").setEphemeral(true).queue();
            }
        } catch (Exception e) {
            event.reply("Failed to add player: " + e.getMessage()).setEphemeral(true).queue();
        }
    }

    private static String optString(SlashCommandInteractionEvent event, String name) {
        var opt = event.getOption(name);
        return opt == null ? null : opt.getAsString();
    }

    private static String normalizeGamertag(String raw) {
        return raw == null ? "" : raw.trim();
    }

    /**
     * @return new player ID if inserted, or -1 if already exists
     */
    private long insertPlayerIfNotExists(String gamertag) throws Exception {
        try (Connection c = db.open()) {

            // Case-insensitive existence check
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM players WHERE LOWER(gamertag) = LOWER(?)"
            )) {
                ps.setString(1, gamertag);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return -1;
                }
            }

            // Insert
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO players (gamertag) VALUES (?)",
                    PreparedStatement.RETURN_GENERATED_KEYS
            )) {
                ps.setString(1, gamertag);
                ps.executeUpdate();

                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) return keys.getLong(1);
                }
            }
        }

        throw new IllegalStateException("Insert succeeded but no ID returned");
    }
}