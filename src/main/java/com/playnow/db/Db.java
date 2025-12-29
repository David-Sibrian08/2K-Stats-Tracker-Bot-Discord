package com.playnow.db;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * Tiny SQLite helper:
 * - Ensures ./data/playnow.db exists
 * - Creates schema (tables) if missing
 *
 */
public class Db {

    private final String jdbcUrl;

    public Db(String dbFilePath) {
        // SQLite JDBC URL format: jdbc:sqlite:/absolute/or/relative/path.db
        this.jdbcUrl = "jdbc:sqlite:" + dbFilePath;
    }

    /** Convenience default: data/playnow.db */
    public static Db defaultDb() {
        return new Db("data/playnow.db");
    }

    /** Open a connection. Callers should use try-with-resources. */
    public Connection open() throws Exception {
        ensureDataDir();
        return DriverManager.getConnection(jdbcUrl);
    }

    /** Create tables if they don't exist. Safe to run every startup. */
    public void initSchema() throws Exception {
        try (Connection c = open(); Statement s = c.createStatement()) {
            s.execute("PRAGMA foreign_keys = ON;");

            s.execute("""
CREATE TABLE IF NOT EXISTS players (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  gamertag TEXT NOT NULL UNIQUE -- case-insensitive uniqueness enforced in app
);
""");

            s.execute("""
CREATE TABLE IF NOT EXISTS games (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  played_at TEXT NOT NULL,
  team_a_score INTEGER,
  team_b_score INTEGER,
  notes TEXT, -- reserved for future manual notes
  status TEXT NOT NULL DEFAULT 'DRAFT',
  image_path TEXT
);
""");

            s.execute("""
CREATE TABLE IF NOT EXISTS participants (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  game_id INTEGER NOT NULL,
  player_id INTEGER NOT NULL,
  team TEXT NOT NULL CHECK(team IN ('A','B')),

  pts INTEGER NOT NULL,
  reb INTEGER NOT NULL,
  ast INTEGER NOT NULL,
  stl INTEGER NOT NULL,
  blk INTEGER NOT NULL,
  fouls INTEGER NOT NULL,
  turnovers INTEGER NOT NULL,

  fgm INTEGER NOT NULL,
  fga INTEGER NOT NULL,
  tpm INTEGER NOT NULL,
  tpa INTEGER NOT NULL,
  ftm INTEGER NOT NULL,
  fta INTEGER NOT NULL,

  FOREIGN KEY(game_id) REFERENCES games(id) ON DELETE CASCADE,
  FOREIGN KEY(player_id) REFERENCES players(id)
);
""");

            s.execute("CREATE INDEX IF NOT EXISTS idx_participants_game ON participants(game_id);");
            s.execute("CREATE INDEX IF NOT EXISTS idx_participants_player ON participants(player_id);");
        }
    }

    private static void ensureDataDir() throws Exception {
        Path dataDir = Path.of("data");
        if (!Files.exists(dataDir)) {
            Files.createDirectories(dataDir);
        }
    }
}