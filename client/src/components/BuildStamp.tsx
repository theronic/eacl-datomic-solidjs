import { Show, type JSX } from "solid-js";
import { useAppState } from "../state";
import type { ArtifactProvenance, BuildInfo } from "../types";

const SHORT_COMMIT_LENGTH = 7;

export function shortCommit(commit: string | null | undefined): string | null {
  return commit ? commit.slice(0, SHORT_COMMIT_LENGTH) : null;
}

export function commitUrl(repository: string, commit: string): string {
  return `${repository.replace(/\/$/, "")}/commit/${commit}`;
}

/** Maven version a timestamped snapshot belongs to: 8.0.0-20260818.233134-7 → 8.0.0-SNAPSHOT. */
export function snapshotBaseVersion(version: string): string {
  const match = /^(.+)-\d{8}\.\d{6}-\d+$/.exec(version);
  return match ? `${match[1]}-SNAPSHOT` : version;
}

export function clojarsUrl(lib: string, version: string): string {
  return `https://clojars.org/${lib}/versions/${snapshotBaseVersion(version)}`;
}

export function formatBuiltAt(value: string | null | undefined): string | null {
  if (!value) return null;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return `${date.toISOString().slice(0, 16).replace("T", " ")} UTC`;
}

function CommitLink(props: {
  repository: string;
  commit: string;
  title?: string;
}): JSX.Element {
  return (
    <a
      class="app-footer__link"
      href={commitUrl(props.repository, props.commit)}
      title={props.title ?? props.commit}
    >
      <code>{shortCommit(props.commit)}</code>
    </a>
  );
}

function SourceStamp(props: { build: BuildInfo }): JSX.Element {
  const source = () => props.build.source;
  const title = () => {
    const parts = [source().commit ?? ""];
    if (source().ref) parts.unshift(`${source().ref} @`);
    if (source().committedAt) parts.push(`committed ${source().committedAt}`);
    return parts.join(" ");
  };
  return (
    <Show
      when={!props.build.development && source().commit}
      fallback={
        <code title="Running from a source checkout">
          {props.build.development ? "development" : "unknown"}
        </code>
      }
    >
      {(commit) => (
        <>
          <CommitLink repository={source().repository} commit={commit()} title={title()} />
          <Show when={source().dirty}>
            <code
              class="app-footer__build-warning"
              title="Built from a working tree with uncommitted changes"
            >
              +dirty
            </code>
          </Show>
        </>
      )}
    </Show>
  );
}

function ArtifactStamp(props: {
  repository: string;
  artifact: ArtifactProvenance | null;
}): JSX.Element {
  const artifact = () => props.artifact;
  const title = () =>
    `${artifact()?.lib ?? "dev.eacl/eacl-datahike"} · jar SHA-256 ${artifact()?.jarSha256 ?? "unknown"}`;
  return (
    <Show when={artifact()} fallback={<code>unavailable</code>}>
      {(resolved) => (
        <>
          <Show
            when={resolved().resolvedVersion}
            fallback={
              <>
                <code title={title()}>{resolved().version ?? "unknown"}</code>{" "}
                <span
                  class="app-footer__build-warning"
                  title="No Clojars download matches this jar; it was built or installed locally."
                >
                  (unpublished)
                </span>
              </>
            }
          >
            {(version) => (
              <a
                class="app-footer__link"
                href={clojarsUrl(resolved().lib, version())}
                title={title()}
              >
                <code>{version()}</code>
              </a>
            )}
          </Show>
          <Show when={resolved().commit}>
            {(commit) => (
              <>
                {" @ "}
                <CommitLink repository={props.repository} commit={commit()} />
              </>
            )}
          </Show>
        </>
      )}
    </Show>
  );
}

export function BuildStampContent(props: { build: BuildInfo }): JSX.Element {
  const eacl = () => props.build.eacl;
  const coreMismatch = () => {
    const adapter = eacl().adapter?.commit;
    const core = eacl().core?.commit;
    return adapter && core && adapter !== core ? eacl().core : null;
  };
  return (
    <p class="app-footer__build" aria-label="Build provenance">
      <span class="app-footer__build-label">Build</span> <SourceStamp build={props.build} />
      {" · "}
      <span class="app-footer__build-label">EACL</span>{" "}
      <ArtifactStamp repository={eacl().repository} artifact={eacl().adapter} />
      <Show when={coreMismatch()}>
        {(core) => (
          <span
            class="app-footer__build-warning"
            title="The EACL core jar was published from a different commit than the Datahike adapter."
          >
            {" "}
            (core @ <CommitLink repository={eacl().repository} commit={core().commit ?? ""} />)
          </span>
        )}
      </Show>
      <Show when={formatBuiltAt(props.build.builtAt)}>
        {(builtAt) => (
          <>
            {" · built "}
            <time dateTime={props.build.builtAt ?? undefined}>{builtAt()}</time>
          </>
        )}
      </Show>
    </p>
  );
}

export function BuildStamp(): JSX.Element {
  const app = useAppState();
  const build = () => app.bootstrapData()?.data.build;
  return <Show when={build()}>{(info) => <BuildStampContent build={info()} />}</Show>;
}
