package com.playnow.betting.core;

import com.playnow.db.Db;

import java.io.IOException;
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

    public void createMarketsForGame(int gameId) {
        List<Integer> playerIds = new ArrayList<>();

        // 1) players in this draft game
        String playersSql = """
        SELECT DISTINCT player_id
        FROM participants
        WHERE game_id = ?
    """;

        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(playersSql)) {

            ps.setInt(1, gameId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    playerIds.add(rs.getInt("player_id"));
                }
            }
        } catch (SQLException | IOException e) {
            throw new RuntimeException("Failed to load participants for game " + gameId, e);
        }

        if (playerIds.isEmpty()) return;

        // 2) for each player, compute averages from last N FINAL games
        String historySql = """
        SELECT p.pts, p.reb, p.ast, p.turnovers
        FROM participants p
        JOIN games g ON g.id = p.game_id
        WHERE p.player_id = ?
          AND g.status = 'FINAL'
        ORDER BY g.id DESC
        LIMIT ?
    """;

        String insertMarketSql = """
        INSERT OR IGNORE INTO bet_markets (game_id, player_id, stat, line, status, created_at)
        VALUES (?, ?, ?, ?, 'OPEN', ?)
    """;

        try (Connection c = db.open()) {
            c.setAutoCommit(false);

            try (PreparedStatement histPs = c.prepareStatement(historySql);
                 PreparedStatement insPs = c.prepareStatement(insertMarketSql)) {

                for (int playerId : playerIds) {

                    histPs.setInt(1, playerId);
                    histPs.setInt(2, LOOKBACK_GAMES);

                    int games = 0;
                    int sumPts = 0, sumReb = 0, sumAst = 0, sumTo = 0;

                    try (ResultSet rs = histPs.executeQuery()) {
                        while (rs.next()) {
                            games++;
                            sumPts += rs.getInt("pts");
                            sumReb += rs.getInt("reb");
                            sumAst += rs.getInt("ast");
                            sumTo  += rs.getInt("turnovers");
                        }
                    }

                    if (games < MIN_GAMES) {
                        continue; // not enough history for this player
                    }

                    double avgPts = roundToHalf((double) sumPts / games);
                    double avgReb = roundToHalf((double) sumReb / games);
                    double avgAst = roundToHalf((double) sumAst / games);
                    double avgTo  = roundToHalf((double) sumTo  / games);

                    String now = java.time.Instant.now().toString();

                    // Insert 4 markets per player
                    insertMarket(insPs, gameId, playerId, "PTS", avgPts, now);
                    insertMarket(insPs, gameId, playerId, "REB", avgReb, now);
                    insertMarket(insPs, gameId, playerId, "AST", avgAst, now);
                    insertMarket(insPs, gameId, playerId, "TO",  avgTo,  now);
                }

                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }

        } catch (SQLException | IOException e) {
            throw new RuntimeException("Failed to create markets for game " + gameId, e);
        }
    }

    private void insertMarket(PreparedStatement ps, int gameId, int playerId, String stat, double line, String now) throws SQLException {
        ps.setInt(1, gameId);
        ps.setInt(2, playerId);
        ps.setString(3, stat);
        ps.setDouble(4, line);
        ps.setString(5, now);
        ps.executeUpdate();
    }

    private double roundToHalf(double value) {
        return Math.round(value * 2.0) / 2.0;
    }
}