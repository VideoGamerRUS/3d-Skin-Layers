package dev.tr7zw.skinlayers.util;

import java.lang.reflect.Method;
import java.util.Optional;

import dev.tr7zw.skinlayers.SkinLayersModBase;
import net.minecraft.client.model.geom.ModelPart;

/**
 * Reflection shim for the optional bendy-lib mod (usually shipped jar-in-jar
 * inside Emotecraft/playerAnimator). bendy-lib does not touch the ModelPart
 * pose, it bends the cuboid vertices instead - so the 3d layers have to check
 * the active bend state themselves.
 */
public final class BendyLibCompat {

    /** same epsilon playerAnimator uses before enabling a bend */
    private static final float BEND_EPSILON = 0.0001f;

    private static final Method OPTIONAL_GET_CUBOID; // ModelPartAccessor#optionalGetCuboid(ModelPart, int)
    private static final Method GET_ACTIVE_MUTATOR; // MutableCuboid#getActiveMutator() -> Tuple<String, ICuboid>
    private static final Method TUPLE_GET_B; // Tuple#getB() -> ICuboid
    private static final Method GET_BEND; // BendableCuboid#getBend() -> float
    private static final Class<?> BENDABLE_CUBOID;

    private static boolean errored = false;

    static {
        Method optionalGetCuboid = null;
        Method getActiveMutator = null;
        Method tupleGetB = null;
        Method getBend = null;
        Class<?> bendableCuboid = null;
        try {
            Class<?> accessor = Class.forName("io.github.kosmx.bendylib.ModelPartAccessor");
            Class<?> mutableCuboid = Class.forName("io.github.kosmx.bendylib.MutableCuboid");
            bendableCuboid = Class.forName("io.github.kosmx.bendylib.impl.BendableCuboid");
            optionalGetCuboid = accessor.getMethod("optionalGetCuboid", ModelPart.class, int.class);
            getActiveMutator = mutableCuboid.getMethod("getActiveMutator");
            tupleGetB = getActiveMutator.getReturnType().getMethod("getB");
            getBend = bendableCuboid.getMethod("getBend");
            SkinLayersModBase.LOGGER.info("Found bendy-lib, enable bend compatibility!");
        } catch (ClassNotFoundException ex) {
            // bendy-lib is not installed, nothing to do
        } catch (Throwable ex) {
            SkinLayersModBase.LOGGER.error("Found bendy-lib, but the API doesn't match. Bend compatibility disabled!",
                    ex);
            optionalGetCuboid = null;
        }
        OPTIONAL_GET_CUBOID = optionalGetCuboid;
        GET_ACTIVE_MUTATOR = getActiveMutator;
        TUPLE_GET_B = tupleGetB;
        GET_BEND = getBend;
        BENDABLE_CUBOID = bendableCuboid;
    }

    private BendyLibCompat() {
        // util class
    }

    /**
     * @return true when bendy-lib is currently bending the cuboid of this part,
     *         false in every other case (no bendy-lib, no active bend, error)
     */
    public static boolean isBent(ModelPart part) {
        if (OPTIONAL_GET_CUBOID == null || errored || part == null) {
            return false;
        }
        try {
            Optional<?> cuboid = (Optional<?>) OPTIONAL_GET_CUBOID.invoke(null, part, 0);
            if (!cuboid.isPresent()) {
                return false;
            }
            Object activeMutator = GET_ACTIVE_MUTATOR.invoke(cuboid.get());
            if (activeMutator == null) {
                return false;
            }
            Object mutator = TUPLE_GET_B.invoke(activeMutator);
            if (!BENDABLE_CUBOID.isInstance(mutator)) {
                return false;
            }
            return Math.abs(((Float) GET_BEND.invoke(mutator)).floatValue()) >= BEND_EPSILON;
        } catch (Throwable ex) {
            errored = true;
            SkinLayersModBase.LOGGER.error("Error while reading the bendy-lib state. Bend compatibility disabled!", ex);
            return false;
        }
    }

}
