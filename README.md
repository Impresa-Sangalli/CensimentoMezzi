# Censimento Mezzi – Impresa Sangalli

App Android nativa per il censimento dei mezzi parcheggiati nel piazzale aziendale.
Funziona **senza internet** e salva tutto sul telefono (database interno + foto nella galleria).

| | |
|---|---|
| Versione | 1.8 (`versionCode` 9) |
| Package | `it.impresasangalli.censimentomezzi` |
| Android | 8.0 o superiore (`minSdk` 26, `targetSdk` 34) |
| Linguaggio | Kotlin, solo componenti standard di Android (niente Jetpack Compose né librerie esterne) |

## Funzionalità

**CENSIMENTO**
- Targa (solo lettere e numeri, maiuscolo, obbligatoria)
- Zona di parcheggio: A, B, C, D, E, F, G, H1, H2 (obbligatoria)
- Foto (obbligatoria; al primo scatto l'app chiede il permesso per la fotocamera)
- Data di uscita (facoltativa, in fondo al modulo)
- Data e ora del censimento registrate in automatico
- Avviso se la targa è già censita, con pulsante **"Registra uscita di questa targa"**

**ELENCO**
- Ricerca per targa, filtri *Tutti / Nel piazzale / Usciti*
- Dettaglio con foto, modifica o rimozione della data di uscita, eliminazione

**ESPORTA**
- Riepilogo (totali e per zona)
- Esportazione Excel (`.xlsx`) e ZIP (Excel + cartella `foto/`), condivisione
- "Azzera archivio"

**PORTALE** – collegamento al portale eDepositeria (vedi sotto) e aggiornamenti dell'app

### Data di uscita
Per registrarla dopo il censimento:
- scheda ELENCO › cercare la targa › toccare la riga › DATA USCITA, oppure
- scheda CENSIMENTO › digitare la targa › pulsante "Registra uscita di questa targa".

L'Excel esportato ha la colonna "Data uscita".

## Dove finiscono i file sul telefono

| Cosa | Percorso |
|---|---|
| Fotografie | `Immagini (Pictures)/CensimentoMezzi/TARGA.jpg` — targhe duplicate: `TARGA_2.jpg`, `TARGA_3.jpg`, … |
| Esportazioni | `Download/CensimentoMezzi/Censimento_Mezzi_AAAAMMGG_HHMM.xlsx` (o `.zip`) |
| Archivio censimenti | database SQLite interno dell'app (`censimenti.db`) |

## Installazione sul telefono

1. Copiare l'APK sul telefono (e-mail, cavo USB, Drive…).
2. Aprirlo e consentire "Installa app sconosciute" all'app usata per aprirlo.
3. Gli aggiornamenti si installano sopra la versione precedente **senza perdere i censimenti già registrati**,
   purché firmati con lo stesso `debug.keystore` (vedi sotto).

## Compilazione

Il progetto si compila **senza Gradle**, con la catena di strumenti dell'Android SDK
(kotlinc → aapt → d8 → zipalign → apksigner). Il risultato è
`CensimentoMezzi-debug.apk` nella cartella del progetto (escluso da git).

### Windows – `build.ps1`

```powershell
powershell -ExecutionPolicy Bypass -File .\build.ps1
```

Strumenti richiesti (percorsi modificabili con variabili d'ambiente):

| Strumento | Variabile | Percorso predefinito |
|---|---|---|
| JDK 17 (Microsoft Build of OpenJDK) | `JAVA_HOME` | obbligatoria |
| Android SDK: `platforms/android-34`, `build-tools/37.0.0` | `ANDROID_HOME` | `%LOCALAPPDATA%\Android\Sdk` |
| Compilatore Kotlin 2.4 | `KOTLIN_HOME` | `%LOCALAPPDATA%\Programs\kotlinc` |

Installazione degli strumenti:
```powershell
winget install --id Microsoft.OpenJDK.17 -e
```
- Android SDK: scaricare i *Command line tools only* da developer.android.com/studio,
  scompattarli in `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest`, poi
  `android.exe sdk install platforms/android-34` e `android.exe sdk install build-tools/37.0.0`
  (in `cmdline-tools\latest\bin`).
- Kotlin: scaricare `kotlin-compiler-<versione>.zip` dalle release di github.com/JetBrains/kotlin
  e scompattarlo in `%LOCALAPPDATA%\Programs\kotlinc`.

Note:
- lo script chiama direttamente i `.jar` di d8/apksigner/kotlinc invece dei `.bat`, perché il
  percorso del progetto contiene `&`, che `cmd.exe` interpreta come separatore di comandi;
- usare build-tools recenti: il `d8` di build-tools 34 non riconosce i metadati di Kotlin 2.4.
  La versione dei build-tools non cambia `targetSdk` (resta 34, definito nel manifest).

### Linux – `build.sh`

Stessi passaggi di `build.ps1`, usato da GitHub Actions. Richiede `ANDROID_HOME` e `KOTLIN_HOME`;
non crea mai un keystore nuovo (si ferma se `debug.keystore` manca).

## Aggiornamento automatico (dalla versione 1.3)

L'app si aggiorna da sola scaricando le nuove versioni dalle
[Release di GitHub](https://github.com/Impresa-Sangalli/CensimentoMezzi/releases) via internet
(Wi-Fi o dati mobili). Il censimento continua a funzionare anche senza rete.

**Pubblicare una nuova versione**
1. Aumentare `android:versionCode` (+1) e `android:versionName` in `app/src/main/AndroidManifest.xml`.
2. Fare commit e push su `main`.
3. Il workflow *Compila e pubblica* (`.github/workflows/release.yml`) compila, firma con il
   `debug.keystore` (Secret `DEBUG_KEYSTORE_BASE64`), verifica l'impronta del certificato e
   pubblica la Release `v<versione>` con l'APK e `version.json`.
   I push che non cambiano la versione vengono solo compilati, senza pubblicare nulla.

**Sul telefono**
- L'app controlla `version.json` dell'ultima Release all'apertura (al massimo ogni 30 minuti),
  ogni 6 ore in background e con il pulsante *Controlla aggiornamenti* (scheda PORTALE).
- Scarica l'APK, ne verifica l'impronta SHA-256 e lo installa. Android accetta solo APK firmati
  con la stessa chiave dell'app già installata.
- Non installa mai se nel modulo di censimento ci sono dati non salvati (targa, foto o data di uscita):
  l'installazione chiude l'app.
- Serve una sola volta l'autorizzazione **"Installa app sconosciute"** per Censimento Mezzi
  (l'app la chiede alla prima apertura).
- **Android 12 e successivi**: installazione senza conferma. **Android 8–11**: il sistema chiede
  sempre un tocco su "Installa" (ad app chiusa compare una notifica).
- La versione 1.2 non contiene l'aggiornamento automatico: la 1.3 va installata a mano una volta
  (scaricandola dalla pagina delle Release), poi le successive arrivano da sole.

## Firma e aggiornamenti

Le nuove versioni **devono** essere firmate con lo stesso `debug.keystore`
(alias `androiddebugkey`), altrimenti Android rifiuta di installarle sopra quella presente sui telefoni.

- `debug.keystore` **non è nel repository** (escluso da `.gitignore`): va conservato a parte,
  con una copia di sicurezza. Se va perso, sui telefoni sarà necessario disinstallare l'app
  (perdendo i dati non esportati) prima di installare la nuova versione.
- A ogni nuova versione aumentare `android:versionCode` e `android:versionName` in
  `app/src/main/AndroidManifest.xml`.

## Collegamento al portale (dalla versione 1.5)

Il **portale eDepositeria** ([repository privato `edepositeria`](https://github.com/Impresa-Sangalli/edepositeria),
`https://edepositeria.impresasangalli.it`, rete aziendale) è l'archivio principale e l'anagrafica in tempo reale per l'ufficio.
L'app è lo strumento operativo nel piazzale:

- registra **entrate** (targa, zona, foto, dati facoltativi: marca, modello, colore, tipo, pratica, ente, provvedimento, note),
  **uscite**, **spostamenti di zona** e **correzioni**;
- mostra l'**elenco aggiornato del piazzale**, compresi i mezzi registrati dagli altri telefoni o dall'ufficio, con le foto del portale;
- funziona anche senza rete: ogni operazione va in una **coda** e parte appena il telefono è sul **Wi-Fi aziendale**
  (sincronizzazione all'apertura, dopo ogni operazione e ogni 15 minuti);
- **non cancella** nulla sul portale: l'eliminazione si fa dall'ufficio.
- **una targa, una sola entrata nel piazzale** (dalla 1.6): una targa già nel piazzale non si può registrare di nuovo (prima va
  registrata l'uscita). Se un telefono rimasto senza rete invia una targa che nel frattempo risulta già nel piazzale, il portale la
  rifiuta e l'invio resta **in attesa** (barra rossa, scheda PORTALE, riga rossa nell'Elenco): l'operatore corregge i dati e
  l'invio riparte, oppure lo **scarta indicando il motivo**, che finisce nel registro attività del portale;
- **rientro**: se la targa è già stata nel piazzale ed è uscita, il modulo mostra i dati dell'ultima volta e il pulsante
  «Usa i dati del mezzo» ricompila marca, modello, colore e tipo.

**Collegare un telefono** (una volta, a cura dell'amministratore): sul portale *Controlli › Telefoni › Nuovo telefono* →
nell'app scheda **PORTALE › Collega al portale** (protetto da un PIN amministratore, scelto al primo uso) → inserire il codice
(vale 24 ore, una sola volta). Al collegamento i censimenti già presenti sul telefono vengono inviati al portale.
Un telefono perso si disattiva dal portale: il suo token smette subito di funzionare.

Contratto delle API: `docs/api.md` nel repository del portale. Il certificato del portale (`*.impresasangalli.it`, GoDaddy)
è verificato normalmente; la radice "GoDaddy TLS Root CA - R1" è inclusa nell'app (`res/raw`) per i telefoni Android
meno recenti che non l'hanno ancora tra le radici di sistema (`res/xml/network_security_config.xml`).

L'invio a SharePoint delle versioni 1.1–1.4 è stato rimosso.
## Struttura del codice

```
app/src/main/AndroidManifest.xml
app/src/main/res/                    icone e stringhe
app/src/main/java/it/impresasangalli/censimentomezzi/
   MainActivity.kt   interfaccia (Censimento / Elenco / Esporta / Portale)
   Db.kt             archivio locale SQLite
   PhotoStore.kt     salvataggio foto in Pictures/CensimentoMezzi
   Exporter.kt       esportazione Excel e ZIP in Download/CensimentoMezzi
   XlsxWriter.kt     generatore .xlsx senza librerie esterne
   FilesProvider.kt  scambio file con l'app fotocamera e condivisione (Android 8-9)
   Server.kt         collegamento al portale: indirizzo, token cifrato, PIN, chiamate HTTP
   SyncEngine.kt     coda di invio e sincronizzazione del piazzale (JobScheduler)
   Updater.kt        aggiornamento automatico dalle Release GitHub
.github/workflows/release.yml   compilazione e pubblicazione delle Release
build.ps1            compilazione su Windows
build.sh             compilazione su Linux / GitHub Actions
LEGGIMI.txt          istruzioni originali
```
