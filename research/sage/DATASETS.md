# Contact datasets and provenance

Last inspected 2026-10-06. No MeshMind traffic comes with these traces: all packets,
gateways, adoption, losses and battery are synthetic and generated on top of the real
encounter sequences. Every format below was checked against the source documentation
**and** the downloaded file before a parser was written. Nothing that needs an
account, licence click-through or request form was downloaded.

## Imported (11 traces, 145 usable episodes)

| Dataset | Context (family) | Official source | Licence | Nodes | Contact rows | Span | Episodes used |
|---|---|---|---|---:|---:|---:|---:|
| SFHH conference | conference | [SocioPatterns](https://sociopatterns.org/datasets/sfhh-conference-data-set/) | CC0 | 403 | 70,261 | 31.8 h | 2 (test) |
| Hypertext 2009 | conference | [SocioPatterns](https://sociopatterns.org/datasets/hypertext-2009-dynamic-contact-network/) | CC BY-NC-SA | 113 | 20,818 | 59 h | 3 (validation) |
| "Infectious" Science Gallery | exhibition (public crowd) | [SocioPatterns](https://sociopatterns.org/datasets/infectious-exhibition-dynamic-contact-networks/) | CC BY-NC-SA | 10,972 visitors | 415,912 | 69 days | 45 train / 10 val / 14 test |
| Copenhagen Networks Study (Bluetooth) | campus smartphones | [figshare 7267433](https://figshare.com/articles/dataset/The_Copenhagen_Networks_Study_interaction_data/7267433) | MIT | 692 | 2,426,275 | 28 days | 18 train / 4 val / 6 test |
| High school 2013 | school | [SocioPatterns](https://sociopatterns.org/datasets/high-school-contact-and-friendship-networks/) | CC BY-NC-SA | 327 | 188,508 | 101 h | 5 (train) |
| High school 2011 | school | [SocioPatterns](https://sociopatterns.org/datasets/high-school-dynamic-contact-networks/) | CC BY-NC-SA | 126 | 28,411 | 76 h | 4 (train) |
| High school 2012 | school | same page | CC BY-NC-SA | 180 | 45,047 | 203 h | 7 (train) |
| Primary school | school | [SocioPatterns](https://sociopatterns.org/datasets/primary-school-temporal-network-data/) | CC BY-NC-SA | 242 | 125,773 | 32.5 h | 2 (train) |
| Workplace (InVS 2013) | workplace | [SocioPatterns](https://sociopatterns.org/datasets/contacts-in-a-workplace/) | CC0 | 92 | 9,827 | 274 h | 10 (train) |
| Workplace (InVS 2015) | workplace | [SocioPatterns](https://sociopatterns.org/datasets/test/) | CC0 | 217 | 78,249 | 276 h | 10 (train) |
| Hospital ward (Lyon) | hospital | [SocioPatterns](https://sociopatterns.org/datasets/hospital-ward-dynamic-contact-network/) | CC BY-NC-SA | 75 | 32,424 | 96.5 h | 5 (train) |

Raw files live in `data/raw/<name>/` with `source.json` (URL, SHA-256, licence,
citation). Import reports (rows dropped and why) are in
`data/processed/v1/episodes/<name>_import.json`; per-episode statistics are in
`outputs/v1/episodes.csv`. The v0 pilot statistics remain in `outputs/v0_pilot/`.

### Verified formats and normalisation decisions

* **SocioPatterns** (`t i j [extra]`, whitespace separated): each row is an active
  face-to-face contact in the 20-second interval `[t-20, t]` (end-stamped). The extra
  columns (class, department, hospital role) are ignored and never used as features.
  Timestamps are seconds (relative or UNIX). **498 High-school-2011 rows** are offset by
  10 s from the trace's 20-s grid; they are snapped to the nearest grid point and counted
  in the import report. 149 exact duplicates and 1 self-contact in that file are dropped.
* **Infectious**: a `.tgz` of 69 daily files `listcontacts_YYYY_MM_DD.txt` (`t i j`, UNIX
  time, 20-s bins). Badge IDs are reused by different visitors on different days, so IDs
  are prefixed with the date and each day is an independent episode with no warm-up
  history. Archive members are read as bytes only; nothing is extracted to disk.
* **Copenhagen** (`timestamp,user_a,user_b,rssi`, 5-minute scans): `user_b = -1` empty
  scans (2,220,136 rows) and `-2` non-participant devices (827,874 rows) are dropped, as are
  4 rows with non-negative RSSI (physically implausible). A detection at `t` is treated as a
  radio-contact opportunity over `[t, t+300]`, and the simulator only lets a phone use
  `contact_seconds` (default 60 s) of airtime per bin. This is an assumption: the scan says
  the phones were in Bluetooth range, not for how long. No RSSI threshold is applied,
  because any detectable device is a potential Nearby Connections peer. The study's start
  time is relative, so the "hour of day" used by the routine feature is
  `t mod 86400`, which is consistent but not aligned to local midnight.
* **Episodes**: a trace is split at gaps longer than 3 h and cut into windows of at most
  24 h. Episodes with fewer than 30 active nodes or under 4 h are dropped. Earlier
  episodes of the *same population*, up to 7 days, are replayed into the history state
  before the episode starts ("phones remember who they met yesterday"). They contribute
  history only, never packets or labels.

Contact proximity is a proxy for radio opportunity. SocioPatterns badges detect
face-to-face proximity of about 1–1.5 m, so they **under**-estimate Bluetooth range.
Copenhagen is real phone-to-phone Bluetooth discovery, the closest available analogue to
Nearby Connections.

## Inspected but not imported

| Dataset | Reason |
|---|---|
| Cambridge/Haggle, RollerNet, Nottingham Mall (CRAWDAD) | Archive access needs IEEE DataPort account/licence steps. Not bypassed. Place authorised files under `data/raw/<name>/` with their README; no adapter is written without the documentation. |
| KONECT/network-repository mirrors of Haggle | Third-party redistribution of CRAWDAD data; the original terms require CRAWDAD's licence. Not used. |
| Malawi village (SocioPatterns) | The page does not document the CSV columns (`contact_time`, `day`, ...) or units; not guessed. |
| Kenya / South Africa households, baboons, co-location sets | Not crowd/event contact traces (households, animals, room co-location). |
| MIT Reality Mining | Requires a data request. |

## Citations required by the licences

Isella et al., *J. Theor. Biol.* 271 (2011); Génois & Barrat, *EPJ Data Science* 7, 11
(2018); Génois et al., *Network Science* 3, 326 (2015); Mastrandrea et al., *PLoS ONE*
10(9) e0136497 (2015); Fournet & Barrat, *PLoS ONE* 9(9) e107878 (2014); Stehlé et al.,
*PLoS ONE* 6(8) e23176 (2011); Vanhems et al., *PLoS ONE* 8(9) e73970 (2013); Sapiezynski
et al., *Scientific Data* 6, 315 (2019). Acknowledge the SocioPatterns collaboration.
CC BY-NC-SA data and models derived from it must not be used commercially without
checking the licence terms.
