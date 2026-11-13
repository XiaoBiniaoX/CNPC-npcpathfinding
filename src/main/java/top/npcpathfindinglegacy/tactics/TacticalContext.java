package top.npcpathfindinglegacy.tactics;

/** Numeric input shared by small independent tactic modules. */
public final class TacticalContext {
private final WeaponRole role;
private final double health;
private final double enemyHealth;
private final double distance;
private final double range;
private final int meleeRange;
private final boolean visible;
private final boolean recentHurt;
private final boolean enemyFocused;
private final int members;
private final int slot;
private final long time;
private final int identity;
private final double leaderHealth;
private final boolean narrow;
private final boolean rough;
private final boolean attackPhase;
private final double maneuverSide;
private final double rangeScale;
public TacticalContext(WeaponRole role, double health, double enemyHealth, double distance, double range, int meleeRange, boolean visible, boolean recentHurt, boolean enemyFocused, int members, int slot, long time, int identity, double leaderHealth, boolean narrow, boolean rough, boolean attackPhase, double maneuverSide, double rangeScale) {
this.role=role;this.health=health;this.enemyHealth=enemyHealth;this.distance=distance;this.range=range;this.meleeRange=meleeRange;this.visible=visible;this.recentHurt=recentHurt;this.enemyFocused=enemyFocused;this.members=members;this.slot=slot;this.time=time;this.identity=identity;this.leaderHealth=leaderHealth;this.narrow=narrow;this.rough=rough;this.attackPhase=attackPhase;this.maneuverSide=maneuverSide;this.rangeScale=rangeScale;}
public WeaponRole role() { return role; }
public double health() { return health; }
public double enemyHealth() { return enemyHealth; }
public double distance() { return distance; }
public double range() { return range; }
public int meleeRange() { return meleeRange; }
public boolean visible() { return visible; }
public boolean recentHurt() { return recentHurt; }
public boolean enemyFocused() { return enemyFocused; }
public int members() { return members; }
public int slot() { return slot; }
public long time() { return time; }
public int identity() { return identity; }
public double leaderHealth() { return leaderHealth; }
public boolean narrow() { return narrow; }
public boolean rough() { return rough; }
public boolean attackPhase() { return attackPhase; }
public double maneuverSide() { return maneuverSide; }
public double rangeScale() { return rangeScale; }
@Override public int hashCode() { return java.util.Objects.hash(role,health,enemyHealth,distance,range,meleeRange,visible,recentHurt,enemyFocused,members,slot,time,identity,leaderHealth,narrow,rough,attackPhase,maneuverSide,rangeScale); }
@Override public boolean equals(Object value) { if (this==value) return true; if (!(value instanceof TacticalContext)) return false; TacticalContext other=(TacticalContext)value; return java.util.Objects.equals(role,other.role) && java.util.Objects.equals(health,other.health) && java.util.Objects.equals(enemyHealth,other.enemyHealth) && java.util.Objects.equals(distance,other.distance) && java.util.Objects.equals(range,other.range) && java.util.Objects.equals(meleeRange,other.meleeRange) && java.util.Objects.equals(visible,other.visible) && java.util.Objects.equals(recentHurt,other.recentHurt) && java.util.Objects.equals(enemyFocused,other.enemyFocused) && java.util.Objects.equals(members,other.members) && java.util.Objects.equals(slot,other.slot) && java.util.Objects.equals(time,other.time) && java.util.Objects.equals(identity,other.identity) && java.util.Objects.equals(leaderHealth,other.leaderHealth) && java.util.Objects.equals(narrow,other.narrow) && java.util.Objects.equals(rough,other.rough) && java.util.Objects.equals(attackPhase,other.attackPhase) && java.util.Objects.equals(maneuverSide,other.maneuverSide) && java.util.Objects.equals(rangeScale,other.rangeScale); }

    public TacticalContext(WeaponRole role, double health, double enemyHealth, double distance,
        double range, int meleeRange, boolean visible, boolean recentHurt, boolean enemyFocused,
        int members, int slot, long time, int identity, double leaderHealth, boolean narrow, boolean rough) {
        this(role,health,enemyHealth,distance,range,meleeRange,visible,recentHurt,enemyFocused,members,slot,time,identity,
            leaderHealth,narrow,rough,true,(slot & 1) == 0 ? 1 : -1,1);
    }
    public double side() { return maneuverSide; }
    public double preferredRange() {
        if (melee()) return 1.2;
        return Math.max(1, Math.min(Math.max(1, range - 1), Math.max(3, Math.min(18, range * 0.6)) * rangeScale));
    }
    public boolean melee() { return role == WeaponRole.MELEE || role == WeaponRole.HYBRID && distance <= meleeRange; }
    public double attackLateral(double amount) { return melee() ? Math.min(0.7, amount) : amount; }
}
