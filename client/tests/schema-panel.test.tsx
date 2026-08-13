import { fireEvent, render, screen, waitFor } from "@solidjs/testing-library";
import { setFetchImplementation } from "../src/api";
import { SchemaPanel } from "../src/components/SchemaPanel";
import { AppStateProvider } from "../src/state";
import { bootstrap, failure, jsonResponse, schema, schemaSource, success } from "./fixtures";

describe("schema editor", () => {
  it("retains an invalid draft and adopts a successful committed write", async () => {
    let invalid = true;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input);
      if (path === "/api/bootstrap") return jsonResponse(success(bootstrap));
      if (path === "/api/schema" && init?.method === "PUT") {
        if (invalid) return failure("invalid-schema", "Expected closing brace", 422);
        const source = JSON.parse(String(init.body)).source as string;
        return jsonResponse(success({ ...schema, source }, "h101.c0"));
      }
      if (path === "/api/schema") return jsonResponse(success(schema));
      throw new Error(`Unexpected request: ${path}`);
    });
    setFetchImplementation(fetchMock as typeof fetch);
    render(() => (
      <AppStateProvider>
        <SchemaPanel />
      </AppStateProvider>
    ));

    fireEvent.click(await screen.findByRole("button", { name: /Schema \(/ }));
    const editor = await screen.findByRole("textbox", { name: "Spice Schema" });
    await waitFor(() => expect(editor).toHaveValue(schemaSource));
    fireEvent.input(editor, { target: { value: "definition broken {" } });
    expect(screen.getByText("Unsaved changes")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Write Schema" }));
    await screen.findByText("Expected closing brace");
    expect(editor).toHaveValue("definition broken {");

    invalid = false;
    fireEvent.input(editor, { target: { value: `${schemaSource}\n// committed` } });
    fireEvent.click(screen.getByRole("button", { name: "Write Schema" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Write Schema" })).toBeDisabled());
    expect(editor).toHaveValue(`${schemaSource}\n// committed`);
  });

  it("is read-only when production bootstrap denies schema writes", async () => {
    setFetchImplementation(
      vi.fn(async (input: RequestInfo | URL) => {
        const path = String(input);
        if (path === "/api/bootstrap") {
          return jsonResponse(success({
            ...bootstrap,
            capabilities: {...bootstrap.capabilities, schemaWrite: false},
          }));
        }
        if (path === "/api/schema") return jsonResponse(success(schema));
        throw new Error(`Unexpected request: ${path}`);
      }) as typeof fetch,
    );
    render(() => (
      <AppStateProvider>
        <SchemaPanel />
      </AppStateProvider>
    ));

    fireEvent.click(await screen.findByRole("button", { name: /Schema \(/ }));
    const editor = await screen.findByRole("textbox", { name: "Spice Schema" });
    await waitFor(() => expect(editor).toHaveValue(schemaSource));
    expect(editor).toHaveAttribute("readonly");
    expect(screen.getByText("Read-only public demo")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Write Schema" }))
      .not.toBeInTheDocument();
    for (const tab of screen.getAllByRole("tab")) expect(tab).toBeDisabled();
  });
});
