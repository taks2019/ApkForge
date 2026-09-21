# APK Forge v2

GitHub-alapú Android APK builder.

## Fő funkciók

- GitHub token kezelése
- meglévő GitHub repó hozzáadása
- új GitHub repó létrehozása az alkalmazásból
- automatikus kezdő README + APK Forge workflow
- GitHub Actions APK build indítása
- build állapot követése
- GitHub Actions napló megnyitása
- elkészült APK artifact letöltése
- APK telepítése
- projekt eltávolítása csak az ApkForge listájából
- GitHub repó végleges törlése külön megerősítéssel

## Token jogosultság

Fine-grained PAT esetén az új repó létrehozásához és törléséhez GitHub szerint
`Administration: Read and write` szükséges. A workflow/fájl kezeléshez
`Contents: Read and write` és `Workflows: Read and write` szükséges.

A token értékét ne oszd meg senkivel.
