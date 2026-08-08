import { createSignal, Show, type JSX } from "solid-js";
import { apiRequest } from "../api";
import { useAppState } from "../state";
import type { CacheSnapshot } from "../types";
import { DisclosureButton, ErrorBlock } from "./Common";

interface CapturedSnapshot {
  capturedAt: string;
  cacheEnabled: boolean;
  snapshot: CacheSnapshot;
}
export function CachePanel(): JSX.Element {
  const app = useAppState();
  const expansionKey = "segment:cache";
  const expanded = () => app.isExpanded(expansionKey);
  const [snapshot, setSnapshot] = createSignal<CapturedSnapshot>();
  const [refreshing, setRefreshing] = createSignal(false);
  const [refreshError, setRefreshError] = createSignal<unknown>();
  const [evicting, setEvicting] = createSignal(false);
  const [evictError, setEvictError] = createSignal<unknown>();

  const refreshCache = async () => {
    setRefreshing(true);
    setRefreshError(undefined);
    try {
      const result = await apiRequest<CacheSnapshot>("/api/cache");
      setSnapshot({
        capturedAt: result.data.capturedAt,
        cacheEnabled: app.cacheEnabled(),
        snapshot: result.data,
      });
    } catch (error) {
      setRefreshError(error);
    } finally {
      setRefreshing(false);
    }
  };

  const evictCache = async () => {
    setEvicting(true);
    setEvictError(undefined);
    try {
      const result = await apiRequest<{ status: string }>("/api/cache/evict", {
        method: "POST",
        body: "{}",
      });
      app.applyMutationRevision(result.meta.revision);
    } catch (error) {
      setEvictError(error);
    } finally {
      setEvicting(false);
    }
  };

  const prettySnapshot = () => JSON.stringify(snapshot(), null, 2);

  return (
    <section class="schema-shell cache-shell">
      <div class={`panel-card cache-panel ${expanded() ? "" : "panel-card--collapsed"}`}>
        <div class="panel-heading schema-shell__header">
          <DisclosureButton
            expanded={expanded()}
            controls="cache-segment-content"
            onClick={() => app.toggleExpanded(expansionKey)}
          >
            <span class="group-card__title">Cache</span>
          </DisclosureButton>
          <div class="cache-controls">
            <label class="cache-toggle">
              <span class="cache-toggle__label">Cache Enabled:</span>
              <span class="cache-switch">
                <input
                  class="cache-switch__input"
                  type="checkbox"
                  role="switch"
                  checked={app.cacheEnabled()}
                  aria-checked={app.cacheEnabled()}
                  onChange={(event) => app.setCacheEnabled(event.currentTarget.checked)}
                />
                <span class="cache-switch__slider" aria-hidden="true" />
              </span>
              <span class="cache-toggle__state">{app.cacheEnabled() ? "On" : "Off"}</span>
            </label>
            <button
              type="button"
              class="pagination-button cache-evict"
              disabled={evicting()}
              onClick={() => void evictCache()}
            >
              {evicting() ? "Evicting…" : "Evict Cache"}
            </button>
            <button
              type="button"
              class="pagination-button cache-refresh"
              disabled={refreshing()}
              onClick={() => void refreshCache()}
            >
              {refreshing() ? "Refreshing…" : "Refresh cache"}
            </button>
          </div>
        </div>
        <Show when={expanded()}>
          <div id="cache-segment-content" class="cache-metrics">
            <Show when={evictError()}>{(error) => <ErrorBlock error={error()} />}</Show>
            <Show when={refreshError()}>{(error) => <ErrorBlock error={error()} />}</Show>
            <Show
              when={snapshot()}
              fallback={
                <p class="empty-state">
                  Cache metrics have not been captured. Click Refresh cache.
                </p>
              }
            >
              <p class="cache-snapshot-meta">
                Captured {new Date(snapshot()!.capturedAt).toLocaleString()} · cache{" "}
                {snapshot()!.cacheEnabled ? "enabled" : "disabled"}
              </p>
              <pre class="cache-metrics__code">
                <code>{prettySnapshot()}</code>
              </pre>
            </Show>
          </div>
        </Show>
      </div>
    </section>
  );
}
