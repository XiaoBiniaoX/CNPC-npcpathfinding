package top.npcpathfindinglegacy.tactics;

/** Numeric input shared by small independent tactic modules. */
public record TacticalContext(WeaponRole role, double health, double enemyHealth, double distance,
    double range, int meleeRange, boolean visible, boolean recentHurt, boolean enemyFocused,
    int members, int slot, long time, int identity, double leaderHealth, boolean narrow, boolean rough,
    boolean attackPhase, double maneuverSide, double rangeScale) {
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
