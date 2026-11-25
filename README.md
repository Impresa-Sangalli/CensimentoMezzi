# Censimento Mezzi – Impresa Sangalli

App Android nativa per il censimento dei mezzi parcheggiati nel piazzale aziendale.
Funziona **senza internet** e salva tutto sul telefono (database interno + foto nella galleria).

| | |
|---|---|
| Versione | 1.2 (`versionCode` 3) |
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

**ONLINE** – invio automatico a SharePoint *(predisposto ma **non attivo**, vedi sotto)*

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

Script originale, scritto per l'ambiente in cui l'app è nata (percorsi in `/home/claude/tools`
e `/usr/lib/android-sdk`, strumento `dx`, rimosso dai build-tools recenti). Va adattato prima di
riusarlo altrove.

## Firma e aggiornamenti

Le nuove versioni **devono** essere firmate con lo stesso `debug.keystore`
(alias `androiddebugkey`), altrimenti Android rifiuta di installarle sopra quella presente sui telefoni.

- `debug.keystore` **non è nel repository** (escluso da `.gitignore`): va conservato a parte,
  con una copia di sicurezza. Se va perso, sui telefoni sarà necessario disinstallare l'app
  (perdendo i dati non esportati) prima di installare la nuova versione.
- A ogni nuova versione aumentare `android:versionCode` e `android:versionName` in
  `app/src/main/AndroidManifest.xml`.

## Invio a SharePoint (predisposto, non attivo)

Il codice per l'invio automatico a SharePoint tramite Microsoft Graph è presente
(scheda ONLINE), ma **non è attivo**: si è deciso di tenere il censimento solo sul dispositivo.
Senza configurazione l'app mostra "Invio online non configurato" e non invia nulla.

Limiti noti:
- una data di uscita inserita **dopo** l'invio non aggiorna la riga già caricata su SharePoint;
- la parte SharePoint è stata provata solo contro un server simulato, **non sul tenant reale**.

<details>
<summary>Come si attiverebbe (riferimento)</summary>

Struttura su SharePoint:
```
<Sito> › Documenti › Censimento Mezzi / foto / TARGA.jpg
<Sito> › Documenti › Censimento Mezzi / Censimento_Mezzi.xlsx   (una riga per censimento)
```
Senza connessione i censimenti restano in coda e partono appena torna la rete (anche ad app chiusa).

Configurazione una tantum a cura dell'IT (Microsoft Entra ID):
1. portal.azure.com › Microsoft Entra ID › Registrazioni app › Nuova registrazione —
   Nome: *Censimento Mezzi*, Tipi di account: solo questa directory (tenant singolo).
2. Autenticazione › Aggiungi piattaforma › "Applicazioni per dispositivi mobili e desktop" —
   URI di reindirizzamento personalizzato: `it.impresasangalli.censimentomezzi://auth`
3. Autorizzazioni API › Microsoft Graph › Delegate:
   `Files.ReadWrite.All`, `User.Read`, `offline_access`, `openid`, `profile` › "Concedi consenso amministratore".
4. Comunicare: ID applicazione (client), ID directory (tenant), indirizzo del sito SharePoint.
   Gli operatori devono avere permesso di modifica sulla raccolta Documenti del sito.

Sul telefono: scheda ONLINE › Configurazione (amministratore) › inserire i dati › Salva ›
accedere con l'account Microsoft aziendale. In alternativa i valori possono essere inseriti
in `Config.kt` (`DEFAULT_…`) prima di compilare.
</details>

## Struttura del codice

```
app/src/main/AndroidManifest.xml
app/src/main/res/                    icone e stringhe
app/src/main/java/it/impresasangalli/censimentomezzi/
   MainActivity.kt   interfaccia (Censimento / Elenco / Esporta / Online)
   Db.kt             archivio locale SQLite
   PhotoStore.kt     salvataggio foto in Pictures/CensimentoMezzi
   Exporter.kt       esportazione Excel e ZIP in Download/CensimentoMezzi
   XlsxWriter.kt     generatore .xlsx senza librerie esterne
   FilesProvider.kt  scambio file con l'app fotocamera e condivisione (Android 8-9)
   Config.kt         configurazione SharePoint e sessione (token cifrati nel Keystore)
   Auth.kt           accesso Microsoft 365 (OAuth 2.0 + PKCE)
   Graph.kt          client Microsoft Graph: foto in SharePoint, righe nel file Excel
   SyncEngine.kt     coda di invio automatica (JobScheduler)
build.ps1            compilazione su Windows
build.sh             compilazione da riga di comando (ambiente Linux originale)
LEGGIMI.txt          istruzioni originali
```
