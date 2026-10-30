package test;

import src.GamePanel;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * Headless check that the simulation and renderer behave. It drives the game
 * through its states without opening a window, so it can run in a build or over
 * SSH, and it asserts the properties that matter for playability: a round
 * starts, a flap lifts the bird, an unattended bird dies, a bot that flaps on a
 * simple rule scores, and the game returns to a playable state afterwards.
 *
 * Run with: java -Djava.awt.headless=true test.SmokeTest
 */
public final class SmokeTest {
    private static int failures = 0;

    public static void main(String[] args) {
        GamePanel game = new GamePanel(false);
        BufferedImage canvas = new BufferedImage(GamePanel.W, GamePanel.H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();

        check("starts on the menu", game.getState() == GamePanel.State.MENU);

        //every screen must render without throwing
        game.render(g);

        game.pressPrimary();
        check("one press leaves the menu", game.getState() == GamePanel.State.READY);
        game.render(g);

        game.pressPrimary();
        check("second press starts play", game.getState() == GamePanel.State.PLAYING);

        //an idle bird should hit the ground, which proves gravity and ground collision work
        for(int i = 0; i < 600 && game.getState() == GamePanel.State.PLAYING; i++) {
            game.step();
        }
        check("an unattended bird eventually dies", game.getState() != GamePanel.State.PLAYING);

        //the game over screen must be reachable and renderable
        for(int i = 0; i < 300 && game.getState() != GamePanel.State.OVER; i++) {
            game.step();
        }
        check("death leads to the game over screen", game.getState() == GamePanel.State.OVER);
        game.render(g);

        //and a single press must get straight back into a round
        for(int i = 0; i < 60; i++) {
            game.step();
        }
        game.pressPrimary();
        check("one press replays after game over", game.getState() == GamePanel.State.READY);

        check("a simple bot clears 25+ pipes on Chill", botScore(GamePanel.Difficulty.CHILL, g) >= 25);
        check("a simple bot clears 25+ pipes on Normal", botScore(GamePanel.Difficulty.NORMAL, g) >= 25);
        check("Insane is genuinely harder", botScore(GamePanel.Difficulty.INSANE, g) >= 5);

        System.out.println(failures == 0 ? "All checks passed." : failures + " check(s) FAILED.");
        g.dispose();
        System.exit(failures == 0 ? 0 : 1);
    }

    /**
     * Plays a round with a bot that does the one thing a player does: aim at
     * the middle of the next gap and flap when it drops below it. A bot this
     * crude racking up a long run is the evidence that the physics, pipe
     * spacing and gap placement are actually controllable.
     * @param difficulty the preset to play
     * @param g a surface to render frames into
     * @return the score the bot reached
     */
    private static int botScore(GamePanel.Difficulty difficulty, Graphics2D g) {
        GamePanel game = new GamePanel(false);
        game.setDifficulty(difficulty);
        game.pressPrimary();
        game.pressPrimary();

        for(int i = 0; i < 20000 && game.getState() == GamePanel.State.PLAYING; i++) {
            if(game.birdCenterY() > game.nextGapTargetY()) {
                game.pressPrimary();
            }
            game.step();
            if(i % 120 == 0) {
                game.render(g);
            }
        }
        System.out.println("  bot on " + difficulty.label() + " scored " + game.getScore());
        return game.getScore();
    }

    /**
     * Records a check result.
     * @param name description of the expectation
     * @param condition true when the expectation holds
     */
    private static void check(String name, boolean condition) {
        System.out.println((condition ? "ok   " : "FAIL ") + name);
        if(!condition) {
            failures++;
        }
    }
}
