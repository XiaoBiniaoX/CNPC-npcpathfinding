# NPC Pathfinding Legacy 1.12.2

Java 8 behavior port of XiaoBiniaoX's Minecraft 1.20.1 `npcpathfinding` mod for
the SRParasites-compatible CustomNPCs v19 build. This is an independent Forge
addon; it does not modify the CustomNPCs JAR.

## Requirements

- Minecraft Forge 1.12.2 (`14.23.5.2859` tested)
- MixinBooter 10.7
- `CustomNPCs-SRParasites-compat-v19-hardtraderfix-tooltip-bulktrade-srpperf`
- The same addon JAR installed on both the server and every client

## Usage

Existing and newly created NPCs keep vanilla navigation by default. To enable
the port for one ground NPC:

1. Open the CustomNPCs editor.
2. Open the AI page.
3. Select `Pathfinding`.
4. Change the navigation mode from `Vanilla` to `Enhanced` and save the NPC.

Enhanced mode supports ordinary ground paths, diagonal movement, platforms,
short climbs, controlled drops, configured parkour gaps, ladders, and local
stuck recovery. It does not teleport stuck NPCs. Flying, swimming, and other
non-ground movement modes continue to use their existing navigator.

The original NBT field names are preserved:

- `NPC_PathfindingMode`
- `NPC_WalkTendency`
- `NPC_JumpTendency`
- `NPC_ParkourMaxDist`
- `NPC_ChaseJumpInterval`

## Configuration

Forge creates the global configuration at:

`config/npcpathfindinglegacy.cfg`

The configuration contains the master switch, path reuse interval, server-wide
A* searches-per-tick budget, per-search node limit, maximum drop height, stuck
threshold, and block blacklist/whitelist. Enhanced A* never queries unloaded
chunks while `preventChunkLoads=true`.

## Compatibility boundary

The addon only replaces the selected NPC's ground navigator. It does not change
CustomNPCs target selection, attack AI, Nashorn scripts, InoriLoot sessions, or
the Smart Path route system. The tested CustomNPCs AI Optimizer mixins can load
alongside this addon; real raid movement should still be validated before broad
deployment.

The 1.20.1 JAR bundles version-specific Baritone classes, but its active path
creation first tries vanilla navigation and then its own bounded parkour A*.
Its separate `createEmbeddedBaritonePath` method has no caller. This port
therefore reimplements the active behavior against the 1.12.2 APIs and does not
embed the incompatible 1.20.1 Baritone binary.

## Credits

- Original Minecraft 1.20.1 mod and behavior: XiaoBiniaoX
- Forge 1.12.2 compatibility port: 阿介的炒面

## Changes

- `1.0.2`: Keep each parkour landing fixed while airborne, calculate jump
  velocity from the actual height difference, dynamically brake toward the
  landing center, and briefly stabilize narrow-pad landings before the next
  hop. Vanilla path following is paused during a controlled jump so a chain of
  landing nodes cannot be consumed early.
- `1.0.1`: Prevent the vanilla navigator from reading past the final custom
  path node while an enhanced repath request is still pending.
