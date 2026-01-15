package com.betting;

public class BetMarket {
    private final int id;
    private final int gameId;
    private final int playerId;
    private final String stat;
    private final double line;
    private final String status;

    public BetMarket(int id, int gameId, int playerId, String stat, double line, String status) {
        this.id = id;
        this.gameId = gameId;
        this.playerId = playerId;
        this.stat = stat;
        this.line = line;
        this.status = status;
    }

    public int getId() { return id; }
    public int getGameId() { return gameId; }
    public int getPlayerId() { return playerId; }
    public String getStat() { return stat; }
    public double getLine() { return line; }
    public String getStatus() { return status; }
}