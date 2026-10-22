# TDD-0011: Il server sul telefono — lettura, copia offline e summary locale

- **Status:** implemented
- **Version:** 0.1
- **Author:** @AlessioBrillo
- **Date:** 2026-10-22
- **Related:** ADR-0015 (store locale), ADR-0020/0021 (canale HR e consenso), ADR-0025 (copia del server sul
  dispositivo), TDD-0002 (sync), TDD-0009 (analisi)

## 1. Summary

Fino a TDD-0010 il telefono registra e carica, ma non legge: la storia di un account si vede solo sul web.
Questo incremento chiude il ciclo — il telefono legge la lista delle attività e il dettaglio di ciascuna — e
rende la lettura possibile senza connessione, con due pezzi di dati derivati che vivono sul dispositivo:

- la **copia del server** (cache): i riassunti della lista e gli ultimi dettagli aperti, con una scadenza per
  volume e per numero (ADR-0025);
- il **summary locale** (`<id>.summary.json`): i numeri di un'attività appena conclusa, scritti accanto al
  file dell'attività, così una corsa non ancora sincronizzata mostra distanza e passo anche offline.

Nessun endpoint nuovo: il server espone già `GET /v1/activities`, `/{id}`, `/{id}/track` e `/{id}/analysis`.
L'unico cambiamento di contratto è additivo: `apiVersion` e `minClientVersion` in `/v1/client-config`, che
producono un avviso non bloccante quando app e server non combaciano.

## 2. Goals and non-goals

### Obiettivi

1. Il telefono mostra la lista delle attività dell'account (quelle del server e quelle non ancora caricate) e il
   dettaglio di ciascuna (numeri, split, curva del profilo altimetrico, curva del battito).
2. Senza connessione la lista e i dettagli già aperti restano leggibili, e la schermata dice che sono una copia.
3. Un'attività conclusa sul dispositivo mostra i suoi numeri anche prima (e senza) la sincronizzazione.
4. Un'attività cancellata sul server sparisce dalla lista e resta nei "dati sul dispositivo", fino a che l'utente
   la elimina.
5. Un'analisi che il server sta calcolando è uno stato ("in calcolo"), non un errore.
6. App e server che non combaciano producono un avviso, mai un blocco.

### Fuori perimetro

- La mappa: nessuna basemap e nessun MapLibre Native in questo incremento. Il tracciato viene copiato lo
  stesso (è il GeoJSON del server), pronto per lo slice successivo.
- I record personali, il profilo, le unità di misura e il massimo HR: fuori.
- Il sensore HR su iOS: fuori (l'app iOS non ha adapter BLE).
- La cancellazione di un'attività **sul server** dal telefono: il mobile è in sola lettura sul server.
- L'analisi calcolata in locale per un'attività non ancora sincronizzata: il dettaglio locale mostra il
  summary, gli split arrivano con la sincronizzazione.

## 3. Functional requirements

| # | Requisito | Come è soddisfatto |
|---|---|---|
| F1 | Lista delle attività dell'account | `GET /v1/activities?limit=50`, cursore opaco, `nextCursor` |
| F2 | Dettaglio di un'attività | `GET /v1/activities/{id}` (+ `/{id}/track`, `/{id}/analysis`) |
| F3 | Lista senza connessione | copia dei riassunti (`summaries.json`), con il timestamp della copia |
| F4 | Dettaglio senza connessione | copia degli ultimi dettagli aperti (`details/<id>.json`) |
| F5 | Numeri di un'attività locale | summary locale `<id>.summary.json` |
| F6 | Attività cancellata sul server | sparisce dalla lista, resta nei "dati sul dispositivo" |
| F7 | Analisi in calcolo | `503` + `Retry-After` → stato "in calcolo", nuova lettura dopo l'attesa |
| F8 | Versioni incompatibili | `apiVersion`/`minClientVersion` in `/v1/client-config` → banner non bloccante |
| F9 | Cancellazione locale | rimuove file grezzo, summary, stato di sync e sidecar HR |

## 4. Non-functional requirements

- **Privacy.** La copia contiene coordinate e serie HR: è della stessa classe dei dati di origine
  (`docs/privacy/data-classification.md`). Nessun token nella copia, nessun file grezzo, nessun dato HR
  "Health" (intervalli RR). La copia è esclusa dal backup, si cancella a ogni fine sessione e l'utente può
  cancellarla a mano (ADR-0025).
- **Consistenza.** Il server è l'autorità. La copia è sempre "best effort": può essere più vecchia del server,
  e ogni schermata dice quando quello che mostra è una copia.
- **Prestazioni.** Tutto il I/O della copia e dello store locale gira fuori dal thread principale
  (`Dispatchers.Default`); nessuna operazione proporzionale alla lunghezza dell'attività gira nel ciclo della UI.
- **Batteria.** Nessun polling in background: la copia si aggiorna quando l'utente apre la schermata.
- **Self-hosting.** Nessuna modifica allo schema del database; una variabile d'ambiente in più
  (`AVERYN_MIN_CLIENT_VERSION`), facoltativa.

## 5. Design

### 5.1 I moduli

```text
backend ─────────────► shared:api   (ActivitySummary, ActivityPage, Track, ClientConfig, Api)
   │
   └─────────────────► shared:analysis (ActivityAnalysis, già condiviso)

apps Android/iOS ───► shared:client (AverynClient, ActivityCache, HistoryRepository)
                        ├──► shared:api
                        ├──► shared:analysis
                        ├──► shared:tracking  (ActivityStore, LocalActivitySummary)
                        └──► shared:sync      (SyncStatusStore)
```

`shared:api` è puro: solo DTO e costanti, nessun Ktor. Il backend dipende da `shared:api` e non da
`shared:client`: il contratto vive in un posto solo, e il client Ktor vive solo dove serve.

### 5.2 Il merge della lista

La lista è **guidata dal server**: una riga è un'attività del server. Si aggiungono le attività che sono solo
sul dispositivo e che il server non ha ancora (non caricate, in coda, o rifiutate). Un'attività che il server ha
e che il dispositivo ha pure è **una riga sola**, con i numeri del server.

Un'attività locale `READY` che il server non ha più (è stata cancellata lì) **non compare nella lista**: resta
nei "dati sul dispositivo", dove l'utente può cancellarla. Un `410` del server (attività già cancellata)produce
lo stesso effetto.

```text
server ──► [ attività del server ] ──┐
                                     ├──► merge ──► lista
locale ──► [ solo qui, non ancora ] ─┘
```

### 5.3 La copia sul dispositivo (ADR-0025)

```text
<cache>/manifest.json          versione dello schema e server a cui appartiene
<cache>/summaries.json         i riassunti caricati, il cursore, quando sono stati letti
<cache>/details/<id>.json      il dettaglio aperto: riassunto, tracciato, analisi
<cache>/details/index.json     quando l'ultima volta è stato aperto, quanti byte occupa
```

- Scritture atomiche (file temporaneo + rinomina): un kill lascia il file precedente, mai mezzo JSON.
- Limiti: 10 dettagli, 50 MB, 500 riassunti. Il più vecchio esce per fare spazio.
- Il manifest legge il server: una copia di un altro server non viene mai mostrata (e viene cancellata).
- Un `404` su un dettaglio cancella il dettaglio copiato e il riassunto dalla lista.
- Il tracciato è copiato ma **non disegnato**: la mappa arriva con lo slice successivo.

### 5.4 Il summary locale

`<id>.summary.json`, accanto a `<id>.jsonl`. Non è un'attività: non viene caricato, non viene esportato e non
contiene coordinate — solo i numeri che la lista mostra.

È scritto in `ActivityRecorder.buildCompletedActivity()`, che copre l'arresto normale, il recupero di
un'attività rimasta in `STOPPING` e la chiusura delle attività più vecchie. I numeri vengono dagli stessi
strumenti del server (`ActivitySnapshot`, `QualityReport`, `HeartRateMetrics` con `hr-v2`), così il summary
locale e quello del server non possono divergere. Le attività registrate da una build precedente lo ricevono da
un ricalcolo al primo avvio (`backfillLocalSummaries`).

### 5.5 Le versioni

`/v1/client-config` espone `apiVersion` (la versione del contratto che il server parla) e `minClientVersion`
(la versione minima dell'app che l'istanza supporta, facoltativa). L'app confronta i numeri con la propria
versione e mostra un banner: il server è più vecchio, il server è più nuovo, l'app è sotto il minimo. Il
contratto è additivo dentro `apiVersion = 1`, quindi un client vecchio continua a funzionare.

### 5.6 I stati dell'analisi

`GET /v1/activities/{id}/analysis` risponde `503` con `Retry-After: 5` quando il server sta calcolando. Per il
client quello è uno stato, non un errore: il dettaglio mostra "in calcolo" e rilegge dopo l'attesa indicata
(al massimo tre volte, poi un pulsante "Riprova"). Le altre risposte temporanee (429, 502, 504, rete) sono
ripetute dal client, sempre aspettando quanto chiesto dal server.

## 6. Test strategy

| Livello | Cosa copre |
|---|---|
| `shared:api` | decoding del contratto, campi sconosciuti ignorati, confronto delle versioni |
| `shared:client` | cache (limiti, LRU, schema, server diverso, file rotto), client (401/404/429/503, `Retry-After`), merge della lista, 404, analisi in calcolo |
| `shared:tracking` | il summary locale: scrittura, pausa, recovery, delete, ricalcolo, backfill |
| `backend` | `/v1/client-config` espone le versioni; un minimo non valido è ignorato |
| device matrix | S23–S28 (vedi `docs/testing/device-matrix.md`) |

## 7. Rollout and migration

- Nessuna migrazione: il database non cambia e il formato dei file locali non cambia (il summary è un file
  nuovo, accanto agli altri).
- La copia del server è nuova e vuota: si riempie al primo avvio della schermata.
- Il summary locale manca per le attività registrate prima: il ricalcolo al primo avvio (in background) lo
  scrive; se non è ancora successo, l'attività mostra i numeri appena il server li ha.
- Il banner delle versioni compare solo se le versioni non combaciano: un server e un app della stessa versione
  non mostrano nulla.

## 8. Risks and mitigations

| Rischio | Mitigazione |
|---|---|
| Una copia di dati che il dispositivo non aveva | ADR-0025, wipe a ogni fine sessione, esclusione dal backup, cancellazione a mano |
| Numeri locali che divergono da quelli del server | stessi algoritmi (`hr-v2`, `distance-v1`…), versioni nel summary, il server vince sempre, il summary locale è etichettato |
| Una copia che non coincide con il server | il server è l'autorità, il `404` rimuove, la schermata mostra il timestamp |
| Il volume della copia | 10 dettagli, 50 MB, 500 riassunti, LRU |
| L'app e il server che non combaciano | avviso non bloccante, contratto additivo |
| Il lavoro che si accumula in `shared:client` | moduli separati (`shared:api` puro, `shared:client` Ktor), nessuna dipendenza dal backend |

## 9. Open questions

- Il tracciato copiato non è ancora disegnato: la mappa (MapLibre Native) è lo slice successivo e userà la copia
  che questa slice lascia sul dispositivo.
- L'analisi calcolata in locale per le attività non sincronizzate non è in questo incremento: si valuta quando il
  costo di un replay sul telefono è misurato su un dispositivo vero.
