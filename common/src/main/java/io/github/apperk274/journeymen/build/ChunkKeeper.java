package io.github.apperk274.journeymen.build;

import io.github.apperk274.journeymen.Constants;
import io.github.apperk274.journeymen.config.JourneymenConfig;
import io.github.apperk274.journeymen.entity.JourneymanEntity;
import io.github.apperk274.journeymen.entity.JourneymanRegistry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ChunkKeeper {

    private static final TicketType<ChunkPos> TICKET = TicketType.create(Constants.MOD_ID, Comparator.comparingLong(ChunkPos::toLong));
    private static final int SITE_DISTANCE = 2;
    private static final int JOURNEYMAN_DISTANCE = 3;
    private static final int INTERVAL = 20;

    private static final Map<ResourceKey<Level>, Map<Long, Integer>> held = new HashMap<>();
    @Nullable
    private static MinecraftServer server;

    public record Spot(String name, ResourceKey<Level> dimension, ChunkPos chunk) {
    }

    public record Kept(String foreman, String file, ResourceKey<Level> dimension, List<ChunkPos> site, List<Spot> journeymen) {
    }

    private ChunkKeeper() {
    }

    public static void tick(MinecraftServer current) {
        if (current.getTickCount() % INTERVAL == 0) {
            update(current);
        }
    }

    public static void reset() {
        held.clear();
        server = null;
    }

    public static void update(MinecraftServer current) {
        if (server != current) {
            held.clear();
            server = current;
        }
        Map<ResourceKey<Level>, Map<Long, Integer>> wanted = new HashMap<>();
        if (JourneymenConfig.KEEP_CHUNKS_LOADED.get()) {
            for (Kept kept : kept(current)) {
                Map<Long, Integer> site = wanted.computeIfAbsent(kept.dimension(), key -> new HashMap<>());
                kept.site().forEach(chunk -> site.merge(chunk.toLong(), SITE_DISTANCE, Math::max));
                for (Spot spot : kept.journeymen()) {
                    wanted.computeIfAbsent(spot.dimension(), key -> new HashMap<>()).merge(spot.chunk().toLong(), JOURNEYMAN_DISTANCE, Math::max);
                }
            }
        }
        Set<ResourceKey<Level>> dimensions = new HashSet<>(held.keySet());
        dimensions.addAll(wanted.keySet());
        for (ResourceKey<Level> dimension : dimensions) {
            Map<Long, Integer> have = held.computeIfAbsent(dimension, key -> new HashMap<>());
            Map<Long, Integer> want = wanted.getOrDefault(dimension, Map.of());
            ServerLevel level = current.getLevel(dimension);
            if (level == null) {
                held.remove(dimension);
                continue;
            }
            ServerChunkCache chunks = level.getChunkSource();
            Iterator<Map.Entry<Long, Integer>> iterator = have.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Long, Integer> entry = iterator.next();
                if (!entry.getValue().equals(want.get(entry.getKey()))) {
                    ChunkPos chunk = new ChunkPos(entry.getKey());
                    chunks.removeRegionTicket(TICKET, chunk, entry.getValue(), chunk);
                    iterator.remove();
                }
            }
            for (Map.Entry<Long, Integer> entry : want.entrySet()) {
                if (!have.containsKey(entry.getKey())) {
                    ChunkPos chunk = new ChunkPos(entry.getKey());
                    chunks.addRegionTicket(TICKET, chunk, entry.getValue(), chunk);
                    have.put(entry.getKey(), entry.getValue());
                }
            }
            if (have.isEmpty()) {
                held.remove(dimension);
            } else {
                level.resetEmptyTime();
            }
        }
    }

    public static Map<ResourceKey<Level>, Integer> heldTickets() {
        Map<ResourceKey<Level>, Integer> counts = new HashMap<>();
        held.forEach((dimension, chunks) -> counts.put(dimension, chunks.size()));
        return counts;
    }

    public static List<Kept> kept(MinecraftServer current) {
        JourneymanRegistry registry = JourneymanRegistry.get(current);
        List<Kept> list = new ArrayList<>();
        for (BuildJob job : registry.jobs()) {
            BoundingBox area = job.getArea();
            List<ChunkPos> site = new ArrayList<>();
            for (int x = area.minX() >> 4; x <= area.maxX() >> 4; x++) {
                for (int z = area.minZ() >> 4; z <= area.maxZ() >> 4; z++) {
                    site.add(new ChunkPos(x, z));
                }
            }
            List<Spot> journeymen = new ArrayList<>();
            Spot foreman = spot(current, registry, job.getForeman());
            if (foreman != null && foreman.dimension().equals(job.getDimension())) {
                journeymen.add(foreman);
            }
            for (UUID member : job.getMembers()) {
                Spot spot = spot(current, registry, member);
                if (spot != null && spot.dimension().equals(job.getDimension())) {
                    journeymen.add(spot);
                }
            }
            String name = registry.get(job.getForeman()).map(JourneymanRegistry.Entry::qualifiedName).orElse(job.getForeman().toString());
            list.add(new Kept(name, job.getFile(), job.getDimension(), site, journeymen));
        }
        return list;
    }

    @Nullable
    private static Spot spot(MinecraftServer current, JourneymanRegistry registry, UUID id) {
        JourneymanEntity loaded = registry.loaded(current, id);
        if (loaded != null) {
            return new Spot(loaded.getJourneymanName(), loaded.level().dimension(), loaded.chunkPosition());
        }
        return registry.get(id).map(entry -> new Spot(entry.name(), entry.dimension(), new ChunkPos(entry.pos()))).orElse(null);
    }
}
