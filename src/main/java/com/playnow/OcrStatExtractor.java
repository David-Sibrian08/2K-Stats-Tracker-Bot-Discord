package com.playnow;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

public class OcrStatExtractor {

    /**
     * One detected stat line for a human player.
     * team = "A" for the top table, "B" for the bottom table.
     */
    public record StatLine(
            String team,
            String gamertag,
            int pts, int reb, int ast, int stl, int blk, int fouls, int turnovers,
            int fgm, int fga, int tpm, int tpa, int ftm, int fta
    ) {}

    /**
     * Pass only your human gamertags (from DB players table).
     * We'll only return lines for those names to avoid AI players.
     */
    public List<StatLine> extractStatLines(String imagePath, Set<String> humanGamertags) throws Exception {
        BufferedImage img = ImageIO.read(new File(imagePath));
        if (img == null) return List.of();

        // Crop where the big stats tables live (center-right region)
        BufferedImage crop = cropRelative(img,
                0.28, 0.98,   // right side where stat tables are
                0.12, 0.80    // vertical span covering both teams rows
        );

        // Debug crop so we can visually verify
        Path debug = Path.of("data", "images", "debug_stats_crop.png");
        Files.createDirectories(debug.getParent());
        ImageIO.write(crop, "png", debug.toFile());
        System.out.println("Saved stats debug crop to: " + debug);

        // OCR TSV so we can use row position (top y)
        Path tmp = Files.createTempFile("stats_crop_", ".png");
        ImageIO.write(crop, "png", tmp.toFile());

        String tsv;
        try {
            tsv = runTesseractTSV(tmp.toString());
        } finally {
            Files.deleteIfExists(tmp);
        }

        // Convert TSV -> "lines" that keep their y-position within the crop
        List<OcrLine> lines = tsvToLinesWithY(tsv);

        // Midpoint split: top half = Team A, bottom half = Team B
        int midY = crop.getHeight() / 2;

        List<StatLine> out = new ArrayList<>();
        for (OcrLine ol : lines) {
            String match = findGamertagInLine(ol.text, humanGamertags);
            if (match == null) continue;

            String team = (ol.topY < midY) ? "A" : "B";

            StatLine parsed = tryParseLine(team, match, ol.text);
            if (parsed != null) out.add(parsed);
        }

        return out;
    }

    // ----------------------------
    // Parsing (simple first-pass)
    // ----------------------------

    private String findGamertagInLine(String line, Set<String> humans) {
        String lower = line.toLowerCase(Locale.ROOT);
        for (String g : humans) {
            if (lower.contains(g.toLowerCase(Locale.ROOT))) return g;
        }
        return null;
    }

    private StatLine tryParseLine(String team, String gamertag, String rawLine) {
        String line = normalize(rawLine);

        // Pull out shot pairs like 9/20, 0/1, 4/4
        List<int[]> pairs = extractPairs(line);
        if (pairs.size() < 3) return null; // needs FG, 3PT, FT

        // Remove shot pairs then pull out ints for base stats
        String withoutPairs = line.replaceAll("\\b\\d+\\s*/\\s*\\d+\\b", " ");
        List<Integer> nums = extractInts(withoutPairs);

        // Need: PTS REB AST STL BLK FOULS TO
        if (nums.size() < 7) return null;

        int pts = nums.get(0);
        int reb = nums.get(1);
        int ast = nums.get(2);
        int stl = nums.get(3);
        int blk = nums.get(4);
        int fouls = nums.get(5);
        int turnovers = nums.get(6);

        int[] fg = pairs.get(0);
        int[] tp = pairs.get(1);
        int[] ft = pairs.get(2);

        return new StatLine(
                team,
                gamertag,
                pts, reb, ast, stl, blk, fouls, turnovers,
                fg[0], fg[1],
                tp[0], tp[1],
                ft[0], ft[1]
        );
    }

    private String normalize(String s) {
        return s.replace('\u00A0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private List<int[]> extractPairs(String s) {
        var m = Pattern.compile("\\b(\\d+)\\s*/\\s*(\\d+)\\b").matcher(s);
        List<int[]> out = new ArrayList<>();
        while (m.find()) {
            int a = Integer.parseInt(m.group(1));
            int b = Integer.parseInt(m.group(2));
            out.add(new int[]{a, b});
        }
        return out;
    }

    private List<Integer> extractInts(String s) {
        var m = Pattern.compile("\\b\\d+\\b").matcher(s);
        List<Integer> out = new ArrayList<>();
        while (m.find()) out.add(Integer.parseInt(m.group()));
        return out;
    }

    // ----------------------------
    // TSV helpers (keep y-position)
    // ----------------------------

    private record OcrLine(int topY, String text) {}

    private List<OcrLine> tsvToLinesWithY(String tsv) {
        // TSV header:
        // level page_num block_num par_num line_num word_num left top width height conf text
        String[] rows = tsv.split("\\R");
        if (rows.length <= 1) return List.of();

        class Acc {
            int minTop = Integer.MAX_VALUE;
            StringBuilder sb = new StringBuilder();
        }

        // Key by line_num so we build one string per OCR line
        Map<String, Acc> lineMap = new LinkedHashMap<>();

        for (int i = 1; i < rows.length; i++) {
            String r = rows[i];
            String[] parts = r.split("\\t");
            if (parts.length < 12) continue;

            String lineNum = parts[4];     // line_num
            String topStr = parts[7];      // top
            String text = parts[11];       // text
            if (text == null || text.isBlank()) continue;

            int top;
            try {
                top = Integer.parseInt(topStr);
            } catch (NumberFormatException e) {
                continue;
            }

            Acc acc = lineMap.computeIfAbsent(lineNum, k -> new Acc());
            acc.minTop = Math.min(acc.minTop, top);
            acc.sb.append(text).append(" ");
        }

        List<OcrLine> out = new ArrayList<>();
        for (Acc acc : lineMap.values()) {
            String line = acc.sb.toString().trim();
            if (!line.isBlank()) out.add(new OcrLine(acc.minTop, line));
        }

        // sort top-to-bottom just to be safe
        out.sort(Comparator.comparingInt(OcrLine::topY));
        return out;
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
    // Crop helpers
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
}