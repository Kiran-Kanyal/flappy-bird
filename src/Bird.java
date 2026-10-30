package src;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The player's bird. Movement is velocity based: gravity accelerates the bird
 * every tick and a flap replaces the vertical velocity with a fixed upward
 * impulse, which is what makes the controls feel smooth and predictable instead
 * of teleporting the bird a fixed distance per press.
 */
public class Bird {
    public static final int DRAW_WIDTH = 46, DRAW_HEIGHT = 32;

    //the hitbox is deliberately smaller than the sprite so near-misses are forgiving
    private static final int HIT_INSET_X = 9, HIT_INSET_Y = 8;

    private static final double GRAVITY = 0.40;
    private static final double FLAP_VELOCITY = -6.9;
    private static final double MAX_FALL_SPEED = 9.6;
    private static final double TUMBLE_GRAVITY = 0.55;

    private double x, y;
    private double velocity = 0;
    private double angle = 0;
    private double flapAnim = 0;
    private boolean tumbling = false;
    private final Deque<double[]> trail = new ArrayDeque<>();

    /**
     * Creates a bird at the given position.
     * @param x left edge of the sprite in logical pixels
     * @param y top edge of the sprite in logical pixels
     */
    public Bird(double x, double y) {
        this.x = x;
        this.y = y;
    }

    /**
     * Returns the bird to its pre-round state.
     * @param y top edge of the sprite in logical pixels
     */
    public void reset(double y) {
        this.y = y;
        velocity = 0;
        angle = 0;
        flapAnim = 0;
        tumbling = false;
        trail.clear();
    }

    /**
     * Applies the upward impulse.
     */
    public void flap() {
        velocity = FLAP_VELOCITY;
        flapAnim = 1.0;
    }

    /**
     * Switches the bird into its post-collision tumble, where it falls faster
     * and spins instead of responding to input.
     */
    public void tumble() {
        tumbling = true;
        velocity = Math.min(velocity, -2.0); //a small hop sells the impact
    }

    /**
     * Reports whether the bird is in its post-collision tumble.
     * @return true while tumbling
     */
    public boolean isTumbling() {
        return tumbling;
    }

    /**
     * Advances one fixed timestep of physics.
     * @param ceiling topmost y the bird may occupy, or -1 to allow any height
     */
    public void update(double ceiling) {
        velocity = Math.min(velocity + (tumbling ? TUMBLE_GRAVITY : GRAVITY), MAX_FALL_SPEED);
        y += velocity;

        if(ceiling >= 0 && y < ceiling) {
            //bumping the sky slows the bird rather than ending the round
            y = ceiling;
            velocity = Math.max(velocity, 0.6);
        }

        if(tumbling) {
            angle = Math.min(angle + 9, 180);
        }
        else {
            //tilt follows velocity, so the bird visibly commits to rises and dives
            double target = Math.max(-26, Math.min(78, velocity * 7.5));
            angle += (target - angle) * 0.22;
        }

        flapAnim = Math.max(0, flapAnim - 0.09);

        trail.addFirst(new double[] {x + DRAW_WIDTH * 0.25, y + DRAW_HEIGHT * 0.5});
        while(trail.size() > 9) {
            trail.removeLast();
        }
    }

    /**
     * Makes the bird hover in place with a gentle bob, used on the menu and
     * before the first flap of a round.
     * @param tick global frame counter
     * @param baseY the y value to bob around
     */
    public void hover(int tick, double baseY) {
        y = baseY + Math.sin(tick * 0.09) * 7.0;
        velocity = 0;
        angle = Math.sin(tick * 0.09) * 7.0;
        trail.clear();
    }

    /**
     * The forgiving collision box.
     * @return the bird's hitbox in logical pixels
     */
    public Rectangle hitbox() {
        return new Rectangle((int) Math.round(x) + HIT_INSET_X,
                             (int) Math.round(y) + HIT_INSET_Y,
                             DRAW_WIDTH - HIT_INSET_X * 2,
                             DRAW_HEIGHT - HIT_INSET_Y * 2);
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getCenterX() {
        return x + DRAW_WIDTH / 2.0;
    }

    public double getCenterY() {
        return y + DRAW_HEIGHT / 2.0;
    }

    public double getVelocity() {
        return velocity;
    }

    /**
     * Draws the motion trail, then the rotated sprite.
     * @param g the target surface
     */
    public void draw(Graphics2D g) {
        int i = 0;
        for(double[] p : trail) {
            int alpha = (int) (52 * (1.0 - i / (double) trail.size()));
            if(alpha > 0) {
                g.setColor(new java.awt.Color(255, 255, 255, alpha));
                double size = 13 - i;
                g.fillOval((int) (p[0] - size / 2), (int) (p[1] - size / 2), (int) size, (int) size);
            }
            i++;
        }

        BufferedImage sprite = Sprites.bird(DRAW_WIDTH, DRAW_HEIGHT);
        //a brief vertical stretch on each flap reads as a wingbeat from one sprite
        double squash = 1.0 + flapAnim * 0.16;
        AffineTransform saved = g.getTransform();
        g.translate(x + DRAW_WIDTH / 2.0, y + DRAW_HEIGHT / 2.0);
        g.rotate(Math.toRadians(angle));
        g.scale(1.0 / squash, squash);
        g.drawImage(sprite, -DRAW_WIDTH / 2, -DRAW_HEIGHT / 2, null);
        g.setTransform(saved);
    }
}
