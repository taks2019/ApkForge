# APK Forge

GitHub repókból épít APK-t: elindítja a GitHub Actions buildet, követi, letölti és telepíti az APK-t.

## GitHub hitelesítés

Az APK Forge új verziója **nem kér Personal Access Tokent**.

A hitelesítés GitHub App Device Flow-val történik:
1. Az alkalmazás GitHub bejelentkezési kódot kér.
2. Megnyitja a GitHub engedélyezési oldalt.
3. A felhasználó bejelentkezik és engedélyezi az APK Forge GitHub Appot.
4. Az alkalmazás rövid életű user access tokent kap.
5. A refresh tokennel automatikusan megújítja a munkamenetet.
6. A tokenek Android Keystore által védett tárhelyen vannak titkosítva.

A GitHub user access token alapértelmezés szerint 8 óra után lejár, a refresh token pedig 6 hónapig használható. Az alkalmazás automatikusan frissíti az access tokent.

## Egyszeri fejlesztői beállítás

Az APK Forge számára létre kell hozni egy GitHub Appot, és engedélyezni kell a Device Flow-t. A GitHub App Client ID nem titkos adat, ezért beépíthető az APK-ba.

Az `app/build.gradle.kts` fájlban cseréld ezt:

`PUT_YOUR_GITHUB_APP_CLIENT_ID_HERE`

a saját GitHub App Client ID értékére.

A szükséges jogosultságok az alkalmazás jelenlegi funkcióihoz:
- Repository Metadata: read
- Repository Contents: write
- Repository Actions: write
- Repository Workflows: write
- Repository Administration: write, ha a későbbi repo-létrehozás/törlés funkciókat is használni fogjuk.

Csak a ténylegesen használt jogosultságokat érdemes engedélyezni.

## Build

GitHub Actions automatikusan elkészíti a debug APK-t a `main` ágra történő push után.
