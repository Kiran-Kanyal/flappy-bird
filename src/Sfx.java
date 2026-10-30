package src;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;

/**
 * Tiny synthesised sound bank. Clips are rendered to memory once at startup so
 * playback is instant and the game has no binary audio dependencies. Every call
 * is failure-tolerant: on a machine with no audio device the game runs silently.
 */
public final class Sfx {
    private static final float SAMPLE_RATE = 44100f;

    private static Clip flap, score, hit, fall, swoosh, medal;
    private static boolean muted = false;

    private Sfx() {
    }

    /**
     * Renders every clip. Safe to call more than once.
     */
    public static void init() {
        if(flap != null) {
            return;
        }
        flap = sweep(760, 1180, 70, 0.22, false);
        score = sweep(880, 1320, 110, 0.20, false);
        hit = sweep(220, 90, 150, 0.30, true);
        fall = sweep(620, 110, 420, 0.22, false);
        swoosh = sweep(300, 760, 130, 0.14, true);
        medal = sweep(1040, 1560, 260, 0.18, false);
    }

    /**
     * Toggles audio output.
     * @return true when sound is now muted
     */
    public static boolean toggleMute() {
        muted = !muted;
        return muted;
    }

    /**
     * Reports the current mute state.
     * @return true when sound is muted
     */
    public static boolean isMuted() {
        return muted;
    }

    public static void flap() {
        play(flap);
    }

    public static void score() {
        play(score);
    }

    public static void hit() {
        play(hit);
    }

    public static void fall() {
        play(fall);
    }

    public static void swoosh() {
        play(swoosh);
    }

    public static void medal() {
        play(medal);
    }

    /**
     * Restarts a clip from its first frame.
     * @param clip the clip to play; ignored when null
     */
    private static void play(Clip clip) {
        if(clip == null || muted) {
            return;
        }
        try {
            clip.stop();
            clip.setFramePosition(0);
            clip.start();
        }
        catch(Exception e) {
            //a busy or broken mixer must never interrupt gameplay
        }
    }

    /**
     * Builds a clip that glides between two frequencies under a short decay envelope.
     * @param fromHz starting frequency
     * @param toHz ending frequency
     * @param millis clip length
     * @param volume peak amplitude from 0 to 1
     * @param noisy true to blend in white noise, which suits impact sounds
     * @return the opened clip, or null when audio is unavailable
     */
    private static Clip sweep(double fromHz, double toHz, int millis, double volume, boolean noisy) {
        try {
            int frames = (int) (SAMPLE_RATE * millis / 1000.0);
            byte[] data = new byte[frames * 2];
            double phase = 0;

            for(int i = 0; i < frames; i++) {
                double t = (double) i / frames;
                double hz = fromHz + (toHz - fromHz) * t;
                phase += 2 * Math.PI * hz / SAMPLE_RATE;

                //soft attack plus exponential decay keeps the blips from clicking
                double envelope = Math.min(1.0, t * 14.0) * Math.pow(1.0 - t, 1.7);
                double wave = Math.sin(phase) * 0.7 + Math.sin(phase * 2) * 0.3;
                if(noisy) {
                    wave = wave * 0.45 + (Math.random() * 2 - 1) * 0.55;
                }

                int sample = (int) (wave * envelope * volume * Short.MAX_VALUE);
                sample = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample));
                data[i * 2] = (byte) (sample & 0xFF);
                data[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
            }

            AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
            Clip clip = AudioSystem.getClip();
            clip.open(format, data, 0, data.length);
            return clip;
        }
        catch(Exception e) {
            return null;
        }
    }
}
