import { Show, type JSX } from "solid-js";
import { CachePanel } from "./components/CachePanel";
import { DetailPanel } from "./components/DetailPanel";
import { EmptyState, ErrorBlock, LoadingBlock } from "./components/Common";
import { Header } from "./components/Header";
import { ResourceTreePanel } from "./components/ResourceTree";
import { SchemaPanel } from "./components/SchemaPanel";
import { SubjectsPanel } from "./components/SubjectsPanel";
import { useAppState } from "./state";

function SeedProgress(): JSX.Element {
  const app = useAppState();
  const progress = () => app.seedProgress();
  const percent = () => {
    const target = Math.max(1, progress()?.serversTarget ?? 1);
    return Math.min(100, ((progress()?.serversCompleted ?? 0) / target) * 100);
  };
  return (
    <section class="seed-progress-banner" aria-live="polite">
      <div class="seed-progress-banner__copy">
        <strong>Seeding Datomic Pro</strong>
        <span>
          {progress()?.serversCompleted ?? 0} / {progress()?.serversTarget ?? 0} servers
        </span>
        <span class="seed-progress-card__label">
          {progress()?.label ?? "Applying managed EACL relationships"}
        </span>
      </div>
      <div
        class="seed-progress-card__track"
        role="progressbar"
        aria-valuemin="0"
        aria-valuemax="100"
        aria-valuenow={Math.round(percent())}
      >
        <div class="seed-progress-card__fill" style={{ width: `${percent()}%` }} />
      </div>
    </section>
  );
}

export function App(): JSX.Element {
  const app = useAppState();
  return (
    <div class="app-shell" data-theme={app.theme()}>
      <Header />
      <Show when={app.bootstrap.loading && !app.bootstrap.error}>
        <main class="loading-grid">
          <LoadingBlock label="explorer" />
        </main>
      </Show>
      <Show when={app.bootstrap.error}>
        <main class="loading-grid">
          <ErrorBlock error={app.bootstrap.error} retry={app.refetchBootstrap} />
        </main>
      </Show>
      <Show when={!app.bootstrap.error && app.bootstrap()}>
        <Show when={app.seeding()}>
          <SeedProgress />
        </Show>
        <SchemaPanel />
        <CachePanel />
        <main class="panel-grid">
          <section class="panel-host">
            <SubjectsPanel />
          </section>
          <section class="panel-host">
            <ResourceTreePanel />
          </section>
          <section class="panel-host">
            <DetailPanel />
          </section>
        </main>
      </Show>
      <footer class="app-footer">
        <p class="app-footer__copy">
          EACL authorization runs on Datomic Pro; SolidJS receives only bounded HTTP
          results.
        </p>
        <Show when={!app.permission()}>
          <EmptyState>No permission is available in the active schema.</EmptyState>
        </Show>
      </footer>
    </div>
  );
}
