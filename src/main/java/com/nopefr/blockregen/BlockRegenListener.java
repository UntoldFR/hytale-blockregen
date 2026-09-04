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
import java.util.function.Function;
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

        // Si une area CustomAreas taguee BLOCKREGEN couvre cette position, ses
        // regles (heritees des globales, ou propres si elle en a) s'appliquent
        // a la place ; sinon (ou si CustomAreas n'est pas installe) on retombe
        // sur les regles globales, comportement inchange.
        String areaName = plugin.getCustomAreasBridge().findBlockRegenAreaAt(world.getName(), x, y, z);
        BlockRegenPlugin.EffectiveRule rule = plugin.resolveEffectiveRule(areaName, brokenType.getId());
        if (rule == null) {
            return; // aucune regle applicable pour ce type de bloc a cette position
        }

        if (wasPlayerPlaced) {
            return; // anti-abus : bloc pose par un joueur (sans bypass admin), pas de regeneration
        }

        int delaySeconds = rule.delaySeconds();
        long respawnAtMillis = System.currentTimeMillis() + delaySeconds * 1000L;
        GhostEntities ghosts = spawnGhost(store, holder -> commandBuffer.addEntity(holder, AddReason.SPAWN), x, y, z, brokenType, delaySeconds);
        if (ghosts != null) {
            plugin.trackPendingGhost(world, ghosts.nameplateRef(), brokenType.getId(), respawnAtMillis);
        }

        scheduleRegen(world, x, y, z, brokenType, delaySeconds, respawnAtMillis, rule.needFloor(), rule.radius(), rule.regrowth(), ghosts);
    }

    /**
     * Replanifie une regeneration qui etait en attente avant le dernier arret
     * du serveur/monde (voir BlockRegenPlugin#persistPendingRegenRecord et
     * #start()). Appele depuis le thread du monde (voir world.execute() plus
     * bas), donc PAS dans un gestionnaire d'evenement ECS : le fantome est
     * donc ajoute directement via le Store (pas de CommandBuffer disponible
     * ni necessaire ici).
     */
    public void rescheduleFromPersistedRecord(@Nonnull World world, @Nonnull BlockRegenPlugin.PendingRegenRecord record) {
        if (!world.isAlive()) {
            return;
        }
        world.execute(() -> {
            BlockType blockType = BlockType.fromString(record.blockId());
            if (blockType == null || blockType == BlockType.EMPTY) {
                // Ce type de bloc n'existe plus (mod retire, id invalide...) : rien a
                // restaurer, mais on nettoie l'enregistrement pour ne pas le retenter indefiniment.
                plugin.removePendingRegenRecord(world, record.x(), record.y(), record.z());
                return;
            }

            // Si un bloc occupe deja cette position (ex: le monde a rechargee avec le
            // bloc casse jamais ecrit sur disque avant l'arret), la regeneration n'a
            // plus lieu d'etre : on nettoie simplement l'enregistrement, sans fantome
            // ni planification - sinon on se retrouve avec un fantome fige flottant
            // au-dessus d'un bloc deja bien present.
            WorldChunk existingChunk = world.getChunk(ChunkUtil.indexChunkFromBlock(record.x(), record.z()));
            BlockType existing = existingChunk != null ? existingChunk.getBlockType(record.x(), record.y(), record.z()) : null;
            if (existing != null && existing != BlockType.EMPTY) {
                plugin.removePendingRegenRecord(world, record.x(), record.y(), record.z());
                return;
            }

            int delaySeconds = (int) Math.max(0, (record.respawnAtMillis() - System.currentTimeMillis()) / 1000);
            Store<EntityStore> store = world.getEntityStore().getStore();
            GhostEntities ghosts = spawnGhost(store, holder -> store.addEntity(holder, AddReason.SPAWN), record.x(), record.y(), record.z(), blockType, delaySeconds);
            if (ghosts != null) {
                plugin.trackPendingGhost(world, ghosts.nameplateRef(), record.blockId(), record.respawnAtMillis());
            }

            scheduleRegen(world, record.x(), record.y(), record.z(), blockType, delaySeconds, record.respawnAtMillis(), record.needFloor(), record.radius(), record.regrowth(), ghosts);
        });
    }

    // Decalage vertical (en blocs) de l'ancre du nameplate par rapport au coin
    // bas du bloc : NPC_Spawn_Marker est dimensionne pour un PNJ complet, donc
    // sans ce decalage vers le bas le texte flotte environ 1 bloc trop haut
    // au-dessus d'un fantome qui ne fait qu'1 bloc de haut.
    private static final double NAMEPLATE_Y_OFFSET = -1.0;

    /**
     * Holds the two entities that make up one ghost preview:
     * <ul>
     *   <li>{@code blockRef} - the block-shaped, collision-free visual (PrefabPreview), anchored exactly at the
     *       block's corner so it renders in the right grid cell (BlockChange only takes integer local offsets, so
     *       this entity's transform can't be shifted without breaking the cube's position);</li>
     *   <li>{@code nameplateRef} - a separate, invisible marker centered on the block (and shifted down, see
     *       {@link #NAMEPLATE_Y_OFFSET}) purely to anchor the floating countdown Nameplate somewhere better than
     *       the block's corner.</li>
     * </ul>
     * Both carry {@link BlockRegenGhostMarker}, so {@link BlockRegenGhostVisibilitySystem} hides both uniformly
     * for players without the ghost permission.
     */
    public record GhostEntities(@Nonnull Ref<EntityStore> blockRef, @Nonnull Ref<EntityStore> nameplateRef) {
    }

    /**
     * Spawns the two entities of one ghost preview (see {@link GhostEntities}) at the broken block's position.
     * Entities are added via {@code adder} rather than a hardcoded Store/CommandBuffer call: from within an ECS
     * event handler (a fresh break), the Store is mid-processing and mutations must go through the CommandBuffer
     * instead; from a deferred world.execute() callback (restoring a persisted regen after a restart, no ECS event
     * in progress), adding directly via the Store is required instead since there's no CommandBuffer available
     * there. Both shapes are {@code Holder -> Ref}, see the two call sites.
     */
    @Nullable
    private GhostEntities spawnGhost(
        @Nonnull Store<EntityStore> store, @Nonnull Function<Holder<EntityStore>, Ref<EntityStore>> adder, int x, int y, int z, @Nonnull BlockType blockType, int delaySeconds
    ) {
        Ref<EntityStore> blockRef = spawnGhostBlock(store, adder, x, y, z);
        Ref<EntityStore> nameplateRef = spawnGhostNameplate(store, adder, x, y, z, blockType, delaySeconds);
        if (blockRef == null || nameplateRef == null) {
            return null;
        }
        return new GhostEntities(blockRef, nameplateRef);
    }

    /** The block-shaped, collision-free preview itself - see {@link GhostEntities}. */
    @Nullable
    private Ref<EntityStore> spawnGhostBlock(
        @Nonnull Store<EntityStore> store, @Nonnull Function<Holder<EntityStore>, Ref<EntityStore>> adder, int x, int y, int z
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

        holder.addComponent(BlockRegenGhostMarker.getComponentType(), BlockRegenGhostMarker.INSTANCE);
        holder.ensureComponent(UUIDComponent.getComponentType());
        holder.ensureComponent(Intangible.getComponentType());

        return adder.apply(holder);
    }

    /**
     * The invisible marker entity that anchors the floating countdown Nameplate, centered horizontally on the
     * block and shifted down (see {@link #NAMEPLATE_Y_OFFSET}) - kept separate from the block visual above since
     * a Nameplate always floats a fixed height above its anchor's own model (here NPC_Spawn_Marker, sized for a
     * full NPC) with no per-instance override, so shifting where we place this anchor is the only lever we have.
     */
    @Nullable
    private Ref<EntityStore> spawnGhostNameplate(
        @Nonnull Store<EntityStore> store, @Nonnull Function<Holder<EntityStore>, Ref<EntityStore>> adder, int x, int y, int z, @Nonnull BlockType blockType, int delaySeconds
    ) {
        Holder<EntityStore> holder = store.getRegistry().newHolder();
        holder.addComponent(NetworkId.getComponentType(), new NetworkId(store.getExternalData().takeNextNetworkId()));
        holder.addComponent(TransformComponent.getComponentType(), new TransformComponent(
            new Vector3d(x + 0.5, y + NAMEPLATE_Y_OFFSET, z + 0.5), new Rotation3f()
        ));

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

        return adder.apply(holder);
    }

    private void scheduleRegen(
        World world, int x, int y, int z, BlockType blockType, int delaySeconds, long respawnAtMillis, boolean needFloor, int radius, boolean regrowth,
        @Nullable GhostEntities ghosts
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
                                BlockType toPlace = blockType;
                                if (regrowth) {
                                    BlockType sapling = resolveSapling(blockType.getId());
                                    if (sapling != null && hasGrowableFloor(chunk, target.x(), target.y(), target.z())) {
                                        toPlace = sapling;
                                    }
                                    // Pas d'espece de jeune pousse correspondante, ou pas de sol
                                    // cultivable : on retombe simplement sur le rondin normal.
                                }
                                chunk.setBlock(target.x(), target.y(), target.z(), toPlace);
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
                        plugin.removePendingRegenRecord(world, x, y, z);
                        removeGhost(world, ghosts);
                    }
                });
            },
            delaySeconds,
            TimeUnit.SECONDS
        );

        plugin.trackActiveRegen(world, x, y, z, future, ghosts);
        // Persiste sur disque pour survivre a un arret du serveur/monde avant
        // que le delai soit ecoule (voir BlockRegenPlugin#start()).
        plugin.persistPendingRegenRecord(world, x, y, z, blockType.getId(), respawnAtMillis, needFloor, radius, regrowth);

        // Enregistre la tache pour qu'elle soit annulee automatiquement
        // si le plugin est desactive avant la fin du delai.
        plugin.getTaskRegistry().registerTask(future);
    }

    // Blocs de troncs suivent le schema "Wood_<Espece>_Trunc..." et les jeunes
    // pousses correspondantes "Plant_Sapling_<Espece>" - la chaine d'espece
    // est identique entre les deux, donc pas besoin de table de correspondance
    // manuelle : simple transformation de texte, verifiee contre le vrai
    // registre de blocs du jeu (BlockType.fromString). Retourne null si le
    // bloc casse n'est pas un tronc reconnu ou si son espece n'a pas de jeune
    // pousse correspondante (ex: "Wood_Fir_Trunk" n'en a pas) - dans ce cas
    // la regeneration retombe simplement sur le rondin normal.
    private static final String LOG_PREFIX = "Wood_";
    private static final String LOG_MARKER = "_Trunk";
    private static final String SAPLING_PREFIX = "Plant_Sapling_";

    @Nullable
    private static BlockType resolveSapling(@Nonnull String logBlockId) {
        if (!logBlockId.startsWith(LOG_PREFIX)) {
            return null;
        }
        int markerIndex = logBlockId.indexOf(LOG_MARKER, LOG_PREFIX.length());
        if (markerIndex < 0) {
            return null;
        }
        String species = logBlockId.substring(LOG_PREFIX.length(), markerIndex);
        return BlockType.fromString(SAPLING_PREFIX + species);
    }

    // Tous les variants de sol "herbe" du jeu partagent ce prefixe (ex:
    // Soil_Grass, Soil_Grass_Dry, Soil_Grass_Wet_Full...) - un sol simplement
    // "Soil_Dirt" (sans herbe) ne compte pas comme cultivable pour l'instant.
    private static final String GROWABLE_FLOOR_PREFIX = "Soil_Grass";

    /** True si le bloc juste en dessous de (x,y,z) est un sol "cultivable" (herbe) pour y planter une jeune pousse. */
    private static boolean hasGrowableFloor(@Nonnull WorldChunk chunk, int x, int y, int z) {
        if (y <= 0) {
            return false;
        }
        BlockType below = chunk.getBlockType(x, y - 1, z);
        return below != null && below.getId() != null && below.getId().startsWith(GROWABLE_FLOOR_PREFIX);
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

    private void removeGhost(@Nonnull World world, @Nullable GhostEntities ghosts) {
        plugin.removeGhostEntity(world, ghosts);
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return Archetype.empty();
    }
}
