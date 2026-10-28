package io.github.apperk274.journeymen.build;

import io.github.apperk274.journeymen.schematic.Schematic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

public record PlacementTransform(BlockPos origin, Vec3i base, Rotation rotation) {

    public static PlacementTransform of(Schematic schematic, BlockPos origin, Rotation rotation, boolean corner) {
        return new PlacementTransform(origin.immutable(), corner ? Vec3i.ZERO : schematic.getPasteOffset(), rotation);
    }

    public BlockPos toWorld(int x, int y, int z) {
        return origin.offset(new BlockPos(base.getX() + x, base.getY() + y, base.getZ() + z).rotate(rotation));
    }

    public BlockPos toSchematic(BlockPos world) {
        Rotation back = switch (rotation) {
            case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
            case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;
            default -> rotation;
        };
        return world.subtract(origin).rotate(back).subtract(base);
    }

    public BlockState transform(BlockState state) {
        return state.rotate(rotation);
    }

    public BoundingBox area(Vec3i size) {
        return BoundingBox.fromCorners(toWorld(0, 0, 0), toWorld(size.getX() - 1, size.getY() - 1, size.getZ() - 1));
    }
}
