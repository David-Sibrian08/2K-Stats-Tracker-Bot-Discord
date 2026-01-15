package com.playnow.betting;

import com.playnow.betting.core.BetMarket;
import com.playnow.betting.core.MarketService;
import com.playnow.db.Db;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class PropsCommand extends ListenerAdapter {

    private final Db db;
    private final MarketService marketService;

    public PropsCommand(Db db) {
        this.db = db;
        this.marketService = new MarketService(db);
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("props")) return;

        Integer gameId = db.getLatestDraftGameId();
        if (gameId == null) {
            event.reply("❌ No active DRAFT game found. Create a game first.")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        // Create markets lazily (safe to call repeatedly because of UNIQUE constraint)
        marketService.createMarketsForGame(gameId);

        List<BetMarket> markets = marketService.listMarketsForGame(gameId);
        if (markets.isEmpty()) {
            event.reply("ℹ️ No props available yet for game #" + gameId +
                            ". (Need enough past games to compute averages.)")
                    .setEphemeral(true)
                    .queue();
            return;
        }

        Map<Integer, String> playerNames = db.getPlayerIdToGamertagMap();

        String message = formatMarkets(gameId, markets, playerNames);
        event.reply(message).queue();
    }

    private String formatMarkets(int gameId, List<BetMarket> markets, Map<Integer, String> playerNames) {
        Map<String, List<BetMarket>> byStat = markets.stream()
                .collect(Collectors.groupingBy(BetMarket::stat));

        StringBuilder sb = new StringBuilder();
        sb.append("**Props for Game #").append(gameId).append("**\n");
        sb.append("Use: `/bet market_id:<id> side:OVER|UNDER amount:<chips>`\n\n");

        String[] statOrder = {"PTS", "REB", "AST", "TO"};

        for (String stat : statOrder) {
            List<BetMarket> list = byStat.get(stat);
            if (list == null || list.isEmpty()) continue;

            sb.append("**").append(stat).append("**\n");
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

        byStat.keySet().stream()
                .filter(stat -> !List.of(statOrder).contains(stat))
                .sorted()
                .forEach(stat -> {
                    List<BetMarket> list = byStat.get(stat);
                    if (list == null || list.isEmpty()) return;

                    sb.append("**").append(stat).append("**\n");
                    for (BetMarket m : list) {
                        String name = playerNames.getOrDefault(m.playerId(), "Player#" + m.playerId());
                        sb.append("• `[").append(m.id()).append("]` ")
                                .append(name)
                                .append(" — O/U ")
                                .append(m.line())
                                .append("\n");
                    }
                    sb.append("\n");
                });

        return sb.toString();
    }
}