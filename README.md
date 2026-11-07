# KeiScope

**A changelog explorer for Keiyoushi extensions used with Mihon.**

Application Android native open source, compatible avec Android 9.0 jusqu'à Android 17 (API 28 à 37, optimisée pour smartphones, mode paysage et tablettes). Indépendante de Mihon et de l'équipe Keiyoushi.

## Fonctions disponibles dans le code

- Interface utilisateur responsive et adaptative : NavigationRail latérale et grille adaptative sur tablettes et mode paysage, barre inférieure sur smartphones en mode portrait. Support Edge-to-Edge et thèmes dynamiques Material 3.
- Détecte les APK installés sous `eu.kanade.tachiyomi.extension.*` via `PackageManager` (Android 9 à 17).
- Importe les extensions depuis le [vrai catalogue Keiyoushi](https://github.com/keiyoushi/extensions/blob/repo/index.json), et non `index.min.json` (ce dernier est désormais un fichier de compatibilité).
- Affiche la version disponible, la version installée et un indicateur si la version du catalogue est plus récente.
- Recherche avec bouton d'effacement rapide et gestion des favoris locaux.
- Historique Git d'une extension via `keiyoushi/extensions-source`, regroupé par numéro de version extrait de `build.gradle.kts` à chaque commit ; bouton pour afficher commits et PR.
- Cache local du catalogue et de l'historique (6 heures).
- Vérifie les releases de `Epikaigle/keiscope` et permet de télécharger une mise à jour, vérifier son SHA-256 (via le fichier `.sha256` de la release), et déclencher le processus d'installation Android.
- Aucune publicité, aucun compte, aucune collecte de la liste des applications.

### Limites connues

Les mainteneurs d'extensions ne publient pas systématiquement des changelogs officiels. Les groupes de versions de KeiScope sont **reconstruits à partir des commits et de l'état du fichier Gradle à cette révision**. Cette reconstruction peut être incomplète, en particulier pour les modifications apportées à des bibliothèques partagées, les versions anciennes et les extensions dont la structure diffère. Ce n'est **pas** une preuve qu'une modification donnée est intégrée dans un APK publié. GitHub limite les requêtes non authentifiées ; l'application affiche une erreur si une limite est atteinte. L'historique est paginé à 30 commits par page.

**Aucune mise à jour des extensions elles-mêmes** : cette fonction reste assurée par Mihon. KeiScope fournit uniquement les informations et les mises à jour de **KeiScope**.

## Construire et signer

Prérequis : JDK 17, Android SDK 37 (ou 34+) et Build Tools 36.0.0. Gradle Wrapper 9.7.0 inclus.

```bash
# Build de développement (signature debug)
./gradlew :app:assembleDebug

# Build signé (release officielle avec le keystore)
source ~/.android/keiscope-signing.env   # ou exporter KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD
./gradlew :app:assembleRelease
```

**ATTENTION :** ne jamais utiliser la signature debug comme signature d'une version installée destinée à recevoir des mises à jour. Ne jamais publier le keystore, les mots de passe, ni leurs fichiers de sauvegarde dans le dépôt. Les mises à jour Android exigent le même `applicationId`, le même certificat et un `versionCode` supérieur. Conserver le keystore hors du dépôt pour toutes les futures versions.

## GitHub Actions : premier build signé

1. Créer un dépôt public `Epikaigle/keiscope` avec la branche par défaut `main`, puis envoyer le contenu de ce projet **directement sur `main`** (sans PR).
2. Dans *Settings → Secrets and variables → Actions*, créer quatre secrets :
   - `KEYSTORE_BASE64` : contenu d'une seule ligne de `base64 -w0 keiscope-signing.jks` sous Linux ; sur macOS : `base64 < keiscope-signing.jks | tr -d '\n'`.
   - `KEYSTORE_PASSWORD` : mot de passe du keystore.
   - `KEY_ALIAS` : `keiscope`.
   - `KEY_PASSWORD` : mot de passe de la clé (ici identique au précédent dans le pack de sauvegarde).
3. La CI (fichier `ci.yml`) compilera l'application à chaque push sur `main`.
4. Dans *Actions → Signed Android Release → Run workflow*, sélectionner `main` et lancer. Le workflow compile, vérifie la signature et crée la release `v1.0.0` avec l'APK signé.
5. Pour publier une mise à jour, augmenter **à la fois** `versionCode` et `versionName` dans `app/build.gradle.kts`, pousser sur `main`, puis relancer *Signed Android Release*. **Ne jamais changer de keystore.**

Une release existante n'est jamais écrasée par ce workflow. La signature est vérifiée avec `apksigner verify` avant publication.

## Permissions

- `INTERNET` : consulter Keiyoushi et GitHub.
- `QUERY_ALL_PACKAGES` : trouver les extensions installées sur un Android sideloadé. Permission destinée à un usage personnel, non préparée pour une publication Google Play.
- `REQUEST_INSTALL_PACKAGES` : déclencher, avec confirmation utilisateur, l'installation des mises à jour de KeiScope.

## Sources utilisées

- https://github.com/keiyoushi/extensions
- https://github.com/keiyoushi/extensions-source

Licence : Apache-2.0. Le nom KeiScope n'implique aucune affiliation.
