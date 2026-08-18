import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@solidjs/testing-library";
import type { JSX } from "solid-js";
import { setFetchImplementation } from "../src/api";
import { DetailPanel } from "../src/components/DetailPanel";
import { AppStateProvider, useAppState } from "../src/state";
import type { Bootstrap, CacheStatus } from "../src/types";
import {
  bootstrap,
  failure,
  jsonResponse,
  success,
  timedSuccess,
} from "./fixtures";

const detailBootstrap: Bootstrap = {
  ...bootstrap,
  schema: {
    ...bootstrap.schema,
    permissionsByType: {
      account: ["view", "admin"],
      server: ["view"],
    },
    nodes: [
      { id: "account", permissions: ["view", "admin"] },
      { id: "server", permissions: ["view"] },
    ],
    permissionCount: 3,
  },
};

function Harness(): JSX.Element {
  const app = useAppState();
  return (
    <>
      <button
        type="button"
        onClick={() => app.setSelectedResource({ type: "account", id: "account-0" })}
      >
        Select account
      </button>
      <button
        type="button"
        onClick={() => app.setSelectedResource({ type: "server", id: "server-0" })}
      >
        Select server
      </button>
      <button type="button" onClick={() => app.setSubjectId("super-user")}>
        Change subject
      </button>
      <button type="button" onClick={() => app.setSubjectId("user-2")}>
        Change subject again
      </button>
      <button type="button" onClick={() => app.setPermission("admin")}>
        Change active permission
      </button>
      <button type="button" onClick={() => app.setPageSize(50)}>
        Change page size
      </button>
      <button type="button" onClick={() => app.setCacheEnabled(!app.cacheEnabled())}>
        Change cache
      </button>
      <button type="button" onClick={() => app.applyMutationRevision("h101.c0")}>
        Change revision
      </button>
      <button type="button" onClick={app.refetchBootstrap}>
        Refresh schema
      </button>
      <DetailPanel />
    </>
  );
}

function renderHarness(): void {
  render(() => (
    <AppStateProvider>
      <Harness />
    </AppStateProvider>
  ));
}

function requestBody(init?: RequestInit): Record<string, unknown> {
  return JSON.parse(String(init?.body)) as Record<string, unknown>;
}

function holderResponse(permission: string, cache: boolean): Response {
  return jsonResponse(timedSuccess({
    items: [{ type: "user", id: `${permission}-holder` }],
    pageInfo: {
      endCursor: "holder-next",
      hasNextPage: true,
      hasPreviousPage: false,
    },
  }, 8.5, cache ? "miss" : "disabled"));
}

describe("selected-resource permission decisions", () => {
  it("posts every schema permission and renders decisions before holder lookups", async () => {
    const requests: Array<{ path: string; body: Record<string, unknown> }> = [];
    setFetchImplementation(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path === "/api/bootstrap") return jsonResponse(success(detailBootstrap));
      if (path === "/api/eacl/check-permission") {
        const body = requestBody(init);
        requests.push({ path, body });
        const permission = String(body.permission);
        const cache = Boolean(body.cache);
        const status: CacheStatus = cache
          ? (permission === "view" ? "hit" : "miss")
          : "disabled";
        return jsonResponse(timedSuccess(
          { allowed: permission === "view" },
          permission === "view" ? 0.4 : 5.2,
          status,
        ));
      }
      if (path === "/api/eacl/lookup-subjects") {
        const body = requestBody(init);
        requests.push({ path, body });
        return holderResponse(String(body.permission), Boolean(body.cache));
      }
      throw new Error(`Unexpected request: ${path}`);
    }) as typeof fetch);
    renderHarness();

    expect(screen.getByText("Click a resource to inspect it.")).toBeInTheDocument();
    expect(requests).toHaveLength(0);
    fireEvent.click(screen.getByRole("button", { name: "Select account" }));

    const decisions = await screen.findByRole("region", {
      name: "Can active subject?",
    });
    expect(await within(decisions).findByText("Allowed")).toBeInTheDocument();
    expect(await within(decisions).findByText("Denied")).toBeInTheDocument();
    expect(within(decisions).getByText("0.4ms")).toBeInTheDocument();
    expect(within(decisions).getByText("hit", { exact: false })).toBeInTheDocument();
    expect(within(decisions).getByText("5.2ms")).toBeInTheDocument();
    expect(within(decisions).getByText("miss", { exact: false })).toBeInTheDocument();
    const firstHolders = document.querySelector(".permission-subjects");
    expect(
      decisions.compareDocumentPosition(firstHolders as Node) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();

    const checkBodies = requests
      .filter(({ path }) => path.endsWith("check-permission"))
      .map(({ body }) => body);
    expect(checkBodies).toEqual([
      {
        subject: { type: "user", id: "user-1" },
        resource: { type: "account", id: "account-0" },
        permission: "view",
        cache: true,
      },
      {
        subject: { type: "user", id: "user-1" },
        resource: { type: "account", id: "account-0" },
        permission: "admin",
        cache: true,
      },
    ]);

    fireEvent.click(screen.getByRole("button", { name: "Change cache" }));
    await waitFor(() => {
      expect(requests.filter(({ path }) => path.endsWith("check-permission")))
        .toHaveLength(4);
    });
    await waitFor(() => {
      expect(within(decisions).getAllByText("disabled", { exact: false }))
        .toHaveLength(2);
    });
  });

  it("uses semantic keys and reflects schema permission changes", async () => {
    let bootstrapReads = 0;
    const checks: Array<Record<string, unknown>> = [];
    setFetchImplementation(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path === "/api/bootstrap") {
        bootstrapReads += 1;
        return jsonResponse(success(bootstrapReads === 1
          ? {
              ...detailBootstrap,
              schema: {
                ...detailBootstrap.schema,
                permissionsByType: { ...detailBootstrap.schema.permissionsByType, account: ["view"] },
              },
            }
          : detailBootstrap));
      }
      if (path === "/api/eacl/check-permission") {
        const body = requestBody(init);
        checks.push(body);
        return jsonResponse(timedSuccess({ allowed: true }, 1.1, "hit"));
      }
      if (path === "/api/eacl/lookup-subjects") {
        const body = requestBody(init);
        return holderResponse(String(body.permission), Boolean(body.cache));
      }
      throw new Error(`Unexpected request: ${path}`);
    }) as typeof fetch);
    renderHarness();
    fireEvent.click(screen.getByRole("button", { name: "Select account" }));
    await waitFor(() => expect(checks).toHaveLength(1));

    fireEvent.click(screen.getByRole("button", { name: "Change page size" }));
    fireEvent.click(screen.getByRole("button", { name: "Change active permission" }));
    const viewHolders = document.querySelector(
      '.permission-subjects[data-permission="view"]',
    ) as HTMLElement;
    fireEvent.click(await within(viewHolders).findByRole("button", { name: "Next" }));
    await waitFor(() => expect(within(viewHolders).getByText("Page 2"))
      .toBeInTheDocument());
    expect(checks).toHaveLength(1);

    fireEvent.click(screen.getByRole("button", { name: "Change cache" }));
    await waitFor(() => expect(checks).toHaveLength(2));
    fireEvent.click(screen.getByRole("button", { name: "Change revision" }));
    await waitFor(() => expect(checks).toHaveLength(3));
    fireEvent.click(screen.getByRole("button", { name: "Change subject" }));
    await waitFor(() => expect(checks).toHaveLength(4));
    expect(checks.at(-1)).toMatchObject({
      subject: { type: "user", id: "super-user" },
      resource: { type: "account", id: "account-0" },
    });

    fireEvent.click(screen.getByRole("button", { name: "Refresh schema" }));
    await waitFor(() => expect(checks).toHaveLength(5));
    expect(checks.at(-1)).toMatchObject({ permission: "admin" });
    expect(document.querySelectorAll(".permission-decision")).toHaveLength(2);

    fireEvent.click(screen.getByRole("button", { name: "Select server" }));
    await waitFor(() => expect(checks).toHaveLength(6));
    expect(checks.at(-1)).toMatchObject({
      resource: { type: "server", id: "server-0" },
      permission: "view",
    });
  });

  it("retains the prior result and suppresses a late superseded response", async () => {
    let releaseSuper: (response: Response) => void = () => undefined;
    let releaseUser2: (response: Response) => void = () => undefined;
    const superGate = new Promise<Response>((resolve) => { releaseSuper = resolve; });
    const user2Gate = new Promise<Response>((resolve) => { releaseUser2 = resolve; });
    setFetchImplementation(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path === "/api/bootstrap") {
        return jsonResponse(success({
          ...detailBootstrap,
          schema: {
            ...detailBootstrap.schema,
            permissionsByType: { ...detailBootstrap.schema.permissionsByType, account: ["view"] },
          },
        }));
      }
      if (path === "/api/eacl/check-permission") {
        const body = requestBody(init) as { subject: { id: string } };
        if (body.subject.id === "super-user") return superGate;
        if (body.subject.id === "user-2") return user2Gate;
        return jsonResponse(timedSuccess({ allowed: true }, 0.7, "hit"));
      }
      if (path === "/api/eacl/lookup-subjects") {
        const body = requestBody(init);
        return holderResponse(String(body.permission), Boolean(body.cache));
      }
      throw new Error(`Unexpected request: ${path}`);
    }) as typeof fetch);
    renderHarness();
    fireEvent.click(screen.getByRole("button", { name: "Select account" }));
    const row = await screen.findByText("Allowed");
    const decisionRow = row.closest(".permission-decision") as HTMLElement;

    fireEvent.click(screen.getByRole("button", { name: "Change subject" }));
    await waitFor(() => expect(decisionRow).toHaveAttribute("aria-busy", "true"));
    expect(within(decisionRow).getByText("Allowed")).toBeInTheDocument();
    expect(within(decisionRow).getByLabelText("Refreshing view permission decision"))
      .toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Change subject again" }));
    releaseSuper(jsonResponse(timedSuccess({ allowed: false }, 99, "miss")));
    await Promise.resolve();
    expect(within(decisionRow).getByText("Allowed")).toBeInTheDocument();
    expect(within(decisionRow).queryByText("99.0ms")).not.toBeInTheDocument();

    releaseUser2(jsonResponse(timedSuccess({ allowed: false }, 1.3, "miss")));
    await waitFor(() => expect(within(decisionRow).getByText("Denied"))
      .toBeInTheDocument());
    expect(within(decisionRow).getByText("1.3ms")).toBeInTheDocument();
  });

  it("isolates a failed row and retries it without replacing other results", async () => {
    let viewAttempts = 0;
    setFetchImplementation(vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path === "/api/bootstrap") return jsonResponse(success(detailBootstrap));
      if (path === "/api/eacl/check-permission") {
        const body = requestBody(init);
        if (body.permission === "view" && ++viewAttempts === 1) {
          return failure("backend-timeout", "View decision timed out", 504);
        }
        return jsonResponse(timedSuccess(
          { allowed: body.permission === "view" },
          body.permission === "view" ? 1.2 : 2.4,
          "miss",
        ));
      }
      if (path === "/api/eacl/lookup-subjects") {
        const body = requestBody(init);
        return holderResponse(String(body.permission), Boolean(body.cache));
      }
      throw new Error(`Unexpected request: ${path}`);
    }) as typeof fetch);
    renderHarness();
    fireEvent.click(screen.getByRole("button", { name: "Select account" }));

    const error = await screen.findByText("View decision timed out");
    const viewRow = error.closest(".permission-decision") as HTMLElement;
    const adminRow = document.querySelector(
      '.permission-decision[data-permission="admin"]',
    ) as HTMLElement;
    expect(within(adminRow).getByText("Denied")).toBeInTheDocument();
    expect(screen.getByText("admin-holder")).toBeInTheDocument();
    fireEvent.click(within(viewRow).getByRole("button", { name: "Retry" }));
    await waitFor(() => expect(within(viewRow).getByText("Allowed"))
      .toBeInTheDocument());
    expect(within(adminRow).getByText("Denied")).toBeInTheDocument();
    expect(viewAttempts).toBe(2);
  });
});
