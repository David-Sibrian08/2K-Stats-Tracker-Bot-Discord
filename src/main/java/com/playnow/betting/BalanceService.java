package com.playnow.betting;

public class BalanceService {

    private static final int STARTING_CHIPS = 1000;

    public int getOrCreateBalance(String userId) {
        // SELECT chips FROM bet_balances WHERE user_id = ?
        // if not exists → INSERT user with STARTING_CHIPS
        return 0;
    }

    public boolean hasEnough(String userId, int amount) {
        return getOrCreateBalance(userId) >= amount;
    }

    public void debit(String userId, int amount) {
        // UPDATE bet_balances SET chips = chips - ? WHERE user_id = ?
    }

    public void credit(String userId, int amount) {
        // UPDATE bet_balances SET chips = chips + ? WHERE user_id = ?
    }
}
