package com.playnow.util;

//This fantasy scoring reflects my league's scoring
public class FantasyScoring {

    // Shot scoring
    private static final double FG_MADE = 1.0;
    private static final double FG_MISS = -0.55;

    private static final double FT_MADE = 0.5;
    private static final double FT_MISS = -0.85;

    private static final double TP_MADE = 1.25;
    private static final double TP_MISS = -0.6;

    // Counting stats
    private static final double REB = 1.2;
    private static final double AST = 1.7;
    private static final double STL = 2.0;
    private static final double BLK = 1.9;
    private static final double TO  = -1.5;

    // Bonuses
    private static final double DOUBLE_DOUBLE_BONUS = 3.0;
    private static final double TRIPLE_DOUBLE_BONUS = 5.0;

    private FantasyScoring() {}

    /**
     * NOTE:
     * - A made 3PT counts as: FG made + 3PT made.
     * - A missed 3PT counts as: FG miss + 3PT miss.
     * - Double/Triple double counts only these categories: PTS, REB, AST, STL, BLK (TO excluded)
     * - Triple double also gets the double-double bonus.
     */
    public static double fantasyPoints(
            int pts, int reb, int ast, int stl, int blk, int turnovers,
            int fgm, int fga,
            int tpm, int tpa,
            int ftm, int fta
    ) {
        int fgMiss = Math.max(0, fga - fgm);
        int tpMiss = Math.max(0, tpa - tpm);
        int ftMiss = Math.max(0, fta - ftm);

        double total = 0.0;

        // FG (includes 3PT attempts inherently via fga/fgm)
        total += fgm * FG_MADE;
        total += fgMiss * FG_MISS;

        // 3PT bonus/penalty (in addition to FG)
        total += tpm * TP_MADE;
        total += tpMiss * TP_MISS;

        // FT
        total += ftm * FT_MADE;
        total += ftMiss * FT_MISS;

        // Counting stats
        total += reb * REB;
        total += ast * AST;
        total += stl * STL;
        total += blk * BLK;
        total += turnovers * TO;

        // DD / TD bonuses
        int ddCount = count10PlusCategories(pts, reb, ast, stl, blk);
        if (ddCount >= 2) total += DOUBLE_DOUBLE_BONUS;
        if (ddCount >= 3) total += TRIPLE_DOUBLE_BONUS; // also keeps DD bonus

        return round1(total);
    }

    private static int count10PlusCategories(int pts, int reb, int ast, int stl, int blk) {
        int c = 0;
        if (pts >= 10) c++;
        if (reb >= 10) c++;
        if (ast >= 10) c++;
        if (stl >= 10) c++;
        if (blk >= 10) c++;
        return c;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}