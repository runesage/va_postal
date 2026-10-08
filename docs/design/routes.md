# Design: routes without manual waypoints

**Status:** option A (automatic survey) is built, with ladder climbing; see §8. The other options are still
ideas.

## 1. The problem

Every address needs a route: a list of waypoints from its post office to the address, placed by hand with
the `/setroute` editor (right-click blocks to append, commands to move, insert or remove). It works, but it
breaks immersion: players spend time clicking blocks instead of playing, and a route breaks quietly when
the town changes around it.

## 2. How routes work today

- A route is stored per address in `config.yml` (`address.<office>.<address>.route.<n>.location`).
- The postman walks it waypoint by waypoint. Citizens' pathfinder only handles the short hop between two
  waypoints (bounded by `settings.range`).
- When a postman gets stuck, `Stuck_NPC` teleports it on.
- `/showroute`, Dynmap and route timing all read the same waypoint list.

**Why waypoints exist at all:** Citizens' pathfinder isn't meant for long paths. Beyond its search range it
gives up, and long searches are expensive. Whatever replaces manual routes still has to give the NPC short
hops. The question is only who works them out.

## 3. Options

These can be combined; they aren't alternatives to choose one from.

### A. Automatic survey

When an address is registered (or on request), Postal works out a path from the office to the address chest
itself and saves it as ordinary waypoints.

- **Pathfinder:** Postal's own A* search over walkable blocks. It runs off the main thread on chunk
  snapshots, with a limit on the search area and the work per survey.
- **Prefers roads:** cheaper costs for path blocks, gravel, stone bricks, planks and slabs; higher costs for
  grass and sand. It avoids crops, water, lava and drops. It treats doors, gates and trapdoors as passable.
- **Saves turns only:** the found path is cut down to the points where it changes direction (with a maximum
  gap between points within the Citizens range), so the result is a normal waypoint route. The postman,
  `/showroute`, Dynmap and timing keep working unchanged.
- **Preview:** the route is drawn with particles, as `/showroute` does now.
- **RP framing:** "a surveyor from the post office mapped your route."

Open points: whether a person confirms the route or it's accepted automatically; how far a survey may reach;
what happens when the survey finds no path.

### B. "Show the postman the way" (walk-recording)

The owner gets a route map item at the office and walks from the office to their address. Postal samples
their position as they walk, then trims the samples to waypoints the same way as option A. The item is used
up when they reach the address chest.

- No clicking blocks. It's the in-world version of the editor.
- It's a natural fallback when a survey fails (bridges, ladders, odd builds), and it needs no pathfinder.
- The click editor could stay as the admin tool.

Open points: how to reject a recording that the NPC can't follow (jumps, elytra, boats, teleports);
whether a player other than the owner may record it.

### C. Self-healing routes

When a postman gets stuck, Postal re-surveys from where it is to the next reachable waypoint and patches the
saved route, instead of only teleporting it on.

- A route that keeps breaking could be flagged for the Central inspector (`docs/design/central-office.md`
  §5).
- Needs option A's pathfinder.

Open points: how many patches before a route is flagged; whether patches need confirming.

### D. Town road network

Instead of one route per address, each town keeps one shared graph of road segments, surveyed (A) or walked
(B) once. A new address only needs a short leg from the nearest road node, which is cheap to survey. A
postman's route is a shortest path through the graph.

- Routes in a town stay consistent, and fewer surveys are needed.
- It makes **multi-stop delivery** possible: one trip covering several addresses. Today each trip serves one
  address, which is a large part of why big towns feel slow.
- It's the biggest change here: dispatch and route timing would work from the graph instead of a stored list.

Open points: how road segments are created and kept up to date; whether in-world markers (signposts) should
define the nodes.

### E. Out-of-sight travel between towns

Office-to-Central trips can be hundreds of blocks: too long to survey cheaply, and wasteful to walk through
chunks nobody is in. When no player is near, the NPC could travel "off-screen": Postal waits the estimated
travel time and moves the NPC on, walking only where someone could see it.

- Saves server work and fixes NPCs stalling in unloaded chunks.
- Could be framed as a mail coach.

Open points: how "a player is near" is measured; how the travel time is estimated (route length, a fixed
speed, or the recorded round-trip time).

## 4. Things any option has to handle

- **Cost:** surveys must be bounded (area and work) and run off the main thread.
- **Unloaded or ungenerated terrain:** chunk snapshots need loaded chunks; a survey into ungenerated land
  must stop, not generate it.
- **Protection plugins:** a route shouldn't cut through land the NPC couldn't walk through as a player
  (claims, regions). How Postal finds out without a hook for every plugin is open.
- **Vertical travel:** stairs and slabs are fine; ladders, scaffolding, water columns and elevators need a
  decision (unsupported, or recorded only).
- **Doors and gates:** the NPC must be able to open what the path goes through.
- **Compatibility:** options A–C and E keep the current waypoint format; D replaces it.

## 5. Possible phasing (not decided)

| Phase | Scope |
|---|---|
| R1 | Option A (survey) and option B (walk-recording), keeping the waypoint format |
| R2 | Option C (self-healing) and option E (out-of-sight travel) |
| R3 | Option D (road network, multi-stop delivery) |

## 6. Test world

`dev/towns/` builds three small towns (hills with stairs and a slab ramp, a river with two kinds of bridge, a
fence gate, a door, an alley, a tunnel, a hedge maze, trees and a ladder-only loft) with a route to each of
15 addresses, and `dev/towns/soak.sh` reports which addresses the postmen actually reach. It's the baseline
for today's manual routes and the test bed for any of the options above. See `dev/towns/README.md`.

## 7. Decisions

- **Surveyed routes are accepted automatically** and saved as ordinary waypoints. `/showroute` previews them
  and `/setroute` still edits them, so an admin can correct one by hand.
- **A new address is surveyed when it's registered** (`/setaddr`); it opens once it has a route. A failed
  survey says how close it got, and the address stays closed until a route is set by hand.
- **Ladders are supported**: the survey climbs them and Postal climbs the postman (Citizens can't).
  Scaffolding and vines count as ladders. Water columns and elevators aren't supported.
- **No swimming, no crops, drops of three blocks at most**, and never over a chest (so never across a
  mailbox).

## 8. As built (R1)

- **`Surveyor`** (pure Java, unit-tested on hand-built grids): A* over cells (`Cell`: open, ground, road,
  step, rough, wall, door, gate, ladder, crop, water, danger, unknown). Moves: walk, diagonal (only when both
  sides are clear), step up, drop (up to 3), through doors and gates, up and down ladders. Costs prefer roads
  and stairs, and treat a jump onto a full block as expensive, so the bridge's stairs win over jumping on
  from the side. Steps next to walls cost a little more, which keeps routes to the middle of passages.
  Bounded by a box (32 blocks around the two ends, 16 up or down) and 400,000 positions.
- **Waypoints:** the path is cut to straight hops at most 8 blocks long. Each hop is checked across the
  postman's width (no cut corners), and the path between must stay within 0.75 blocks of the line. A
  waypoint is kept on either side of every door (never on the door: Postal's door handling expects it
  between two waypoints), every jump and every drop of two or more, and on every ladder rung.
- **`SurveyGrid`**: chunk snapshots, so the search runs off the main thread. Chunks are loaded
  asynchronously and never generated, and unloaded or missing chunks read as walls. Blocks are classified by
  name.
- **`RouteSurvey`**: `/postal survey` and the `/setaddr` hook. Surveys run one at a time. A route a postman
  is walking right now isn't replaced.
- **`Climb`**: when the next waypoint is straight up or down a ladder column, Postal cancels Citizens'
  navigation and moves the postman 0.15 blocks a tick, facing the ladder.
- **Doors:** `Doorway` takes the postman through a door or gate just ahead of him: line up, open it, walk
  through, close it behind him. v4's door sequencer is off; Citizens' pathfinder wouldn't plan through a door
  even when it was open.
- **Stairs:** the survey knows which way stairs go up (a step from the front, a jump from the side or back),
  keeps a waypoint at the foot and top of every flight or drop, never on the stair itself, and keeps routes to
  the middle of bridges and away from ledges.
- **Arriving:** a waypoint counts as reached only at its height, and the top or foot of a climb only within
  1.25 blocks. The old 2 blocks, ignoring height, let a postman count himself on a bridge while still beside it.
- **Stall watchdog:** a postman who gets no closer to his waypoint for 30 seconds is teleported to it, so one
  stuck postman can't stop his office's queue.
- **Results (October 2026):** towns soak with surveyed routes: all 15 addresses walked, no rescues; SMP
  load test (12 towns, 240 addresses): every route surveyed (median 8 ms), TPS at least 18.9, no rescues.
- **Fixed on the way:** the stuck handler's door recovery looped forever, so a postman stuck at a door
  jumped in place for good.
