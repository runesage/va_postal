# Design: routes without manual waypoints

**Status:** ideas for discussion. **No decisions have been made**; everything below is an option.

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

## 6. Decisions

None yet.
