package io.github.apperk274.journeymen.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

public final class AccessRamp {

    public static final int ORDER_STRIDE = 4096;

    private AccessRamp() {
    }

    public static Map<Long, int[]> columns(ServerLevel level, BuildPlan plan, @Nullable BlockPos reference) {
        Map<Long, int[]> columns = new HashMap<>();
        BoundingBox area = plan.getArea();
        if (reference == null) {
            return columns;
        }
        int gapX = gap(reference.getX(), area.minX(), area.maxX());
        int gapZ = gap(reference.getZ(), area.minZ(), area.maxZ());
        if (gapX == 0 && gapZ == 0) {
            return columns;
        }
        int x = Math.max(area.minX(), Math.min(area.maxX(), reference.getX()));
        int z = Math.max(area.minZ(), Math.min(area.maxZ(), reference.getZ()));
        boolean alongZ = Math.abs(gapX) >= Math.abs(gapZ);
        int stepX = alongZ ? 0 : (x - area.minX() <= area.maxX() - x ? 1 : -1);
        int stepZ = alongZ ? (z - area.minZ() <= area.maxZ() - z ? 1 : -1) : 0;
        int outsideX = alongZ ? x + Integer.signum(gapX) : x;
        int outsideZ = alongZ ? z : z + Integer.signum(gapZ);
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, outsideX, outsideZ);
        Map<BlockPos, BlockState> planned = plan.targetsByPosition();
        for (int i = 0; i <= area.getYSpan(); i++) {
            int columnX = x + stepX * i;
            int columnZ = z + stepZ * i;
            if (columnX < area.minX() || columnX > area.maxX() || columnZ < area.minZ() || columnZ > area.maxZ()) {
                break;
            }
            BlockPos step = new BlockPos(columnX, ground + i, columnZ);
            if (!isStep(level, planned, step)) {
                break;
            }
            columns.put(ChunkPos.asLong(columnX, columnZ), new int[]{i, step.getY() + 1});
        }
        return columns;
    }

    private static int gap(int value, int min, int max) {
        return value < min ? value - min : value > max ? value - max : 0;
    }

    private static boolean isStep(ServerLevel level, Map<BlockPos, BlockState> planned, BlockPos pos) {
        VoxelShape shape = planned.getOrDefault(pos, level.getBlockState(pos)).getCollisionShape(level, pos);
        return !shape.isEmpty() && shape.max(Direction.Axis.Y) <= 1.0;
    }
}
