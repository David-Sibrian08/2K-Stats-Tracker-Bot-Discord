package com.playnow.game;

import com.playnow.util.FantasyScoring;
import com.playnow.db.Db;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.utils.FileUpload;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class GameSummaryListener extends ListenerAdapter {

    private final Db db;

    public GameSummaryListener(Db db) {
        this.db = db;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!"game".equals(event.getName())) return;
        if (!"summary".equals(event.getSubcommandName())) return;

        var gameOpt = event.getOption("game_id");
        if (gameOpt == null) {
            event.reply("Missing required option: game_id").setEphemeral(true).queue();
            return;
        }

        long gameId = gameOpt.getAsLong();
        event.deferReply(false).queue();

        try {
            GameMeta meta = loadGameMeta(gameId);
            if (meta == null) {
                event.getHook().editOriginal("Game #" + gameId + " not found.").queue();
                return;
            }

            List<PlayerLine> teamA = loadTeam(gameId, "A");
            List<PlayerLine> teamB = loadTeam(gameId, "B");

            if (teamA.isEmpty() && teamB.isEmpty()) {
                event.getHook().editOriginal(
                        "Game #" + gameId + " has no participants yet. Add stats first with `/stat add`."
                ).queue();
                return;
            }

            EmbedBuilder eb = new EmbedBuilder();
            eb.setTitle("🏀 Game #" + gameId + " — " + formatPlayedAt(meta.playedAt));

            StringBuilder desc = new StringBuilder()
                    .append("Status: **").append(meta.status).append("**");

            if (meta.teamAScore != null && meta.teamBScore != null) {
                desc.append("\nScore:  **")
                        .append(meta.teamAScore)
                        .append("** — **")
                        .append(meta.teamBScore)
                        .append("**");
            }

            eb.setDescription(desc.toString());

            addTeamPlayers(eb, "🅰️", teamA);
            eb.addField("\u200B", "\u200B", false); // spacer
            addTeamPlayers(eb, "🅱️", teamB);

            eb.setFooter("GTD 2K Tracker • /game summary");

            // If we have a saved local receipt, attach it to the message
            File receipt = resolveReceipt(meta.imagePath);
            if (receipt != null) {
                event.getHook()
                        .editOriginalEmbeds(eb.build())
                        .setFiles(FileUpload.fromData(receipt, receipt.getName()))
                        .queue();
            } else {
                event.getHook().editOriginalEmbeds(eb.build()).queue();
            }

        } catch (Exception e) {
            event.getHook().editOriginal("Failed to build summary: " + e.getMessage()).queue();
        }
    }

    // ----------------------------
    // Render helpers
    // ----------------------------

    private void addTeamPlayers(EmbedBuilder eb, String teamTitle, List<PlayerLine> players) {
        eb.addField(teamTitle, players.isEmpty() ? "_(none)_" : "\u200B", false);

        for (PlayerLine p : players) {
            StringBuilder sb = new StringBuilder();

            addStat(sb, p.pts(), "PTS");
            addStat(sb, p.reb(), "REB");
            addStat(sb, p.ast(), "AST");
            addStat(sb, p.stl(), "STL");
            addStat(sb, p.blk(), "BLK");
            addStat(sb, p.fouls(), "FLS");
            addStat(sb, p.turnovers(), "TO");

            if (p.fga() > 0) appendSplit(sb, "FG", p.fgm(), p.fga());
            if (p.tpa() > 0) appendSplit(sb, "3PT", p.tpm(), p.tpa());
            if (p.fta() > 0) appendSplit(sb, "FT", p.ftm(), p.fta());

            double fp = FantasyScoring.fantasyPoints(
                    p.pts(), p.reb(), p.ast(), p.stl(), p.blk(), p.turnovers(),
                    p.fgm(), p.fga(), p.tpm(), p.tpa(), p.ftm(), p.fta()
            );
            sb.append(" | FP ").append(String.format("%.1f", fp));

            eb.addField("• " + p.gamertag(), sb.toString(), false);
        }
    }

    private static void addStat(StringBuilder sb, int value, String label) {
        if (value <= 0) return;
        if (!sb.isEmpty()) sb.append(" | ");
        sb.append(value).append(" ").append(label);
    }

    private static void appendSplit(StringBuilder sb, String label, int made, int att) {
        if (!sb.isEmpty()) sb.append(" | ");
        sb.append(label).append(" ").append(made).append("/").append(att);
    }

    private static File resolveReceipt(String imagePath) {
        if (imagePath == null || imagePath.isBlank()) return null;
        File f = new File(imagePath);
        return (f.exists() && f.isFile()) ? f : null;
    }

    private static String formatPlayedAt(String raw) {
        if (raw == null || raw.isBlank()) return "Unknown date";
        try {
            if (raw.contains("T")) {
                LocalDateTime dt = LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                return dt.format(DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a"));
            }
            LocalDate d = LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE);
            return d.format(DateTimeFormatter.ofPattern("MMM d, yyyy"));
        } catch (Exception ignored) {
            return raw;
        }
    }

    // ----------------------------
    // DB reads
    // ----------------------------

    private GameMeta loadGameMeta(long gameId) throws Exception {
        String sql = "SELECT played_at, status, image_path, team_a_score, team_b_score FROM games WHERE id = ?";
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {

            ps.setLong(1, gameId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;

                return new GameMeta(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        (Integer) rs.getObject(4),
                        (Integer) rs.getObject(5)
                );
            }
        }
    }

    private List<PlayerLine> loadTeam(long gameId, String team) throws Exception {
        String sql = """
            SELECT p.gamertag,
                   pa.pts, pa.reb, pa.ast,
                   pa.stl, pa.blk, pa.fouls, pa.turnovers,
                   pa.fgm, pa.fga,
                   pa.tpm, pa.tpa,
                   pa.ftm, pa.fta
            FROM participants pa
            JOIN players p ON p.id = pa.player_id
            WHERE pa.game_id = ? AND pa.team = ?
            ORDER BY pa.id ASC
            """;

        List<PlayerLine> out = new ArrayList<>();

        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {

            ps.setLong(1, gameId);
            ps.setString(2, team);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new PlayerLine(
                            rs.getString(1),
                            rs.getInt(2), rs.getInt(3), rs.getInt(4),
                            rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getInt(8),
                            rs.getInt(9), rs.getInt(10),
                            rs.getInt(11), rs.getInt(12),
                            rs.getInt(13), rs.getInt(14)
                    ));
                }
            }
        }

        return out;
    }

    private record GameMeta(String playedAt, String status, String imagePath, Integer teamAScore, Integer teamBScore) {}

    private record PlayerLine(
            String gamertag,
            int pts, int reb, int ast,
            int stl, int blk, int fouls, int turnovers,
            int fgm, int fga,
            int tpm, int tpa,
            int ftm, int fta
    ) {}
}