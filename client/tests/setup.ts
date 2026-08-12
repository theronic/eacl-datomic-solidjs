import "@testing-library/jest-dom/vitest";
import { cleanup } from "@solidjs/testing-library";
import { afterEach, beforeEach, vi } from "vitest";
import { setFetchImplementation, setRequestTimeoutMs } from "../src/api";

class TestResizeObserver implements ResizeObserver {
  observe = vi.fn();
  unobserve = vi.fn();
  disconnect = vi.fn();
}

const storedValues = new Map<string, string>();
const testLocalStorage: Storage = {
  get length() {
    return storedValues.size;
  },
  clear: () => storedValues.clear(),
  getItem: (key) => storedValues.get(key) ?? null,
  key: (index) => [...storedValues.keys()][index] ?? null,
  removeItem: (key) => storedValues.delete(key),
  setItem: (key, value) => storedValues.set(key, String(value)),
};

vi.stubGlobal("ResizeObserver", TestResizeObserver);
// Node 25+ exposes an incomplete process-level localStorage unless a backing
// file is configured. Keep the test boundary deterministic across Node lines.
vi.stubGlobal("localStorage", testLocalStorage);

beforeEach(() => {
  localStorage.clear();
});

afterEach(() => {
  cleanup();
  setFetchImplementation();
  setRequestTimeoutMs();
  vi.clearAllMocks();
});
