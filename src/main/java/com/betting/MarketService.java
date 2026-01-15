package com.betting;

import java.util.List;

public class MarketService {

    private static final int LOOKBACK_GAMES = 10;
    private static final int MIN_GAMES = 5;

    public void createMarketsForGame(int gameId) {
        // 1) fetch participants for game
        // 2) for each player:
        //    - fetch last N finalized games
        //    - if < MIN_GAMES → skip
        //    - compute avg for each stat
        //    - insert into bet_markets if not exists
    }

    public List<BetMarket> listMarketsForGame(int gameId) {
        // SELECT * FROM bet_markets WHERE game_id = ? AND status = 'OPEN'
        return null;
    }

    private double roundToHalf(double value) {
        return Math.round(value * 2.0) / 2.0;
    }
}
