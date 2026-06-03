# Flashback Server — Design

**Date:** 2026-06-03
**Statut:** Validé (brainstorming) — en attente de relecture utilisateur avant plan d'implémentation

## Résumé

**Flashback Server** est un plugin **Paper 1.21.x** qui génère des replays au
format **Flashback** **entièrement côté serveur**, sans que les joueurs aient
besoin d'un mod d'enregistrement. Il se distingue de l'existant
(`Paper Flashback`, `server-replay`) par trois axes :

1. **Zéro dépendance** — interception par **injection Netty pure**, pas de
   ProtocolLib.
2. **Compatible Folia** — fonctionne sur le fork multi-thread de Paper.
3. **Système de clips (fonctionnalité signature)** — « sauvegarde les N
   dernières secondes » sur événement (mort, kill, commande, API), un usage
   nouveau et intrinsèquement partageable.

L'objectif produit est de **percer sur Modrinth** : le socle technique (1 + 2)
est l'argument de fiabilité, le système de clips (3) est la vitrine.

## Contexte et alternatives existantes

| Projet | Format | État | Limite vis-à-vis de notre but |
|---|---|---|---|
| `server-replay` (Fabric) | .mcpr + Flashback | Maintenu | Fabric uniquement |
| `PaperRecorder` | .mcpr | **Abandonné** (2023, fork de Paper) | Obsolète, intrusif |
| `Paper Flashback` | Flashback | Maintenu (mars 2026) | **Dépend de ProtocolLib**, pas de Folia, pas de clips, tout en commandes |

Aucun plugin Paper 1.21.x maintenu ne propose un enregistrement Flashback
**sans ProtocolLib**, **compatible Folia**, **avec un système de clips**.

## Décisions de cadrage

- **Plateforme** : Paper 1.21.x (et dérivés Purpur/Pufferfish/Folia). Pas de
  multi-version 1.17–1.20.
- **Langage / build** : Java + Gradle (paperweight-userdev).
- **Interception** : injection d'un `ChannelDuplexHandler` dans le pipeline
  Netty du joueur, capture des octets wire bruts des paquets *play* sortants.
- **Format de sortie** : Flashback (zstd + snapshots/keyframes).
- **État initial** : mode « depuis la connexion » pour le MVP ; mode
  « snapshot en cours de session » requis et ajouté par le système de clips.
- **Nom** : « Flashback Server » (choix utilisateur assumé malgré le risque de
  proximité de marque avec le mod Flashback de Moulberry).
- **Licence** : MIT (notre code). Distincte de celle de Flashback.

## Contrainte légale (clean-room)

Le code de Flashback (Moulberry) est **propriétaire** : *« Copyright 2024
Moulberry. All rights reserved. Do not redistribute. »*

- ✅ **Autorisé** : lire le code pour comprendre le **format de fichier**
  (interopérabilité — un format n'est pas protégeable).
- ❌ **Interdit** : copier le code, recopier l'architecture de classes, créer
  une œuvre dérivée.

**Règle de travail :** P0 produit une **implémentation 100 % originale** du
format, documentée comme spec interne (`docs/format/flashback-format.md`).
Aucun copier-coller depuis le dépôt Flashback.

## Architecture (unités isolées)

Chaque unité a un rôle unique, une interface claire, et est testable seule.

### 1. `PacketCapture`
- **Rôle** : injecte un `ChannelDuplexHandler` dans le pipeline Netty du
  joueur, capture les `ByteBuf` des paquets *play* sortants (octets wire bruts).
- **Dépend de** : pipeline Netty de Paper (isolé derrière une couche
  d'adaptation pour absorber les changements entre builds 1.21.x).
- **Interface** : `inject(player)`, `eject(player)`, callback
  `onPacket(player, ByteBuf, timestampMs)`.

### 2. `FlashbackWriter`
- **Rôle** : sérialise le flux de paquets au format Flashback (conteneur,
  métadonnées, compression zstd, snapshots/keyframes).
- **Dépend de** : la spec de format issue de P0.
- **Interface** : `open(meta)`, `write(packet, timestamp)`,
  `snapshot(state)`, `close()`.

### 3. `RecordingManager`
- **Rôle** : orchestre les enregistrements actifs (cycle de vie start/stop/
  save), un fichier par joueur, limites (taille/durée).
- **Interface** : exposée via les commandes `/replay` et une API publique.

### 4. `InitialStateBuilder`
- **Rôle** : produit la séquence initiale au temps 0 (join, dimension,
  position, player info, chunks autour du joueur).
- **Modes** : `FROM_CONNECTION` (capture réelle dès le login),
  `MID_SESSION_SNAPSHOT` (reconstruit l'état courant — requis par les clips).

### 5. `ClipBuffer` *(signature)*
- **Rôle** : buffer circulaire des N dernières secondes de paquets par joueur ;
  sur trigger, fige un snapshot initial (`MID_SESSION_SNAPSHOT`) + vide le
  buffer → clip Flashback autonome.
- **Triggers** : mort joueur, kill, commande `/replay clip`, événements
  configurables, API.
- **Interface** : `arm(player, windowSeconds)`, `trigger(player, reason)`.

### 6. `PlatformScheduler`
- **Rôle** : abstraction Bukkit/Folia du scheduling. Tout le reste ignore le
  scheduler concret.
- **Interface** : `runRegion(...)`, `runAsync(...)`, `runGlobal(...)`.

### 7. `Telemetry`
- **Rôle** : envoi d'événements anonymes vers PostHog (opt-out, zéro PII).
- **Événements** : enregistrements start/stop, triggers de clips, tailles de
  fichiers, version plateforme (Paper/Folia), erreurs.
- **Contrainte** : toggle `telemetry: true/false` dans la config, mention dans
  le README, aucune IP/aucun pseudo en clair.

## Flux de données

```
Joueur (Netty pipeline)
   │  paquets play sortants (ByteBuf)
   ▼
PacketCapture ──► RecordingManager ──► FlashbackWriter ──► fichier .zip Flashback
                       │                     ▲
                       │                     │ snapshot initial
                       ├──► InitialStateBuilder
                       │
                       └──► ClipBuffer (ring buffer N s) ──(trigger)──► clip autonome
```

## Gestion d'erreurs

- Échec d'injection Netty → log + désactivation propre de l'enregistrement pour
  ce joueur, le serveur n'est jamais impacté.
- Paquet non sérialisable → ignoré + compteur de télémétrie, jamais de crash.
- Disque plein / IO → arrêt de l'enregistrement, fichier partiel fermé
  proprement, message admin.
- Changement de pipeline Netty entre builds → la couche d'adaptation lève une
  erreur explicite au démarrage (fail-fast, message clair).

## Stratégie de test (infra autonome complète)

Objectif : pouvoir **tester et approuver le plugin sans intervention humaine**
(hors un spot-check visuel final).

- **Tests unitaires** (JUnit + MockBukkit) : `RecordingManager`, commandes,
  `ClipBuffer`, sérialisation `FlashbackWriter`.
- **Validateur Flashback** (oracle, issu de P0) : re-parse le fichier produit
  et asserte — conteneur valide, métadonnées correctes, zstd décompressable,
  état initial présent, paquets désérialisables dans le protocole *play*,
  timestamps monotones, comptage de paquets cohérent.
- **Harness d'intégration** : lance un vrai serveur Paper (`runServer` de
  paperweight), connecte un **bot client headless scripté** (MCProtocolLib) qui
  agit (déplacement, actions), déclenche `/replay`, récupère le fichier, le
  passe au validateur.
- **Limite d'autonomie assumée** : le rendu visuel final dans le mod Flashback
  (« est-ce que ça a l'air correct ») n'est pas automatisable à 100 % →
  **un seul spot-check humain** en P6.

## Découpage en phases

Chaque phase = un plan d'implémentation et un cycle de commits.

- **P0 — Spike format Flashback (clean-room)** : étude du code Flashback,
  écriture d'une implémentation originale du writer + d'un **lecteur/validateur**
  (oracle de test), documentée dans `docs/format/flashback-format.md`.
- **P1 — Harness de test autonome** : socle JUnit/MockBukkit + lancement Paper
  + bot headless. Rend tout le reste testable en TDD.
- **P2 — Cœur** : `PacketCapture` + `FlashbackWriter` + `RecordingManager` +
  `InitialStateBuilder` (mode connexion) + `/replay start|stop players`.
  → un `.zip` Flashback lisible.
- **P3 — Socle A** : `PlatformScheduler` (Folia) + verrouillage zéro-dépendance
  + écriture asynchrone / perf.
- **P4 — Signature B** : `ClipBuffer` + `MID_SESSION_SNAPSHOT` + triggers + API.
- **P5 — Télémétrie PostHog** : `Telemetry` opt-out, sans PII.
- **P6 — Publication Modrinth** : page projet, docs, README, spot-check humain.

## Workflow de développement

- Repo **public GitHub** `FlashbackServer` (compte Zeffut), créé en étape 0.
- Fichiers initiaux : README, `.gitignore` (Java/Gradle), `LICENSE` (MIT),
  ce spec.
- **Commits atomiques et fréquents**, style *conventional commits*
  (`feat:`, `fix:`, `test:`, `docs:`, `chore:`), push régulier après chaque
  unité de travail.

## Risques

- **R1 (majeur)** — Format Flashback non documenté publiquement. *Mitigation :*
  P0 obligatoire (étude code + validateur) avant tout engagement sur le reste.
- **R2** — Noms/structure du pipeline Netty de Paper variables entre builds
  1.21.x. *Mitigation :* couche d'adaptation isolée + fail-fast au démarrage.
- **R3** — Snapshot d'état en cours de session (clips) techniquement complexe.
  *Mitigation :* isolé en P4, après un cœur stable.
- **R4** — Proximité de marque « Flashback ». *Accepté par l'utilisateur.*

## Hors périmètre (YAGNI pour l'instant)

- Enregistrement par zone de chunks (server-replay le fait, pas prioritaire).
- Lecture/playback côté serveur (`/replay view`).
- Support voice chat.
- Sortie au format ReplayMod `.mcpr` (Flashback uniquement).
- Dashboard web / liens de partage hébergés.
