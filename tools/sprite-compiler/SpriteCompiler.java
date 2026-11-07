import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Compiles palette-indexed text sprites into a PNG atlas.
 *
 * <pre>
 *   java tools/sprite-compiler/SpriteCompiler.java assets
 * </pre>
 *
 * Input:  {@code <dir>/palette.json} ({"K": {"name": "ink", "hex": "#1b1a26"}, ...}) and {@code <dir>/sprites/*.pxl}.
 * Output: {@code <dir>/generated/atlas.png}, {@code atlas.json}, {@code preview.png}, {@code sprites/<name>.png}.
 *
 * .pxl format (one directive per line; '#' starts a comment line):
 * <pre>
 *   @sprite name          followed by rows of palette keys, one char per pixel; a blank line ends it.
 *                         '.' is always transparent. All rows must be the same width.
 *   @mirror name from src horizontal flip of src
 *   @recolor name from src a=b c=d ...   copy of src with keys replaced (all at once)
 *   @anim name frameMs frame1 frame2 ...
 * </pre>
 *
 * No dependencies beyond the JDK. Output is deterministic (sprites sorted by name).
 */
public class SpriteCompiler {
    record Sprite(String name, int w, int h, char[][] px) {}
    record Anim(String name, int frameMs, List<String> frames) {}

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : "assets");
        Map<Character, Color> palette = readPalette(root.resolve("palette.json"));
        Map<String, Sprite> sprites = new TreeMap<>();
        Map<String, Anim> anims = new TreeMap<>();

        List<Path> files;
        try (Stream<Path> s = Files.list(root.resolve("sprites"))) {
            files = s.filter(p -> p.toString().endsWith(".pxl")).sorted().toList();
        }
        for (Path f : files) parse(f, palette, sprites, anims);
        for (Anim a : anims.values())
            for (String fr : a.frames())
                if (!sprites.containsKey(fr)) fail("animation " + a.name() + ": unknown frame " + fr);

        Path out = root.resolve("generated");
        Files.createDirectories(out.resolve("sprites"));

        // Shelf packing, 1px gap, fixed atlas width.
        int atlasW = 256, x = 0, y = 0, shelfH = 0;
        Map<String, int[]> rects = new LinkedHashMap<>();
        for (Sprite s : sprites.values()) {
            if (x + s.w() > atlasW) { x = 0; y += shelfH + 1; shelfH = 0; }
            rects.put(s.name(), new int[]{x, y, s.w(), s.h()});
            x += s.w() + 1;
            shelfH = Math.max(shelfH, s.h());
        }
        int atlasH = y + shelfH;
        BufferedImage atlas = new BufferedImage(atlasW, Math.max(1, atlasH), BufferedImage.TYPE_INT_ARGB);
        for (Sprite s : sprites.values()) {
            int[] r = rects.get(s.name());
            BufferedImage img = render(s, palette);
            atlas.getGraphics().drawImage(img, r[0], r[1], null);
            ImageIO.write(img, "png", out.resolve("sprites").resolve(s.name() + ".png").toFile());
        }
        ImageIO.write(atlas, "png", out.resolve("atlas.png").toFile());
        Files.writeString(out.resolve("atlas.json"), json(palette, rects, anims), StandardCharsets.UTF_8);
        ImageIO.write(preview(sprites, palette), "png", out.resolve("preview.png").toFile());
        System.out.println("Compiled " + sprites.size() + " sprites, " + anims.size() + " animations -> " + out);
    }

    static Map<Character, Color> readPalette(Path p) throws IOException {
        String text = Files.readString(p, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\"(.)\"\\s*:\\s*\\{[^}]*?\"hex\"\\s*:\\s*\"#([0-9a-fA-F]{6})\"").matcher(text);
        Map<Character, Color> pal = new LinkedHashMap<>();
        while (m.find()) {
            char k = m.group(1).charAt(0);
            if (k == '.') fail("palette: '.' is reserved for transparent");
            pal.put(k, new Color(Integer.parseInt(m.group(2), 16)));
        }
        if (pal.isEmpty()) fail("palette: no colours found in " + p);
        return pal;
    }

    static void parse(Path f, Map<Character, Color> pal, Map<String, Sprite> sprites, Map<String, Anim> anims) throws IOException {
        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        String current = null;
        List<String> rows = new ArrayList<>();
        int startLine = 0;
        for (int i = 0; i <= lines.size(); i++) {
            String raw = i < lines.size() ? lines.get(i) : "";
            String line = raw.strip();
            if (current != null) {
                if (!line.isEmpty() && !line.startsWith("@") && !line.startsWith("#")) { rows.add(line); continue; }
                put(sprites, build(f, startLine, current, rows, pal));
                current = null;
                rows = new ArrayList<>();
            }
            if (line.isEmpty() || line.startsWith("#")) continue;
            String where = f.getFileName() + ":" + (i + 1);
            String[] t = line.split("\\s+");
            switch (t[0]) {
                case "@sprite" -> { need(t, 2, where); current = t[1]; startLine = i + 1; }
                case "@mirror" -> {
                    need(t, 4, where);
                    Sprite src = src(sprites, t[3], where);
                    char[][] px = new char[src.h()][src.w()];
                    for (int r = 0; r < src.h(); r++)
                        for (int c = 0; c < src.w(); c++) px[r][c] = src.px()[r][src.w() - 1 - c];
                    put(sprites, new Sprite(t[1], src.w(), src.h(), px));
                }
                case "@recolor" -> {
                    need(t, 4, where);
                    Sprite src = src(sprites, t[3], where);
                    Map<Character, Character> map = new HashMap<>();
                    for (int k = 4; k < t.length; k++) {
                        if (t[k].length() != 3 || t[k].charAt(1) != '=') fail(where + ": bad mapping " + t[k]);
                        char to = t[k].charAt(2);
                        if (to != '.' && !pal.containsKey(to)) fail(where + ": unknown palette key " + to);
                        map.put(t[k].charAt(0), to);
                    }
                    char[][] px = new char[src.h()][src.w()];
                    for (int r = 0; r < src.h(); r++)
                        for (int c = 0; c < src.w(); c++) px[r][c] = map.getOrDefault(src.px()[r][c], src.px()[r][c]);
                    put(sprites, new Sprite(t[1], src.w(), src.h(), px));
                }
                case "@anim" -> {
                    need(t, 4, where);
                    anims.put(t[1], new Anim(t[1], Integer.parseInt(t[2]), List.of(Arrays.copyOfRange(t, 3, t.length))));
                }
                default -> fail(where + ": unknown directive " + t[0]);
            }
        }
    }

    static Sprite build(Path f, int line, String name, List<String> rows, Map<Character, Color> pal) {
        if (rows.isEmpty()) fail(f.getFileName() + ":" + line + ": sprite " + name + " has no rows");
        int w = rows.get(0).length();
        char[][] px = new char[rows.size()][];
        for (int r = 0; r < rows.size(); r++) {
            String row = rows.get(r);
            if (row.length() != w)
                fail(f.getFileName() + ":" + (line + 1 + r) + ": sprite " + name + " row " + r + " is " + row.length() + " wide, expected " + w);
            for (char c : row.toCharArray())
                if (c != '.' && !pal.containsKey(c))
                    fail(f.getFileName() + ":" + (line + 1 + r) + ": unknown palette key '" + c + "' in " + name);
            px[r] = row.toCharArray();
        }
        return new Sprite(name, w, rows.size(), px);
    }

    static Sprite src(Map<String, Sprite> sprites, String name, String where) {
        Sprite s = sprites.get(name);
        if (s == null) fail(where + ": unknown source sprite " + name + " (define it earlier)");
        return s;
    }

    static void put(Map<String, Sprite> sprites, Sprite s) {
        if (sprites.put(s.name(), s) != null) fail("duplicate sprite " + s.name());
    }

    static void need(String[] t, int n, String where) {
        if (t.length < n) fail(where + ": expected at least " + n + " tokens in '" + String.join(" ", t) + "'");
    }

    static BufferedImage render(Sprite s, Map<Character, Color> pal) {
        BufferedImage img = new BufferedImage(s.w(), s.h(), BufferedImage.TYPE_INT_ARGB);
        for (int r = 0; r < s.h(); r++)
            for (int c = 0; c < s.w(); c++) {
                char k = s.px()[r][c];
                if (k != '.') img.setRGB(c, r, 0xFF000000 | pal.get(k).getRGB());
            }
        return img;
    }

    static String json(Map<Character, Color> pal, Map<String, int[]> rects, Map<String, Anim> anims) {
        StringBuilder b = new StringBuilder("{\n  \"image\": \"atlas.png\",\n  \"palette\": {");
        int i = 0;
        for (var e : pal.entrySet())
            b.append(i++ == 0 ? "\n" : ",\n").append("    \"").append(esc(e.getKey())).append("\": \"")
                    .append(String.format("#%06x", e.getValue().getRGB() & 0xFFFFFF)).append('"');
        b.append("\n  },\n  \"sprites\": {");
        i = 0;
        for (var e : rects.entrySet()) {
            int[] r = e.getValue();
            b.append(i++ == 0 ? "\n" : ",\n").append("    \"").append(e.getKey()).append("\": {\"x\": ").append(r[0])
                    .append(", \"y\": ").append(r[1]).append(", \"w\": ").append(r[2]).append(", \"h\": ").append(r[3]).append('}');
        }
        b.append("\n  },\n  \"animations\": {");
        i = 0;
        for (Anim a : anims.values()) {
            b.append(i++ == 0 ? "\n" : ",\n").append("    \"").append(a.name()).append("\": {\"frameMs\": ").append(a.frameMs()).append(", \"frames\": [");
            for (int k = 0; k < a.frames().size(); k++) b.append(k == 0 ? "" : ", ").append('"').append(a.frames().get(k)).append('"');
            b.append("]}");
        }
        return b.append("\n  }\n}\n").toString();
    }

    static String esc(char c) { return c == '"' || c == '\\' ? "\\" + c : String.valueOf(c); }

    /** Every sprite at 4x on a checkerboard, with its name underneath. */
    static BufferedImage preview(Map<String, Sprite> sprites, Map<Character, Color> pal) {
        int scale = 4, cell = 16 * scale + 16, labelH = 14, cols = 8;
        int rowsN = (sprites.size() + cols - 1) / cols;
        BufferedImage img = new BufferedImage(cols * cell, rowsN * (cell + labelH), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(0x24, 0x24, 0x2c));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int i = 0;
        for (Sprite s : sprites.values()) {
            int cx = (i % cols) * cell + 8, cy = (i / cols) * (cell + labelH) + 8;
            for (int yy = 0; yy < s.h() * scale; yy += 8)
                for (int xx = 0; xx < s.w() * scale; xx += 8) {
                    g.setColor(((xx + yy) / 8) % 2 == 0 ? new Color(0x50, 0x50, 0x5a) : new Color(0x3c, 0x3c, 0x46));
                    g.fillRect(cx + xx, cy + yy, Math.min(8, s.w() * scale - xx), Math.min(8, s.h() * scale - yy));
                }
            g.drawImage(render(s, pal), cx, cy, s.w() * scale, s.h() * scale, null);
            g.setColor(Color.WHITE);
            g.drawString(s.name(), cx - 4, cy + 16 * scale + 14);
            i++;
        }
        g.dispose();
        return img;
    }

    static void fail(String msg) {
        System.err.println("sprite-compiler: " + msg);
        System.exit(1);
        throw new IllegalStateException(msg);
    }
}
