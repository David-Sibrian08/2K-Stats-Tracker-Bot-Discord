package com.playnow.betting.core;

import com.playnow.db.Db;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class MarketService {

    private static final int LOOKBACK_GAMES = 10;
    private static final int MIN_GAMES = 5;

    private final Db db;

    public MarketService(Db db) {
        this.db = db;
    }

    public void createMarketsForGame(long gameId) {
    }

    public List<BetMarket> listMarketsForGame(long gameId) {
        String sql = """
            SELECT id, game_id, player_id, stat, line, status
            FROM bet_markets
            WHERE game_id = ?
              AND status = 'OPEN'
            ORDER BY stat, player_id
        """;

        List<BetMarket> out = new ArrayList<>();

        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(sql)) {

            ps.setLong(1, gameId);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new BetMarket(
                            rs.getInt("id"),
                            rs.getInt("game_id"),
                            rs.getInt("player_id"),
                            rs.getString("stat"),
                            rs.getDouble("line"),
                            rs.getString("status")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to list markets for game " + gameId, e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return out;
    }

    private double roundToHalf(double value) {
        return Math.round(value * 2.0) / 2.0;
    }
}