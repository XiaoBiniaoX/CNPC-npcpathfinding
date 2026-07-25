package top.npcpathfinding.baritone;

import baritone.api.IBaritone;
import baritone.api.behavior.ILookBehavior;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.cache.IWorldProvider;
import baritone.api.command.manager.ICommandManager;
import baritone.api.event.listener.IEventBus;
import baritone.api.pathing.calc.IPathingControlManager;
import baritone.api.process.*;
import baritone.api.selection.ISelectionManager;
import baritone.api.utils.IInputOverrideHandler;
import baritone.api.utils.IPlayerContext;
import net.minecraft.world.entity.Mob;

public final class NpcBaritoneAdapter implements IBaritone {
    private final Mob mob;

    public NpcBaritoneAdapter(Mob mob) {
        this.mob = mob;
    }

    public Mob mob() {
        return mob;
    }

    @Override public IPathingBehavior getPathingBehavior() { return null; }
    @Override public ILookBehavior getLookBehavior() { return null; }
    @Override public IFollowProcess getFollowProcess() { return null; }
    @Override public IMineProcess getMineProcess() { return null; }
    @Override public IBuilderProcess getBuilderProcess() { return null; }
    @Override public IExploreProcess getExploreProcess() { return null; }
    @Override public IFarmProcess getFarmProcess() { return null; }
    @Override public ICustomGoalProcess getCustomGoalProcess() { return null; }
    @Override public IGetToBlockProcess getGetToBlockProcess() { return null; }
    @Override public IElytraProcess getElytraProcess() { return null; }
    @Override public IWorldProvider getWorldProvider() { return null; }
    @Override public IPathingControlManager getPathingControlManager() { return null; }
    @Override public IInputOverrideHandler getInputOverrideHandler() { return null; }
    @Override public IPlayerContext getPlayerContext() { return null; }
    @Override public IEventBus getGameEventHandler() { return null; }
    @Override public ISelectionManager getSelectionManager() { return null; }
    @Override public ICommandManager getCommandManager() { return null; }
    @Override public void openClick() {}
}
