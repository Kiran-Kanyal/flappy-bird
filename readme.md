# Flappy src.Bird Redux

A Java/Swing Flappy src.Bird with real flap physics, three difficulty presets, a
day-to-night cycle, and one-button start and restart.

## Play

```bash
bash run.sh      # compile and play
bash run.sh test    # headless smoke test, no window needed
```

Requires a JDK (tested on 21).

## Controls

One button does everything — start, flap, and play again.

| Input | Action |
| --- | --- |
| `Space` / `↑` / `W` / `Enter` / click | Start, flap, and replay |
| `←` `→` | Change difficulty (on the menu or score card) |
| `P` or `Esc` | Pause / resume. From the score card, `Esc` returns to the menu |
| `R` | Restart instantly |
| `M` | Mute |
| `Q` | Quit |

## Difficulty

| Preset | Gap | Scroll speed | Pipe spacing |
| --- | --- | --- | --- |
| Chill | 240 | 2.05 → 2.9 | 310 |
| Normal | 206 | 2.55 → 3.75 | 272 |
| Insane | 170 | 3.25 → 4.8 | 234 |

The gap narrows and the speed rises with your score, both clamped so the ramp
stays fair. Gap placement is also capped relative to the previous pipe, so two
pipes can never demand an impossible climb. Best scores are kept per difficulty
in `~/.flappy_bird_redux`.

## How it works

| File | Role |
| --- | --- |
| `src.TopClass.java` | Entry point; builds the window |
| `src.GamePanel.java` | Simulation, rendering, input, persistence |
| `src.Bird.java` | Player physics and sprite drawing |
| `src.PipePair.java` | One obstacle, drawn procedurally |
| `src.Sprites.java` | Image loading, scaling and caching |
| `src.Sfx.java` | Synthesised sound bank |
| `src.SmokeTest.java` | Headless checks for the simulation and renderer |

Two decisions shape how it plays:

**A fixed logical world.** Everything is simulated in a 480×720 space that is
scaled to fit the window. The bird, the pipes and the distance one flap carries
you are always in the same proportion to the playfield, so the game feels
identical on a laptop and a 5K display. Resize the window freely.

**A fixed 60 Hz timestep.** A Swing timer accumulates real elapsed time and runs
whole simulation ticks, so motion is the same speed on any machine and the game
never busy-waits a thread. Surplus time after a stall is dropped rather than
fast-forwarded.

Flapping sets an upward velocity that gravity then works against, instead of
moving the bird a fixed distance per press, which is what makes the controls
feel continuous. The collision box is deliberately smaller than the sprite, so
clipping a pipe's edge is a near miss rather than a death, and touching the sky
slows you instead of ending the round.

## Testing

`bash run.sh test` drives the game with no window and asserts the things that make
it playable: a round starts in one press, a flap lifts the bird, an unattended
bird dies, one press replays from the score card, every screen renders, and a
bot that simply aims at the next gap clears 25+ pipes on Chill and Normal while
scoring far lower on Insane.

## Credits

src.Bird sprite from `resources/`. The pipes are now drawn procedurally so they stay
crisp at any gap height, which the fixed-size tube sprites could not do without
stretching their end caps; those sprites are kept in `resources/` but unused.
See `LICENSE`.
