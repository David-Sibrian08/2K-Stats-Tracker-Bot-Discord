package com.playnow.game;

import com.playnow.db.Db;
import com.playnow.security.AccessControl;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;

public class GameListener extends ListenerAdapter {

    private final Db db;

    public GameListener(Db db) {
        this.db = db;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!"game".equals(event.getName())) return;
        if (!"create".equals(event.getSubcommandName())) return;
        if (!AccessControl.allowWrite(event)) return;

        try {
            long gameId = insertDraftGame(LocalDate.now().toString());
            event.reply("🧾 Created **Game #" + gameId + "** (DRAFT)")
                    .setEphemeral(true)
                    .queue();
        } catch (Exception e) {
            event.reply("Failed to create game: " + e.getMessage())
                    .setEphemeral(true)
                    .queue();
        }
    }

    private long insertDraftGame(String playedDate) throws Exception {
        try (Connection c = db.open()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO games (played_at, status) VALUES (?, 'DRAFT')"
                )) {
                    ps.setString(1, playedDate);
                    ps.executeUpdate();
                }

                try (PreparedStatement ps2 = c.prepareStatement("SELECT last_insert_rowid()");
                     ResultSet rs = ps2.executeQuery()) {
                    rs.next();
                    long id = rs.getLong(1);
                    c.commit();
                    return id;
                }
            } catch (Exception e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }
}