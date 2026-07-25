package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.process.IElytraProcess;
import baritone.api.process.PathingCommand;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.core.BlockPos;

public final class ElytraProcess extends BaritoneProcessHelper implements IElytraProcess {
    public ElytraProcess(Baritone baritone) {
        super(baritone);
    }

    public static ElytraProcess create(Baritone baritone) {
        return new ElytraProcess(baritone);
    }

    @Override public void repackChunks() {}
    @Override public BlockPos currentDestination() { return null; }
    @Override public void pathTo(BlockPos destination) {}
    @Override public void pathTo(Goal destination) {}
    @Override public void resetState() {}
    @Override public boolean isLoaded() { return false; }
    @Override public boolean isSafeToCancel() { return true; }
    @Override public boolean isActive() { return false; }
    @Override public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) { return null; }
    @Override public void onLostControl() {}
    @Override public String displayName0() { return "Elytra disabled"; }
}
