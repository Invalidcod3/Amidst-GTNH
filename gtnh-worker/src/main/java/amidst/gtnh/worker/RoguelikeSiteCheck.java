package amidst.gtnh.worker;

import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import java.lang.reflect.*;
import java.util.Arrays;
import java.util.Random;

/** Invokes only validLocation, through a read-only editor restricted to populated chunks. */
final class RoguelikeSiteCheck {
    static String check(WorldServer world, int x, int z) {
        try {
            Class<?> editorType = Class.forName("greymerk.roguelike.worldgen.IWorldEditor");
            Object editor =
                    Class.forName("greymerk.roguelike.worldgen.WorldEditor")
                            .getConstructor(World.class)
                            .newInstance(world);
            Object guarded =
                    Proxy.newProxyInstance(
                            editorType.getClassLoader(),
                            new Class<?>[] {editorType},
                            (proxy, method, args) -> {
                                if (!Arrays.asList(
                                                "getBiome",
                                                "isAirBlock",
                                                "validGroundBlock",
                                                "getBlock")
                                        .contains(method.getName()))
                                    throw new IllegalStateException(
                                            "Unsupported site-check operation");
                                Object coord = args[0];
                                int bx = (Integer) coord.getClass().getMethod("getX").invoke(coord);
                                int bz = (Integer) coord.getClass().getMethod("getZ").invoke(coord);
                                if (!world.blockExists(bx, 0, bz)
                                        || !world.getChunkFromBlockCoords(bx, bz)
                                                .isTerrainPopulated)
                                    throw new IllegalStateException(
                                            "Site includes unloaded or unpopulated chunks");
                                return method.invoke(editor, args);
                            });
            Class<?> dungeonType = Class.forName("greymerk.roguelike.dungeon.Dungeon");
            Object dungeon = dungeonType.getConstructor(editorType).newInstance(guarded);
            boolean valid =
                    (Boolean)
                            dungeonType
                                    .getMethod("validLocation", Random.class, int.class, int.class)
                                    .invoke(dungeon, new Random(world.getSeed()), x, z);
            return valid
                    ? "Current terrain passes Roguelike site checks; dungeon existence remains"
                            + " unverified"
                    : "Current terrain fails Roguelike site checks; not proof of absence. Recheck"
                            + " after terrain/save changes";
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return "Roguelike site check unavailable or chunks not fully loaded; dungeon existence"
                    + " remains unverified";
        }
    }
}
