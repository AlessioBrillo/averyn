# Progetto: Piattaforma sportiva open-source

**Documento di riferimento tecnico e strategico — v0.1**

- **Data:** 24 settembre 2026
- **Obiettivo:** progettare una piattaforma sportiva open-source, multipiattaforma e self-hostable, con ampia parità funzionale rispetto alle piattaforme sportive esistenti senza imporre un abbonamento per ogni funzione.
- **Target:** iOS, Android e Web.
- **Modalità di distribuzione:** cloud gestito opzionale + installazione self-hosted.
- **Priorità dichiarate:**
  1. GPS e tracking estremamente affidabili.
  2. Ampia parità funzionale con le piattaforme sportive esistenti.
  3. Analisi sportiva avanzata.
  4. Mappe e navigazione offline.
  5. Social e community.
  6. Supporto smartwatch e sensori.
- **Profilo di sviluppo:** sviluppatore avanzato, progetto inizialmente gestito da una sola persona.
- **Licenza:** da decidere.
- **Budget:** da definire.

---

## 1. Visione del prodotto

Il progetto dovrebbe essere una piattaforma sportiva completa e indipendente, capace di gestire:

- registrazione delle attività sportive;
- analisi e visualizzazione dei dati;
- importazione ed esportazione dei formati più comuni;
- mappe, percorsi e navigazione;
- segmenti e classifiche;
- profili, feed e interazioni social;
- sensori, smartwatch e piattaforme salute;
- hosting cloud gestito;
- installazione autonoma su infrastruttura propria;
- API pubbliche e integrazioni esterne;
- privacy e controllo dei dati da parte dell’utente.

L’obiettivo non dovrebbe essere una semplice copia dell’interfaccia di una piattaforma esistente, ma un sistema modulare che offra una funzionalità comparabile, lasciando agli utenti la possibilità di usare il servizio gestito oppure mantenere autonomamente i propri dati.

### Principi guida

1. **I dati dell’utente devono essere esportabili.**
2. **Le funzioni fondamentali non devono essere artificialmente bloccate da un paywall.**
3. **Il tracking deve funzionare anche senza connessione.**
4. **La qualità dei dati deve essere esplicita e misurabile.**
5. **Le metriche devono essere versionate e riproducibili.**
6. **Il sistema deve essere offline-first dove ha senso.**
7. **Il self-hosting deve essere una modalità reale, non un’aggiunta teorica.**
8. **La privacy deve essere progettata dall’inizio.**
9. **Il sistema deve essere estendibile senza trasformarsi prematuramente in una piattaforma di microservizi.**
10. **Il progetto deve essere sostenibile anche con un solo sviluppatore.**

---

## 2. Considerazioni sulle piattaforme di terze parti e sulle integrazioni

È consigliabile costruire il prodotto attorno a un proprio modello dati e a una propria pipeline di elaborazione, evitando di dipendere dall’API di piattaforme di terze parti per le funzionalità principali.

Le API di piattaforme di terze parti possono avere:

- limiti di rate;
- restrizioni sui casi d’uso;
- vincoli sui dati conservabili;
- regole specifiche per le applicazioni concorrenti;
- limitazioni su funzionalità che replicano direttamente il servizio originale;
- requisiti di revisione o approvazione.

### Strategia raccomandata

- usare i dati raccolti direttamente dalla propria app;
- supportare importazione da file GPX, FIT e TCX;
- consentire importazioni manuali o tramite integrazioni autorizzate;
- rispettare sempre i termini di servizio delle piattaforme esterne;
- non basare il core del prodotto su scraping o su API non garantite;
- mantenere un layer di integrazione separato dal dominio principale.

Il sistema deve essere autonomo anche se tutte le integrazioni esterne diventassero indisponibili.

---

## 3. Modello open-source e sostenibilità

Un possibile modello sostenibile:

### Core open-source

Comprende:

- app mobile;
- frontend web;
- backend principale;
- schema dati;
- motore di tracking;
- motore di analisi;
- import/export;
- API;
- strumenti base di amministrazione;
- deployment self-hosted.

### Servizio cloud gestito opzionale

Può offrire:

- hosting pronto all’uso;
- aggiornamenti automatici;
- backup gestiti;
- monitoraggio;
- elaborazione più potente;
- infrastruttura per mappe e routing;
- supporto tecnico;
- funzionalità operative che richiedono costi infrastrutturali.

### Possibili fonti di sostenibilità

- piani cloud gestiti;
- supporto professionale;
- consulenza e deployment;
- donazioni;
- sponsorizzazioni;
- grant open-source;
- servizi premium non essenziali;
- hosting per team, club o organizzazioni.

### Principio da evitare

Non bloccare:

- l’accesso ai dati personali;
- l’esportazione;
- il tracking di base;
- le metriche fondamentali;
- le funzioni di sicurezza;
- la possibilità di migrare a un’installazione self-hosted.

---

## 4. Aree funzionali principali

## 4.1 Tracking e registrazione attività

Funzioni previste:

- registrazione GPS;
- supporto a corsa, ciclismo, camminata, escursionismo e altri sport;
- pausa e ripresa;
- rilevamento automatico delle pause;
- registrazione in foreground e background;
- funzionamento con schermo bloccato;
- supporto offline;
- recupero dopo terminazione o interruzione dell’app;
- registrazione di altitudine;
- velocità e passo;
- frequenza cardiaca;
- cadenza;
- potenza;
- temperatura e altri sensori compatibili;
- segmentazione in lap;
- marcatori manuali;
- note dell’attività;
- allegati e foto;
- modalità risparmio energetico;
- diagnostica della qualità del segnale.

### Requisiti prioritari

- nessuna perdita silenziosa di dati;
- scrittura incrementale locale;
- recupero dopo crash;
- sincronizzazione ripetibile;
- gestione dei permessi chiara;
- comportamento prevedibile in background;
- consumo energetico misurato;
- indicazione della qualità del GPS;
- separazione tra dati grezzi e dati derivati.

---

## 4.2 Attività e storico

Ogni attività dovrebbe includere:

- sport e sottotipo;
- data e ora;
- durata totale;
- durata in movimento;
- durata in pausa;
- distanza;
- velocità media e massima;
- passo medio e migliore;
- dislivello positivo e negativo;
- altitudine minima, massima e media;
- frequenza cardiaca media e massima;
- potenza media e massima;
- cadenza;
- temperatura, quando disponibile;
- calorie, con indicazione se stimate;
- percorso sulla mappa;
- lap;
- segmenti attraversati;
- dispositivi utilizzati;
- qualità dei dati;
- versione dell’algoritmo di analisi;
- privacy e visibilità;
- allegati e commenti.

Funzioni:

- modifica dei dettagli;
- correzione del tipo di sport;
- ritaglio dell’inizio o della fine;
- unione o divisione delle attività;
- eliminazione;
- duplicazione controllata;
- esportazione;
- condivisione;
- gestione delle attività private;
- ricerca e filtri avanzati.

---

## 4.3 Statistiche e analisi

### Metriche fondamentali

- distanza;
- durata;
- tempo in movimento;
- tempo in pausa;
- velocità;
- passo;
- dislivello;
- pendenza;
- lap;
- distribuzione del passo;
- distribuzione della frequenza cardiaca;
- distribuzione della potenza;
- andamento nel tempo.

### Analisi multisensore

- zone di frequenza cardiaca;
- zone di potenza;
- cadenza;
- potenza normalizzata, dove applicabile;
- tempo in zona;
- carico di allenamento;
- record personali;
- confronti tra attività;
- confronti tra periodi;
- andamento settimanale, mensile e annuale.

### Modelli avanzati

Possibili funzionalità future:

- fitness;
- fatigue;
- form/readiness;
- training load;
- stress da allenamento;
- stima del recupero;
- previsione delle prestazioni;
- analisi della consistenza;
- suggerimenti di allenamento;
- rilevamento di anomalie.

Queste funzioni devono essere presentate come modelli e stime, non come misurazioni certe.

### Requisiti per ogni metrica

Ogni metrica dovrebbe dichiarare:

- nome;
- definizione;
- unità;
- formula o metodo;
- input necessari;
- requisiti minimi di qualità;
- gestione dei dati mancanti;
- versione dell’algoritmo;
- livello di affidabilità;
- eventuali limitazioni;
- test di riferimento.

Non bisogna mescolare risultati prodotti da versioni differenti senza renderlo esplicito.

---

## 4.4 Segmenti e classifiche

Funzioni possibili:

- creazione di segmenti;
- segmenti per corsa, ciclismo e altri sport compatibili;
- rilevamento del passaggio;
- confronto con i propri risultati;
- record personali;
- classifiche;
- filtri per genere, età o categoria, se legalmente e tecnicamente appropriato;
- classifiche private per gruppi;
- esclusione di attività non valide;
- rilevamento di GPS anomalo;
- gestione di segmenti contestati;
- moderazione;
- privacy e opt-out.

### Rischi tecnici

- falsi passaggi;
- GPS rumoroso;
- percorsi non confrontabili;
- attività motorizzate;
- dati manipolati;
- differenze tra dispositivi;
- cambiamenti del percorso;
- problemi di matching geografico.

Il motore di matching deve essere testato con fixture reali e casi limite.

---

## 4.5 Mappe, percorsi e navigazione

### Funzioni iniziali

- visualizzazione del percorso;
- importazione di GPX;
- creazione manuale di itinerari;
- modifica di punti e tratti;
- profili di attività;
- calcolo della distanza;
- stima del dislivello;
- visualizzazione di pendenze;
- indicazione di punti di interesse;
- salvataggio dei percorsi;
- condivisione;
- navigazione offline di base;
- avvisi di deviazione;
- stato di avanzamento.

### Funzioni successive

- navigazione turn-by-turn;
- ricalcolo;
- profili strada, sterrato, bici, trekking e corsa;
- evitamento di strade o superfici;
- itinerari circolari;
- percorsi suggeriti;
- supporto a DEM;
- mappe topografiche;
- mappe ciclabili;
- waypoint;
- cue sheet;
- navigazione su smartwatch.

### Stack possibile

- MapLibre Native su mobile;
- MapLibre GL JS sul web;
- PostGIS per dati geografici;
- Valhalla e GraphHopper da confrontare;
- DEM per il dislivello;
- storage versionato per pacchetti offline.

### Strategia consigliata

Non partire subito con il routing mondiale.

1. scegliere una regione pilota;
2. definire un set di profili;
3. confrontare qualità e costi dei motori;
4. validare il calcolo del dislivello;
5. progettare pacchetti offline versionati;
6. testare la navigazione in assenza totale di rete;
7. estendere gradualmente la copertura.

### Ordine consigliato per l’offline

1. visualizzazione di una traccia già scaricata;
2. importazione di una route;
3. posizione corrente;
4. indicazione di avanzamento;
5. avviso di deviazione;
6. istruzioni di navigazione;
7. ricalcolo offline o ibrido.

---

## 4.6 Social e community

Funzioni:

- profili;
- follower e following;
- feed;
- apprezzamenti/reazioni;
- commenti;
- condivisione delle attività;
- privacy per singola attività;
- nascondimento della partenza e dell’arrivo;
- zone private;
- gruppi;
- club;
- eventi;
- sfide;
- classifiche di gruppo;
- inviti;
- notifiche;
- segnalazioni;
- blocco utenti;
- moderazione;
- strumenti anti-abuso.

### Privacy sociale

Prevedere almeno:

- attività pubblica;
- attività per follower;
- attività privata;
- condivisione tramite link;
- visibilità limitata del percorso;
- oscuramento di aree sensibili;
- controllo dei commenti;
- blocco e silenziamento;
- cancellazione dei contenuti;
- esportazione dei dati sociali quando possibile.

---

## 4.7 Smartwatch, sensori e piattaforme salute

### Integrazioni possibili

- Bluetooth Low Energy;
- sensori di frequenza cardiaca;
- sensori di cadenza;
- misuratori di potenza;
- sensori di velocità;
- Apple Watch;
- Wear OS;
- HealthKit;
- Health Connect;
- dispositivi esterni;
- importazione da file FIT/TCX/GPX.

### Strategia a fasi

1. telefono come dispositivo principale;
2. sensore BLE di frequenza cardiaca;
3. HealthKit e Health Connect;
4. integrazione con smartwatch;
5. registrazione autonoma su smartwatch;
6. sincronizzazione tra watch e telefono;
7. supporto a sensori multipli;
8. gestione di conflitti e duplicati.

### Architettura consigliata

Usare adapter separati per ogni fonte:

- `BleSensorAdapter`;
- `HealthKitAdapter`;
- `HealthConnectAdapter`;
- `AppleWatchAdapter`;
- `WearOsAdapter`;
- `FileImportAdapter`.

Il dominio principale non deve conoscere i dettagli specifici di ciascuna piattaforma.

---

## 5. Architettura tecnica proposta

## 5.1 Mobile

### Opzione raccomandata

- Kotlin Multiplatform per dominio e logica condivisa;
- SwiftUI per iOS;
- Jetpack Compose per Android;
- implementazione nativa del tracking;
- adapter nativi per permessi, background e sensori.

### Motivo

Il tracking GPS e il comportamento in background sono aree sensibili alla piattaforma. È preferibile condividere:

- modelli;
- regole di dominio;
- metriche;
- serializzazione;
- sincronizzazione;
- validazione;
- test;

mantenendo nativi:

- location services;
- lifecycle;
- permessi;
- background execution;
- integrazione smartwatch;
- HealthKit;
- Health Connect;
- BLE a basso livello.

---

## 5.2 Web

Stack suggerito:

- React;
- TypeScript;
- gestione dello stato limitata e strutturata;
- rendering delle mappe tramite MapLibre GL JS;
- grafici modulari;
- interfaccia responsive;
- supporto PWA solo dove non compromette le funzionalità native;
- dashboard e analisi dettagliate;
- editor di percorsi;
- strumenti di amministrazione.

### Aree Web

#### Pubbliche

- profilo pubblico;
- attività condivise;
- percorsi pubblici;
- gruppi;
- pagine evento.

#### Autenticate

- dashboard;
- storico;
- dettaglio attività;
- grafici;
- editor;
- import/export;
- privacy;
- dispositivi;
- impostazioni.

#### Amministrazione

- moderazione;
- utenti;
- segnalazioni;
- job;
- storage;
- audit log;
- configurazione;
- metriche operative.

---

## 5.3 Backend

### Scelta iniziale

- Kotlin;
- Ktor;
- modular monolith;
- API REST o REST + WebSocket dove utile;
- job asincroni;
- PostgreSQL;
- PostGIS;
- object storage S3-compatible.

### Perché modular monolith

È più gestibile da un solo sviluppatore rispetto a un’architettura a microservizi e consente comunque di separare bene i domini.

I microservizi potranno essere introdotti solo quando esisteranno motivazioni concrete:

- carichi indipendenti;
- esigenze di scalabilità separate;
- team multipli;
- isolamento operativo;
- requisiti di deployment distinti.

### Moduli backend

- `auth`;
- `users`;
- `activities`;
- `activity-processing`;
- `metrics`;
- `routes`;
- `segments`;
- `equipment`;
- `social`;
- `notifications`;
- `privacy`;
- `integrations`;
- `moderation`;
- `admin`;
- `jobs`;
- `storage`;
- `audit`.

---

## 5.4 Database e storage

### PostgreSQL + PostGIS

Adatto per:

- utenti;
- attività;
- geometrie;
- percorsi;
- segmenti;
- relazioni social;
- query geografiche;
- ricerca spaziale;
- zone private;
- matching di tracce.

### Object storage

Usarlo per:

- file GPX;
- file FIT;
- file TCX;
- file originali;
- esportazioni;
- immagini;
- pacchetti di mappe;
- dati grezzi voluminosi;
- backup applicativi non direttamente relazionali.

### Regola importante

Non inserire ogni campione GPS come oggetto complesso in una tabella relazionale senza valutare volume, indicizzazione e costi. Prevedere una strategia ibrida per:

- campioni grezzi;
- campioni normalizzati;
- geometrie semplificate;
- dati aggregati;
- dati per grafici;
- dati per analisi avanzate.

---

## 6. Tracking Engine

Il tracking è il componente più critico del progetto.

## 6.1 Pipeline proposta

```text
Native Location Adapter
        |
        v
Raw Location Buffer
        |
        v
Validation / Normalization
        |
        v
Quality Assessment
        |
        v
Local Activity Store
        |
        +--> Live Metrics
        |
        +--> UI / Audio / Watch
        |
        v
Sync Queue
        |
        v
Server Upload
        |
        v
Server Analysis
```

### Principi

- conservare i campioni grezzi quando possibile;
- non sovrascrivere irreversibilmente i dati originali;
- separare acquisizione e analisi;
- versionare gli algoritmi;
- distinguere tempo totale, movimento e pausa;
- gestire campioni duplicati;
- gestire timestamp errati;
- filtrare outlier senza distruggere il dato originale;
- produrre indicatori di qualità.

---

## 6.2 Stati dell’attività

Stati suggeriti:

- `IDLE`;
- `PREPARING`;
- `RECORDING`;
- `PAUSED`;
- `STOPPING`;
- `COMPLETED`;
- `FAILED`.

Le transizioni devono essere esplicite e testabili.

Esempio:

```text
IDLE -> PREPARING -> RECORDING -> PAUSED -> RECORDING
                         |
                         v
                      STOPPING -> COMPLETED
                         |
                         v
                       FAILED
```

### Requisiti di recupero

Il sistema deve gestire:

- chiusura forzata dell’app;
- crash;
- riavvio del dispositivo;
- perdita temporanea del GPS;
- perdita della rete;
- sospensione del processo;
- cambio di autorizzazione;
- batteria critica;
- cambio di attività;
- aggiornamento dell’app;
- ripristino da backup locale, se previsto.

---

## 6.3 Qualità GPS

Valutare:

- accuratezza dichiarata dal sistema;
- intervallo tra campioni;
- velocità anomala;
- salti spaziali;
- timestamp non monotoni;
- campioni duplicati;
- perdita di segnale;
- deriva in aree urbane;
- comportamento in galleria;
- campioni con precisione scarsa;
- variazioni improvvise di altitudine.

Ogni attività dovrebbe avere un indicatore o un report di qualità, evitando di presentare ogni dato come ugualmente affidabile.

---

## 6.4 Test del tracking

Fixture minime:

- percorso rettilineo;
- curve strette;
- pausa completa;
- ripartenza;
- segnale intermittente;
- outlier GPS;
- frequenza di campionamento variabile;
- movimento lento;
- movimento veloce;
- altitudine rumorosa;
- attività lunga;
- attività con batteria bassa;
- perdita della rete;
- blocco schermo;
- app in background;
- terminazione forzata;
- ripristino;
- cambio di permessi;
- sensori mancanti.

### Matrice di test

Incrociare:

- versione del sistema operativo;
- modello del dispositivo;
- stato della batteria;
- qualità del segnale;
- stato dell’app;
- connettività;
- durata;
- sport;
- presenza di sensori;
- temperatura ambientale, se rilevante.

---

## 7. Specificità iOS

Componenti da valutare:

- Core Location;
- background location;
- HealthKit;
- WatchConnectivity;
- gestione delle autorizzazioni;
- background capabilities;
- sessioni di allenamento su Apple Watch.

Il sistema operativo può sospendere o limitare l’esecuzione dell’app. Per questo il tracking deve essere progettato rispettando i meccanismi ufficiali della piattaforma, con recovery e gestione esplicita del lifecycle.

Test da prevedere:

- autorizzazione negata;
- autorizzazione concessa solo durante l’uso;
- autorizzazione sempre, dove disponibile;
- posizione precisa o approssimata;
- blocco schermo;
- background prolungato;
- terminazione;
- riavvio;
- Apple Watch collegato o scollegato;
- perdita di comunicazione tra watch e telefono.

---

## 8. Specificità Android

Componenti da valutare:

- Fused Location Provider;
- foreground service;
- foreground service type `location`;
- eventuali tipologie relative alla salute;
- permessi di localizzazione;
- limitazioni dei produttori;
- ottimizzazioni della batteria;
- restrizioni del Play Store;
- comportamento in background;
- gestione di Android 14 e versioni successive.

Il tracking deve considerare:

- permessi di localizzazione precisa;
- permessi in background quando necessari;
- avvio e mantenimento del foreground service;
- notifica persistente;
- restrizioni di esecuzione;
- modalità risparmio energetico;
- impostazioni aggressive dei produttori;
- sospensione o terminazione del processo.

La documentazione e le policy Android devono essere verificate nuovamente durante l’implementazione, perché possono cambiare con le versioni del sistema operativo e con le policy dello store.

---

## 9. Sincronizzazione e modalità offline

La sincronizzazione deve essere:

- offline-first;
- incrementale;
- idempotente;
- ripetibile;
- resumable;
- tollerante agli errori;
- sicura rispetto a duplicati e conflitti.

## 9.1 Protocollo suggerito

Ogni operazione dovrebbe avere:

- `client_operation_id`;
- identificativo dell’entità;
- timestamp;
- tipo di operazione;
- versione locale;
- payload;
- stato;
- numero di tentativi;
- ultimo errore.

### Upload

Per file e attività di grandi dimensioni:

- chunk upload;
- checksum;
- retry parziali;
- ripresa dopo interruzione;
- verifica finale;
- idempotenza;
- limite di dimensione;
- cancellazione controllata.

### Stati di sincronizzazione

- `LOCAL_ONLY`;
- `QUEUED`;
- `UPLOADING`;
- `UPLOADED`;
- `PROCESSING`;
- `READY`;
- `FAILED_RETRYABLE`;
- `FAILED_PERMANENT`.

### Conflitti

Definire in anticipo il comportamento per:

- modifica locale e modifica server;
- cancellazione concorrente;
- duplicazione di attività;
- importazione dello stesso file;
- modifica di privacy;
- aggiornamento di metadati;
- cambiamento di versione dello schema.

---

## 10. Modello dati iniziale

Entità principali:

- `users`;
- `profiles`;
- `activities`;
- `activity_samples`;
- `activity_files`;
- `activity_analyses`;
- `laps`;
- `activity_segments`;
- `equipment`;
- `devices`;
- `routes`;
- `route_points`;
- `segments`;
- `follows`;
- `comments`;
- `reactions`;
- `groups`;
- `group_members`;
- `notifications`;
- `privacy_rules`;
- `sync_operations`;
- `audit_logs`;
- `jobs`.

### Campi trasversali consigliati

- `id`;
- `created_at`;
- `updated_at`;
- `deleted_at`, se si usa soft delete;
- `version`;
- `source`;
- `algorithm_version`;
- `visibility`;
- `owner_id`;
- `metadata`;
- `data_quality`.

Evitare di rendere tutto un JSON generico. Il JSON può essere utile per estensioni, ma i campi utilizzati per query, sicurezza, statistiche e relazioni devono restare strutturati.

---

## 11. Importazione ed esportazione

Formati prioritari:

- GPX;
- FIT;
- TCX;
- CSV per dati tabellari, dove utile;
- JSON per API e backup strutturati.

Funzioni:

- importazione manuale;
- importazione multipla;
- rilevamento duplicati;
- validazione del file;
- anteprima;
- correzione di timestamp;
- gestione di coordinate non valide;
- conversione tra formati;
- esportazione della singola attività;
- esportazione completa dell’account;
- esportazione di percorsi;
- esportazione dei dati sociali quando possibile.

L’importazione deve mantenere i dati originali e registrare la fonte, la data e gli eventuali passaggi di trasformazione.

---

## 12. Privacy e sicurezza

Requisiti fondamentali:

- minimizzazione dei dati;
- cifratura in transito;
- cifratura a riposo dove appropriato;
- gestione sicura delle sessioni;
- autenticazione robusta;
- supporto a passkey o MFA in una fase successiva;
- controllo accessi;
- audit log;
- cancellazione definitiva;
- esportazione;
- gestione delle zone private;
- oscuramento di partenza e arrivo;
- privacy per singola attività;
- revoca delle sessioni;
- rate limiting;
- protezione da abuso;
- backup e ripristino testati.

### Dati sensibili

I percorsi sportivi possono rivelare:

- abitazione;
- luogo di lavoro;
- abitudini;
- orari;
- condizioni fisiche;
- informazioni sanitarie indirette.

La privacy non deve essere una funzione secondaria.

---

## 13. Roadmap generale

## Fase 0 — Progettazione e PoC

Obiettivi:

- definire requisiti;
- stabilire il modello dati;
- progettare il tracking;
- realizzare un PoC nativo iOS/Android;
- testare background e recupero;
- scegliere il formato interno dei campioni;
- definire metriche fondamentali;
- preparare fixture e test.

## Fase 1 — MVP tracking

Funzioni:

- registrazione GPS;
- pausa/ripresa;
- scrittura locale incrementale;
- background;
- campioni grezzi;
- metriche live;
- esportazione GPX;
- recovery;
- diagnostica;
- test su dispositivi reali.

## Fase 2 — Core della piattaforma

Funzioni:

- account;
- autenticazione;
- API;
- PostgreSQL;
- object storage;
- sincronizzazione;
- job asincroni;
- dashboard web;
- dettaglio attività;
- storico;
- importazione GPX/FIT/TCX.

## Fase 3 — Analisi e interoperabilità

Funzioni:

- grafici;
- elevazione;
- lap;
- record personali;
- frequenza cardiaca;
- BLE;
- HealthKit;
- Health Connect;
- metriche versionate;
- confronto tra attività;
- statistiche periodiche.

## Fase 4 — Mappe e navigazione

Funzioni:

- editor di percorsi;
- mappe offline;
- routing per bici e trekking;
- profili di routing;
- dislivello;
- navigazione;
- avvisi di deviazione;
- waypoint;
- esportazione e condivisione.

## Fase 5 — Social e segmenti

Funzioni:

- profili;
- follow;
- feed;
- commenti;
- reazioni;
- privacy;
- gruppi;
- moderazione;
- segmenti;
- classifiche;
- contestazione dei risultati.

## Fase 6 — Funzioni avanzate

Funzioni potenziali:

- smartwatch autonomi;
- training load;
- fitness/fatigue;
- suggerimenti;
- sfide;
- eventi;
- API pubbliche;
- plugin;
- funzionalità per club;
- deployment cloud automatizzato;
- strumenti avanzati di osservabilità.

---

## 14. MVP personalizzato raccomandato

### MVP-0 — PoC tracking

- tracking nativo iOS;
- tracking nativo Android;
- storage locale incrementale;
- pausa/ripresa;
- background;
- campioni grezzi;
- metriche fondamentali;
- esportazione GPX;
- test con dispositivi reali;
- recovery;
- diagnostica.

### MVP-1 — Core

- account;
- attività;
- API;
- backend;
- PostgreSQL;
- object storage;
- sync;
- job;
- dashboard web;
- dettaglio attività.

### MVP-2 — Analisi e interoperabilità

- GPX/FIT/TCX;
- grafici;
- elevazione;
- lap;
- record personali;
- frequenza cardiaca;
- BLE;
- HealthKit;
- Health Connect;
- metriche versionate.

### MVP-3 — Mappe e navigazione

- route editor;
- mappe offline;
- routing bici/trekking;
- profilo altimetrico;
- navigazione;
- avviso di deviazione.

### MVP-4 — Social e segmenti

- profili;
- follow;
- feed;
- commenti;
- reazioni;
- privacy;
- gruppi;
- moderazione;
- segmenti;
- classifiche.

---

## 15. Struttura monorepo proposta

```text
/
├── apps/
│   ├── ios/
│   ├── android/
│   ├── web/
│   └── admin/
│
├── shared/
│   ├── domain/
│   ├── activity-engine/
│   ├── metrics/
│   ├── sync/
│   ├── serialization/
│   └── testing/
│
├── backend/
│   ├── auth/
│   ├── users/
│   ├── activities/
│   ├── processing/
│   ├── metrics/
│   ├── routes/
│   ├── segments/
│   ├── social/
│   ├── integrations/
│   ├── moderation/
│   └── admin/
│
├── geo/
│   ├── routing/
│   ├── elevation/
│   ├── map-data/
│   └── offline-packages/
│
├── infrastructure/
│   ├── docker/
│   ├── compose/
│   ├── migrations/
│   ├── backups/
│   └── observability/
│
├── tests/
│   ├── gps-fixtures/
│   ├── integration/
│   ├── performance/
│   └── device-matrix/
│
└── docs/
    ├── architecture/
    ├── api/
    ├── privacy/
    ├── deployment/
    └── product/
```

La struttura può essere adattata alle esigenze degli strumenti di build e del team. Il principio importante è mantenere separati:

- applicazioni;
- dominio condiviso;
- backend;
- dati geografici;
- infrastruttura;
- test;
- documentazione.

---

## 16. Deployment e self-hosting

## 16.1 Prima fase

Usare Docker Compose per:

- backend;
- database;
- object storage;
- worker;
- reverse proxy;
- sistema di osservabilità minimo.

## 16.2 Kubernetes

Non introdurlo inizialmente salvo requisiti concreti. Può essere valutato quando servono:

- scaling indipendente;
- alta disponibilità;
- deployment complessi;
- gestione di più ambienti;
- operazioni gestite da un team.

## 16.3 Due modalità di distribuzione

### Self-hosted

L’utente gestisce:

- database;
- storage;
- job;
- backup;
- aggiornamenti;
- mappe;
- routing;
- monitoraggio;
- dominio e certificati.

Fornire:

- Docker Compose;
- file `.env.example`;
- migrazioni;
- script di backup;
- procedura di restore;
- health check;
- documentazione di upgrade;
- strumenti di diagnostica.

### Cloud gestito

Il progetto può offrire:

- provisioning automatico;
- backup;
- aggiornamenti;
- monitoraggio;
- storage;
- elaborazione;
- gestione mappe;
- supporto;
- piani per singoli utenti, team e club.

Il codice condiviso dovrebbe essere il più possibile lo stesso tra self-hosted e cloud.

---

## 17. Licenza

Opzioni da valutare:

### AGPLv3

Possibili vantaggi:

- forte protezione della libertà del software anche quando viene eseguito come servizio di rete;
- incentivo a condividere modifiche al backend;
- adatta a progetti che vogliono evitare versioni proprietarie chiuse del server.

Possibili svantaggi:

- maggiore complessità nella compatibilità con alcune librerie e integrazioni;
- possibili ostacoli per aziende che preferiscono licenze permissive;
- necessità di valutare attentamente i confini tra componenti.

### MIT

Vantaggi:

- semplice;
- permissiva;
- facile adozione;
- buona compatibilità commerciale.

Svantaggi:

- consente la creazione di versioni proprietarie derivate;
- minore protezione contro la chiusura di modifiche distribuite come servizio.

### Apache-2.0

Vantaggi:

- permissiva;
- include disposizioni sui brevetti;
- adatta a ecosistemi tecnici e aziendali.

Svantaggi:

- non impone la condivisione delle modifiche;
- richiede valutazione della compatibilità con dipendenze e componenti.

La scelta deve essere fatta con una revisione legale, soprattutto se si utilizzano dati cartografici, librerie con licenze diverse, servizi esterni e contributi di terzi.

---

## 18. Osservabilità e operatività

Prevedere fin dall’inizio:

- logging strutturato;
- metriche backend;
- tracciamento dei job;
- error reporting;
- health check;
- monitoraggio dello storage;
- monitoraggio del database;
- metriche di sincronizzazione;
- percentuale di upload falliti;
- tempi di elaborazione;
- consumo di risorse;
- audit degli eventi sensibili.

### Metriche operative utili

- attività registrate per giorno;
- durata media del processing;
- percentuale di attività con errori;
- fallimenti di sincronizzazione;
- latenza API;
- errori per versione app;
- consumo di storage;
- uso del routing;
- download dei pacchetti offline;
- crash durante il tracking;
- consumo batteria durante sessioni di test.

---

## 19. Performance e costi

Aree da misurare:

- consumo batteria;
- memoria su dispositivo;
- scrittura su storage;
- dimensione dei file;
- tempo di upload;
- tempo di elaborazione;
- costo dello storage;
- costo del routing;
- costo delle mappe;
- query geografiche;
- costo delle classifiche;
- dimensione degli indici;
- traffico di rete.

### Principi

- aggregare i dati per grafici quando possibile;
- conservare i dati grezzi separatamente;
- applicare retention configurabile;
- usare cache per risultati costosi;
- non ricalcolare inutilmente tutte le attività;
- rendere i job ripetibili;
- evitare query geografiche senza indici;
- introdurre code solo dove servono;
- misurare prima di ottimizzare.

---

## 20. Sicurezza applicativa

Checklist iniziale:

- gestione sicura delle password;
- token con scadenza e revoca;
- protezione da brute force;
- rate limiting;
- validazione input;
- autorizzazione per oggetto;
- protezione da IDOR;
- upload con controlli di tipo e dimensione;
- scansione o validazione dei file;
- sanitizzazione dei contenuti;
- protezione XSS e CSRF dove applicabile;
- gestione sicura dei webhook;
- segreti fuori dal repository;
- aggiornamento delle dipendenze;
- backup cifrati;
- audit log;
- procedure di incident response.

---

## 21. Rischi principali

### Rischio 1 — Tracking inaffidabile

Mitigazione:

- PoC precoce;
- test su molti dispositivi;
- raccolta di fixture reali;
- logging diagnostico;
- recupero locale;
- metriche di qualità.

### Rischio 2 — Scope troppo ampio

Mitigazione:

- roadmap a fasi;
- MVP molto focalizzato;
- evitare di sviluppare subito tutte le funzioni social;
- rimandare modelli avanzati;
- limitare inizialmente la copertura geografica.

### Rischio 3 — Costi cartografici

Mitigazione:

- benchmark iniziale;
- scelta consapevole delle fonti dati;
- cache;
- pacchetti regionali;
- limiti di utilizzo;
- architettura sostituibile.

### Rischio 4 — Complessità delle integrazioni

Mitigazione:

- adapter indipendenti;
- core autonomo;
- import/export come fallback;
- test per ogni fonte;
- gestione dei duplicati.

### Rischio 5 — Moderazione e abuso

Mitigazione:

- strumenti di segnalazione;
- blocco utenti;
- audit;
- rate limiting;
- regole chiare;
- pannello amministrativo.

### Rischio 6 — Manutenzione da solo sviluppatore

Mitigazione:

- modular monolith;
- automazione CI/CD;
- test automatici;
- documentazione;
- dipendenze limitate;
- infrastruttura semplice;
- osservabilità;
- issue tracking strutturato.

---

## 22. Ordine di lavoro consigliato

1. Definire i requisiti del tracking.
2. Definire il modello dei campioni GPS.
3. Realizzare l’adapter iOS.
4. Realizzare l’adapter Android.
5. Implementare lo storage locale.
6. Implementare pause/ripresa e recovery.
7. Implementare il motore di metriche fondamentali.
8. Creare fixture GPS e test.
9. Validare il comportamento su dispositivi reali.
10. Progettare il protocollo di sincronizzazione.
11. Implementare il backend minimo.
12. Implementare upload e processing.
13. Creare la pagina web di dettaglio attività.
14. Aggiungere import/export.
15. Aggiungere analisi avanzate.
16. Aggiungere mappe e routing.
17. Aggiungere social.
18. Aggiungere segmenti.
19. Aggiungere smartwatch autonomi.
20. Rafforzare self-hosting, osservabilità e documentazione.

---

## 23. Primo deliverable consigliato

Il primo deliverable concreto dovrebbe essere composto da:

### A. Technical Design Document v0.1

Contenuti:

- requisiti funzionali;
- requisiti non funzionali;
- stati del tracking;
- modello dati dei campioni;
- architettura mobile;
- architettura backend;
- protocollo di sync;
- modello di qualità GPS;
- strategia di test;
- decisioni aperte;
- rischi.

### B. Tracking Engine PoC

Deve dimostrare:

- registrazione GPS;
- scrittura locale incrementale;
- background;
- pausa/ripresa;
- recovery;
- esportazione;
- metriche base;
- diagnostica;
- test su dispositivi reali.

### C. Decision log

Per ogni scelta importante registrare:

- problema;
- opzioni considerate;
- decisione;
- motivazione;
- conseguenze;
- possibilità di revisione.

---

## 24. Decisioni ancora aperte

- Nome del progetto.
- Licenza definitiva.
- Linguaggio e framework backend definitivi.
- Kotlin Multiplatform oppure altra strategia mobile.
- Database locale mobile.
- Formato interno dei campioni.
- Frequenza di campionamento.
- Strategia di filtraggio GPS.
- Fonti cartografiche.
- Motore di routing.
- Fonte DEM.
- Provider cloud.
- Sistema di autenticazione.
- Strategia di notifiche.
- Politica di retention.
- Modello economico.
- Politica di moderazione.
- Supporto iniziale agli sport.
- Regione pilota per mappe.
- Livello di compatibilità con dispositivi specifici.

---

## 25. Checklist iniziale

### Prodotto

- [ ] Definire gli sport supportati nell’MVP.
- [ ] Definire il pubblico iniziale.
- [ ] Definire le funzioni irrinunciabili.
- [ ] Definire la politica di privacy.
- [ ] Definire la licenza.
- [ ] Definire il modello di sostenibilità.

### Tracking

- [ ] Definire il modello dei campioni.
- [ ] Implementare buffer locale.
- [ ] Implementare recovery.
- [ ] Implementare pausa/ripresa.
- [ ] Testare background.
- [ ] Testare batteria.
- [ ] Testare perdita GPS.
- [ ] Testare perdita rete.
- [ ] Creare fixture.
- [ ] Definire metriche di qualità.

### Backend

- [ ] Creare schema PostgreSQL.
- [ ] Configurare PostGIS.
- [ ] Configurare object storage.
- [ ] Implementare autenticazione.
- [ ] Implementare upload.
- [ ] Implementare processing.
- [ ] Implementare idempotenza.
- [ ] Implementare job retry.
- [ ] Implementare backup.
- [ ] Implementare audit log.

### Web

- [ ] Dashboard.
- [ ] Storico attività.
- [ ] Dettaglio attività.
- [ ] Mappa.
- [ ] Grafici.
- [ ] Import/export.
- [ ] Impostazioni privacy.
- [ ] Admin minimo.

### Mappe

- [ ] Scegliere fonte dati.
- [ ] Scegliere motore routing.
- [ ] Validare dislivello.
- [ ] Definire pacchetti offline.
- [ ] Testare deviazioni.
- [ ] Definire aggiornamenti dati.

### Social

- [ ] Profili.
- [ ] Follow.
- [ ] Feed.
- [ ] Commenti.
- [ ] Reazioni.
- [ ] Blocco utenti.
- [ ] Segnalazioni.
- [ ] Moderazione.
- [ ] Privacy.

### Self-hosting

- [ ] Docker Compose.
- [ ] `.env.example`.
- [ ] Migrazioni.
- [ ] Backup.
- [ ] Restore.
- [ ] Health check.
- [ ] Upgrade guide.
- [ ] Troubleshooting.

---

## 26. Sintesi finale

La strategia più solida consiste nel partire da un **motore di tracking affidabile e verificabile**, non dalla componente social o dall’interfaccia.

L’ordine consigliato è:

1. tracking nativo e robusto;
2. storage locale e recovery;
3. metriche fondamentali;
4. sincronizzazione affidabile;
5. backend e dashboard;
6. import/export;
7. analisi avanzate;
8. mappe e navigazione;
9. social;
10. segmenti;
11. smartwatch autonomi;
12. ecosistema self-hosted e cloud gestito.

La decisione architetturale più importante è separare chiaramente:

- acquisizione dei dati;
- conservazione dei dati originali;
- normalizzazione;
- analisi;
- sincronizzazione;
- visualizzazione;
- integrazioni esterne.

Questo rende il progetto più testabile, più resiliente e più semplice da estendere nel tempo.

Il rischio maggiore non è implementare una singola funzione, ma tentare di realizzare contemporaneamente tracking, analisi, mappe, social, smartwatch, self-hosting e cloud. Per un progetto gestito inizialmente da una sola persona, è essenziale procedere per vertical slice e validare presto la parte GPS su dispositivi reali.

---

## 27. Riferimenti tecnici da consultare

Durante l’implementazione verificare sempre la documentazione aggiornata di:

- Apple Core Location;
- Apple background execution;
- HealthKit;
- WatchConnectivity;
- Android Fused Location Provider;
- Android foreground services;
- Android background location;
- Google Play policy per la localizzazione;
- MapLibre;
- PostgreSQL;
- PostGIS;
- Valhalla;
- GraphHopper;
- OpenStreetMap e relative policy;
- formati GPX, FIT e TCX;
- standard Bluetooth Low Energy;
- Health Connect;
- Docker;
- Ktor;
- Kotlin Multiplatform.

Le policy di sistema operativo, store, provider cartografici e servizi esterni possono cambiare. Prima di una release pubblica è necessario ricontrollare i requisiti vigenti.
