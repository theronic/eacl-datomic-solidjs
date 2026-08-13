import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import { setFetchImplementation } from "../src/api";
import { CachePanel } from "../src/components/CachePanel";
import { Header } from "../src/components/Header";
import { DetailPanel } from "../src/components/DetailPanel";
import { ResourceTreePanel } from "../src/components/ResourceTree";
import { AppStateProvider } from "../src/state";
import { bootstrap, failure, jsonResponse, success } from "./fixtures";

describe("reactive resource paging", () => {
  it("resets page cursors on page-size changes without coupling the exact count", async () => {
    const requestBodies: Array<{ path: string; body: Record<string, unknown> }> = [];
    let releaseSecondPage: () => void = () => undefined;
    const secondPageGate = new Promise<void>((resolve) => {
      releaseSecondPage = resolve;
    });
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path === "/api/bootstrap") return jsonResponse(success(bootstrap));
      if (path === "/api/eacl/lookup-resources") {
        const body = JSON.parse(String(init?.body)) as Record<string, unknown>;
        requestBodies.push({ path, body });
        if (body.after) await secondPageGate;
        const suffix = body.after ? "page-2" : "page-1";
        return jsonResponse({
          ...success({
            items: [{ type: "server", id: `server-${suffix}` }],
            pageInfo: {
              startCursor: "start",
              endCursor: "next-cursor",
              hasNextPage: !body.after,
              hasPreviousPage: Boolean(body.after),
            },
          }),
          meta: {
            revision: "h100.c0",
            requestId: "page-request",
            elapsedMs: body.after ? 4.7 : 3.2,
            cacheStatus: "hit" as const,
          },
        });
      }
      if (path === "/api/eacl/count-resources") {
        requestBodies.push({ path, body: JSON.parse(String(init?.body)) });
        return jsonResponse({
          ...success({ count: 48, limit: 50_000, truncated: false }),
          meta: {
            revision: "h100.c0",
            requestId: "count-request",
            elapsedMs: 14_379.3,
            cacheStatus: "miss" as const,
          },
        });
      }
      if (path === "/api/cache/evict") {
        return jsonResponse(success({ status: "evicted" }, "h100.c1"));
      }
      if (path === "/api/eacl/lookup-subjects") {
        return jsonResponse(success({
          items: [{ type: "user", id: "user-1" }],
          pageInfo: { hasNextPage: false, hasPreviousPage: false },
        }));
      }
      if (path === "/api/seed") throw new Error("Seed not expected");
      throw new Error(`Unexpected request: ${path}`);
    });
    setFetchImplementation(fetchMock as typeof fetch);
    render(() => (
      <AppStateProvider>
        <Header />
        <CachePanel />
        <ResourceTreePanel />
        <DetailPanel />
      </AppStateProvider>
    ));

    const groupButton = await screen.findByRole("button", { name: /Servers/ });
    const group = groupButton.closest(".group-card") as HTMLElement;
    fireEvent.click(groupButton);
    await screen.findByText("Server Page 1");
    expect(screen.getByText("Server Page 1").parentElement)
      .toHaveTextContent("Server Page 1 server-page-1");
    expect(await within(group).findByText("1–1")).toBeInTheDocument();
    expect(await within(group).findByText("48")).toBeInTheDocument();
    expect(
      requestBodies.find(({ path }) => path.endsWith("count-resources"))?.body,
    ).toMatchObject({ countLimit: 50_000 });

    const lookupsBeforeEviction = requestBodies.filter(({ path }) =>
      path.endsWith("lookup-resources"),
    ).length;
    const countsBeforeEviction = requestBodies.filter(({ path }) =>
      path.endsWith("count-resources"),
    ).length;
    fireEvent.click(screen.getByRole("button", { name: /^cache$/i }));
    fireEvent.click(screen.getByRole("button", { name: "Evict Cache" }));
    await waitFor(() => {
      expect(
        requestBodies.filter(({ path }) => path.endsWith("lookup-resources")),
      ).toHaveLength(lookupsBeforeEviction + 1);
      expect(
        requestBodies.filter(({ path }) => path.endsWith("count-resources")),
      ).toHaveLength(countsBeforeEviction + 1);
    });
    await Promise.resolve();
    expect(
      requestBodies.filter(({ path }) => path.endsWith("lookup-resources")),
    ).toHaveLength(lookupsBeforeEviction + 1);
    expect(
      requestBodies.filter(({ path }) => path.endsWith("count-resources")),
    ).toHaveLength(countsBeforeEviction + 1);

    await within(group).findByText("Server Page 1");
    fireEvent.click(screen.getByRole("button", { name: /Server Page 1.*server-page-1/i }));
    expect(await screen.findByRole("button", { name: /User 1/ })).toBeInTheDocument();
    const countStats = group.querySelector(".group-card__count-stats");
    expect(countStats).toHaveTextContent("48(14,379.3msmiss)");
    fireEvent.click(
      screen
        .getAllByRole("button", { name: "Next" })
        .find((button) => !(button as HTMLButtonElement).disabled)!,
    );
    await waitFor(() => {
      expect(
        requestBodies.some(({ path, body }) =>
          path.endsWith("lookup-resources") && body.after === "next-cursor",
        ),
      ).toBe(true);
    });
    const pendingNext = within(group).getByRole("button", { name: "Next" });
    expect(pendingNext).toBeDisabled();
    expect(pendingNext).toHaveAttribute("aria-busy", "true");
    expect(pendingNext.querySelector(".button-spinner")).toBeInTheDocument();
    expect(within(group).getByText("Server Page 1")).toBeInTheDocument();
    expect(within(group).getByText("Page 1")).toBeInTheDocument();
    expect(group.querySelector(".group-card__page-stats"))
      .toHaveTextContent("1–1(3.2mshit)");
    expect(group.querySelector(".group-card__page-stats .inline-loading"))
      .not.toBeInTheDocument();
    expect(group.querySelector(".group-card__page-stats"))
      .not.toHaveTextContent("21–21");
    expect(group.querySelector(".group-card__count-stats")).toBe(countStats);
    expect(countStats).toHaveTextContent("48(14,379.3msmiss)");
    releaseSecondPage();
    await screen.findByText("Server Page 2");
    expect(group.querySelector(".group-card__count-stats")).toBe(countStats);
    expect(group.querySelector(".group-card__page-stats"))
      .toHaveTextContent("21–21(4.7mshit)");
    const countRequestsAfterEviction = requestBodies.filter(({ path }) =>
      path.endsWith("count-resources"),
    ).length;

    fireEvent.change(screen.getByRole("combobox", { name: "Page size" }), {
      target: { value: "50" },
    });
    await waitFor(() => {
      const pages = requestBodies.filter(({ path }) => path.endsWith("lookup-resources"));
      expect(pages.at(-1)?.body).toMatchObject({ pageSize: 50 });
      expect(pages.at(-1)?.body.after).toBeUndefined();
    });
    expect(requestBodies.filter(({ path }) => path.endsWith("count-resources")))
      .toHaveLength(countRequestsAfterEviction);

    fireEvent.click(groupButton);
    expect(within(group).queryByText("48")).not.toBeInTheDocument();
  });

  it("bounds totals at 50,000 and doubles only the clicked count until exact", async () => {
    const countBodies: Array<Record<string, unknown>> = [];
    let pageRequests = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path === "/api/bootstrap") return jsonResponse(success(bootstrap));
      if (path === "/api/eacl/lookup-resources") {
        pageRequests += 1;
        return jsonResponse(success({
          items: [{ type: "server", id: "server-page-1" }],
          pageInfo: {
            hasNextPage: false,
            hasPreviousPage: false,
          },
        }));
      }
      if (path === "/api/eacl/count-resources") {
        const body = JSON.parse(String(init?.body)) as Record<string, unknown>;
        countBodies.push(body);
        const limit = Number(body.countLimit);
        return jsonResponse(success(
          limit < 200_000
            ? { count: limit, limit, truncated: true }
            : { count: 175_000, limit, truncated: false },
        ));
      }
      throw new Error(`Unexpected request: ${path}`);
    });
    setFetchImplementation(fetchMock as typeof fetch);
    render(() => (
      <AppStateProvider>
        <CachePanel />
        <ResourceTreePanel />
      </AppStateProvider>
    ));

    const groupButton = await screen.findByRole("button", { name: /Servers/ });
    const group = groupButton.closest(".group-card") as HTMLElement;
    fireEvent.click(groupButton);

    const truncatedCount = await within(group).findByRole("button", {
      name: /Count beyond 50,000 server resources/,
    });
    expect(truncatedCount).toHaveTextContent("50,000+");
    expect(countBodies).toEqual([
      expect.objectContaining({ countLimit: 50_000, cache: true }),
    ]);
    const pagesBeforeDoubling = pageRequests;

    fireEvent.click(truncatedCount);
    const doubledCount = await within(group).findByRole("button", {
      name: /Count beyond 100,000 server resources/,
    });
    expect(doubledCount).toHaveTextContent("100,000+");
    expect(countBodies).toEqual([
      expect.objectContaining({ countLimit: 50_000, cache: true }),
      expect.objectContaining({ countLimit: 100_000, cache: true }),
    ]);
    expect(pageRequests).toBe(pagesBeforeDoubling);

    fireEvent.click(doubledCount);
    expect(await within(group).findByText("175,000")).toBeInTheDocument();
    expect(countBodies).toEqual([
      expect.objectContaining({ countLimit: 50_000, cache: true }),
      expect.objectContaining({ countLimit: 100_000, cache: true }),
      expect.objectContaining({ countLimit: 200_000, cache: true }),
    ]);
    expect(pageRequests).toBe(pagesBeforeDoubling);
    expect(within(group).queryByRole("button", {
      name: /Count beyond/,
    })).not.toBeInTheDocument();

    const cacheSwitch = screen.getByRole("switch", { name: /Cache Enabled/ });
    fireEvent.click(cacheSwitch);
    await waitFor(() => {
      expect(countBodies.at(-1)).toMatchObject({ countLimit: 50_000, cache: false });
    });
    expect(await within(group).findByRole("button", {
      name: /Count beyond 50,000 server resources/,
    })).toBeInTheDocument();

    fireEvent.click(cacheSwitch);
    await waitFor(() => {
      expect(countBodies.at(-1)).toMatchObject({ countLimit: 50_000, cache: true });
    });
  });

  it("retains the last successful page with a retryable error before publishing recovered data", async () => {
    let secondPageAttempts = 0;
    setFetchImplementation(
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const path = String(input);
        if (path === "/api/bootstrap") return jsonResponse(success(bootstrap));
        if (path === "/api/eacl/count-resources") {
          return jsonResponse(success({ count: 48, limit: 50_000, truncated: false }));
        }
        if (path === "/api/eacl/lookup-resources") {
          const body = JSON.parse(String(init?.body)) as Record<string, unknown>;
          if (body.after && ++secondPageAttempts === 1) {
            return failure("backend-timeout", "Authorization traversal timed out", 504);
          }
          return jsonResponse(success({
            items: [{
              type: "server",
              id: body.after ? "server-page-2" : "server-page-1",
            }],
            pageInfo: {
              endCursor: "next-cursor",
              hasNextPage: !body.after,
              hasPreviousPage: Boolean(body.after),
            },
          }));
        }
        throw new Error(`Unexpected request: ${path}`);
      }) as typeof fetch,
    );
    render(() => (
      <AppStateProvider>
        <ResourceTreePanel />
      </AppStateProvider>
    ));

    const groupButton = await screen.findByRole("button", { name: /Servers/ });
    const group = groupButton.closest(".group-card") as HTMLElement;
    fireEvent.click(groupButton);
    await within(group).findByText("Server Page 1");
    fireEvent.click(within(group).getByRole("button", { name: "Next" }));

    expect(await within(group).findByText("Authorization traversal timed out"))
      .toBeInTheDocument();
    expect(within(group).getByText("Server Page 1")).toBeInTheDocument();
    expect(within(group).getByText("Page 1")).toBeInTheDocument();
    expect(within(group).getByRole("button", { name: "Next" })).toBeEnabled();
    expect(within(group).getByText("Page 2 failed")).toBeInTheDocument();
    expect(within(group).getByRole("button", { name: "Previous page" }))
      .toBeInTheDocument();

    fireEvent.click(within(group).getByRole("button", { name: "Retry" }));
    expect(await within(group).findByText("Server Page 2")).toBeInTheDocument();
    expect(secondPageAttempts).toBe(2);
  });

  it("surfaces an expired cursor before offering an explicit first-page recovery", async () => {
    setFetchImplementation(
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const path = String(input);
        if (path === "/api/bootstrap") return jsonResponse(success(bootstrap));
        if (path === "/api/eacl/count-resources") {
          return jsonResponse(success({ count: 48, limit: 50_000, truncated: false }));
        }
        if (path === "/api/eacl/lookup-resources") {
          const body = JSON.parse(String(init?.body)) as Record<string, unknown>;
          if (body.after) {
            return failure("invalid-cursor", "The database basis changed", 409);
          }
          return jsonResponse(success({
            items: [{ type: "server", id: "server-page-1" }],
            pageInfo: {
              endCursor: "old-cursor",
              hasNextPage: true,
              hasPreviousPage: false,
            },
          }));
        }
        throw new Error(`Unexpected request: ${path}`);
      }) as typeof fetch,
    );
    render(() => (
      <AppStateProvider>
        <ResourceTreePanel />
      </AppStateProvider>
    ));

    const groupButton = await screen.findByRole("button", { name: /Servers/ });
    const group = groupButton.closest(".group-card") as HTMLElement;
    fireEvent.click(groupButton);
    await within(group).findByText("Server Page 1");
    fireEvent.click(within(group).getByRole("button", { name: "Next" }));

    expect(await within(group).findByText("The database basis changed"))
      .toBeInTheDocument();
    expect(within(group).getByRole("button", { name: "First page" }))
      .toBeInTheDocument();
    expect(within(group).getByText("Server Page 1")).toBeInTheDocument();
    expect(within(group).getByText("Page 1")).toBeInTheDocument();

    fireEvent.click(within(group).getByRole("button", { name: "First page" }));
    expect(await within(group).findByText("Server Page 1")).toBeInTheDocument();
  });
});
