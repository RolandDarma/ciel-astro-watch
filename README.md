# Ciel Astro — tuile Wear OS pour la Galaxy Watch 4

Application Wear OS autonome qui affiche, au poignet, la qualité du ciel pour les
prochaines nuits à **Luc-sur-Mer** : score /100, créneau dégagé, nuit astronomique,
Lune et seeing estimé.

* **Tuile** (à glisser à côté du cadran) : la nuit qui vient, d'un coup d'œil.
* **Application** (appui sur la tuile) : les 5 à 7 prochaines nuits en détail.

Mêmes données et même formule de score que la page web
`https://rolanddarma.github.io/meteo-astro/` : Open-Meteo pour la météo
(nuages bas/moyens/hauts pondérés 0,9 / 0,7 / 0,45, pluie, rafales, écart
température / point de rosée), éphémérides calculées dans l'application
(algorithmes de Meeus, vérifiés contre la nouvelle lune du 06/01/2000,
la pleine lune du 21/01/2000 et le coucher du Soleil à Paris au solstice).

La Lune n'entre pas dans le score : elle gêne le ciel profond, pas le planétaire.

---

## 1. Obtenir l'APK sans installer Android Studio (recommandé)

Le dépôt contient un workflow GitHub Actions qui compile l'APK dans le cloud.

1. Créer un dépôt **public** sur GitHub (par exemple `ciel-astro-watch`), sans README.
2. Sur la page d'upload du dépôt, glisser-déposer **le contenu** de ce dossier :
   `app/`, `gradle/`, `.github/`, `build.gradle.kts`, `settings.gradle.kts`,
   `gradle.properties`, `README.md`.
   (Le contenu, pas le dossier `cielastro` lui-même : les fichiers Gradle doivent
   se trouver à la racine du dépôt.)
3. Commit. L'onglet **Actions** lance « Compiler l'APK » (3 à 5 minutes).
4. L'APK apparaît dans **Releases → latest → app-debug.apk**, et aussi en pièce
   jointe du run.

L'APK est signé avec la clé de debug : parfait pour un usage personnel, impossible
à publier sur le Play Store en l'état.

## 2. Ou compiler avec Android Studio

Ouvrir ce dossier dans Android Studio, accepter la synchronisation Gradle
(le wrapper sera régénéré), puis `Run` sur la montre connectée, ou
`Build > Build APK(s)`.

## 3. Installer sur la Galaxy Watch 4

Activer d'abord le mode développeur sur la montre :
**Paramètres → À propos de la montre → Informations sur le logiciel →
taper 5 fois sur « Version du logiciel »**, puis dans
**Paramètres → Options de développement** activer **Débogage ADB** et
**Débogage sans fil**.

* **Sans PC** : installer *Wear Installer 2* sur le téléphone, lui donner l'APK
  téléchargé depuis la release, il l'envoie à la montre en sans-fil.
* **Avec un PC** : télécharger les *platform-tools* Android, puis
  ```
  adb pair <ip-montre>:<port-appairage>     # code affiché sur la montre
  adb connect <ip-montre>:<port-debug>
  adb install -r app-debug.apk
  ```

Enfin, **ajouter la tuile** : appui long sur le cadran → *Tuiles* → **+** → « Ciel ».

## 4. Changer de lieu d'observation

Dans `app/src/main/java/fr/darmagnac/cielastro/Forecast.kt` :

```kotlin
object Site {
    const val LAT = 49.3145          // Luc-sur-Mer
    const val LON = -0.3550
    const val NAME = "Luc-sur-Mer"
}
```

## Notes techniques

* `minSdk 30`, `compileSdk 35`. La Galaxy Watch 4 tourne sous One UI 8 Watch /
  Wear OS 6 depuis décembre 2025 : largement au-dessus du minimum.
* Dépendances volontairement minimales : pas de Compose, pas de Retrofit.
  Réseau en `HttpURLConnection`, JSON avec `org.json`, interface en vues Android.
  Moins de dépendances, moins de surprises à la compilation.
* Les prévisions sont mises en cache 30 minutes (`SharedPreferences`) ; hors
  réseau, l'application affiche les dernières données connues avec la mention
  « en cache ».
* La tuile demande un rafraîchissement toutes les heures
  (`setFreshnessIntervalMillis`).
* Versions épinglées : AGP 8.7.3, Gradle 8.9, Kotlin 2.0.21,
  `androidx.wear.tiles:tiles:1.4.1`, `androidx.wear.protolayout:protolayout:1.2.1`.
  Des versions plus récentes existent (tiles 1.6.2 / protolayout 1.4.2, juillet 2026) ;
  l'ancienne série est conservée parce que son API est stable et suffisante ici.
