import { createSignal, For, Show, type JSX } from "solid-js";
import { apiRequest } from "../api";
import { useAppState } from "../state";
import { PAGE_SIZE_OPTIONS, type PageSize, type SeedProgress } from "../types";
import { ErrorBlock } from "./Common";

export function Header(): JSX.Element {
  const app = useAppState();
  const [seedSize, setSeedSize] = createSignal("10000");
  const [seedError, setSeedError] = createSignal<unknown>();
  const ready = () => !app.bootstrap.error && Boolean(app.bootstrap());
  const serverTotal = () => (ready() ? (app.bootstrap()?.data.totals.servers ?? 0) : 0);

  const seed = async (event: SubmitEvent) => {
    event.preventDefault();
    const value = Number(seedSize());
    if (!Number.isSafeInteger(value) || value <= 0) {
      setSeedError(new Error("Seed size must be a positive whole number."));
      return;
    }
    setSeedError(undefined);
    app.setSeedProgress({
      status: "seeding",
      serversAdded: 0,
      serversCompleted: 0,
      serversTarget: value,
      totalServers: serverTotal(),
      label: "Preparing Datomic transactions",
    });
    try {
      const result = await apiRequest<SeedProgress>("/api/seed", {
        method: "POST",
        body: JSON.stringify({ serverCount: value }),
      });
      app.setSeedProgress(result.data);
    } catch (error) {
      setSeedError(error);
      app.setSeedProgress({
        status: "error",
        serversAdded: 0,
        serversCompleted: 0,
        serversTarget: value,
        totalServers: serverTotal(),
        error: error instanceof Error ? error.message : String(error),
      });
    }
  };

  return (
    <header class="app-header">
      <div class="app-header__intro">
        <p class="eyebrow">EACL v8 + Datomic Pro + SolidJS</p>
        <h1 class="app-title">🦅 EACL Explorer</h1>
        <p class="app-subtitle">
          Reactive authorization over explicit, inspectable HTTP queries.
        </p>
      </div>
      <div class="app-header__actions">
        <nav class="app-header__sources" aria-label="Source repositories">
          <a class="app-header__link" href="https://github.com/theronic/eacl">
            EACL Source
          </a>
          <a class="app-header__link" href="https://github.com/theronic/eacl-solidjs">
            SolidJS Source
          </a>
        </nav>
        <div class="app-header__controls">
          <div class="stat-pill" aria-live="polite">
            <span class="stat-pill__label">
              {app.bootstrap.error ? "unavailable" : app.seeding() ? "seeding" : "ready"}
            </span>
            <strong>
              <Show
                when={app.seeding()}
                fallback={`${serverTotal()} servers`}
              >
                {app.seedProgress()?.serversCompleted ?? 0} /{" "}
                {app.seedProgress()?.serversTarget ?? 0} servers
              </Show>
            </strong>
          </div>
          <label class="page-size-control">
            <span class="page-size-control__label">Page size</span>
            <select
              class="page-size-control__select"
              aria-label="Page size"
              value={String(app.pageSize())}
              onChange={(event) =>
                app.setPageSize(Number(event.currentTarget.value) as PageSize)
              }
            >
              <For each={PAGE_SIZE_OPTIONS}>
                {(value) => <option value={value}>{value}</option>}
              </For>
            </select>
          </label>
          <form class="seed-controls" aria-busy={app.seeding()} onSubmit={seed}>
            <input
              class="seed-input"
              aria-label="Servers to seed"
              type="number"
              min="1"
              step="1"
              disabled={app.seeding() || !ready()}
              value={seedSize()}
              onInput={(event) => setSeedSize(event.currentTarget.value)}
            />
            <button
              class="seed-submit"
              type="submit"
              disabled={app.seeding() || !ready()}
            >
              {app.seeding() ? "Seeding…" : "Seed DB"}
            </button>
          </form>
          <button
            class="graph-toggle"
            type="button"
            onClick={() => app.setTheme(app.theme() === "dark" ? "light" : "dark")}
          >
            {app.theme() === "dark" ? "Light theme" : "Dark theme"}
          </button>
        </div>
        <Show when={seedError()}>{(error) => <ErrorBlock error={error()} />}</Show>
      </div>
    </header>
  );
}
