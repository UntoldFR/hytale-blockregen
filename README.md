# BlockRegen (plugin Hytale)

Plugin serveur Hytale qui permet, via la commande `/blockregen`, de faire
regenerer automatiquement un type de bloc apres un delai une fois qu'il a
ete casse.

Compatible Hytale `>=0.6.3 <0.7.0`.

## Usage en jeu

```
/blockregen "Stone" 120
```
-> Tout bloc "Stone" casse reapparait 120 secondes plus tard (120 = minutes
par defaut quand aucune unite n'est precisee -> ici 120 minutes).

```
/blockregen "Stone" 90s
/blockregen "Stone" 5m
/blockregen "Stone" 2h
```
-> Meme chose, avec un delai exprime explicitement en secondes (`s`),
minutes (`m`) ou heures (`h`).

```
/blockregen "Stone" 0 --remove
```
-> Supprime la regle de regeneration pour "Stone".

```
/blockregen list
```
-> Affiche la liste des blocs actuellement configures pour se regenerer,
avec le delai associe a chacun.

```
/blockregen 90s
```
-> Si aucun nom de bloc n'est precise (uniquement un delai), le plugin
recupere le bloc actuellement vise par le joueur (a 10 blocs max) et
demande confirmation dans le chat :
`Confirm setting a regeneration delay of 90s on block "Stone"? Reply Y to confirm or N to cancel.`
Le joueur doit repondre `Y` (oui) ou `N` (non) dans le chat pour valider ou
annuler. Cette variante necessite d'etre execute par un joueur (pas la
console).

Le nom du bloc doit correspondre a l'identifiant exact utilise par le jeu
(ex: `Stone`, `Dirt`, `MyPlugin_MossyBlock`...). Si le nom est invalide, la
commande renvoie une erreur.

### Anti-abus : les blocs poses par un joueur ne regenerent pas

Par defaut, si un joueur pose un bloc dont le type a une regle de
regeneration active, ce bloc ne sera jamais planifie pour regenerer s'il est
ensuite casse (par n'importe qui) - meme si une regle existe pour son type.
Ca evite qu'un joueur pose puis recasse en boucle un bloc regle pour le
farmer gratuitement. Les blocs "naturels" (deja presents dans le monde,
generes normalement) ne sont pas concernes et regenerent comme d'habitude.

```
/blockregen admin
```
-> Bascule, pour le joueur qui execute la commande, un mode "bypass" : tant
qu'il est actif, les blocs qu'il pose restent eligibles a la regeneration
(utile pour un admin qui veut reconstituer un filon de minerai ou une deco).
Le nouvel etat est confirme en jaune dans le chat et via une notification en
haut de l'ecran. Necessite la permission generee automatiquement
`<basePermission>.command.blockregen.admin`.

### Apercu fantome des blocs en attente de regeneration

Des qu'un bloc regle est casse, un apercu transparent et sans collision du
bloc a venir apparait a son emplacement, avec un texte flottant au-dessus
indiquant le temps restant ("Regenerates in 45s"). Cet apercu n'est visible
que par les joueurs ayant la permission `<basePermission>.ghosts` (ex:
`com.nopefr.blockregen.ghosts`) - les autres joueurs voient simplement
l'emplacement vide, comme d'habitude. Donne cette permission a ton groupe
admin/OP pour en profiter.

## Pre-requis

- Java 25 (Adoptium/Temurin recommande)
- IntelliJ IDEA
- `HytaleServer.jar` (fourni automatiquement via le plugin Gradle
  `com.azuredoom.hytale-tools` utilise dans `build.gradle.kts`)

## Mise en route dans IntelliJ

1. Ouvre ce dossier comme projet Gradle dans IntelliJ (`File > Open`).
2. Verifie que le SDK du projet est en Java 25.
3. Adapte dans `gradle.properties` : `group`, `mod_id`, `mod_name`,
   `main_class`, `mod_author`.
4. Synchronise Gradle (IntelliJ le propose automatiquement, sinon
   bouton "Reload All Gradle Projects").
5. Prepare l'environnement de dev (telecharge le serveur, genere le
   manifest, etc.) :
   ```
   ./gradlew setupHytaleDev
   ```
6. Compile le plugin :
   ```
   ./gradlew build
   ```
   Le JAR se trouve dans `build/libs/`.
7. Pour tester en local :
   ```
   ./gradlew runServer
   ```
   ou copie le JAR compile dans le dossier `mods/` d'un serveur Hytale existant.

## Structure du projet

```
hytale-blockregen/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── src/main/java/com/nopefr/blockregen/
│   ├── BlockRegenPlugin.java        # classe principale : commande, listener, ecoute du chat, persistance
│   ├── BlockRegenCommand.java       # commande /blockregen "<bloc>" <duree> [--remove]
│   ├── BlockRegenListCommand.java   # sous-commande /blockregen list (ouvre BlockRegenListPage pour un joueur)
│   ├── BlockRegenAdminCommand.java  # sous-commande /blockregen admin (toggle bypass anti-abus)
│   ├── BlockRegenListPage.java      # fenetre UI custom listant les regles (icone, delai editable, S/M/H, suppression)
│   ├── BlockRegenTargetCommand.java # variante /blockregen <duree> (bloc vise + confirmation)
│   ├── BlockRegenListener.java      # ecoute la casse de blocs, planifie la regeneration, fait apparaitre le fantome
│   ├── BlockRegenPlaceListener.java # ecoute la pose de blocs : annule la regen en attente, marquage anti-abus
│   ├── BlockRegenGhostMarker.java   # composant marqueur des entites d'apercu fantome
│   ├── BlockRegenGhostVisibilitySystem.java # cache les fantomes aux joueurs sans la permission dediee
│   └── DurationParser.java          # parsing/formatage des durees (h/m/s, minutes par defaut)
└── src/main/resources/Common/UI/Custom/Pages/BlockRegen/
    ├── BlockRegenListPage.ui        # layout de la fenetre (conteneur + liste scrollable)
    └── BlockRegenEntryRow.ui        # layout d'une ligne (icone, nom, delai, S/M/H, bouton X)
```

## Points a verifier / a adapter (important)

Hytale est en early access et son API de modding evolue vite (nouvelles
Updates regulieres). Ce code a ete compile et verifie contre les sources
decompilees + JavaDoc du serveur 0.6.3, mais pourrait necessiter de petits
ajustements sur une future version :

- **`build.gradle.kts`** : la syntaxe exacte du bloc `hytaleTools { ... }`
  du plugin `com.azuredoom.hytale-tools` peut differer legerement selon
  sa version. Le wiki du plugin (`github.com/AzureDoom/Hytale-Gradle-Plugin/wiki`)
  fait reference.
- **Persistance** : les regles (`/blockregen`) sont sauvegardees sur disque
  (fichier `BlockRegenRules.json` dans le dossier de donnees du plugin) via
  le systeme `Config`/`Codec` natif du plugin, et rechargees automatiquement
  au demarrage du serveur. Seules les regenerations deja en attente (bloc
  casse, minuteur en cours) et les confirmations Y/N en attente sont perdues
  si le serveur redemarre avant l'echeance.
- **Permission** : la commande genere automatiquement le noeud de
  permission `com.nopefr.blockregen.command.blockregen` (a adapter selon
  ton `group`/`mod_id`). Donne cette permission aux joueurs/roles
  concernes, sinon seule la console pourra l'utiliser.

## Ameliorations possibles

- Stocker le bloc "original" avant remplacement (si tu veux remplacer par
  un autre bloc temporaire, ex: casser -> zone vide -> stone au bout de 120s).
- Persister egalement les regenerations et confirmations en attente (voir
  ci-dessus).
