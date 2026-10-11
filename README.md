# Téléprompteur

App Android de téléprompteur selfie : le texte défile en haut de l'écran,
juste sous l'objectif frontal, pendant que la caméra te filme — pour donner
l'illusion que tu regardes l'objectif au lieu de lire.

## Utilisation

1. **Colle ton texte** sur l'écran d'accueil (ou touche *Insérer un exemple*).
2. **Lance le prompteur** : la caméra frontale s'affiche en plein écran et le
   texte démarre en haut, à mi-hauteur du bandeau.
3. En bas de l'écran (accessible au pouce) :
   - **Vitesse** : vitesse de défilement, réglable en direct
   - **Taille du texte** : corps du texte
   - **Défiler / Pause** : démarre ou suspend le défilement
   - **Début** : revient au début du texte
   - **Miroir** : inverse le texte horizontalement (usage avec un vrai
     prompteur à vitre)
   - **● REC / ■ Stop** : enregistre un selfie vidéo
4. La vidéo MP4 (avec le son) est enregistrée dans la galerie, dossier
   **Films/Téléprompteur**.
5. **Texte** (ou bouton retour) ramène à l'édition.

Le texte, la vitesse, la taille et le mode miroir sont mémorisés entre les
lancements. Un appui sur le texte qui défile met aussi en pause/reprend.

## Technique

- App native Java, `minSdk 26`, `targetSdk 35`
- [CameraX](https://developer.android.com/training/camerax) 1.4.2 :
  `Preview` + `VideoCapture`/`Recorder` (qualité FHD avec repli SD)
- Enregistrement via `MediaStore` → `Movies/Teleprompteur` (pas de permission
  de stockage nécessaire à partir d'Android 10)
- Permissions demandées au lancement du prompteur : caméra (obligatoire),
  micro (optionnelle — la vidéo est muette si refusée), stockage (≤ Android 9)

## Compiler

```bash
cd android && ./gradlew assembleDebug
# APK → android/app/build/outputs/apk/debug/app-debug.apk
```

## Licence

MIT — voir [LICENSE](LICENSE).
