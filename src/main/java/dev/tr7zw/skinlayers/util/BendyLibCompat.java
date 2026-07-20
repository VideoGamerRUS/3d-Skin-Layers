package dev.tr7zw.skinlayers.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Optional;

import dev.tr7zw.skinlayers.SkinLayersModBase;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;

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
    private static final Method HAS_MUTATOR; // MutableCuboid#hasMutator(String)
    private static final Method REGISTER_MUTATOR; // MutableCuboid#registerMutator(String, ICuboidBuilder)
    private static final Class<?> ICUBOID_BUILDER; // ICuboidBuilder (functional interface)
    private static final Method BUILDER_SET_DIRECTION; // BendableCuboid.Builder#setDirection(Direction)
    private static final Method BUILDER_BUILD; // BendableCuboid.Builder#build(Data)
    private static final Field DATA_PIVOT; // ICuboidBuilder.Data#pivot

    private static boolean errored = false;

    static {
        Method optionalGetCuboid = null;
        Method getActiveMutator = null;
        Method tupleGetB = null;
        Method getBend = null;
        Class<?> bendableCuboid = null;
        Method hasMutator = null;
        Method registerMutator = null;
        Class<?> iCuboidBuilder = null;
        Method builderSetDirection = null;
        Method builderBuild = null;
        Field dataPivot = null;
        try {
            Class<?> accessor = Class.forName("io.github.kosmx.bendylib.ModelPartAccessor");
            Class<?> mutableCuboid = Class.forName("io.github.kosmx.bendylib.MutableCuboid");
            bendableCuboid = Class.forName("io.github.kosmx.bendylib.impl.BendableCuboid");
            optionalGetCuboid = accessor.getMethod("optionalGetCuboid", ModelPart.class, int.class);
            getActiveMutator = mutableCuboid.getMethod("getActiveMutator");
            tupleGetB = getActiveMutator.getReturnType().getMethod("getB");
            getBend = bendableCuboid.getMethod("getBend");
            iCuboidBuilder = Class.forName("io.github.kosmx.bendylib.ICuboidBuilder");
            Class<?> builderData = Class.forName("io.github.kosmx.bendylib.ICuboidBuilder$Data");
            Class<?> builder = Class.forName("io.github.kosmx.bendylib.impl.BendableCuboid$Builder");
            hasMutator = mutableCuboid.getMethod("hasMutator", String.class);
            registerMutator = mutableCuboid.getMethod("registerMutator", String.class, iCuboidBuilder);
            builderSetDirection = builder.getMethod("setDirection", Direction.class);
            builderBuild = builder.getMethod("build", builderData);
            dataPivot = builderData.getField("pivot");
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
        HAS_MUTATOR = hasMutator;
        REGISTER_MUTATOR = registerMutator;
        ICUBOID_BUILDER = iCuboidBuilder;
        BUILDER_SET_DIRECTION = builderSetDirection;
        BUILDER_BUILD = builderBuild;
        DATA_PIVOT = dataPivot;
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

    /**
     * playerAnimator registers its "bend" mutators once in the PlayerModel
     * constructor. Mods like EMF later swap in freshly built cuboids that never
     * got that registration - playerAnimator then NPEs mid-render the moment an
     * emote tries to bend such a part. Re-registering the missing mutators
     * every frame before setupAnim runs keeps the bend working on any cuboid.
     *
     * @param playerModel the PlayerModel (taken as Object to stay
     *                    version-agnostic)
     */
    public static void ensurePlayerBendMutators(Object playerModel) {
        if (REGISTER_MUTATOR == null || errored || playerModel == null) {
            return;
        }
        ensureBendMutator(getPart(playerModel, "body"), Direction.DOWN, false);
        ensureBendMutator(getPart(playerModel, "jacket"), Direction.DOWN, false);
        ensureBendMutator(getPart(playerModel, "leftArm"), Direction.UP, false);
        ensureBendMutator(getPart(playerModel, "rightArm"), Direction.UP, false);
        ensureBendMutator(getPart(playerModel, "leftSleeve"), Direction.UP, false);
        ensureBendMutator(getPart(playerModel, "rightSleeve"), Direction.UP, false);
        ensureBendMutator(getPart(playerModel, "leftLeg"), Direction.UP, false);
        ensureBendMutator(getPart(playerModel, "rightLeg"), Direction.UP, false);
        ensureBendMutator(getPart(playerModel, "leftPants"), Direction.UP, false);
        ensureBendMutator(getPart(playerModel, "rightPants"), Direction.UP, false);
        ensureBendMutator(getPart(playerModel, "cloak"), Direction.UP, true);
    }

    private static ModelPart getPart(Object model, String fieldName) {
        Class<?> clazz = model.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                Object value = field.get(model);
                return value instanceof ModelPart ? (ModelPart) value : null;
            } catch (NoSuchFieldException ex) {
                clazz = clazz.getSuperclass();
            } catch (Throwable ex) {
                return null; // inaccessible (module restrictions) - skip this part
            }
        }
        return null;
    }

    private static void ensureBendMutator(ModelPart part, Direction direction, boolean capePivot) {
        if (part == null) {
            return;
        }
        try {
            Optional<?> cuboid = (Optional<?>) OPTIONAL_GET_CUBOID.invoke(null, part, 0);
            if (!cuboid.isPresent()) {
                return;
            }
            Object mutable = cuboid.get();
            if ((Boolean) HAS_MUTATOR.invoke(mutable, "bend")) {
                return;
            }
            // same registration playerAnimator does in initBend/initCapeBend
            Object builder = Proxy.newProxyInstance(BendyLibCompat.class.getClassLoader(),
                    new Class<?>[] { ICUBOID_BUILDER }, (proxy, method, args) -> {
                        if ("build".equals(method.getName())) {
                            if (capePivot) {
                                DATA_PIVOT.setInt(args[0], 6);
                            }
                            Object bendableBuilder = BUILDER_BUILD.getDeclaringClass().getConstructor().newInstance();
                            BUILDER_SET_DIRECTION.invoke(bendableBuilder, direction);
                            return BUILDER_BUILD.invoke(bendableBuilder, args[0]);
                        }
                        if ("toString".equals(method.getName())) {
                            return "skinlayers3d bend builder";
                        }
                        if ("hashCode".equals(method.getName())) {
                            return System.identityHashCode(proxy);
                        }
                        if ("equals".equals(method.getName())) {
                            return proxy == args[0];
                        }
                        return null;
                    });
            REGISTER_MUTATOR.invoke(mutable, "bend", builder);
        } catch (Throwable ex) {
            errored = true;
            SkinLayersModBase.LOGGER
                    .error("Error while re-registering bendy-lib mutators. Bend compatibility disabled!", ex);
        }
    }

}
