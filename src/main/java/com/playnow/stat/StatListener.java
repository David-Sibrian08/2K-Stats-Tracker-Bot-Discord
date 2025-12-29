package com.playnow.stat;

import com.playnow.db.Db;
import com.playnow.security.AccessControl;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class StatListener extends ListenerAdapter {

    private final Db db;

    public StatListener(Db db) {
        this.db = db;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("stat")) return;
        if (!"add".equals(event.getSubcommandName())) return;

        handleAdd(event);
    }

    private void handleAdd(SlashCommandInteractionEvent event) {
        // Required options
        Long gameId = getLong(event, "game_id");
        String gamertag = getString(event, "gamertag");
        String team = getString(event, "team");

        if (gameId == null || gamertag == null || team == null) {
            event.reply("Missing required fields.").setEphemeral(true).queue();
            return;
        }

        if (!AccessControl.allowWrite(event)) return;

        event.deferReply(true).queue();

        team = team.trim().toUpperCase();
        if (!team.equals("A") && !team.equals("B")) {
            event.getHook().editOriginal("Team must be A or B.").queue();
            return;
        }

        // Stats (all required)
        Integer pts = getInt(event, "pts");
        Integer reb = getInt(event, "reb");
        Integer ast = getInt(event, "ast");
        Integer stl = getInt(event, "stl");
        Integer blk = getInt(event, "blk");
        Integer fouls = getInt(event, "fouls");
        Integer turnovers = getInt(event, "to");

        Integer fgm = getInt(event, "fgm");
        Integer fga = getInt(event, "fga");
        Integer tpm = getInt(event, "tpm");
        Integer tpa = getInt(event, "tpa");
        Integer ftm = getInt(event, "ftm");
        Integer fta = getInt(event, "fta");

        if (pts == null || reb == null || ast == null || stl == null || blk == null ||
                fouls == null || turnovers == null ||
                fgm == null || fga == null || tpm == null || tpa == null || ftm == null || fta == null) {
            event.getHook().editOriginal("Missing one or more stat fields.").queue();
            return;
        }

        try {
            gamertag = gamertag.trim();

            Long playerId = findPlayerId(gamertag);
            if (playerId == null) {
                event.getHook().editOriginal("Unknown gamertag `" + gamertag + "`. Add it first with `/player add`.").queue();
                return;
            }

            String status = getGameStatus(gameId);
            if (status == null) {
                event.getHook().editOriginal("Game #" + gameId + " not found.").queue();
                return;
            }
            if ("FINAL".equalsIgnoreCase(status)) {
                event.getHook().editOriginal("Game #" + gameId + " is FINAL. Stats are locked.").queue();
                return;
            }

            // Upsert: replace existing player line in this game
            upsertParticipant(
                    gameId, playerId, team,
                    pts, reb, ast, stl, blk, fouls, turnovers,
                    fgm, fga, tpm, tpa, ftm, fta
            );

            event.getHook().editOriginal("Saved stats for `" + gamertag + "` in Game #" + gameId + " (Team " + team + ")").queue();

        } catch (Exception e) {
            event.getHook().editOriginal("Failed to save stats: " + e.getMessage()).queue();
        }
    }

    private void upsertParticipant(
            long gameId, long playerId, String team,
            int pts, int reb, int ast, int stl, int blk, int fouls, int turnovers,
            int fgm, int fga, int tpm, int tpa, int ftm, int fta
    ) throws Exception {
        try (Connection c = db.open()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement del = c.prepareStatement(
                        "DELETE FROM participants WHERE game_id = ? AND player_id = ?"
                )) {
                    del.setLong(1, gameId);
                    del.setLong(2, playerId);
                    del.executeUpdate();
                }

                try (PreparedStatement ins = c.prepareStatement(
                        """
                        INSERT INTO participants (
                          game_id, player_id, team,
                          pts, reb, ast, stl, blk, fouls, turnovers,
                          fgm, fga, tpm, tpa, ftm, fta
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """
                )) {
                    ins.setLong(1, gameId);
                    ins.setLong(2, playerId);
                    ins.setString(3, team);

                    ins.setInt(4, pts);
                    ins.setInt(5, reb);
                    ins.setInt(6, ast);
                    ins.setInt(7, stl);
                    ins.setInt(8, blk);
                    ins.setInt(9, fouls);
                    ins.setInt(10, turnovers);

                    ins.setInt(11, fgm);
                    ins.setInt(12, fga);
                    ins.setInt(13, tpm);
                    ins.setInt(14, tpa);
                    ins.setInt(15, ftm);
                    ins.setInt(16, fta);

                    ins.executeUpdate();
                }

                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    private Long findPlayerId(String gamertag) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id FROM players WHERE LOWER(gamertag) = LOWER(?)"
             )) {
            ps.setString(1, gamertag);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        return null;
    }

    // ✅ Replaces gameExists() + isGameFinal() with one DB call
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

    private static String getString(SlashCommandInteractionEvent event, String name) {
        var opt = event.getOption(name);
        return opt == null ? null : opt.getAsString();
    }

    private static Long getLong(SlashCommandInteractionEvent event, String name) {
        var opt = event.getOption(name);
        return opt == null ? null : opt.getAsLong();
    }

    private static Integer getInt(SlashCommandInteractionEvent event, String name) {
        var opt = event.getOption(name);
        return opt == null ? null : opt.getAsInt();
    }
}