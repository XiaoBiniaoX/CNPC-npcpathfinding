package top.npcpathfindinglegacy.compat;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** Preserve numeric movement calculations at the legacy entity field boundary. */
public final class LegacyMath {
    private LegacyMath() {}
    public static Vec3d bottom(BlockPos pos) { return new Vec3d(pos.getX()+0.5,pos.getY(),pos.getZ()+0.5); }
    public static Vec3d motion(Entity entity) { return new Vec3d(entity.motionX,entity.motionY,entity.motionZ); }
    public static void motion(Entity entity,Vec3d value) { motion(entity,value.x,value.y,value.z); }
    public static void motion(Entity entity,double x,double y,double z) { entity.motionX=x;entity.motionY=y;entity.motionZ=z; }
    public static double horizontal(Vec3d value) { return Math.hypot(value.x,value.z); }
    public static Vec3d multiply(Vec3d value,double x,double y,double z) { return new Vec3d(value.x*x,value.y*y,value.z*z); }
    public static Vec3d lerp(Vec3d a,Vec3d b,double t) { return a.add(b.subtract(a).scale(t)); }
    public static BlockPos destination(net.minecraft.pathfinding.PathNavigate navigation) {
        if (navigation instanceof top.npcpathfindinglegacy.pathfinding.EnhancedPathNavigator)
            return ((top.npcpathfindinglegacy.pathfinding.EnhancedPathNavigator)navigation).getTargetPos();
        net.minecraft.pathfinding.Path path=navigation.getPath();
        if (path==null || path.getFinalPathPoint()==null) return null;
        net.minecraft.pathfinding.PathPoint end=path.getFinalPathPoint();return new BlockPos(end.x,end.y,end.z);
    }
}
