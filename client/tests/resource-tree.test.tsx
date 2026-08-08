import { fireEvent, render, screen, waitFor, within } from "@solidjs/testing-library";
import { setFetchImplementation } from "../src/api";
import { CachePanel } from "../src/components/CachePanel";
import { Header } from "../src/components/Header";
import { DetailPanel } from "../src/components/DetailPanel";
import { ResourceTreePanel } from "../src/components/ResourceTree";
import { AppStateProvider } from "../src/state";
import { bootstrap, jsonResponse, success } from "./fixtures";

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
            revision: "d100.c0",
            requestId: "page-request",
            elapsedMs: body.after ? 4.7 : 3.2,
            cacheStatus: "hit" as const,
          },
        });
      }
      if (path === "/api/eacl/count-resources") {
        requestBodies.push({ path, body: JSON.parse(String(init?.body)) });
        return jsonResponse({
          ...success({ count: 48 }),
          meta: {
            revision: "d100.c0",
            requestId: "count-request",
            elapsedMs: 60.9,
            cacheStatus: "miss" as const,
          },
        });
      }
      if (path === "/api/cache/evict") {
        return jsonResponse(success({ status: "evicted" }, "d100.c1"));
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
    expect(within(group).getByText("48")).toBeInTheDocument();

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

    fireEvent.click(screen.getByRole("button", { name: /Server Page 1.*server-page-1/i }));
    expect(await screen.findByRole("button", { name: /User 1/ })).toBeInTheDocument();
    const countStats = group.querySelector(".group-card__count-stats");
    expect(countStats).toHaveTextContent("48(60.9msmiss)");
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
    expect(group.querySelector(".group-card__count-stats")).toBe(countStats);
    expect(countStats).toHaveTextContent("48(60.9msmiss)");
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
});
