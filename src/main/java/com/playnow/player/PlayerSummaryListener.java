package com.playnow.player;

import com.playnow.util.FantasyScoring;
import com.playnow.db.Db;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class PlayerSummaryListener extends ListenerAdapter {

    private final Db db;

    public PlayerSummaryListener(Db db) {
        this.db = db;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!"player".equals(event.getName())) return;
        if (!"summary".equals(event.getSubcommandName())) return;

        String gamertag = optString(event, "gamertag");
        if (gamertag == null || gamertag.trim().isBlank()) {
            event.reply("Missing required option: gamertag").setEphemeral(true).queue();
            return;
        }
        gamertag = gamertag.trim();

        event.deferReply(false).queue();

        try {
            Long playerId = findPlayerId(gamertag);
            if (playerId == null) {
                event.getHook().editOriginal("Unknown gamertag `" + gamertag + "`.").queue();
                return;
            }

            PlayerAgg agg = loadAggAndFantasy(playerId);

            EmbedBuilder eb = new EmbedBuilder();
            eb.setTitle("Player Summary — " + gamertag);

            if (agg.games == 0) {
                eb.setDescription("No games recorded yet for this player.");
                event.getHook().editOriginalEmbeds(eb.build()).queue();
                return;
            }

            eb.setDescription("Games played: **" + agg.games + "**");

            double gp = agg.games;

            String core =
                    "```" +
                            "\nPTS  " + fmt1(agg.pts / gp) +
                            "\nREB  " + fmt1(agg.reb / gp) +
                            "\nAST  " + fmt1(agg.ast / gp) +
                            "\nSTL  " + fmt1(agg.stl / gp) +
                            "\nBLK  " + fmt1(agg.blk / gp) +
                            "\nTO   " + fmt1(agg.turnovers / gp) +
                            "\n```";

            String shooting =
                    "```" +
                            "\nFG   " + agg.fgm + "/" + agg.fga + "  (" + fmtPct(pct(agg.fgm, agg.fga)) + ")" +
                            "\n3PT  " + agg.tpm + "/" + agg.tpa + "  (" + fmtPct(pct(agg.tpm, agg.tpa)) + ")" +
                            "\nFT   " + agg.ftm + "/" + agg.fta + "  (" + fmtPct(pct(agg.ftm, agg.fta)) + ")" +
                            "\n```";

            eb.addField("Per-game averages", core, true);
            eb.addField("Shooting", shooting, true);
            eb.addField("Fantasy", "```AVG FPTS  " + fmt1(agg.fpTotal / gp) + "```", false);

            event.getHook().editOriginalEmbeds(eb.build()).queue();

        } catch (Exception e) {
            event.getHook().editOriginal("Failed to build player summary: " + e.getMessage()).queue();
        }
    }

    private static String optString(SlashCommandInteractionEvent event, String name) {
        var opt = event.getOption(name);
        return opt == null ? null : opt.getAsString();
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

    /**
     * Single DB pass: totals + fantasy total.
     * (We keep fantasy calculation in Java because it’s your custom scoring.)
     */
    private PlayerAgg loadAggAndFantasy(long playerId) throws Exception {
        String sql = """
            SELECT pts, reb, ast, stl, blk, turnovers,
                   fgm, fga, tpm, tpa, ftm, fta
            FROM participants
            WHERE player_id = ?
            """;

        int games = 0;

        int pts = 0, reb = 0, ast = 0, stl = 0, blk = 0, to = 0;
        int fgm = 0, fga = 0, tpm = 0, tpa = 0, ftm = 0, fta = 0;
        double fpTotal = 0.0;

        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, playerId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    games++;

                    int rPts = rs.getInt(1);
                    int rReb = rs.getInt(2);
                    int rAst = rs.getInt(3);
                    int rStl = rs.getInt(4);
                    int rBlk = rs.getInt(5);
                    int rTo  = rs.getInt(6);

                    int rFgm = rs.getInt(7);
                    int rFga = rs.getInt(8);
                    int rTpm = rs.getInt(9);
                    int rTpa = rs.getInt(10);
                    int rFtm = rs.getInt(11);
                    int rFta = rs.getInt(12);

                    pts += rPts; reb += rReb; ast += rAst; stl += rStl; blk += rBlk; to += rTo;
                    fgm += rFgm; fga += rFga; tpm += rTpm; tpa += rTpa; ftm += rFtm; fta += rFta;

                    fpTotal += FantasyScoring.fantasyPoints(
                            rPts, rReb, rAst, rStl, rBlk, rTo,
                            rFgm, rFga, rTpm, rTpa, rFtm, rFta
                    );
                }
            }
        }

        return new PlayerAgg(games, pts, reb, ast, stl, blk, to, fgm, fga, tpm, tpa, ftm, fta, fpTotal);
    }

    private static double pct(int made, int att) {
        if (att <= 0) return 0.0;
        return (double) made / (double) att;
    }

    private static String fmt1(double v) {
        return String.format("%.1f", v);
    }

    private static String fmtPct(double v) {
        return String.format("%.1f%%", v * 100.0);
    }

    private record PlayerAgg(
            int games,
            int pts, int reb, int ast,
            int stl, int blk, int turnovers,
            int fgm, int fga,
            int tpm, int tpa,
            int ftm, int fta,
            double fpTotal
    ) {}
}