package dev.tr7zw.skinlayers.util;

import java.lang.reflect.Method;
import java.util.function.Function;

import dev.tr7zw.skinlayers.SkinLayersModBase;

/**
 * Bridge between EMF and the legacy kosmx playerAnimator (shipped jar-in-jar
 * inside Emotecraft 2.4.x). EMF only registers its "use the vanilla model
 * while a player animation is running" fallback for the newer
 * player_animation_library/bendable_cuboids mods - with the legacy
 * playerAnimator the hook never activates and EMF renders its own model,
 * ignoring the emote pose and the bendy-lib bends entirely.
 *
 * This shim registers the same conditions through the public EMFAnimationApi,
 * just checking the legacy playerAnimator animation state instead.
 */
public final class EMFAnimationCompat {

    private EMFAnimationCompat() {
        // util class
    }

    public static void init() {
        Class<?> emfApi;
        try {
            emfApi = Class.forName("traben.entity_model_features.EMFAnimationApi");
        } catch (ClassNotFoundException ex) {
            return; // EMF is not installed, nothing to do
        }
        Class<?> animatedPlayer;
        try {
            animatedPlayer = Class.forName("dev.kosmx.playerAnim.impl.IAnimatedPlayer");
        } catch (ClassNotFoundException ex) {
            return; // legacy playerAnimator is not installed, nothing to do
        }
        try {
            Method getAnimation = animatedPlayer.getMethod("playerAnimator_getAnimation");
            Method isActive = getAnimation.getReturnType().getMethod("isActive");

            Function<Object, Boolean> emoteActive = entity -> {
                try {
                    if (animatedPlayer.isInstance(entity)) {
                        return (Boolean) isActive.invoke(getAnimation.invoke(entity));
                    }
                } catch (Throwable ex) {
                    // never crash the render loop over the compat check
                }
                return false;
            };

            emfApi.getMethod("registerPauseCondition", Function.class).invoke(null, emoteActive);
            emfApi.getMethod("registerVanillaModelCondition", Function.class).invoke(null, emoteActive);
            SkinLayersModBase.LOGGER
                    .info("Found EMF + legacy playerAnimator, registered the vanilla model fallback for emotes!");
        } catch (Throwable ex) {
            SkinLayersModBase.LOGGER.error("Failed to register the EMF/playerAnimator compatibility!", ex);
        }
    }

}
