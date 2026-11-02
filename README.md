# sd-storage — sistem distribuit de stocare a fișierelor

Fiecare fișier are două copii pe noduri distincte și rămâne accesibil când un nod cade.
Metadatele trec prin coordonator (unul dintre noduri), iar conținutul fișierelor circulă
direct între client și noduri, ca în HDFS.

**Stadiu: Etapa 2** — transfer TCP între client și noduri și între noduri, descoperire prin
UDP multicast. Fiecare nod are un ID, o adresă și un port; nu există o listă fixă de vecini.

## Echipa

| Membru      | Grupa | Utilizator Git |
|-------------|-------|----------------|
| Faur Vladut | I     | FaurVladut     |

## Cerințe

- JDK 17 sau mai nou (`java -version`)
- Maven 3.8 sau mai nou (`mvn -version`)
- Git
- Linux sau Windows

## Compilare

Din rădăcina repository-ului:

```
mvn -q package
```

Clasele compilate ajung în `target/classes`.

## Pornire

Fiecare componentă este un proces separat, cu propriul `main`. Nimic nu este scris în cod:
porturile, directoarele și rolurile vin din linia de comandă.

### Nodurile de stocare (câte un terminal pentru fiecare)

```
java -cp target/classes ro.ubm.sd.node.StorageNode --id n1 --port 7001 --dir data/n1 --coordinator
java -cp target/classes ro.ubm.sd.node.StorageNode --id n2 --port 7002 --dir data/n2
java -cp target/classes ro.ubm.sd.node.StorageNode --id n3 --port 7003 --dir data/n3
```

| Argument          | Obligatoriu | Descriere |
|-------------------|-------------|-----------|
| `--id`            | da  | identificatorul nodului, de forma `n<număr>` (n1, n2, n10); numărul este prioritatea pentru Bully |
| `--port`          | da  | portul TCP al nodului (1–65535) |
| `--dir`           | da  | directorul local pentru fișiere; se creează dacă lipsește |
| `--host`          | nu  | adresa anunțată celorlalte noduri (implicit `localhost`; pe mai multe mașini, IP-ul mașinii) |
| `--coordinator`   | nu  | nodul este coordonator; temporar, până la alegerea prin Bully din Etapa 5 |
| `--mcast-group`   | nu  | grupul multicast pentru descoperire (implicit `239.255.10.10`) |
| `--mcast-port`    | nu  | portul UDP pentru descoperire (implicit `4446`) |
| `--iface`         | nu  | interfața de rețea pentru multicast, după nume sau IP (implicit cea a rutei implicite) |

Ieșire așteptată (fiecare linie are timestamp, ca timpii de descoperire să poată fi măsurați din jurnale):

```
15:47:14.254 [n2] pornit: port 7002, director data/n2, rol: nod de stocare
15:47:14.608 [n2] descoperire: multicast 239.255.10.10:4446 pe eth0 (...), anunt la fiecare 1000 ms
15:47:14.702 [n2] nod descoperit: n3 la localhost:7003; noduri cunoscute: 2
15:47:14.707 [n2] nod descoperit: n1 la localhost:7001 (coordonator); noduri cunoscute: 3
15:47:14.707 [n2] coordonator: n1 la localhost:7001
```

Un nod se oprește cu Ctrl+C. Argumentele greșite sunt raportate și procesul iese cu codul 2;
un port deja ocupat iese cu codul 1.

### Clientul (al patrulea terminal)

```
java -cp target/classes ro.ubm.sd.client.Client
```

Fără argumente, clientul găsește coordonatorul prin multicast. Cu `--coordinator localhost:7001`
îl folosește direct, fără descoperire (util dacă rețeaua blochează multicast-ul).

```
[client] coordonator descoperit: n1@localhost:7001; comenzi: put, get, ls, rm, nodes, exit
sd> put raport.pdf
incarcat raport.pdf: id 962df9e1-383c-48ee-a642-128e4ad25095, 300000 octeti, copii pe n1,n2 (0.29 s)
sd> get raport.pdf out
descarcat raport.pdf de la n1 in out/raport.pdf, 300000 octeti, checksum verificat b83f6f0c1533... (0.03 s)
```

| Comandă                  | Efect |
|--------------------------|-------|
| `put <fișier-local>`     | încarcă un fișier (2 copii pe noduri distincte) |
| `get <nume> [director]`  | descarcă un fișier și îi verifică checksum-ul; dacă prima copie nu răspunde, o folosește pe a doua |
| `ls`                     | listează fișierele, cu dimensiunea și nodurile fiecăruia |
| `rm <nume>`              | șterge un fișier de pe toate nodurile |
| `nodes`                  | nodurile cunoscute, starea lor și coordonatorul |
| `help`, `exit`           | ajutor, ieșire |

Numele care conțin spații se scriu între ghilimele: `put "raport final.pdf"`.

### Scripturi

- **Linux:** `./scripts/start-local.sh` compilează și pornește n1, n2, n3 în fundal; ieșirea
  fiecăruia ajunge în `logs/<id>.log` și este afișată în terminal. Ctrl+C oprește toate nodurile.
  Cu `--no-build` sare peste compilare.
- **Windows:** `scripts\start-local.bat` compilează și deschide câte o fereastră pentru fiecare nod.
- `./scripts/integrity-test.sh [N] [KiB]` (nodurile pornite) încarcă și descarcă N fișiere
  aleatoare și raportează câte diferă — obiectivul de integritate din `docs/cerinte.md`.

### Probleme cu multicast

Dacă nodurile nu se descoperă între ele, alegeți explicit interfața cu `--iface` (de exemplu
`--iface 127.0.0.1` pentru toate procesele pe aceeași mașină, sau IP-ul plăcii de rețea),
aceeași pentru toate procesele. Pe Windows, firewall-ul trebuie să permită `java` pe rețeaua privată.

## Structura

```
sd-storage/
├── README.md
├── pom.xml
├── docs/cerinte.md              # cerințe, model de date, scenariu, protocol
├── scripts/
│   ├── start-local.sh           # 3 noduri locale (Linux)
│   ├── start-local.bat          # 3 noduri locale (Windows)
│   └── integrity-test.sh        # N încărcări + descărcări, comparate octet cu octet
└── src/main/java/ro/ubm/sd/
    ├── client/Client.java       # interfața în terminal
    ├── node/StorageNode.java    # nodul de stocare (poate fi și coordonator)
    ├── node/MetadataStore.java  # tabelele coordonatorului: fișiere și noduri
    ├── node/LocalStore.java     # copiile locale ale unui nod (.bin + .meta)
    └── common/                  # mesaje, conexiuni TCP, descoperire UDP, configurație
```

`data/`, `logs/`, `out/` și `target/` sunt ignorate de Git.
