import { Show, type Accessor, type JSX } from "solid-js";
import type { ApiMeta, CacheStatus } from "../types";

export function identifierLabel(value: string | undefined): string {
  if (!value) return "None";
  return value
    .replaceAll("_", " ")
    .replaceAll("-", " ")
    .replace(/\b\w/g, (letter) => letter.toUpperCase());
}

export function TypeBadge(props: { type: string }): JSX.Element {
  return (
    <span class={`type-badge type-${props.type}`} aria-label={`${props.type} type`} />
  );
}

export function CacheTiming(props: {
  status?: CacheStatus;
  elapsedMs?: number;
}): JSX.Element {
  return (
    <Show when={props.status || props.elapsedMs !== undefined}>
      <span class="cache-timing">
        <span class={`cache-badge cache-badge--${props.status ?? "miss"}`}>
          {props.status ?? "miss"}
        </span>
        <Show when={props.elapsedMs !== undefined}>
          <span class="cache-timing__duration"> {props.elapsedMs?.toFixed(1)} ms</span>
        </Show>
      </span>
    </Show>
  );
}

export function MetaTiming(props: { meta?: ApiMeta }): JSX.Element {
  return <CacheTiming status={props.meta?.cacheStatus} elapsedMs={props.meta?.elapsedMs} />;
}

export function DisclosureButton(props: {
  expanded: boolean;
  controls: string;
  onClick: () => void;
  children: JSX.Element;
}): JSX.Element {
  return (
    <button
      type="button"
      class="group-card__toggle"
      aria-expanded={props.expanded}
      aria-controls={props.controls}
      onClick={() => props.onClick()}
    >
      <span class="group-card__caret" aria-hidden="true">
        {props.expanded ? "▾" : "▸"}
      </span>
      {props.children}
    </button>
  );
}

export function Pagination(props: {
  page: number;
  canPrevious: boolean;
  canNext: boolean;
  busy?: boolean;
  busyAction?: "first" | "previous" | "next";
  first: () => void;
  previous: () => void;
  next: () => void;
}): JSX.Element {
  const busy = () => props.busy || Boolean(props.busyAction);
  return (
    <div class="pagination-controls" aria-label="Pagination" aria-busy={busy()}>
      <button
        type="button"
        class="pagination-button"
        disabled={busy() || !props.canPrevious}
        aria-busy={props.busyAction === "first"}
        onClick={() => props.first()}
      >
        <Show when={props.busyAction === "first"}>
          <ButtonSpinner />
        </Show>
        First
      </button>
      <button
        type="button"
        class="pagination-button"
        disabled={busy() || !props.canPrevious}
        aria-busy={props.busyAction === "previous"}
        onClick={() => props.previous()}
      >
        <Show when={props.busyAction === "previous"}>
          <ButtonSpinner />
        </Show>
        Previous
      </button>
      <span class="pagination-page">Page {props.page}</span>
      <button
        type="button"
        class="pagination-button"
        disabled={busy() || !props.canNext}
        aria-busy={props.busyAction === "next"}
        onClick={() => props.next()}
      >
        <Show when={props.busyAction === "next"}>
          <ButtonSpinner />
        </Show>
        Next
      </button>
    </div>
  );
}

export function ButtonSpinner(): JSX.Element {
  return <span class="button-spinner" aria-hidden="true" />;
}

export function ErrorBlock(props: {
  error: unknown;
  label?: string;
  retry?: () => void;
  secondary?: { label: string; action: () => void };
}): JSX.Element {
  const message = () =>
    props.error instanceof Error ? props.error.message : String(props.error);
  return (
    <div class="error-block" role="alert">
      <div class="error-block__copy">
        <strong>{props.label ?? "Request failed"}</strong>
        <span>{message()}</span>
      </div>
      <div class="error-block__actions">
        <Show when={props.secondary}>
          {(secondary) => (
            <button
              type="button"
              class="retry-button"
              onClick={() => secondary().action()}
            >
              {secondary().label}
            </button>
          )}
        </Show>
        <Show when={props.retry}>
          <button type="button" class="retry-button" onClick={() => props.retry?.()}>
            Retry
          </button>
        </Show>
      </div>
    </div>
  );
}

export function InlineLoading(props: { label: string }): JSX.Element {
  return (
    <span class="inline-loading" role="status" aria-live="polite">
      <span class="loading-dot" aria-hidden="true" />
      {props.label}
    </span>
  );
}

export function InlineError(props: { label: string }): JSX.Element {
  return (
    <span class="inline-error" role="alert">
      {props.label}
    </span>
  );
}

export function LoadingBlock(props: {
  label: string;
  refreshing?: Accessor<boolean>;
}): JSX.Element {
  return (
    <div class="loading-block" role="status" aria-live="polite">
      <span class="loading-dot" aria-hidden="true" />
      {props.refreshing?.() ? `Refreshing ${props.label}…` : `Loading ${props.label}…`}
    </div>
  );
}

export function EmptyState(props: { children: JSX.Element }): JSX.Element {
  return <div class="empty-state">{props.children}</div>;
}
