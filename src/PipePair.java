package src;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Rectangle;

/**
 * One top-and-bottom pipe obstacle with the gap between them. The pipes are
 * drawn procedurally so they stay crisp at any gap height, which the original
 * fixed-size sprites could not do without stretching their end caps.
 */
public class PipePair {
    public static final int WIDTH = 78;
    private static final int CAP_HEIGHT = 30, CAP_OVERHANG = 7;

    /** How far past the left edge a pipe must travel before it can be recycled. */
    public static final int CAP_OVERHANG_LIMIT = CAP_OVERHANG + 4;

    private double x;
    private final int gapCenterY;
    private final int gapHeight;
    private boolean scored = false;

    /**
     * Creates a pipe pair.
     * @param x left edge in logical pixels
     * @param gapCenterY vertical midpoint of the gap
     * @param gapHeight height of the passable gap
     */
    public PipePair(double x, int gapCenterY, int gapHeight) {
        this.x = x;
        this.gapCenterY = gapCenterY;
        this.gapHeight = gapHeight;
    }

    /**
     * Scrolls the pipe left.
     * @param dx distance to move this tick
     */
    public void advance(double dx) {
        x -= dx;
    }

    public double getX() {
        return x;
    }

    public double getRightEdge() {
        return x + WIDTH;
    }

    public int getGapTop() {
        return gapCenterY - gapHeight / 2;
    }

    public int getGapBottom() {
        return gapCenterY + gapHeight / 2;
    }

    public boolean isScored() {
        return scored;
    }

    public void markScored() {
        scored = true;
    }

    /**
     * Collision box of the upper pipe.
     * @return the upper pipe's rectangle
     */
    public Rectangle topRect() {
        return new Rectangle((int) Math.round(x), -200, WIDTH, getGapTop() + 200);
    }

    /**
     * Collision box of the lower pipe.
     * @param groundY y coordinate of the ground line
     * @return the lower pipe's rectangle
     */
    public Rectangle bottomRect(int groundY) {
        return new Rectangle((int) Math.round(x), getGapBottom(), WIDTH, groundY - getGapBottom() + 1);
    }

    /**
     * Draws both pipes.
     * @param g the target surface
     * @param body main pipe colour
     * @param groundY y coordinate of the ground line
     */
    public void draw(Graphics2D g, Color body, int groundY) {
        int left = (int) Math.round(x);
        drawColumn(g, left, 0, getGapTop(), body);
        drawColumn(g, left, getGapBottom(), groundY - getGapBottom(), body);
        drawCap(g, left, getGapTop() - CAP_HEIGHT, body);
        drawCap(g, left, getGapBottom(), body);
    }

    /**
     * Draws a pipe shaft with a lit left edge and shaded right edge.
     * @param g the target surface
     * @param left left edge in logical pixels
     * @param top top edge in logical pixels
     * @param height shaft height; nothing is drawn when not positive
     * @param body main pipe colour
     */
    private void drawColumn(Graphics2D g, int left, int top, int height, Color body) {
        if(height <= 0) {
            return;
        }
        g.setPaint(new GradientPaint(left, 0, lighten(body, 0.35f), left + WIDTH, 0, darken(body, 0.32f)));
        g.fillRect(left, top, WIDTH, height);
        g.setColor(new Color(255, 255, 255, 60));
        g.fillRect(left + 10, top, 7, height);
        g.setColor(darken(body, 0.55f));
        g.fillRect(left, top, 3, height);
        g.fillRect(left + WIDTH - 3, top, 3, height);
    }

    /**
     * Draws the wider lip at the mouth of a pipe.
     * @param g the target surface
     * @param left left edge of the shaft
     * @param top top edge of the cap
     * @param body main pipe colour
     */
    private void drawCap(Graphics2D g, int left, int top, Color body) {
        int capX = left - CAP_OVERHANG, capW = WIDTH + CAP_OVERHANG * 2;
        g.setPaint(new GradientPaint(capX, 0, lighten(body, 0.45f), capX + capW, 0, darken(body, 0.28f)));
        g.fillRoundRect(capX, top, capW, CAP_HEIGHT, 8, 8);
        g.setColor(new Color(255, 255, 255, 70));
        g.fillRoundRect(capX + 9, top + 4, 8, CAP_HEIGHT - 8, 6, 6);
        g.setColor(darken(body, 0.6f));
        g.drawRoundRect(capX, top, capW, CAP_HEIGHT, 8, 8);
    }

    /**
     * Blends a colour toward white.
     * @param c base colour
     * @param amount blend factor from 0 to 1
     * @return the lightened colour
     */
    private static Color lighten(Color c, float amount) {
        return new Color((int) (c.getRed() + (255 - c.getRed()) * amount),
                         (int) (c.getGreen() + (255 - c.getGreen()) * amount),
                         (int) (c.getBlue() + (255 - c.getBlue()) * amount));
    }

    /**
     * Blends a colour toward black.
     * @param c base colour
     * @param amount blend factor from 0 to 1
     * @return the darkened colour
     */
    private static Color darken(Color c, float amount) {
        return new Color((int) (c.getRed() * (1 - amount)),
                         (int) (c.getGreen() * (1 - amount)),
                         (int) (c.getBlue() * (1 - amount)));
    }
}
