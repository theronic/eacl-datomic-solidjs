import {
  createEffect,
  createMemo,
  createResource,
  createSignal,
  For,
  on,
  onCleanup,
  Show,
  type JSX,
} from "solid-js";
import { ApiError, LatestRequest } from "../api";
import { useAppState } from "../state";
import type {
  ApiMeta,
  ApiSuccess,
  ChildPath,
  EaclObject,
  ObjectPage,
  RelationshipPage,
  ResourceCount,
} from "../types";
import {
  ButtonSpinner,
  DisclosureButton,
  EmptyState,
  ErrorBlock,
  InlineError,
  InlineLoading,
  LoadingBlock,
  MetaTiming,
  Pagination,
  TypeBadge,
  identifierLabel,
} from "./Common";

const resourceKey = (resource: EaclObject) => `${resource.type}:${resource.id}`;
const initialCountLimit = 50_000;
const countFormatter = new Intl.NumberFormat("en-US");
const compactCountFormatter = new Intl.NumberFormat("en-US", {
  notation: "compact",
  maximumFractionDigits: 1,
});
const formatTruncatedCount = (count: number) =>
  compactCountFormatter.format(count).toLocaleLowerCase("en-US");

function PaginationTiming(props: { meta?: ApiMeta }): JSX.Element {
  return (
    <Show when={props.meta?.elapsedMs !== undefined || props.meta?.cacheStatus}>
      <span class="pagination-timing">
        <span aria-hidden="true">(</span>
        <Show when={props.meta?.elapsedMs !== undefined}>
          <span>{props.meta?.elapsedMs?.toFixed(1)}ms</span>
        </Show>
        <Show when={props.meta?.cacheStatus}>
          {(status) => (
            <span class={`cache-badge cache-badge--${status()}`}>{status()}</span>
          )}
        </Show>
        <span aria-hidden="true">)</span>
      </span>
    </Show>
  );
}

function RelationshipGroup(props: {
  parent: EaclObject;
  path: ChildPath;
  ancestry: ReadonlySet<string>;
}): JSX.Element {
  const app = useAppState();
  const key = () =>
    `relationship:${resourceKey(props.parent)}:${props.path.resourceType}:${props.path.relation}`;
  const expanded = () => app.isExpanded(key());
  const request = new LatestRequest();
  const [cursors, setCursors] = createSignal<string[]>([]);
  const cursor = () => cursors().at(-1);
  const source = () =>
    expanded() && app.permission()
      ? ([
          props.parent.type,
          props.parent.id,
          props.path.resourceType,
          props.path.relation,
          app.subjectId(),
          app.permission(),
          app.pageSize(),
          cursor() ?? "",
          app.cacheEnabled(),
          app.mutationRevision(),
        ] as const)
      : false;
  const [relationships, { refetch }] = createResource(source, (input) =>
    request.run<RelationshipPage>("/api/eacl/read-relationships", {
      method: "POST",
      body: JSON.stringify({
        subject: { type: input[0], id: input[1] },
        resourceType: input[2],
        relation: input[3],
        authorizationSubject: { type: "user", id: input[4] },
        permission: input[5],
        pageSize: input[6],
        after: input[7] || undefined,
        cache: input[8],
      }),
    }),
  );
  const [displayedRelationships, setDisplayedRelationships] =
    createSignal<ApiSuccess<RelationshipPage>>();
  const [displayedCursors, setDisplayedCursors] = createSignal<string[]>([]);
  const [pendingAction, setPendingAction] =
    createSignal<"first" | "previous" | "next">();

  createEffect(() => {
    if (relationships.loading || relationships.error) return;
    const envelope = relationships();
    if (!envelope) return;
    setDisplayedRelationships(envelope);
    setDisplayedCursors([...cursors()]);
  });
  createEffect(() => {
    if (!relationships.loading) setPendingAction(undefined);
  });

  createEffect(
    on(
      () => [
        app.subjectId(),
        app.permission(),
        app.pageSize(),
        app.cacheEnabled(),
        app.queryGeneration(),
      ] as const,
      () => setCursors((current) => (current.length ? [] : current)),
      { defer: true },
    ),
  );
  onCleanup(() => request.abort());
  const settledRelationships = displayedRelationships;
  const navigationAction = () => {
    if (cursors().length > displayedCursors().length) return "next" as const;
    if (!cursors().length && displayedCursors().length) return "first" as const;
    if (cursors().length < displayedCursors().length) return "previous" as const;
    return undefined;
  };
  const navigate = (
    action: "first" | "previous" | "next",
    nextCursors: string[],
  ) => {
    if (relationships.loading) return;
    setPendingAction(action);
    setCursors(nextCursors);
  };
  const retryRelationships = () => {
    setPendingAction(navigationAction());
    void refetch();
  };
  const relationshipRecovery = () => {
    if (!cursors().length) return undefined;
    return relationships.error instanceof ApiError &&
      relationships.error.code === "invalid-cursor"
      ? { label: "First page", action: () => navigate("first", []) }
      : {
          label: "Previous page",
          action: () =>
            navigate("previous", displayedCursors().slice(0, -1)),
        };
  };

  return (
    <div class="relationship-group">
      <div class="relationship-group__header">
        <DisclosureButton
          expanded={expanded()}
          controls={`${key()}-content`}
          onClick={() => app.toggleExpanded(key())}
        >
          <TypeBadge type={props.path.resourceType} />
          <span class="relationship-group__title">
            {identifierLabel(props.path.resourceType)}s
          </span>
        </DisclosureButton>
        <MetaTiming meta={settledRelationships()?.meta} />
        <Show when={relationships.loading && settledRelationships()}>
          <InlineLoading label={`Loading page ${cursors().length + 1}…`} />
        </Show>
      </div>
      <Show when={expanded()}>
        <div id={`${key()}-content`} class="relationship-group__content">
          <Show when={relationships.loading && !settledRelationships()}>
            <LoadingBlock label={`relationships page ${cursors().length + 1}`} />
          </Show>
          <Show when={relationships.error}>
            <ErrorBlock
              label={`Relationships page ${cursors().length + 1} failed`}
              error={relationships.error}
              retry={retryRelationships}
              secondary={relationshipRecovery()}
            />
          </Show>
          <Show when={settledRelationships()}>
            {(envelope: () => ApiSuccess<RelationshipPage>) => (
              <>
                <Pagination
                  page={displayedCursors().length + 1}
                  canPrevious={displayedCursors().length > 0}
                  canNext={envelope().data.pageInfo.hasNextPage}
                  busy={relationships.loading}
                  busyAction={pendingAction()}
                  first={() => navigate("first", [])}
                  previous={() =>
                    navigate("previous", displayedCursors().slice(0, -1))
                  }
                  next={() => {
                    const next = envelope().data.pageInfo.endCursor;
                    if (next) navigate("next", [...displayedCursors(), next]);
                  }}
                />
                <div class="resource-children" aria-busy={relationships.loading}>
                  <For
                    each={envelope().data.items}
                    fallback={<EmptyState>No authorized resources on this page.</EmptyState>}
                  >
                    {(relationship) => (
                      <ResourceNode
                        resource={relationship.resource}
                        ancestry={props.ancestry}
                      />
                    )}
                  </For>
                </div>
              </>
            )}
          </Show>
        </div>
      </Show>
    </div>
  );
}

function ResourceNode(props: {
  resource: EaclObject;
  ancestry: ReadonlySet<string>;
}): JSX.Element {
  const app = useAppState();
  const key = () => resourceKey(props.resource);
  const cycle = () => props.ancestry.has(key());
  const paths = createMemo(
    () => app.bootstrapData()?.data.schema.childPaths[props.resource.type] ?? [],
  );
  const expanded = () => app.isExpanded(`resource:${key()}`);
  const selected = () => resourceKey(app.selectedResource() ?? { type: "", id: "" }) === key();
  const nextAncestry = createMemo(
    () => new Set([...props.ancestry, key()]) as ReadonlySet<string>,
  );

  return (
    <div class={`resource-node ${cycle() ? "resource-node--cycle" : ""}`}>
      <div class="resource-node__row">
        <Show
          when={!cycle() && paths().length > 0}
          fallback={<span class="resource-node__spacer" aria-hidden="true" />}
        >
          <button
            type="button"
            class="resource-node__toggle"
            aria-label={`${expanded() ? "Collapse" : "Expand"} ${props.resource.id}`}
            aria-expanded={expanded()}
            onClick={() => app.toggleExpanded(`resource:${key()}`)}
          >
            {expanded() ? "▾" : "▸"}
          </button>
        </Show>
        <button
          type="button"
          class={`resource-button ${selected() ? "resource-button--active" : ""}`}
          aria-pressed={selected()}
          onClick={() => app.setSelectedResource(props.resource)}
        >
          <TypeBadge type={props.resource.type} />
          <span class="resource-caption">
            <span class="resource-caption__name">
              {identifierLabel(props.resource.id)}
            </span>
            {" "}
            <span class="resource-caption__id">{props.resource.id}</span>
          </span>
        </button>
        <Show when={cycle()}>
          <span class="cycle-badge">cycle</span>
        </Show>
      </div>
      <Show when={expanded() && !cycle()}>
        <div class="resource-node__children">
          <For each={paths()}>
            {(path) => (
              <RelationshipGroup
                parent={props.resource}
                path={path}
                ancestry={nextAncestry()}
              />
            )}
          </For>
        </div>
      </Show>
    </div>
  );
}

function ResourceTypeGroup(props: { resourceType: string }): JSX.Element {
  const app = useAppState();
  const groupKey = () => `resource-type:${props.resourceType}`;
  const expanded = () => app.isExpanded(groupKey());
  const pageRequest = new LatestRequest();
  const countRequest = new LatestRequest();
  const [cursors, setCursors] = createSignal<string[]>([]);
  const [countDemandVersion, setCountDemandVersion] = createSignal(0);
  let activeCountScope = "";
  let activeCountLimit = initialCountLimit;
  const cursor = () => cursors().at(-1);
  const countScope = () =>
    JSON.stringify([
      app.subjectId(),
      app.permission(),
      props.resourceType,
      app.cacheEnabled(),
      app.mutationRevision(),
    ]);
  const countLimit = () => {
    countDemandVersion();
    const scope = countScope();
    if (scope !== activeCountScope) {
      activeCountScope = scope;
      activeCountLimit = initialCountLimit;
    }
    return activeCountLimit;
  };
  const base = () =>
    expanded() && app.permission()
      ? ([
          app.subjectId(),
          app.permission(),
          props.resourceType,
          app.cacheEnabled(),
          app.mutationRevision(),
        ] as const)
      : false;
  const pageSource = () => {
    const value = base();
    return value ? ([...value, app.pageSize(), cursor() ?? ""] as const) : false;
  };
  const countSource = () => {
    const value = base();
    return value ? ([...value, countLimit()] as const) : false;
  };
  const [page, { refetch: refetchPage }] = createResource(pageSource, (input) =>
    pageRequest.run<ObjectPage>("/api/eacl/lookup-resources", {
      method: "POST",
      body: JSON.stringify({
        subject: { type: "user", id: input[0] },
        permission: input[1],
        resourceType: input[2],
        cache: input[3],
        pageSize: input[5],
        after: input[6] || undefined,
      }),
    }),
  );
  const [count, { refetch: refetchCount }] = createResource(countSource, (input) =>
    countRequest.run<ResourceCount>("/api/eacl/count-resources", {
      method: "POST",
      body: JSON.stringify({
        subject: { type: "user", id: input[0] },
        permission: input[1],
        resourceType: input[2],
        cache: input[3],
        countLimit: input[5],
      }),
    }),
  );
  const [displayedPage, setDisplayedPage] = createSignal<ApiSuccess<ObjectPage>>();
  const [displayedCount, setDisplayedCount] =
    createSignal<ApiSuccess<ResourceCount>>();
  const [displayedCursors, setDisplayedCursors] = createSignal<string[]>([]);
  const [displayedPageSize, setDisplayedPageSize] = createSignal(app.pageSize());
  const [pendingPageAction, setPendingPageAction] =
    createSignal<"first" | "previous" | "next">();

  createEffect(() => {
    if (page.loading || page.error) return;
    const envelope = page();
    if (!envelope) return;
    setDisplayedPage(envelope);
    setDisplayedCursors([...cursors()]);
    setDisplayedPageSize(app.pageSize());
  });
  createEffect(() => {
    if (!page.loading) setPendingPageAction(undefined);
  });
  createEffect(() => {
    if (count.loading || count.error) return;
    const envelope = count();
    if (envelope) setDisplayedCount(envelope);
  });

  const doubleCountLimit = () => {
    const result = displayedCount()?.data;
    if (!result?.truncated || count.loading) return;
    const currentLimit = Math.max(countLimit(), result.limit);
    const nextLimit = Math.min(Number.MAX_SAFE_INTEGER, currentLimit * 2);
    if (nextLimit > currentLimit) {
      activeCountScope = countScope();
      activeCountLimit = nextLimit;
      setCountDemandVersion((version) => version + 1);
    }
  };

  createEffect(
    on(
      () => [
        app.subjectId(),
        app.permission(),
        app.pageSize(),
        app.cacheEnabled(),
        app.queryGeneration(),
      ] as const,
      () => setCursors((current) => (current.length ? [] : current)),
      { defer: true },
    ),
  );
  onCleanup(() => {
    pageRequest.abort();
    countRequest.abort();
  });

  const settledPage = displayedPage;
  const settledCount = displayedCount;
  const pageNavigationAction = () => {
    if (cursors().length > displayedCursors().length) return "next" as const;
    if (!cursors().length && displayedCursors().length) return "first" as const;
    if (cursors().length < displayedCursors().length) return "previous" as const;
    return undefined;
  };
  const navigatePage = (
    action: "first" | "previous" | "next",
    nextCursors: string[],
  ) => {
    if (page.loading) return;
    setPendingPageAction(action);
    setCursors(nextCursors);
  };
  const retryPage = () => {
    setPendingPageAction(pageNavigationAction());
    void refetchPage();
  };
  const pageRecovery = () => {
    if (!cursors().length) return undefined;
    return page.error instanceof ApiError && page.error.code === "invalid-cursor"
      ? { label: "First page", action: () => navigatePage("first", []) }
      : {
          label: "Previous page",
          action: () =>
            navigatePage("previous", displayedCursors().slice(0, -1)),
        };
  };
  const itemCount = () => settledPage()?.data.items.length ?? 0;
  const rangeStart = () =>
    itemCount() ? displayedCursors().length * displayedPageSize() + 1 : 0;
  const rangeEnd = () =>
    displayedCursors().length * displayedPageSize() + itemCount();

  return (
    <div class="group-card">
      <div class="group-card__header">
        <DisclosureButton
          expanded={expanded()}
          controls={`${groupKey()}-content`}
          onClick={() => app.toggleExpanded(groupKey())}
        >
          <TypeBadge type={props.resourceType} />
          <span class="group-card__title">{identifierLabel(props.resourceType)}s</span>
        </DisclosureButton>
        <Show when={expanded()}>
          <div class="group-card__stats">
            <span class="group-card__page-stats">
              <Show
                when={settledPage()}
                fallback={
                  page.loading
                    ? <InlineLoading label={`Loading page ${cursors().length + 1}…`} />
                    : page.error
                      ? <InlineError label={`Page ${cursors().length + 1} failed`} />
                      : <span class="section-meta">—</span>
                }
              >
                <span class="group-card__range">
                  {rangeStart()}–{rangeEnd()}
                </span>
                <PaginationTiming meta={settledPage()?.meta} />
                <Show when={page.loading}>
                  <InlineLoading label={`Loading page ${cursors().length + 1}…`} />
                </Show>
                <Show when={page.error}>
                  <InlineError label={`Page ${cursors().length + 1} failed`} />
                </Show>
              </Show>
            </span>
            <span class="group-card__stats-separator">of</span>
            <span class="group-card__count-stats">
              <Show
                when={settledCount()}
                fallback={
                  count.loading
                    ? <InlineLoading label="Counting…" />
                    : count.error
                      ? <InlineError label="Count failed" />
                      : <span class="section-meta">—</span>
                }
              >
                {(envelope: () => ApiSuccess<ResourceCount>) => (
                  <>
                    <Show
                      when={envelope().data.truncated}
                      fallback={
                        <span class="group-card__count">
                          {countFormatter.format(envelope().data.count)}
                        </span>
                      }
                    >
                      <button
                        type="button"
                        class="group-card__count group-card__count-button"
                        aria-label={`Count beyond ${countFormatter.format(envelope().data.count)} ${props.resourceType} resources`}
                        disabled={count.loading}
                        aria-busy={count.loading}
                        onClick={doubleCountLimit}
                      >
                        <Show when={count.loading}>
                          <ButtonSpinner />
                        </Show>
                        {formatTruncatedCount(envelope().data.count)}+
                      </button>
                    </Show>
                    <PaginationTiming meta={envelope().meta} />
                    <Show when={count.loading && !envelope().data.truncated}>
                      <InlineLoading label="Counting…" />
                    </Show>
                    <Show when={count.error}>
                      <InlineError label="Count failed" />
                    </Show>
                  </>
                )}
              </Show>
            </span>
          </div>
        </Show>
      </div>

      <Show when={expanded()}>
        <div id={`${groupKey()}-content`} class="group-card__content">
          <Show when={page.loading && !settledPage()}>
            <LoadingBlock label={`${props.resourceType} page ${cursors().length + 1}`} />
          </Show>
          <Show when={page.error}>
            <ErrorBlock
              label={`${identifierLabel(props.resourceType)} page ${cursors().length + 1} failed`}
              error={page.error}
              retry={retryPage}
              secondary={pageRecovery()}
            />
          </Show>
          <Show when={count.error}>
            <ErrorBlock
              label={`${identifierLabel(props.resourceType)} count failed`}
              error={count.error}
              retry={() => void refetchCount()}
            />
          </Show>
          <Show when={settledPage()}>
            {(envelope: () => ApiSuccess<ObjectPage>) => (
              <>
                <Pagination
                  page={displayedCursors().length + 1}
                  canPrevious={displayedCursors().length > 0}
                  canNext={envelope().data.pageInfo.hasNextPage}
                  busy={page.loading}
                  busyAction={pendingPageAction()}
                  first={() => navigatePage("first", [])}
                  previous={() =>
                    navigatePage("previous", displayedCursors().slice(0, -1))
                  }
                  next={() => {
                    const next = envelope().data.pageInfo.endCursor;
                    if (next) navigatePage("next", [...displayedCursors(), next]);
                  }}
                />
                <div class="resource-tree" aria-busy={page.loading}>
                  <For
                    each={envelope().data.items}
                    fallback={<EmptyState>No resources on this page.</EmptyState>}
                  >
                    {(resource) => (
                      <ResourceNode resource={resource} ancestry={new Set()} />
                    )}
                  </For>
                </div>
              </>
            )}
          </Show>
        </div>
      </Show>
    </div>
  );
}

export function ResourceTreePanel(): JSX.Element {
  const app = useAppState();
  return (
    <div class="panel-card resources-panel">
      <h2 class="panel-kicker">Resources</h2>
      <div class="panel-summary">
        <span class="panel-summary__value">{app.subjectId()}</span>
        <span class="panel-summary__separator" aria-hidden="true">
          ·
        </span>
        <span class="panel-summary__value">:{app.permission()}</span>
      </div>
      <For
        each={app.bootstrapData()?.data.schema.resourceTypes ?? []}
        fallback={<EmptyState>No queryable resource types.</EmptyState>}
      >
        {(resourceType) => <ResourceTypeGroup resourceType={resourceType} />}
      </For>
    </div>
  );
}
