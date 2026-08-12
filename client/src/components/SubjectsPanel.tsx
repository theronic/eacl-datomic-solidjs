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
import { LatestRequest } from "../api";
import { useAppState } from "../state";
import type { ApiSuccess, KnownSubjectPage } from "../types";
import {
  EmptyState,
  ErrorBlock,
  LoadingBlock,
  Pagination,
  TypeBadge,
  identifierLabel,
} from "./Common";

export function SubjectsPanel(): JSX.Element {
  const app = useAppState();
  const request = new LatestRequest();
  const [offset, setOffset] = createSignal(0);
  const source = () => [offset(), app.pageSize(), app.mutationRevision()] as const;
  const [subjects, { refetch }] = createResource(
    source,
    ([currentOffset, pageSize]) =>
      request.run<KnownSubjectPage>(
        `/api/subjects?offset=${currentOffset}&limit=${pageSize}`,
      ),
  );

  createEffect(
    on(
      () => app.pageSize(),
      () => setOffset(0),
      { defer: true },
    ),
  );
  onCleanup(() => request.abort());

  const settledSubjects = () =>
    subjects.loading || subjects.error ? undefined : subjects();

  const permissions = createMemo(() => {
    const byType = app.bootstrap()?.data.schema.permissionsByType ?? {};
    return [...new Set(Object.values(byType).flat())].sort();
  });
  const page = () => Math.floor(offset() / app.pageSize()) + 1;

  return (
    <div class="panel-card subjects-panel">
      <h2 class="panel-kicker">Subjects &amp; permissions</h2>
      <div class="active-summary active-summary--subject">
        <span class="active-summary__label">Active subject</span>
        <span class="active-summary__value">{app.subjectId()}</span>
      </div>

      <section class="panel-section" aria-labelledby="permission-heading">
        <div class="section-header">
          <p id="permission-heading" class="panel-label">
            Permission
          </p>
        </div>
        <div class="chip-row">
          <For each={permissions()} fallback={<EmptyState>No permissions defined.</EmptyState>}>
            {(permission) => (
              <button
                type="button"
                class={`chip ${app.permission() === permission ? "chip--active" : ""}`}
                aria-pressed={app.permission() === permission}
                onClick={() => app.setPermission(permission)}
              >
                :{permission}
              </button>
            )}
          </For>
        </div>
      </section>

      <section class="panel-section" aria-labelledby="quick-subject-heading">
        <div class="section-header">
          <p id="quick-subject-heading" class="panel-label">
            Quick subjects
          </p>
        </div>
        <div class="chip-row">
          <For each={app.bootstrap()?.data.quickSubjects ?? []}>
            {(subject) => (
              <button
                type="button"
                class={`subject-button ${app.subjectId() === subject.id ? "subject-button--active" : ""}`}
                aria-pressed={app.subjectId() === subject.id}
                onClick={() => app.setSubjectId(subject.id)}
              >
                {subject.label}
              </button>
            )}
          </For>
        </div>
      </section>

      <section class="panel-section" aria-labelledby="known-subject-heading">
        <div class="section-header">
          <p id="known-subject-heading" class="panel-label">
            Known users
          </p>
          <Show when={settledSubjects()?.data.pageInfo.total !== undefined}>
            <span class="section-meta">
              {settledSubjects()?.data.pageInfo.total} total
            </span>
          </Show>
        </div>

        <Show when={subjects.loading}>
          <LoadingBlock label={`subjects page ${page()}`} />
        </Show>
        <Show when={subjects.error}>
          <ErrorBlock
            label={`Subjects page ${page()} failed`}
            error={subjects.error}
            retry={() => void refetch()}
            secondary={offset() > 0
              ? {
                  label: "Previous page",
                  action: () => setOffset(Math.max(0, offset() - app.pageSize())),
                }
              : undefined}
          />
        </Show>
        <Show when={settledSubjects()}>
          {(envelope: () => ApiSuccess<KnownSubjectPage>) => (
            <>
              <Pagination
                page={page()}
                canPrevious={offset() > 0}
                canNext={envelope().data.pageInfo.hasNextPage}
                first={() => setOffset(0)}
                previous={() => setOffset(Math.max(0, offset() - app.pageSize()))}
                next={() =>
                  setOffset(envelope().data.pageInfo.nextOffset ?? offset())
                }
              />
              <div class="list-stack">
                <For
                  each={envelope().data.data}
                  fallback={<EmptyState>No users on this page.</EmptyState>}
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
                        {" "}
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
    </div>
  );
}
