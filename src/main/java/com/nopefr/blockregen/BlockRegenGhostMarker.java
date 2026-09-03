package com.nopefr.blockregen;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Marker component tagging the "ghost" preview entities spawned by
 * {@link BlockRegenListener} for a broken block that is awaiting
 * regeneration. Carries no data; its only purpose is to let
 * {@link BlockRegenGhostVisibilitySystem} recognize and hide these entities
 * from players without the ghost-viewing permission.
 */
public class BlockRegenGhostMarker implements Component<EntityStore> {

    public static final BlockRegenGhostMarker INSTANCE = new BlockRegenGhostMarker();
    public static final BuilderCodec<BlockRegenGhostMarker> CODEC = BuilderCodec.builder(BlockRegenGhostMarker.class, () -> INSTANCE).build();

    private static ComponentType<EntityStore, BlockRegenGhostMarker> componentType;

    private BlockRegenGhostMarker() {
    }

    public static ComponentType<EntityStore, BlockRegenGhostMarker> getComponentType() {
        return componentType;
    }

    static void setComponentType(ComponentType<EntityStore, BlockRegenGhostMarker> type) {
        componentType = type;
    }

    @Override
    public Component<EntityStore> clone() {
        return INSTANCE;
    }
}
