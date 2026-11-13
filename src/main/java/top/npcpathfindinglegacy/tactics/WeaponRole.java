package top.npcpathfindinglegacy.tactics;

public enum WeaponRole {
    MELEE, RANGED, HYBRID;
    public static WeaponRole classify(boolean projectile, int meleeRange) {
        return !projectile ? MELEE : meleeRange > 0 ? HYBRID : RANGED;
    }
    public boolean ranged() { return this != MELEE; }
}
