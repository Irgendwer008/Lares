import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import javax.imageio.ImageIO;

/*
 * Lares – blocks distracting apps and websites; loosening needs a guardian code.
 * Copyright (C) 2026 Lares contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

/**
 * Renders the store graphics (icon 512 × 512, feature graphic 1024 × 500) from the logo SVGs in
 * docs/ with Java2D – no SVG renderer needed. Run from the project root:
 *   java -Djava.awt.headless=true tools/StoreGraphics.java
 * Output: fastlane/metadata/android/en-US/images/
 */
public class StoreGraphics {
    static Path2D parse(String d) {
        Path2D.Double p = new Path2D.Double();
        Matcher m = Pattern.compile("[MmLlHhVvCcZz]|-?(?:\\d+\\.?\\d*|\\.\\d+)(?:e-?\\d+)?").matcher(d);
        java.util.List<String> t = new ArrayList<>();
        while (m.find()) t.add(m.group());
        int i = 0; char cmd = 'M'; double x = 0, y = 0, sx = 0, sy = 0;
        while (i < t.size()) {
            if (Character.isLetter(t.get(i).charAt(0))) cmd = t.get(i++).charAt(0);
            switch (cmd) {
                case 'M', 'm' -> {
                    double nx = Double.parseDouble(t.get(i++)), ny = Double.parseDouble(t.get(i++));
                    if (cmd == 'm') { nx += x; ny += y; }
                    x = nx; y = ny; sx = x; sy = y; p.moveTo(x, y); cmd = cmd == 'm' ? 'l' : 'L';
                }
                case 'L', 'l' -> {
                    double nx = Double.parseDouble(t.get(i++)), ny = Double.parseDouble(t.get(i++));
                    if (cmd == 'l') { nx += x; ny += y; }
                    x = nx; y = ny; p.lineTo(x, y);
                }
                case 'H', 'h' -> { double v = Double.parseDouble(t.get(i++)); x = cmd == 'h' ? x + v : v; p.lineTo(x, y); }
                case 'V', 'v' -> { double v = Double.parseDouble(t.get(i++)); y = cmd == 'v' ? y + v : v; p.lineTo(x, y); }
                case 'C', 'c' -> {
                    double[] c = new double[6];
                    for (int k = 0; k < 6; k++) c[k] = Double.parseDouble(t.get(i++));
                    if (cmd == 'c') for (int k = 0; k < 6; k += 2) { c[k] += x; c[k + 1] += y; }
                    p.curveTo(c[0], c[1], c[2], c[3], c[4], c[5]); x = c[4]; y = c[5];
                }
                case 'Z', 'z' -> { p.closePath(); x = sx; y = sy; }
                default -> throw new IllegalStateException("unsupported " + cmd);
            }
        }
        return p;
    }

    static Graphics2D canvas(BufferedImage img, Color bg) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(bg);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        return g;
    }

    /** The `d` attribute of the path with [id] (or the first path if null). */
    static String pathData(String svg, String id) throws Exception {
        String text = Files.readString(Path.of(svg));
        Matcher m = Pattern.compile("<path(.*?)/>", Pattern.DOTALL).matcher(text);
        while (m.find()) {
            String attrs = m.group(1);
            if (id != null && !attrs.contains("id=\"" + id + "\"")) continue;
            Matcher d = Pattern.compile("\\sd=\"([^\"]+)\"").matcher(attrs);
            if (d.find()) return d.group(1);
        }
        throw new IllegalStateException("no path in " + svg);
    }

    public static void main(String[] a) throws Exception {
        String out = "fastlane/metadata/android/en-US/images";
        new File(out).mkdirs();
        Color parchment = new Color(0xED, 0xDC, 0xC6), clay = new Color(0x9A, 0x53, 0x26), brown = new Color(0x28, 0x17, 0x0B);
        // docs/Logo.svg: path1 is the L in 108-unit canvas coordinates (as in the launcher icon).
        Path2D l = parse(pathData("docs/Logo.svg", "path1"));
        Path2D word = parse(pathData("docs/Logo_long.svg", null));

        // Icon 512 x 512: same layout as the adaptive launcher icon (108-unit canvas, L scaled to
        // 78 % around the center, 0.6-unit outline). Play applies its own mask.
        BufferedImage icon = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas(icon, parchment);
        g.scale(512 / 108.0, 512 / 108.0);
        g.translate(54, 54); g.scale(0.78, 0.78); g.translate(-54, -54);
        g.setColor(clay); g.fill(l);
        g.setStroke(new BasicStroke(0.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 6f)); g.draw(l);
        g.dispose();
        ImageIO.write(icon, "png", new File(out, "icon.png"));

        // Feature graphic 1024 x 500: wordmark centered, 56 % of the width.
        BufferedImage fg = new BufferedImage(1024, 500, BufferedImage.TYPE_INT_RGB);
        g = canvas(fg, parchment);
        Rectangle2D b = word.getBounds2D();
        double s = 1024 * 0.56 / b.getWidth();
        g.translate(512 - b.getCenterX() * s, 250 - b.getCenterY() * s);
        g.scale(s, s);
        g.setColor(brown); g.fill(word);
        g.dispose();
        ImageIO.write(fg, "png", new File(out, "featureGraphic.png"));
    }
}
