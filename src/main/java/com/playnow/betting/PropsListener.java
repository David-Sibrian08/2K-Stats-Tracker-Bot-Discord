package com.playnow.betting;

import com.playnow.betting.core.BetMarket;
import com.playnow.betting.core.MarketService;
import com.playnow.db.Db;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

public class PropsListener extends ListenerAdapter {

    private final Db db;
    private final MarketService marketService;

    public PropsListener(Db db) {
        this.db = db;
        this.marketService = new MarketService(db);
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!"props".equals(event.getName())) return;

        Integer gameId = getLatestDraftGameId();
        if (gameId == null) {
            event.reply("❌ No active DRAFT game found. Create a game first.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        // Lazy creation (we’ll implement createMarketsForGame next)
        marketService.createMarketsForGame(gameId);
        List<BetMarket> markets = marketService.listMarketsForGame(gameId);

        if (markets.isEmpty()) {
            event.reply("No props available yet for game #" + gameId + ".")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        Map<Long, String> playerNames = loadPlayerNames();

        event.reply(formatMarkets(gameId, markets, playerNames)).queue();
    }

    private Integer getLatestDraftGameId() {
        String sql = """
            SELECT id FROM games
            WHERE status = 'DRAFT'
            ORDER BY id DESC
            LIMIT 1
        """;
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) return rs.getInt(1);
            return null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private Map<Long, String> loadPlayerNames() {
        String sql = "SELECT id, gamertag FROM players";
        Map<Long, String> map = new HashMap<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) map.put(rs.getLong(1), rs.getString(2));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return map;
    }

    private String formatMarkets(long gameId, List<BetMarket> markets, Map<Long, String> playerNames) {
        Map<String, List<BetMarket>> byStat = new HashMap<>();
        for (BetMarket m : markets) byStat.computeIfAbsent(m.stat(), k -> new ArrayList<>()).add(m);

        String[] statOrder = {"PTS", "REB", "AST", "TO"};

        StringBuilder sb = new StringBuilder();
        sb.append("**Props for Game #").append(gameId).append("**\n");
        sb.append("Use: `/bet market_id:<id> side:OVER|UNDER amount:<chips>`\n\n");

        for (String stat : statOrder) {
            var list = byStat.get(stat);
            if (list == null || list.isEmpty()) continue;

            sb.append("**").append(stat).append("**\n");
            list.sort(Comparator.comparingLong(BetMarket::playerId));
            for (BetMarket m : list) {
                String name = playerNames.getOrDefault(m.playerId(), "Player#" + m.playerId());
                sb.append("• `[").append(m.id()).append("]` ")
                        .append(name)
                        .append(" — O/U ")
                        .append(m.line())
                        .append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}