import { fireEvent, render, screen } from "@solidjs/testing-library";
import { setFetchImplementation } from "../src/api";
import { SubjectsPanel } from "../src/components/SubjectsPanel";
import { AppStateProvider } from "../src/state";
import { bootstrap, jsonResponse, success } from "./fixtures";

describe("subjects and permissions", () => {
  it("reactively switches quick subjects and schema-derived permissions", async () => {
    const subjectRequests: string[] = [];
    let releaseSecondPage: () => void = () => undefined;
    const secondPageGate = new Promise<void>((resolve) => {
      releaseSecondPage = resolve;
    });
    const model = {
      ...bootstrap,
      schema: {
        ...bootstrap.schema,
        permissionsByType: { account: ["admin", "view"], server: ["view"] },
      },
    };
    setFetchImplementation(
      vi.fn(async (input: RequestInfo | URL) => {
        const path = String(input);
        if (path === "/api/bootstrap") return jsonResponse(success(model));
        if (path.startsWith("/api/subjects?")) {
          subjectRequests.push(path);
          const offset = Number(new URL(path, "http://example.test").searchParams.get("offset"));
          if (offset) await secondPageGate;
          return jsonResponse(success({
            data: [{ type: "user", id: offset ? "user-2" : "user-1" }],
            pageInfo: {
              hasNextPage: offset === 0,
              hasPreviousPage: offset > 0,
              nextOffset: offset === 0 ? 20 : undefined,
              total: 21,
            },
          }));
        }
        throw new Error(`Unexpected request: ${path}`);
      }) as typeof fetch,
    );
    render(() => (
      <AppStateProvider>
        <SubjectsPanel />
      </AppStateProvider>
    ));

    const admin = await screen.findByRole("button", { name: ":admin" });
    expect(screen.getByRole("button", { name: "Super user" }).querySelector(".type-badge"))
      .toBeNull();
    fireEvent.click(admin);
    expect(admin).toHaveAttribute("aria-pressed", "true");
    await screen.findByText("21 total");
    expect(screen.getByText("User 1", { selector: ".resource-caption__name" }).parentElement)
      .toHaveTextContent("User 1 user-1");
    fireEvent.click(screen.getByRole("button", { name: "Next" }));
    expect(screen.getByText("user-1", { selector: ".resource-caption__id" }))
      .toBeInTheDocument();
    expect(screen.getByText("Page 1")).toBeInTheDocument();
    const pendingNext = screen.getByRole("button", { name: "Next" });
    expect(pendingNext).toBeDisabled();
    expect(pendingNext).toHaveAttribute("aria-busy", "true");
    expect(pendingNext.querySelector(".button-spinner")).toBeInTheDocument();
    releaseSecondPage();
    await screen.findByText("Page 2");
    const requestsBeforeSelection = subjectRequests.length;
    fireEvent.click(screen.getAllByRole("button", { name: /User 1/ })[0]);
    expect(screen.getByText("Active subject").parentElement).toHaveTextContent("user-1");
    expect(screen.getByText("Page 2")).toBeInTheDocument();
    expect(subjectRequests).toHaveLength(requestsBeforeSelection);
  });
});
