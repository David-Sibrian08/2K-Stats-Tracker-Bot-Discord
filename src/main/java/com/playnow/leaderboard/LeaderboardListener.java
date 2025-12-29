package com.playnow.leaderboard;

import com.playnow.util.FantasyScoring;
import com.playnow.db.Db;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

public class LeaderboardListener extends ListenerAdapter {

    private final Db db;

    private static final int MIN_GAMES = 3;
    private static final int MAX_ROWS = 25; // Discord embed field limit

    public LeaderboardListener(Db db) {
        this.db = db;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("leaderboard")) return;

        event.deferReply(false).queue();

        try {
            List<Row> rows = loadAllParticipantRowsFinalOnly();

            Map<String, Agg> byPlayer = new HashMap<>();

            for (Row r : rows) {
                Agg a = byPlayer.computeIfAbsent(r.gamertag, k -> new Agg());
                a.gp++;

                // W/L (only when scores exist and not a tie)
                if (r.teamAScore != null && r.teamBScore != null && !Objects.equals(r.teamAScore, r.teamBScore)) {
                    boolean teamAWon = r.teamAScore > r.teamBScore;
                    boolean playerWon =
                            ("A".equalsIgnoreCase(r.team) && teamAWon) ||
                                    ("B".equalsIgnoreCase(r.team) && !teamAWon);

                    if (playerWon) a.wins++;
                    else a.losses++;
                }

                a.pts += r.pts;
                a.reb += r.reb;
                a.ast += r.ast;
                a.stl += r.stl;
                a.blk += r.blk;

                a.fpTotal += FantasyScoring.fantasyPoints(
                        r.pts, r.reb, r.ast, r.stl, r.blk, r.turnovers,
                        r.fgm, r.fga, r.tpm, r.tpa, r.ftm, r.fta
                );
            }

            List<PlayerBoard> board = new ArrayList<>();
            for (Map.Entry<String, Agg> e : byPlayer.entrySet()) {
                Agg a = e.getValue();
                if (a.gp < MIN_GAMES) continue;

                board.add(new PlayerBoard(
                        e.getKey(),
                        a.gp,
                        a.wins,
                        a.losses,
                        a.fpTotal / a.gp,
                        (double) a.pts / a.gp,
                        (double) a.reb / a.gp,
                        (double) a.ast / a.gp,
                        (double) a.stl / a.gp,
                        (double) a.blk / a.gp
                ));
            }

            board.sort(Comparator.comparingDouble(PlayerBoard::fpPerGame).reversed());

            EmbedBuilder eb = new EmbedBuilder();
            eb.setTitle("🏆 Leaderboard (Ranked by FP/G)");
            eb.setDescription("Min " + MIN_GAMES + " games • Positive stats only");
            eb.setFooter("FINAL games only");

            if (board.isEmpty()) {
                eb.setDescription("_Not enough FINAL games yet (need at least " + MIN_GAMES + " games per player)._");
                event.getHook().editOriginalEmbeds(eb.build()).queue();
                return;
            }

            int limit = Math.min(MAX_ROWS, board.size());
            for (int i = 0; i < limit; i++) {
                int rank = i + 1;
                PlayerBoard p = board.get(i);

                String medal = switch (rank) {
                    case 1 -> "🥇";
                    case 2 -> "🥈";
                    case 3 -> "🥉";
                    default -> "🔹";
                };

                String nameLine = medal + " " + rank + ". " + p.gamertag() + "  (GP " + p.gp() + ")";

                String valueLine =
                        "FP/G **" + fmt1(p.fpPerGame()) + "**\n" +
                                "W/L **" + p.wins() + "-" + p.losses() + "**\n" +
                                "PTS " + fmt1(p.ptsPerGame()) + " • REB " + fmt1(p.rebPerGame()) + " • AST " + fmt1(p.astPerGame()) + "\n" +
                                "STL " + fmt1(p.stlPerGame()) + " • BLK " + fmt1(p.blkPerGame());

                eb.addField(nameLine, valueLine, false);
            }

            if (board.size() > MAX_ROWS) {
                eb.addField("…", "_Showing top " + MAX_ROWS + " only._", false);
            }

            event.getHook().editOriginalEmbeds(eb.build()).queue();

        } catch (Exception e) {
            event.getHook().editOriginal("Failed to build leaderboard: " + e.getMessage()).queue();
        }
    }

    private static String fmt1(double v) {
        return String.format("%.1f", v);
    }

    private List<Row> loadAllParticipantRowsFinalOnly() throws Exception {
        String sql =
                "SELECT p.gamertag, pa.team, " +
                        "g.team_a_score, g.team_b_score, " +
                        "pa.pts, pa.reb, pa.ast, pa.stl, pa.blk, pa.turnovers, " +
                        "pa.fgm, pa.fga, pa.tpm, pa.tpa, pa.ftm, pa.fta " +
                        "FROM participants pa " +
                        "JOIN players p ON p.id = pa.player_id " +
                        "JOIN games g ON g.id = pa.game_id " +
                        "WHERE UPPER(g.status) = 'FINAL'";

        List<Row> out = new ArrayList<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                out.add(new Row(
                        rs.getString(1),
                        rs.getString(2),
                        (Integer) rs.getObject(3),
                        (Integer) rs.getObject(4),
                        rs.getInt(5), rs.getInt(6), rs.getInt(7),
                        rs.getInt(8), rs.getInt(9), rs.getInt(10),
                        rs.getInt(11), rs.getInt(12),
                        rs.getInt(13), rs.getInt(14),
                        rs.getInt(15), rs.getInt(16)
                ));
            }
        }
        return out;
    }

    private record Row(
            String gamertag,
            String team,
            Integer teamAScore,
            Integer teamBScore,
            int pts, int reb, int ast,
            int stl, int blk, int turnovers,
            int fgm, int fga,
            int tpm, int tpa,
            int ftm, int fta
    ) {}

    private static class Agg {
        int gp = 0;
        int pts = 0, reb = 0, ast = 0, stl = 0, blk = 0;
        double fpTotal = 0.0;
        int wins = 0;
        int losses = 0;
    }

    private record PlayerBoard(
            String gamertag,
            int gp,
            int wins,
            int losses,
            double fpPerGame,
            double ptsPerGame,
            double rebPerGame,
            double astPerGame,
            double stlPerGame,
            double blkPerGame
    ) {}
}