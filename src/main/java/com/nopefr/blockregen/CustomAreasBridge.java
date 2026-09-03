package com.nopefr.blockregen;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;

/**
 * Optional, reflection-only bridge to the CustomAreas plugin
 * ({@code com.nopefr.api.CustomAreasAPI}), so BlockRegen stays a fully
 * standalone, independently buildable plugin: no compile-time dependency on
 * CustomAreas' classes, and no crash or behavior change if it isn't
 * installed on the server.
 *
 * Presence and readiness are re-checked lazily on every call rather than
 * once at {@code setup()}, since there's no guaranteed load order between
 * the two plugins: {@code CustomAreasAPI.get()} throws until CustomAreas has
 * finished its own setup(), which this bridge just treats as "not available
 * yet" and retries next time.
 */
final class CustomAreasBridge {

    private static final String API_CLASS = "com.nopefr.api.CustomAreasAPI";
    static final String BLOCKREGEN_FLAG = "BLOCKREGEN";

    private final BlockRegenPlugin plugin;
    private boolean warnedOnce = false;
    private boolean flagRegistered = false;

    CustomAreasBridge(BlockRegenPlugin plugin) {
        this.plugin = plugin;
    }

    boolean isPresent() {
        return resolveApi() != null;
    }

    /** Registers the BLOCKREGEN flag with CustomAreas, if available. Safe to call repeatedly. */
    void ensureFlagRegistered() {
        if (flagRegistered) {
            return;
        }
        Object api = resolveApi();
        if (api == null) {
            return;
        }
        try {
            Object flagRegistry = invoke(api, "flags");
            invoke(flagRegistry, "register", new Class<?>[]{String.class, String.class},
                BLOCKREGEN_FLAG, "Broken blocks in this area regenerate per BlockRegen's per-area rules.");
            flagRegistered = true;
        } catch (ReflectiveOperationException e) {
            logOnce("Failed to register the BLOCKREGEN flag with CustomAreas: " + e.getMessage());
        }
    }

    /** All areas currently flagged BLOCKREGEN, for the "/blockregen list" scope dropdown. */
    @Nonnull
    List<String> getBlockRegenAreaNames() {
        Object api = resolveApi();
        if (api == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        try {
            Collection<?> areas = (Collection<?>) invoke(api, "getAreas");
            for (Object area : areas) {
                if ((boolean) invoke(area, "hasFlag", new Class<?>[]{String.class}, BLOCKREGEN_FLAG)) {
                    names.add((String) invoke(area, "name"));
                }
            }
        } catch (ReflectiveOperationException | ClassCastException e) {
            logOnce("Failed to list CustomAreas areas: " + e.getMessage());
            return List.of();
        }
        return names;
    }

    /** First BLOCKREGEN-flagged area containing this position, or null if none (or CustomAreas absent). */
    @Nullable
    String findBlockRegenAreaAt(@Nonnull String world, int x, int y, int z) {
        Object api = resolveApi();
        if (api == null) {
            return null;
        }
        try {
            List<?> areas = (List<?>) invoke(
                api, "getAreasAt", new Class<?>[]{String.class, int.class, int.class, int.class}, world, x, y, z
            );
            for (Object area : areas) {
                if ((boolean) invoke(area, "hasFlag", new Class<?>[]{String.class}, BLOCKREGEN_FLAG)) {
                    return (String) invoke(area, "name");
                }
            }
        } catch (ReflectiveOperationException | ClassCastException e) {
            logOnce("Failed to query CustomAreas areas at (" + world + "," + x + "," + y + "," + z + "): " + e.getMessage());
        }
        return null;
    }

    @Nullable
    private Object resolveApi() {
        try {
            Class<?> apiClass = Class.forName(API_CLASS);
            Method getMethod = apiClass.getMethod("get");
            return getMethod.invoke(null);
        } catch (ClassNotFoundException e) {
            return null; // CustomAreas isn't installed - normal, not an error
        } catch (InvocationTargetException e) {
            // CustomAreasAPI.get() throws IllegalStateException until CustomAreas' own setup() has run.
            return null;
        } catch (ReflectiveOperationException e) {
            logOnce("Unexpected error resolving CustomAreasAPI: " + e.getMessage());
            return null;
        }
    }

    private static Object invoke(Object target, String methodName) throws ReflectiveOperationException {
        return invoke(target, methodName, new Class<?>[0]);
    }

    private static Object invoke(Object target, String methodName, Class<?>[] paramTypes, Object... args) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(methodName, paramTypes);
        return method.invoke(target, args);
    }

    private void logOnce(String message) {
        if (!warnedOnce) {
            warnedOnce = true;
            plugin.getLogger().at(Level.WARNING).log("[CustomAreas integration] " + message);
        }
    }
}
