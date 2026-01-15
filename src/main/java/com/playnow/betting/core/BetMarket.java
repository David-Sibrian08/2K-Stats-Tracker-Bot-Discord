package com.playnow.betting.core;

public record BetMarket(int id, int gameId, int playerId, String stat, double line, String status) {
}