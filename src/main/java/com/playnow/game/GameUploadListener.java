package com.playnow.game;

import com.playnow.db.Db;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.utils.AttachmentProxy;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDateTime;

public class GameUploadListener extends ListenerAdapter {

    private final Db db;

    public GameUploadListener(Db db) {
        this.db = db;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!event.getName().equals("game")) return;
        if (!"upload".equals(event.getSubcommandName())) return;

        handleUpload(event);
    }

    private void handleUpload(SlashCommandInteractionEvent event) {
        var gameOpt = event.getOption("game_id");
        var fileOpt = event.getOption("screenshot");

        if (gameOpt == null || fileOpt == null) {
            event.reply("Missing required options: game_id and screenshot").setEphemeral(true).queue();
            return;
        }

        long gameId = gameOpt.getAsLong();
        var attachment = fileOpt.getAsAttachment();

        // We will reply immediately, then edit after download finishes
        event.deferReply(true).queue(); // ephemeral

        try {
            ensureImagesDir();

            String safeName = "game_" + gameId + "_" + LocalDateTime.now().toString().replace(":", "-");
            String ext = guessExtension(attachment.getFileName());
            Path target = Path.of("data", "images", safeName + ext);

            // Download attachment (Discord-hosted) to local file
            AttachmentProxy proxy = attachment.getProxy();
            try (InputStream in = proxy.download().join()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }

            // Save path to DB
            int updated = updateGameImagePath(gameId, target.toString());
            if (updated == 0) {
                // If no such game, delete the file so we don't keep junk
                Files.deleteIfExists(target);
                event.getHook().editOriginal("Game #" + gameId + " not found. Upload discarded.").queue();
                return;
            }

            event.getHook().editOriginal("✅ Screenshot saved for Game #" + gameId + " (private).").queue();

        } catch (Exception e) {
            event.getHook().editOriginal("Failed to save screenshot: " + e.getMessage()).queue();
        }
    }

    private int updateGameImagePath(long gameId, String imagePath) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE games SET image_path = ? WHERE id = ?"
             )) {
            ps.setString(1, imagePath);
            ps.setLong(2, gameId);
            return ps.executeUpdate();
        }
    }

    private void ensureImagesDir() throws Exception {
        Path dir = Path.of("data", "images");
        if (!Files.exists(dir)) Files.createDirectories(dir);
    }

    private String guessExtension(String originalName) {
        if (originalName == null) return ".png";
        String lower = originalName.toLowerCase();
        if (lower.endsWith(".png")) return ".png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return ".jpg";
        if (lower.endsWith(".webp")) return ".webp";
        return ".png";
    }
}