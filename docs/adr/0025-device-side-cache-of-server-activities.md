# ADR-0025: La copia del server sul dispositivo — una cache con una fine, non un mirror

- **Status:** accepted
- **Date:** 2026-10-22
- **Deciders:** @AlessioBrillo

## Context and problem

TDD-0011 chiude il ciclo: il telefono legge la lista delle attività dell'account e il dettaglio di ciascuna.
Il telefono è, però, un dispositivo che va in treno, in montagna e in metrotro: senza connessione la lista non
si apre, e il lavoro di registrazione — l'unica cosa che il prodotto fa davvero — diventa scollegato da tutto
il resto.

Il server ha già i dati, e li ha con un contratto semplice (`GET /v1/activities`, `/{id}`, `/{id}/track`,
`/{id}/analysis`). La domanda è se il dispositivo debba tenere una copia, e di che copia.

Le copie hanno un costo che va detto adesso, perché è il punto vero:

1. **Il tracciato èRestricted.** Le coordinate di una corsa sono dati personali, non un numero. Una copia sul
   telefono allunga il tempo in cui quei dati esistono fuori dal controllo del server.
2. **Il telefono si perde, si ruba, si presta.** Un dispositivo finito in mano sbagliata mostra la storia
   completa di un account.
3. **Il backup.** Un backup automatico del telefono che porta via la copia porta via anche le coordinate,
   fuori dal perimetro che l'utente crede di avere scelto.

D'altra parte, il telefono **è già** un dispositivo che tiene i dati delle attività: il file grezzo del
telefono contiene i campioni GPS di ogni attività (ADR-0015), e il sidecar HR tiene i campioni del battito
(ADR-0020/0021). La copia del server non introduce una classe di dati nuova sul dispositivo: ne introduce una
**nuova provenienza** (un'altra attività, o un'attività di un altro dispositivo), e quindi una copia che il
telefono non si è guadagnato da solo.

Infine, i numeri locali (`<id>.summary.json`) sono una cosa diversa: sono derivati dall'attività che il
telefono ha registrato, non una copia di un'altra attività. Non c'è scelta: o si calcolano e si salvano, o la
riga di un'attività non ancora sincronizzata resta senza numeri.

## Options considered

1. **Nessuna copia.** Il telefono legge tutto dal server. La cosa più semplice e la più privata, e la più
   inutile: senza rete non c'è history, e la metà delle attività (quelle non ancora caricate) non c'è comunque.
2. **Un mirror completo dell'account.** Il server manda tutto al dispositivo, e il telefono diventa il secondo
   sistema di record. Tanti dati, tanto lavoro, due fonti di verità che divergono (un'attività cancellata sul
   server resta sul telefono per sempre, per esempio) e la superficie di privacy più grande possibile.
3. **Una copia con una fine, limitata a quello che l'utente ha aperto.** Il dispositivo tiene i riassunti della
   lista e gli ultimi dettagli aperti, con un limite di numero e di volume, e la copia finisce con la sessione.
4. **Solo i numeri, nessun tracciato.** La copia si limita ai riassunti: la lista si legge offline, il dettaglio
   no. Meno dati, ma il dettaglio è la metà del valore.

## Decision

We choose **Option 3**: una copia con una fine.

- **Cosa viene copiato.** I riassunti della lista (`ActivitySummary`) e, per le attività che l'utente apre, il
  dettaglio: riassunto, tracciato (GeoJSON) e analisi. Nient'altro: nessun file grezzo, nessun token, nessun
  intervallo RR (dato "Health", ADR-0013).
- **Quanto.** 10 dettagli, 50 MB, 500 riassunti. Il meno recente esce per fare spazio. La copia è
  best-effort: il sistema operativo può toglierla, e va bene così — è una copia, non il sistema di record.
- **Quando finisce.** Alla fine della sessione (sign-out, o un refresh token che il provider rifiuta) e quando
  cambia il server. Il manifest della copia ricorda a quale server appartiene: la copia di un altro server non
  viene mai mostrata.
- **Il backup.** La copia è esclusa dal backup del dispositivo (`isExcludedFromBackup` su iOS, `allowBackup=false`
  su Android). Il telefono non porta via la copia del server nel cloud di Apple o di Google.
- **L'utente può cancellarla.** La schermata dell'account mostra quanto occupa e la cancella con un tocco.
- **Il server resta l'autorità.** La copia è datata e lo dice: ogni schermata che mostra una copia dice da
  quando. Un `404` cancella il dettaglio copiato; un'attività cancellata sul server sparisce dalla lista, e i
  suoi file restano sul dispositivo fino a che l'utente li elimina.
- **Il summary locale è un'altra cosa.** I numeri di un'attività registrata dal telefono sono derivati da
  quello che il telefono ha misurato: non sono una copia, sono un calcolo, e non hanno le implicazioni di
  privacy di una copia di un'altra attività. Restano accanto al file dell'attività, con la stessa vita.

## Consequences

- Good: la history si legge senza rete, e le attività appena registrate mostrano i numeri subito.
- Good: la superficie dei dati nuovi è limitata e dichiarata: tracciati e serie HR di ciò che l'utente ha
  aperto, nient'altro.
- Good: la copia non può diventare una seconda fonte di verità: è datata, limitata e cancellabile.
- Bad / costs: il dispositivo tiene una copia di dati che l'utente non ha creato lì (un'attività importata da
  un GPX, un'attività registrata su un altro telefono). Il threat model lo dice (ADR-0025 → threat model).
- Bad / costs: la copia è un altro posto dove il formato può evolvere. Per questo ha un `schemaVersion`: una
  copia di un'altra versione viene buttata e ricostruita.
- Bad / costs: senza rete la lista può mostrare attività che il server ha già cancellato, fino al prossimo
  refresh. È il prezzo di una copia, ed è dichiarato a schermo.
- Follow-ups: il tracciato copiato non è ancora disegnato (la mappa è lo slice successivo); se la mappa arriva,
  la copia le serve così com'è.

## Revisit when

- Il volume reale della copia supera il limite (i 50 MB) su un account vero: allora il limite va cambiato, o
  la copia diventa selettiva (per esempio solo i riassunti).
- La copia viene eseguita da un servizio in background (per una notifica push, o per un widget): allora la sua
  vita non è più legata alla sessione e la decisione va rifatta da capo.
- Entra in scena un dato "Health" (gli intervalli RR): la copia non può contenerli senza un consenso e una
  revisione dedicated (ADR-0013, ADR-0021).
