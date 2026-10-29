package io.github.apperk274.journeymen.build;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class EntityTraps {

    @Nullable
    private static final Method ENTITY_INSIDE = findEntityInside();
    private static final Map<Class<?>, Boolean> OVERRIDES = new ConcurrentHashMap<>();

    private EntityTraps() {
    }

    public static boolean reactsToEntitiesInside(Block block) {
        if (ENTITY_INSIDE == null) {
            return false;
        }
        return OVERRIDES.computeIfAbsent(block.getClass(), EntityTraps::overrides);
    }

    private static boolean overrides(Class<?> type) {
        for (Class<?> current = type; current != null && current != BlockBehaviour.class; current = current.getSuperclass()) {
            try {
                current.getDeclaredMethod(ENTITY_INSIDE.getName(), ENTITY_INSIDE.getParameterTypes());
                return true;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return false;
    }

    @Nullable
    private static Method findEntityInside() {
        Method found = null;
        for (Method method : BlockBehaviour.class.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getReturnType() == void.class && parameters.length == 4 && parameters[0] == BlockState.class
                    && parameters[1] == Level.class && parameters[2] == BlockPos.class && parameters[3] == Entity.class) {
                if (found != null) {
                    return null;
                }
                found = method;
            }
        }
        return found;
    }
}
