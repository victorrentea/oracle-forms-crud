import { defineConfig, devices } from '@playwright/test';

if (!process.env.BASE_URL) {
  throw new Error('BASE_URL is not set. Run: eval "$(../stack.sh up ui)"  (or npm run test:stack)');
}

// DEMO=1 shows the browser and slows every action down, to watch a test run live
const demo = process.env.DEMO === '1';

export default defineConfig({
  testDir: './tests',
  // one shared database, reset before every test => strictly serial
  workers: 1,
  fullyParallel: false,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.BASE_URL,
    headless: !demo,
    launchOptions: { slowMo: demo ? 700 : 0 },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'], headless: !demo } }],
});
