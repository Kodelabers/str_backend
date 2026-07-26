# eGOP integracija — otvorena pitanja

> ## ⚠️ SUPERSEDED
>
> Ovaj dokument je nacrt **v0.1**, pisan isključivo iz PDF specifikacije, **bez uvida u WSDL-ove**.
> Značajan dio pitanja s ove liste je u međuvremenu odgovoren analizom WSDL-a (npr. „što je
> ServiceMDM" — 44 operacije, kompletan šifrarnik).
>
> **Aktualni izvor istine: [`docs/eGOP-endpoint-analiza.md`](./eGOP-endpoint-analiza.md)** —
> §11 (što nam fali) i §12 (pitanja za MINT, formulirana za slanje).
>
> Zadržano kao trag razmišljanja; ne koristiti za implementaciju.

> Pitanja koja se otvaraju nakon čitanja *eGOP10 — specifikacija integracijskih web servisa v1.11* iz perspektive senior backend developera koji planira implementirati klijenta u STR-u.
> Spec opisuje *transport layer i payload shape*, ali ne nudi *šifre, codes, business policy* koji su nužni za stvarnu integraciju. Lista ide MUP-u / MINT-u / vlasniku eGOP platforme prije prve linije koda.

---

## A. Autentifikacija i autorizacija (mora se riješiti prvo)

**1. Točan autentifikacijski mehanizam za STR-ov tehnički račun.**
Spec kaže "Windows ili Basic auth preko IIS-a". Što stvarno koristi test okruženje na `egopeaitest.mint.hr` — HTTP Basic (lakše), NTLM (treba Apache HttpClient WinAuth modul) ili Kerberos (treba SPN/keytab)? Različite biblioteke rade s različitom razinom bola.

**2. Username model.**
- Postoji li **jedan** STR servisni AD račun (npr. `MINT\str-svc`), ili STR mora imati zaseban "useri službenika" model gdje svaki MINT-ov službenik koji odobrava zahtjeve ima vlastiti račun?
- Treba li nam ikad pozivati `DohvatiSubjektIdZaUsername`, ili to nije relevantno za naš use-case (STR ne radi izlazna pismena)?
- Kako vodimo `userNameZaposlenika` kad zahtjev podnosi *iznajmljivač* preko portala — predaje li STR svoj servisni račun u oba `userName` i `userNameKorisnikaAplikacije`, ili je drugačije?

**3. Lista metoda za koje treba odobriti naš tehnički račun.**
Treba nam eksplicitna potvrda da je za STR-ov račun aktivirana sljedeća lista metoda (predlog):
- `Subjekt.KreirajSubjektaProsireno`
- `Subjekt.DohvatiPodatkeSubjekta`
- `Predmet.KreirajPredmet2`
- `Predmet.PostaviSubjektaNaPredmetu` (rezerva)
- `Predmet.DohvatiPodatkePredmeta`
- `Pismeno.KreirajPismenoPoUredbi`
- `Pismeno.KreirajDokumentZaPismenoPoUredbi`
- `Pismeno.KreirajPrilogPoUredbi` (rezerva)
- `Pismeno.KreirajDokumentZaPrilogPoUredbi` (rezerva)
- `Pismeno.DohvatiPodatkePismenaPoUredbi`

---

## B. Šifrarnici koje moramo znati prije prvog poziva

**4. `vrstaPredmeta` — eGOP šifra za "Zahtjev za registracijski broj objekta kratkoročnog najma".**
Spec ne navodi listu. Bez ove šifre `KreirajPredmet2` vraća `-240`. Treba: točan kod (npr. `334-05`, `UP-STR-01`, …) plus način kako saznati postoji li već kodirano u eGOP-u.

**5. `vrstaPismena` — eGOP šifra za ulazno pismeno tipa "Zahtjev za registraciju STR".**
Isto kao gore, ali za pismeno. Bez šifre `KreirajPismenoPoUredbi` ne prolazi.

**6. `idUlogePartnera` — uloga iznajmljivača na predmetu.**
Ako se pokaže da osim "glavnog subjekta" trebamo dodatne uloge (npr. "podnositelj zahtjeva" kao posebna uloga), trebamo eGOP šifrarnik tih uloga.

**7. `tipOsobe` — šifra za fizičku osobu, obrt, d.o.o. kod `KreirajSubjektaProsireno`.**
Iznajmljivač u STR-u može biti fizička osoba ili pravna osoba (obrt, d.o.o.). Trebamo mapping STR-`OblikPoslovanja` → eGOP `tipOsobe`.

**8. `upisnaKnjiga` — UP/I ili nešto drugo.**
Spec dozvoljava `NP`, `UP/I`, `UP/II`. Naša pretpostavka je `UP/I` jer se radi o prvostupanjskom upravnom postupku. Treba *eksplicitna potvrda* MINT pisarnice da je ovo ispravno za STR.

**9. `nadleznaOrgJedinica` — mapping župnija → org. jedinica.**
STR korisnik ima objekt u nekoj županiji. eGOP organizacijske jedinice imaju svoje ID-ove. Trebamo:
- Šifrarnik svih org. jedinica koje rade STR registracije (vjerojatno turistički nadzor po PU / područnoj službi).
- Mapping `STR zupanija → eGOP idOrgJedinice`.
- Ili odluku da STR otvara predmet *bez* `nadleznaOrgJedinica` i bez `rjesavatelja`, pa pisarnica raspoređuje ručno. *Spec kaže da je barem jedno obavezno*, dakle ne smije biti oboje prazno.

**10. ServiceMDM — što tu zapravo ima?**
Spec lista `ServiceMDM.asmx` u URL-ovima, ali u tijelu dokumenta nema niti jedne MDM metode. Pretpostavka je da je MDM šifrarnik (vraća kodove za `vrstaPredmeta`, `vrstaPismena`, org. jedinica, …). Trebamo:
- Specifikaciju MDM metoda (zasebnu, ako postoji).
- Ako MDM stvarno daje sve šifre — ne treba nam ručna lista iz pitanja 4–8, samo nam treba pristup MDM-u.

---

## C. Poslovna pravila i tok

**11. Tko otvara predmet — STR ili pisarnica?**
Dvije opcije:

  a) **STR otvara predmet automatski** na submit. Pisarnica vidi otvoren predmet u eGOP-u, dodjeljuje rješavatelja, rješava.

  b) **STR ne otvara predmet**, već šalje samo XML/PDF na neki mailbox / queue, a pisarnica otvara predmet ručno.

Spec dozvoljava obje. Naša pretpostavka je (a). Treba potvrda procesnog vlasnika (turistički nadzor MINT-a).

**12. Što se dešava ako iznajmljivač ponovno podnese zahtjev (npr. promijenio adresu)?**
- Otvara li STR **novi predmet** ili dodaje **novo pismeno na postojeći predmet**?
- Spec dozvoljava više pismena u jednom predmetu, ali u upravnom postupku jedan zahtjev = jedan predmet. → poslovna odluka MUP-a, ne tehnička.

**13. Što ako iznajmljivač već postoji u eGOP-u s drugim podacima (drugačija adresa)?**
- Možemo li `AzurirajPodatkeSubjekta` koristiti, ili eGOP zahtijeva da službenik to napravi ručno?
- STR drži immutable snapshot iznajmljivača per registracija — kako se to slaže s eGOP modelom gdje subjekt je *mutable* singleton po OIB-u?

**14. `KreirajDokumentZaPismenoPoUredbi` na već postojeći dokument — overwrite ili error?**
Bitno za retry semantiku. Ako overwrite, retry je siguran. Ako error, retry mora prvo zvati `DohvatiDokumentZaPismenoPoUredbi` da provjeri ima li dokumenta.

**15. Maksimalna veličina PDF attachmenta.**
SOAP Base64 napuhuje payload za ~33%. IIS po defaultu ima limit ~4MB. Treba znati:
- Server-side limit na eGOP-u (može li 10MB proći).
- Predviđena maksimalna veličina STR PDF zahtjeva (trenutno mali — ali ako ikad dodamo priloge, eskalira).

**16. SLA i timeouti.**
- Koje response timeoute imamo s eGOP strane (P50, P95, P99)?
- Postoji li dokumentirana max trajnost zahtjeva?
- Postoje li planirani downtimei / održavanja koje treba znati za circuit breaker tuning?

**17. Idempotency keys / dedup.**
Postoji li u eGOP-u bilo kakav idempotency-key zaglavlje ili "client request ID" kojim možemo označiti retry pokušaj da eGOP sam ne otvori drugi predmet? (Nije u spec-u, ali možda postoji nedokumentirano.)

---

## D. Operativni i security aspekti

**18. Pristupne kredencijale za test okruženje.**
Treba nam:
- AD username i password (ili keytab).
- IP allowlist na MINT strani (ako postoji) za naš Railway / production deployment.
- Test "sandbox" subjekt s OIB-om kojim možemo trenirati create/lookup bez zagađivanja produkcijskog registra.

**19. Test podaci.**
Ima li MINT pripremljen set test subjekata u test okolini kojima možemo bez bojazni mijenjati podatke, ili svako naše testiranje stvarno mutira shared test okruženje?

**20. Audit i compliance.**
Postoji li zahtjev da STR logira **svaki** eGOP poziv (request body, response body, timestamp, korelacijski ID) za GDPR / DPIA svrhe? Ako da, koliko dugo se logovi čuvaju i u kakvom enkripcijskom režimu?

**21. Mjenjanje verzije specifikacije.**
Spec je v1.11 iz 10/2025. Postoji li:
- Mailing lista za nove revizije?
- SLA o najavi breaking changeova (npr. 30 dana unaprijed)?
- Kontakt osoba za pitanja o spec-u (Antun Divald i Krešimir Kavran su navedeni kao autori — jesu li i danas kontakt)?

**22. Distinkcija test ↔ produkcija osim URL-a.**
Postoji li differentno ponašanje (npr. test ne provodi sve poslovne validacije, ili dozvoljava test OIB-e koji u produkciji ne bi prošli)? Što testirati protiv produkcije *prije nego što idemo live*?

---

## E. Pitanja iz STR perspektive (interna, ali povezana)

**23. Cron schedule za sync job.**
Predloženo: svakih 60s rekonsilijacija svih `submission` zapisa s `egop_sync_status NOT IN (SYNCED, FAILED)`. Treba dogovoriti s ops timom radi opterećenja.

**24. Dead-letter handling i alerti.**
Tko prima alert kad `egop_sync_status = FAILED`? Slack channel? PagerDuty? Mail na sluzbenik@... ?

**25. Hoće li korisnik portala vidjeti eGOP klasifikacijsku oznaku?**
- Ako da — frontend mora pollati `submission` dok ne postane `SYNCED`.
- Ako ne — eGOP polja su čisto interna, korisnik ostaje na STR-ovom `HR12345678` brojem.

**26. Postoji li već "lookup" tablica STR.county → eGOP.idOrgJedinica?**
U `str_rn` nema. Trebati će novi changelog za seed. → poslovna grupa mora dati podatke.

**27. Kako se postupa sa zahtjevima izvan radnog vremena MINT-a?**
eGOP test/prod IIS može biti offline noću ako MINT to gasi. Retry policy treba s tim računati.

---

## Prioritet

| #     | Pitanje                                      | Blokira |
| :---- | :------------------------------------------- | :------ |
| 1, 2, 18 | Auth, username, kredencijale              | Prvi `wsdl2java` call protiv test endpointa |
| 4, 5, 9 | `vrstaPredmeta`, `vrstaPismena`, org. jedinica | Bilo kakav uspješan `KreirajPredmet2` |
| 10    | MDM specifikacija                            | Drugi pristup šifrarnicima (ako MDM postoji, gornja 3 pitanja se rješavaju automatski) |
| 11    | Tko otvara predmet                           | Cijela arhitektura sync flowa |
| 8     | upisnaKnjiga UP/I potvrda                    | Prvi `KreirajPredmet2` |
| 7     | tipOsobe mapping                             | `KreirajSubjektaProsireno` za pravnu osobu (fizičku možda krenemo bez toga) |
| 14, 15 | retry semantika, max veličina               | Produkcijska otpornost |
| 16, 17 | SLA, idempotency keys                       | Tuning circuit breakera, retry policy |

Sve ostale stavke su sekundarne, mogu se prikupljati paralelno s razvojem.
