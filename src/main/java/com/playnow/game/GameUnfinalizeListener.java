package com.playnow.game;

import com.playnow.db.Db;
import com.playnow.security.AccessControl;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class GameUnfinalizeListener extends ListenerAdapter {

    private final Db db;

    public GameUnfinalizeListener(Db db) {
        this.db = db;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("game")) return;
        if (!"unfinalize".equals(event.getSubcommandName())) return;

        var idOpt = event.getOption("game_id");
        if (idOpt == null) {
            event.reply("Missing required option: game_id").setEphemeral(true).queue();
            return;
        }

        if (!AccessControl.allowWrite(event)) return;

        long gameId = idOpt.getAsLong();
        event.deferReply(true).queue();

        try {
            int updated = unfinalizeIfFinal(gameId);

            if (updated == 1) {
                event.getHook().editOriginal("Game #" + gameId + " is now **DRAFT** (unfinalized).").queue();
                return;
            }

            // updated == 0 -> either not found OR not FINAL; fetch status once to explain
            String status = getGameStatus(gameId);
            if (status == null) {
                event.getHook().editOriginal("Game #" + gameId + " not found.").queue();
            } else {
                event.getHook().editOriginal("Game #" + gameId + " is not FINAL (current: " + status + ").").queue();
            }

        } catch (Exception e) {
            event.getHook().editOriginal("Failed to unfinalize game: " + e.getMessage()).queue();
        }
    }

    private int unfinalizeIfFinal(long gameId) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE games SET status = 'DRAFT' WHERE id = ? AND UPPER(status) = 'FINAL'"
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