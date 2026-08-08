import { expect, test, type Page } from "@playwright/test";

async function openSegment(page: Page, name: RegExp): Promise<void> {
  const button = page.getByRole("button", { name }).first();
  if ((await button.getAttribute("aria-expanded")) === "false") await button.click();
}

test.describe.serial("real Datomic-backed explorer", () => {
  test("three-panel navigation, pagination, page size, detail, and theme", async ({
    page,
  }, testInfo) => {
    await page.goto("/");
    await expect(page.getByRole("heading", { name: /EACL Explorer/ })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Subjects & permissions" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Resources" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Detail" })).toBeVisible();

    await page.getByRole("combobox", { name: "Page size" }).selectOption("10");
    await expect(page.getByRole("combobox", { name: "Page size" })).toHaveValue("10");
    await openSegment(page, /Servers/);
    const serverGroup = page.locator(".group-card").filter({ hasText: "Servers" }).first();
    await expect(serverGroup.getByText("1–10", { exact: true })).toBeVisible();
    await serverGroup.getByRole("button", { name: /Servers/ }).click();
    await expect(serverGroup.locator(".group-card__stats")).toBeHidden();
    await serverGroup.getByRole("button", { name: /Servers/ }).click();
    await expect(serverGroup.getByText("1–10", { exact: true })).toBeVisible();
    await serverGroup.getByRole("button", { name: "Next" }).click();
    await expect(serverGroup.getByText("11–20", { exact: true })).toBeVisible();
    await serverGroup.getByRole("button", { name: /Server .*account-/ }).first().click();
    await expect(page.locator(".detail-header")).toContainText("account-");

    await page.getByRole("button", { name: "Dark theme" }).click();
    await expect(page.locator(".app-shell")).toHaveAttribute("data-theme", "dark");
    await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
    expect(await page.locator("body").evaluate((body) => getComputedStyle(body).backgroundImage))
      .toContain("rgb(16, 21, 28)");
    await page.screenshot({
      path: testInfo.outputPath(`explorer-${testInfo.project.name}.png`),
      fullPage: false,
    });
  });

  test("invalid and valid schema writes preserve the controlled draft", async ({ page }) => {
    await page.goto("/");
    await openSegment(page, /^Schema \(/);
    const editor = page.getByRole("textbox", { name: "Spice Schema" });
    const recursiveTab = page.getByRole("tab", { name: "Recursive", exact: true });
    const nonRecursiveTab = page.getByRole("tab", {
      name: "Non-recursive",
      exact: true,
    });
    const startedRecursive = (await recursiveTab.getAttribute("aria-selected")) === "true";
    await editor.fill("definition broken {");
    await page.getByRole("button", { name: "Write Schema" }).click();
    await expect(page.getByText(/invalid|expected|parse/i).first()).toBeVisible();
    await expect(editor).toHaveValue("definition broken {");

    if (startedRecursive) {
      await nonRecursiveTab.click();
      await page.getByRole("button", { name: "Write Schema" }).click();
      await expect(page.getByRole("button", { name: "Write Schema" })).toBeDisabled();
    }
    await recursiveTab.click();
    await page.getByRole("button", { name: "Write Schema" }).click();
    await expect(page.getByRole("button", { name: "Write Schema" })).toBeDisabled();
    await nonRecursiveTab.click();
    await page.getByRole("button", { name: "Write Schema" }).click();
    await expect(page.getByRole("button", { name: "Write Schema" })).toBeDisabled();
  });

  test("cache display is manual across toggle and eviction", async ({ page }) => {
    let cacheReads = 0;
    page.on("request", (request) => {
      if (request.method() === "GET" && new URL(request.url()).pathname === "/api/cache") {
        cacheReads += 1;
      }
    });
    await page.goto("/");
    await openSegment(page, /^Cache$/);
    await expect(page.getByText(/not been captured/i)).toBeVisible();
    await page.getByRole("switch", { name: /Cache Enabled/ }).click();
    await page.getByRole("button", { name: "Evict Cache" }).click();
    await expect.poll(() => cacheReads).toBe(0);
    await page.getByRole("button", { name: "Refresh cache" }).click();
    await expect(page.locator(".cache-metrics__code code")).toContainText('"provider"');
    expect(cacheReads).toBe(1);
    const snapshot = await page.locator(".cache-metrics__code code").textContent();

    await page.getByRole("button", { name: "Evict Cache" }).click();
    await expect(page.locator(".cache-metrics__code code")).toHaveText(snapshot ?? "");
    expect(cacheReads).toBe(1);
  });

  test("seed progress keeps the explorer mounted and refetches while active", async ({
    page,
  }) => {
    let seedReads = 0;
    await page.route("**/api/seed", async (route) => {
      const request = route.request();
      if (request.method() === "POST") {
        await route.fulfill({
          status: 202,
          contentType: "application/json",
          body: JSON.stringify({
            data: {
              status: "seeding",
              serversAdded: 0,
              serversCompleted: 0,
              serversTarget: 2001,
              totalServers: 48,
              label: "Queued Datomic seed job",
            },
            meta: { revision: "d900.c0", requestId: "seed-post" },
          }),
        });
        return;
      }
      seedReads += 1;
      const active = seedReads === 1;
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          data: {
            status: active ? "seeding" : "ready",
            serversAdded: active ? 0 : 2001,
            serversCompleted: active ? 1000 : 2001,
            serversTarget: 2001,
            totalServers: active ? 1048 : 2049,
            label: active ? "Seeded account batch" : null,
          },
          meta: {
            revision: active ? "d901.c0" : "d902.c0",
            requestId: `seed-get-${seedReads}`,
          },
        }),
      });
    });
    await page.goto("/");
    await openSegment(page, /Servers/);
    const serverGroup = page.locator(".group-card").filter({ hasText: "Servers" }).first();
    await expect(serverGroup.locator(".group-card__stats")).toContainText(/of\d+/);
    let resourceQueriesDuringSeed = 0;
    let seedBannerObserved = false;
    page.on("request", (request) => {
      if (
        new URL(request.url()).pathname === "/api/eacl/lookup-resources" &&
        seedBannerObserved
      ) {
        resourceQueriesDuringSeed += 1;
      }
    });
    await page.getByRole("spinbutton", { name: "Servers to seed" }).fill("2001");
    await page.getByRole("button", { name: "Seed DB" }).click();
    await expect(page.locator(".seed-progress-banner")).toBeVisible();
    seedBannerObserved = true;
    await expect(page.getByRole("heading", { name: "Resources" })).toBeVisible();
    await expect(serverGroup).toBeVisible();
    await expect.poll(() => resourceQueriesDuringSeed).toBeGreaterThan(0);
    await expect.poll(() => seedReads).toBeGreaterThanOrEqual(2);
    await expect(page.locator(".seed-progress-banner")).toBeHidden();
    await expect(page.getByRole("button", { name: "Seed DB" })).toBeEnabled();
  });

  test("bootstrap failure offers a focused retry", async ({ page }) => {
    let failures = 0;
    await page.route("**/api/bootstrap", async (route) => {
      if (failures++ === 0) {
        await route.fulfill({
          status: 503,
          contentType: "application/json",
          body: JSON.stringify({ error: { code: "warming", message: "Warming up" } }),
        });
      } else {
        await route.continue();
      }
    });
    await page.goto("/");
    await expect(page.getByText("Warming up")).toBeVisible();
    await page.getByRole("button", { name: "Retry" }).click();
    await expect(page.getByRole("heading", { name: "Resources" })).toBeVisible();
  });

  test("keyboard landmarks and narrow layout stay usable", async ({ page }, testInfo) => {
    await page.goto("/");
    await page.keyboard.press("Tab");
    await expect(page.locator(":focus")).toBeVisible();
    await expect(page.getByRole("navigation", { name: "Source repositories" })).toBeVisible();
    await expect(page.getByRole("combobox", { name: "Page size" })).toBeVisible();
    if (testInfo.project.name === "mobile") {
      expect(
        await page.evaluate(
          () => document.documentElement.scrollWidth <= document.documentElement.clientWidth,
        ),
      ).toBe(true);
    }
  });
});
