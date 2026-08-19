import { render, screen, waitFor } from "@solidjs/testing-library";
import { setFetchImplementation } from "../src/api";
import {
  BuildStamp,
  BuildStampContent,
  clojarsUrl,
  formatBuiltAt,
  shortCommit,
  snapshotBaseVersion,
} from "../src/components/BuildStamp";
import { AppStateProvider } from "../src/state";
import { bootstrap, build, jsonResponse, success } from "./fixtures";

describe("build stamp helpers", () => {
  it("abbreviates commits and maps snapshots to their Clojars page", () => {
    expect(shortCommit("f4be377a139f9bc9dfcb9c40f91418bdbf3a4b3d")).toBe("f4be377");
    expect(shortCommit(null)).toBeNull();
    expect(snapshotBaseVersion("8.0.0-20260818.233134-7")).toBe("8.0.0-SNAPSHOT");
    expect(snapshotBaseVersion("8.0.0")).toBe("8.0.0");
    expect(clojarsUrl("dev.eacl/eacl-datahike", "8.0.0-20260818.233134-7")).toBe(
      "https://clojars.org/dev.eacl/eacl-datahike/versions/8.0.0-SNAPSHOT",
    );
    expect(formatBuiltAt("2026-08-18T22:01:47.120Z")).toBe("2026-08-18 22:01 UTC");
    expect(formatBuiltAt(null)).toBeNull();
    expect(formatBuiltAt("not a date")).toBe("not a date");
  });
});

describe("build stamp", () => {
  it("links the demo commit, the EACL snapshot, and the EACL commit", () => {
    render(() => <BuildStampContent build={build} />);
    const stamp = screen.getByLabelText("Build provenance");
    expect(stamp).toHaveTextContent(
      "Build 06d8141 · EACL 8.0.0-20260818.233134-7 @ f4be377 · built 2026-08-18 22:01 UTC",
    );
    expect(screen.getByRole("link", { name: "06d8141" })).toHaveAttribute(
      "href",
      "https://github.com/theronic/eacl-datomic-solidjs/commit/06d8141a0cfebbd3b423cd719f9f05eb94ca50aa",
    );
    expect(screen.getByRole("link", { name: "8.0.0-20260818.233134-7" })).toHaveAttribute(
      "href",
      "https://clojars.org/dev.eacl/eacl-datahike/versions/8.0.0-SNAPSHOT",
    );
    expect(screen.getByRole("link", { name: "f4be377" })).toHaveAttribute(
      "href",
      "https://github.com/theronic/eacl/commit/f4be377a139f9bc9dfcb9c40f91418bdbf3a4b3d",
    );
    expect(stamp).not.toHaveTextContent("dirty");
    expect(stamp).not.toHaveTextContent("unpublished");
    expect(stamp).not.toHaveTextContent("core @");
  });

  it("flags dirty trees, unpublished EACL jars, and adapter/core commit drift", () => {
    render(() => (
      <BuildStampContent
        build={{
          ...build,
          source: { ...build.source, dirty: true },
          eacl: {
            ...build.eacl,
            adapter: {
              ...build.eacl.adapter!,
              resolvedVersion: null,
              commit: "1111111111111111111111111111111111111111",
            },
            core: {
              ...build.eacl.core!,
              commit: "2222222222222222222222222222222222222222",
            },
          },
        }}
      />
    ));
    const stamp = screen.getByLabelText("Build provenance");
    expect(stamp).toHaveTextContent("06d8141+dirty");
    expect(stamp).toHaveTextContent("EACL 8.0.0-SNAPSHOT (unpublished) @ 1111111");
    expect(stamp).toHaveTextContent("(core @ 2222222)");
    expect(screen.queryByRole("link", { name: "8.0.0-SNAPSHOT" })).not.toBeInTheDocument();
  });

  it("describes a source checkout without inventing a commit", () => {
    render(() => (
      <BuildStampContent
        build={{
          ...build,
          development: true,
          builtAt: null,
          source: { ...build.source, commit: null, ref: null, dirty: null },
        }}
      />
    ));
    const stamp = screen.getByLabelText("Build provenance");
    expect(stamp).toHaveTextContent("Build development · EACL 8.0.0-20260818.233134-7 @ f4be377");
    expect(stamp).not.toHaveTextContent("built ");
  });

  it("renders from bootstrap data and stays silent when the server omits it", async () => {
    let includeBuild = true;
    let bootstrapRequests = 0;
    setFetchImplementation(
      vi.fn(async (input: RequestInfo | URL) => {
        if (String(input) === "/api/bootstrap") {
          bootstrapRequests += 1;
          const withoutBuild: Partial<typeof bootstrap> = { ...bootstrap };
          delete withoutBuild.build;
          return jsonResponse(success(includeBuild ? bootstrap : withoutBuild));
        }
        throw new Error(`Unexpected request: ${String(input)}`);
      }) as typeof fetch,
    );
    const first = render(() => (
      <AppStateProvider>
        <BuildStamp />
      </AppStateProvider>
    ));
    await waitFor(() =>
      expect(screen.getByLabelText("Build provenance")).toHaveTextContent("06d8141"),
    );
    first.unmount();

    includeBuild = false;
    render(() => (
      <AppStateProvider>
        <BuildStamp />
      </AppStateProvider>
    ));
    await waitFor(() => expect(bootstrapRequests).toBe(2));
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(screen.queryByLabelText("Build provenance")).not.toBeInTheDocument();
  });
});
