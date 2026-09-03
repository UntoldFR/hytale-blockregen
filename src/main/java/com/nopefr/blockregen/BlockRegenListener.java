package com.nopefr.blockregen;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.packets.interface_.BlockChange;
import com.hypixel.hytale.protocol.packets.interface_.FluidChange;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.asset.type.model.config.ModelAsset;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.event.events.ecs.BreakBlockEvent;
import com.hypixel.hytale.server.core.modules.entity.component.Intangible;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.PersistentModel;
import com.hypixel.hytale.server.core.modules.entity.component.PrefabPreview;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.HytaleServer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.joml.Vector3d;
import org.joml.Vector3i;

/**
 * A chaque casse de bloc, verifie si le type de bloc casse a une regle
 * de regeneration active (enregistree via /blockregen). Si oui, planifie
 * la restauration du bloc a la meme position apres le delai configure et
 * (si un joueur a la permission dediee) fait apparaitre un petit marqueur
 * flottant, sans collision, avec le temps restant affiche au-dessus.
 */
public class BlockRegenListener extends EntityEventSystem<EntityStore, BreakBlockEvent> {

    // Modele generique et leger deja utilise par le jeu pour ses propres
    // marqueurs de developpement (voir AmbiencePlugin) : pas besoin d'un
    // modele specifique au bloc, qui n'existe pas forcement. Sert uniquement
    // de point d'ancrage pour le Nameplate (le modele lui-meme est discret).
    private static final String MARKER_MODEL_ID = "NPC_Spawn_Marker";

    // Bloc-outil de l'editeur, transparent par design (voir sa definition :
    // "Opacity": "Transparent"), utilise ici pour donner une vraie forme
    // transparente au fantome a la place du bloc reel qui va regenerer.
    private static final String GHOST_BLOCK_ID = "Editor_Empty";

    private static final int DEFAULT_BIOME_TINT = 0x5B9F28;
    private static final int DEFAULT_WATER_TINT = 0x0A34D5;

    // Nombre de positions aleatoires essayees dans le rayon configure avant
    // d'abandonner et de retomber sur la position d'origine du bloc casse.
    private static final int SCATTER_ATTEMPTS = 12;

    private final BlockRegenPlugin plugin;

    public BlockRegenListener(BlockRegenPlugin plugin) {
        super(BreakBlockEvent.class);
        this.plugin = plugin;
    }

    @Override
    public void handle(int i,
                        @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                        @Nonnull Store<EntityStore> store,
                        @Nonnull CommandBuffer<EntityStore> commandBuffer,
                        @Nonnull BreakBlockEvent event) {

        BlockType brokenType = event.getBlockType();
        if (brokenType == null || brokenType == BlockType.EMPTY) {
            return; // pas de bloc (air) : rien a faire
        }

        World world = store.getExternalData().getWorld();
        Vector3i targetBlock = event.getTargetBlock();
        int x = targetBlock.x();
        int y = targetBlock.y();
        int z = targetBlock.z();

        // Consomme le marquage "pose par un joueur" dans tous les cas (meme
        // sans regle active pour ce type de bloc) pour ne pas le laisser
        // trainer indefiniment a cette position.
        boolean wasPlayerPlaced = plugin.consumePlayerPlacedMark(world, x, y, z);

        Integer delaySeconds = plugin.getDelayFor(brokenType.getId());
        if (delaySeconds == null) {
            return; // aucune regle enregistree pour ce type de bloc
        }

        if (wasPlayerPlaced) {
            return; // anti-abus : bloc pose par un joueur (sans bypass admin), pas de regeneration
        }

        long respawnAtMillis = System.currentTimeMillis() + delaySeconds * 1000L;
        Ref<EntityStore> ghostRef = spawnGhost(store, commandBuffer, x, y, z, brokenType, delaySeconds);
        if (ghostRef != null) {
            plugin.trackPendingGhost(world, ghostRef, brokenType.getId(), respawnAtMillis);
        }

        boolean needFloor = plugin.isNeedFloorFor(brokenType.getId());
        int radius = plugin.getRadiusFor(brokenType.getId());
        scheduleRegen(world, x, y, z, brokenType, delaySeconds, needFloor, radius, ghostRef);
    }

    /**
     * Spawns a small, non-solid marker entity at the broken block's position with a floating countdown nameplate,
     * visible only to permitted players. Must go through the CommandBuffer (not Store directly): this runs from
     * within an ECS event handler, while the Store is mid-processing, and Store's own mutation methods refuse to
     * be called at that point.
     */
    @Nullable
    private Ref<EntityStore> spawnGhost(
        @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer, int x, int y, int z, @Nonnull BlockType blockType, int delaySeconds
    ) {
        Holder<EntityStore> holder = store.getRegistry().newHolder();
        holder.addComponent(NetworkId.getComponentType(), new NetworkId(store.getExternalData().takeNextNetworkId()));
        // Position au coin du bloc (pas centree) : BlockChange(0,0,0,...) rend
        // un cube d'un bloc a partir de l'origine locale de ce TransformComponent.
        holder.addComponent(TransformComponent.getComponentType(), new TransformComponent(new Vector3d(x, y, z), new Rotation3f()));

        // Forme du fantome : le bloc transparent de l'editeur, positionne en
        // local (0,0,0) puisque BlockChange est relatif au TransformComponent.
        int ghostBlockNumericId = BlockType.getAssetMap().getIndex(GHOST_BLOCK_ID);
        if (ghostBlockNumericId >= 0) {
            BlockChange[] blocks = {new BlockChange(0, 0, 0, ghostBlockNumericId, (byte) 0)};
            PrefabPreview preview = new PrefabPreview(blocks, new FluidChange[0], Integer.MAX_VALUE, DEFAULT_BIOME_TINT, DEFAULT_WATER_TINT);
            holder.addComponent(PrefabPreview.getComponentType(), preview);
        }

        // Un Nameplate a besoin d'un ModelComponent sur la meme entite pour
        // avoir un point d'ancrage cote client (confirme via plusieurs
        // marqueurs natifs du jeu, ex. ObjectiveLocationMarkerCommand).
        ModelAsset modelAsset = ModelAsset.getAssetMap().getAsset(MARKER_MODEL_ID);
        if (modelAsset != null) {
            Model model = Model.createUnitScaleModel(modelAsset);
            holder.addComponent(ModelComponent.getComponentType(), new ModelComponent(model));
            holder.addComponent(PersistentModel.getComponentType(), new PersistentModel(model.toReference()));
        }

        holder.addComponent(Nameplate.getComponentType(), new Nameplate(Message.translation(BlockRegenMessages.GHOST_NAMEPLATE)
            .param("block", blockType.getId())
            .param("duration", DurationParser.formatSeconds(delaySeconds))
            .getAnsiMessage()));
        holder.addComponent(BlockRegenGhostMarker.getComponentType(), BlockRegenGhostMarker.INSTANCE);
        holder.ensureComponent(UUIDComponent.getComponentType());
        holder.ensureComponent(Intangible.getComponentType());

        return commandBuffer.addEntity(holder, AddReason.SPAWN);
    }

    private void scheduleRegen(
        World world, int x, int y, int z, BlockType blockType, int delaySeconds, boolean needFloor, int radius, @Nullable Ref<EntityStore> ghostRef
    ) {
        @SuppressWarnings("unchecked")
        ScheduledFuture<Void> future = (ScheduledFuture<Void>) HytaleServer.SCHEDULED_EXECUTOR.schedule(
            () -> {
                // Le scheduler global tourne sur son propre thread : toute
                // modification du monde doit repasser par le thread du monde.
                if (world == null || !world.isAlive()) {
                    return;
                }
                world.execute(() -> {
                    try {
                        Vector3i target = pickRegenPosition(world, x, y, z, radius, needFloor);
                        if (target != null) {
                            WorldChunk chunk = world.getChunk(ChunkUtil.indexChunkFromBlock(target.x(), target.z()));
                            if (chunk != null) {
                                chunk.setBlock(target.x(), target.y(), target.z(), blockType);
                            }
                        }
                        // target == null : aucune position valide trouvee
                        // (occupee, ou pas de support avec "need floor") ;
                        // ce cycle de regeneration est simplement abandonne.
                    } catch (Exception e) {
                        plugin.getLogger().at(Level.WARNING)
                            .log("Failed to regenerate block at (" + x + "," + y + "," + z + "): " + e.getMessage());
                    } finally {
                        plugin.untrackActiveRegen(world, x, y, z);
                        removeGhost(world, ghostRef);
                    }
                });
            },
            delaySeconds,
            TimeUnit.SECONDS
        );

        plugin.trackActiveRegen(world, x, y, z, future, ghostRef);

        // Enregistre la tache pour qu'elle soit annulee automatiquement
        // si le plugin est desactive avant la fin du delai.
        plugin.getTaskRegistry().registerTask(future);
    }

    /**
     * Determine ou faire regenerer le bloc : si un rayon est configure,
     * essaie plusieurs positions aleatoires alentour (meme hauteur) et
     * retient la premiere valide ; sinon (ou si aucune n'est valide),
     * retombe sur la position d'origine. Retourne null si meme celle-ci
     * n'est pas valide (deja occupee, ou pas de support avec "need floor").
     */
    @Nullable
    private static Vector3i pickRegenPosition(World world, int x, int y, int z, int radius, boolean needFloor) {
        if (radius > 0) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            for (int attempt = 0; attempt < SCATTER_ATTEMPTS; attempt++) {
                int candidateX = x + random.nextInt(-radius, radius + 1);
                int candidateZ = z + random.nextInt(-radius, radius + 1);
                if (isValidRegenSpot(world, candidateX, y, candidateZ, needFloor)) {
                    return new Vector3i(candidateX, y, candidateZ);
                }
            }
        }
        return isValidRegenSpot(world, x, y, z, needFloor) ? new Vector3i(x, y, z) : null;
    }

    /** Une position est valide si elle est actuellement vide (on n'ecrase jamais un bloc existant) et, si demande, supportee. */
    private static boolean isValidRegenSpot(World world, int x, int y, int z, boolean needFloor) {
        WorldChunk chunk = world.getChunk(ChunkUtil.indexChunkFromBlock(x, z));
        if (chunk == null) {
            return false;
        }
        BlockType current = chunk.getBlockType(x, y, z);
        if (current != null && current != BlockType.EMPTY) {
            return false;
        }
        return !needFloor || hasFloor(chunk, x, y, z);
    }

    /** True si le bloc juste en dessous de (x,y,z) dans ce chunk existe et n'est pas de l'air. */
    private static boolean hasFloor(WorldChunk chunk, int x, int y, int z) {
        if (y <= 0) {
            return false;
        }
        BlockType below = chunk.getBlockType(x, y - 1, z);
        return below != null && below != BlockType.EMPTY;
    }

    private void removeGhost(@Nonnull World world, @Nullable Ref<EntityStore> ghostRef) {
        plugin.removeGhostEntity(world, ghostRef);
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }
}
