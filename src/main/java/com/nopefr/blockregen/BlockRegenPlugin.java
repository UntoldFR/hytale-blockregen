package com.nopefr.blockregen;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.map.MapCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.Config;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Plugin principal.
 *
 * Stocke les "regles" enregistrees via /blockregen : pour chaque identifiant
 * de bloc (ex: "Stone"), le delai (en secondes) au bout duquel un bloc de ce
 * type doit se regenerer automatiquement apres avoir ete casse.
 *
 * Les regles sont persistees sur disque (voir {@link BlockRegenConfig}) via
 * le systeme de configuration natif du plugin, et rechargees automatiquement
 * au demarrage.
 */
public class BlockRegenPlugin extends JavaPlugin {

    private final Config<BlockRegenConfig> config = this.withConfig("BlockRegenRules", BlockRegenConfig.CODEC);

    // blockId (ex: "Stone") -> delai en secondes avant regeneration.
    // Meme instance de Map que BlockRegenConfig.rules : toute modification
    // est donc automatiquement reprise par config.save() (voir persist()).
    private Map<String, Integer> regenRules;

    // blockId -> le bloc a besoin d'un support (bloc non-air juste en dessous)
    // pour pouvoir regenerer. Absence d'entree = false. Meme principe de
    // partage d'instance avec BlockRegenConfig que regenRules.
    private Map<String, Boolean> needFloorRules;

    // blockId -> rayon (en blocs) dans lequel une position valide est tiree
    // au hasard pour la regeneration. Absence d'entree ou 0 = position exacte
    // du bloc casse.
    private Map<String, Integer> radiusRules;

    // Surcharges par zone CustomAreas (nom de l'area -> blockId -> valeur) :
    // une area taguee BLOCKREGEN herite des regles globales ci-dessus mais
    // peut ajouter/remplacer une regle pour un blockId donne. Voir
    // resolveEffectiveRule() pour la logique de resolution.
    private Map<String, Map<String, Integer>> areaDelayRules;
    private Map<String, Map<String, Boolean>> areaNeedFloorRules;
    private Map<String, Map<String, Integer>> areaRadiusRules;

    // Nom d'area -> true si elle ignore entierement les regles globales et
    // n'utilise que ses propres regles (voir resolveEffectiveRule()).
    private Map<String, Boolean> independentAreas;

    // Pont reflectif (optionnel) vers le plugin CustomAreas, voir CustomAreasBridge.
    private final CustomAreasBridge customAreasBridge = new CustomAreasBridge(this);

    // Positions (cle "<monde>@<x>,<y>,<z>") -> regeneration en attente,
    // persistees sur disque pour survivre a un redemarrage du serveur/monde
    // (voir persistPendingRegenRecord/removePendingRegenRecord et start()).
    private Map<String, PendingRegenRecord> pendingRegenRecords;

    // Garde une reference vers le systeme d'ecoute de casse de bloc pour
    // pouvoir lui demander de replanifier les regenerations persistees au
    // demarrage (voir start()).
    private BlockRegenListener blockRegenListener;

    // UUID du joueur -> demande de regle en attente de confirmation Y/N dans le chat
    private final Map<UUID, PendingRegen> pendingConfirmations = new ConcurrentHashMap<>();

    // Blocs actuellement casses en attente de regeneration, avec leur apercu
    // fantome (visible uniquement par les joueurs ayant la permission).
    private final List<PendingGhost> pendingGhosts = new CopyOnWriteArrayList<>();

    // Position (monde + coordonnees) du bloc casse -> regeneration en attente
    // a cette position, pour pouvoir l'annuler si un joueur pose un bloc au
    // meme endroit avant la fin du delai (voir cancelPendingRegenAt).
    private final Map<PositionKey, ActiveRegen> activeRegens = new ConcurrentHashMap<>();

    // Positions occupees par un bloc pose par un joueur "normal" (sans le
    // bypass admin) : si ce bloc est casse, aucune regeneration n'est
    // planifiee meme si une regle existe pour son type (anti-abus). Voir
    // BlockRegenPlaceListener et setPlayerPlaced/consumePlayerPlacedMark.
    private final Set<PositionKey> playerPlacedBlocks = ConcurrentHashMap.newKeySet();

    // UUID des joueurs ayant actuellement active le mode "admin" (/blockregen
    // admin) : les blocs qu'ils posent ne sont pas marques comme ci-dessus,
    // et pourront donc regenerer normalement s'ils sont casses plus tard.
    private final Set<UUID> adminBypassPlayers = ConcurrentHashMap.newKeySet();

    private ComponentType<EntityStore, BlockRegenGhostMarker> ghostMarkerComponentType;
    private String ghostPermission;

    public BlockRegenPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        // Charge les regles persistees (preLoad() a deja charge la config a ce stade).
        regenRules = config.get().rules;
        needFloorRules = config.get().needFloor;
        radiusRules = config.get().radius;
        areaDelayRules = config.get().areaDelay;
        areaNeedFloorRules = config.get().areaNeedFloor;
        areaRadiusRules = config.get().areaRadius;
        independentAreas = config.get().independentAreas;
        pendingRegenRecords = config.get().pendingRegens;

        // Commande /blockregen "Nom du block" 120 (inclut la sous-commande
        // /blockregen admin, voir BlockRegenAdminCommand)
        getCommandRegistry().registerCommand(new BlockRegenCommand(this));

        // Composant + systeme pour le marqueur fantome (sans collision) des
        // blocs en attente de regeneration, visible uniquement par les
        // joueurs ayant la permission dediee (voir getGhostPermission()).
        ghostMarkerComponentType = getEntityStoreRegistry()
            .registerComponent(BlockRegenGhostMarker.class, "BlockRegenGhostMarker", BlockRegenGhostMarker.CODEC);
        BlockRegenGhostMarker.setComponentType(ghostMarkerComponentType);
        ghostPermission = getBasePermission() + ".ghosts";
        PermissionsModule.registerPermission(ghostPermission);
        getEntityStoreRegistry().registerSystem(new BlockRegenGhostVisibilitySystem(
            EntityTrackerSystems.EntityViewer.getComponentType(), ghostMarkerComponentType, ghostPermission
        ));

        // Ecoute des evenements de casse de bloc (systeme ECS)
        blockRegenListener = new BlockRegenListener(this);
        getEntityStoreRegistry().registerSystem(blockRegenListener);

        // Ecoute des evenements de pose de bloc : annule la regeneration en
        // attente si un joueur pose un bloc a l'endroit d'un bloc casse.
        getEntityStoreRegistry().registerSystem(new BlockRegenPlaceListener(this));

        // Ecoute le chat pour recuperer les reponses Y/N de confirmation
        // (voir BlockRegenTargetCommand, utilise quand aucun bloc n'est precise).
        getEventRegistry().registerGlobal(PlayerChatEvent.class, this::handleChat);

        // Rafraichit le texte "<bloc>: regenerates in Xs" des marqueurs fantomes chaque seconde.
        @SuppressWarnings("unchecked")
        ScheduledFuture<Void> ghostRefreshTask = (ScheduledFuture<Void>) HytaleServer.SCHEDULED_EXECUTOR
            .scheduleAtFixedRate(this::refreshGhostNameplates, 1, 1, TimeUnit.SECONDS);
        getTaskRegistry().registerTask(ghostRefreshTask);

        // Enregistre le flag BLOCKREGEN aupres de CustomAreas s'il est present sur
        // le serveur (sans effet, sans erreur, si absent - voir CustomAreasBridge).
        customAreasBridge.ensureFlagRegistered();

        getLogger().at(Level.INFO).log("BlockRegen enabled (%d rule(s) loaded).", regenRules.size());
    }

    @Override
    protected void start() {
        // Replanifie les regenerations qui etaient en attente avant le
        // dernier arret du serveur/monde (voir persistPendingRegenRecord).
        // Un monde introuvable (pas encore charge a ce stade) est ignore :
        // cas rare pour le monde par defaut, limite connue et documentee.
        for (PendingRegenRecord record : new ArrayList<>(pendingRegenRecords.values())) {
            World world = Universe.get().getWorld(record.world());
            if (world == null) {
                getLogger().at(Level.FINE).log("Skipping restore of pending regen in unknown/unloaded world '%s'.", record.world());
                continue;
            }
            blockRegenListener.rescheduleFromPersistedRecord(world, record);
        }
    }

    @Override
    protected void shutdown() {
        getLogger().at(Level.INFO).log("BlockRegen disabled.");
    }

    /** Enregistre ou met a jour une regle de regeneration pour un type de bloc, et la persiste. */
    public void setRule(String blockId, int delaySeconds) {
        regenRules.put(blockId, delaySeconds);
        persist();
    }

    /** Supprime une regle de regeneration et persiste le changement. Retourne true si elle existait. */
    public boolean removeRule(String blockId) {
        boolean removed = regenRules.remove(blockId) != null;
        needFloorRules.remove(blockId);
        radiusRules.remove(blockId);
        if (removed) {
            persist();
        }
        return removed;
    }

    /** Retourne le delai (en secondes) configure pour ce blockId, ou null si aucune regle. */
    public Integer getDelayFor(String blockId) {
        return regenRules.get(blockId);
    }

    public Map<String, Integer> getRules() {
        return regenRules;
    }

    /** Active/desactive, pour ce blockId, l'obligation d'avoir un support avant de regenerer, et persiste. */
    public void setNeedFloor(String blockId, boolean needFloor) {
        if (needFloor) {
            needFloorRules.put(blockId, true);
        } else {
            needFloorRules.remove(blockId);
        }
        persist();
    }

    /** True si ce blockId doit verifier la presence d'un support avant de regenerer (false par defaut). */
    public boolean isNeedFloorFor(String blockId) {
        return needFloorRules.getOrDefault(blockId, false);
    }

    /** Definit, pour ce blockId, le rayon (en blocs) de recherche d'une position valide, et persiste. */
    public void setRadius(String blockId, int radius) {
        if (radius > 0) {
            radiusRules.put(blockId, radius);
        } else {
            radiusRules.remove(blockId);
        }
        persist();
    }

    /** Rayon (en blocs) configure pour ce blockId (0 par defaut = position exacte du bloc casse). */
    public int getRadiusFor(String blockId) {
        return radiusRules.getOrDefault(blockId, 0);
    }

    /** The reflective, optional bridge to the CustomAreas plugin (never null; check {@code isPresent()}). */
    @Nonnull
    public CustomAreasBridge getCustomAreasBridge() {
        return customAreasBridge;
    }

    /**
     * Resolves the rule that actually applies to this blockId at the given scope: {@code areaName}
     * null means "no BLOCKREGEN area at this position" and falls back to the global rule exactly like
     * before this feature existed. A non-null areaName looks up that area's own override first; if it
     * has none and isn't marked independent, falls back to the global rule; independent areas with no
     * own rule for this block resolve to null (no regeneration there), ignoring the global rule.
     */
    @Nullable
    public EffectiveRule resolveEffectiveRule(@Nullable String areaName, @Nonnull String blockId) {
        if (areaName != null) {
            EffectiveRule override = getAreaRule(areaName, blockId);
            if (override != null) {
                return override;
            }
            if (isAreaIndependent(areaName)) {
                return null;
            }
        }
        Integer delay = regenRules.get(blockId);
        if (delay == null) {
            return null;
        }
        return new EffectiveRule(delay, needFloorRules.getOrDefault(blockId, false), radiusRules.getOrDefault(blockId, 0));
    }

    /** This area's own explicit rule for this blockId, or null if it has none (falls back to global, unless independent). */
    @Nullable
    public EffectiveRule getAreaRule(@Nonnull String areaName, @Nonnull String blockId) {
        Integer delay = areaDelayRules.getOrDefault(areaName, Map.of()).get(blockId);
        if (delay == null) {
            return null;
        }
        boolean needFloor = areaNeedFloorRules.getOrDefault(areaName, Map.of()).getOrDefault(blockId, false);
        int radius = areaRadiusRules.getOrDefault(areaName, Map.of()).getOrDefault(blockId, 0);
        return new EffectiveRule(delay, needFloor, radius);
    }

    /** Block ids this area has its own explicit rule for (does not include inherited global block ids). */
    @Nonnull
    public Set<String> getAreaRuleBlockIds(@Nonnull String areaName) {
        return areaDelayRules.getOrDefault(areaName, Map.of()).keySet();
    }

    /** Sets (or updates) this area's own delay override for this blockId, and persists. */
    public void setAreaRule(@Nonnull String areaName, @Nonnull String blockId, int delaySeconds) {
        areaDelayRules.computeIfAbsent(areaName, k -> new ConcurrentHashMap<>()).put(blockId, delaySeconds);
        persist();
    }

    /** Sets this area's own "need floor" override for this blockId, and persists. */
    public void setAreaNeedFloor(@Nonnull String areaName, @Nonnull String blockId, boolean needFloor) {
        if (needFloor) {
            areaNeedFloorRules.computeIfAbsent(areaName, k -> new ConcurrentHashMap<>()).put(blockId, true);
        } else {
            areaNeedFloorRules.getOrDefault(areaName, Map.of()).remove(blockId);
        }
        persist();
    }

    /** Sets this area's own scatter radius override for this blockId, and persists. */
    public void setAreaRadius(@Nonnull String areaName, @Nonnull String blockId, int radius) {
        if (radius > 0) {
            areaRadiusRules.computeIfAbsent(areaName, k -> new ConcurrentHashMap<>()).put(blockId, radius);
        } else {
            areaRadiusRules.getOrDefault(areaName, Map.of()).remove(blockId);
        }
        persist();
    }

    /** Removes this area's own override for this blockId (it then falls back to inheriting global, unless independent). */
    public void removeAreaRule(@Nonnull String areaName, @Nonnull String blockId) {
        areaDelayRules.getOrDefault(areaName, Map.of()).remove(blockId);
        areaNeedFloorRules.getOrDefault(areaName, Map.of()).remove(blockId);
        areaRadiusRules.getOrDefault(areaName, Map.of()).remove(blockId);
        persist();
    }

    /** Toggles whether this area ignores global rules entirely (true = only its own rules apply), and persists. */
    public void setAreaIndependent(@Nonnull String areaName, boolean independent) {
        if (independent) {
            independentAreas.put(areaName, true);
        } else {
            independentAreas.remove(areaName);
        }
        persist();
    }

    public boolean isAreaIndependent(@Nonnull String areaName) {
        return independentAreas.getOrDefault(areaName, false);
    }

    /** Rule that actually applies for a given block/scope, resolved by {@link #resolveEffectiveRule}. */
    public record EffectiveRule(int delaySeconds, boolean needFloor, int radius) {
    }

    private void persist() {
        config.save();
    }

    /**
     * Enregistre la regeneration en attente a la position (monde + coordonnees)
     * du bloc casse, pour pouvoir l'annuler si un joueur pose un bloc au meme
     * endroit avant la fin du delai (voir {@link #cancelPendingRegenAt}).
     */
    public void trackActiveRegen(@Nonnull World world, int x, int y, int z, @Nonnull Future<?> future, @Nullable Ref<EntityStore> ghostRef) {
        activeRegens.put(new PositionKey(world, x, y, z), new ActiveRegen(future, ghostRef));
    }

    /** Arrete de suivre la regeneration en attente a cette position (elle vient de se produire, normalement). */
    public void untrackActiveRegen(@Nonnull World world, int x, int y, int z) {
        activeRegens.remove(new PositionKey(world, x, y, z));
    }

    /**
     * Enregistre sur disque la regeneration en attente a cette position, pour
     * qu'elle survive a un arret du serveur/monde (voir {@link #start()} qui
     * les recharge et les replanifie). Ecrit immediatement (pas seulement a
     * l'arret propre) pour supporter aussi un crash/coupure brutale.
     */
    public void persistPendingRegenRecord(
        @Nonnull World world, int x, int y, int z, @Nonnull String blockId, long respawnAtMillis, boolean needFloor, int radius
    ) {
        pendingRegenRecords.put(pendingRegenKey(world, x, y, z), new PendingRegenRecord(world.getName(), x, y, z, blockId, respawnAtMillis, needFloor, radius));
        persist();
    }

    /** Retire l'enregistrement persiste de regeneration en attente a cette position, s'il existe. */
    public void removePendingRegenRecord(@Nonnull World world, int x, int y, int z) {
        if (pendingRegenRecords.remove(pendingRegenKey(world, x, y, z)) != null) {
            persist();
        }
    }

    @Nonnull
    private static String pendingRegenKey(@Nonnull World world, int x, int y, int z) {
        return world.getName() + "@" + x + "," + y + "," + z;
    }

    /**
     * Annule la regeneration en attente a cette position, si il y en a une :
     * arrete la tache planifiee et retire son marqueur fantome. Appele quand
     * un joueur pose un bloc a cet endroit avant la fin du delai.
     *
     * Prend un CommandBuffer (et non le Store directement) car ceci est
     * appele depuis BlockRegenPlaceListener, en plein traitement de
     * PlaceBlockEvent : muter le Store directement a ce moment-la (comme le
     * faisait une version precedente) corrompt le traitement de l'evenement
     * en cours et provoquait la deconnexion du joueur qui vient de poser le
     * bloc. Meme regle deja documentee sur spawnGhost() dans BlockRegenListener.
     */
    public void cancelPendingRegenAt(@Nonnull World world, int x, int y, int z, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        ActiveRegen active = activeRegens.remove(new PositionKey(world, x, y, z));
        if (active == null) {
            return;
        }
        active.future().cancel(false);
        removeGhostEntity(commandBuffer, active.ghostRef());
        removePendingRegenRecord(world, x, y, z);
    }

    /**
     * Retire un marqueur fantome via un CommandBuffer, s'il existe encore.
     * A utiliser depuis un gestionnaire d'evenement ECS (voir cancelPendingRegenAt).
     */
    public void removeGhostEntity(@Nonnull CommandBuffer<EntityStore> commandBuffer, @Nullable Ref<EntityStore> ghostRef) {
        if (ghostRef == null) {
            return;
        }
        untrackPendingGhost(ghostRef);
        if (ghostRef.isValid()) {
            commandBuffer.removeEntity(ghostRef, RemoveReason.REMOVE);
        }
    }

    /**
     * Retire un marqueur fantome du monde directement via le Store, s'il
     * existe encore. Uniquement sur au thread du monde EN DEHORS d'un
     * gestionnaire d'evenement ECS (ex: depuis world.execute() a la fin
     * d'une regeneration planifiee) - voir removeGhostEntity(CommandBuffer, Ref)
     * pour l'equivalent utilisable depuis un gestionnaire d'evenement.
     */
    public void removeGhostEntity(@Nonnull World world, @Nullable Ref<EntityStore> ghostRef) {
        if (ghostRef == null) {
            return;
        }
        untrackPendingGhost(ghostRef);
        if (ghostRef.isValid()) {
            world.getEntityStore().getStore().removeEntity(ghostRef, RemoveReason.REMOVE);
        }
    }

    /**
     * Bascule le mode "admin bypass" de ce joueur (voir {@link #hasAdminBypass})
     * et retourne le nouvel etat (true = active).
     */
    public boolean toggleAdminBypass(@Nonnull UUID playerUuid) {
        if (!adminBypassPlayers.remove(playerUuid)) {
            adminBypassPlayers.add(playerUuid);
            return true;
        }
        return false;
    }

    /** True si ce joueur a actuellement le bypass admin actif (ses placements ne sont pas marques anti-abus). */
    public boolean hasAdminBypass(@Nonnull UUID playerUuid) {
        return adminBypassPlayers.contains(playerUuid);
    }

    /**
     * Marque (ou demarque) cette position comme occupee par un bloc pose par
     * un joueur normal. Appele a chaque pose de bloc par {@link BlockRegenPlaceListener}.
     */
    public void setPlayerPlaced(@Nonnull World world, int x, int y, int z, boolean placed) {
        PositionKey key = new PositionKey(world, x, y, z);
        if (placed) {
            playerPlacedBlocks.add(key);
        } else {
            playerPlacedBlocks.remove(key);
        }
    }

    /**
     * Retire et retourne le marquage "pose par un joueur" de cette position,
     * s'il existait. Appele a chaque casse de bloc, avant de decider si une
     * regeneration doit etre planifiee (anti-abus).
     */
    public boolean consumePlayerPlacedMark(@Nonnull World world, int x, int y, int z) {
        return playerPlacedBlocks.remove(new PositionKey(world, x, y, z));
    }

    /** Enregistre un bloc/delai en attente de confirmation Y/N par le joueur donne. */
    public void setPendingConfirmation(UUID playerUuid, String blockId, int delaySeconds) {
        pendingConfirmations.put(playerUuid, new PendingRegen(blockId, delaySeconds));
    }

    private void handleChat(PlayerChatEvent event) {
        PlayerRef sender = event.getSender();
        PendingRegen pending = pendingConfirmations.get(sender.getUuid());
        if (pending == null) {
            return; // pas de demande en attente pour ce joueur : chat normal
        }

        // Le message sert de reponse a la confirmation : il ne doit pas etre diffuse.
        event.setCancelled(true);

        String reply = event.getContent().trim();
        if (reply.equalsIgnoreCase("Y")) {
            pendingConfirmations.remove(sender.getUuid());
            setRule(pending.blockId(), pending.delaySeconds());
            sender.sendMessage(Message.translation(BlockRegenMessages.RULE_SET)
                .param("block", pending.blockId())
                .param("duration", DurationParser.formatSeconds(pending.delaySeconds())));
        } else if (reply.equalsIgnoreCase("N")) {
            pendingConfirmations.remove(sender.getUuid());
            sender.sendMessage(Message.translation(BlockRegenMessages.CANCELLED));
        } else {
            sender.sendMessage(Message.translation(BlockRegenMessages.CONFIRM_PROMPT));
        }
    }

    /** Permission node required to see the block regen ghost previews (auto-generated: base permission + ".ghosts"). */
    public String getGhostPermission() {
        return ghostPermission;
    }

    /** Registers a spawned ghost entity so its floating countdown text is refreshed until it regenerates. */
    public void trackPendingGhost(@Nonnull World world, @Nonnull Ref<EntityStore> ghostRef, @Nonnull String blockId, long respawnAtMillis) {
        pendingGhosts.add(new PendingGhost(world, ghostRef, blockId, respawnAtMillis));
    }

    /** Stops refreshing the given ghost entity (called once it is removed, whether regenerated or on shutdown). */
    public void untrackPendingGhost(@Nonnull Ref<EntityStore> ghostRef) {
        pendingGhosts.removeIf(pending -> pending.ghostRef().equals(ghostRef));
    }

    private void refreshGhostNameplates() {
        for (PendingGhost pending : pendingGhosts) {
            if (!pending.ghostRef().isValid() || !pending.world().isAlive()) {
                pendingGhosts.remove(pending);
                continue;
            }

            pending.world().execute(() -> {
                if (!pending.ghostRef().isValid()) {
                    return;
                }

                Store<EntityStore> store = pending.ghostRef().getStore();
                Nameplate nameplate = store.getComponent(pending.ghostRef(), Nameplate.getComponentType());
                if (nameplate != null) {
                    long remainingMillis = pending.respawnAtMillis() - System.currentTimeMillis();
                    int remainingSeconds = (int) Math.max(0, remainingMillis / 1000);
                    nameplate.setText(Message.translation(BlockRegenMessages.GHOST_NAMEPLATE)
                        .param("block", pending.blockId())
                        .param("duration", DurationParser.formatSeconds(remainingSeconds))
                        .getAnsiMessage());
                }
            });
        }
    }

    private record PendingRegen(String blockId, int delaySeconds) {
    }

    private record PendingGhost(World world, Ref<EntityStore> ghostRef, String blockId, long respawnAtMillis) {
    }

    /** Identifie une position de bloc dans un monde donne (identite du World par reference). */
    private record PositionKey(World world, int x, int y, int z) {
    }

    /** Regeneration en attente a une position : tache planifiee + marqueur fantome associe. */
    private record ActiveRegen(Future<?> future, @Nullable Ref<EntityStore> ghostRef) {
    }

    /**
     * Version persistee sur disque d'une regeneration en attente : suffisant
     * pour la replanifier telle quelle au demarrage (voir
     * BlockRegenListener#rescheduleFromPersistedRecord), sans avoir besoin de
     * re-resoudre une eventuelle regle CustomAreas (deja resolue au moment
     * ou la regeneration a ete planifiee la premiere fois).
     */
    public static class PendingRegenRecord {
        @Nonnull
        public static final BuilderCodec<PendingRegenRecord> CODEC = BuilderCodec.builder(PendingRegenRecord.class, PendingRegenRecord::new)
            .append(new KeyedCodec<>("World", Codec.STRING), (r, s) -> r.world = s, r -> r.world).add()
            .append(new KeyedCodec<>("X", Codec.INTEGER), (r, i) -> r.x = i, r -> r.x).add()
            .append(new KeyedCodec<>("Y", Codec.INTEGER), (r, i) -> r.y = i, r -> r.y).add()
            .append(new KeyedCodec<>("Z", Codec.INTEGER), (r, i) -> r.z = i, r -> r.z).add()
            .append(new KeyedCodec<>("BlockId", Codec.STRING), (r, s) -> r.blockId = s, r -> r.blockId).add()
            .append(new KeyedCodec<>("RespawnAtMillis", Codec.LONG), (r, l) -> r.respawnAtMillis = l, r -> r.respawnAtMillis).add()
            .append(new KeyedCodec<>("NeedFloor", Codec.BOOLEAN), (r, b) -> r.needFloor = b, r -> r.needFloor).add()
            .append(new KeyedCodec<>("Radius", Codec.INTEGER), (r, i) -> r.radius = i, r -> r.radius).add()
            .build();

        private String world;
        private int x;
        private int y;
        private int z;
        private String blockId;
        private long respawnAtMillis;
        private boolean needFloor;
        private int radius;

        public PendingRegenRecord() {
        }

        public PendingRegenRecord(String world, int x, int y, int z, String blockId, long respawnAtMillis, boolean needFloor, int radius) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.blockId = blockId;
            this.respawnAtMillis = respawnAtMillis;
            this.needFloor = needFloor;
            this.radius = radius;
        }

        public String world() {
            return world;
        }

        public int x() {
            return x;
        }

        public int y() {
            return y;
        }

        public int z() {
            return z;
        }

        public String blockId() {
            return blockId;
        }

        public long respawnAtMillis() {
            return respawnAtMillis;
        }

        public boolean needFloor() {
            return needFloor;
        }

        public int radius() {
            return radius;
        }
    }

    /** Configuration persistee sur disque (JSON) contenant les regles de regeneration. */
    public static class BlockRegenConfig {
        @Nonnull
        public static final BuilderCodec<BlockRegenConfig> CODEC = BuilderCodec.builder(BlockRegenConfig.class, BlockRegenConfig::new)
            .append(new KeyedCodec<>("Rules", new MapCodec<Integer, Map<String, Integer>>(Codec.INTEGER, ConcurrentHashMap::new, false)), (c, m) -> c.rules = m, c -> c.rules)
            .add()
            // Cles optionnelles ajoutees ulterieurement : une entree absente
            // (ou un fichier de config existant qui ne les connait pas encore)
            // laisse simplement la map par defaut (vide) ci-dessous, donc
            // aucune migration n'est necessaire pour les regles existantes.
            .append(new KeyedCodec<>("NeedFloor", new MapCodec<Boolean, Map<String, Boolean>>(Codec.BOOLEAN, ConcurrentHashMap::new, false), false), (c, m) -> c.needFloor = m, c -> c.needFloor)
            .add()
            .append(new KeyedCodec<>("Radius", new MapCodec<Integer, Map<String, Integer>>(Codec.INTEGER, ConcurrentHashMap::new, false), false), (c, m) -> c.radius = m, c -> c.radius)
            .add()
            // Per-CustomAreas-area overrides (nom d'area -> blockId -> valeur),
            // et zones marquees "independantes". Cles optionnelles : absentes
            // sur un config existant, elles restent simplement des maps vides.
            .append(new KeyedCodec<>("AreaDelay", new MapCodec<Map<String, Integer>, Map<String, Map<String, Integer>>>(
                new MapCodec<Integer, Map<String, Integer>>(Codec.INTEGER, ConcurrentHashMap::new, false), ConcurrentHashMap::new, false), false),
                (c, m) -> c.areaDelay = m, c -> c.areaDelay)
            .add()
            .append(new KeyedCodec<>("AreaNeedFloor", new MapCodec<Map<String, Boolean>, Map<String, Map<String, Boolean>>>(
                new MapCodec<Boolean, Map<String, Boolean>>(Codec.BOOLEAN, ConcurrentHashMap::new, false), ConcurrentHashMap::new, false), false),
                (c, m) -> c.areaNeedFloor = m, c -> c.areaNeedFloor)
            .add()
            .append(new KeyedCodec<>("AreaRadius", new MapCodec<Map<String, Integer>, Map<String, Map<String, Integer>>>(
                new MapCodec<Integer, Map<String, Integer>>(Codec.INTEGER, ConcurrentHashMap::new, false), ConcurrentHashMap::new, false), false),
                (c, m) -> c.areaRadius = m, c -> c.areaRadius)
            .add()
            .append(new KeyedCodec<>("IndependentAreas", new MapCodec<Boolean, Map<String, Boolean>>(Codec.BOOLEAN, ConcurrentHashMap::new, false), false),
                (c, m) -> c.independentAreas = m, c -> c.independentAreas)
            .add()
            // Regenerations en attente au moment de la sauvegarde (voir
            // PendingRegenRecord) : permet de les reprendre au demarrage
            // suivant au lieu de les perdre silencieusement.
            .append(new KeyedCodec<>("PendingRegens", new MapCodec<PendingRegenRecord, Map<String, PendingRegenRecord>>(
                PendingRegenRecord.CODEC, ConcurrentHashMap::new, false), false),
                (c, m) -> c.pendingRegens = m, c -> c.pendingRegens)
            .add()
            .build();

        private Map<String, Integer> rules = new ConcurrentHashMap<>();
        private Map<String, Boolean> needFloor = new ConcurrentHashMap<>();
        private Map<String, Integer> radius = new ConcurrentHashMap<>();
        private Map<String, Map<String, Integer>> areaDelay = new ConcurrentHashMap<>();
        private Map<String, Map<String, Boolean>> areaNeedFloor = new ConcurrentHashMap<>();
        private Map<String, Map<String, Integer>> areaRadius = new ConcurrentHashMap<>();
        private Map<String, Boolean> independentAreas = new ConcurrentHashMap<>();
        private Map<String, PendingRegenRecord> pendingRegens = new ConcurrentHashMap<>();
    }
}
