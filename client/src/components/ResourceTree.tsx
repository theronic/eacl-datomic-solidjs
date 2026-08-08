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
  DisclosureButton,
  EmptyState,
  ErrorBlock,
  LoadingBlock,
  MetaTiming,
  Pagination,
  TypeBadge,
  identifierLabel,
} from "./Common";

const resourceKey = (resource: EaclObject) => `${resource.type}:${resource.id}`;

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
  createEffect(() => {
    const error = relationships.error;
    if (error instanceof ApiError && error.code === "invalid-cursor" && cursors().length) {
      setCursors([]);
    }
  });
  onCleanup(() => request.abort());

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
        <MetaTiming meta={relationships()?.meta} />
      </div>
      <Show when={expanded()}>
        <div id={`${key()}-content`} class="relationship-group__content">
          <Show when={relationships.loading && !relationships()}>
            <LoadingBlock label="relationships" />
          </Show>
          <Show when={relationships.error}>
            <ErrorBlock error={relationships.error} retry={() => void refetch()} />
          </Show>
          <Show when={relationships()}>
            {(envelope: () => ApiSuccess<RelationshipPage>) => (
              <>
                <Pagination
                  page={cursors().length + 1}
                  canPrevious={cursors().length > 0}
                  canNext={envelope().data.pageInfo.hasNextPage}
                  first={() => setCursors([])}
                  previous={() => setCursors((value) => value.slice(0, -1))}
                  next={() => {
                    const next = envelope().data.pageInfo.endCursor;
                    if (next) setCursors((value) => [...value, next]);
                  }}
                />
                <div class="resource-children">
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
    () => app.bootstrap()?.data.schema.childPaths[props.resource.type] ?? [],
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
  const cursor = () => cursors().at(-1);
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
  const [count, { refetch: refetchCount }] = createResource(base, (input) =>
    countRequest.run<ResourceCount>("/api/eacl/count-resources", {
      method: "POST",
      body: JSON.stringify({
        subject: { type: "user", id: input[0] },
        permission: input[1],
        resourceType: input[2],
        cache: input[3],
      }),
    }),
  );

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
  createEffect(() => {
    const error = page.error;
    if (error instanceof ApiError && error.code === "invalid-cursor" && cursors().length) {
      setCursors([]);
    }
  });
  onCleanup(() => {
    pageRequest.abort();
    countRequest.abort();
  });

  const itemCount = () => page()?.data.items.length ?? 0;
  const rangeStart = () => (itemCount() ? cursors().length * app.pageSize() + 1 : 0);
  const rangeEnd = () => cursors().length * app.pageSize() + itemCount();

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
              <Show when={page()} fallback={<span class="section-meta">—</span>}>
                <span class="group-card__range">
                  {rangeStart()}–{rangeEnd()}
                </span>
                <PaginationTiming meta={page()?.meta} />
              </Show>
            </span>
            <span class="group-card__stats-separator">of</span>
            <span class="group-card__count-stats">
              <Show when={count()} fallback={<span class="section-meta">—</span>}>
                <span class="group-card__count">{count()?.data.count}</span>
                <PaginationTiming meta={count()?.meta} />
              </Show>
            </span>
          </div>
        </Show>
      </div>

      <Show when={expanded()}>
        <div id={`${groupKey()}-content`} class="group-card__content">
          <Show when={(page.loading && !page()) || (count.loading && !count())}>
            <LoadingBlock label={`${props.resourceType} resources`} />
          </Show>
          <Show when={page.error}>
            <ErrorBlock error={page.error} retry={() => void refetchPage()} />
          </Show>
          <Show when={count.error}>
            <ErrorBlock error={count.error} retry={() => void refetchCount()} />
          </Show>
          <Show when={page()}>
            {(envelope: () => ApiSuccess<ObjectPage>) => (
              <>
                <Pagination
                  page={cursors().length + 1}
                  canPrevious={cursors().length > 0}
                  canNext={envelope().data.pageInfo.hasNextPage}
                  first={() => setCursors([])}
                  previous={() => setCursors((value) => value.slice(0, -1))}
                  next={() => {
                    const next = envelope().data.pageInfo.endCursor;
                    if (next) setCursors((value) => [...value, next]);
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
        each={app.bootstrap()?.data.schema.resourceTypes ?? []}
        fallback={<EmptyState>No queryable resource types.</EmptyState>}
      >
        {(resourceType) => <ResourceTypeGroup resourceType={resourceType} />}
      </For>
    </div>
  );
}
