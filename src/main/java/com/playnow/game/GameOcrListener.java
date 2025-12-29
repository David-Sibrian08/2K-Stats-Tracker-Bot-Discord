package com.playnow.game;

import com.playnow.db.Db;
import com.playnow.security.AccessControl;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * /game ocr
 *
 * Flow:
 * 1) Create DRAFT game
 * 2) Save FULL screenshot as receipt -> games.image_path (local file path)
 * 3) Crop stats table region, run Tesseract TSV, parse rows
 * 4) Insert participants (if OCR_WRITE_STATS=true)
 *
 * Known limitations (WIP):
 * - Shooting splits can be tokenized inconsistently (e.g. "1", "/", "11")
 * - Left-side icon gutter can leak noise depending on crop
 */
public class GameOcrListener extends ListenerAdapter {

    private final Db db;

    // If false, OCR runs but does NOT write to DB.
    private static final boolean OCR_WRITE_STATS = true;

    // If true, keep the crop image file used for OCR so you can inspect it.
    // If false, we delete it after OCR finishes (keeps data/images clean).
    private static final boolean OCR_DEBUG_KEEP_CROP = false;

    /**
     * Crop settings for FULL 3840x2160 screenshots (Xbox Series X)
     * - Save FULL screenshot as receipt
     * - OCR only the stats table area
     */
    private static final double CROP_X1 = 0.08;
    private static final double CROP_X2 = 0.985;
    private static final double CROP_Y1 = 0.14;
    private static final double CROP_Y2 = 0.78;

    // Row grouping tolerance for TSV word "top" coordinate.
    private static final int Y_THRESHOLD = 14;

    // Ignore tokens too close to crop-left (filters icon gutter leak).
    private static final double MIN_X_PERCENT = 0.03;

    // TSV confidence filter (throw away low-confidence garbage tokens).
    private static final int MIN_CONF = 40;

    public GameOcrListener(Db db) {
        this.db = db;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (!"game".equals(event.getName())) return;
        if (!"ocr".equals(event.getSubcommandName())) return;
        if (!AccessControl.allowWrite(event)) return;

        var imgOpt = event.getOption("image");
        if (imgOpt == null) {
            event.reply("Missing required option: image").setEphemeral(true).queue();
            return;
        }

        String imageUrl = imgOpt.getAsAttachment().getUrl();
        event.deferReply(true).queue();

        try {
            // 1) Create DRAFT game (do NOT set image_path here)
            long gameId = insertDraftGame(LocalDate.now().toString());

            // 2) Save FULL screenshot receipt locally, store local path in DB
            String localReceiptPath = downloadReceipt(gameId, imageUrl);
            updateGameImagePath(gameId, localReceiptPath);

            // 3) OCR + insert participants
            OcrResult res = ocrFromFullScreenshot(gameId, localReceiptPath);

            String msg =
                    "OCR upload received.\n" +
                            "Created **DRAFT** Game `#" + gameId + "`.\n" +
                            "Receipt saved to `" + localReceiptPath + "`.\n" +
                            "Matched **" + res.matched + "** human stat line(s).\n" +
                            "Written **" + res.written + "** to database.\n" +
                            "Mode: **" + (OCR_WRITE_STATS ? "WRITE ENABLED" : "WRITE DISABLED") + "**\n" +
                            "Next: `/game summary game_id:" + gameId + "` (review)";

            event.getHook().editOriginal(msg).queue();

        } catch (Exception e) {
            event.getHook().editOriginal("Failed OCR upload: " + e.getMessage()).queue();
        }
    }

    // ----------------------------
    // OCR from full screenshot
    // ----------------------------

    private record OcrResult(int matched, int written) {}

    private OcrResult ocrFromFullScreenshot(long gameId, String fullImagePath) throws Exception {
        BufferedImage full = ImageIO.read(new File(fullImagePath));
        if (full == null) throw new RuntimeException("Could not read image: " + fullImagePath);

        BufferedImage tableCrop = cropRelative(full, CROP_X1, CROP_X2, CROP_Y1, CROP_Y2);

        // We need a crop file on disk because tesseract CLI reads files.
        Path cropPath = Path.of("data", "images", "ocr_game_" + gameId + "_crop.png");
        Files.createDirectories(cropPath.getParent());
        ImageIO.write(tableCrop, "png", cropPath.toFile());

        if (OCR_DEBUG_KEEP_CROP) {
            System.out.println("Saved OCR crop: " + cropPath);
        }

        Map<String, Long> players = loadPlayersNormalized();
        String tsv = runTesseractTSV(cropPath.toString());
        List<OcrRow> rows = groupRowsFromTsv(tsv, tableCrop.getWidth());

        int matched = 0;
        int written = 0;

        for (OcrRow row : rows) {
            Long playerId = matchPlayerId(row, players);
            if (playerId == null) continue;

            String team = (row.centerY < (tableCrop.getHeight() / 2)) ? "A" : "B";

            StatLine stats = parseStatsFromRow(row);
            if (stats == null) {
                // “WIP” debug signal
                System.out.println("PARSE FAILED TOKENS: " + row.tokens);
                continue;
            }

            matched++;

            if (OCR_WRITE_STATS) {
                upsertParticipant(
                        gameId,
                        playerId,
                        team,
                        stats.pts, stats.reb, stats.ast, stats.stl, stats.blk, stats.fouls, stats.turnovers,
                        stats.fgm, stats.fga, stats.tpm, stats.tpa, stats.ftm, stats.fta
                );
                written++;
            }
        }

        System.out.println("OCR matched=" + matched + " written=" + written + " (write=" + OCR_WRITE_STATS + ")");

        // Keep folder clean unless debugging
        if (!OCR_DEBUG_KEEP_CROP) {
            try { Files.deleteIfExists(cropPath); } catch (Exception ignored) {}
        }

        return new OcrResult(matched, written);
    }

    // ----------------------------
    // Players lookup
    // ----------------------------

    private Map<String, Long> loadPlayersNormalized() throws Exception {
        Map<String, Long> map = new HashMap<>();
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("SELECT id, gamertag FROM players");
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                long id = rs.getLong("id");
                String gt = rs.getString("gamertag");
                map.put(norm(gt), id);
            }
        }
        return map;
    }

    private Long matchPlayerId(OcrRow row, Map<String, Long> players) {
        // Join all normalized tokens so splits like ["@", "lying", "bible"] still match "lyingbible"
        String joined = row.tokens.stream()
                .map(GameOcrListener::norm)
                .filter(s -> !s.isBlank())
                .reduce("", String::concat);

        for (Map.Entry<String, Long> e : players.entrySet()) {
            if (joined.contains(e.getKey())) return e.getValue();
        }
        return null;
    }

    // ----------------------------
    // Parse stats
    // ----------------------------

    private StatLine parseStatsFromRow(OcrRow row) {
        String rawUpper = String.join(" ", row.tokens).toUpperCase(Locale.ROOT);
        if (rawUpper.contains("PTS") || rawUpper.contains("TOTAL")) return null;

        // Map: PTS REB AST STL BLK FOULS TO then FG/3PT/FT pairs
        Pattern pairPat = Pattern.compile("^(\\d{1,2})\\s*/\\s*(\\d{1,2})$");
        Pattern intPat  = Pattern.compile("\\b\\d{1,3}\\b");

        List<Integer> intsBeforeFirstPair = new ArrayList<>();
        List<IntPair> pairs = new ArrayList<>();
        boolean seenFirstPair = false;

        for (String tok : row.tokens) {
            String s = tok == null ? "" : tok.trim();
            if (s.isBlank()) continue;

            // Fix common OCR mistakes before pair matching
            s = s.replace('O', '0').replace('o', '0');
            s = s.replace('V', '/').replace('v', '/');

            // Skip grades like B-, A+, etc.
            if (s.matches("^[ABCDF][+-]?$")) continue;

            Matcher pm = pairPat.matcher(s);
            if (pm.matches()) {
                int a = safeParse(pm.group(1));
                int b = safeParse(pm.group(2));
                if (a >= 0 && b >= 0) pairs.add(new IntPair(a, b));
                seenFirstPair = true;
                continue;
            }

            if (!seenFirstPair) {
                Matcher im = intPat.matcher(s);
                while (im.find()) {
                    int v = safeParse(im.group());
                    if (v >= 0) intsBeforeFirstPair.add(v);
                }
            }
        }

        if (pairs.isEmpty()) return null;
        if (intsBeforeFirstPair.size() < 7) return null;

        // Use LAST 7 ints before the first x/y pair: PTS REB AST STL BLK FOULS TO
        int n = intsBeforeFirstPair.size();
        List<Integer> last7 = intsBeforeFirstPair.subList(n - 7, n);

        int pts   = last7.get(0);
        int reb   = last7.get(1);
        int ast   = last7.get(2);
        int stl   = last7.get(3);
        int blk   = last7.get(4);
        int fouls = last7.get(5);
        int to    = last7.get(6);

        IntPair fg = pairs.get(0);
        IntPair tp = pairs.size() > 1 ? pairs.get(1) : new IntPair(0, 0);
        IntPair ft = pairs.size() > 2 ? pairs.get(2) : new IntPair(0, 0);

        if (fg.a > fg.b || tp.a > tp.b || ft.a > ft.b) return null;

        return new StatLine(
                pts, reb, ast, stl, blk, fouls, to,
                fg.a, fg.b,
                tp.a, tp.b,
                ft.a, ft.b
        );
    }

    // ----------------------------
    // TSV -> Rows
    // ----------------------------

    private static class OcrRow {
        final int centerY;
        final List<String> tokens;

        OcrRow(int centerY, List<String> tokens) {
            this.centerY = centerY;
            this.tokens = tokens;
        }
    }

    private static class OcrToken {
        final String text;
        final int x;
        final int y;

        OcrToken(String text, int x, int y) {
            this.text = text;
            this.x = x;
            this.y = y;
        }
    }

    private List<OcrRow> groupRowsFromTsv(String tsv, int cropWidth) {
        int minX = (int) (cropWidth * MIN_X_PERCENT);

        String[] lines = tsv.split("\\R");
        List<OcrToken> tokens = new ArrayList<>();

        // TSV columns:
        // level page_num block_num par_num line_num word_num left top width height conf text
        for (int i = 1; i < lines.length; i++) {
            String[] parts = lines[i].split("\t");
            if (parts.length < 12) continue;

            String text = parts[11].trim();
            if (text.isBlank()) continue;

            int left = safeParse(parts[6]);
            int top  = safeParse(parts[7]);
            int conf = safeParse(parts[10]);

            if (left < minX) continue;
            if (conf >= 0 && conf < MIN_CONF) continue;

            tokens.add(new OcrToken(text, left, top));
        }

        tokens.sort(Comparator.comparingInt((OcrToken t) -> t.y).thenComparingInt(t -> t.x));

        List<OcrRow> rows = new ArrayList<>();
        List<OcrToken> current = new ArrayList<>();
        int currentY = Integer.MIN_VALUE;

        for (OcrToken t : tokens) {
            if (current.isEmpty()) {
                current.add(t);
                currentY = t.y;
                continue;
            }

            if (Math.abs(t.y - currentY) <= Y_THRESHOLD) {
                current.add(t);
            } else {
                rows.add(toRow(current));
                current.clear();
                current.add(t);
                currentY = t.y;
            }
        }

        if (!current.isEmpty()) rows.add(toRow(current));
        return rows;
    }

    private OcrRow toRow(List<OcrToken> toks) {
        toks.sort(Comparator.comparingInt(a -> a.x));
        int avgY = (int) toks.stream().mapToInt(t -> t.y).average().orElse(0);

        List<String> texts = new ArrayList<>(toks.size());
        for (OcrToken t : toks) texts.add(t.text);

        return new OcrRow(avgY, texts);
    }

    private String runTesseractTSV(String imageFilePath) throws Exception {
        List<String> cmd = List.of(
                "tesseract",
                imageFilePath,
                "stdout",
                "-l", "eng",
                "--oem", "1",
                "--psm", "6",
                "tsv"
        );

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);

        Process p = pb.start();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            in.transferTo(baos);
        }

        int code = p.waitFor();
        if (code != 0) throw new RuntimeException("tesseract TSV failed");

        return baos.toString(StandardCharsets.UTF_8);
    }

    // ----------------------------
    // participants upsert
    // ----------------------------

    private void upsertParticipant(
            long gameId, long playerId, String team,
            int pts, int reb, int ast, int stl, int blk, int fouls, int turnovers,
            int fgm, int fga, int tpm, int tpa, int ftm, int fta
    ) throws Exception {
        try (Connection c = db.open()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement del = c.prepareStatement(
                        "DELETE FROM participants WHERE game_id = ? AND player_id = ?"
                )) {
                    del.setLong(1, gameId);
                    del.setLong(2, playerId);
                    del.executeUpdate();
                }

                try (PreparedStatement ins = c.prepareStatement(
                        """
                        INSERT INTO participants (
                          game_id, player_id, team,
                          pts, reb, ast, stl, blk, fouls, turnovers,
                          fgm, fga, tpm, tpa, ftm, fta
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """
                )) {
                    int i = 1;
                    ins.setLong(i++, gameId);
                    ins.setLong(i++, playerId);
                    ins.setString(i++, team);

                    ins.setInt(i++, pts);
                    ins.setInt(i++, reb);
                    ins.setInt(i++, ast);
                    ins.setInt(i++, stl);
                    ins.setInt(i++, blk);
                    ins.setInt(i++, fouls);
                    ins.setInt(i++, turnovers);

                    ins.setInt(i++, fgm);
                    ins.setInt(i++, fga);
                    ins.setInt(i++, tpm);
                    ins.setInt(i++, tpa);
                    ins.setInt(i++, ftm);
                    ins.setInt(i++, fta);

                    ins.executeUpdate();
                }

                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    // ----------------------------
    // DB helpers
    // ----------------------------

    private long insertDraftGame(String playedAt) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO games (played_at, status) VALUES (?, 'DRAFT')"
             )) {
            ps.setString(1, playedAt);
            ps.executeUpdate();
        }

        try (Connection c = db.open();
             PreparedStatement ps2 = c.prepareStatement("SELECT last_insert_rowid()");
             ResultSet rs = ps2.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void updateGameImagePath(long gameId, String localPath) throws Exception {
        try (Connection c = db.open();
             PreparedStatement ps = c.prepareStatement("UPDATE games SET image_path = ? WHERE id = ?")) {
            ps.setString(1, localPath);
            ps.setLong(2, gameId);
            ps.executeUpdate();
        }
    }

    private String downloadReceipt(long gameId, String imageUrl) throws Exception {
        Path dir = Path.of("data", "images");
        Files.createDirectories(dir);

        String ext = ".png";
        int q = imageUrl.indexOf('?');
        String clean = (q >= 0) ? imageUrl.substring(0, q) : imageUrl;
        int dot = clean.lastIndexOf('.');
        if (dot >= 0 && dot > clean.lastIndexOf('/')) {
            String found = clean.substring(dot);
            if (found.length() <= 5) ext = found;
        }

        Path out = dir.resolve("game_" + gameId + ext);

        try (InputStream in = new URL(imageUrl).openStream()) {
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
        }

        return out.toString();
    }

    // ----------------------------
    // Utils
    // ----------------------------

    private static BufferedImage cropRelative(BufferedImage img, double x1, double x2, double y1, double y2) {
        int w = img.getWidth();
        int h = img.getHeight();

        int x = clamp((int) Math.round(w * x1), 0, w - 1);
        int xEnd = clamp((int) Math.round(w * x2), x + 1, w);
        int y = clamp((int) Math.round(h * y1), 0, h - 1);
        int yEnd = clamp((int) Math.round(h * y2), y + 1, h);

        return img.getSubimage(x, y, xEnd - x, yEnd - y);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int safeParse(String s) {
        try { return Integer.parseInt(s.trim()); }
        catch (Exception e) { return -1; }
    }

    /**
     * Normalize for gamertag matching. Strips icon junk like '@', arrows, logos, etc.
     * '@' before a tag is a 2K overlay artifact (not part of the name).
     */
    private static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private record IntPair(int a, int b) {}

    private static class StatLine {
        final int pts, reb, ast, stl, blk, fouls, turnovers;
        final int fgm, fga, tpm, tpa, ftm, fta;

        StatLine(int pts, int reb, int ast, int stl, int blk, int fouls, int turnovers,
                 int fgm, int fga, int tpm, int tpa, int ftm, int fta) {
            this.pts = pts;
            this.reb = reb;
            this.ast = ast;
            this.stl = stl;
            this.blk = blk;
            this.fouls = fouls;
            this.turnovers = turnovers;
            this.fgm = fgm;
            this.fga = fga;
            this.tpm = tpm;
            this.tpa = tpa;
            this.ftm = ftm;
            this.fta = fta;
        }
    }
}