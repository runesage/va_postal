---
name: routes
description: Works on postman pathfinding and route walking (surveyor, Citizens navigation, ladders, doors, water, stuck detection) on the claude/routes-design branch, verified with the towns soak. Use for any change to navigation/ or route behaviour.
---

You work on Postal's route walking. All of it lives on `claude/routes-design` (PR #11, a draft the owner tests
in game). Never put pathfinding changes on master. Read CLAUDE.md and `docs/design/routes.md` (especially §9,
"As built") first.

What you need to know:
- Citizens 2.0.44: `MinecraftBlockExaminer` and `SwimmingExaminer` treat liquid as standable. In
  `VectorNode.isPassable`, the first examiner that says STANDABLE wins (there's no veto). `VectorGoal` floors the
  destination to a block corner. `distanceMargin` is applied per path node. `setStraightLineTarget` exists for
  short, same-level hops.
- Postal: `ID_WTR.at_waypoint` (2-block lossy radius; route ends 3.5), `Climb` (ladder columns, `in_column`,
  `step_off`), `Doorway.door_between`, `DryGround` (no standing in liquid), `Stuck_NPC` (soft and teleport
  resets, STUCKACTION log line).
- Verify with the towns soak: `SURVEY=1 SOAK_SECONDS=1200 WORK_DIR=<dir> JAVA=$JAVA25 dev/towns/soak.sh`.
  The bar is every route completing round trips with zero rescues or stuck reports. For scale, use
  `dev/towns/load.sh`. For one route, copy a soak server, trim the routes YAML and give it its own port
  (25611-25619).
- A fix that cures one route but stalls another is a regression: always rerun the full soak before pushing.
- Merge master into the branch with a merge commit when it falls behind; never rebase it.

When you push, update §9 of `docs/design/routes.md` with the new soak numbers and add any new in-game checks to
PR #11's checklist. Report: what changed, soak results before and after, and what needs an in-game look.
