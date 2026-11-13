package top.npcpathfindinglegacy.pathfinding;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;
import net.minecraft.init.Blocks;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.Vec3d;
import top.npcpathfindinglegacy.compat.LegacyCollision;
import top.npcpathfindinglegacy.compat.LegacyCollision.Shape;
import noppes.npcs.entity.EntityNPCInterface;
import top.npcpathfindinglegacy.NpcPathfindingConfig;

/** All world queries live here and must run on the logical server thread. */
final class TerrainSampler {
    private final EntityNPCInterface npc;
    private final World level;

    TerrainSampler(EntityNPCInterface npc, World level) { this.npc = npc; this.level = level; }

    boolean loaded(BlockPos pos) {
        return pos != null && pos.getY() >= 0
            && pos.getY() < 256 && level.isBlockLoaded(pos);
    }

    int classify(BlockPos pos) {
        if (!loaded(pos)) return 0;
        IBlockState state = level.getBlockState(pos);
        if (NpcPathfindingConfig.isBlacklisted(state.getBlock())
            || state.getMaterial() == net.minecraft.block.material.Material.LAVA
            || state.getBlock() == Blocks.FIRE || state.getBlock() == Blocks.CACTUS || state.getBlock() == Blocks.MAGMA
            ) return 0;
        boolean ladder = state.getBlock().isLadder(state,level,pos,npc);
        boolean water = state.getMaterial() == net.minecraft.block.material.Material.WATER;
        Shape shape = LegacyCollision.shape(state,level,pos,npc);
        int flags = ladder ? TerrainSnapshot.LADDER : 0;
        if (water) {
            if (npc.ais != null && npc.ais.canSwim) flags |= TerrainSnapshot.BODY | TerrainSnapshot.WATER;
            return flags;
        }
        boolean snow = GroundSurface.snowHeight(level,pos) >= 0;
        if (shape.isEmpty() || snow || ladder || interactableDoor(state)
            || NpcPathfindingConfig.isWhitelisted(state.getBlock())) flags |= TerrainSnapshot.BODY;
        if (!shape.isEmpty() && !snow) {
            AxisAlignedBB bounds = shape.bounds();
            if (bounds.maxY >= 0.4 && bounds.maxY <= 1.01) flags |= TerrainSnapshot.SUPPORT;
            if (!ladder) {
                if (state.isSideSolid(level, pos, EnumFacing.NORTH)) flags |= TerrainSnapshot.WALL_NORTH;
                if (state.isSideSolid(level, pos, EnumFacing.SOUTH)) flags |= TerrainSnapshot.WALL_SOUTH;
                if (state.isSideSolid(level, pos, EnumFacing.WEST)) flags |= TerrainSnapshot.WALL_WEST;
                if (state.isSideSolid(level, pos, EnumFacing.EAST)) flags |= TerrainSnapshot.WALL_EAST;
            }
        }
        return flags;
    }

    boolean bodyClear(BlockPos feet) {
        int height = bodyHeight(), radius = bodyRadius();
        double floor=GroundSurface.bodyY(level,feet);
        for (int y = 0; y < height; y++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos cell=feet.add(dx,y,dz);
                    double snow=GroundSurface.snowHeight(level,cell);
                    if ((classify(cell)&TerrainSnapshot.BODY)==0 || snow>0 && cell.getY()+snow>floor+1.0E-6) return false;
                }
            }
        }
        double snow = GroundSurface.snowHeight(level,feet);
        if (snow > 0) {
            double base = feet.getY()+snow;
            int top = (int)Math.floor(base+npc.height-1.0E-6);
            for (int y=feet.getY(); y<=top; y++)
                for (int dx=-radius; dx<=radius; dx++) for (int dz=-radius; dz<=radius; dz++) {
                    BlockPos pos=feet.add(dx,y-feet.getY(),dz);
                    if ((classify(pos)&TerrainSnapshot.BODY)==0
                        || GroundSurface.snowHeight(level,pos)>0 && pos.getY()+GroundSurface.snowHeight(level,pos)>base+1.0E-6) return false;
                }
        }
        return true;
    }

    boolean standable(BlockPos feet, boolean ladders) {
        if (!bodyClear(feet)) return false;
        int flags = classify(feet), support = classify(feet.down());
        return (support & TerrainSnapshot.SUPPORT) != 0
            || (flags & TerrainSnapshot.WATER) != 0
            || (ladders && ((flags | support) & TerrainSnapshot.LADDER) != 0);
    }

    boolean ladder(BlockPos feet) {
        return ((classify(feet) | classify(feet.down())) & TerrainSnapshot.LADDER) != 0;
    }

    double standY(BlockPos feet) {
        if (GroundSurface.snowHeight(level,feet) >= 0) return GroundSurface.bodyY(level,feet);
        BlockPos support = feet.down();
        if (!loaded(support) || (classify(support) & TerrainSnapshot.SUPPORT) == 0) return feet.getY();
        Shape shape = LegacyCollision.shape(level.getBlockState(support),level,support,npc);
        return shape.isEmpty() ? feet.getY() : support.getY() + shape.max(EnumFacing.Axis.Y);
    }

    BlockPos feetPosition() {
        return feetPosition(npc);
    }

    BlockPos feetPosition(Entity entity) {
        double actualY = entity.getEntityBoundingBox().minY;
        BlockPos pos = new BlockPos(entity.posX, actualY + 0.05, entity.posZ);
        if (entity.onGround && (classify(pos) & TerrainSnapshot.SUPPORT) != 0) {
            pos = pos.up();
        }
        // The body can still be supported by a pad when its center is just beyond that pad's edge.
        if (entity.onGround && !standable(pos, true)) {
            BlockPos best = pos;
            double limit = Double.MAX_VALUE;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos side = pos.add(dx, 0, dz);
                    if (!standable(side, true) || Math.abs(standY(side) - actualY) > 0.1) continue;
                    AxisAlignedBB body = entity.getEntityBoundingBox();
                    if (body.maxX <= side.getX() || body.minX >= side.getX() + 1
                        || body.maxZ <= side.getZ() || body.minZ >= side.getZ() + 1) continue;
                    double x = side.getX() + 0.5 - entity.posX, z = side.getZ() + 0.5 - entity.posZ;
                    double distance = x * x + z * z;
                    if (distance < limit) { limit = distance; best = side; }
                }
            }
            pos = best;
        }
        return pos;
    }

    private boolean interactableDoor(IBlockState state) {
        return state.getBlock() instanceof net.minecraft.block.BlockDoor && state.getMaterial() == net.minecraft.block.material.Material.WOOD && npc.ais != null
            && (npc.ais.doorInteract == 0 || npc.ais.doorInteract == 1);
    }

    boolean interactableDoor(BlockPos pos) {
        return loaded(pos) && interactableDoor(level.getBlockState(pos))
            && !level.getBlockState(pos).getValue(net.minecraft.block.BlockDoor.OPEN);
    }

    EnumFacing wallDirection(BlockPos feet) {
        EnumFacing[] directions = {EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST};
        int[] faces = {TerrainSnapshot.WALL_SOUTH, TerrainSnapshot.WALL_NORTH,
                       TerrainSnapshot.WALL_EAST, TerrainSnapshot.WALL_WEST};
        for (int i = 0; i < directions.length; i++) {
            BlockPos side = feet.offset(directions[i]);
            if (((classify(side) | classify(side.up())) & faces[i]) != 0) return directions[i];
        }
        return null;
    }

    BlockPos resolveTarget(BlockPos requested, boolean wallMode) {
        if (!loaded(requested) || standable(requested, !wallMode)) return requested;
        BlockPos best = null;
        double score = Double.MAX_VALUE;
        for (int radius = 0; radius <= 2; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (radius > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    for (int dy = -3; dy <= 3; dy++) {
                        BlockPos candidate = requested.add(dx, dy, dz);
                        if (!standable(candidate, !wallMode)) continue;
                        double cost = candidate.distanceSq(requested) + Math.abs(dy) * 0.35;
                        if (cost < score) { score = cost; best = candidate; }
                    }
                }
            }
            if (best != null) return best;
        }
        return requested;
    }

    int bodyHeight() { return Math.max(1, Math.min(8, (int) Math.ceil(npc.height))); }
    int bodyRadius() { return Math.max(0, Math.min(4, (int) Math.ceil((npc.width - 1) / 2.0))); }

    boolean groundLineClear(Vec3d from, Vec3d to) {
        if (Math.abs(from.y - to.y) > 0.05) return false;
        int samples = Math.max(1, (int) Math.ceil(from.distanceTo(to) * 5));
        for (int i = 0; i <= samples; i++) {
            Vec3d point = top.npcpathfindinglegacy.compat.LegacyMath.lerp(from,to, i / (double) samples);
            BlockPos feet = new BlockPos(point.x, point.y + 0.05, point.z);
            if (!standable(feet, false) || Math.abs(standY(feet) - point.y) > 0.05) return false;
            AxisAlignedBB body = new AxisAlignedBB(point.x - npc.width / 2, point.y + 1.0E-5,
                point.z - npc.width / 2, point.x + npc.width / 2,
                point.y + npc.height, point.z + npc.width / 2);
            for (int x = (int) Math.floor(body.minX); x <= (int) Math.floor(body.maxX); x++) {
                for (int z = (int) Math.floor(body.minZ); z <= (int) Math.floor(body.maxZ); z++) {
                    if (!loaded(new BlockPos(x, feet.getY(), z))) return false;
                }
            }
            if (!level.getCollisionBoxes(npc,body).isEmpty()) return false;
        }
        return true;
    }

    Builder begin(BlockPos start, BlockPos goal) { return new Builder(start, goal); }

    final class Builder {
        private final GridPos minimum;
        private final int sizeX, sizeY, sizeZ, height, radius;
        private final boolean swimming;
        private final byte[] blocks;
        private final byte[] sightBlocked;
        private final short[] supportHeights;
        private final double width, actualHeight;
        private int cursor;

        Builder(BlockPos start, BlockPos goal) {
            // ponytail: local snapshots reach 32 blocks/24 vertical; partial routes extend long trips.
            int minX = Math.max(start.getX() - 32, Math.min(start.getX(), goal.getX()) - 8);
            int maxX = Math.min(start.getX() + 32, Math.max(start.getX(), goal.getX()) + 8);
            int minZ = Math.max(start.getZ() - 32, Math.min(start.getZ(), goal.getZ()) - 8);
            int maxZ = Math.min(start.getZ() + 32, Math.max(start.getZ(), goal.getZ()) + 8);
            int minY = Math.max(0, Math.max(start.getY() - 26,
                Math.min(start.getY(), goal.getY()) - NpcPathfindingConfig.maxDropHeight - 2));
            int maxY = Math.min(256 - 1, Math.min(start.getY() + 30,
                Math.max(start.getY(), goal.getY()) + 5 + bodyHeight()));
            minimum = new GridPos(minX, minY, minZ);
            sizeX = maxX - minX + 1; sizeY = Math.max(1, maxY - minY + 1); sizeZ = maxZ - minZ + 1;
            height = bodyHeight(); radius = bodyRadius(); swimming = npc.ais != null && npc.ais.canSwim;
            blocks = new byte[sizeX * sizeY * sizeZ];
            width = npc.width; actualHeight = npc.height;
            int mode = ((IDataAIPathfinding) npc.ais).npcpathfindinglegacy$getMode();
            sightBlocked = mode == 4 || mode == 5 ? new byte[blocks.length] : null;
            supportHeights = new short[blocks.length];
        }

        boolean capture(int count) {
            int end = Math.min(blocks.length, cursor + count);
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            while (cursor < end) {
                int x = cursor % sizeX, z = cursor / sizeX % sizeZ, y = cursor / (sizeX * sizeZ);
                pos.setPos(minimum.x() + x, minimum.y() + y, minimum.z() + z);
                blocks[cursor] = (byte) classify(pos);
                if (sightBlocked != null) sightBlocked[cursor] = (byte) (!loaded(pos)
                    || !LegacyCollision.shape(level.getBlockState(pos),level,pos,npc).isEmpty() ? 1 : 0);
                double snow = GroundSurface.snowHeight(level,pos);
                // Negative entries encode a partial floor within this BODY cell (including zero-height snow).
                if (snow >= 0) supportHeights[cursor] = (short)(-1-Math.round(snow*1000));
                if (supportHeights != null && (blocks[cursor] & TerrainSnapshot.SUPPORT) != 0) {
                    supportHeights[cursor] = (short) Math.round(LegacyCollision.shape(level.getBlockState(pos),level,pos,npc).max(EnumFacing.Axis.Y) * 1000);
                }
                cursor++;
            }
            return cursor == blocks.length;
        }

        TerrainSnapshot freeze() {
            if (cursor != blocks.length) throw new IllegalStateException("Incomplete snapshot");
            return new TerrainSnapshot(minimum, sizeX, sizeY, sizeZ, height, radius, swimming, blocks,
                width, actualHeight, sightBlocked, supportHeights);
        }
    }
}
