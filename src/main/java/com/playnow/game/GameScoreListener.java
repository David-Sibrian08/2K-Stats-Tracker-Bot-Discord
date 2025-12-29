package com.playnow.game;

import com.playnow.db.Db;
import com.playnow.security.AccessControl;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class GameScoreListener extends ListenerAdapter {

    private final Db db;

    public GameScoreListener(Db db) {
        this.db = db;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("game")) return;
        if (!"score".equals(event.getSubcommandName())) return;

        if (!AccessControl.allowWrite(event)) return;

        var gameOpt = event.getOption("game_id");
        var aOpt = event.getOption("team_a");
        var bOpt = event.getOption("team_b");

        if (gameOpt == null || aOpt == null || bOpt == null) {
            event.reply("Missing required fields. Use: `/game score game_id:<id> team_a:<int> team_b:<int>`")
                    .setEphemeral(true).queue();
            return;
        }

        long gameId = gameOpt.getAsLong();
        int teamA = aOpt.getAsInt();
        int teamB = bOpt.getAsInt();

        event.deferReply(true).queue();

        try {
            if (!gameExists(gameId)) {
                event.getHook().editOriginal("Game #" + gameId + " not found.").queue();
                return;
            }

            // Basic sanity bounds (doesn’t block weird games, just avoids obvious junk)
            if (teamA < 0 || teamB < 0 || teamA > 250 || teamB > 250) {
                event.getHook().editOriginal("That score looks invalid. Try something like 0–250.").queue();
                return;
            }

            updateGameScores(gameId, teamA, teamB);

            event.getHook().editOriginal(
                    "Saved score for Game #" + gameId + ": 🅰️ " + teamA + " — 🅱️ " + teamB +
                            "\nRun `/game summary game_id:" + gameId + "` to view it."
            ).queue();

        } catch (Exception e) {
            event.getHook().editOriginal("Failed to save score: " + e.getMessage()).queue();
        }
    }

    private boolean gameExists(long gameId) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM games WHERE id = ?")) {
            ps.setLong(1, gameId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private void updateGameScores(long gameId, int teamAScore, int teamBScore) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE games SET team_a_score = ?, team_b_score = ? WHERE id = ?"
             )) {
            ps.setInt(1, teamAScore);
            ps.setInt(2, teamBScore);
            ps.setLong(3, gameId);
            ps.executeUpdate();
        }
    }
}