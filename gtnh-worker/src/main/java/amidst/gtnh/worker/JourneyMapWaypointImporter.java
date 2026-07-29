package amidst.gtnh.worker;

import java.awt.Color;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Optional JourneyMap integration. Reflection keeps the worker loadable when
 * JourneyMap is not installed and gives the caller a useful runtime error.
 */
final class JourneyMapWaypointImporter {

    private static final int MAX_WAYPOINTS = 2000;

    int importWaypoints(
            int dimension,
            List<WaypointDescriptor> descriptors) {
        if (descriptors == null || descriptors.isEmpty()) {
            return 0;
        }
        if (descriptors.size() > MAX_WAYPOINTS) {
            throw new IllegalArgumentException(
                    "direct JourneyMap import is limited to "
                            + MAX_WAYPOINTS
                            + " waypoints per operation");
        }
        try {
            Class<?> waypointClass = Class.forName("journeymap.client.model.Waypoint");
            Class<?> typeClass = Class.forName("journeymap.client.model.Waypoint$Type");
            Class<?> storeClass = Class.forName("journeymap.client.waypoint.WaypointStore");
            Object normalType = enumValue(typeClass, "Normal");
            Constructor<?> constructor = waypointClass.getConstructor(
                    String.class,
                    Integer.TYPE,
                    Integer.TYPE,
                    Integer.TYPE,
                    Color.class,
                    typeClass,
                    Integer.class);
            Object store = storeClass.getMethod("instance").invoke(null);
            Method save = storeClass.getMethod("save", waypointClass);

            int imported = 0;
            for (WaypointDescriptor descriptor : descriptors) {
                validate(descriptor);
                Object waypoint = constructor.newInstance(
                        descriptor.name,
                        Integer.valueOf(descriptor.x),
                        Integer.valueOf(descriptor.y),
                        Integer.valueOf(descriptor.z),
                        new Color(descriptor.red, descriptor.green, descriptor.blue),
                        normalType,
                        Integer.valueOf(dimension));
                save.invoke(store, waypoint);
                imported++;
            }
            return imported;
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "JourneyMap is not installed or has not finished loading", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw new IllegalStateException(
                    "JourneyMap rejected a waypoint: "
                            + (cause == null ? e.getMessage() : cause.getMessage()),
                    cause == null ? e : cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "the installed JourneyMap version does not provide the GTNH 1.7.10 waypoint API",
                    e);
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static Object enumValue(Class<?> enumClass, String name) {
        return Enum.valueOf((Class<? extends Enum>) enumClass, name);
    }

    private static void validate(WaypointDescriptor descriptor) {
        if (descriptor == null) {
            throw new IllegalArgumentException("waypoint must not be null");
        }
        if (descriptor.name == null || descriptor.name.trim().isEmpty()) {
            throw new IllegalArgumentException("waypoint name must not be blank");
        }
        if (descriptor.red < 0 || descriptor.red > 255
                || descriptor.green < 0 || descriptor.green > 255
                || descriptor.blue < 0 || descriptor.blue > 255) {
            throw new IllegalArgumentException("waypoint RGB values must be between 0 and 255");
        }
    }

    static final class WaypointDescriptor {

        private String name;
        private int x;
        private int y;
        private int z;
        private int red;
        private int green;
        private int blue;
    }
}
