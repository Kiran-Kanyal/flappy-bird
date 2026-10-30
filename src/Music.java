package src;

import java.io.File;
import java.net.URL;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;

/**
 * Looping background music. The track is loaded once into a Clip and played on
 * repeat. Like Sfx, every call is failure-tolerant: a missing file, an
 * unsupported format or a machine with no audio device leaves the game silent
 * rather than broken, so the game still runs with no music file present.
 */
public final class Music {
    private static final String TRACK = "resources/music/brdsofafeather.wav";

    //the synthesised sfx peak around 0.2 amplitude, so the music sits well below unity
    private static final float PLAY_DB = -14f;
    private static final float DUCK_DB = -24f;

    private static Clip clip;
    private static boolean muted = false;

    private Music() {
    }

    /**
     * Loads the track. Safe to call more than once.
     */
    public static void init() {
        if(clip != null) {
            return;
        }
        try {
            AudioInputStream raw = openStream();
            if(raw == null) {
                return; //no music file shipped; the game just runs without it
            }
            Clip c = AudioSystem.getClip();
            c.open(toPcm(raw));
            clip = c;
            gain(PLAY_DB);
        }
        catch(Exception e) {
            clip = null;
        }
    }

    /**
     * Starts the track from the beginning and loops it forever.
     */
    public static void play() {
        if(clip == null || muted) {
            return;
        }
        try {
            clip.setFramePosition(0);
            gain(PLAY_DB);
            clip.loop(Clip.LOOP_CONTINUOUSLY);
        }
        catch(Exception e) {
            //a busy or broken mixer must never interrupt gameplay
        }
    }

    /**
     * Halts playback, keeping the current position.
     */
    public static void pause() {
        if(clip == null) {
            return;
        }
        try {
            clip.stop();
        }
        catch(Exception e) {
        }
    }

    /**
     * Continues playback from where pause() left off.
     */
    public static void resume() {
        if(clip == null || muted) {
            return;
        }
        try {
            clip.loop(Clip.LOOP_CONTINUOUSLY);
        }
        catch(Exception e) {
        }
    }

    /**
     * Drops the music under the death and score-card sounds.
     */
    public static void duck() {
        gain(DUCK_DB);
    }

    /**
     * Returns the music to its normal level.
     */
    public static void restore() {
        gain(PLAY_DB);
    }

    /**
     * Releases the clip. Required before exit, because an open Clip holds a
     * non-daemon thread that can keep the JVM alive.
     */
    public static void stop() {
        if(clip == null) {
            return;
        }
        try {
            clip.stop();
            clip.close();
        }
        catch(Exception e) {
        }
        clip = null;
    }

    /**
     * Toggles the music independently of the sound effects.
     * @return true when the music is now muted
     */
    public static boolean toggleMute() {
        setMuted(!muted);
        return muted;
    }

    /**
     * Mutes or unmutes the music.
     * @param value true to silence the music
     */
    public static void setMuted(boolean value) {
        muted = value;
        if(muted) {
            pause();
        }
        else {
            resume();
        }
    }

    /**
     * Reports the current mute state.
     * @return true when the music is muted
     */
    public static boolean isMuted() {
        return muted;
    }

    /**
     * Finds the track on the classpath, falling back to the working directory so
     * the game also runs from an IDE where resources/ is not on the classpath.
     * @return an open stream, or null when the track cannot be found
     */
    private static AudioInputStream openStream() throws Exception {
        URL url = Music.class.getResource("/" + TRACK);
        if(url != null) {
            return AudioSystem.getAudioInputStream(url);
        }
        File file = new File(TRACK);
        return file.isFile() ? AudioSystem.getAudioInputStream(file) : null;
    }

    /**
     * Converts a stream to signed PCM when it is not already, which a Clip needs.
     * @param in the decoded stream
     * @return a stream a Clip can open
     */
    private static AudioInputStream toPcm(AudioInputStream in) {
        AudioFormat source = in.getFormat();
        if(AudioFormat.Encoding.PCM_SIGNED.equals(source.getEncoding())) {
            return in;
        }
        AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                source.getSampleRate(), 16, source.getChannels(),
                source.getChannels() * 2, source.getSampleRate(), false);
        return AudioSystem.getAudioInputStream(target, in);
    }

    /**
     * Sets playback volume in decibels, clamped to what the mixer allows.
     * @param db target gain; 0 is the file's own level
     */
    private static void gain(float db) {
        if(clip == null || !clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            return;
        }
        try {
            FloatControl control = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
            control.setValue(Math.max(control.getMinimum(), Math.min(control.getMaximum(), db)));
        }
        catch(Exception e) {
        }
    }
}