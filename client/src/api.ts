import type { ApiFailure, ApiSuccess } from "./types";

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly details?: unknown;

  constructor(status: number, failure: ApiFailure) {
    super(failure.error.message);
    this.name = "ApiError";
    this.status = status;
    this.code = failure.error.code;
    this.details = failure.error.details;
  }
}
export type FetchImplementation = typeof fetch;
let fetchImplementation: FetchImplementation = (...args) => fetch(...args);

export function setFetchImplementation(implementation?: FetchImplementation): void {
  fetchImplementation = implementation ?? ((...args) => fetch(...args));
}

function isSuccess<T>(value: unknown): value is ApiSuccess<T> {
  return Boolean(
    value &&
      typeof value === "object" &&
      "data" in value &&
      "meta" in value &&
      typeof (value as ApiSuccess<T>).meta?.revision === "string",
  );
}

function isFailure(value: unknown): value is ApiFailure {
  return Boolean(
    value &&
      typeof value === "object" &&
      "error" in value &&
      typeof (value as ApiFailure).error?.code === "string" &&
      typeof (value as ApiFailure).error?.message === "string",
  );
}

export async function apiRequest<T>(
  path: string,
  options: RequestInit = {},
): Promise<ApiSuccess<T>> {
  const response = await fetchImplementation(path, {
    ...options,
    headers: {
      accept: "application/json",
      ...(options.body ? { "content-type": "application/json" } : {}),
      ...options.headers,
    },
  });

  let payload: unknown;
  try {
    payload = await response.json();
  } catch {
    throw new ApiError(response.status || 500, {
      error: {
        code: "invalid-json-response",
        message: "The server returned an invalid JSON response.",
      },
    });
  }

  if (!response.ok) {
    if (isFailure(payload)) throw new ApiError(response.status, payload);
    throw new ApiError(response.status, {
      error: {
        code: "unexpected-api-response",
        message: "The server returned an unexpected error response.",
      },
    });
  }

  if (!isSuccess<T>(payload)) {
    throw new ApiError(500, {
      error: {
        code: "invalid-api-envelope",
        message: "The server returned an invalid success envelope.",
      },
    });
  }

  return payload;
}

export class LatestRequest {
  private controller?: AbortController;
  private sequence = 0;

  async run<T>(path: string, options: RequestInit = {}): Promise<ApiSuccess<T>> {
    this.controller?.abort();
    const controller = new AbortController();
    const sequence = ++this.sequence;
    this.controller = controller;

    try {
      const result = await apiRequest<T>(path, {
        ...options,
        signal: controller.signal,
      });
      if (sequence !== this.sequence) {
        throw new DOMException("Superseded request", "AbortError");
      }
      return result;
    } finally {
      if (sequence === this.sequence) this.controller = undefined;
    }
  }

  abort(): void {
    this.controller?.abort();
    this.controller = undefined;
    this.sequence += 1;
  }
}

export function jsonBody(value: unknown): Pick<RequestInit, "method" | "body"> {
  return { method: "POST", body: JSON.stringify(value) };
}
