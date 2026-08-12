import { render, screen, waitFor } from "@solidjs/testing-library";
import { setFetchImplementation } from "../src/api";
import { Header } from "../src/components/Header";
import { AppStateProvider } from "../src/state";
import { bootstrap, jsonResponse, success } from "./fixtures";

describe("production capabilities", () => {
  it("hides the seed mutation controls in the public UI", async () => {
    setFetchImplementation(
      vi.fn(async (input: RequestInfo | URL) => {
        if (String(input) === "/api/bootstrap") {
          return jsonResponse(success({
            ...bootstrap,
            capabilities: {...bootstrap.capabilities, seedWrite: false},
          }));
        }
        throw new Error(`Unexpected request: ${String(input)}`);
      }) as typeof fetch,
    );
    render(() => (
      <AppStateProvider>
        <Header />
      </AppStateProvider>
    ));

    await waitFor(() => expect(screen.getByText("48 servers")).toBeInTheDocument());
    expect(screen.queryByRole("spinbutton", { name: "Servers to seed" }))
      .not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Seed DB" }))
      .not.toBeInTheDocument();
  });
});
