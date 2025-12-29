package com.playnow.game;

import com.playnow.db.Db;
import com.playnow.security.AccessControl;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class GameFinalizeListener extends ListenerAdapter {

    private final Db db;

    public GameFinalizeListener(Db db) {
        this.db = db;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("game")) return;
        if (!"finalize".equals(event.getSubcommandName())) return;

        var idOpt = event.getOption("game_id");
        if (idOpt == null) {
            event.reply("Missing required option: game_id").setEphemeral(true).queue();
            return;
        }

        if (!AccessControl.allowWrite(event)) return;

        long gameId = idOpt.getAsLong();
        event.deferReply(true).queue();

        try {
            int updated = finalizeIfDraft(gameId);

            if (updated == 1) {
                event.getHook().editOriginal("Game #" + gameId + " is now **FINAL**.").queue();
                return;
            }

            // updated == 0 -> either not found OR already FINAL (or some other status)
            String status = getGameStatus(gameId);
            if (status == null) {
                event.getHook().editOriginal("Game #" + gameId + " not found.").queue();
            } else if ("FINAL".equalsIgnoreCase(status)) {
                event.getHook().editOriginal("Game #" + gameId + " is already **FINAL**.").queue();
            } else {
                event.getHook().editOriginal("Game #" + gameId + " is not DRAFT (current: " + status + ").").queue();
            }

        } catch (Exception e) {
            event.getHook().editOriginal("Failed to finalize game: " + e.getMessage()).queue();
        }
    }

    private int finalizeIfDraft(long gameId) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE games SET status = 'FINAL' WHERE id = ? AND UPPER(status) = 'DRAFT'"
             )) {
            ps.setLong(1, gameId);
            return ps.executeUpdate();
        }
    }

    private String getGameStatus(long gameId) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT status FROM games WHERE id = ?")) {
            ps.setLong(1, gameId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return rs.getString(1);
            }
        }
    }
}