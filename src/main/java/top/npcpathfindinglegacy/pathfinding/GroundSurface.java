package top.npcpathfindinglegacy.pathfinding;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.shapes.VoxelShape;
/** Snow remains a floor inside its cell; callers still check support and body collisions. */
public final class GroundSurface {
    private GroundSurface() {}
    public static double snowHeight(Level level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return -1;
        net.minecraft.world.level.block.state.BlockState state=level.getBlockState(pos);
        if (!state.is(Blocks.SNOW)) return -1;
        VoxelShape shape=state.getCollisionShape(level,pos);
        return shape.isEmpty()?0:shape.max(Direction.Axis.Y);
    }
    public static double bodyY(Level level, BlockPos pos) { return pos.getY()+Math.max(0,snowHeight(level,pos)); }
    public static net.minecraft.world.phys.Vec3 center(net.minecraft.world.level.Level level, BlockPos pos) { return new net.minecraft.world.phys.Vec3(pos.getX()+0.5,bodyY(level,pos),pos.getZ()+0.5); }
}
