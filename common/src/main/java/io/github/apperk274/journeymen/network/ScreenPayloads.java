package io.github.apperk274.journeymen.network;

import io.github.apperk274.journeymen.Constants;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public final class ScreenPayloads {

    private ScreenPayloads() {
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String name) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, name));
    }

    private static final StreamCodec<RegistryFriendlyByteBuf, List<Component>> COMPONENTS =
            ComponentSerialization.STREAM_CODEC.apply(ByteBufCodecs.list());
    private static final StreamCodec<ByteBuf, BlockState> BLOCK_STATE = ByteBufCodecs.idMapper(Block.BLOCK_STATE_REGISTRY);

    public record ChestEntry(BlockPos pos, boolean supply) {

        static void write(RegistryFriendlyByteBuf buf, List<ChestEntry> chests) {
            buf.writeVarInt(chests.size());
            for (ChestEntry chest : chests) {
                buf.writeBlockPos(chest.pos);
                buf.writeBoolean(chest.supply);
            }
        }

        static List<ChestEntry> read(RegistryFriendlyByteBuf buf) {
            int size = buf.readVarInt();
            List<ChestEntry> chests = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                chests.add(new ChestEntry(buf.readBlockPos(), buf.readBoolean()));
            }
            return chests;
        }
    }

    public record CrewEntry(String name, String doing, boolean available) {

        static void write(RegistryFriendlyByteBuf buf, List<CrewEntry> crew) {
            buf.writeVarInt(crew.size());
            for (CrewEntry entry : crew) {
                buf.writeUtf(entry.name);
                buf.writeUtf(entry.doing);
                buf.writeBoolean(entry.available);
            }
        }

        static List<CrewEntry> read(RegistryFriendlyByteBuf buf) {
            int size = buf.readVarInt();
            List<CrewEntry> crew = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                crew.add(new CrewEntry(buf.readUtf(), buf.readUtf(), buf.readBoolean()));
            }
            return crew;
        }
    }

    public record BuilderState(int builderId, String name, String owner, String foreman, boolean following, boolean working, float health,
                               float maxHealth, String task) implements CustomPacketPayload {

        public static final Type<BuilderState> TYPE = payloadType("builder_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, BuilderState> CODEC = StreamCodec.of((buf, value) -> {
            buf.writeVarInt(value.builderId);
            buf.writeUtf(value.name);
            buf.writeUtf(value.owner);
            buf.writeUtf(value.foreman);
            buf.writeBoolean(value.following);
            buf.writeBoolean(value.working);
            buf.writeFloat(value.health);
            buf.writeFloat(value.maxHealth);
            buf.writeUtf(value.task);
        }, buf -> new BuilderState(buf.readVarInt(), buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readBoolean(), buf.readBoolean(),
                buf.readFloat(), buf.readFloat(), buf.readUtf()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Hiring(ItemStack price, int have, boolean affordable, int builders, int maxBuilders, int maxCrew, String suggestedName,
                         String problem) {

        static final StreamCodec<RegistryFriendlyByteBuf, Hiring> CODEC = StreamCodec.of((buf, value) -> {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, value.price);
            buf.writeVarInt(value.have);
            buf.writeBoolean(value.affordable);
            buf.writeVarInt(value.builders);
            buf.writeVarInt(value.maxBuilders);
            buf.writeVarInt(value.maxCrew);
            buf.writeUtf(value.suggestedName);
            buf.writeUtf(value.problem);
        }, buf -> new Hiring(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf), buf.readVarInt(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readUtf(), buf.readUtf()));
    }

    public record ForemanState(int foremanId, String name, String owner, boolean following, boolean working, float health, float maxHealth,
                               String jobFile, String jobWhere, String phase, String detail, int done, int total, List<ChestEntry> chests,
                               List<CrewEntry> crew, List<String> orphans, Hiring hiring) implements CustomPacketPayload {

        public static final Type<ForemanState> TYPE = payloadType("foreman_state");
        public static final StreamCodec<RegistryFriendlyByteBuf, ForemanState> CODEC = StreamCodec.of((buf, value) -> {
            buf.writeVarInt(value.foremanId);
            buf.writeUtf(value.name);
            buf.writeUtf(value.owner);
            buf.writeBoolean(value.following);
            buf.writeBoolean(value.working);
            buf.writeFloat(value.health);
            buf.writeFloat(value.maxHealth);
            buf.writeUtf(value.jobFile);
            buf.writeUtf(value.jobWhere);
            buf.writeUtf(value.phase);
            buf.writeUtf(value.detail);
            buf.writeVarInt(value.done);
            buf.writeVarInt(value.total);
            ChestEntry.write(buf, value.chests);
            CrewEntry.write(buf, value.crew);
            buf.writeCollection(value.orphans, (b, s) -> b.writeUtf(s));
            Hiring.CODEC.encode(buf, value.hiring);
        }, buf -> new ForemanState(buf.readVarInt(), buf.readUtf(), buf.readUtf(), buf.readBoolean(), buf.readBoolean(), buf.readFloat(),
                buf.readFloat(), buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readVarInt(), ChestEntry.read(buf),
                CrewEntry.read(buf), buf.readList(b -> b.readUtf()), Hiring.CODEC.decode(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SchematicList(List<String> files) implements CustomPacketPayload {

        public static final Type<SchematicList> TYPE = payloadType("schematic_list");
        public static final StreamCodec<RegistryFriendlyByteBuf, SchematicList> CODEC = StreamCodec.of(
                (buf, value) -> buf.writeCollection(value.files, (b, s) -> b.writeUtf(s)),
                buf -> new SchematicList(buf.readList(b -> b.readUtf())));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SchematicInfo(String file, BlockPos size, BlockPos offset, List<Component> materials, List<BlockState> palette,
                                int parts, boolean complete) implements CustomPacketPayload {

        public static final Type<SchematicInfo> TYPE = payloadType("schematic_info");
        public static final StreamCodec<RegistryFriendlyByteBuf, SchematicInfo> CODEC = StreamCodec.of((buf, value) -> {
            buf.writeUtf(value.file);
            buf.writeBlockPos(value.size);
            buf.writeBlockPos(value.offset);
            COMPONENTS.encode(buf, value.materials);
            buf.writeVarInt(value.palette.size());
            value.palette.forEach(state -> BLOCK_STATE.encode(buf, state));
            buf.writeVarInt(value.parts);
            buf.writeBoolean(value.complete);
        }, buf -> {
            String file = buf.readUtf();
            BlockPos size = buf.readBlockPos();
            BlockPos offset = buf.readBlockPos();
            List<Component> materials = COMPONENTS.decode(buf);
            int paletteSize = buf.readVarInt();
            List<BlockState> palette = new ArrayList<>();
            for (int i = 0; i < paletteSize; i++) {
                palette.add(BLOCK_STATE.decode(buf));
            }
            return new SchematicInfo(file, size, offset, materials, palette, buf.readVarInt(), buf.readBoolean());
        });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SchematicBlocks(String file, int part, byte[] data) implements CustomPacketPayload {

        public static final Type<SchematicBlocks> TYPE = payloadType("schematic_blocks");
        public static final StreamCodec<RegistryFriendlyByteBuf, SchematicBlocks> CODEC = StreamCodec.of((buf, value) -> {
            buf.writeUtf(value.file);
            buf.writeVarInt(value.part);
            buf.writeByteArray(value.data);
        }, buf -> new SchematicBlocks(buf.readUtf(), buf.readVarInt(), buf.readByteArray(PreviewBlocks.PART_BYTES)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record CheckResult(int foremanId, String file, BlockPos origin, String rotation, String options, List<Component> lines,
                              boolean passed, boolean started, String error, int unplaceable, int missing, boolean missingTools)
            implements CustomPacketPayload {

        public static final Type<CheckResult> TYPE = payloadType("check_result");
        public static final StreamCodec<RegistryFriendlyByteBuf, CheckResult> CODEC = StreamCodec.of((buf, value) -> {
            buf.writeVarInt(value.foremanId);
            buf.writeUtf(value.file);
            buf.writeBlockPos(value.origin);
            buf.writeUtf(value.rotation);
            buf.writeUtf(value.options);
            COMPONENTS.encode(buf, value.lines);
            buf.writeBoolean(value.passed);
            buf.writeBoolean(value.started);
            buf.writeUtf(value.error);
            buf.writeVarInt(value.unplaceable);
            buf.writeVarInt(value.missing);
            buf.writeBoolean(value.missingTools);
        }, buf -> new CheckResult(buf.readVarInt(), buf.readUtf(), buf.readBlockPos(), buf.readUtf(), buf.readUtf(), COMPONENTS.decode(buf),
                buf.readBoolean(), buf.readBoolean(), buf.readUtf(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record LinkMode(int foremanId, boolean active, boolean supply, List<ChestEntry> chests) implements CustomPacketPayload {

        public static final Type<LinkMode> TYPE = payloadType("link_mode");
        public static final StreamCodec<RegistryFriendlyByteBuf, LinkMode> CODEC = StreamCodec.of((buf, value) -> {
            buf.writeVarInt(value.foremanId);
            buf.writeBoolean(value.active);
            buf.writeBoolean(value.supply);
            ChestEntry.write(buf, value.chests);
        }, buf -> new LinkMode(buf.readVarInt(), buf.readBoolean(), buf.readBoolean(), ChestEntry.read(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Action(int journeymanId, String action, String text, BlockPos pos) implements CustomPacketPayload {

        public static final Type<Action> TYPE = payloadType("journeyman_action");
        public static final StreamCodec<RegistryFriendlyByteBuf, Action> CODEC = StreamCodec.of((buf, value) -> {
            buf.writeVarInt(value.journeymanId);
            buf.writeUtf(value.action, 64);
            buf.writeUtf(value.text, 1024);
            buf.writeBlockPos(value.pos);
        }, buf -> new Action(buf.readVarInt(), buf.readUtf(64), buf.readUtf(1024), buf.readBlockPos()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
