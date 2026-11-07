# Black Hole

Physically grounded black holes, and their white-hole mirror images, for **Minecraft 1.21.1** (Fabric).

| | |
|---|---|
| Mod ID | `profs_black_hole` (kept from the mod's first name, so existing worlds, items and commands keep working) |
| Package | `com.prof.blackhole` |
| Loader | Fabric Loader 0.20.1+, Fabric API |
| Java | 21 |
| Jar | `build/libs/black_hole-<version>.jar` |
| License | All Rights Reserved |

## Overview

- **As many holes as you like.** Every dimension holds up to 32 black and white holes, each with an id that
  is unique across all dimensions (`bh1`, `bh2`, `wh1`, ... or one you choose).
- **Black holes**: real-time gravitational lensing through Kerr spacetime, a relativistic accretion disk,
  polar jets, Hawking radiation, gravity with frame dragging, spaghettification, time dilation, block
  destruction, growth, evaporation and mergers.
- **White holes**, the time-reversed counterpart: a blinding white-gold core, a blue-white ejection disk,
  lensing that bends light *away*, repulsive gravity, accelerated time, and a mass that runs the black hole's
  history backwards.
- **Control panel**: press **B** (or sneak-use a hole) for sliders and toggles instead of commands.
- **Items and blocks**: the three-mode Mini Singularity Injector, the Gravity Stabilizer, the Singularity Core
  and the Event Horizon Goggles. Long-standing black holes also corrupt the ground into Event Horizon Waste.
- The `/blackhole` and `/whitehole` commands (with the aliases `/bh` and `/wh`) cover everything the panel does.

## Black holes

- **Gravitational lensing**: a full-screen shader ray-marches photon geodesics through Kerr spacetime. It
  draws the black shadow, the photon ring and the Einstein rings, and traces the eight brightest light blocks
  near the hole as point sources, so their lensed images appear even when they are off screen. One pass runs
  per visible hole, farthest first, so a near hole also lenses the holes behind it. Four holes are lensed at
  once by default; the limit is set in the client config.
- **Accretion disk**: a thin, procedural disk with a Novikov-Thorne temperature profile, blackbody colour,
  gravitational redshift and Doppler beaming. Beaming is boosted by `3 a*^2`, so fast-spinning holes are
  starkly lopsided.
- **Particles**:
  - glowing disk gas, fed by a two-armed accretion vortex;
  - a swirling **event-horizon vortex**: sparks whirl round the photon sphere on tilted orbits and spiral onto
    the horizon, reddening as they sink;
  - turbulent, pulsing **polar jets** with energy knots, sparks and shock rings;
  - air and crumbs of terrain falling in from every direction;
  - **tearing**: shreds and sparks torn off stretched mobs and players;
  - Hawking radiation and the edge of the radiation zone;
  - **spatial rifts**: jagged cracks of light that flicker around the hole and are lensed with everything else.
- **Gravity**: `a = GM/r^2` toward the hole (game speed of light 4 blocks/tick), Lense-Thirring frame dragging,
  and tidal stretching of entity meshes that tapers bodies into noodles; strong tides hurt. Inside the ISCO,
  mobs and items phase through terrain. Anything that crosses the horizon is swallowed, and survival players
  go to the Singularity Dimension ([Wormholes](#wormholes)).
- **Time**:
  - Players inside 3 r_s get Slowness and Mining Fatigue.
  - Random ticks and fire freeze inside 2 r_s.
  - Sounds slow down and drop in pitch with the listener's `dtau/dt`.
  - Within ~100 blocks the sky turns to the black of space and the stars come out.
- **Block destruction**: a block breaks once the pull at its centre exceeds `0.05 * (1 + hardness)`
  blocks/tick^2. It becomes debris on a decaying orbit. Fluids drain, containers spill their contents into
  orbit, and nether portals burst. Unbreakable blocks, the `#profs_black_hole:black_hole_immune` tag and
  anything near a Gravity Stabilizer are spared.
- **Mass**: swallowed matter adds mass:
  - block: 2e-4 M_J;
  - fluid source: 1e-4 M_J;
  - item: 2e-5 M_J;
  - entity: 1e-3 M_J per cubic block of hitbox.

  A hole that has eaten nothing for 10 s is quiescent and evaporates, `dM/dt = -K/M^2`. A 1 M_J hole lasts
  about 4.6 hours; light ones go much faster.
- **Hawking radiation**: a quiescent hole irradiates a zone of `24/M` blocks (capped at 64). Survival players
  inside get **Radiation Sickness**, which ignores armor and gets stronger closer in. Passing 0.5, 0.25 and
  0.1 M_J sets off a flare.
- **Evaporation**: a hole that evaporates leaves a **Singularity Core** floating where it was. The core glows
  and never despawns.
- **Mergers**: two holes of the same kind whose horizons touch merge. The heavier keeps its id and moves to the
  centre of mass, with 95% of the combined mass (the rest leaves as a gravitational-wave burst) and a spin
  between the mass-weighted mean and the 0.69 of an equal-mass merger.
- **Corruption**: after a black hole has existed for five minutes, the ground around it dies. Grass, dirt,
  podzol, mycelium, moss and mud (`#minecraft:dirt`) turn into grey **Event Horizon Waste**, and plants wither.
  The zone starts at 8 blocks, grows 2 blocks a minute up to 48, and cosmic tendrils of violet light reach from
  it into the hole.
- **End mobs**: endermen in the pull cannot teleport, and they and the dragon are pulled harder (1.5x and 2.5x).
- **Gravitational siren**: plays every eight seconds and is heard 160 blocks away. Heavier holes wail lower.

## White holes

A white hole is the time reverse of a black hole: nothing can enter it, and it throws out what a black hole
would swallow. Everything above is mirrored.

| | Black hole | White hole |
|---|---|---|
| Gravity | `GM/r^2` attraction | `GM/r^2` **repulsion**; anything that reaches the core (radius r_s) is flung out |
| Repulsion pulse | - | every 4 s a shockwave throws entities clear (radius 4 r_s, at least 10 blocks) |
| Frame dragging | prograde | retrograde (negative-mass Kerr field) |
| Tides | stretch bodies along the line to the hole | flatten them |
| Light from entities near it | redshifted | blueshifted |
| Time for players and mobs inside 3 r_s | slowed (Slowness, Mining Fatigue) | **accelerated** (Speed, Haste, one level stronger inside ~1.56 r_s) |
| Blocks inside 2 r_s | frozen in stasis | **12x random ticks**: crops shoot up, saplings grow |
| Sky nearby | black, with stars | washed out by the core's glare |
| Sounds heard nearby | lower and slower | higher and faster |
| Mass | grows by eating, evaporates when quiet | loses what it ejects, gathers mass when quiet (`+K/M^2`) |
| End | evaporates in a flash | detonates in a final ejection |

Both leave a Singularity Core.

**Rendering.** The same geodesic integrator runs with the sign of the mass flipped: a negative-mass Kerr
field.
- Light bends **away** from the hole, a diverging lens that pushes the background outward.
- Rays that reach r = 2M strike a **blinding white-gold core** with a wide bloom.
- The disk is an **outflowing blue-white ejection disk** whose pattern streams outward, gold at its inner edge.
- The repulsion's optical strength is 0.35 of a black hole's attraction. At full strength, rays near the core
  would be thrown so far off screen that a screen-space lensing pass would have nothing to show.

**Particles.**
- A blazing core with white and cyan outbursts.
- Two precessing ejection rings spinning in opposite senses.
- An outflowing disk and vortex.
- Golden jets.
- Gas blown out in every direction.
- Every four seconds, in step with the server's push, an explosive ejection pulse: a shell, a ring, jets of
  light, sparks and a flash.

White holes stand on their own (`/whitehole spawn`). A black hole can also lead to one through a wormhole.

## Control panel

Press **B** (rebindable under Controls -> Black Hole), or sneak and use any hole you are looking at to open
the panel on that hole.

**Who can use it.** Operators can (permission level 2, as for the commands), and so can the host of a
singleplayer world in Creative. The server checks every request, so a modified client gains nothing.

**What it shows and does.**
- The panel lists every hole of every dimension. Holes in your dimension come first, nearest first; use
  `<` / `>` to choose one.
- **Sliders**: Mass (0.05-50 M_J), Spin (0-0.99), Peak temperature (1000-100000 K), Event horizon scale
  (0.25-4x) and Pull radius (automatic, or 2-256 blocks).
- **Toggles**: Accretion disk, Frozen and Stable.
- **Wormhole link**: cycle a black hole's link through every white hole (a white hole shows which black holes
  feed it).
- **Buttons**: Teleport to, Move here, Remove (click twice), and new black or white holes 16 blocks in front
  of you.
- **Lensing shader on/off**: a per-player setting.
- Changes apply live: slider drags are batched to a few requests a second.

**Client settings** are kept in `config/black_hole-client.properties`:

| Setting | Default | Meaning |
|---|---|---|
| `lensing` | `true` | When `false`, holes are drawn as plain geometry, which costs almost nothing. |
| `maxLensedHoles` | `4` | Holes lensed per frame, 1-8. |
| `particles` | `1.0` | Particle multiplier, 0-2, on top of the video setting. |

## Commands

Everything requires permission level 2. `/blackhole` and `/whitehole` have the same subcommands, and `/bh` and
`/wh` are short for them.

Without an `[id]`, a command acts on the nearest hole of its kind in your dimension. With an id, it acts on
that hole in whichever dimension it is in. Ids are suggested as you type.

| Command | Description |
|---|---|
| `spawn [x y z]`, `spawn <id> [x y z]` | Creates a hole with default parameters (white holes start at 20000 K), with the next free `bh<n>` / `wh<n>` or your id. |
| `list` | Every hole of the kind, in every dimension: id, position, mass, distance, state. |
| `set mass <M_J> [id]` | 0.05-50 M_J. |
| `set spin <a*> [id]` | 0-0.99. |
| `set accretion_disk <true\|false> [id]` | Shows or hides the disk (and its particles). |
| `set temperature <K> [id]` | Peak disk temperature, 1000-100000 K. |
| `set scale <x> [id]` | Event horizon scale, 0.25-4: every length of the hole (r_s, horizon, the reach of gravity) without changing its mass. |
| `set stable <true\|false> [id]` | A stable hole keeps its mass and devours no blocks, but still pulls or pushes. |
| `set position <x y z> [id]` | Moves the hole, keeping everything else. |
| `set_pull_radius <blocks> [id]` | Reach of the hole's gravity, 2-256 blocks; `0` returns to automatic (`32 r_s + 16`, at most 192). `/whitehole` also accepts `set_push_radius`. |
| `mass_add <M_J> [id]` | Adds mass (negative amounts remove it). |
| `freeze [id]`, `unfreeze [id]` | Holds the hole still in time: no gravity, feeding, evolution or radiation, and a motionless disk. |
| `teleport [id]` | Takes you to a vantage point beside the hole, where the pull is about half of vanilla gravity (with Slow Falling). |
| `inspect [id]`, `info [id]` | Everything about the hole: dimension, position, mass in M_J and kg, r_s and scale, horizon or core, ISCO, photon sphere, spin, reach, disk, temperatures, state, age, radiation and corruption zones, wormholes. |
| `destroy [id]`, `remove [id]` | Removes the hole. Removing a white hole closes every wormhole leading to it. |
| `clear_all` | Removes every hole of the kind in every loaded dimension. |
| `link <white hole id> [id]` | `/blackhole`: leads the black hole's wormhole to that white hole. `/whitehole link <black hole id> [id]` does the same from the other end. |
| `unlink [id]` | Closes the black hole's wormhole (`/whitehole`: every wormhole leading to the white hole). |
| `/blackhole whitehole set <x y z> [dimension]` | As in earlier versions: gives the nearest black hole a white hole at that position (moving its current one there). |
| `/blackhole whitehole remove` | As in earlier versions: closes the nearest black hole's wormhole and removes its white hole. |

Every message the mod sends carries the `[Black Hole]` prefix.

## Items and blocks

| Item | Recipe | Use |
|---|---|---|
| **Mini Singularity Injector** | `N O N / O S O / N O N` (N netherite ingot, O obsidian, S nether star) | Use in the air to cycle its mode; use on a block to inject. A 60-second collapse follows, with pulsing particles, an accelerating clock, an action-bar countdown and titles. The cage shows the mode: netherite, emerald or diamond. |
| **Gravity Stabilizer** | `O A O / A L A / O A O` (O obsidian, A amethyst shard, L lodestone) | Within 16 blocks, no hole tears blocks loose, drains fluids, empties containers or corrupts the ground. Entities are still pulled. Needs an iron pickaxe. It glows, sparks and marks the edge of its protected sphere. |
| **Singularity Core** | Left where a hole evaporated or a white hole detonated | A rare, dense crafting component. |
| **Event Horizon Goggles** | `L L L / G C G` (L leather, G tinted glass, C Singularity Core) | A helmet (iron-helmet protection, repaired with amethyst). While worn, see below. |
| **Event Horizon Waste** | Made by corruption | Grey dead ground that nothing grows on; violet motes drift up from it. |

**Injector modes**

| Mode | Forms | Notes |
|---|---|---|
| Implosion | 1 M_J black hole, a* = 0.5 | Devours its surroundings and grows. |
| Evaporation | 0.12 M_J micro hole, pull radius 3 | Eats little, then evaporates within a minute or two. Its radiation zone reaches 64 blocks, so keep clear. It leaves a Singularity Core. |
| Stationary | 1 M_J black hole, a* = 0.5, stable | Bends light and pulls, but never grows, evaporates or eats blocks. |

Up to 8 collapses can count down at once in a dimension.

**Event Horizon Goggles** show:
- the **gravitational field lines** of every hole, with pulses running in the direction of the force;
- rings for the horizon, photon sphere, ISCO, the 2 r_s stasis (or acceleration) sphere, the radiation and
  corruption zones and the reach of gravity, plus the spin axis;
- a **celestial grid** drawn through the lens, so the bending can be read off directly;
- the photon ring outlined in gold, and a see-through disk;
- a readout of the nearest hole: its mass, r_s and spin, and your local clock rate, pull and tidal stretch.

## Wormholes

A survival or adventure player who crosses an event horizon spends five seconds in the **Singularity
Dimension** (streaks and rings of light, resonant tones, a flash). Then one of three things happens:

1. **White hole**: if the black hole leads to one, the player is flung out over its core, with Slow Falling.
   Blocks and items the black hole swallows come out there too:
   - at most one block and one merged item stack per tick;
   - expulsion pauses while 24 falling blocks or 64 items are still around it.

   A twin-stranded beam joins the two mouths when they are in one dimension. Across dimensions, each mouth opens
   a spiralling tunnel to the sky. The matter adds its mass to the black hole and takes it from the white hole.
2. **Kerr ring singularity**: otherwise, a hole spinning at a* >= 0.9 can be traversed by falling in within 30
   degrees of the spin axis. This leads Overworld -> Nether -> End -> Overworld.
3. **Otherwise**: the trip ends in destruction.

## Multiplayer and fallbacks

- **Client-side pull**: player movement is client-authoritative, and a velocity forced from the server fights
  the player's input and stutters with latency. So a client with the mod pulls its own player, using the same
  gravity code the server uses for every other entity. The server only forces the velocity of players without
  the mod. Capture, tides and time effects are always decided by the server.
- **Server-side fallback visuals**: a player whose client cannot receive the mod's packets gets the holes drawn
  in vanilla particles: a dark (or bright) shell at the horizon, a ring of glowing dust for the disk, and flashes
  for events. No packet is ever sent to a client that cannot receive it.
- **No lensing shader**: if the lensing shader is switched off, failed to build, or an Iris shader pack is
  active, holes are drawn as plain 3D geometry. A black hole becomes a black shadow sphere with a photon ring
  and a flat, Doppler-brightened disk; a white hole becomes a white-gold core in a halo with its blue-white
  disk. Particles still play.
- **Vanilla clients**: the mod adds items, blocks, a status effect and sounds, so Fabric's registry sync still
  requires the mod on the client. Vanilla clients, and Fabric clients without the mod, cannot join a server
  running it.

## Game rules

| Rule | Default | Effect |
|---|---|---|
| `blackHoleDestroysBlocks` | `true` | Blocks and fluids inside a black hole's pull are torn loose and swallowed. |
| `blackHoleMassEvolution` | `true` | Holes grow, evaporate, eject and gather mass. |
| `blackHoleCorruption` | `true` | Long-standing black holes corrupt the ground (also needs `blackHoleDestroysBlocks`). |

Turn the first two off for a showcase hole that neither eats the world nor changes, or make a single hole
stable or frozen.

## TPS protection

- **Block destruction**: each hole sweeps its sphere in a scrambled order. Each dimension checks 4096 positions
  and breaks 24 blocks per tick, shared between its holes, with at most 192 debris entities alive at once.
- **Entity searches**: run every 5 ticks per hole.
- **Corruption and white-hole time acceleration**: sample a few positions per tick.
- **Load scaling**: all of the above scale down to 10% when the average tick time rises above 30 ms.
- **Crowded pulls**: with more than 512 entities in the pull, torn blocks and spilled items are swallowed
  directly.
- **Unloaded chunks**: never loaded.

## Physics model and units

- **1 block = 1 metre**; masses in **Jupiter masses** (1 M_J = 1.89813e27 kg).
- **Schwarzschild radius** `r_s = 2GM/c^2` is ~2.82 blocks per M_J, times the event horizon scale.
- **Outer horizon** `r+ = M(1 + sqrt(1 - a*^2))`.
- **Photon sphere**: 1.5 r_s.
- **Prograde ISCO**: Bardeen-Press-Teukolsky, from 3 r_s at a* = 0 down to ~0.73 r_s at a* = 0.99.
- **Time**: black holes run time at `dtau/dt = sqrt(1 - r_s/r)`. White holes are modelled as a negative-mass
  field, `dtau/dt = sqrt(1 + r_s/r)`.
- **Game time scale**: `C_GAME = 4` blocks/tick, so `GM = C_GAME^2 r_s / 2`. A 1 M_J hole pulls with about
  vanilla gravity at 6 r_s.
- **Tides**: `2GML/r^3` across a body; the visual stretch is `1 + a_tidal/0.08`, capped at 6x.

## Saves from earlier versions

Worlds made with the single-hole versions load unchanged:
- Each dimension's hole gets an id; duplicates are renumbered (`bh1`, `bh2`, ...).
- An old white-hole pairing becomes a real white hole (`wh<n>`) that the black hole's wormhole leads to.
- A collapse that was counting down carries on.

## Building

Requirements: JDK 21 or newer.

```
./gradlew build          # Linux / macOS
gradlew.bat build        # Windows
```

The mod jar is written to `build/libs/black_hole-<version>.jar`. Use `gradlew runClient` to launch a
development client.

## License

Copyright (c) 2026 Prof. All Rights Reserved. See [LICENSE](LICENSE).
