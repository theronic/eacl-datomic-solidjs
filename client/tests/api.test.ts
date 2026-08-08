import { ApiError, LatestRequest, apiRequest, setFetchImplementation } from "../src/api";
import { jsonResponse, success } from "./fixtures";

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

describe("HTTP adapter", () => {
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
