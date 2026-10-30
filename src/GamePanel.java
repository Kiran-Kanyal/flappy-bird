package src;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import javax.swing.JPanel;
import javax.swing.Timer;

/**
 * The whole game: simulation, rendering and input.
 *
 * Two design choices drive how this plays. First, the world is a fixed
 * 480x720 logical space that is scaled to fit whatever window size the player
 * uses, so the bird, the pipes and the flap distance are always in the same
 * proportion to the playfield no matter how large the display is. Second, the
 * simulation runs on a fixed 60Hz timestep driven by a Swing timer, so motion
 * is smooth and identical on fast and slow machines, and the game never busy
 * waits on a thread.
 */
public class GamePanel extends JPanel implements ActionListener, KeyListener {
    private static final long serialVersionUID = 2L;

    //logical world dimensions; everything below is expressed in these units
    public static final int W = 480, H = 720;
    public static final int GROUND_Y = 620;

    private static final double TICK_SECONDS = 1.0 / 60.0;
    private static final int MAX_CATCHUP_TICKS = 5;
    private static final double BIRD_X = 132;
    private static final int CEILING = 4;
    private static final int RESTART_LOCKOUT_TICKS = 24;
    private static final int GAP_EDGE_MARGIN = 86;
    private static final int MAX_GAP_SHIFT = 170;

    /** The screens the game can be on. */
    public enum State { MENU, READY, PLAYING, DYING, OVER, PAUSED }

    /**
     * Difficulty presets. The default is tuned to be comfortable: the gap is
     * roughly six times the bird's height and the scroll speed gives well over
     * a second of reaction time between pipes.
     */
    public enum Difficulty {
        CHILL("Chill", 240, 214, 2.05, 2.9, 310),
        NORMAL("Normal", 206, 170, 2.55, 3.75, 272),
        INSANE("Insane", 170, 130, 3.25, 4.80, 234);

        private final String label;
        private final int gapStart, gapMin;
        private final double speedStart, speedMax;
        private final int spacing;

        Difficulty(String label, int gapStart, int gapMin, double speedStart, double speedMax, int spacing) {
            this.label = label;
            this.gapStart = gapStart;
            this.gapMin = gapMin;
            this.speedStart = speedStart;
            this.speedMax = speedMax;
            this.spacing = spacing;
        }

        public String label() {
            return label;
        }
    }

    //fonts, created once because FontMetrics lookups in a render loop are not free
    private static final Font F_TITLE = new Font("SansSerif", Font.BOLD, 56);
    private static final Font F_BIG = new Font("SansSerif", Font.BOLD, 44);
    private static final Font F_SCORE = new Font("SansSerif", Font.BOLD, 62);
    private static final Font F_MED = new Font("SansSerif", Font.BOLD, 24);
    private static final Font F_SMALL = new Font("SansSerif", Font.BOLD, 17);
    private static final Font F_TINY = new Font("SansSerif", Font.PLAIN, 14);

    //game state
    private State state = State.MENU;
    private Difficulty difficulty = Difficulty.NORMAL;
    private final Bird bird = new Bird(BIRD_X, H / 2.0 - Bird.DRAW_HEIGHT);
    private final List<PipePair> pipes = new ArrayList<>();
    private final List<Particle> particles = new ArrayList<>();
    private final List<ScorePop> pops = new ArrayList<>();
    private final List<Cloud> clouds = new ArrayList<>();
    private final double[][] stars = new double[70][3];
    private final Random rng = new Random();

    private int score = 0;
    private int best = 0;
    private boolean newBest = false;
    private int lastGapCenter = (GROUND_Y - 80) / 2;
    private double scroll = 0;
    private double speed = Difficulty.NORMAL.speedStart;
    private int gap = Difficulty.NORMAL.gapStart;
    private int tick = 0;
    private int overTicks = 0;
    private double shake = 0;
    private double flash = 0;
    private String toast = "";
    private int toastTicks = 0;

    //frame pacing
    private long lastNanos = System.nanoTime();
    private double accumulator = 0;

    /**
     * Builds the panel, wires input and starts the clock.
     */
    public GamePanel() {
        this(true);
    }

    /**
     * Builds the panel.
     * @param startClock false to leave the simulation un-driven so a test can
     *                   call step() itself; the real game always passes true
     */
    public GamePanel(boolean startClock) {
        setPreferredSize(new Dimension(W, H));
        setBackground(new Color(16, 18, 28));
        setFocusable(true);
        setDoubleBuffered(true);
        addKeyListener(this);
        addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                primaryAction();
            }
        });

        for(int i = 0; i < 7; i++) {
            clouds.add(new Cloud(rng.nextDouble() * W * 1.6, 60 + rng.nextDouble() * 260,
                    0.55 + rng.nextDouble() * 0.9, 0.18 + rng.nextDouble() * 0.22));
        }
        for(int i = 0; i < stars.length; i++) {
            stars[i] = new double[] {rng.nextDouble() * W, rng.nextDouble() * (GROUND_Y - 160), 1 + rng.nextDouble() * 1.8};
        }

        Sfx.init();
        best = loadBest(difficulty);
        bird.reset(H / 2.0 - Bird.DRAW_HEIGHT);

        if(startClock) {
            //music only runs in the real game; the headless smoke test stays silent
            Music.init();
            Music.play();
            //a short timer plus an accumulator decouples render cadence from simulation rate
            new Timer(8, this).start();
        }
    }

    /**
     * Grabs keyboard focus as soon as the panel joins a window, so the player
     * never has to click the game before the keys respond.
     */
    public void addNotify() {
        super.addNotify();
        requestFocusInWindow();
    }

    /**
     * Timer callback: runs as many fixed simulation steps as real time has
     * earned, then repaints once.
     * @param e the timer event
     */
    public void actionPerformed(ActionEvent e) {
        long now = System.nanoTime();
        accumulator += (now - lastNanos) / 1_000_000_000.0;
        lastNanos = now;

        //drop surplus time after a stall instead of fast-forwarding the game
        if(accumulator > TICK_SECONDS * MAX_CATCHUP_TICKS) {
            accumulator = TICK_SECONDS * MAX_CATCHUP_TICKS;
        }

        boolean stepped = false;
        while(accumulator >= TICK_SECONDS) {
            accumulator -= TICK_SECONDS;
            step();
            stepped = true;
        }
        if(stepped) {
            repaint();
        }
    }

    /**
     * Advances the simulation by exactly one tick.
     */
    public void step() {
        tick++;
        shake *= 0.88;
        flash *= 0.86;
        if(toastTicks > 0) {
            toastTicks--;
        }

        //the scenery keeps drifting on every screen so menus never look frozen
        double sceneSpeed = (state == State.PLAYING || state == State.DYING) ? speed : 1.2;
        scroll += sceneSpeed;
        for(Cloud c : clouds) {
            c.x -= sceneSpeed * c.speedMul;
            if(c.x < -160) {
                c.x = W + rng.nextDouble() * 220;
                c.y = 60 + rng.nextDouble() * 260;
                c.scale = 0.55 + rng.nextDouble() * 0.9;
            }
        }

        updateParticles();

        switch(state) {
            case MENU:
                bird.hover(tick, 330); //sits in the gap between the title and the menu panel
                break;
            case READY:
                bird.hover(tick, H / 2.0 - Bird.DRAW_HEIGHT);
                break;
            case PLAYING:
                stepPlaying();
                break;
            case DYING:
                stepDying();
                break;
            case OVER:
                overTicks++;
                break;
            case PAUSED:
            default:
                break;
        }
    }

    /**
     * One tick of live gameplay: difficulty curve, pipes, physics, collisions.
     */
    private void stepPlaying() {
        //difficulty ramps with score but is clamped, so the game gets tense without becoming unfair
        gap = Math.max(difficulty.gapMin, difficulty.gapStart - (score / 4) * 3);
        speed = Math.min(difficulty.speedMax, difficulty.speedStart + score * 0.035);

        bird.update(CEILING);
        spawnPipes();

        for(Iterator<PipePair> it = pipes.iterator(); it.hasNext();) {
            PipePair p = it.next();
            p.advance(speed);
            if(p.getRightEdge() < -PipePair.CAP_OVERHANG_LIMIT) {
                it.remove();
            }
        }

        if(tick % 3 == 0 && bird.getVelocity() < -1.5) {
            addParticles(bird.getX() + 6, bird.getCenterY() + 6, 1, new Color(255, 255, 255, 190), 1.4);
        }

        scoreCheck();
        collisionCheck();
    }

    /**
     * One tick of the post-collision fall, which ends when the bird lands.
     */
    private void stepDying() {
        bird.update(-1);
        if(bird.getY() + Bird.DRAW_HEIGHT >= GROUND_Y) {
            shake = Math.max(shake, 7);
            addParticles(bird.getCenterX(), GROUND_Y - 6, 10, new Color(224, 200, 130), 3.0);
            finishRound();
        }
    }

    /**
     * Adds a pipe when the last one has travelled far enough, keeping the
     * spacing between obstacles constant.
     */
    private void spawnPipes() {
        double spawnAt = W + 40;
        if(pipes.isEmpty()) {
            if(scroll > 40) { //a short runway before the first pipe arrives
                pipes.add(new PipePair(spawnAt, nextGapCenter(), gap));
            }
            return;
        }

        PipePair last = pipes.get(pipes.size() - 1);
        if(last.getX() <= W - difficulty.spacing) {
            pipes.add(new PipePair(last.getX() + difficulty.spacing, nextGapCenter(), gap));
        }
    }

    /**
     * Picks the next gap's midpoint. The result is kept inside the playfield
     * and within a bounded distance of the previous gap, so consecutive pipes
     * can never demand an impossible climb.
     * @return the gap midpoint in logical pixels
     */
    private int nextGapCenter() {
        int min = GAP_EDGE_MARGIN + gap / 2;
        int max = GROUND_Y - GAP_EDGE_MARGIN + 20 - gap / 2;
        if(max < min) {
            max = min;
        }
        int lo = Math.max(min, lastGapCenter - MAX_GAP_SHIFT);
        int hi = Math.min(max, lastGapCenter + MAX_GAP_SHIFT);
        if(hi < lo) {
            hi = lo;
        }
        lastGapCenter = lo + rng.nextInt(hi - lo + 1);
        return lastGapCenter;
    }

    /**
     * Awards a point for each pipe the bird has fully passed.
     */
    private void scoreCheck() {
        for(PipePair p : pipes) {
            if(!p.isScored() && p.getRightEdge() < bird.getX()) {
                p.markScored();
                score++;
                Sfx.score();
                pops.add(new ScorePop("+1", bird.getCenterX() + 26, bird.getCenterY() - 14));
                addParticles(p.getRightEdge(), (p.getGapTop() + p.getGapBottom()) / 2.0, 7,
                        new Color(255, 236, 140), 2.2);
                if(score > 0 && score % 10 == 0) {
                    flash = Math.max(flash, 0.18);
                    showToast(score + " in a row!");
                    Sfx.medal();
                }
            }
        }
    }

    /**
     * Ends the round if the bird has struck a pipe or the ground.
     */
    private void collisionCheck() {
        Rectangle box = bird.hitbox();

        if(box.y + box.height >= GROUND_Y) {
            Sfx.hit();
            Sfx.fall();
            Music.duck();
            shake = 9;
            flash = 0.5;
            addParticles(bird.getCenterX(), GROUND_Y - 8, 12, new Color(224, 200, 130), 3.0);
            finishRound();
            return;
        }

        for(PipePair p : pipes) {
            if(box.intersects(p.topRect()) || box.intersects(p.bottomRect(GROUND_Y))) {
                Sfx.hit();
                shake = 11;
                flash = 0.55;
                addParticles(bird.getCenterX(), bird.getCenterY(), 14, new Color(255, 255, 255), 3.4);
                bird.tumble();
                state = State.DYING;
                Sfx.fall();
                Music.duck();
                return;
            }
        }
    }

    /**
     * Records the result and moves to the game over screen.
     */
    private void finishRound() {
        newBest = score > best;
        if(newBest) {
            best = score;
            saveBest(difficulty, best);
        }
        overTicks = 0;
        state = State.OVER;
    }

    /**
     * Clears the field and puts a fresh round in its pre-flap state.
     */
    private void startRound() {
        pipes.clear();
        particles.clear();
        pops.clear();
        score = 0;
        newBest = false;
        scroll = 0;
        gap = difficulty.gapStart;
        speed = difficulty.speedStart;
        lastGapCenter = (GAP_EDGE_MARGIN + GROUND_Y - GAP_EDGE_MARGIN) / 2;
        bird.reset(H / 2.0 - Bird.DRAW_HEIGHT);
        state = State.READY;
        Sfx.swoosh();
        Music.restore();
    }

    /**
     * Handles the single "do the thing" input, bound to space, the arrow and W
     * keys, enter and any mouse click. One button runs the entire game: it
     * starts, flaps and replays, which is why getting back into a round takes a
     * single press from anywhere.
     */
    private void primaryAction() {
        switch(state) {
            case MENU:
                startRound();
                break;
            case READY:
                state = State.PLAYING;
                bird.flap();
                Sfx.flap();
                addParticles(bird.getX() + 6, bird.getCenterY() + 8, 4, new Color(255, 255, 255, 200), 1.8);
                break;
            case PLAYING:
                bird.flap();
                Sfx.flap();
                addParticles(bird.getX() + 6, bird.getCenterY() + 8, 4, new Color(255, 255, 255, 200), 1.8);
                break;
            case OVER:
                //a short lockout stops the keypress that killed you from skipping the score card
                if(overTicks >= RESTART_LOCKOUT_TICKS) {
                    startRound();
                }
                break;
            case DYING:
            case PAUSED:
            default:
                break;
        }
    }

    /**
     * Key handling.
     * @param e the key event
     */
    public void keyPressed(KeyEvent e) {
        int k = e.getKeyCode();

        if(k == KeyEvent.VK_SPACE || k == KeyEvent.VK_UP || k == KeyEvent.VK_W || k == KeyEvent.VK_ENTER) {
            primaryAction();
            return;
        }

        if(k == KeyEvent.VK_P || k == KeyEvent.VK_ESCAPE) {
            if(state == State.PLAYING) {
                state = State.PAUSED;
                Music.pause();
            }
            else if(state == State.PAUSED) {
                state = State.PLAYING;
                Music.resume();
                lastNanos = System.nanoTime();
                accumulator = 0;
            }
            else if(state == State.OVER || state == State.READY) {
                state = State.MENU;
                pipes.clear();
                Sfx.swoosh();
                Music.restore();
            }
            return;
        }

        if(k == KeyEvent.VK_R && state != State.MENU) {
            startRound();
            return;
        }

        if(k == KeyEvent.VK_M) {
            //M is the master mute, so silencing the game also silences the music
            boolean off = Sfx.toggleMute();
            Music.setMuted(off);
            showToast(off ? "Sound off" : "Sound on");
            return;
        }

        if(k == KeyEvent.VK_N) {
            showToast(Music.toggleMute() ? "Music off" : "Music on");
            return;
        }

        if(k == KeyEvent.VK_LEFT || k == KeyEvent.VK_RIGHT) {
            if(state == State.MENU || state == State.OVER) {
                Difficulty[] all = Difficulty.values();
                int i = difficulty.ordinal() + (k == KeyEvent.VK_RIGHT ? 1 : all.length - 1);
                difficulty = all[i % all.length];
                best = loadBest(difficulty);
                gap = difficulty.gapStart;
                speed = difficulty.speedStart;
                showToast(difficulty.label());
                Sfx.swoosh();
            }
            return;
        }

        if(k == KeyEvent.VK_Q) {
            Music.stop();
            System.exit(0);
        }
    }

    public void keyReleased(KeyEvent e) {
    }

    public void keyTyped(KeyEvent e) {
    }

    /**
     * Shows a brief banner message.
     * @param message the text to display
     */
    private void showToast(String message) {
        toast = message;
        toastTicks = 90;
    }

    /**
     * Spawns a burst of debris.
     * @param x origin x
     * @param y origin y
     * @param count number of particles
     * @param color particle colour
     * @param spread initial speed scale
     */
    private void addParticles(double x, double y, int count, Color color, double spread) {
        for(int i = 0; i < count; i++) {
            particles.add(new Particle(x, y,
                    (rng.nextDouble() - 0.5) * spread * 2 - 0.8,
                    (rng.nextDouble() - 0.5) * spread * 2,
                    18 + rng.nextInt(22),
                    2 + rng.nextDouble() * 3.5,
                    color));
        }
    }

    /**
     * Ages particles and score popups, discarding the expired ones.
     */
    private void updateParticles() {
        for(Iterator<Particle> it = particles.iterator(); it.hasNext();) {
            Particle p = it.next();
            p.x += p.vx;
            p.y += p.vy;
            p.vy += 0.12;
            p.vx *= 0.98;
            if(--p.life <= 0) {
                it.remove();
            }
        }
        for(Iterator<ScorePop> it = pops.iterator(); it.hasNext();) {
            ScorePop s = it.next();
            s.y -= 1.1;
            if(--s.life <= 0) {
                it.remove();
            }
        }
    }

    /**
     * Scales the fixed logical world to fit the window, keeping the aspect
     * ratio, and letterboxes whatever is left over.
     * @param g the surface supplied by Swing
     */
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        double scale = Math.min(getWidth() / (double) W, getHeight() / (double) H);
        double offX = (getWidth() - W * scale) / 2.0;
        double offY = (getHeight() - H * scale) / 2.0;

        AffineTransform saved = g2.getTransform();
        g2.translate(offX, offY);
        g2.scale(scale, scale);
        g2.clipRect(0, 0, W, H);
        render(g2);
        g2.setTransform(saved);
        g2.dispose();
    }

    /**
     * Draws one frame of the logical world.
     * @param g the target surface, already scaled to logical units
     */
    public void render(Graphics2D g) {
        Palette p = palette();

        //screen shake is applied to the world only, so the HUD stays readable
        AffineTransform worldTransform = g.getTransform();
        if(shake > 0.4) {
            g.translate((rng.nextDouble() - 0.5) * shake, (rng.nextDouble() - 0.5) * shake);
        }

        drawSky(g, p);
        drawHills(g, p);
        drawClouds(g, p);

        for(PipePair pipe : pipes) {
            pipe.draw(g, p.pipe, GROUND_Y);
        }

        drawBirdShadow(g);
        bird.draw(g);
        drawGround(g, p);
        drawParticles(g);

        g.setTransform(worldTransform);

        drawHud(g, p);
        drawOverlays(g, p);

        if(flash > 0.01) {
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.min(0.8, flash)));
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, W, H);
            g.setComposite(AlphaComposite.SrcOver);
        }
    }

    /**
     * Sky gradient, stars and the sun or moon.
     * @param g the target surface
     * @param p the active palette
     */
    private void drawSky(Graphics2D g, Palette p) {
        g.setPaint(new GradientPaint(0, 0, p.skyTop, 0, GROUND_Y, p.skyBottom));
        g.fillRect(0, 0, W, GROUND_Y);

        if(p.night > 0.02) {
            for(double[] s : stars) {
                //twinkle is a cheap sine on a per-star phase
                double twinkle = 0.55 + 0.45 * Math.sin(tick * 0.05 + s[0]);
                int alpha = (int) (235 * p.night * twinkle);
                g.setColor(new Color(255, 255, 255, Math.max(0, Math.min(255, alpha))));
                g.fillOval((int) s[0], (int) s[1], (int) s[2], (int) s[2]);
            }
        }

        int cx = W - 92, cy = 96;
        g.setColor(new Color(255, 255, 255, 36));
        g.fillOval(cx - 16, cy - 16, 92, 92);
        g.setColor(p.night > 0.5 ? new Color(238, 240, 220) : new Color(255, 236, 150));
        g.fillOval(cx, cy, 60, 60);
        if(p.night > 0.5) {
            g.setColor(p.skyTop);
            g.fillOval(cx + 16, cy - 6, 56, 56); //crescent bite
        }
    }

    /**
     * Two parallax hill layers.
     * @param g the target surface
     * @param p the active palette
     */
    private void drawHills(Graphics2D g, Palette p) {
        drawHillLayer(g, p.hillFar, 0.22, 150, GROUND_Y - 110, 300);
        drawHillLayer(g, p.hillNear, 0.42, 190, GROUND_Y - 58, 230);
    }

    /**
     * Draws a single scrolling hill silhouette built from a sine wave.
     * @param g the target surface
     * @param color fill colour
     * @param parallax fraction of the world speed this layer moves at
     * @param wavelength horizontal period of the hills
     * @param baseY y coordinate of the hill tops
     * @param amplitude hill height
     */
    private void drawHillLayer(Graphics2D g, Color color, double parallax, int wavelength, int baseY, int amplitude) {
        int steps = 24;
        int[] xs = new int[steps + 3];
        int[] ys = new int[steps + 3];
        double offset = scroll * parallax;

        for(int i = 0; i <= steps; i++) {
            double x = i * (W / (double) steps);
            double phase = (x + offset) / wavelength;
            xs[i] = (int) x;
            ys[i] = (int) (baseY - Math.abs(Math.sin(phase)) * amplitude * 0.42);
        }
        xs[steps + 1] = W;
        ys[steps + 1] = GROUND_Y;
        xs[steps + 2] = 0;
        ys[steps + 2] = GROUND_Y;

        g.setColor(color);
        g.fillPolygon(xs, ys, steps + 3);
    }

    /**
     * Soft layered clouds.
     * @param g the target surface
     * @param p the active palette
     */
    private void drawClouds(Graphics2D g, Palette p) {
        for(Cloud c : clouds) {
            int alpha = (int) (150 + 70 * c.speedMul);
            g.setColor(new Color(p.cloud.getRed(), p.cloud.getGreen(), p.cloud.getBlue(), Math.min(235, alpha)));
            double w = 110 * c.scale, h = 38 * c.scale;
            g.fillOval((int) c.x, (int) c.y, (int) w, (int) h);
            g.fillOval((int) (c.x + w * 0.22), (int) (c.y - h * 0.45), (int) (w * 0.5), (int) (h * 1.25));
            g.fillOval((int) (c.x + w * 0.52), (int) (c.y - h * 0.18), (int) (w * 0.45), (int) h);
        }
    }

    /**
     * Ground strip with a grass lip and scrolling texture bands.
     * @param g the target surface
     * @param p the active palette
     */
    private void drawGround(Graphics2D g, Palette p) {
        g.setColor(p.grass);
        g.fillRect(0, GROUND_Y, W, 16);
        g.setPaint(new GradientPaint(0, GROUND_Y + 16, p.ground, 0, H, p.groundDark));
        g.fillRect(0, GROUND_Y + 16, W, H - GROUND_Y - 16);

        g.setColor(new Color(0, 0, 0, 38));
        int band = 34;
        int shift = (int) (scroll % (band * 2));
        for(int x = -band * 2; x < W + band * 2; x += band * 2) {
            g.fillRect(x - shift, GROUND_Y + 16, band, H - GROUND_Y - 16);
        }

        g.setColor(new Color(0, 0, 0, 55));
        g.fillRect(0, GROUND_Y, W, 3);
    }

    /**
     * An ellipse under the bird that shrinks with altitude, which makes the
     * bird's height much easier to judge.
     * @param g the target surface
     */
    private void drawBirdShadow(Graphics2D g) {
        if(state == State.MENU) {
            return;
        }
        double height = Math.max(0, GROUND_Y - bird.getCenterY());
        double t = Math.min(1.0, height / (double) GROUND_Y);
        int alpha = (int) (70 * (1 - t * 0.8));
        double w = Bird.DRAW_WIDTH * (1.0 - t * 0.45);
        g.setColor(new Color(0, 0, 0, Math.max(0, alpha)));
        g.fillOval((int) (bird.getCenterX() - w / 2), GROUND_Y + 4, (int) w, (int) (w * 0.26));
    }

    /**
     * Debris and floating score popups.
     * @param g the target surface
     */
    private void drawParticles(Graphics2D g) {
        for(Particle p : particles) {
            int alpha = (int) (255 * (p.life / (double) p.maxLife));
            g.setColor(new Color(p.color.getRed(), p.color.getGreen(), p.color.getBlue(), Math.max(0, Math.min(255, alpha))));
            g.fillOval((int) p.x, (int) p.y, (int) p.size, (int) p.size);
        }
        for(ScorePop s : pops) {
            int alpha = (int) (255 * (s.life / (double) ScorePop.LIFE));
            g.setFont(F_MED);
            g.setColor(new Color(255, 255, 255, Math.max(0, alpha)));
            g.drawString(s.text, (int) s.x, (int) s.y);
        }
    }

    /**
     * Score, best score, difficulty and status indicators.
     * @param g the target surface
     * @param p the active palette
     */
    private void drawHud(Graphics2D g, Palette p) {
        if(state == State.PLAYING || state == State.DYING || state == State.PAUSED || state == State.READY) {
            outlinedCenter(g, String.valueOf(score), W / 2.0, 96, F_SCORE, Color.WHITE);
        }

        g.setFont(F_TINY);
        g.setColor(new Color(255, 255, 255, 185));
        g.drawString(difficulty.label(), 14, 24);
        String bestText = "Best " + best;
        FontMetrics fm = g.getFontMetrics(F_TINY);
        g.drawString(bestText, W - 14 - fm.stringWidth(bestText), 24);
        if(Sfx.isMuted()) {
            g.drawString("muted", W - 14 - fm.stringWidth("muted"), 42);
        }
        else if(Music.isMuted()) {
            g.drawString("music off", W - 14 - fm.stringWidth("music off"), 42);
        }

        if(toastTicks > 0 && state != State.OVER) {
            int alpha = (int) (255 * Math.min(1.0, toastTicks / 30.0));
            g.setFont(F_SMALL);
            String t = toast;
            int w = g.getFontMetrics(F_SMALL).stringWidth(t);
            g.setColor(new Color(0, 0, 0, (int) (alpha * 0.45)));
            g.fillRoundRect(W / 2 - w / 2 - 14, 128, w + 28, 32, 16, 16);
            g.setColor(new Color(255, 255, 255, alpha));
            g.drawString(t, W / 2 - w / 2, 150);
        }
    }

    /**
     * Full-screen screens: menu, get-ready prompt, pause and game over.
     * @param g the target surface
     * @param p the active palette
     */
    private void drawOverlays(Graphics2D g, Palette p) {
        switch(state) {
            case MENU:
                drawMenu(g);
                break;
            case READY:
                drawReady(g);
                break;
            case PAUSED:
                drawPaused(g);
                break;
            case OVER:
                drawGameOver(g);
                break;
            default:
                break;
        }
    }

    /**
     * Title screen.
     * @param g the target surface
     */
    private void drawMenu(Graphics2D g) {
        double bob = Math.sin(tick * 0.05) * 6;
        outlinedCenter(g, "FLAPPY", W / 2.0, 170 + bob, F_TITLE, new Color(255, 228, 96));
        outlinedCenter(g, "BIRD", W / 2.0, 226 + bob, F_TITLE, new Color(255, 228, 96));
        outlinedCenter(g, "Redux", W / 2.0, 262 + bob, F_MED, Color.WHITE);

        panel(g, 60, 404, W - 120, 176);
        outlinedCenter(g, "SPACE or CLICK to play", W / 2.0, 438, F_SMALL, Color.WHITE);
        outlinedCenter(g, "◀️  " + difficulty.label() + "  ▶️", W / 2.0, 474, F_MED, new Color(150, 240, 190));
        outlinedCenter(g, "Best on " + difficulty.label() + ": " + best, W / 2.0, 504, F_SMALL, new Color(255, 255, 255, 215));

        g.setFont(F_TINY);
        g.setColor(new Color(255, 255, 255, 190));
        centerTiny(g, "P pause   R restart   M sound   N music   Q quit", W / 2.0, 544);
        centerTiny(g, "← → change difficulty", W / 2.0, 564);
    }

    /**
     * Pre-flap prompt.
     * @param g the target surface
     */
    private void drawReady(Graphics2D g) {
        outlinedCenter(g, "GET READY", W / 2.0, 250, F_BIG, Color.WHITE);
        double pulse = 1.0 + Math.sin(tick * 0.14) * 0.08;
        g.setFont(F_SMALL);
        outlinedCenter(g, "press SPACE or click to flap", W / 2.0, 292, F_SMALL, new Color(255, 255, 255, 230));

        //an arrow that pulses upward next to the bird shows which way a tap moves you
        int ax = (int) (bird.getCenterX() + 54), ay = (int) (bird.getCenterY() - 10 * pulse);
        g.setColor(new Color(255, 255, 255, 200));
        g.setStroke(new BasicStroke(4, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(ax, ay + 22, ax, ay - 14);
        g.drawLine(ax, ay - 16, ax - 9, ay - 6);
        g.drawLine(ax, ay - 16, ax + 9, ay - 6);
        g.setStroke(new BasicStroke(1));
    }

    /**
     * Pause overlay.
     * @param g the target surface
     */
    private void drawPaused(Graphics2D g) {
        g.setColor(new Color(0, 0, 0, 130));
        g.fillRect(0, 0, W, H);
        outlinedCenter(g, "PAUSED", W / 2.0, H / 2.0 - 10, F_BIG, Color.WHITE);
        outlinedCenter(g, "P or ESC to resume     R to restart", W / 2.0, H / 2.0 + 28, F_SMALL, new Color(255, 255, 255, 225));
    }

    /**
     * Score card with medal and replay prompt.
     * @param g the target surface
     */
    private void drawGameOver(Graphics2D g) {
        g.setColor(new Color(0, 0, 0, 120));
        g.fillRect(0, 0, W, H);

        outlinedCenter(g, "GAME OVER", W / 2.0, 190, F_BIG, new Color(255, 120, 110));
        panel(g, 56, 222, W - 112, 240);

        //medal tiers give the score something to aim at beyond the number itself
        String medal = score >= 50 ? "PLATINUM" : score >= 35 ? "GOLD" : score >= 20 ? "SILVER" : score >= 10 ? "BRONZE" : "";
        Color medalColor = score >= 50 ? new Color(190, 235, 245)
                : score >= 35 ? new Color(255, 212, 90)
                : score >= 20 ? new Color(216, 220, 228)
                : new Color(206, 140, 86);

        if(!medal.isEmpty()) {
            int mx = 96, my = 262;
            g.setColor(new Color(0, 0, 0, 90));
            g.fillOval(mx - 3, my + 3, 74, 74);
            g.setPaint(new GradientPaint(mx, my, medalColor.brighter(), mx + 70, my + 70, medalColor.darker()));
            g.fillOval(mx, my, 70, 70);
            g.setColor(new Color(255, 255, 255, 150));
            g.setStroke(new BasicStroke(3));
            g.drawOval(mx + 7, my + 7, 56, 56);
            g.setStroke(new BasicStroke(1));
            g.setFont(F_TINY);
            g.setColor(new Color(40, 34, 26));
            int tw = g.getFontMetrics(F_TINY).stringWidth(medal);
            g.drawString(medal, mx + 35 - tw / 2, my + 40);
        }

        g.setFont(F_SMALL);
        g.setColor(new Color(255, 255, 255, 220));
        g.drawString("SCORE", 226, 262);
        outlinedLeft(g, String.valueOf(score), 226, 310, F_BIG, Color.WHITE);
        g.setFont(F_SMALL);
        g.setColor(new Color(255, 255, 255, 220));
        g.drawString("BEST", 226, 348);
        outlinedLeft(g, String.valueOf(best), 226, 390, F_BIG, new Color(255, 228, 96));

        if(newBest) {
            double pulse = Math.sin(tick * 0.18) * 0.5 + 0.5;
            g.setColor(new Color(255, 90, 120, (int) (170 + 85 * pulse)));
            g.fillRoundRect(96, 408, 150, 32, 16, 16);
            outlinedCenter(g, "NEW BEST!", 171, 430, F_SMALL, Color.WHITE);
        }

        boolean ready = overTicks >= RESTART_LOCKOUT_TICKS;
        int alpha = ready ? (int) (200 + 55 * Math.sin(tick * 0.12)) : 90;
        outlinedCenter(g, "SPACE or CLICK to play again", W / 2.0, 506,
                F_SMALL, new Color(255, 255, 255, Math.max(0, Math.min(255, alpha))));
        g.setFont(F_TINY);
        g.setColor(new Color(255, 255, 255, 190));
        centerTiny(g, "◀️ ▶️ difficulty: " + difficulty.label(), W / 2.0, 536);
        centerTiny(g, "R restart     ESC menu     Q quit", W / 2.0, 558);
    }

    /**
     * Draws the translucent rounded backdrop used by the overlay screens.
     * @param g the target surface
     * @param x left edge
     * @param y top edge
     * @param w width
     * @param h height
     */
    private void panel(Graphics2D g, int x, int y, int w, int h) {
        g.setColor(new Color(18, 22, 38, 222));
        g.fillRoundRect(x, y, w, h, 22, 22);
        g.setColor(new Color(255, 255, 255, 45));
        g.drawRoundRect(x, y, w, h, 22, 22);
    }

    /**
     * Draws horizontally centred text with a drop shadow for contrast against
     * any background.
     * @param g the target surface
     * @param s the text
     * @param cx centre x
     * @param y text baseline
     * @param f the font
     * @param c the fill colour
     */
    private void outlinedCenter(Graphics2D g, String s, double cx, double y, Font f, Color c) {
        g.setFont(f);
        int w = g.getFontMetrics(f).stringWidth(s);
        outlinedLeft(g, s, (int) (cx - w / 2.0), y, f, c);
    }

    /**
     * Draws left-aligned text with a drop shadow.
     * @param g the target surface
     * @param s the text
     * @param x left x
     * @param y text baseline
     * @param f the font
     * @param c the fill colour
     */
    private void outlinedLeft(Graphics2D g, String s, double x, double y, Font f, Color c) {
        g.setFont(f);
        g.setColor(new Color(0, 0, 0, Math.min(255, c.getAlpha())));
        g.drawString(s, (int) x + 3, (int) y + 3);
        g.setColor(c);
        g.drawString(s, (int) x, (int) y);
    }

    /**
     * Draws small centred hint text in the current colour.
     * @param g the target surface
     * @param s the text
     * @param cx centre x
     * @param y text baseline
     */
    private void centerTiny(Graphics2D g, String s, double cx, double y) {
        g.setFont(F_TINY);
        int w = g.getFontMetrics(F_TINY).stringWidth(s);
        g.drawString(s, (int) (cx - w / 2.0), (int) y);
    }

    /**
     * Builds the colour scheme for this frame. The scheme drifts through day,
     * dusk and night as the score climbs, so a long run visibly changes scenery.
     * @return the interpolated palette
     */
    private Palette palette() {
        Palette[] cycle = {Palette.DAY, Palette.DUSK, Palette.NIGHT, Palette.DUSK};
        double position = score / 12.0;
        int i = (int) position % cycle.length;
        double t = position - Math.floor(position);
        return Palette.lerp(cycle[i], cycle[(i + 1) % cycle.length], t);
    }

    /**
     * Reads the stored best score for a difficulty.
     * @param d the difficulty
     * @return the stored best, or 0 when unavailable
     */
    private int loadBest(Difficulty d) {
        try(FileInputStream in = new FileInputStream(scoreFile())) {
            Properties props = new Properties();
            props.load(in);
            return Integer.parseInt(props.getProperty(d.name(), "0"));
        }
        catch(Exception e) {
            return 0;
        }
    }

    /**
     * Persists the best score for a difficulty. Failures are ignored, since a
     * read-only home directory should not break the game.
     * @param d the difficulty
     * @param value the new best score
     */
    private void saveBest(Difficulty d, int value) {
        Properties props = new Properties();
        try(FileInputStream in = new FileInputStream(scoreFile())) {
            props.load(in);
        }
        catch(Exception e) {
            //no existing file yet
        }
        props.setProperty(d.name(), String.valueOf(value));
        try(FileOutputStream out = new FileOutputStream(scoreFile())) {
            props.store(out, "Flappy src.Bird Redux high scores");
        }
        catch(Exception e) {
            //best score simply will not survive this session
        }
    }

    /**
     * Location of the high score file.
     * @return the score file path
     */
    private File scoreFile() {
        return new File(System.getProperty("user.home", "."), ".flappy_bird_redux");
    }

    public State getState() {
        return state;
    }

    public int getScore() {
        return score;
    }

    /**
     * Overrides the difficulty, used by the smoke test.
     * @param d the difficulty to select
     */
    public void setDifficulty(Difficulty d) {
        difficulty = d;
        gap = d.gapStart;
        speed = d.speedStart;
        best = loadBest(d);
    }

    /**
     * Drives the single-button input from code, used by the smoke test.
     */
    public void pressPrimary() {
        primaryAction();
    }

    /**
     * The bird's current vertical centre, used by the smoke test.
     * @return the bird's centre y in logical pixels
     */
    public double birdCenterY() {
        return bird.getCenterY();
    }

    /**
     * The vertical centre of the next gap the bird has to fly through, used by
     * the smoke test to play like a human aiming at the opening.
     * @return the target y in logical pixels, or mid-screen when no pipe is ahead
     */
    public double nextGapTargetY() {
        for(PipePair p : pipes) {
            if(p.getRightEdge() >= bird.getX()) {
                return (p.getGapTop() + p.getGapBottom()) / 2.0;
            }
        }
        return H * 0.45;
    }

    /** A piece of debris. */
    private static final class Particle {
        double x, y, vx, vy, size;
        int life, maxLife;
        Color color;

        Particle(double x, double y, double vx, double vy, int life, double size, Color color) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.life = life;
            this.maxLife = life;
            this.size = size;
            this.color = color;
        }
    }

    /** A floating score label. */
    private static final class ScorePop {
        static final int LIFE = 46;
        final String text;
        double x, y;
        int life = LIFE;

        ScorePop(String text, double x, double y) {
            this.text = text;
            this.x = x;
            this.y = y;
        }
    }

    /** A background cloud. */
    private static final class Cloud {
        double x, y, scale, speedMul;

        Cloud(double x, double y, double scale, double speedMul) {
            this.x = x;
            this.y = y;
            this.scale = scale;
            this.speedMul = speedMul;
        }
    }

    /** A complete colour scheme for one time of day. */
    private static final class Palette {
        static final Palette DAY = new Palette(
                new Color(74, 186, 240), new Color(186, 232, 255), new Color(255, 255, 255),
                new Color(140, 198, 214), new Color(104, 186, 126), new Color(92, 189, 70),
                new Color(222, 198, 120), new Color(186, 156, 86), new Color(124, 200, 104), 0.0);

        static final Palette DUSK = new Palette(
                new Color(64, 78, 152), new Color(255, 158, 118), new Color(255, 206, 206),
                new Color(100, 100, 154), new Color(72, 82, 122), new Color(86, 162, 86),
                new Color(190, 150, 110), new Color(148, 110, 84), new Color(96, 152, 96), 0.35);

        static final Palette NIGHT = new Palette(
                new Color(12, 16, 46), new Color(54, 54, 112), new Color(198, 204, 236),
                new Color(34, 40, 80), new Color(22, 28, 58), new Color(56, 132, 88),
                new Color(74, 68, 98), new Color(48, 44, 70), new Color(58, 118, 86), 1.0);

        final Color skyTop, skyBottom, cloud, hillFar, hillNear, pipe, ground, groundDark, grass;
        final double night;

        Palette(Color skyTop, Color skyBottom, Color cloud, Color hillFar, Color hillNear,
                Color pipe, Color ground, Color groundDark, Color grass, double night) {
            this.skyTop = skyTop;
            this.skyBottom = skyBottom;
            this.cloud = cloud;
            this.hillFar = hillFar;
            this.hillNear = hillNear;
            this.pipe = pipe;
            this.ground = ground;
            this.groundDark = groundDark;
            this.grass = grass;
            this.night = night;
        }

        /**
         * Blends two palettes.
         * @param a palette at t = 0
         * @param b palette at t = 1
         * @param t blend factor from 0 to 1
         * @return the blended palette
         */
        static Palette lerp(Palette a, Palette b, double t) {
            return new Palette(mix(a.skyTop, b.skyTop, t), mix(a.skyBottom, b.skyBottom, t),
                    mix(a.cloud, b.cloud, t), mix(a.hillFar, b.hillFar, t),
                    mix(a.hillNear, b.hillNear, t), mix(a.pipe, b.pipe, t),
                    mix(a.ground, b.ground, t), mix(a.groundDark, b.groundDark, t),
                    mix(a.grass, b.grass, t), a.night + (b.night - a.night) * t);
        }

        /**
         * Blends two colours.
         * @param a colour at t = 0
         * @param b colour at t = 1
         * @param t blend factor from 0 to 1
         * @return the blended colour
         */
        private static Color mix(Color a, Color b, double t) {
            return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * t),
                    (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                    (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
        }
    }
}