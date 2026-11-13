package top.npcpathfindinglegacy.pathfinding;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import top.npcpathfindinglegacy.pathfinding.PlannedRoute.Action;
import top.npcpathfindinglegacy.pathfinding.PlannedRoute.Step;
import top.npcpathfindinglegacy.tactics.*;
import top.npcpathfindinglegacy.tactics.flow.FlowField;
import top.npcpathfindinglegacy.tactics.group.AutoTactic;
import top.npcpathfindinglegacy.tactics.group.Formation;

/** Algorithm and decision checks using numeric inputs only; no Minecraft world is started. */
final class MajorUpdateSelfCheck {
    static void run() {
        checkJps(); checkRouteVariation(); checkTheta(); checkSegments(); checkWandWaypoints(); checkFlow(); checkTactics();
        checkFormationFollowing(); checkTacticalTiming(); checkSnowFloors();
        System.out.println("2.0.0 checks passed: seeded JPS/Dijkstra (1600 searches), equal-cost route variation, Theta, segments, wand waypoints, flow, tactics, 32-unit formation following and event timing");
    }

    private static void checkSnowFloors() {
        for (int layer=1; layer<=8; layer++) {
            Grid grid=new Grid(5,1);
            short[] heights=new short[grid.blocks.length];
            for (int x=0; x<5; x++) { heights[x]=1000; heights[5+x]=(short)(-1-(layer-1)*125); }
            TerrainSnapshot snow=new TerrainSnapshot(new GridPos(0,0,0),5,5,1,2,0,false,grid.blocks,0.6,1.8,null,heights);
            GridPos start=new GridPos(0,1,0),goal=new GridPos(4,1,0);
            require(snow.standable(start,false),"Snow layer became a full obstacle: "+layer);
            require(snow.jumpBodyClear(0.5,1+(layer-1)*0.125,0.5),"Snow floor blocked its own body");
            if (layer>1) require(!snow.fullSupport(start),"JPS must not flatten a raised snow surface");
            require(layer==1 || !snow.jumpBodyClear(0.5,1,0.5),"A jump may not pass through snow below its floor");
            PlannedRoute route=new RoutePlanner(snow,new RoutePlanner.Settings(3,100,0,0,3,1000),java.util.Collections.emptySet()).search(start,goal,0);
            require(route!=null&&route.reached(),"Walking route rejected snow layer "+layer);
            // The physical raised floor must also raise the headroom check.
            grid.blocks[3*5]=0;
            TerrainSnapshot ceiling=new TerrainSnapshot(new GridPos(0,0,0),5,5,1,2,0,false,grid.blocks,0.6,1.8,null,heights);
            require(ceiling.standable(start,false)==(layer<=2),"Snow headroom was tested at integer ground height");
            grid.blocks[0]=0;
            TerrainSnapshot floating=new TerrainSnapshot(new GridPos(0,0,0),5,5,1,2,0,false,grid.blocks,0.6,1.8,null,heights);
            require(!floating.standable(start,false),"Floating snow supplied nonexistent support");
        }
        Grid overhead=new Grid(5,1);short[] overheadHeights=new short[overhead.blocks.length];
        overheadHeights[2*5]=(short)-126;
        TerrainSnapshot overheadSnow=new TerrainSnapshot(new GridPos(0,0,0),5,5,1,2,0,false,overhead.blocks,0.6,1.8,null,overheadHeights);
        require(!overheadSnow.bodyClear(new GridPos(0,1,0)),"Snow in the head cell must retain its real collision");
        System.out.println("Snow checks passed: 1-8 layers, walking reachability, raised-floor headroom, no floating support or JPS flattening");
    }

    private static void checkJps() {
        Random random = new Random(20261113);
        for (int trial = 0; trial < 400; trial++) {
            Grid grid = new Grid(10 + random.nextInt(16), 10 + random.nextInt(16));
            for (int z = 0; z < grid.depth; z++) for (int x = 0; x < grid.width; x++)
                if (random.nextDouble() < 0.25) grid.block(x,z);
            int sx = random.nextInt(grid.width), sz = random.nextInt(grid.depth);
            int gx = random.nextInt(grid.width), gz = random.nextInt(grid.depth);
            grid.clear(sx,sz); grid.clear(gx,gz);
            GridPos start = new GridPos(sx,1,sz), goal = new GridPos(gx,1,gz);
            double expected = dijkstra(grid, start, goal);
            for (int seed : new int[]{0,17,-103,random.nextInt()}) {
            PlannedRoute result = new JumpPointPlanner(grid.freeze(),20000,seed).search(start,goal);
            require((result != null) == Double.isFinite(expected), "JPS reachability differs from Dijkstra at grid " + trial);
            if (result == null) continue;
            require(result.reached() && Math.abs(result.cost() - expected) < 1.0E-7,
                "JPS must retain the optimal eight-neighbor cost at grid " + trial);
            for (int i = 1; i < result.steps().size(); i++) {
                GridPos a = result.steps().get(i - 1).position(), b = result.steps().get(i).position();
                int dx = b.x() - a.x(), dz = b.z() - a.z();
                require(Math.abs(dx) <= 1 && Math.abs(dz) <= 1 && grid.open(b.x(),b.z()), "Invalid JPS step");
                if (dx != 0 && dz != 0) require(grid.open(a.x() + dx,a.z()) && grid.open(a.x(),a.z() + dz), "JPS cannot cut corners");
            }
            }
        }
        Grid open = new Grid(60,60);
        JumpPointPlanner planner = new JumpPointPlanner(open.freeze(),20000);
        require(planner.search(new GridPos(1,1,1),new GridPos(58,1,58)) != null && planner.expandedNodes() < 10,
            "Open-grid JPS should expand jump points instead of every grid node");
        Grid corner = new Grid(3,3); corner.block(1,0); corner.block(0,1);
        require(new JumpPointPlanner(corner.freeze(),100).search(new GridPos(0,1,0),new GridPos(1,1,1)) == null,
            "Blocked orthogonal neighbors must prevent a diagonal escape");
        Grid step = new Grid(5,1); short[] heights = new short[step.blocks.length];
        for (int x = 0; x < 5; x++) heights[x] = (short)(x == 2 ? 500 : 1000);
        TerrainSnapshot nonuniform = new TerrainSnapshot(new GridPos(0,0,0),5,5,1,2,0,false,step.blocks,0.6,1.8,null,heights);
        require(new JumpPointPlanner(nonuniform,100).search(new GridPos(0,1,0),new GridPos(4,1,0)) == null,
            "Classic JPS must exclude partial-height supports");
    }

    private static void checkRouteVariation() {
        Grid grid = new Grid(17,17);
        for (int z = 5; z <= 11; z++) grid.block(8,z);
        GridPos start = new GridPos(2,1,8), goal = new GridPos(14,1,8);
        RoutePlanner.Settings settings = new RoutePlanner.Settings(3,50,50,0,8,20000);
        double astarCost = new RoutePlanner(grid.freeze(),settings,top.npcpathfindinglegacy.compat.Immutable.set()).search(start,goal,0).cost();
        double jpsCost = dijkstra(grid,start,goal);
        Set<List<Step>> astarPaths = new HashSet<>(), jpsPaths = new HashSet<>();
        for (int seed = 1; seed <= 32; seed++) {
            PlannedRoute astar = new RoutePlanner(grid.freeze(),settings,top.npcpathfindinglegacy.compat.Immutable.set(),null,seed).search(start,goal,0);
            PlannedRoute jps = new JumpPointPlanner(grid.freeze(),20000,seed).search(start,goal);
            require(astar != null && astar.reached() && Math.abs(astar.cost() - astarCost) < 1.0E-7,
                "Seeded A* must preserve its movement costs and optimal route cost");
            require(jps != null && jps.reached() && Math.abs(jps.cost() - jpsCost) < 1.0E-7,
                "Seeded JPS must preserve Dijkstra cost on symmetric obstacles");
            astarPaths.add(astar.steps()); jpsPaths.add(jps.steps());
        }
        require(astarPaths.size() > 1 && jpsPaths.size() > 1, "Independent seeds should select multiple equal-cost obstacle detours");
    }

    private static final class Cost implements Comparable<Cost> {
private final int index;
private final double value;
public Cost(int index, double value) {
this.index=index;this.value=value;}
public int index() { return index; }
public double value() { return value; }
@Override public int hashCode() { return java.util.Objects.hash(index,value); }
@Override public boolean equals(Object value) { if (this==value) return true; if (!(value instanceof Cost)) return false; Cost other=(Cost)value; return java.util.Objects.equals(index,other.index) && java.util.Objects.equals(value,other.value); }

        public int compareTo(Cost other) { return Double.compare(value,other.value); }
    }
    private static double dijkstra(Grid grid, GridPos start, GridPos goal) {
        double[] costs = new double[grid.width * grid.depth]; Arrays.fill(costs,Double.POSITIVE_INFINITY);
        int first = start.z() * grid.width + start.x(), last = goal.z() * grid.width + goal.x();
        PriorityQueue<Cost> queue = new PriorityQueue<>(); costs[first] = 0; queue.add(new Cost(first,0));
        while (!queue.isEmpty()) {
            Cost current = queue.poll(); if (current.value() > costs[current.index()]) continue;
            if (current.index() == last) return current.value();
            int x = current.index() % grid.width, z = current.index() / grid.width;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0 || !grid.open(x + dx,z + dz)) continue;
                if (dx != 0 && dz != 0 && (!grid.open(x + dx,z) || !grid.open(x,z + dz))) continue;
                int index = (z + dz) * grid.width + x + dx;
                double cost = current.value() + (dx != 0 && dz != 0 ? Math.sqrt(2) : 1);
                if (cost >= costs[index]) continue;
                costs[index] = cost; queue.add(new Cost(index,cost));
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    private static void checkTheta() {
        Grid grid = new Grid(12,12);
        GridPos start = new GridPos(1,1,1), goal = new GridPos(10,1,8);
        PlannedRoute route = new RoutePlanner(grid.freeze(),new RoutePlanner.Settings(3,50,50,0,8,5000),top.npcpathfindinglegacy.compat.Immutable.set()).search(start,goal,0);
        PlannedRoute smooth = ThetaPathSmoother.smooth(grid.freeze(),route);
        require(route != null && smooth.steps().size() == 2 && smooth.steps().size() < route.steps().size(),
            "Theta should merge clear ground into a direct segment");
        grid.block(5,4);
        require(!grid.freeze().groundLineClear(start,goal), "Theta shortcut must detect an obstacle across its entire body path");
        Grid side = new Grid(5,4); side.block(2,1);
        require(!side.freeze().groundLineClear(new GridPos(1,1,1),new GridPos(3,1,2)),
            "Theta must reject body collisions near an oblique obstacle");
        Grid gap = new Grid(6,3); gap.blocks[gap.index(3,0,1)] = 0;
        require(!gap.freeze().groundLineClear(new GridPos(1,1,1),new GridPos(4,1,1)), "Theta must retain support over the whole segment");
        List<Step> steps = new ArrayList<>(); steps.add(new Step(new GridPos(1,1,1),Action.WALK));
        steps.add(new Step(new GridPos(2,1,1),Action.PARKOUR)); steps.add(new Step(new GridPos(3,1,1),Action.WALK));
        steps.add(new Step(new GridPos(4,1,1),Action.WALK));
        PlannedRoute special = ThetaPathSmoother.smooth(new Grid(6,3).freeze(),new PlannedRoute(steps,new GridPos(4,1,1),true,3));
        require(special.steps().contains(steps.get(1)), "Theta must retain parkour action endpoints");
        Grid door = new Grid(6,3); byte[] physical = new byte[door.blocks.length];
        physical[door.index(3,1,1)] = 1;
        TerrainSnapshot closedDoor = new TerrainSnapshot(new GridPos(0,0,0),6,5,3,2,0,false,door.blocks,0.6,1.8,physical);
        require(closedDoor.standable(new GridPos(3,1,1),false)
            && !closedDoor.groundLineClear(new GridPos(1,1,1),new GridPos(4,1,1)),
            "Openable doors remain A* nodes but cannot be hidden inside Theta shortcuts");
    }

    private static void checkSegments() {
        GridPos start = new GridPos(-130,6,75), goal = new GridPos(157,19,-113), current = start;
        for (int i = 0; i < 100 && !current.equals(goal); i++) {
            GridPos next = SegmentedPlanning.nextGoal(current,goal);
            require(!next.equals(current) && next.distanceSquared(goal) < current.distanceSquared(goal), "Segments must make progress without repeated endpoints");
            require(next.distanceSquared(current) < 18 * 18, "Each segment must stay locally bounded");
            current = next;
        }
        require(current.equals(goal) && goal.equals(new GridPos(157,19,-113)), "Segmentation must reach and preserve the external final goal");
        require(SegmentedPlanning.nextGoal(goal,goal).equals(goal), "A completed trip must not invent another segment");
    }

    private static void checkWandWaypoints() {
        Grid terrain = new Grid(30,30);
        List<GridPos> waypoints = top.npcpathfindinglegacy.compat.Immutable.list(new GridPos(3,1,3),new GridPos(24,1,3),new GridPos(24,1,24),new GridPos(3,1,24),new GridPos(3,1,3));
        for (int mode = 1; mode <= 7; mode++) {
            GridPos current = waypoints.get(0);
            RoutePlanner.Settings settings = new RoutePlanner.Settings(mode,50,50,4,8,5000);
            for (GridPos waypoint : waypoints.subList(1,waypoints.size())) {
                int segments = 0;
                while (!current.equals(waypoint) && segments++ < 10) {
                    GridPos goal = mode == 7 ? SegmentedPlanning.nextGoal(current,waypoint) : waypoint;
                    PlannedRoute route = new RoutePlanner(terrain.freeze(),settings,top.npcpathfindinglegacy.compat.Immutable.set(),null).search(current,goal,0);
                    require(route != null && route.reached(), "Coordinate patrol must find every wand waypoint in mode " + mode);
                    GridPos end = route.steps().get(route.steps().size() - 1).position();
                    require(!end.equals(current) && end.equals(goal), "A patrol segment must end at the requested coordinate without substituting a combat position");
                    current = end;
                }
                require(current.equals(waypoint), "Segmented patrol must reach the actual wand waypoint before advancing");
            }
        }
    }

    private static void checkFlow() {
        int width = 9, depth = 7; int[] heights = new int[width * depth]; Arrays.fill(heights,1);
        for (int z = 0; z < depth - 1; z++) heights[z * width + 4] = Integer.MIN_VALUE;
        int goal = 3 * width + 7;
        FlowField flow = new FlowField(width,depth,heights,goal);
        Arrays.fill(heights,Integer.MIN_VALUE);
        require(flow.next(3 * width + 4) < 0, "Blocked flow cells must remain unreachable");
        for (int initial = 0; initial < width * depth; initial++) {
            int index = initial;
            if (flow.next(index) < 0) continue;
            int count = 0;
            while (index != goal && count++ < width * depth) {
                int next = flow.next(index);
                require(next >= 0 && flow.distance(next) < flow.distance(index), "Flow must decrease monotonically without cycles");
                index = next;
            }
            require(index == goal, "Every reachable flow cell must arrive at the shared destination");
        }
        int[] island = {1,Integer.MIN_VALUE,1};
        require(new FlowField(3,1,island,2).next(0) < 0, "Flow must not cross an unreachable gap");
        require(new FlowField(3,1,new int[]{1,3,3},2).next(0) < 0, "Flow must not connect a two-block step");
    }

    private static TacticalContext context(WeaponRole role, double health, double distance, boolean visible, boolean hurt, boolean focused, int members, boolean narrow, boolean rough) {
        return new TacticalContext(role,health,0.7,distance,20,3,visible,hurt,focused,members,1,20,7,0.8,narrow,rough);
    }
    private static void checkTactics() {
        require(WeaponRole.classify(false,10) == WeaponRole.MELEE && WeaponRole.classify(true,0) == WeaponRole.RANGED
            && WeaponRole.classify(true,3) == WeaponRole.HYBRID, "Projectile slot and melee distance must distinguish all three weapon roles");
        require(context(WeaponRole.HYBRID,1,2,true,false,false,1,false,false).preferredRange() < 2
            && context(WeaponRole.HYBRID,1,9,true,false,false,1,false,false).preferredRange() > 3,
            "Hybrid units must use melee only within their configured distance");
        TacticalContext meleeAttack = context(WeaponRole.MELEE,1,2,true,false,false,1,false,false);
        for (TacticType type : top.npcpathfindinglegacy.compat.Immutable.list(TacticType.AGGRESSIVE,TacticType.BERSERKER,TacticType.OPPORTUNISTIC,
            TacticType.COUNTERATTACK,TacticType.STRAFING,TacticType.ADVANCE)) {
            Decision attack = TacticDecisions.decide(type,meleeAttack);
            require(Math.hypot(attack.forward(),attack.lateral()) < 2 && attack.allowAttack(),
                "Melee attack positions must stay within striking range: " + type);
        }
        TacticalContext hybrid = context(WeaponRole.HYBRID,1,12,true,false,false,1,false,false);
        require(TacticDecisions.decide(TacticType.AGGRESSIVE,hybrid).forward() == 0,
            "An aggressive hybrid must close into melee instead of fleeing to its ranged band");
        for (WeaponRole role : WeaponRole.values()) for (TacticType type : top.npcpathfindinglegacy.compat.Immutable.list(TacticType.AMBUSH,TacticType.GROUP_AMBUSH)) {
            Decision hiding = TacticDecisions.decide(type,context(role,1,10,true,false,false,8,false,false));
            require(hiding.cover() && !hiding.allowAttack(), "Ambush must conceal itself at ordinary ranged engagement distances");
        }
        require(TacticDecisions.decide(TacticType.COVER,context(WeaponRole.RANGED,1,15,true,false,false,8,false,false)).cover(),
            "Take cover must reach concealment before choosing a firing exposure");
        TacticalContext left = new TacticalContext(WeaponRole.RANGED,1,0.7,20,20,0,true,false,false,8,5,20,7,1,false,false);
        TacticalContext right = new TacticalContext(WeaponRole.RANGED,1,0.7,20,20,0,true,false,false,8,6,20,8,1,false,false);
        require(TacticDecisions.decide(TacticType.FLANKING,left).lateral() * TacticDecisions.decide(TacticType.FLANKING,right).lateral() > 0,
            "Flanking's maneuvering element must use one side");
        require(TacticDecisions.decide(TacticType.PINCER,left).lateral() * TacticDecisions.decide(TacticType.PINCER,right).lateral() < 0,
            "Pincer wings must approach opposite sides");
        for (TacticType type : TacticType.values()) for (WeaponRole role : WeaponRole.values())
            for (double health : new double[]{0.1,0.5,1}) for (double distance : new double[]{0,2,10,25}) {
                Decision decision = TacticDecisions.decide(type,context(role,health,distance,true,true,true,8,false,false));
                require(Double.isFinite(decision.forward()) && Double.isFinite(decision.lateral()) && Double.isFinite(decision.speed())
                    && decision.speed() > 0 && decision.speed() <= 4, "Invalid decision in " + type + "/" + role);
            }
        Set<TacticType> candidates = new HashSet<>();
        for (WeaponRole role : WeaponRole.values()) for (double health : new double[]{0.1,0.4,1})
            for (double distance : new double[]{2,11,20}) for (boolean visible : new boolean[]{false,true})
                for (boolean hurt : new boolean[]{false,true}) for (boolean focus : new boolean[]{false,true})
                    for (int members : new int[]{1,2,8}) for (boolean narrow : new boolean[]{false,true})
                        candidates.add(AutoTactic.choose(context(role,health,distance,visible,hurt,focus,members,narrow,false)));
        require(candidates.containsAll(top.npcpathfindinglegacy.compat.Immutable.list(TacticType.RETREAT,TacticType.COVER,TacticType.GROUP_AMBUSH,TacticType.FORMATION,
            TacticType.PINCER,TacticType.FLANKING,TacticType.SKIRMISH,TacticType.ADVANCE)), "Auto must be able to select every group behavior");
        require(AutoTactic.choose(context(WeaponRole.MELEE,0.1,2,true,false,false,8,false,false)) == TacticType.RETREAT,
            "Critical health must trigger retreat");
        for (Formation.Shape shape : Formation.Shape.values()) {
            Set<String> positions = new HashSet<>();
            for (int slot = 0; slot < 32; slot++) {
                double[] offset = Formation.offset(shape,slot);
                require(positions.add(Arrays.toString(offset)), "Formation slots must be distinct: " + shape);
            }
        }
        require(Formation.choose(context(WeaponRole.MELEE,1,20,true,false,false,8,true,false)) == Formation.Shape.COLUMN,
            "Narrow terrain must select column");
        require(Formation.choose(context(WeaponRole.RANGED,1,5,true,false,false,8,false,false)) == Formation.Shape.LINE,
            "Close visible ranged engagements must select line");
        require(Formation.choose(context(WeaponRole.MELEE,0.3,20,true,false,false,8,false,false)) == Formation.Shape.BOX,
            "Defensive conditions must select box");
        require(Formation.choose(context(WeaponRole.MELEE,1,20,true,false,false,8,false,true)) == Formation.Shape.STAGGERED_COLUMN,
            "Rough terrain must select staggered column");
        require(Formation.choose(context(WeaponRole.MELEE,1,20,true,false,false,8,false,false)) == Formation.Shape.WEDGE,
            "Ordinary advance must select wedge");
        require(Formation.leaderScore(1,WeaponRole.HYBRID,2,false) > Formation.leaderScore(0.2,WeaponRole.HYBRID,2,true) + 0.8,
            "A healthy member must outrank an injured incumbent including leader retention bonus");
        require(Formation.leaderScore(1,WeaponRole.MELEE,60,false) + 0.8 > Formation.leaderScore(1,WeaponRole.MELEE,0,false),
            "The column's long tail must not elect a new leader just because the formation changes the squad center");
    }

    private static void checkFormationFollowing() {
        Random random = new Random(9371);
        for (Formation.Shape shape : Formation.Shape.values()) for (double angle : new double[]{0,Math.PI / 6,Math.PI / 2,2.3})
            for (double spacing : new double[]{2,3.8}) {
                double awayX = Math.cos(angle), awayZ = Math.sin(angle), anchorX = -13.2, anchorZ = 7.8;
                double[] x = new double[32], z = new double[32];
                int[] shuffled = new int[32]; for (int i = 0; i < 32; i++) shuffled[i] = i;
                for (int i = 31; i > 0; i--) { int j = random.nextInt(i + 1), old = shuffled[i]; shuffled[i] = shuffled[j]; shuffled[j] = old; }
                int leader = -1;
                for (int member = 0; member < 32; member++) {
                    double[] point = Formation.position(anchorX,anchorZ,awayX,awayZ,shape,shuffled[member],spacing);
                    x[member] = point[0] + (random.nextDouble() - 0.5) * 0.2;
                    z[member] = point[1] + (random.nextDouble() - 0.5) * 0.2;
                    if (shuffled[member] == 0) { leader = member; x[member] = anchorX; z[member] = anchorZ; }
                }
                int[] order = Formation.assign(x,z,leader,shape,awayX,awayZ,spacing);
                for (int slot = 0; slot < 32; slot++) require(shuffled[order[slot]] == slot,
                    "Members already near their slots must not cross the formation to sort by identity: " + shape);
                Set<String> nodes = new HashSet<>();
                for (int slot = 0; slot < 32; slot++) {
                    double[] point = Formation.position(anchorX,anchorZ,awayX,awayZ,shape,slot,spacing);
                    double[] shifted = Formation.position(anchorX + 19,anchorZ - 11,awayX,awayZ,shape,slot,spacing);
                    require(Math.abs(shifted[0] - point[0] - 19) < 1.0E-8 && Math.abs(shifted[1] - point[1] + 11) < 1.0E-8,
                        "Every slot must follow the same leader displacement and heading");
                    require(nodes.add(Math.floor(point[0]) + ":" + Math.floor(point[1])), "PathPoint rounding must not merge formation slots");
                    for (int other = 0; other < slot; other++) {
                        double[] previous = Formation.position(anchorX,anchorZ,awayX,awayZ,shape,other,spacing);
                        require(Math.hypot(Math.floor(point[0]) - Math.floor(previous[0]),Math.floor(point[1]) - Math.floor(previous[1])) >= spacing - 1,
                            "Rounded slots must retain space for the configured NPC width");
                    }
                    x[order[slot]] += random.nextDouble() * 16 - 8; z[order[slot]] += random.nextDouble() * 16 - 8;
                }
                // Numeric integration uses the production slot functions; the world/EntityAIBase wiring is checked in the artifact.
                for (int tick = 0; tick < 720; tick++) {
                    if (tick < 600) { anchorX += 0.06; anchorZ -= 0.03; }
                    x[leader] = anchorX; z[leader] = anchorZ;
                    for (int slot = 1; slot < 32; slot++) {
                        int member = order[slot];
                        double[] point = Formation.position(anchorX,anchorZ,awayX,awayZ,shape,slot,spacing);
                        double dx = Math.floor(point[0]) + 0.5 - x[member], dz = Math.floor(point[1]) + 0.5 - z[member];
                        double movement = Math.min(1,0.3 / Math.max(0.0001,Math.hypot(dx,dz)));
                        x[member] += dx * movement; z[member] += dz * movement;
                        if (tick >= 580 && tick < 600) require(Math.hypot(Math.floor(point[0]) + 0.5 - x[member],Math.floor(point[1]) + 0.5 - z[member]) <= 2.5,
                            "Moving followers must stay inside the advancing formation's tolerance");
                    }
                }
                for (int slot = 1; slot < 32; slot++) {
                    double[] point = Formation.position(anchorX,anchorZ,awayX,awayZ,shape,slot,spacing);
                    require(Math.hypot(x[order[slot]] - Math.floor(point[0]) - 0.5,z[order[slot]] - Math.floor(point[1]) - 0.5) <= 1,
                        "Scattered followers must gather and keep up with a moving leader: " + shape);
                }
            }
    }

    private static void checkTacticalTiming() {
        Set<Integer> retreats = new HashSet<>(), peeks = new HashSet<>(); Set<Double> sides = new HashSet<>(), ranges = new HashSet<>();
        for (int seed = 0; seed < 64; seed++) {
            Random random = new Random(seed * 7919L + 13); TacticalTiming timing = new TacticalTiming();
            timing.update(100,false,false,false,1,random::nextInt);
            require(timing.attackPhase, "An unthreatened unit must be able to attack");
            sides.add(timing.side); ranges.add(timing.rangeScale);
            timing.update(101,true,true,false,0.5,random::nextInt);
            require(!timing.attackPhase, "A new enemy swing must trigger a response without waiting for the scheduled maneuver");
            require(timing.rangeScale >= 0.85 && timing.rangeScale <= 1.1 && timing.leadTicks >= 1 && timing.leadTicks <= 4,
                "Random maneuver and prediction settings must stay bounded");
            timing.contact(102,1,random::nextInt);
            int duration = 0; while (timing.retreating(102 + duration) && duration < 100) duration++;
            require(duration >= 18 && duration <= 60, "Contact withdrawal must have a bounded duration"); retreats.add(duration);
            timing.contact(103,1,random::nextInt);
            require(!timing.retreating(102 + duration), "Repeated attack callbacks during cooldown must not prolong withdrawal");
            timing.update(104,false,false,true,0.8,random::nextInt);
            require(!timing.retreating(104), "A healthy unit must stop withdrawing when the enemy escapes");
            timing.reset();
            require(!timing.peek(100,false,false,random::nextInt), "A unit must arrive at cover before starting a peek");
            require(timing.peek(101,true,false,random::nextInt), "Arrival at cover should allow a firing exposure");
            duration = 0; while (timing.peek(101 + duration,false,false,random::nextInt) && duration < 100) duration++;
            require(duration >= 16 && duration <= 37, "Exposure duration must stay bounded"); peeks.add(duration);
            timing.reset(); timing.peek(100,true,false,random::nextInt);
            require(!timing.peek(101,true,true,random::nextInt) && !timing.peek(102,true,false,random::nextInt),
                "Damage must end exposure and hold the unit in cover");
        }
        require(retreats.size() > 8 && peeks.size() > 8 && sides.size() == 2 && ranges.size() > 8,
            "Independent NPCs must not share fixed withdrawal, peek, lateral or range patterns");
    }

    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static final class Grid {
        final int width, depth; final byte[] blocks;
        Grid(int width, int depth) {
            this.width = width; this.depth = depth; blocks = new byte[width * 5 * depth];
            Arrays.fill(blocks,(byte)TerrainSnapshot.BODY);
            for (int z = 0; z < depth; z++) for (int x = 0; x < width; x++) blocks[index(x,0,z)] = (byte)TerrainSnapshot.SUPPORT;
        }
        int index(int x, int y, int z) { return (y * depth + z) * width + x; }
        void block(int x, int z) { blocks[index(x,1,z)] = 0; blocks[index(x,2,z)] = 0; }
        void clear(int x, int z) { blocks[index(x,1,z)] = TerrainSnapshot.BODY; blocks[index(x,2,z)] = TerrainSnapshot.BODY; }
        boolean open(int x, int z) { return x >= 0 && z >= 0 && x < width && z < depth && blocks[index(x,1,z)] == TerrainSnapshot.BODY; }
        TerrainSnapshot freeze() { return new TerrainSnapshot(new GridPos(0,0,0),width,5,depth,2,0,false,blocks,0.6,1.8); }
    }
}
