import {
  createEffect,
  createResource,
  createSignal,
  For,
  lazy,
  onCleanup,
  Show,
  Suspense,
  type JSX,
} from "solid-js";
import { apiRequest, LatestRequest } from "../api";
import { useAppState } from "../state";
import type { SchemaInfo } from "../types";
import { DisclosureButton, ErrorBlock, LoadingBlock } from "./Common";

const SchemaGraph = lazy(() => import("./SchemaGraph"));

export function SchemaPanel(): JSX.Element {
  const app = useAppState();
  const request = new LatestRequest();
  const [schema, { mutate, refetch }] = createResource(
    () => true,
    () => request.run<SchemaInfo>("/api/schema"),
  );
  const [draft, setDraft] = createSignal("");
  const [committed, setCommitted] = createSignal("");
  const [writeError, setWriteError] = createSignal<unknown>();
  const [writing, setWriting] = createSignal(false);
  const expansionKey = "segment:schema";
  const expanded = () => app.isExpanded(expansionKey);

  createEffect(() => {
    const source = schema()?.data.source;
    if (source === undefined) return;
    if (!committed() || draft() === committed()) setDraft(source);
    setCommitted(source);
  });
  onCleanup(() => request.abort());

  const writeSchema = async () => {
    setWriting(true);
    setWriteError(undefined);
    try {
      const result = await apiRequest<SchemaInfo>("/api/schema", {
        method: "PUT",
        body: JSON.stringify({ source: draft() }),
      });
      mutate(result);
      setCommitted(result.data.source);
      setDraft(result.data.source);
      app.applyMutationRevision(result.meta.revision);
      app.refetchBootstrap();
    } catch (error) {
      setWriteError(error);
    } finally {
      setWriting(false);
    }
  };

  return (
    <section class="schema-shell">
      <div class={`panel-card panel-card--graph ${expanded() ? "" : "panel-card--collapsed"}`}>
        <div class="panel-heading schema-shell__header">
          <DisclosureButton
            expanded={expanded()}
            controls="schema-segment-content"
            onClick={() => app.toggleExpanded(expansionKey)}
          >
            <span class="group-card__title">
              Schema ({schema()?.data.resourceCount ?? 0} resources, {" "}
              {schema()?.data.relationCount ?? 0} relations, {" "}
              {schema()?.data.permissionCount ?? 0} permissions)
            </span>
          </DisclosureButton>
          <Show when={writing() || draft() !== committed()}>
            <span class="section-meta" role="status">
              {writing() ? "Writing schema…" : "Unsaved changes"}
            </span>
          </Show>
        </div>
        <Show when={expanded()}>
          <div id="schema-segment-content" class="schema-panel">
            <section class="schema-panel__pane">
              <div class="section-header">
                <div>
                  <p class="panel-label">Spice Schema</p>
                  <p class="section-meta">Edit the schema and click Write Schema</p>
                </div>
              </div>
              <Show when={schema.loading && !schema()}>
                <LoadingBlock label="schema" />
              </Show>
              <Show when={schema.error}>
                <ErrorBlock error={schema.error} retry={() => void refetch()} />
              </Show>
              <div class="schema-preset-tabs" role="tablist" aria-label="Schema presets">
                <For each={schema()?.data.presets ?? []}>
                  {(preset) => (
                    <button
                      type="button"
                      role="tab"
                      class={`schema-preset-tab ${draft() === preset.schema ? "schema-preset-tab--active" : ""}`}
                      aria-selected={draft() === preset.schema}
                      onClick={() => setDraft(preset.schema)}
                    >
                      {preset.label}
                    </button>
                  )}
                </For>
              </div>
              <textarea
                id="schema-editor"
                class="schema-editor"
                aria-label="Spice Schema"
                spellcheck={false}
                value={draft()}
                onInput={(event) => setDraft(event.currentTarget.value)}
              />
              <div class="schema-panel__actions">
                <Show when={writeError()}>
                  {(error) => <ErrorBlock error={error()} />}
                </Show>
                <button
                  type="button"
                  class="pagination-button"
                  disabled={writing() || !draft() || draft() === committed()}
                  onClick={() => void writeSchema()}
                >
                  {writing() ? "Writing…" : "Write Schema"}
                </button>
              </div>
            </section>
            <section class="schema-panel__pane">
              <div class="section-header">
                <div>
                  <p class="panel-label">Schema Graph</p>
                  <p class="section-meta">Resources, permissions, and relation paths</p>
                </div>
              </div>
              <div class="graph-canvas">
                <Suspense fallback={<LoadingBlock label="schema graph" />}>
                  <Show when={schema()}>
                    {(envelope) => (
                      <SchemaGraph
                        nodes={envelope().data.nodes}
                        links={envelope().data.links}
                      />
                    )}
                  </Show>
                </Suspense>
              </div>
            </section>
          </div>
        </Show>
      </div>
    </section>
  );
}
