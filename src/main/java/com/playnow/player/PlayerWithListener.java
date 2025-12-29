package com.playnow.player;

import com.playnow.db.Db;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class PlayerWithListener extends ListenerAdapter {

    private final Db db;

    public PlayerWithListener(Db db) {
        this.db = db;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!"player".equals(event.getName())) return;
        if (!"with".equals(event.getSubcommandName())) return;

        String gamertag = optString(event, "gamertag");
        String teammate = optString(event, "teammate");
        if (gamertag == null || teammate == null) {
            event.reply("Missing required options: gamertag, teammate").setEphemeral(true).queue();
            return;
        }

        gamertag = gamertag.trim();
        teammate = teammate.trim();

        event.deferReply(false).queue();

        try {
            Long p1 = findPlayerId(gamertag);
            if (p1 == null) {
                event.getHook().editOriginal("Unknown gamertag `" + gamertag + "`.").queue();
                return;
            }

            Long p2 = findPlayerId(teammate);
            if (p2 == null) {
                event.getHook().editOriginal("Unknown teammate `" + teammate + "`.").queue();
                return;
            }

            WithAgg agg = loadWithAgg(p1, p2);

            EmbedBuilder eb = new EmbedBuilder();
            eb.setTitle("🤝 With Teammate — " + gamertag + " + " + teammate);
            eb.setDescription("Games together: **" + agg.games + "**");

            if (agg.games == 0) {
                eb.addField("Result", "No games found where both players were on the same team.", false);
                event.getHook().editOriginalEmbeds(eb.build()).queue();
                return;
            }

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

            eb.addField(gamertag + " averages (with " + teammate + ")", core, true);
            eb.addField("Shooting (with " + teammate + ")", shooting, true);

            event.getHook().editOriginalEmbeds(eb.build()).queue();

        } catch (Exception e) {
            event.getHook().editOriginal("Failed to build teammate split: " + e.getMessage()).queue();
        }
    }

    private static String optString(SlashCommandInteractionEvent event, String name) {
        var opt = event.getOption(name);
        return opt == null ? null : opt.getAsString();
    }

    /**
     * Games together = same game_id AND same team.
     * Aggregate stats for player1 only, limited to those games.
     */
    private WithAgg loadWithAgg(long player1Id, long player2Id) throws Exception {
        String sql = """
            SELECT
              COUNT(*) as games,
              COALESCE(SUM(p1.pts), 0),
              COALESCE(SUM(p1.reb), 0),
              COALESCE(SUM(p1.ast), 0),
              COALESCE(SUM(p1.stl), 0),
              COALESCE(SUM(p1.blk), 0),
              COALESCE(SUM(p1.turnovers), 0),
              COALESCE(SUM(p1.fgm), 0),
              COALESCE(SUM(p1.fga), 0),
              COALESCE(SUM(p1.tpm), 0),
              COALESCE(SUM(p1.tpa), 0),
              COALESCE(SUM(p1.ftm), 0),
              COALESCE(SUM(p1.fta), 0)
            FROM participants p1
            JOIN participants p2
              ON p2.game_id = p1.game_id
             AND p2.team    = p1.team
            WHERE p1.player_id = ?
              AND p2.player_id = ?
            """;

        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, player1Id);
            ps.setLong(2, player2Id);

            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new WithAgg(
                        rs.getInt(1),
                        rs.getInt(2), rs.getInt(3), rs.getInt(4),
                        rs.getInt(5), rs.getInt(6), rs.getInt(7),
                        rs.getInt(8), rs.getInt(9),
                        rs.getInt(10), rs.getInt(11),
                        rs.getInt(12), rs.getInt(13)
                );
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

    private record WithAgg(
            int games,
            double pts, double reb, double ast,
            double stl, double blk, double turnovers,
            int fgm, int fga,
            int tpm, int tpa,
            int ftm, int fta
    ) {}
}