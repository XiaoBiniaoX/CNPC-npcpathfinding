package top.npcpathfindinglegacy.traffic;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import top.npcpathfindinglegacy.pathfinding.GridPos;
import top.npcpathfindinglegacy.traffic.OrcaSolver.Agent;
import top.npcpathfindinglegacy.traffic.OrcaSolver.Velocity;

/** Behavioral checks exercise production math/scheduling without a client or a world. */
public final class TrafficSelfCheck {
    private static final UUID A = new UUID(0,1), B = new UUID(0,2), C = new UUID(0,3);
    public static void run() {
        avoid(); reservations(); passages(); deadlocks();
        System.out.println("Traffic checks passed: ORCA crossing/overlap/body size, connected passages, A/B/C ordering, body-clear exit, timeout/cancel/death, priority aging, wait cycles and bounded escalation");
    }

    private static void avoid() {
        Agent a = agent(1,-4,0,0,0,0.3), b = agent(2,4,0,0,0,0.3);
        double minimum = Double.MAX_VALUE;
        for (int tick = 0; tick < 150; tick++) {
            Velocity av = OrcaSolver.solve(a,List.of(b),new Velocity(0.18,0),0.18,false);
            Velocity bv = OrcaSolver.solve(b,List.of(a),new Velocity(-0.18,0),0.18,false);
            require(av.length() <= 0.180001 && bv.length() <= 0.180001,"ORCA must respect maximum speed");
            a = agent(1,a.x() + av.x(),a.z() + av.z(),av.x(),av.z(),0.3);
            b = agent(2,b.x() + bv.x(),b.z() + bv.z(),bv.x(),bv.z(),0.3);
            minimum = Math.min(minimum,Math.hypot(a.x() - b.x(),a.z() - b.z()));
        }
        require(minimum >= 0.599,"Head-on units must avoid body overlap");
        require(a.x() > 2 && b.x() < -2,"Head-on units must pass rather than permanently stop");
        a = agent(1,-3,0,0,0,0.3); b = agent(2,0,-3,0,0,0.3);
        minimum = Double.MAX_VALUE;
        for (int tick = 0; tick < 100; tick++) {
            Velocity av = OrcaSolver.solve(a,List.of(b),new Velocity(0.13,0),0.13,false);
            Velocity bv = OrcaSolver.solve(b,List.of(a),new Velocity(0,0.13),0.13,false);
            a = agent(1,a.x() + av.x(),a.z() + av.z(),av.x(),av.z(),0.3);
            b = agent(2,b.x() + bv.x(),b.z() + bv.z(),bv.x(),bv.z(),0.3);
            minimum = Math.min(minimum,Math.hypot(a.x() - b.x(),a.z() - b.z()));
        }
        require(minimum >= 0.599,"Crossing routes must keep both NPC bodies separated");
        for (double radius : new double[]{0.2,0.3,0.8,1.5}) {
            a = agent(1,0,0,0,0,radius); b = agent(2,radius * 1.5,0,0,0,radius);
            Velocity v = OrcaSolver.solve(a,List.of(b),new Velocity(0.2,0),0.2,false);
            require(v.x() <= 0,"Overlapping larger bodies must not accelerate into each other");
            require(Double.isFinite(v.x()) && Double.isFinite(v.z()),"Result must be finite");
        }
        Velocity same = OrcaSolver.solve(agent(1,0,0,0,0,0.3),List.of(agent(2,0,0,0,0,0.3)),Velocity.ZERO,0.2,false);
        require(Double.isFinite(same.x()) && same.length() <= 0.20001,"Coincident bodies must have a finite bounded result");
        Agent stationary = new Agent(2,1,0,0,0,0.3,1.4,false);
        Velocity approach = OrcaSolver.solve(agent(1,0,0,0.15,0,0.3),List.of(stationary),new Velocity(0.2,0),0.2,false);
        require(approach.x() < 0.15,"Moving NPC must take full responsibility for a stationary obstacle");
        Velocity alone = OrcaSolver.solve(agent(1,0,0,0,0,0.3),List.of(),new Velocity(0.2,0),0.2,false);
        require(alone.equals(new Velocity(0.2,0)),"An isolated unit must keep its original preferred direction");
    }

    private static Agent agent(long id, double x, double z, double vx, double vz, double radius) {
        return new Agent(id,x,z,vx,vz,radius,1.4,true);
    }
    private static Set<GridPos> corridor() {
        Set<GridPos> cells = new HashSet<>();
        for (int x = 0; x < 10; x++) cells.add(new GridPos(x,1,0));
        return cells;
    }
    private static void observe(PathReservationManager m, UUID id, double x, long tick) { m.observe(id,x,1,0.5,0.3,tick); }
    private static void reservations() {
        PathReservationManager m = new PathReservationManager(); long region = m.register(corridor(),0);
        observe(m,A,0.5,0); observe(m,B,11.5,0); observe(m,C,13.5,0);
        require(!m.request(B,region,0,0).allowed(),"An existing physical occupant wins over an opposite-side arrival");
        require(m.request(A,region,0,0).allowed(),"A must own the complete passage");
        require(!m.request(C,region,0,1).allowed(),"C must wait behind B");
        observe(m,A,10.1,20); observe(m,B,11.5,20); observe(m,C,13.5,20);
        require(!m.request(B,region,0,20).allowed(),"Rights cannot transfer while A's bounding box still overlaps the exit");
        observe(m,A,10.5,21);
        require(m.request(B,region,0,21).allowed(),"B must acquire after A completely leaves");
        observe(m,B,9.5,22); require(m.request(B,region,0,22).allowed(),"B keeps its right throughout the region");
        require(!m.request(C,region,0,22).allowed(),"C must not enter behind B before B exits");
        observe(m,B,-0.5,40); observe(m,C,13.5,40);
        require(m.request(C,region,0,40).allowed(),"C must acquire after B leaves");

        m = new PathReservationManager(); region = m.register(corridor(),0);
        observe(m,A,1.5,0); observe(m,B,11.5,0); m.request(A,region,0,0); m.request(B,region,0,0);
        for (int tick = 20; tick <= 140; tick += 20) {
            observe(m,A,1.5,tick); observe(m,B,11.5,tick); m.request(B,region,0,tick);
            var permission = m.request(A,region,0,tick);
            if (tick == 140) require(permission.retreat(),"A stalled owner must be told to retreat after its progress lease expires");
        }
        require(!m.request(B,region,0,140).allowed(),"Timeout cannot grant through the stalled owner's body");
        m.cancel(A);
        require(!m.request(B,region,0,141).allowed(),"Cancel releases rights, but a remaining physical body still blocks entry");
        m.remove(A);
        require(m.request(B,region,0,142).allowed(),"Death/removal must immediately release occupancy and ownership");

        m = new PathReservationManager(); region = m.register(corridor(),0);
        observe(m,A,1.5,0); observe(m,B,11.5,0); observe(m,C,13.5,0); m.request(A,region,0,0);
        m.request(B,region,0,0); m.request(C,region,0,1); m.request(C,region,4,2);
        observe(m,A,10.5,3);
        require(m.request(C,region,4,3).allowed(),"Queued priorities must be adjustable without preempting a physical owner");
        m.remove(C); m.remove(A); m.remove(B);
        observe(m,A,1.5,10); m.request(A,region,0,10);
        for (int tick = 20; tick <= 400; tick += 20) {
            observe(m,A,tick % 40 == 0 ? 1.5 : 2.5,tick); m.request(A,region,0,tick);
            observe(m,B,11.5,tick); m.request(B,region,0,tick);
        }
        observe(m,C,13.5,400); m.request(C,region,5,400); observe(m,A,10.5,401);
        require(m.request(B,region,0,401).allowed(),"Aging must eventually beat later high-priority arrivals");
        m.expire(500); observe(m,C,13.5,500);
        require(m.request(C,region,0,500).allowed(),"Missing heartbeats must expire stale rights");

        PathReservationManager merged = new PathReservationManager();
        long first = merged.register(Set.of(new GridPos(0,1,0),new GridPos(1,1,0)),0);
        long second = merged.register(Set.of(new GridPos(3,1,0),new GridPos(4,1,0)),0);
        merged.register(Set.of(new GridPos(1,1,0),new GridPos(2,1,0),new GridPos(3,1,0)),1);
        require(first != second && merged.regionAt(new GridPos(0,1,0)) == merged.regionAt(new GridPos(4,1,0)),"Overlapping scans must merge the entire corridor into one reservation region");
    }
    private static void passages() {
        Set<GridPos> narrow = new HashSet<>();
        for (int x = 0; x < 300; x++) narrow.add(new GridPos(x,1,0));
        for (int z = 1; z < 12; z++) narrow.add(new GridPos(299,1,z));
        NarrowPassageScanner scanner = new NarrowPassageScanner(new GridPos(0,1,0));
        int rounds = 0;
        while (!scanner.advance(pos -> narrow.contains(pos) ? 2 : 1,8)) require(++rounds < 1000,"Incremental scan must finish");
        require(scanner.cells().equals(narrow) && rounds > 1,"Bent and long continuous corridors must remain a single region across budget slices");
    }
    private static void deadlocks() {
        DeadlockRecovery r = new DeadlockRecovery();
        for (int tick = 0; tick <= 400; tick++) {
            r.update(A,tick * 0.1,0,true,false,null,0,tick);
            require(r.update(B,0,0,true,true,A,0,tick) == DeadlockRecovery.Action.NONE,"Healthy queuing must never trigger a replan");
        }
        r = new DeadlockRecovery();
        r.update(A,0,0,true,true,B,1,0); r.update(B,0,0,true,true,C,0,0); r.update(C,0,0,true,true,A,0,0);
        require(C.equals(r.cycleVictim(A,0)),"A wait cycle must choose one deterministic lower-priority victim");
        require(r.update(A,0,0,true,true,B,1,40) == DeadlockRecovery.Action.NONE,"Non-victim must keep its place");
        require(r.update(B,0,0,true,true,C,0,40) == DeadlockRecovery.Action.NONE,"Only one NPC may yield");
        require(r.update(C,0,0,true,true,A,0,40) == DeadlockRecovery.Action.LOCAL,"First failure attempts a local detour");
        r.update(A,0,0,true,true,B,1,80); r.update(B,0,0,true,true,C,0,80);
        require(r.update(C,0,0,true,true,A,0,80) == DeadlockRecovery.Action.BACKOFF,"Persistent cycle temporarily backs off its victim");
        r = new DeadlockRecovery();
        int full = 0; long previous = -1000;
        for (int tick = 0; tick < 800; tick++) {
            var action = r.update(A,0,0,true,false,null,0,tick);
            if (tick < 160) require(action != DeadlockRecovery.Action.FULL_PATH,"No full search before multiple failed local recoveries");
            if (action == DeadlockRecovery.Action.FULL_PATH) {
                require(tick - previous >= 200,"Full paths must have at least a ten-second cooldown"); previous = tick; full++;
            }
        }
        require(full > 0 && full <= 4,"A persistent failure must eventually replan at a bounded rate");
    }
    private static void require(boolean valid, String message) { if (!valid) throw new AssertionError(message); }
}
