import {
  ApiError,
  LatestRequest,
  apiPath,
  apiRequest,
  setFetchImplementation,
  setRequestTimeoutMs,
} from "../src/api";
import { jsonResponse, success } from "./fixtures";

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

describe("HTTP adapter", () => {
  it("mounts API calls under the production base path", () => {
    expect(apiPath("/api/bootstrap", "/datahike/")).toBe("/datahike/api/bootstrap");
    expect(apiPath("/api/bootstrap", "/")).toBe("/api/bootstrap");
  });

  it("adds JSON headers and accepts only the common success envelope", async () => {
    const fetchMock = vi.fn(async () => jsonResponse(success({ ok: true })));
    setFetchImplementation(fetchMock as typeof fetch);

    await expect(apiRequest<{ ok: boolean }>("/api/example")).resolves.toMatchObject({
      data: { ok: true },
    });
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/example",
      expect.objectContaining({ headers: expect.objectContaining({ accept: "application/json" }) }),
    );
  });

  it("returns stable typed failures for malformed envelopes", async () => {
    setFetchImplementation(
      vi.fn(async () => jsonResponse({ something: "else" })) as typeof fetch,
    );
    await expect(apiRequest("/api/example")).rejects.toMatchObject({
      code: "invalid-api-envelope",
    } satisfies Partial<ApiError>);
  });

  it("turns transport failures and client deadlines into stable visible errors", async () => {
    setFetchImplementation(
      vi.fn(async () => {
        throw new TypeError("socket vanished");
      }) as typeof fetch,
    );
    await expect(apiRequest("/api/example")).rejects.toMatchObject({
      code: "network-error",
      status: 0,
    } satisfies Partial<ApiError>);

    setRequestTimeoutMs(5);
    setFetchImplementation(
      vi.fn((_path, init) =>
        new Promise<Response>((_resolve, reject) => {
          const signal = init?.signal as AbortSignal;
          signal.addEventListener("abort", () => reject(signal.reason), { once: true });
        })) as typeof fetch,
    );
    await expect(apiRequest("/api/example")).rejects.toMatchObject({
      code: "client-timeout",
      status: 408,
      message: "The request did not finish within 1 seconds.",
    } satisfies Partial<ApiError>);
  });

  it("aborts the old transport and suppresses a late response", async () => {
    const first = deferred<Response>();
    const second = deferred<Response>();
    const signals: AbortSignal[] = [];
    let call = 0;
    setFetchImplementation(
      vi.fn((_path, init) => {
        signals.push(init!.signal as AbortSignal);
        return ++call === 1 ? first.promise : second.promise;
      }) as typeof fetch,
    );
    const latest = new LatestRequest();
    const oldRequest = latest.run<{ value: number }>("/api/value?request=old");
    const newRequest = latest.run<{ value: number }>("/api/value?request=new");

    expect(signals[0].aborted).toBe(true);
    second.resolve(jsonResponse(success({ value: 2 })));
    await expect(newRequest).resolves.toMatchObject({ data: { value: 2 } });
    first.resolve(jsonResponse(success({ value: 1 })));
    await expect(oldRequest).rejects.toMatchObject({ name: "AbortError" });
  });
});
