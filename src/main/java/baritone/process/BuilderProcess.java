package baritone.process;

import baritone.Baritone;
import baritone.api.process.IBuilderProcess;
import baritone.api.process.PathingCommand;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.schematic.ISchematic;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.state.BlockState;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public final class BuilderProcess extends BaritoneProcessHelper implements IBuilderProcess {
    public BuilderProcess(Baritone baritone) {
        super(baritone);
    }

    @Override public void build(String name, ISchematic schematic, Vec3i origin) {}
    @Override public boolean build(String name, File schematic, Vec3i origin) { return false; }
    @Override public void buildOpenSchematic() {}
    @Override public void buildOpenLitematic(int i) {}
    @Override public void pause() {}
    @Override public boolean isPaused() { return false; }
    @Override public void resume() {}
    @Override public void clearArea(BlockPos corner1, BlockPos corner2) {}
    @Override public List<BlockState> getApproxPlaceable() { return Collections.emptyList(); }
    @Override public Optional<Integer> getMinLayer() { return Optional.empty(); }
    @Override public Optional<Integer> getMaxLayer() { return Optional.empty(); }
    public BlockState placeAt(int x, int y, int z, BlockState current) { return null; }
    public boolean placementPlausible(BlockPos pos, BlockState state) { return false; }
    @Override public boolean isActive() { return false; }
    @Override public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) { return null; }
    @Override public void onLostControl() {}
    @Override public String displayName0() { return "Builder disabled"; }

    public static final class GoalBreak extends GoalBlock {
        public GoalBreak(BlockPos pos) {
            super(pos);
        }
    }
}
