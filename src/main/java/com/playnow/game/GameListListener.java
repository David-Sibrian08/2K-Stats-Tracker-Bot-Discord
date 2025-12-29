package com.playnow.game;

import com.playnow.db.Db;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

public class GameListListener extends ListenerAdapter {

    private final Db db;

    public GameListListener(Db db) {
        this.db = db;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("game")) return;

        String sub = event.getSubcommandName();
        if (!"list".equals(sub) && !"find".equals(sub)) return;

        event.deferReply(false).queue();

        try {
            if ("list".equals(sub)) handleList(event);
            else handleFind(event);
        } catch (Exception e) {
            event.getHook().editOriginal("Failed: " + e.getMessage()).queue();
        }
    }

    // ----------------------------
    // /game list
    // ----------------------------

    private void handleList(SlashCommandInteractionEvent event) throws Exception {
        String statusOpt = event.getOption("status") == null ? null : event.getOption("status").getAsString();
        Integer limitOpt = event.getOption("limit") == null ? null : event.getOption("limit").getAsInt();

        String filterStatus = statusOpt == null ? null : statusOpt.trim().toUpperCase(Locale.ROOT);
        int limit = (limitOpt == null) ? 10 : Math.max(1, Math.min(25, limitOpt));

        boolean useStatusFilter =
                filterStatus != null && (filterStatus.equals("DRAFT") || filterStatus.equals("FINAL"));

        EmbedBuilder eb = new EmbedBuilder();
        eb.setTitle("Games");

        List<GameRow> games = loadGames(useStatusFilter ? filterStatus : null, limit);
        String body = renderGames(games);

        if (filterStatus != null && !filterStatus.isBlank() && !useStatusFilter) {
            body += "\n\n_Tip: status must be `DRAFT` or `FINAL`._";
        }

        eb.setDescription(body);
        eb.setFooter("Tip: /game summary game_id:<id>");

        event.getHook().editOriginalEmbeds(eb.build()).queue();
    }

    // ----------------------------
    // /game find gamertag:<...>
    // ----------------------------

    private void handleFind(SlashCommandInteractionEvent event) throws Exception {
        var gtOpt = event.getOption("gamertag");
        if (gtOpt == null) {
            event.getHook().editOriginal("Missing required option: gamertag").queue();
            return;
        }

        String gamertagInput = gtOpt.getAsString().trim();
        if (gamertagInput.isBlank()) {
            event.getHook().editOriginal("gamertag cannot be blank").queue();
            return;
        }

        Integer limitOpt = event.getOption("limit") == null ? null : event.getOption("limit").getAsInt();
        int limit = (limitOpt == null) ? 10 : Math.max(1, Math.min(25, limitOpt));

        Long playerId = findPlayerId(gamertagInput);
        if (playerId == null) {
            event.getHook().editOriginal("Unknown gamertag `" + gamertagInput + "`.").queue();
            return;
        }

        List<GameRow> games = loadGamesForPlayer(playerId, limit);

        EmbedBuilder eb = new EmbedBuilder();
        eb.setTitle("🔎 Games for " + gamertagInput);
        eb.setDescription(renderGames(games));
        eb.setFooter("Tip: /game summary game_id:<id>");

        event.getHook().editOriginalEmbeds(eb.build()).queue();
    }

    // ----------------------------
    // Rendering
    // ----------------------------

    private String renderGames(List<GameRow> games) {
        if (games.isEmpty()) return "_No games found._";

        StringBuilder body = new StringBuilder();
        String lastDateKey = null;

        for (GameRow g : games) {
            String dateKey = dateKey(g.playedAt);

            if (!Objects.equals(dateKey, lastDateKey)) {
                if (lastDateKey != null) body.append("\n");
                body.append(dateKey).append("\n");
                lastDateKey = dateKey;
            }

            String scoreText;
            if (g.teamAScore != null && g.teamBScore != null) {
                scoreText = " 🅰️ " + g.teamAScore + "–" + g.teamBScore + " 🅱️";
            } else {
                scoreText = " _(no score)_";
            }

            body.append("`#").append(g.id).append("`")
                    .append(scoreText)
                    .append("\n");
        }

        return body.toString();
    }

    private String dateKey(String playedAt) {
        if (playedAt == null || playedAt.isBlank()) return "Unknown date";
        int t = playedAt.indexOf('T');
        return (t > 0) ? playedAt.substring(0, t) : playedAt;
    }

    // ----------------------------
    // DB helpers
    // ----------------------------

    private List<GameRow> loadGames(String statusFilterOrNull, int limit) throws Exception {
        String sql;
        if (statusFilterOrNull != null) {
            sql = """
                  SELECT id, played_at, team_a_score, team_b_score
                  FROM games
                  WHERE UPPER(status) = ?
                  ORDER BY id DESC
                  LIMIT ?
                  """;
        } else {
            sql = """
                  SELECT id, played_at, team_a_score, team_b_score
                  FROM games
                  ORDER BY id DESC
                  LIMIT ?
                  """;
        }

        List<GameRow> out = new ArrayList<>();

        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {

            int i = 1;
            if (statusFilterOrNull != null) ps.setString(i++, statusFilterOrNull);
            ps.setInt(i, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong(1);
                    String playedAt = rs.getString(2);
                    Integer a = (Integer) rs.getObject(3);
                    Integer b = (Integer) rs.getObject(4);
                    out.add(new GameRow(id, playedAt, a, b));
                }
            }
        }

        return out;
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

    private List<GameRow> loadGamesForPlayer(long playerId, int limit) throws Exception {
        String sql = """
                SELECT DISTINCT g.id, g.played_at, g.team_a_score, g.team_b_score
                FROM games g
                JOIN participants pa ON pa.game_id = g.id
                WHERE pa.player_id = ?
                ORDER BY g.id DESC
                LIMIT ?
                """;

        List<GameRow> out = new ArrayList<>();

        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {

            ps.setLong(1, playerId);
            ps.setInt(2, limit);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong(1);
                    String playedAt = rs.getString(2);
                    Integer a = (Integer) rs.getObject(3);
                    Integer b = (Integer) rs.getObject(4);
                    out.add(new GameRow(id, playedAt, a, b));
                }
            }
        }

        return out;
    }

    private record GameRow(long id, String playedAt, Integer teamAScore, Integer teamBScore) {}
}