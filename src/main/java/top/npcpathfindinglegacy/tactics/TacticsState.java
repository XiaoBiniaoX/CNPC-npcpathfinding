package top.npcpathfindinglegacy.tactics;

import java.util.UUID;
import net.minecraft.core.BlockPos;

/** Entity-owned state. No global entity registry and no retained target/entity references. */
public final class TacticsState {
    public long nextDecision, actionUntil;
    public UUID target, leader;
    public BlockPos destination, moveTarget;
    public double moveSpeed = 1;
    public BlockPos coverDestination, ambushOrigin;
    public net.minecraft.world.phys.Vec3 approach;
    public int coverCursor;
    public long nextCoverSearch, engagedUntil, counterUntil;
    public final TacticalTiming timing = new TacticalTiming();
    public boolean enemySwinging;
    public boolean meleeEngaged, ambushTriggered, concealing, formationReady;
    public TacticType active = TacticType.ASSAULT;
    public boolean activeMovement, allowAttack = true;
    public void reset() {
        target = null; leader = null; destination = null; moveTarget = null; moveSpeed = 1;
        coverDestination = null; ambushOrigin = null; approach = null; coverCursor = 0;
        nextCoverSearch = 0; engagedUntil = 0; counterUntil = 0; meleeEngaged = false; ambushTriggered = false;
        timing.reset(); enemySwinging = false;
        concealing = false; formationReady = false;
        activeMovement = false; allowAttack = true; nextDecision = 0; actionUntil = 0;
        active = TacticType.ASSAULT;
    }
}
