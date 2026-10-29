package io.github.apperk274.journeymen.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;

import java.util.List;

public enum StallPart implements StringRepresentable {
    COUNTER_LEFT("counter_left", -1, 0, 0),
    COUNTER_MIDDLE("counter_middle", 0, 0, 0),
    COUNTER_RIGHT("counter_right", 1, 0, 0),
    FRONT_POST_LEFT("front_post_left", -1, 1, 0),
    FRONT_POST_RIGHT("front_post_right", 1, 1, 0),
    BACK_POST_LEFT_LOW("back_post_left_low", -1, 0, 1),
    BACK_POST_LEFT_HIGH("back_post_left_high", -1, 1, 1),
    BACK_POST_RIGHT_LOW("back_post_right_low", 1, 0, 1),
    BACK_POST_RIGHT_HIGH("back_post_right_high", 1, 1, 1),
    AWNING_FRONT_LEFT("awning_front_left", -1, 2, 0),
    AWNING_FRONT_MIDDLE("awning_front_middle", 0, 2, 0),
    AWNING_FRONT_RIGHT("awning_front_right", 1, 2, 0),
    AWNING_BACK_LEFT("awning_back_left", -1, 2, 1),
    AWNING_BACK_MIDDLE("awning_back_middle", 0, 2, 1),
    AWNING_BACK_RIGHT("awning_back_right", 1, 2, 1);

    public static final List<int[]> FREE_SPACE = List.of(new int[]{0, 1, 0}, new int[]{0, 0, 1}, new int[]{0, 1, 1}, new int[]{0, 0, -1},
            new int[]{0, 1, -1});

    private final String name;
    private final int across;
    private final int up;
    private final int back;

    StallPart(String name, int across, int up, int back) {
        this.name = name;
        this.across = across;
        this.up = up;
        this.back = back;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    public boolean isCounter() {
        return this == COUNTER_LEFT || this == COUNTER_MIDDLE || this == COUNTER_RIGHT;
    }

    public boolean isAwning() {
        return name.startsWith("awning");
    }

    public BlockPos positionFrom(BlockPos origin, Direction facing) {
        return offset(origin, facing, across, up, back);
    }

    public BlockPos originFrom(BlockPos pos, Direction facing) {
        return offset(pos, facing, -across, -up, -back);
    }

    public static BlockPos offset(BlockPos origin, Direction facing, int across, int up, int back) {
        return origin.relative(facing.getClockWise(), across).above(up).relative(facing.getOpposite(), back);
    }

    public static BlockPos recruiterSpot(BlockPos origin, Direction facing) {
        return offset(origin, facing, 0, 0, 1);
    }
}
