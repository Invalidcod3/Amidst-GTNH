package amidst.gtnh.worker;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Bound primitive calls to the SAME installed methods; no copied noise math. */
final class RwgTerrainAccess {
    private final MethodHandle river, ocean, calculateRiver, terrain;

    RwgTerrainAccess(Object manager, Object noise, Object cell, Method terrainMethod)
            throws ReflectiveOperationException {
        river = bind(manager, "getRiverStrength", int.class, int.class);
        ocean = bind(manager, "getOceanValue", int.class, int.class);
        calculateRiver = bind(manager, "calculateRiver", int.class, int.class, float.class, float.class);
        terrainMethod.setAccessible(true);
        terrain = MethodHandles.insertArguments(MethodHandles.lookup().unreflect(terrainMethod), 1, noise, cell)
                .asType(MethodType.methodType(float.class, Object.class, int.class, int.class,
                        float.class, float.class, float.class));
    }

    private static MethodHandle bind(Object target, String name, Class<?>... parameters)
            throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(name, parameters);
        method.setAccessible(true);
        return MethodHandles.lookup().unreflect(method).bindTo(target);
    }

    float river(int x, int z) throws InvocationTargetException {
        try { return (float) river.invokeExact(x, z); }
        catch (Throwable cause) { throw new InvocationTargetException(cause); }
    }

    float ocean(int x, int z) throws InvocationTargetException {
        try { return (float) ocean.invokeExact(x, z); }
        catch (Throwable cause) { throw new InvocationTargetException(cause); }
    }

    float calculateRiver(int x, int z, float riverStrength, float height) throws InvocationTargetException {
        try { return (float) calculateRiver.invokeExact(x, z, riverStrength, height); }
        catch (Throwable cause) { throw new InvocationTargetException(cause); }
    }

    float terrain(Object biome, int x, int z, float oceanValue, float weight, float riverValue)
            throws InvocationTargetException {
        try { return (float) terrain.invokeExact(biome, x, z, oceanValue, weight, riverValue); }
        catch (Throwable cause) { throw new InvocationTargetException(cause); }
    }
}
