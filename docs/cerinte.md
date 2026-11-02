# sd-storage — cerințe, model de date, scenariu, protocol

## 1. Tema

Sistemul stochează fișiere pe mai multe noduri. Fiecare fișier are două copii pe noduri distincte
și rămâne accesibil când un nod cade. Nodurile noi se adaugă fără oprirea serviciului și sunt
descoperite automat.

- **Clientul** are o interfață în terminal: `put`, `get`, `ls`, `rm`, `nodes`.
- **Nodurile de stocare** păstrează copiile într-un director local (`data/n2/`).
- **Coordonatorul** gestionează metadatele. Nu este un proces separat, ci unul dintre noduri; până
  la Etapa 5 (Bully) este stabilit cu argumentul `--coordinator`.

Metadatele trec prin coordonator, iar conținutul fișierelor circulă direct între client și noduri,
ca în HDFS. Astfel coordonatorul nu devine gâtul de sticlă al sistemului.

### Ce prevede fiecare etapă

| Etapa (săpt.) | Adaugă | Ce este deja prevăzut în cod |
|---|---|---|
| 2 (4)  | transfer TCP, descoperire prin UDP multicast | fiecare nod are ID, adresă și port, anunțate prin `HELLO`; nu există listă fixă de vecini |
| 3 (6)  | heartbeat, lista nodurilor disponibile | `NodeInfo` are `state` (ACTIV / SUSPECT / CAZUT) și `lastSeen`; `HELLO` periodic servește deja ca semn de viață |
| 4 (8)  | minimum 3 noduri, distribuirea încărcării, nod adăugat la rulare | regula de alegere a nodurilor este în `MetadataStore.allocate`; un nod nou intră în listă la primul `HELLO` |
| 5 (10) | alegerea coordonatorului prin Bully | toate nodurile rulează același cod și țin lista nodurilor; prioritatea este numărul din ID |
| 6 (12) | RMI/gRPC, două copii, recuperarea metadatelor | fiecare copie are un fișier `.meta` lângă ea; mesajul `INVENTORY` raportează ce deține un nod |
| 7 (14) | demonstrația finală | scenariul din secțiunea 5 |

## 2. Cerințe funcționale

| Operație | Comanda | Pașii prin sistem | Rezultat | Erori tratate |
|---|---|---|---|---|
| Încărcare | `put raport.pdf` | 1. clientul calculează SHA-256 și trimite `ALLOCATE` coordonatorului; acesta rezervă numele, generează ID-ul și alege 2 noduri active distincte; 2. clientul trimite `PUT` + octeții primului nod; 3. primul nod verifică checksum-ul și trimite `REPLICATE` + octeții celui de-al doilea; 4. după confirmarea copiei, primul nod trimite `COMMIT` coordonatorului; 5. clientul primește `STORED` | ID-ul fișierului și cele două noduri | fișier local inexistent sau peste 1 GiB: refuz local · mai puțin de 2 noduri active: refuz, nimic stocat · nume existent: refuz · primul nod nu răspunde: clientul cere o nouă alocare fără el (același ID), maximum 3 încercări · copia a doua eșuează: primul nod își șterge copia și raportează nodul căzut; clientul reîncearcă fără el · checksum diferit la un nod: copia este respinsă |
| Descărcare | `get raport.pdf ./out/` | 1. clientul trimite `LOCATE`; coordonatorul răspunde cu ID-ul, checksum-ul și adresele copiilor; 2. clientul trimite `GET` primului nod și primește `DATA` + octeții; 3. verifică checksum-ul și mută fișierul din `.part` în numele final | fișierul salvat local, cu checksum verificat | fișier necunoscut: `NOT_FOUND` · primul nod nu răspunde, nu are copia sau checksum greșit: încearcă a doua copie · nicio copie accesibilă: eroare explicită, cu motivul pentru fiecare nod |
| Listare | `ls` | 1. clientul trimite `LIST`; 2. coordonatorul răspunde cu `FILES count=N` urmat de N linii `FILE` | tabel: nume, dimensiune, noduri, ID | coordonator inaccesibil: clientul îl caută din nou prin multicast (dacă nu a fost dat cu `--coordinator`), altfel eroare |
| Ștergere | `rm raport.pdf` | 1. clientul trimite `REMOVE`; 2. coordonatorul scoate fișierul din metadate; 3. trimite `DELETE` fiecărui nod care are o copie | nodurile de pe care s-a șters | fișier necunoscut: `NOT_FOUND` · un nod cu o copie este căzut: fișierul este șters din metadate oricum (nu mai apare în `ls`, nu mai poate fi descărcat), iar clientul vede ce noduri au rămas cu o copie orfană. Copia orfană nu este vizibilă; în Etapa 6, la reconstruirea metadatelor, coordonatorul va compara inventarul nodurilor cu ștergerile și va elimina orfanii |
| Starea sistemului | `nodes` | 1. clientul trimite `NODES`; 2. coordonatorul răspunde cu `NODELIST`: ID, adresă, port, stare, vechimea ultimului semnal, coordonatorul | tabel cu nodurile și rolurile | coordonator inaccesibil: ca la `ls` |
| Adăugarea unui nod la rulare | (sistem) pornirea unui proces `StorageNode` cu un ID nou | 1. nodul nou intră în grupul multicast și anunță `HELLO`; 2. fiecare nod existent îl adaugă în lista lui și răspunde imediat cu propriul `HELLO`; 3. nodul nou află astfel toate celelalte noduri și coordonatorul; 4. coordonatorul îl poate folosi de la următorul `ALLOCATE` | nodul apare în `nodes` în sub 3 s, fără repornirea altor procese | ID duplicat: nodurile raportează conflictul în jurnal · port ocupat: nodul nou se oprește cu codul 1 |

### Decizii

- **Identificare.** Utilizatorul folosește *numele*; intern, fișierul este identificat prin *UUID*,
  generat de coordonator. Copiile de pe disc sunt `<uuid>.bin` + `<uuid>.meta`, deci două fișiere cu
  același nume în momente diferite nu se încurcă.
- **Nume existent.** Încărcarea unui nume existent este refuzată (`ERROR code=exists`); utilizatorul
  șterge întâi cu `rm`. Fără suprascriere, nu există momente în care cele două copii au versiuni diferite.
  Un nume în curs de încărcare este rezervat 10 minute (`busy`).
- **Dimensiune maximă.** 1 GiB pe fișier (`Protocol.MAX_FILE_SIZE`). Transferul este în flux, deci memoria
  folosită nu depinde de dimensiune.
- **Ordinea confirmării.** Metadatele se înregistrează abia după ce ambele copii sunt pe disc și verificate:
  un fișier vizibil în `ls` are întotdeauna 2 copii.

## 3. Modelul de date

Coordonatorul ține în memorie două tabele (`MetadataStore`).

**Metadatele unui fișier** (`FileMeta`)

| Câmp | Tip Java | Exemplu | Stabilit de |
|---|---|---|---|
| identificator | `UUID` | `3f2a9c1e-5b7d-4e8a-9c21-7d4e5f6a8b90` | coordonator, la `ALLOCATE` |
| nume | `String` | `raport.pdf` | utilizator |
| dimensiune | `long` (octeți) | `482133` | client |
| checksum | `String` (SHA-256, hex) | `9f86d081...` | client, verificat de fiecare nod și la descărcare |
| locațiile copiilor | `List<String>` (ID-uri de nod) | `[n2, n3]` | coordonator, la `COMMIT` |

**Un nod din listă** (`NodeInfo`)

| Câmp | Tip Java | Exemplu |
|---|---|---|
| ID nod | `String` | `n2` |
| adresă și port | `String`, `int` | `192.168.37.102`, `7002` |
| stare | `enum NodeState` | `ACTIV`, `SUSPECT`, `CAZUT` (tranzițiile din Etapa 3) |
| ultimul semn de viață | `long` (ms) | `System.currentTimeMillis()` la ultimul `HELLO` |

**Recuperarea metadatelor (Etapa 6).** Fiecare nod păstrează lângă copie un `.meta` cu ID-ul, numele,
dimensiunea și checksum-ul, iar mesajul `INVENTORY` le raportează. Un coordonator nou reconstruiește
tabela fișierelor reunind inventarele: locațiile unui fișier sunt nodurile care raportează același ID.

## 4. Cerințe nefuncționale

Ipoteze de lucru: rețea locală (multicast permis), cel mult un nod căzut la un moment dat, defecte de
tip crash (nu bizantine), ceasurile mașinilor sincronizate la nivel de secundă pentru compararea jurnalelor.

| Cerință | Obiectiv măsurabil | Cum se măsoară | Etapa |
|---|---|---|---|
| Integritate | 0 fișiere cu checksum diferit din 100 de descărcări | `scripts/integrity-test.sh 100` compară octet cu octet | 2 |
| Descoperire | un nod nou apare în lista celorlalți în cel mult 3 s | diferența dintre linia `pornit` a nodului nou și liniile `nod descoperit` din jurnalele celorlalți (măsurat local: ~0,1 s) | 2 |
| Detectarea defectelor | un nod oprit cu `kill -9` este marcat `CAZUT` în cel mult 5 s | jurnale cu timestamp pe fiecare nod | 3 |
| Scalabilitate | debitul de încărcare cu 3 noduri crește față de 1 nod | 10 fișiere de 100 MB, timp total | 4 |
| Coordonare | noul coordonator este ales în cel mult 10 s după căderea celui vechi | jurnale Bully | 5 |
| Replicare | fiecare fișier are exact 2 copii pe noduri distincte | `ls` și conținutul directoarelor `data/` | 6 |
| Disponibilitate | după oprirea oricărui nod, 100% din fișiere rămân descărcabile | descărcarea tuturor fișierelor după oprire | 7 |

## 5. Scenariul demonstrației

| Pas | Acțiunea operatorului | Comanda exactă | Ce trebuie să se vadă | Din etapa |
|---|---|---|---|---|
| 1 | pornește n1, n2, n3 în terminale separate | `java -cp target/classes ro.ubm.sd.node.StorageNode --id n1 --port 7001 --dir data/n1 --coordinator` (analog n2/7002, n3/7003) | fiecare nod îi descoperă pe ceilalți (`nod descoperit`); n1 este coordonator | 2 |
| 2 | încarcă un fișier | `java -cp target/classes ro.ubm.sd.client.Client`, apoi `put raport.pdf` | ID-ul fișierului și două noduri distincte | 2 |
| 3 | descarcă fișierul | `get raport.pdf out` | fișierul descărcat, checksum verificat | 2 |
| 4 | pornește n4 fără a opri ceva | `java -cp target/classes ro.ubm.sd.node.StorageNode --id n4 --port 7004 --dir data/n4`, apoi `nodes` | n4 apare în `nodes`; următoarele încărcări îl pot folosi | 4 |
| 5 | `kill -9` pe un nod care deține raport.pdf | `kill -9 <pid>` (Windows: `taskkill /F /PID <pid>`), apoi `get raport.pdf out` | nodul este marcat căzut; `get` reușește din cealaltă copie | 3 |
| 6 | `kill -9` pe coordonator | `kill -9 <pid n1>` | nodurile rămase aleg un nou coordonator | 5 |
| 7 | `ls` și `get` după realegere | `ls`, `get raport.pdf out` | metadatele sunt reconstruite; fișierul rămâne accesibil | 6 |

În Etapa 2, pașii 1–3 funcționează complet. Pasul 5 funcționează parțial: `get` reușește din a doua copie,
dar nodul nu este încă marcat `CAZUT` (heartbeat-ul vine în Etapa 3).

## 6. Protocol

### TCP

O cerere pe conexiune. Fiecare mesaj are un antet: **o linie de text** UTF-8 terminată cu `\n`,
de forma `TIP cheie=valoare cheie=valoare`, cu valorile codificate URL (spațiile și diacriticele sunt
permise în nume). Când mesajul transportă un fișier, octeții lui urmează imediat după antet, exact
`size` octeți. Răspunsurile cu mai multe rânduri (`FILES`, `NODELIST`) dau numărul rândurilor în antet.

| Mesaj | De la → către | Conținut | Răspuns |
|---|---|---|---|
| `ALLOCATE` | client → coordonator | `name`, `size`, opțional `id` (reîncercare) și `exclude` (noduri eșuate) | `ALLOCATED id nodes=n2@h:p,n3@h:p` sau `ERROR` |
| `PUT` | client → nod | `id`, `name`, `size`, `checksum`, `next` (nodul pentru copia a doua) + octeții | `STORED id nodes` sau `ERROR` (cu `node` = nodul eșuat, dacă e cazul) |
| `REPLICATE` | nod → nod | ca `PUT`, fără `next` + octeții | `STORED` sau `ERROR` |
| `COMMIT` | nod → coordonator | `id`, `name`, `size`, `checksum`, `nodes` | `OK` sau `ERROR` |
| `LOCATE` | client → coordonator | `name` | `LOCATIONS id name size checksum nodes` sau `NOT_FOUND` |
| `GET` | client → nod | `id` | `DATA id name size checksum` + octeții, sau `NOT_FOUND` |
| `LIST` | client → coordonator | — | `FILES count` + `count` × `FILE id name size checksum nodes` |
| `REMOVE` | client → coordonator | `name` | `REMOVED id deleted failed` sau `NOT_FOUND` |
| `DELETE` | coordonator → nod | `id` | `DELETED` sau `NOT_FOUND` |
| `NODES` | client → coordonator | — | `NODELIST count coordinator` + `count` × `NODE id host port state lastSeenMs` |
| `INVENTORY` | coordonator → nod | — | `FILES count node` + `count` × `FILE id name size checksum` (pentru Etapa 6) |

Erorile au forma `ERROR code=<cod> message=<text>`. Coduri: `exists`, `busy`, `insufficient_nodes`,
`too_large`, `checksum`, `replica_failed`, `commit_failed`, `not_coordinator`, `bad_request`, `unknown`.
Un nod care nu este coordonator răspunde la cererile de metadate cu `not_coordinator` și adresa
coordonatorului cunoscut.

Exemplu de încărcare (`put "raport final.pdf"`):

```
client → n1:7001   ALLOCATE name=raport+final.pdf size=300000
n1 → client        ALLOCATED id=962df9e1-... nodes=n1%40localhost%3A7001%2Cn2%40localhost%3A7002
client → n1:7001   PUT id=962df9e1-... name=raport+final.pdf size=300000 checksum=b83f... next=n2%40localhost%3A7002
                   <300000 octeți>
n1 → n2:7002       REPLICATE id=962df9e1-... name=raport+final.pdf size=300000 checksum=b83f...
                   <300000 octeți>
n2 → n1            STORED id=962df9e1-... node=n2
n1 → n1:7001       COMMIT id=962df9e1-... name=raport+final.pdf size=300000 checksum=b83f... nodes=n1%2Cn2
n1 → n1            OK id=962df9e1-...
n1 → client        STORED id=962df9e1-... nodes=n1%2Cn2
```

### UDP multicast (descoperire)

Grup implicit `239.255.10.10:4446`, TTL 1 (doar rețeaua locală). Un mesaj pe datagramă, în același format-linie.

| Mesaj | De la | Conținut | Efect |
|---|---|---|---|
| `HELLO` | fiecare nod, la fiecare 1 s | `id`, `host`, `port` (TCP), `coordinator` (true/false) | cine îl primește adaugă/actualizează nodul în listă; un nod nou primește imediat `HELLO` de la toți ceilalți |
| `DISCOVER` | client (sau orice proces nou) | — | toate nodurile răspund imediat cu `HELLO`; clientul reține nodul cu `coordinator=true` |

Mesajele de heartbeat (Etapa 3) și de alegere a liderului (Etapa 5) se vor adăuga în același format.
