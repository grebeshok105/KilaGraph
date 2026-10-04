package com.lowdragmc.kilagraph.rendertype.runtime;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;

/**
 * Client hook that drives {@link SceneCaptureManager#capture()} at the opaque&rarr;translucent boundary.
 *
 * <p>Listens for {@link WorldRenderEvents#AFTER_ENTITIES}: the closest fabric hook to neoforge's
 * {@code RenderLevelStageEvent.Stage#AFTER_BLOCK_ENTITIES} — vanilla draws block entities in the same
 * pass as entities, and translucent comes later, so the opaque scene is fully on screen here.</p>
 */
public final class SceneCaptureHandler {

    private SceneCaptureHandler() {}

    /** Register the capture listener (client only). */
    public static void init() {
        WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            if (SceneCaptureManager.INSTANCE.isNeeded()) {
                SceneCaptureManager.INSTANCE.capture();
            }
        });
    }
}
