import "@testing-library/jest-dom/vitest";
import { cleanup } from "@solidjs/testing-library";
import { afterEach, beforeEach, vi } from "vitest";
import { setFetchImplementation } from "../src/api";

class TestResizeObserver implements ResizeObserver {
  observe = vi.fn();
  unobserve = vi.fn();
  disconnect = vi.fn();
}

vi.stubGlobal("ResizeObserver", TestResizeObserver);

beforeEach(() => {
  localStorage.clear();
});

afterEach(() => {
  cleanup();
  setFetchImplementation();
  vi.clearAllMocks();
});
