package src;

import java.awt.Dimension;
import java.awt.Image;
import java.awt.Toolkit;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * Entry point. Creates the window and hands everything else to src.GamePanel.
 *
 * The window opens at a comfortable, resizable size rather than forcing full
 * screen: the game world is a fixed logical size that src.GamePanel scales to fit,
 * so the play experience is the same at any window size.
 */
public class TopClass {
    private static final int DEFAULT_SCALE_NUMERATOR = 5, DEFAULT_SCALE_DENOMINATOR = 4;

    /**
     * Launches the game on the event dispatch thread.
     * @param args unused
     */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(TopClass::buildFrame);
    }

    /**
     * Builds and shows the game window.
     */
    private static void buildFrame() {
        JFrame frame = new JFrame("Flappy src.Bird Redux");
        GamePanel game = new GamePanel();

        frame.setContentPane(game);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setResizable(true);
        frame.setMinimumSize(new Dimension(GamePanel.W / 2, GamePanel.H / 2));

        //open slightly larger than the logical world, but never taller than the display
        int height = Math.min(GamePanel.H * DEFAULT_SCALE_NUMERATOR / DEFAULT_SCALE_DENOMINATOR,
                              (int) (Toolkit.getDefaultToolkit().getScreenSize().getHeight() * 0.9));
        int width = height * GamePanel.W / GamePanel.H;
        frame.getContentPane().setPreferredSize(new Dimension(width, height));
        frame.pack();
        frame.setLocationRelativeTo(null);

        Image icon = loadIcon();
        if(icon != null) {
            frame.setIconImage(icon);
        }

        frame.setVisible(true);
        game.requestFocusInWindow();
    }

    /**
     * Loads the window icon.
     * @return the icon image, or null when the resource is unavailable
     */
    private static Image loadIcon() {
        try {
            return Toolkit.getDefaultToolkit().getImage(TopClass.class.getResource("resources/images/blue_bird.png"));
        }
        catch(Exception e) {
            return null;
        }
    }
}
