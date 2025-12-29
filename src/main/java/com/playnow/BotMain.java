package com.playnow;

import com.playnow.db.Db;
import com.playnow.game.*;
import com.playnow.leaderboard.LeaderboardListener;
import com.playnow.player.PlayerListener;
import com.playnow.player.PlayerSummaryListener;
import com.playnow.player.PlayerVsListener;
import com.playnow.player.PlayerWithListener;
import com.playnow.stat.StatListener;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;

public class BotMain {

    public static void main(String[] args) throws Exception {
        String token = requireEnv("DISCORD_TOKEN");
        String guildId = requireEnv("DISCORD_GUILD_ID");

        // Initialize DB
        Db db = Db.defaultDb();
        try {
            db.initSchema();
            System.out.println("DB initialized");
        } catch (Exception e) {
            System.err.println("Failed to initialize DB: " + e.getMessage());
            throw e;
        }

        // Start bot + listeners
        JDA jda = JDABuilder.createDefault(token)
                .addEventListeners(
                        new PingListener(),
                        new PlayerListener(db),
                        new GameListener(db),
                        new StatListener(db),
                        new GameSummaryListener(db),
                        new GameScoreListener(db),
                        new PlayerSummaryListener(db),
                        new PlayerWithListener(db),
                        new PlayerVsListener(db),
                        new GameFinalizeListener(db),
                        new GameUnfinalizeListener(db),
                        new GameListListener(db),
                        new LeaderboardListener(db),
                        new GameOcrListener(db)
                )
                .build();

        jda.awaitReady();

        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            throw new IllegalStateException(
                    "Could not find guild for DISCORD_GUILD_ID=" + guildId +
                            ". Is the bot in that server?"
            );
        }

        // Register slash commands (guild-only)
        guild.updateCommands()
                .addCommands(
                        Commands.slash("ping", "Is the bot alive?"),
                        Commands.slash("leaderboard", "Show everyone on the leaderboard"),
                        buildPlayerCommand(),
                        buildGameCommand(),
                        buildStatCommand()
                )
                .queue();

        System.out.println("Bot ready as: " + jda.getSelfUser().getAsTag());
    }

    private static String requireEnv(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Missing " + key);
        }
        return v;
    }

    private static net.dv8tion.jda.api.interactions.commands.build.SlashCommandData buildPlayerCommand() {
        return Commands.slash("player", "Player commands")
                .addSubcommands(
                        new SubcommandData("add", "Add a player (gamertag)")
                                .addOption(OptionType.STRING, "gamertag", "e.g. Lying Bible", true),

                        new SubcommandData("summary", "Show a player's averages and shooting splits")
                                .addOption(OptionType.STRING, "gamertag", "Player's gamertag", true),

                        new SubcommandData("with", "Show a player's averages when paired with a teammate")
                                .addOption(OptionType.STRING, "gamertag", "Player's gamertag", true)
                                .addOption(OptionType.STRING, "teammate", "Teammate's gamertag", true),

                        new SubcommandData("vs", "Show a player's averages when playing against an opponent")
                                .addOption(OptionType.STRING, "gamertag", "Player gamertag", true)
                                .addOption(OptionType.STRING, "opponent", "Opponent's gamertag", true)
                );
    }

    private static net.dv8tion.jda.api.interactions.commands.build.SlashCommandData buildGameCommand() {
        return Commands.slash("game", "Game commands")
                .addSubcommands(
                        new SubcommandData("create", "Create a new game draft"),

                        new SubcommandData("summary", "Show the box score for a game")
                                .addOption(OptionType.INTEGER, "game_id", "Game id (e.g. 1)", true),

                        new SubcommandData("score", "Add the score to an already created game")
                                .addOption(OptionType.INTEGER, "game_id", "Game id", true)
                                .addOption(OptionType.INTEGER, "team_a", "Team A score", true)
                                .addOption(OptionType.INTEGER, "team_b", "Team B score", true),

                        new SubcommandData("finalize", "Lock in a game and take it out of DRAFT status (uneditable)")
                                .addOption(OptionType.INTEGER, "game_id", "Game id", true),

                        new SubcommandData("unfinalize", "Unlock a game and and make it editable")
                                .addOption(OptionType.INTEGER, "game_id", "Game id", true),

                        new SubcommandData("list", "List recent games")
                                .addOption(OptionType.STRING, "status", "Filter by status (DRAFT or FINAL)", false)
                                .addOption(OptionType.INTEGER, "limit", "How many games to show (1-25)", false),

                        new SubcommandData("find", "Find games by gamertag")
                                .addOption(OptionType.STRING, "gamertag", "Gamertag to search for", true)
                                .addOption(OptionType.INTEGER, "limit", "How many games (1-25)", false),

                        new SubcommandData("ocr", "Upload a screenshot for auto reading (creates a DRAFT)")
                                .addOption(OptionType.ATTACHMENT, "image", "2K screenshot to use as receipt", true)
                );
    }

    private static net.dv8tion.jda.api.interactions.commands.build.SlashCommandData buildStatCommand() {
        return Commands.slash("stat", "Stat commands")
                .addSubcommands(
                        new SubcommandData("add", "Add a player stat line to a game")
                                .addOption(OptionType.INTEGER, "game_id", "Game id (e.g. 3)", true)
                                .addOption(OptionType.STRING, "gamertag", "Must match an added player", true)
                                .addOption(OptionType.STRING, "team", "A or B", true)

                                .addOption(OptionType.INTEGER, "pts", "Points", true)
                                .addOption(OptionType.INTEGER, "reb", "Rebounds", true)
                                .addOption(OptionType.INTEGER, "ast", "Assists", true)
                                .addOption(OptionType.INTEGER, "stl", "Steals", true)
                                .addOption(OptionType.INTEGER, "blk", "Blocks", true)
                                .addOption(OptionType.INTEGER, "fouls", "Fouls", true)
                                .addOption(OptionType.INTEGER, "to", "Turnovers", true)

                                .addOption(OptionType.INTEGER, "fgm", "FG made", true)
                                .addOption(OptionType.INTEGER, "fga", "FG attempts", true)
                                .addOption(OptionType.INTEGER, "tpm", "3PT made", true)
                                .addOption(OptionType.INTEGER, "tpa", "3PT attempts", true)
                                .addOption(OptionType.INTEGER, "ftm", "FT made", true)
                                .addOption(OptionType.INTEGER, "fta", "FT attempts", true)
                );
    }
}