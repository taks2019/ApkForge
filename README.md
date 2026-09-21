# APK Forge

GitHub repókból épít APK-t: elindítja a GitHub Actions buildet, követi, letölti és telepíti az APK-t.

## Az app APK-jának elkészítése
1. Tedd fel ezt a projektet egy GitHub repóba (main ág).
2. Actions fül → "Build APK Forge" → Run workflow (vagy push esetén automatikusan indul).
3. A lefutott run alján töltsd le az `apk-forge` artifactot, csomagold ki, telepítsd.

## Használat
1. Kulcs ikon: GitHub token megadása (repo + workflow jog).
2. Repó hozzáadása (tulajdonos/repó).
3. Ha a repóban nincs build workflow: három pont → Workflow létrehozása.
4. APK építése → Telepítés.
