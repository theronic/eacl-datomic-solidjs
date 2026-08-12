import { expect, test, type Page, type Route } from "@playwright/test";

async function openSegment(page: Page, name: RegExp): Promise<void> {
  const button = page.getByRole("button", { name }).first();
  if ((await button.getAttribute("aria-expanded")) === "false") await button.click();
}

test.describe.serial("real Datahike-backed explorer", () => {
  test("three-panel navigation, pagination, page size, detail, and theme", async ({
    page,
  }, testInfo) => {
    await page.goto("./");
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

  test("pagination never presents stale resources as the requested page", async ({
    page,
  }) => {
    let releaseFailure: () => void = () => undefined;
    const failureGate = new Promise<void>((resolve) => {
      releaseFailure = resolve;
    });
    let failNextPage = true;
    await page.route("**/api/eacl/lookup-resources", async (route) => {
      const body = route.request().postDataJSON() as { after?: string } | null;
      if (!body?.after || !failNextPage) {
        await route.continue();
        return;
      }
      await failureGate;
      failNextPage = false;
      await route.fulfill({
        status: 504,
        contentType: "application/json",
        body: JSON.stringify({
          error: {
            code: "backend-timeout",
            message: "Injected unreliable-backend timeout",
          },
        }),
      });
    });

    await page.goto("./");
    await openSegment(page, /Servers/);
    const serverGroup = page.locator(".group-card").filter({ hasText: "Servers" }).first();
    const firstPageResource = serverGroup.locator(".resource-button").first();
    const firstPageText = await firstPageResource.textContent();
    await serverGroup.getByRole("button", { name: "Next" }).click();

    await expect(serverGroup.getByText("Loading server page 2…")).toBeVisible();
    await expect(serverGroup.locator(".group-card__page-stats"))
      .toContainText("Loading page 2…");
    await expect(serverGroup.locator(".resource-tree")).toHaveCount(0);
    if (firstPageText) await expect(serverGroup.getByText(firstPageText.trim())).toHaveCount(0);

    releaseFailure();
    await expect(serverGroup.getByText("Injected unreliable-backend timeout"))
      .toBeVisible();
    await expect(serverGroup.locator(".group-card__page-stats"))
      .toContainText("Page 2 failed");
    await expect(serverGroup.getByRole("button", { name: "Retry" })).toBeVisible();
    await expect(serverGroup.getByRole("button", { name: "Previous page" })).toBeVisible();

    await serverGroup.getByRole("button", { name: "Retry" }).click();
    await expect(serverGroup.getByText("21–40", { exact: true })).toBeVisible();
    await expect(serverGroup.locator(".resource-tree")).toBeVisible();
  });

  test("invalid and valid schema writes preserve the controlled draft", async ({ page }) => {
    await page.goto("./");
    await openSegment(page, /^Schema \(/);
    const editor = page.getByRole("textbox", { name: "Spice Schema" });
    const writeButton = page.getByRole("button", { name: "Write Schema" });
    if ((await writeButton.count()) === 0) {
      await expect(editor).toHaveAttribute("readonly");
      await expect(page.getByText("Read-only public demo")).toBeVisible();
      return;
    }
    const recursiveTab = page.getByRole("tab", { name: "Recursive", exact: true });
    const nonRecursiveTab = page.getByRole("tab", {
      name: "Non-recursive",
      exact: true,
    });
    const startedRecursive = (await recursiveTab.getAttribute("aria-selected")) === "true";
    await editor.fill("definition broken {");
    await writeButton.click();
    await expect(page.getByText(/invalid|expected|parse/i).first()).toBeVisible();
    await expect(editor).toHaveValue("definition broken {");

    if (startedRecursive) {
      await nonRecursiveTab.click();
      await writeButton.click();
      await expect(writeButton).toBeDisabled();
    }
    await recursiveTab.click();
    await writeButton.click();
    await expect(writeButton).toBeDisabled();
    await nonRecursiveTab.click();
    await writeButton.click();
    await expect(writeButton).toBeDisabled();
  });

  test("cache display is manual across toggle and eviction", async ({ page }) => {
    let cacheReads = 0;
    page.on("request", (request) => {
      if (request.method() === "GET" && new URL(request.url()).pathname.endsWith("/api/cache")) {
        cacheReads += 1;
      }
    });
    await page.goto("./");
    await openSegment(page, /^Cache$/);
    await expect(page.getByText(/not been captured/i)).toBeVisible();
    await page.getByRole("switch", { name: /Cache Enabled/ }).click();
    const evictButton = page.getByRole("button", { name: "Evict Cache" });
    if ((await evictButton.count()) > 0) await evictButton.click();
    await expect.poll(() => cacheReads).toBe(0);
    await page.getByRole("button", { name: "Refresh cache" }).click();
    await expect(page.locator(".cache-metrics__code code")).toContainText('"provider"');
    expect(cacheReads).toBe(1);

    if ((await evictButton.count()) > 0) {
      await evictButton.click();
      await expect(page.locator(".cache-metrics__code code")).toHaveCount(0);
      await expect(page.getByText(/not been captured/i)).toBeVisible();
      expect(cacheReads).toBe(1);
    }
  });

  test("seed progress keeps the current page stable until the terminal revision", async ({
    page,
  }) => {
    let seedReads = 0;
    let finishSeed = false;
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
              label: "Queued Datahike seed job",
            },
            meta: { revision: "h900.c0", requestId: "seed-post" },
          }),
        });
        return;
      }
      seedReads += 1;
      if (seedReads === 1) {
        await route.fulfill({
          status: 503,
          contentType: "application/json",
          body: JSON.stringify({
            error: {
              code: "seed-status-unavailable",
              message: "Injected seed status outage",
            },
          }),
        });
        return;
      }
      const active = !finishSeed;
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
            revision: active ? "h901.c0" : "h902.c0",
            requestId: `seed-get-${seedReads}`,
          },
        }),
      });
    });
    await page.goto("./");
    const seedButton = page.getByRole("button", { name: "Seed DB" });
    if ((await seedButton.count()) === 0) {
      await expect(page.getByRole("heading", { name: "Resources" })).toBeVisible();
      await expect(page.getByRole("spinbutton", { name: "Servers to seed" }))
        .toHaveCount(0);
      return;
    }
    await openSegment(page, /Servers/);
    const serverGroup = page.locator(".group-card").filter({ hasText: "Servers" }).first();
    await expect(serverGroup.locator(".group-card__stats")).toContainText(/of\d+/);
    let resourceQueriesDuringSeed = 0;
    let resourceQueriesAfterSeed = 0;
    let seedBannerObserved = false;
    page.on("request", (request) => {
      if (
        new URL(request.url()).pathname.endsWith("/api/eacl/lookup-resources") &&
        seedBannerObserved
      ) {
        if (finishSeed) resourceQueriesAfterSeed += 1;
        else resourceQueriesDuringSeed += 1;
      }
    });
    await page.getByRole("spinbutton", { name: "Servers to seed" }).fill("2001");
    await seedButton.click();
    await expect(page.locator(".seed-progress-banner")).toBeVisible();
    seedBannerObserved = true;
    await expect(page.getByText("Injected seed status outage")).toBeVisible();
    await page.getByRole("button", { name: "Retry" }).click();
    await expect(page.locator(".seed-progress-banner")).toBeVisible();
    await expect(page.getByRole("heading", { name: "Resources" })).toBeVisible();
    await expect(serverGroup).toBeVisible();
    await expect.poll(() => seedReads).toBeGreaterThanOrEqual(2);
    expect(resourceQueriesDuringSeed).toBe(0);
    finishSeed = true;
    await expect(page.locator(".seed-progress-banner")).toBeHidden();
    await expect.poll(() => resourceQueriesAfterSeed).toBeGreaterThan(0);
    await expect(seedButton).toBeEnabled();
  });

  test("bootstrap failure offers a focused retry", async ({ page }) => {
    const warming = async (route: Route) => {
      await route.fulfill({
        status: 503,
        contentType: "application/json",
        body: JSON.stringify({ error: { code: "warming", message: "Warming up" } }),
      });
    };
    const bootstrapPattern = /\/api\/bootstrap(?:\?.*)?$/;
    await page.route(bootstrapPattern, warming);
    await page.goto("./");
    await expect(page.getByText("Warming up")).toBeVisible();
    await page.unroute(bootstrapPattern, warming);
    await page.getByRole("button", { name: "Retry" }).click();
    await expect(page.getByRole("heading", { name: "Resources" })).toBeVisible();
  });

  test("keyboard landmarks and narrow layout stay usable", async ({ page }, testInfo) => {
    await page.goto("./");
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

  test("API requests stay under the configured application base", async ({
    page,
  }) => {
    const base = new URL(test.info().project.use.baseURL as string);
    const prefix = base.pathname.replace(/\/$/, "");
    const apiPaths: string[] = [];
    page.on("request", (request) => {
      const url = new URL(request.url());
      if (url.origin === base.origin && url.pathname.includes("/api/")) {
        apiPaths.push(url.pathname);
      }
    });
    await page.goto("./");
    await expect(page.getByRole("heading", { name: "Resources" })).toBeVisible();
    expect(apiPaths.length).toBeGreaterThan(0);
    for (const path of apiPaths) expect(path.startsWith(`${prefix}/api/`)).toBe(true);
  });
});
