package top.npcpathfindinglegacy.compat;
import java.util.*;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.*;
import net.minecraft.world.World;
import net.minecraft.init.Blocks;
import top.npcpathfindinglegacy.NpcPathfindingConfig;

/** Server-thread collision sampling freezes only numeric geometry for worker algorithms. */
public final class LegacyCollision {
    private LegacyCollision() {}
    public static boolean loaded(World world,AxisAlignedBB body) {
        for (int x=(int)Math.floor(body.minX);x<=(int)Math.floor(body.maxX);x++)
            for (int z=(int)Math.floor(body.minZ);z<=(int)Math.floor(body.maxZ);z++)
                if (!world.isBlockLoaded(new BlockPos(x,(int)body.minY,z))) return false;
        return true;
    }
    public static List<AxisAlignedBB> blockBoxes(World world,AxisAlignedBB body,Entity entity) {
        List<AxisAlignedBB> boxes=new ArrayList<>();
        for (int x=(int)Math.floor(body.minX)-1;x<=(int)Math.floor(body.maxX)+1;x++)
            for (int y=Math.max(0,(int)Math.floor(body.minY)-1);y<=Math.min(255,(int)Math.floor(body.maxY)+1);y++)
                for (int z=(int)Math.floor(body.minZ)-1;z<=(int)Math.floor(body.maxZ)+1;z++) {
                    BlockPos pos=new BlockPos(x,y,z);if (!world.isBlockLoaded(pos)) continue;
                    world.getBlockState(pos).addCollisionBoxToList(world,pos,body,boxes,entity,false);
                }
        return boxes;
    }
    public static boolean safeSupport(World world,BlockPos pos) {
        if (!world.isBlockLoaded(pos)) return false;IBlockState state=world.getBlockState(pos);
        return !state.getMaterial().isLiquid() && state.isSideSolid(world,pos,EnumFacing.UP)
            && !NpcPathfindingConfig.isBlacklisted(state.getBlock()) && state.getBlock()!=Blocks.MAGMA && state.getBlock()!=Blocks.CACTUS;
    }
    public static Shape shape(IBlockState state,World world,BlockPos pos,Entity entity) {
        List<AxisAlignedBB> boxes=new ArrayList<>();
        state.addCollisionBoxToList(world,pos,new AxisAlignedBB(pos).grow(2),boxes,entity,false);
        AxisAlignedBB bounds=null;for (AxisAlignedBB box:boxes) { box=box.offset(-pos.getX(),-pos.getY(),-pos.getZ());bounds=bounds==null?box:bounds.union(box); }
        return new Shape(bounds);
    }
    public static final class Shape {
        private final AxisAlignedBB bounds;Shape(AxisAlignedBB bounds) { this.bounds=bounds; }
        public boolean isEmpty() { return bounds==null; }
        public AxisAlignedBB bounds() { return bounds; }
        public double max(EnumFacing.Axis axis) { return bounds==null?0:axis==EnumFacing.Axis.Y?bounds.maxY:axis==EnumFacing.Axis.X?bounds.maxX:bounds.maxZ; }
    }
}
