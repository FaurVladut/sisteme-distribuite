# sd-storage — sistem distribuit de stocare a fișierelor

Fiecare fișier are două copii pe noduri distincte și rămâne accesibil când un nod cade.
Datele trec prin coordonator (unul dintre noduri), iar conținutul fișierelor circulă
direct între client și noduri. 

**Stadiu: Etapa 1** — clientul și nodurile pornesc ca procese separate, fiecare cu
argumentele lui, și își afișează configurația. Comunicarea în rețea vine în Etapa 2.

## Echipa

| Membru | Grupa | Utilizator Git |

| Faur Vladut | I | FaurVladut |


## Cerințe

- JDK 17 sau mai nou (`java -version`)
- Maven 3.8 sau mai nou (`mvn -version`)
- Git
- Linux sau Windows

## Compilare

Din rădăcina repository-ului:

```bash
mvn -q package
```

Clasele compilate ajung în `target/classes`.

## Pornire

Fiecare componentă este un proces separat, cu propriul `main`. Nimic nu este scris în cod:
porturile, directoarele și coordonatorul vin din linia de comandă.

### Nodurile de stocare (câte un terminal pentru fiecare)

```bash
java -cp target/classes ro.ubm.sd.node.StorageNode --id n1 --port 7001 --dir data/n1 --coordinator
java -cp target/classes ro.ubm.sd.node.StorageNode --id n2 --port 7002 --dir data/n2
java -cp target/classes ro.ubm.sd.node.StorageNode --id n3 --port 7003 --dir data/n3
```

| Argument | Obligatoriu | Descriere |
|---|---|---|
| `--id` | da | identificatorul nodului, de forma `n<număr>` (`n1`, `n2`, `n10`); numărul este prioritatea pentru Bully |
| `--port` | da | portul TCP al nodului (1–65535) |
| `--dir` | da | directorul local pentru fișiere; se creează dacă lipsește |
| `--host` | nu | adresa anunțată celorlalte noduri (implicit `localhost`) |
| `--coordinator` | nu | nodul este coordonator; temporar, până la alegerea prin Bully din Etapa 5 |

Ieșire așteptată:

```
[n2] pornit: port 7002, director data/n2, rol: nod de stocare
```

Un nod se oprește cu Ctrl+C. Argumentele greșite sunt raportate și procesul iese cu codul 2.

### Clientul (al patrulea terminal)

```bash
java -cp target/classes ro.ubm.sd.client.Client --coordinator localhost:7001
```

Ieșire așteptată:

```
[client] coordonator configurat: localhost:7001; comenzi: put, get, ls, rm, nodes, exit
sd>
```

| Comandă | Efect |
|---|---|
| `put <fișier-local>` | încarcă un fișier (2 copii pe noduri distincte) |
| `get <nume> [director]` | descarcă un fișier și îi verifică checksum-ul |
| `ls` | listează fișierele, cu dimensiunea și nodurile fiecăruia |
| `rm <nume>` | șterge un fișier |
| `nodes` | nodurile cunoscute, starea lor și coordonatorul |
| `help`, `exit` | ajutor, ieșire |

În Etapa 1 comenzile își validează argumentele, dar nu contactează încă nodurile.

### Scripturi pentru 3 noduri locale

- **Linux:** `./scripts/start-local.sh` compilează și pornește n1, n2, n3 în fundal; ieșirea
  fiecăruia ajunge în `logs/<id>.log` și este afișată în terminal. Ctrl+C oprește toate
  nodurile. Cu `--no-build` sare peste compilare.
- **Windows:** `scripts\start-local.bat` compilează și deschide câte o fereastră pentru fiecare nod.

## Structura

```
sd-storage/
├── README.md
├── pom.xml
├── docs/cerinte.md              # cerințe, model de date, scenariu, protocol
├── scripts/
│   ├── start-local.sh           # 3 noduri locale (Linux)
│   └── start-local.bat          # 3 noduri locale (Windows)
└── src/main/java/ro/ubm/sd/
    ├── client/Client.java       # interfața în terminal
    ├── node/StorageNode.java    # nodul de stocare (poate fi și coordonator)
    ├── node/MetadataStore.java  # tabelele coordonatorului
    └── common/                  # configurație, model de date, utilitare
```

`data/`, `logs/`, `out/` și `target/` sunt ignorate de Git.

