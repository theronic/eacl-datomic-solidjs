import {
  createContext,
  createEffect,
  createMemo,
  createResource,
  createSignal,
  on,
  onCleanup,
  useContext,
  type Accessor,
  type ParentComponent,
  type Resource,
} from "solid-js";
import { apiRequest, LatestRequest } from "./api";
import { readPreferences, writePreferences } from "./preferences";
import type {
  ApiSuccess,
  Bootstrap,
  EaclObject,
  PageSize,
  SeedProgress,
  Theme,
} from "./types";

interface AppStateValue {
  bootstrap: Resource<ApiSuccess<Bootstrap>>;
  refetchBootstrap: () => void;
  subjectId: Accessor<string>;
  setSubjectId: (value: string) => void;
  permission: Accessor<string>;
  setPermission: (value: string) => void;
  selectedResource: Accessor<EaclObject | undefined>;
  setSelectedResource: (value: EaclObject | undefined) => void;
  pageSize: Accessor<PageSize>;
  setPageSize: (value: PageSize) => void;
  cacheEnabled: Accessor<boolean>;
  setCacheEnabled: (value: boolean) => void;
  theme: Accessor<Theme>;
  setTheme: (value: Theme) => void;
  mutationRevision: Accessor<string>;
  applyMutationRevision: (value: string) => void;
  queryGeneration: Accessor<number>;
  seedProgress: Accessor<SeedProgress | undefined>;
  setSeedProgress: (value: SeedProgress | undefined) => void;
  seeding: Accessor<boolean>;
  expanded: Accessor<ReadonlySet<string>>;
  toggleExpanded: (key: string) => void;
  isExpanded: (key: string) => boolean;
}

const AppState = createContext<AppStateValue>();

export const AppStateProvider: ParentComponent = (props) => {
  const preferences = readPreferences();
  const bootstrapRequest = new LatestRequest();
  const [bootstrap, { refetch: refetchBootstrapResource }] = createResource(
    () => true,
    () => bootstrapRequest.run<Bootstrap>("/api/bootstrap"),
  );
  const [subjectId, setSubjectSignal] = createSignal(preferences.subjectId);
  const [permission, setPermissionSignal] = createSignal(preferences.permission);
  const [selectedResource, setSelectedResource] = createSignal<EaclObject>();
  const [pageSize, setPageSizeSignal] = createSignal<PageSize>(preferences.pageSize);
  const [cacheEnabled, setCacheSignal] = createSignal(preferences.cacheEnabled);
  const [theme, setTheme] = createSignal<Theme>(preferences.theme);
  const [mutationRevision, setMutationRevision] = createSignal("");
  const [queryGeneration, setQueryGeneration] = createSignal(0);
  const [seedProgress, setSeedProgress] = createSignal<SeedProgress>();
  const [expanded, setExpanded] = createSignal<ReadonlySet<string>>(
    new Set(preferences.expanded),
  );
  const seeding = createMemo(
    () =>
      seedProgress()?.status === "seeding" ||
      (!bootstrap.error && bootstrap()?.data.status === "seeding"),
  );

  const invalidateQueries = () => setQueryGeneration((value) => value + 1);
  const setSubjectId = (value: string) => {
    setSubjectSignal(value);
    invalidateQueries();
  };
  const setPermission = (value: string) => {
    setPermissionSignal(value);
    invalidateQueries();
  };
  const setPageSize = (value: PageSize) => {
    setPageSizeSignal(value);
    invalidateQueries();
  };
  const setCacheEnabled = (value: boolean) => {
    setCacheSignal(value);
    invalidateQueries();
  };
  const applyMutationRevision = (value: string) => {
    setMutationRevision(value);
    invalidateQueries();
  };
  const toggleExpanded = (key: string) => {
    setExpanded((current) => {
      const next = new Set(current);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  createEffect(() => {
    if (bootstrap.error) return;
    const envelope = bootstrap();
    if (!envelope) return;
    if (!mutationRevision()) setMutationRevision(envelope.meta.revision);
    setSeedProgress(envelope.data.seed);
    const permissions = Object.values(envelope.data.schema.permissionsByType).flat();
    if (!permissions.includes(permission())) {
      setPermissionSignal(permissions[0] ?? "");
      invalidateQueries();
    }
  });

  createEffect(
    on(
      () => [
        subjectId(),
        permission(),
        pageSize(),
        cacheEnabled(),
        theme(),
        [...expanded()].sort().join("\u0000"),
      ] as const,
      () =>
        writePreferences({
          subjectId: subjectId(),
          permission: permission(),
          pageSize: pageSize(),
          cacheEnabled: cacheEnabled(),
          theme: theme(),
          expanded: [...expanded()].sort(),
        }),
    ),
  );

  createEffect(() => {
    document.documentElement.dataset.theme = theme();
  });

  createEffect(() => {
    if (!seeding()) return;
    let active = true;
    let timer: number | undefined;
    const poll = async () => {
      try {
        const result = await apiRequest<SeedProgress>("/api/seed");
        if (!active) return;
        setSeedProgress(result.data);
        if (result.meta.revision !== mutationRevision()) {
          applyMutationRevision(result.meta.revision);
        }
        if (result.data.status === "seeding") {
          timer = window.setTimeout(poll, 250);
        } else {
          void refetchBootstrapResource();
        }
      } catch (error) {
        if (!active) return;
        const current = seedProgress();
        setSeedProgress({
          status: "error",
          serversAdded: current?.serversAdded ?? 0,
          serversCompleted: current?.serversCompleted ?? 0,
          serversTarget: current?.serversTarget ?? 0,
          totalServers: current?.totalServers ?? 0,
          error: error instanceof Error ? error.message : String(error),
        });
      }
    };
    timer = window.setTimeout(poll, 100);
    onCleanup(() => {
      active = false;
      if (timer !== undefined) window.clearTimeout(timer);
    });
  });

  onCleanup(() => bootstrapRequest.abort());

  const value: AppStateValue = {
    bootstrap,
    refetchBootstrap: () => void refetchBootstrapResource(),
    subjectId,
    setSubjectId,
    permission,
    setPermission,
    selectedResource,
    setSelectedResource,
    pageSize,
    setPageSize,
    cacheEnabled,
    setCacheEnabled,
    theme,
    setTheme,
    mutationRevision,
    applyMutationRevision,
    queryGeneration,
    seedProgress,
    setSeedProgress,
    seeding,
    expanded,
    toggleExpanded,
    isExpanded: (key) => expanded().has(key),
  };

  return <AppState.Provider value={value}>{props.children}</AppState.Provider>;
};

export function useAppState(): AppStateValue {
  const value = useContext(AppState);
  if (!value) throw new Error("useAppState must be used inside AppStateProvider");
  return value;
}
