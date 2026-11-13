package top.npcpathfindinglegacy.tactics;

import java.util.function.IntUnaryOperator;

/** Entity-owned event timing; randomness is supplied on the server thread and can be seeded in checks. */
public final class TacticalTiming {
    private long maneuverUntil, retreatUntil, nextCycle, peekUntil, nextPeek;
    private boolean threatened;
    public boolean attackPhase = true;
    public double side = 1, rangeScale = 1, leadTicks = 2;

    public void update(long now, boolean attackIncoming, boolean closing, boolean fleeing, double health, IntUnaryOperator random) {
        if (now >= maneuverUntil || attackIncoming && !threatened) {
            side = random.applyAsInt(2) == 0 ? -1 : 1;
            rangeScale = 0.85 + random.applyAsInt(26) / 100.0;
            leadTicks = 1 + random.applyAsInt(4);
            attackPhase = !attackIncoming || health > 0.7 && random.applyAsInt(3) == 0;
            maneuverUntil = now + (attackIncoming || closing ? 12 + random.applyAsInt(18) : 22 + random.applyAsInt(34));
        }
        if (fleeing && health > 0.5) retreatUntil = Math.min(retreatUntil,now);
        threatened = attackIncoming;
    }
    public void contact(long now, double health, IntUnaryOperator random) {
        if (now < nextCycle) return;
        retreatUntil = now + 18 + random.applyAsInt(43) + (health < 0.4 ? 16 : 0);
        nextCycle = retreatUntil + 25 + random.applyAsInt(71);
    }
    public boolean retreating(long now) { return now < retreatUntil; }
    public boolean peek(long now, boolean arrived, boolean hurt, IntUnaryOperator random) {
        if (hurt) { peekUntil = now; nextPeek = now + 25 + random.applyAsInt(41); return false; }
        if (arrived && now >= nextPeek) {
            peekUntil = now + 16 + random.applyAsInt(22);
            nextPeek = peekUntil + 25 + random.applyAsInt(41);
        }
        return now < peekUntil;
    }
    public void reset() {
        maneuverUntil = retreatUntil = nextCycle = peekUntil = nextPeek = 0;
        threatened = false; attackPhase = true; side = rangeScale = 1; leadTicks = 2;
    }
}
