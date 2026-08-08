import { fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import { setFetchImplementation } from "../src/api";
import { CachePanel } from "../src/components/CachePanel";
import { AppStateProvider } from "../src/state";
import { bootstrap, cacheSnapshot, jsonResponse, success } from "./fixtures";

describe("cache snapshot contract", () => {
  it("fetches and replaces the display only through Refresh cache", async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const path = String(input);
      if (path === "/api/bootstrap") return jsonResponse(success(bootstrap));
      if (path === "/api/cache/evict") {
        return jsonResponse(success({ status: "evicted" }, "d100.c1"));
      }
      if (path === "/api/cache") return jsonResponse(success(cacheSnapshot));
      throw new Error(`Unexpected request: ${path}`);
    });
    setFetchImplementation(fetchMock as typeof fetch);
    render(() => (
      <AppStateProvider>
        <CachePanel />
      </AppStateProvider>
    ));

    fireEvent.click(screen.getByRole("button", { name: /^cache$/i }));
    expect(screen.getByText(/not been captured/i)).toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([path]) => path === "/api/cache")).toBe(false);

    fireEvent.click(screen.getByRole("switch", { name: /cache enabled/i }));
    fireEvent.click(screen.getByRole("button", { name: "Evict Cache" }));
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith(
      "/api/cache/evict",
      expect.anything(),
    ));
    expect(fetchMock.mock.calls.some(([path]) => path === "/api/cache")).toBe(false);

    fireEvent.click(screen.getByRole("button", { name: "Refresh cache" }));
    await screen.findByText(/Captured/);
    expect(screen.getByText(/"entries": 2/)).toBeInTheDocument();
    expect(fetchMock.mock.calls.filter(([path]) => path === "/api/cache")).toHaveLength(1);
  });

  it("retains the prior pretty-printed snapshot after a refresh failure", async () => {
    let refreshes = 0;
    setFetchImplementation(
      vi.fn(async (input: RequestInfo | URL) => {
        const path = String(input);
        if (path === "/api/bootstrap") return jsonResponse(success(bootstrap));
        if (path === "/api/cache" && ++refreshes === 1) {
          return jsonResponse(success(cacheSnapshot));
        }
        if (path === "/api/cache") {
          return jsonResponse({ error: { code: "offline", message: "Cache unavailable" } }, 503);
        }
        throw new Error(`Unexpected request: ${path}`);
      }) as typeof fetch,
    );
    render(() => (
      <AppStateProvider>
        <CachePanel />
      </AppStateProvider>
    ));
    fireEvent.click(screen.getByRole("button", { name: /^cache$/i }));
    fireEvent.click(screen.getByRole("button", { name: "Refresh cache" }));
    await screen.findByText(/"entries": 2/);
    fireEvent.click(screen.getByRole("button", { name: "Refresh cache" }));
    await screen.findByText("Cache unavailable");
    expect(screen.getByText(/"entries": 2/)).toBeInTheDocument();
  });
});
