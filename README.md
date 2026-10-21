# Orders form — Oracle Forms tradition, tested black-box

A deliberately tiny CRUD in the style of an Oracle Forms app. The **business rules live in PL/SQL**: packages, triggers and an audit trail. The screens are thin: every button calls exactly one package procedure, and every `ORA-200xx` the PL/SQL raises shows up verbatim in a Forms-like status bar. Around it sit several test layers, ending with **black-box suites** that know nothing about the procedures, even though those procedures run on every test.

```
db/                 schema, triggers, customers_api / orders_api, demo_data.reset (the seed)
app/                Spring Boot + Thymeleaf screens: Orders (query + form), Customers, Audit Log
stack.sh            isolated Oracle Free 23ai + app stacks (Podman), host-chosen ports
capture.sh          records what clients send to the DB (Unified Auditing), for replay
whitebox.sh         utPLSQL unit tests of the PL/SQL                       — white-box
blackbox-tests/     HTTP specs, combinatorial golden masters, DB replay     — black-box
ui-tests/           Playwright, headless browser                           — black-box
test-all.sh         all layers on a fresh throw-away stack (~1.5 min)
forms/              a REAL Oracle Forms 12.2.1.19 server + the same Orders screen as a .fmx,
                    opened in the desktop Java client, and tests driving that client — black-box
```

## Run it

```bash
./stack.sh up            # Oracle Free + app; prints BASE_URL and DB_URL
open "$(./stack.sh env | sed -n 's/.*BASE_URL=//p')/orders"
./test-all.sh            # every layer on its own fresh stack "all", removed at the end
./stack.sh down
```

The stacks run in **Podman** (`podman machine`, 16 GB) because Docker Desktop's disk was full. To use Docker instead: `ENGINE=docker ./stack.sh up`. Oracle image: `gvenzl/oracle-free:23-slim-faststart` (arm64), app user `app/app`, SYSTEM password `oracle`, PDB `FREEPDB1`.

## The rules (all in `db/03_packages.sql` and `db/02_triggers.sql`)

| rule | where | error |
|---|---|---|
| total = qty × price, minus 5% for 10+ items and 10% for 50+ | trigger `orders_biu` | — |
| quantity a positive whole number, price positive, product required | `orders_api.validate` | ORA-20001 |
| open orders (NEW + SHIPPED) may not exceed the customer's credit limit | `orders_api.check_credit` | ORA-20002 |
| inactive customers cannot order | `orders_api.create_order` | ORA-20003 |
| NEW → SHIPPED or CANCELLED; only NEW can be edited; SHIPPED cannot be deleted | `orders_api` | ORA-20004 |
| a customer with orders cannot be deleted; names are unique | `customers_api` | ORA-20005 / 20007 |
| every insert, update and delete is written to `AUDIT_LOG` | triggers `*_audit` | — |

## The test layers

| layer | how | black/white | count |
|---|---|---|---|
| `whitebox.sh` | utPLSQL 3.2.3 suites call the packages directly, with fixtures rolled back | **white** | 67 + 5 known bugs |
| `OrderRulesTest`, `CustomerRulesTest` | a "user without a browser": opens screens, types into fields **by label**, presses buttons **by caption**, reads the status bar | **black** | 30 |
| `CreateOrderGoldenMasterTest` | every combination of customer × quantity × price on the New Order screen (150 cases), approved with ApprovalTests | **black**, characterization | 1 file |
| `OrderLifecycleGoldenMasterTest` | every pair of buttons (Ship, Cancel, Delete, Save) on orders in every status | **black**, characterization | 1 file |
| `DbReplayGoldenMasterTest` | replays **recorded production traffic** at the DB boundary, approves each call's outcome plus the final tables | **black** below the UI | 1 per recording |
| `ui-tests/` | Playwright, the same rules through a headless Chromium | **black** | 17 |

The golden-master files live in `blackbox-tests/src/test/java/ro/victorrentea/blackbox/approved/`. When a run differs, a `*.received.txt` appears next to the approved file. Diff the two, then rename received to approved if the change was intended. No diff tool pops up.

### Record and replay: the part that transfers to a real Forms app

```bash
./capture.sh start bb                       # Unified Auditing policy on the app's packages and tables
# ... users work: the web app, an Oracle Forms runtime, SQL*Plus — any client ...
./capture.sh dump bb > blackbox-tests/src/test/resources/db-recordings/monday.rec
./capture.sh stop bb
cd blackbox-tests && mvn test -Dtest=DbReplayGoldenMasterTest   # first run: approve monday.approved.txt
```

The capture records **top-level** calls to `orders_api`/`customers_api` and DML sent **straight to the tables**, which is what an Oracle Forms base-table block does. It records bind values too. Recursive SQL inside the packages is not recorded. Two sample recordings are included:

- `business-day.rec`: 54 calls produced by `SimulateBusinessDayTest`, a seeded random "day" of clicking.
- `forms-block-dml.rec`: direct `INSERT/UPDATE/DELETE` with named binds, the shape a Forms block sends.

Proof that it catches regressions: I changed the 5% discount threshold from 10 to 11 items in the database only. The replay failed, and the diff pointed at exactly order 107 and its two audit rows.

What to know before trusting a replay:

- **It starts from a known snapshot** (`demo_data.reset`). In production, that means a masked copy of the data taken when the capture started.
- **Recorded ids are literal.** If new code consumes identity values differently, later calls target other rows. Once the first diff appears, the rest of the file is noise.
- **Replay is single-session and sequential.** Concurrency bugs are not reproduced. For example, two sessions can both pass the credit check, because nothing locks the customer row.
- **Golden master means "changed", not "wrong".** The approved files pin today's bugs too. For example, a 0.004 unit price is stored as 0.00 and the order becomes free. Read a baseline before approving it.

## Applying this to the client's Oracle Forms (desktop Java client, not a browser)

Their Forms runs through the **Forms Standalone Launcher** (`java -jar frmsal.jar -url .../frmservlet?config=standaloneapp`). That is a plain Java program drawing Oracle's own EWT components. **Playwright and Selenium cannot drive it.** What does work, in order of value:

1. **Record and replay at the database, as above.** It works whatever the client is, so it works on their system as-is. Their DBA enables a Unified Auditing policy on the application schema for a while in production. You replay the recording against a masked copy and compare. Note the scope: this replays what the Forms triggers *sent* to the DB. So it guards the DB-side logic (packages, DB triggers, constraints) while you refactor it, but it does **not** re-execute logic that lives in the `.fmb` triggers (WHEN-VALIDATE-ITEM, PRE-INSERT…), which runs in the Forms server.
2. **Forms Trace for the form-side logic.** Add `record=forms&tracegroup=...` to the FSAL URL or to `formsweb.cfg`. Run a scenario by hand, then translate the `.trc` to XML with `oracle.forms.diagnostics.Xlate`. Strip timings and pids, and diff the sequence of triggers and SQL between versions. There is no replay tool for it. It replaced the older FRD `record=collect` in 11g. [Oracle docs, Forms Trace](https://docs.oracle.com/cd/E23943_01/web.1111/e10240/tracing.htm)
3. **Driving the real UI** is possible only with commercial, Windows-centred tools. TestComplete documents Oracle Forms 11g/12c support for applets and Web Start, but does not mention FSAL. OATS/OpenScript has a Forms module. Image-based tools such as SikuliX work on anything but are fragile. Attaching a Java agent to the FSAL JVM to walk the component tree is technically possible, but I found no documented success. [TestComplete Oracle Forms](https://support.smartbear.com/testcomplete/docs/app-testing/desktop/oracle-forms/requirements.html), [FSAL docs](https://docs.oracle.com/en/middleware/developer-tools/forms/12.2.1.19/working-forms/forms-standalone-launcher.html)
4. **Protocol load tools** decode Forms traffic: LoadRunner "Oracle NCA" and NeoLoad's Oracle Forms module. They are built for load, not functional checks, and neither documents FSAL or 14c.
5. **A real Forms 14.1.2 to play with** needs Linux or Windows on x86-64, with FMW Infrastructure, downloaded from eDelivery with an Oracle account. It will not run on an Apple Silicon Mac except under emulation, which is untested. Community containers exist: [javalchemist/oracle23-forms1412-container](https://github.com/javalchemist/oracle23-forms1412-container) and [felipebz/oracle-forms-openapi-container](https://github.com/felipebz/oracle-forms-openapi-container).

## Known bugs, found by the white-box layer (left in on purpose)

`KNOWN_BUGS=1 ./whitebox.sh <stack>` runs them as failing tests:

1. A unit price below one cent passes validation and is stored as 0.00, so the order is free.
2. The audit trail prints `#############` for amounts of 1 billion or more, because the format mask is one digit too short.
3. A quantity of 1,000,000 or more, or a product name over 100 characters, raises a raw ORA-01438 or ORA-12899 instead of an ORA-20001 message.

Fixing one is a good classroom exercise. Watch which golden masters change, and why.

## The real thing: Oracle Forms 12c on a desktop Java client (`forms/`)

The web app above is a stand-in. `forms/` runs **actual Oracle Forms 12.2.1.19** over the same database, and its client is a desktop Java window, not a browser page, the way the client's users see it:

```
Mac (arm64)                                  Lima VM (vz + Rosetta, Ubuntu 24.04 kernel 6.8)
┌───────────────────────────────┐            ┌─────────────────────────────────────────────┐
│ Forms client window (Java 8)   │  HTTP      │ x86 Oracle Linux 8 container                 │
│  java -jar frmsal.jar          │──:9001────▶│  WebLogic 12.2.1.4: AdminServer + WLS_FORMS   │
│  + forms-driver agent :9971 ◀──┼── tests    │  frmweb runtime runs ORDERS.fmx               │
└───────────────────────────────┘            └──────────────────┬──────────────────────────┘
                                                                 │ SQL*Net
                                            Podman: Oracle Free 23ai (app schema + RCU schemas)
```

- **The form** is `forms/src/ORDERS.xml`. `forms/build-form.sh` converts it to `.fmb` with `frmxml2f`, then compiles it to `.fmx` with `frmcmp_batch`. The form is classic Forms: a multi-record block on `ORDERS`, `ON-INSERT/ON-UPDATE/ON-DELETE` triggers that call `orders_api`, and Ship/Cancel buttons. Ctrl+S commits.
- **The client** is started by `forms/run-client.sh`. It uses the Forms Standalone Launcher on Oracle JDK 8, which is browser-less. Java Web Start also works, through OpenWebStart, at `http://localhost:9001/forms/frmservlet?config=orders`. Oracle's own `javaws` has no Apple Silicon build.
- **The driver** is `forms/driver/`, a Java agent inside the client JVM. It reads the live component tree (`oracle.forms.ui.VTextField` cells, `VButton`s, the status bar) and posts keystrokes into the client's own event queue. The tests never take your mouse or keyboard, and the Forms window can stay in the background.
- **The tests** are in `forms/ui-tests/`. They use a small Playwright-style layer over the driver, in `forms/ui-tests/.../pw/`:
  - `FormsApp` plays the role of Playwright's Page, with `Locator`s and `expect(...).toHaveText/toContainText` that auto-wait.
  - Each test writes an HTML trace (`-Dtrace=true` adds a screenshot after every key; a failure always gets one).
  - The Page Object `OrdersPage` speaks clerk language: `enterNewOrder(...)`, `save()`, `goToLine(2).cancelOrder()`, `line(4).total()`.
- **The Gherkin layer** on top: `features/orders.feature` runs through Cucumber 8. Every step attaches the Forms window to `target/cucumber-report.html`.
- **Fast and parallel.** `FormsPool` keeps one long-lived client per worker (default 3: `-Dworkers`). A test borrows a worker and gets a clean slate in under a second: F8, the form's Clear Form, rolls back and re-queries; the worker's data is reset; F8 again. Each worker has its own schema (`APP1..APP3`, created by `forms/setup-workers.sh`) and its own `[ordersN]` config, so tests running side by side never see each other's orders. The 6 JUnit tests plus the 7 scenarios take ~17 s, down from ~76 s with a fresh client per test.
- **Headless by default.** The clients run inside the Lima VM on virtual displays (Xvfb), so nothing shows on the Mac, and Lima forwards the driver ports (9971+) back to the Mac. `-Dheaded=true` opens them on the Mac instead, slowed down so you can follow them.

```bash
forms/setup-workers.sh 3                           # once: schemas APP1..3 + configs [orders1..3]
cd forms/ui-tests && mvn test                      # headless, 3 in parallel
mvn test -Dheaded=true -Dworkers=1                 # watch one client type on this Mac
```

```gherkin
Scenario Outline: Ordering more items earns a volume discount
  When I enter a new order for customer 1: <qty> x "Bolt" at 10
  And I save
  Then the message line says "FRM-40400: Transaction complete: 1 records applied and saved."
  And order line 4 shows a discount of <discount>% and a total of <total>
```

Setup notes and gotchas from the install:
- **Rosetta inside Linux VMs:** Rosetta on macOS 15.7 crashes on recent kernels (Podman's Fedora 6.15, Lima's default kernel). Ubuntu 24.04's kernel 6.8 works.
- **RCU:** RCU 12.2.1.4 created its schemas in Oracle 23ai Free without complaint.
- **Ubuntu's OpenJDK 8:** it loads the GNOME accessibility bridge by default, and that bridge deadlocks the Forms client at startup. `-Djavax.accessibility.assistive_technologies=' '` turns it off. The client JVM also ignores SIGTERM, so tests SIGKILL it.
- **The forms1 instance:** a WLST-built domain gets an incomplete `forms1` instance. `setup-domain.sh` fills it in: config templates, placeholders, the `instances/forms1` path, and `fmrweb.res`. The servlet reads `servers/WLS_FORMS/applications/formsapp_12.2.1/config/formsweb.cfg`.
- **The database port is baked in:** the domain's datasource and `APPDB` both point at the Podman database's host port. Recreate the `dev` stack and you must update them.
