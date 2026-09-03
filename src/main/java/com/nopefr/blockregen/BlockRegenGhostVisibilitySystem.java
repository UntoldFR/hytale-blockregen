package com.nopefr.blockregen;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;

/**
 * Hides {@link BlockRegenGhostMarker} entities (the regen "ghost" previews)
 * from any player lacking the ghost-viewing permission. Runs in the same
 * system group as - and right after - the engine's own visibility
 * collection, mirroring the built-in mechanism the game itself uses to hide
 * specific players from each other ({@code EntityTrackerSystems.HideFromPlayer}).
 */
public class BlockRegenGhostVisibilitySystem extends EntityTickingSystem<EntityStore> {

    private final ComponentType<EntityStore, EntityTrackerSystems.EntityViewer> entityViewerComponentType;
    private final ComponentType<EntityStore, PlayerRef> playerRefComponentType;
    private final ComponentType<EntityStore, BlockRegenGhostMarker> ghostMarkerComponentType;
    private final String permission;
    private final Query<EntityStore> query;
    private final Set<Dependency<EntityStore>> dependencies;

    public BlockRegenGhostVisibilitySystem(
        @Nonnull ComponentType<EntityStore, EntityTrackerSystems.EntityViewer> entityViewerComponentType,
        @Nonnull ComponentType<EntityStore, BlockRegenGhostMarker> ghostMarkerComponentType,
        @Nonnull String permission
    ) {
        this.entityViewerComponentType = entityViewerComponentType;
        this.playerRefComponentType = PlayerRef.getComponentType();
        this.ghostMarkerComponentType = ghostMarkerComponentType;
        this.permission = permission;
        this.query = Query.and(entityViewerComponentType, this.playerRefComponentType);
        this.dependencies = Collections.singleton(new SystemDependency<>(Order.AFTER, EntityTrackerSystems.CollectVisible.class));
    }

    @Nullable
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return EntityTrackerSystems.FIND_VISIBLE_ENTITIES_GROUP;
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void tick(
        float dt, int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk, @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        PlayerRef playerRef = archetypeChunk.getComponent(index, playerRefComponentType);
        if (playerRef == null || playerRef.hasPermission(permission)) {
            return; // allowed to see ghosts: nothing to filter out for this viewer
        }

        EntityTrackerSystems.EntityViewer entityViewerComponent = archetypeChunk.getComponent(index, entityViewerComponentType);
        if (entityViewerComponent == null) {
            return;
        }

        Iterator<Ref<EntityStore>> iterator = entityViewerComponent.visible.iterator();
        while (iterator.hasNext()) {
            Ref<EntityStore> targetRef = iterator.next();
            if (!targetRef.isValid()) {
                iterator.remove();
            } else if (commandBuffer.getArchetype(targetRef).contains(ghostMarkerComponentType)) {
                iterator.remove();
            }
        }
    }
}
