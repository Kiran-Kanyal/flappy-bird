package src;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.net.URL;
import javax.imageio.ImageIO;

/**
 * Loads and caches the game's artwork. Images are decoded once and pre-scaled to
 * the exact size they are drawn at, so the render loop never pays for scaling.
 * If a resource is missing the sprite is generated procedurally instead, which
 * keeps the game playable from any working directory.
 */
public final class Sprites {
    private static BufferedImage bird;

    private Sprites() {
    }

    /**
     * Returns the bird sprite scaled to the requested size.
     * @param width desired width in logical pixels
     * @param height desired height in logical pixels
     * @return the cached, pre-scaled bird image
     */
    public static BufferedImage bird(int width, int height) {
        if(bird == null || bird.getWidth() != width || bird.getHeight() != height) {
            BufferedImage source = load("resources/images/blue_bird.png");
            bird = source == null ? fallbackBird(width, height) : scale(source, width, height);
        }
        return bird;
    }

    /**
     * Decodes an image from the classpath.
     * @param path classpath-relative location of the image
     * @return the decoded image, or null when it cannot be read
     */
    private static BufferedImage load(String path) {
        try {
            URL url = Sprites.class.getResource(path);
            return url == null ? null : ImageIO.read(url);
        }
        catch(Exception e) {
            return null;
        }
    }

    /**
     * High-quality rescale into a fresh ARGB image.
     * @param source image to rescale
     * @param width target width
     * @param height target height
     * @return the rescaled image
     */
    private static BufferedImage scale(BufferedImage source, int width, int height) {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(source, 0, 0, width, height, null);
        g.dispose();
        return out;
    }

    /**
     * Draws a simple bird so the game still runs without its resources folder.
     * @param width target width
     * @param height target height
     * @return a generated bird image
     */
    private static BufferedImage fallbackBird(int width, int height) {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(252, 206, 73));
        g.fill(new Ellipse2D.Double(0, 0, width, height));
        g.setColor(new Color(240, 160, 50));
        g.fill(new Ellipse2D.Double(width * 0.08, height * 0.42, width * 0.5, height * 0.4));
        g.setColor(Color.WHITE);
        g.fill(new Ellipse2D.Double(width * 0.58, height * 0.18, width * 0.26, height * 0.32));
        g.setColor(new Color(40, 40, 50));
        g.fill(new Ellipse2D.Double(width * 0.68, height * 0.26, width * 0.12, height * 0.17));
        g.setColor(new Color(240, 120, 40));
        g.fillPolygon(new int[] {(int) (width * 0.86), width, (int) (width * 0.86)},
                      new int[] {(int) (height * 0.44), (int) (height * 0.54), (int) (height * 0.66)}, 3);
        g.dispose();
        return out;
    }
}
