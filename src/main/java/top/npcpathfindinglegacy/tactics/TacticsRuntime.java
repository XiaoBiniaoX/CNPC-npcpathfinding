package top.npcpathfindinglegacy.tactics;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.entity.EntityLiving;
import net.minecraft.util.math.Vec3d;
import noppes.npcs.entity.EntityNPCInterface;
import top.npcpathfindinglegacy.tactics.flow.FlowFieldService;
import top.npcpathfindinglegacy.tactics.group.AutoTactic;
import top.npcpathfindinglegacy.tactics.group.SquadService;

/** The tactic owns destinations; CNPC's original attack goals still own weapon use and cooldowns. */
public final class TacticsRuntime {
    private static boolean submitting;
    private static final TacticalDecisionBudget decisionBudget = new TacticalDecisionBudget();
    private TacticsRuntime() {}

    public static TacticsState state(EntityNPCInterface npc) {
        return npc instanceof ITacticsEntity ? ((ITacticsEntity)(npc)).npcpathfindinglegacy$tacticsState() : null;
    }
    public static boolean active(EntityNPCInterface npc) {
        TacticsState state = state(npc);
        return state != null && state.activeMovement && npc.ais.onAttack == 0 && npc.ais.movementType == 0
            && (state.active == TacticType.FORMATION || npc.getAttackTarget() != null && npc.getAttackTarget().isEntityAlive() && !npc.getAttackTarget().isDead)
            && npc.isEntityAlive() && !npc.isBurning() && !npc.isInteracting()
            && npc.ais instanceof ITacticsData && ((ITacticsData)(npc.ais)).npcpathfindinglegacy$getTactic() != 0
            && !npc.world.isRemote;
    }
    public static boolean redirects(EntityNPCInterface npc) { return !submitting && active(npc); }
    public static boolean formationMember(EntityNPCInterface npc) {
        if (!(npc.ais instanceof ITacticsData)) return false;
        int selected = ((ITacticsData)npc.ais).npcpathfindinglegacy$getTactic();
        TacticsState current = state(npc);
        return selected == TacticType.FORMATION.id()
            || selected == TacticType.AUTO.id() && current != null && current.active == TacticType.FORMATION;
    }
    /** Reissue only a route cancelled by a native Goal; pending asynchronous paths remain untouched. */
    public static void keepMoving(EntityNPCInterface npc) {
        TacticsState current = state(npc);
        if (active(npc) && current.moveTarget != null && npc.getNavigator().noPath()) {
            if (npc.getPositionVector().squareDistanceTo(top.npcpathfindinglegacy.pathfinding.GroundSurface.center(npc.world,current.moveTarget)) >= 0.65)
                move(npc,current.moveTarget,current.moveSpeed);
            else if (current.destination != null && npc.getPositionVector().squareDistanceTo(top.npcpathfindinglegacy.pathfinding.GroundSurface.center(npc.world,current.destination)) > 2)
                current.nextDecision = Math.min(current.nextDecision,npc.world.getTotalWorldTime());
        }
    }
    public static boolean formationFollower(EntityNPCInterface npc) {
        TacticsState state = state(npc);
        return active(npc) && state.active == TacticType.FORMATION && state.leader != null && !state.leader.equals(npc.getUniqueID());
    }
    public static void tick(EntityNPCInterface npc) {
        if (!(npc.world instanceof WorldServer) || !((WorldServer)(npc.world)).getMinecraftServer().isCallingFromMinecraftThread()) return;
        TacticsState state = state(npc);
        if (state == null || !(npc.ais instanceof ITacticsData)) return;
        net.minecraft.entity.EntityLivingBase target = npc.getAttackTarget();
        TacticType selected = TacticType.byId(((ITacticsData)(npc.ais)).npcpathfindinglegacy$getTactic());
        if (selected == TacticType.FORMATION && npc.ais.onAttack == 0 && npc.ais.movementType == 0
            && npc.isEntityAlive() && !npc.isBurning() && !npc.isInteracting()) {
            if (state.activeMovement && state.destination != null && npc.getNavigator().noPath()
                && npc.getPositionVector().squareDistanceTo(top.npcpathfindinglegacy.pathfinding.GroundSurface.center(npc.world,state.destination)) > 2) state.nextDecision = Math.min(state.nextDecision,((WorldServer)npc.world).getTotalWorldTime());
            if (((WorldServer)(npc.world)).getTotalWorldTime() >= state.nextDecision && permitDecision((WorldServer)npc.world,npc)) top.npcpathfindinglegacy.tactics.group.FormationController.tick(npc,(ITacticsData)npc.ais);
            return;
        }
        if (selected == TacticType.ASSAULT || npc.ais.onAttack != 0 || npc.ais.movementType != 0
            || !npc.isEntityAlive() || target == null || !target.isEntityAlive() || target.isDead || npc.isBurning() || npc.isInteracting()) {
            if (state.activeMovement) { npc.getNavigator().clearPath(); TacticalPositions.release(npc.getUniqueID()); }
            state.reset(); return;
        }
        if (!target.getUniqueID().equals(state.target)) {
            npc.getNavigator().clearPath(); state.reset(); TacticalPositions.release(npc.getUniqueID()); state.target = target.getUniqueID();
        }
        long now = ((WorldServer)(npc.world)).getTotalWorldTime();
        // Remember damage across the decision interval and hold the response long enough to reach the enemy.
        if (npc.hurtTime > 0) state.counterUntil = now + 60;
        if (state.activeMovement && state.destination != null && npc.getNavigator().noPath()
            && npc.getPositionVector().squareDistanceTo(top.npcpathfindinglegacy.pathfinding.GroundSurface.center(npc.world,state.destination)) > 2) state.nextDecision = Math.min(state.nextDecision,now);
        boolean swingStarted = target.isSwingInProgress && !state.enemySwinging;
        state.enemySwinging = target.isSwingInProgress;
        if (swingStarted && npc.getDistanceSq(target) < 64 || npc.hurtTime > 7) state.nextDecision = Math.min(state.nextDecision,now);
        if (now < state.nextDecision) return;
        if (!permitDecision((WorldServer)npc.world,npc)) return;
        state.nextDecision = now + (swingStarted ? 2 + npc.getRNG().nextInt(4) : 6 + npc.getRNG().nextInt(8));
        WeaponRole role = WeaponRole.classify(npc.inventory.getProjectile() != null, npc.stats.ranged.getMeleeRange());
        if (role == WeaponRole.HYBRID) {
            // Use a margin to keep a hybrid in melee until it has actually separated from the target.
            state.meleeEngaged = npc.isInRange(target,npc.stats.ranged.getMeleeRange() + (state.meleeEngaged ? 1.5 : 0));
            if (state.meleeEngaged) role = WeaponRole.MELEE;
        } else state.meleeEngaged = false;
        SquadService.Membership membership = selected.group() ? SquadService.membership(npc, ((ITacticsData)(npc.ais)).npcpathfindinglegacy$getSquad())
            : new SquadService.Membership(1, Math.floorMod(npc.getEntityId(), 2), npc.getUniqueID(), npc.getHealth() / Math.max(1, npc.getMaxHealth()), true, null, null,2,1);
        Vec3d toward = target.getPositionVector().subtract(npc.getPositionVector());
        double distance = top.npcpathfindinglegacy.compat.LegacyMath.horizontal(toward);
        double radialSpeed = top.npcpathfindinglegacy.compat.LegacyMath.motion(target).subtract(top.npcpathfindinglegacy.compat.LegacyMath.motion(npc)).dotProduct(toward.normalize());
        state.timing.update(now,target.isSwingInProgress && distance < 8,radialSpeed < -0.05,radialSpeed > 0.06,
            npc.getHealth() / Math.max(1,npc.getMaxHealth()),npc.getRNG()::nextInt);
        if (state.approach == null) state.approach = distance < 0.01 ? new Vec3d(1,0,0) : new Vec3d(-toward.x / distance,0,-toward.z / distance);
        Vec3d away = membership.approach() == null ? state.approach : membership.approach();
        Vec3d side = new Vec3d(-away.z,0,away.x);
        boolean narrow = !TacticalPositions.standable(npc, npc.getPosition().add((int)Math.round(side.x * 2),0,(int)Math.round(side.z * 2)))
            || !TacticalPositions.standable(npc, npc.getPosition().add((int)Math.round(-side.x * 2),0,(int)Math.round(-side.z * 2)));
        boolean rough = !TacticalPositions.standable(npc, npc.getPosition().add((int)Math.round(toward.x / Math.max(1,distance) * 3),0,
            (int)Math.round(toward.z / Math.max(1,distance) * 3)));
        boolean staged = (state.active == TacticType.FLANKING || state.active == TacticType.PINCER)
            && state.destination != null && npc.getPositionVector().squareDistanceTo(top.npcpathfindinglegacy.pathfinding.GroundSurface.center(npc.world,state.destination)) < 2;
        if (staged) state.engagedUntil = now + 65 + npc.getRNG().nextInt(61);
        TacticalContext context = new TacticalContext(role, npc.getHealth() / Math.max(1,npc.getMaxHealth()),
            target.getHealth() / Math.max(1,target.getMaxHealth()), now < state.engagedUntil ? Math.min(4,distance) : distance,
            npc.stats.ranged.getRange(), npc.stats.ranged.getMeleeRange(), npc.getEntitySenses().canSee(target), now < state.counterUntil,
            target instanceof EntityLiving && ((EntityLiving)(target)).getAttackTarget() == npc || target.getLastAttackedEntity() == npc,
            membership.members(), membership.slot(), now, npc.getEntityId(), membership.leaderHealth(), narrow, rough,
            state.timing.attackPhase,selected.group() ? ((membership.slot() & 1) == 0 ? 1 : -1) * membership.flankSide() : state.timing.side,state.timing.rangeScale);
        TacticType chosen = selected == TacticType.AUTO ? AutoTactic.choose(context) : selected;
        if (state.active != chosen && now < state.actionUntil && selected == TacticType.AUTO
            && context.health() > 0.25 && context.leaderHealth() > 0.25) chosen = state.active;
        if (state.active != chosen) state.actionUntil = now + 24 + npc.getRNG().nextInt(47);
        state.active = chosen; state.leader = membership.leader();
        if (chosen == TacticType.FORMATION) { top.npcpathfindinglegacy.tactics.group.FormationController.tick(npc,(ITacticsData)npc.ais); return; }
        Decision decision = TacticDecisions.decide(chosen, context);
        if (chosen == TacticType.FLANKING && decision.forward() < 0) decision = new Decision(decision.forward(),
            decision.lateral() * membership.flankSide(),decision.speed(),decision.cover(),decision.allowAttack());
        if (chosen == TacticType.HIT_AND_RUN || chosen == TacticType.KITING && context.melee() || chosen == TacticType.SKIRMISH) {
            // First make contact; a successful hit or shot starts the withdrawal phase.
            decision = state.timing.retreating(now)
                ? new Decision(context.preferredRange() + 6,context.side() * 2,1.35,false,!context.melee())
                : new Decision(context.preferredRange(),context.side() * context.attackLateral(2),1.2,false,true);
        }
        boolean ambush = chosen == TacticType.AMBUSH || chosen == TacticType.GROUP_AMBUSH;
        if (ambush) {
            if (state.ambushOrigin == null) state.ambushOrigin = npc.getPosition();
            if (distance <= 4 || now < state.counterUntil) state.ambushTriggered = true;
            if (chosen == TacticType.GROUP_AMBUSH && SquadService.ambushTriggered(npc,membership)) state.ambushTriggered = true;
            if (state.ambushTriggered) decision = new Decision(context.preferredRange(),context.side() * context.attackLateral(2),1.3,false,true);
            else decision = Decision.cover(false);
        }
        double radius = ((ITacticsData)(npc.ais)).npcpathfindinglegacy$getTacticRadius();
        double forward = Math.max(-radius, Math.min(radius, decision.forward()));
        double lateral = Math.max(-radius, Math.min(radius, decision.lateral()));
        if (forward > context.preferredRange() + 0.5 && distance > 0.01) {
            away = new Vec3d(-toward.x / distance,0,-toward.z / distance);
            side = new Vec3d(-away.z,0,away.x);
        }
        // Maneuvering attack positions must remain inside real weapon range, including diagonal offsets.
        if (!decision.cover() && decision.allowAttack() && forward >= 0 && forward <= context.preferredRange() + 0.5) {
            double weaponRange = context.melee() ? Math.max(npc.stats.melee.getRange(),npc.width * 2) * 0.8
                : Math.max(0.5,npc.stats.ranged.getRange() * 0.85);
            double length = Math.hypot(forward,lateral);
            if (length > weaponRange) { forward *= weaponRange / length; lateral *= weaponRange / length; }
        }
        Vec3d anchor = target.getPositionVector();
        Vec3d lead = top.npcpathfindinglegacy.compat.LegacyMath.multiply(top.npcpathfindinglegacy.compat.LegacyMath.motion(target),1,0,1).scale(state.timing.leadTicks);
        if (top.npcpathfindinglegacy.compat.LegacyMath.horizontal(lead) > 1.5) lead = lead.normalize().scale(1.5);
        if (!decision.cover() && TacticalPositions.standable(npc,new BlockPos(anchor.add(lead)))) anchor = anchor.add(lead);
        Vec3d desired = anchor.add(away.scale(forward)).add(side.scale(lateral));
        BlockPos destination;
        boolean allowAttack = decision.allowAttack();
        if (decision.cover()) {
            BlockPos cover = TacticalPositions.findCover(npc,state,(int)radius);
            if (cover == null) {
                // Open ground cannot supply concealment; keep searching while retaining a usable combat fallback.
                desired = ambush ? npc.getPositionVector() : target.getPositionVector().add(away.scale(context.preferredRange()));
                destination = TacticalPositions.resolve(npc,desired,false);
                allowAttack = !ambush;
            } else {
                destination = cover;
                boolean arrived = npc.getPositionVector().squareDistanceTo(top.npcpathfindinglegacy.pathfinding.GroundSurface.center(npc.world,cover)) < 2;
                if (!ambush && !context.melee() && state.timing.peek(now,arrived,npc.hurtTime > 0,npc.getRNG()::nextInt)) {
                    BlockPos peek = TacticalPositions.peek(npc,cover);
                    if (peek != null) { destination = peek; allowAttack = true; }
                }
            }
        } else {
            if (!context.visible() && !context.melee() && chosen != TacticType.FORMATION && !ambush
                && decision.allowAttack() && decision.forward() >= 0 && decision.forward() <= context.preferredRange() + 0.5) desired = target.getPositionVector();
            destination = TacticalPositions.resolve(npc,desired,false);
        }
        state.allowAttack = allowAttack; state.activeMovement = true; state.concealing = decision.cover() && state.coverDestination != null;
        state.destination = destination;
        // Shared coarse fields guide the approach; exact final slots and cover always use the selected navigator.
        BlockPos waypoint = npc.getPositionVector().squareDistanceTo(top.npcpathfindinglegacy.pathfinding.GroundSurface.center(npc.world,destination)) > 64
            ? FlowFieldService.waypoint(npc,destination,((ITacticsData)(npc.ais)).npcpathfindinglegacy$getSquad()) : destination;
        if (!TacticalPositions.standable(npc,waypoint)) waypoint = destination;
        TacticalPositions.release(npc.getUniqueID());
        TacticalPositions.reserve(npc,destination);
        if (decision.cover() && state.coverDestination != null) TacticalPositions.reserve(npc,state.coverDestination);
        if (!waypoint.equals(destination)) {
            waypoint = TacticalPositions.resolve(npc,top.npcpathfindinglegacy.pathfinding.GroundSurface.center(npc.world,waypoint),false);
            TacticalPositions.reserve(npc,waypoint);
        }
        move(npc,waypoint,decision.speed());
    }
    public static void move(EntityNPCInterface npc, BlockPos destination, double speed) {
        if (!(npc.world instanceof WorldServer) || !((WorldServer)(npc.world)).getMinecraftServer().isCallingFromMinecraftThread()) return;
        TacticsState current = state(npc);
        if (current != null) { current.moveTarget = destination; current.moveSpeed = speed; }
        submitting = true;
        try {
            if (npc.getPositionVector().squareDistanceTo(top.npcpathfindinglegacy.pathfinding.GroundSurface.center(npc.world,destination)) < 0.65) {
                npc.getNavigator().clearPath(); npc.setSprinting(false);
            } else npc.getNavigator().tryMoveToXYZ(destination.getX() + 0.5,destination.getY(),destination.getZ() + 0.5,speed);
        } finally { submitting = false; }
    }
    private static boolean permitDecision(WorldServer level, EntityNPCInterface npc) {
        return decisionBudget.acquire(level.getMinecraftServer(), level.getMinecraftServer().getTickCounter(), npc.getUniqueID());
    }
    public static void removed(EntityNPCInterface npc) {
        TacticalPositions.release(npc.getUniqueID()); TacticsState state = state(npc); if (state != null) state.reset();
    }
    public static boolean retainHiddenTarget(EntityNPCInterface npc, net.minecraft.entity.Entity target) {
        TacticsState state = state(npc);
        return active(npc) && state.concealing && target != null && target.getUniqueID().equals(state.target);
    }
    public static void attacked(EntityNPCInterface npc) {
        TacticsState state = state(npc);
        if (state == null || !active(npc)) return;
        long now = npc.world.getTotalWorldTime();
        state.timing.contact(now,npc.getHealth() / Math.max(1,npc.getMaxHealth()),npc.getRNG()::nextInt);
    }
}
