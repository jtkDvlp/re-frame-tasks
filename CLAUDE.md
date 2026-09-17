# re-frame-tasks

@../claude-guidelines/CLAUDE.md

Die projektübergreifenden Richtlinien oben gelten unverändert. Hier steht
nur, woran der dort beschriebene Release-Vorgang in diesem Projekt hängt.

## Release

| Frage | Antwort für dieses Projekt |
|---|---|
| Hauptzweig | `master` |
| Wer trägt die Version? | `project.clj`, die `defproject`-Zeile, markiert mit `x-release-please-version` |
| Paket-Repository | [Clojars](https://clojars.org/jtk-dvlp/re-frame-tasks), Artefakt `jtk-dvlp/re-frame-tasks` |
| Secrets | `CLOJARS_USERNAME` und `CLOJARS_PASSWORD` — ein Deploy-Token, eingeschränkt auf dieses eine Artefakt |
| Veröffentlichungsbefehl | `lein deploy clojars` (Ziel steht als `:deploy-repositories` in `project.clj`) |
| Was die CI prüft | `.github/workflows/test.yml` — derselbe Workflow läuft vor der Veröffentlichung noch einmal gegen den Tag |

**Tags tragen kein `v`.** Die letzten Releases heißen `2.0.1`, `2.2.0`;
deshalb `include-v-in-tag: false`. Mit `v` fände release-please die
Historie nicht wieder und finge bei `1.0.0` an. Die älteren `v1.0.0`- und
`v2.0.0-beta`-Tags stammen aus der Zeit davor und bleiben, wie sie sind.

**WATCHOUT: Der Anzeigename des Releases hat eine zweite Option.**
`include-v-in-release-name: false` steht deshalb daneben. Ohne sie
entsteht ein Tag `2.3.0` mit einem Release namens `v2.3.0` darüber.

**WATCHOUT: Auch der Paketname landet sonst im Tag.** In einer
Manifest-Konfiguration stellt release-please die Komponente voran, der
erste Release-PR hier hieß entsprechend
`jtk-dvlp/re-frame-tasks-2.2.1`. Dagegen steht
`include-component-in-tag: false`. Das Repo enthält genau ein Paket, die
Komponente trennt hier also nichts.

**WATCHOUT: Sichtbar im Changelog heißt versionswirksam.**
release-please kennt keinen Typ, der im Changelog steht, aber die Version
in Ruhe lässt: Sobald ein Commit sichtbar ist, wird mindestens eine
Patch-Version daraus. In `changelog-sections` steht deshalb nur sichtbar,
was den Nutzer erreicht — neben `feat` und `fix` also `perf`, `revert`,
`refactor` und `docs`; Docstrings und README gehören zum Artefakt und
werden von cljdoc je Version gerendert. `build`, `chore`, `ci`, `style`
und `test` bleiben auf `hidden: true`, und ein PR, der nur daraus
besteht, erzeugt gar keinen Release-PR — genau so ist es gewollt.

**`.release-please-manifest.json` und `version.txt` gehören der
Maschine.** Nicht von Hand editieren. `version.txt` legt release-please
beim ersten Release selbst an; gelesen wird sie von niemandem — die
Version, die zählt, steht in `project.clj`.

## Tests

**Es gibt noch keine Unit-Tests.** Die CI prüft bislang nur, dass die
Bibliothek unter `:advanced` durchkompiliert — ohne das `:dev`-Profil,
damit sie nicht unbemerkt an einer Dev-Abhängigkeit hängt. Das ist die
Untergrenze, kein Zielzustand: Was hier an Fehlern auftritt, bekommt
einen Test.

**WATCHOUT: Ein Compiler-Lauf ohne Fehler heißt nicht viel.** Eine
unbekannte Var meldet `cljs.main` nur als `WARNING` und beendet sich mit
0; `:warnings-as-errors` kennt es nicht. Der Schritt wertet deshalb die
Ausgabe aus und bricht bei jeder Warnung ab. Wer den Befehl anfasst, muss
das mitnehmen — sonst prüft die CI nur noch, dass der Compiler startet.
