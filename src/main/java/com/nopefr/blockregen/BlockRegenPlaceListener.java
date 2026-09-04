package com.nopefr.blockregen;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.joml.Vector3i;

/**
 * A chaque pose de bloc :
 * <ul>
 *   <li>annule toute regeneration en attente a cette meme position (voir
 *       {@link BlockRegenPlugin#cancelPendingRegenAt}) : si un joueur
 *       construit par-dessus un bloc casse avant la fin de son delai, on ne
 *       veut pas ecraser plus tard ce qu'il vient de poser ;</li>
 *   <li>marque la position comme "posee par un joueur" (sauf si le joueur a
 *       active le mode admin via {@code /blockregen admin}) afin d'empecher
 *       toute regeneration si ce bloc est ensuite casse (anti-abus, voir
 *       {@link BlockRegenListener}).</li>
 * </ul>
 */
public class BlockRegenPlaceListener extends EntityEventSystem<EntityStore, PlaceBlockEvent> {

    private final BlockRegenPlugin plugin;

    public BlockRegenPlaceListener(BlockRegenPlugin plugin) {
        super(PlaceBlockEvent.class);
        this.plugin = plugin;
    }

    @Override
    public void handle(int i,
                        @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                        @Nonnull Store<EntityStore> store,
                        @Nonnull CommandBuffer<EntityStore> commandBuffer,
                        @Nonnull PlaceBlockEvent event) {

        World world = store.getExternalData().getWorld();
        Vector3i targetBlock = event.getTargetBlock();
        int x = targetBlock.x();
        int y = targetBlock.y();
        int z = targetBlock.z();

        plugin.cancelPendingRegenAt(world, x, y, z, commandBuffer);

        // L'entite qui a declenche l'evenement est celle qui a pose le bloc
        // (generalement un joueur ; peut etre absente/non-joueur pour une
        // pose declenchee autrement, auquel cas on ne marque rien).
        Ref<EntityStore> placerRef = archetypeChunk.getReferenceTo(i);
        PlayerRef placer = placerRef != null && placerRef.isValid()
            ? store.getComponent(placerRef, PlayerRef.getComponentType())
            : null;
        boolean isNormalPlayerPlacement = placer != null && !plugin.hasAdminBypass(placer.getUuid());
        plugin.setPlayerPlaced(world, x, y, z, isNormalPlayerPlacement);
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }
}
