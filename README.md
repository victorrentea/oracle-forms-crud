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
