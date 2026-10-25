iOS Launcher - Build-Anleitung

A) GitHub Actions (ohne Android Studio)
1. Neues GitHub-Repo anlegen, den kompletten Ordnerinhalt hochladen (inkl. .github/).
2. Reiter "Actions" -> "Build APK" laeuft automatisch (oder "Run workflow").
3. Fertigen Lauf oeffnen -> unten "Artifacts" -> ios-launcher-apk herunterladen -> entpacken -> app-debug.apk.

B) Android Studio
1. Ordner oeffnen, Gradle-Sync abwarten.
2. Build -> Build Bundle(s) / APK(s) -> Build APK(s).

Installieren und aktivieren (Xiaomi/HyperOS)
1. APK installieren (unbekannte Quellen erlauben).
2. Einstellungen -> Apps -> Standard-Apps -> Startbildschirm -> "iOS Launcher".

Bedienung
- Wischen: Seiten wechseln
- Nach unten wischen: Suche
- Lang druecken auf App: Dock, Ordner, App-Info, Deinstallieren
- Lang druecken auf Ordner: umbenennen / aufloesen
