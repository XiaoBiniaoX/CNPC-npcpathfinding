package top.npcpathfindinglegacy.pathfinding;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import top.npcpathfindinglegacy.compat.LegacyCollision;
/** Snow remains a floor inside its cell; callers still check support and body collisions. */
public final class GroundSurface {
    private GroundSurface() {}
    public static double snowHeight(World level, BlockPos pos) {
        if (!level.isBlockLoaded(pos)) return -1;
        net.minecraft.block.state.IBlockState state=level.getBlockState(pos);
        if (state.getBlock()!=Blocks.SNOW_LAYER) return -1;
        return LegacyCollision.shape(state,level,pos,null).max(EnumFacing.Axis.Y);
    }
    public static double bodyY(World level, BlockPos pos) { return pos.getY()+Math.max(0,snowHeight(level,pos)); }
    public static net.minecraft.util.math.Vec3d center(net.minecraft.world.World level, BlockPos pos) { return new net.minecraft.util.math.Vec3d(pos.getX()+0.5,bodyY(level,pos),pos.getZ()+0.5); }
}
