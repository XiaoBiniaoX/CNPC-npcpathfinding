package top.npcpathfindinglegacy.traffic;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import top.npcpathfindinglegacy.pathfinding.GridPos;
import top.npcpathfindinglegacy.traffic.OrcaSolver.Agent;
import top.npcpathfindinglegacy.traffic.OrcaSolver.Velocity;

public final class TrafficSelfCheck {
    private static final UUID A = new UUID(0,2), B = new UUID(0,3), C = new UUID(0,1);

    public static void run() {
        avoidance(false); avoidance(true); passages(); reservations(); deadlocks();
        System.out.println("Traffic checks passed: ORCA, axis locking, corridor scan, FIFO, priority, ownership cleanup and timed recovery");
    }

    private static void avoidance(boolean crossing) {
        Agent a = agent(1,-4,0,Velocity.ZERO,0.3), b = agent(2,crossing ? 0 : 4,crossing ? -4 : 0,Velocity.ZERO,0.3);
        double min = Double.POSITIVE_INFINITY;
        for (int tick = 0; tick < 130; tick++) {
            Velocity av = OrcaSolver.solve(a,Collections.singletonList(b),new Velocity(0.18,0),0.18,false,false);
            Velocity bv = OrcaSolver.solve(b,Collections.singletonList(a),crossing ? new Velocity(0,0.18) : new Velocity(-0.18,0),0.18,false,false);
            require(av.length() <= 0.180001 && bv.length() <= 0.180001,"ORCA speed limit");
            a = agent(1,a.x + av.x,a.z + av.z,av,0.3);
            b = agent(2,b.x + bv.x,b.z + bv.z,bv,0.3);
            min = Math.min(min,Math.hypot(a.x - b.x,a.z - b.z));
        }
        require(min >= 0.599 && a.x > 2 && (crossing ? b.z > 2 : b.x < -2),"Bodies pass without overlap");
        for (double radius : new double[]{0.2,0.3,0.8,1.5}) {
            a = agent(1,0,0,Velocity.ZERO,radius);
            Velocity v = OrcaSolver.solve(a,Collections.singletonList(agent(2,1.5 * radius,0,Velocity.ZERO,radius)),new Velocity(0.2,0),0.2,false,true);
            require(v.x == 0 && v.z == 0,"Direct controller must brake without changing its axis");
        }
        Velocity preferred = new Velocity(0.2,0);
        require(OrcaSolver.solve(a,Collections.emptyList(),preferred,0.2,false,false).subtract(preferred).length() < 1.0E-9,
            "No steering in empty space");
    }

    private static Agent agent(long id,double x,double z,Velocity velocity,double radius) {
        return new Agent(id,x,z,velocity.x,velocity.z,radius,1.4,true);
    }
    private static void passages() {
        Set<GridPos> corridor = new HashSet<>();
        for (int x = 0; x < 300; x++) corridor.add(new GridPos(x,1,0));
        for (int z = 1; z < 12; z++) corridor.add(new GridPos(299,1,z));
        NarrowPassageScanner scanner = new NarrowPassageScanner(new GridPos(0,1,0));
        int rounds = 0;
        while (!scanner.advance(pos -> corridor.contains(pos) ? 2 : 1,8)) require(++rounds < 1000,"Incremental scan finishes");
        require(rounds > 1 && scanner.cells().equals(corridor),"Long, bent corridor is one region");
    }

    private static void observe(PathReservationManager manager,UUID id,double x,long tick) { manager.observe(id,x,1,0.5,0.3,tick); }
    private static void reservations() {
        Set<GridPos> corridor = new HashSet<>();
        for (int x = 0; x < 10; x++) corridor.add(new GridPos(x,1,0));
        PathReservationManager m = new PathReservationManager(); long region = m.register(corridor,0);
        observe(m,A,0.5,0); observe(m,B,11.5,0); observe(m,C,13.5,0);
        require(!m.request(B,region,0,0).allowed,"Physical occupant has precedence");
        require(m.request(A,region,0,0).allowed,"A owns the entire corridor");
        require(!m.request(C,region,0,0).allowed,"Same-tick arrival C waits behind B regardless of UUID");
        observe(m,A,10.1,20);
        require(!m.request(B,region,0,20).allowed,"Partial body at exit still holds the passage");
        observe(m,A,10.5,21);
        require(!m.request(C,region,0,21).allowed && m.request(B,region,0,21).allowed,"B gets the next turn");
        observe(m,B,9.5,22); m.request(B,region,0,22);
        observe(m,B,-0.5,30);
        require(m.request(C,region,0,30).allowed,"C follows once B is clear");

        m = new PathReservationManager(); region = m.register(corridor,0);
        observe(m,A,1.5,0); m.request(A,region,0,0);
        for (int tick = 20; tick <= 140; tick += 20) {
            observe(m,A,1.5,tick); observe(m,B,11.5,tick);
            require(!m.request(B,region,0,tick).allowed,"Timeout cannot grant through an immobile body");
            if (tick < 140) m.request(A,region,0,tick);
        }
        require(m.request(A,region,0,140).retreat,"Stalled owner is told to retreat");
        m.cancel(A);
        require(!m.request(B,region,0,141).allowed,"Cancellation releases rights, not physical presence");
        m.remove(A);
        require(m.request(B,region,0,142).allowed,"Death/removal releases occupancy immediately");

        m = new PathReservationManager(); region = m.register(corridor,0);
        observe(m,A,1.5,0); observe(m,B,11.5,0); observe(m,C,13.5,0);
        m.request(A,region,0,0); m.request(B,region,0,0); m.request(C,region,0,0);
        require(!m.request(C,region,4,1).allowed,"Priority update does not evict a current owner");
        observe(m,A,10.5,2);
        require(m.request(C,region,4,2).allowed,"Queued priority selects the next owner");
        m.expire(60); observe(m,B,11.5,60);
        require(m.request(B,region,0,60).allowed,"Missing heartbeats expire stale requests");

        m = new PathReservationManager(); region = m.register(corridor,0);
        observe(m,A,0.5,0); m.request(A,region,0,0);
        for (int tick = 0; tick <= 120; tick += 20) {
            observe(m,A,0.5 + tick / 20, tick); m.request(A,region,0,tick);
            observe(m,B,11.5,tick); m.request(B,region,0,tick);
        }
        observe(m,A,10.5,121); observe(m,C,13.5,121);
        require(!m.request(C,region,2,121).allowed && m.request(B,region,0,121).allowed,
            "Waiting long enough outweighs newly arrived priority");
    }

    private static void deadlocks() {
        DeadlockRecovery recovery = new DeadlockRecovery();
        for (int tick = 0; tick < 400; tick++) {
            recovery.update(A,tick * 0.1,0,true,false,null,0,tick);
            require(recovery.update(B,0,0,true,true,A,0,tick) == DeadlockRecovery.Action.NONE,"Healthy queue does not replan");
        }
        recovery = new DeadlockRecovery();
        recovery.update(A,0,0,true,true,B,1,0);
        recovery.update(B,0,0,true,true,C,1,0);
        recovery.update(C,0,0,true,true,A,0,0);
        require(C.equals(recovery.cycleVictim(A,0)),"One lower-priority NPC yields in a wait cycle");
        require(recovery.update(C,0,0,true,true,A,0,40) == DeadlockRecovery.Action.LOCAL,"First recovery is local");
        recovery.update(A,0,0,true,true,B,1,80);
        recovery.update(B,0,0,true,true,C,1,80);
        require(recovery.update(C,0,0,true,true,A,0,80) == DeadlockRecovery.Action.BACKOFF,"Persistent cycle backs off");
        recovery = new DeadlockRecovery();
        recovery.update(A,0,0,true,false,null,0,0);
        require(recovery.update(A,0,0,true,false,null,0,40) == DeadlockRecovery.Action.LOCAL,"Local search before backing off");
        recovery.reposition(A,2,0,60);
        require(recovery.update(A,2,0,true,false,null,0,100) == DeadlockRecovery.Action.BACKOFF,
            "Recovery displacement alone does not reset escalation");
        recovery = new DeadlockRecovery();
        long full = -1000;
        for (int tick = 0; tick < 800; tick++) {
            if (recovery.update(A,0,0,true,false,null,0,tick) == DeadlockRecovery.Action.FULL_PATH) {
                require(tick >= 160 && tick - full >= 200,"Full searches need repeated failures and cooldown");
                full = tick;
            }
        }
        require(full > 0,"Persistent failure eventually triggers a full path");
    }

    private static void require(boolean valid,String message) { if (!valid) throw new AssertionError(message); }
}
