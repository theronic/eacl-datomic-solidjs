import {
  createEffect,
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
import type { ApiSuccess, EaclObject, ObjectPage } from "../types";
import {
  EmptyState,
  ErrorBlock,
  LoadingBlock,
  MetaTiming,
  Pagination,
  TypeBadge,
  identifierLabel,
} from "./Common";

function PermissionSubjects(props: {
  resource: EaclObject;
  permission: string;
}): JSX.Element {
  const app = useAppState();
  const request = new LatestRequest();
  const [cursors, setCursors] = createSignal<string[]>([]);
  const cursor = () => cursors().at(-1);
  const source = () =>
    ([
          props.resource.type,
          props.resource.id,
          props.permission,
          app.pageSize(),
          cursor() ?? "",
          app.cacheEnabled(),
          app.mutationRevision(),
        ] as const);
  const [subjects, { refetch }] = createResource(source, (input) =>
    request.run<ObjectPage>("/api/eacl/lookup-subjects", {
      method: "POST",
      body: JSON.stringify({
        resource: { type: input[0], id: input[1] },
        permission: input[2],
        subjectType: "user",
        pageSize: input[3],
        after: input[4] || undefined,
        cache: input[5],
      }),
    }),
  );

  createEffect(
    on(
      () => [
        props.resource.type,
        props.resource.id,
        app.pageSize(),
        app.cacheEnabled(),
        app.queryGeneration(),
      ] as const,
      () => setCursors([]),
      { defer: true },
    ),
  );
  createEffect(() => {
    const error = subjects.error;
    if (error instanceof ApiError && error.code === "invalid-cursor" && cursors().length) {
      setCursors([]);
    }
  });
  onCleanup(() => request.abort());

  return (
    <section class="panel-section permission-subjects">
      <div class="section-header">
        <p class="panel-label">:{props.permission}</p>
        <MetaTiming meta={subjects()?.meta} />
      </div>
      <Show when={subjects.loading && !subjects()}>
        <LoadingBlock label="permission holders" />
      </Show>
      <Show when={subjects.error}>
        <ErrorBlock error={subjects.error} retry={() => void refetch()} />
      </Show>
      <Show when={subjects()}>
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
            <div class="list-stack">
              <For
                each={envelope().data.items}
                fallback={<EmptyState>No subjects found.</EmptyState>}
              >
                {(subject) => (
                  <button
                    type="button"
                    class={`list-item ${app.subjectId() === subject.id ? "list-item--active" : ""}`}
                    onClick={() => app.setSubjectId(subject.id)}
                  >
                    <TypeBadge type={subject.type} />
                    <span class="resource-caption">
                      <span class="resource-caption__name">
                        {identifierLabel(subject.id)}
                      </span>
                      <span class="resource-caption__id">{subject.id}</span>
                    </span>
                  </button>
                )}
              </For>
            </div>
          </>
        )}
      </Show>
    </section>
  );
}

export function DetailPanel(): JSX.Element {
  const app = useAppState();
  const permissions = () => {
    const selected = app.selectedResource();
    if (!selected) return [];
    return app.bootstrap()?.data.schema.permissionsByType[selected.type] ?? [];
  };

  return (
    <div class="panel-card detail-panel">
      <h2 class="panel-kicker">Detail</h2>
      <Show
        when={app.selectedResource()}
        fallback={<EmptyState>Click a resource to inspect it.</EmptyState>}
      >
        {(selected) => (
          <>
            <div class="detail-header">
              <TypeBadge type={selected().type} />
              <div>
                <p class="detail-header__title">{identifierLabel(selected().type)}</p>
                <p class="detail-header__subtitle">
                  {identifierLabel(selected().id)}
                </p>
                <p class="detail-header__id">{selected().id}</p>
              </div>
            </div>
            <For
              each={permissions()}
              fallback={<EmptyState>No permissions defined for this resource type.</EmptyState>}
            >
              {(permission) => (
                <PermissionSubjects resource={selected()} permission={permission} />
              )}
            </For>
          </>
        )}
      </Show>
    </div>
  );
}
