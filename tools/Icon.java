import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Draws the DJ plugin icon: a vinyl record with an AI sparkle on a gradient tile.
 * Run with: java tools/Icon.java plugin/HTML/EN/plugins/DJ/html/images/icon.png
 */
public class Icon {

    public static void main(String[] args) throws Exception {
        int size = 512;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        // Tile
        g.setPaint(new GradientPaint(0, 0, new Color(0x3B1F8F), size, size, new Color(0x0E8C8C)));
        g.fill(new RoundRectangle2D.Double(0, 0, size, size, 112, 112));

        // Record, slightly left and low so the sparkle has room
        double cx = 236;
        double cy = 276;
        double r = 186;
        g.setColor(new Color(0, 0, 0, 70));
        g.fill(new Ellipse2D.Double(cx - r + 6, cy - r + 10, 2 * r, 2 * r));
        g.setColor(new Color(0x141418));
        g.fill(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
        g.setStroke(new BasicStroke(2.2f));
        for (double gr = r - 14; gr > 70; gr -= 11) {
            g.setColor(new Color(255, 255, 255, gr > r - 40 ? 22 : 14));
            g.draw(new Ellipse2D.Double(cx - gr, cy - gr, 2 * gr, 2 * gr));
        }
        // Light sheen across the grooves
        g.setStroke(new BasicStroke(10f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(255, 255, 255, 28));
        g.draw(new java.awt.geom.Arc2D.Double(cx - r + 26, cy - r + 26, 2 * (r - 26), 2 * (r - 26), 110, 50,
                java.awt.geom.Arc2D.OPEN));

        // Label
        double lr = 64;
        g.setPaint(new RadialGradientPaint((float) cx, (float) cy, (float) lr, new float[] {0f, 1f},
                new Color[] {new Color(0xFFB347), new Color(0xFF5E7E)}));
        g.fill(new Ellipse2D.Double(cx - lr, cy - lr, 2 * lr, 2 * lr));
        g.setColor(new Color(0x141418));
        g.fill(new Ellipse2D.Double(cx - 9, cy - 9, 18, 18));

        // Sparkle (four-point star), top right
        drawSparkle(g, 400, 112, 78, new Color(0xFFE08A));
        drawSparkle(g, 452, 196, 30, new Color(0xFFF3C9));

        g.dispose();
        ImageIO.write(image, "png", new File(args[0]));
    }

    private static void drawSparkle(Graphics2D g, double x, double y, double r, Color color) {
        double w = r * 0.22;
        Path2D star = new Path2D.Double();
        star.moveTo(x, y - r);
        star.quadTo(x + w * 0.35, y - w * 0.35, x + r, y);
        star.quadTo(x + w * 0.35, y + w * 0.35, x, y + r);
        star.quadTo(x - w * 0.35, y + w * 0.35, x - r, y);
        star.quadTo(x - w * 0.35, y - w * 0.35, x, y - r);
        star.closePath();
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 60));
        g.setStroke(new BasicStroke((float) (r * 0.18), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(star);
        g.setColor(color);
        g.fill(star);
    }
}
