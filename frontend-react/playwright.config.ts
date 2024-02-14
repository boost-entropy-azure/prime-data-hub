import { defineConfig } from "@playwright/test";
import dotenvflow from "dotenv-flow";

import type { TestOptions } from "./e2e/helpers/rs-test.ts";

dotenvflow.config({
    purge_dotenv: true,
    silent: true,
    default_node_env: "test",
});

const isAdminTesting = Boolean(process.env.TEST_ADMIN);

/**
 * See https://playwright.dev/docs/test-configuration.
 */
export default defineConfig<TestOptions>({
    testDir: "e2e",
    /* Run tests in files in parallel */
    fullyParallel: true,
    /* Fail the build on CI if you accidentally left test.only in the source code. */
    forbidOnly: !!process.env.CI,
    /* Retry on CI only */
    retries: process.env.CI ? 2 : 0,
    /* Opt out of parallel tests on CI. */
    workers: process.env.CI ? 1 : undefined,
    /* Reporter to use. See https://playwright.dev/docs/test-reporters */
    reporter: "html",
    /* Shared settings for all the projects below. See https://playwright.dev/docs/api/class-testoptions. */
    use: {
        /* Base URL to use in actions like `await page.goto('/')`. */
        baseURL: "http://localhost:4173",

        /* Collect trace when retrying the failed test. See https://playwright.dev/docs/trace-viewer */
        trace: "on-first-retry",

        adminLogin: {
            username: process.env.TEST_ADMIN_USERNAME ?? "",
            password: process.env.TEST_ADMIN_PASSWORD ?? "",
            totpCode: process.env.TEST_ADMIN_TOTP_CODE ?? "",
        },
    },

    /* Configure projects for major browsers */
    projects: [
        isAdminTesting ? { name: "setup", testMatch: /.*\.setup\.ts/ } : {},
        {
            name: "chromium",
            use: { browserName: "chromium" },
            dependencies: [isAdminTesting ? "setup" : ""] as any,
        },

        {
            name: "firefox",
            use: { browserName: "firefox" },
            dependencies: [isAdminTesting ? "setup" : ""] as any,
        },

        {
            name: "webkit",
            use: { browserName: "webkit" },
            dependencies: [isAdminTesting ? "setup" : ""] as any,
        },

        /* Test against mobile viewports. */
        // {
        //   name: 'Mobile Chrome',
        //   use: { ...devices['Pixel 5'] },
        // },
        // {
        //   name: 'Mobile Safari',
        //   use: { ...devices['iPhone 12'] },
        // },

        /* Test against branded browsers. */
        // {
        //   name: 'Microsoft Edge',
        //   use: { ...devices['Desktop Edge'], channel: 'msedge' },
        // },
        // {
        //   name: 'Google Chrome',
        //   use: { ...devices['Desktop Chrome'], channel: 'chrome' },
        // },
    ],

    /* Run the local dev server and start the tests */
    webServer: {
        command: "yarn run preview:build",
        url: "http://localhost:4173",
        timeout: 1000 * 180,
        stdout: "pipe",
        // reuseExistingServer: !process.env.CI,
    },
});
