# UI tests (black-box, Playwright)

Browser tests that use the Orders application the way a clerk does: menu, labelled fields,
buttons, the grid, and the status bar at the bottom. They know nothing about the PL/SQL behind
the screens. The business rules still run for real, because the screens call them.

## Run

```bash
cd ui-tests
npm install && npx playwright install chromium   # once

npm run test:stack          # starts/refreshes your own stack ("ui"), then runs all tests
STACK=bb npm run test:stack # same, on another stack

# against a stack that is already up:
eval "$(../stack.sh env ui)" && npx playwright test
```

- Runs headless. The report goes to `playwright-report/` and never opens a browser.
- Before each test, a fixture runs `../stack.sh seed $STACK` (default `ui`) to reset the data.
  The tests share one database, so they run serially (`workers: 1`).
- `BASE_URL` comes from `stack.sh`. Stop the stack with `../stack.sh down ui`.

## Layout

| file | what it covers |
|---|---|
| `tests/fixtures.ts` | data-reset fixture and user-level helpers (menu, grid rows, status bar, New Order) |
| `tests/orders.spec.ts` | entering, discounts, credit limit, inactive customer, ship/cancel/delete, query, edit, field format |
| `tests/customers.spec.ts` | duplicate name, delete with orders, create and delete |
| `tests/audit.spec.ts` | INSERT and UPDATE rows in the Audit Log after the user acts |

## Watch a test live

```bash
eval "$(../stack.sh env dev)" && STACK=dev DEMO=1 npx playwright test -g "credit limit"
```

`DEMO=1` opens a visible Chromium and slows each action to 0.7 s.
