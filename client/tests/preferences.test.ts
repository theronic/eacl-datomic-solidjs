import { DEFAULT_PREFERENCES, readPreferences, writePreferences } from "../src/preferences";

describe("local preferences", () => {
  it("defaults new viewers to user-1", () => {
    expect(readPreferences()).toMatchObject({ subjectId: "user-1" });
  });

  it("falls back when storage throws or JSON is corrupt", () => {
    const throwing = {
      getItem: () => {
        throw new DOMException("blocked");
      },
    } as unknown as Storage;
    expect(readPreferences(throwing)).toEqual(DEFAULT_PREFERENCES);
    localStorage.setItem("eacl-datahike-demo.preferences.v2", "not-json");
    expect(readPreferences()).toEqual(DEFAULT_PREFERENCES);
  });

  it("normalizes unsafe stored values and persists valid settings", () => {
    localStorage.setItem(
      "eacl-datahike-demo.preferences.v2",
      JSON.stringify({
        subjectId: "user-1",
        permission: "view",
        pageSize: 11,
        cacheEnabled: false,
        theme: "neon",
        expanded: ["resource-type:server", 12],
      }),
    );
    expect(readPreferences()).toEqual({
      subjectId: "user-1",
      permission: "view",
      pageSize: 20,
      cacheEnabled: false,
      theme: "light",
      expanded: ["resource-type:server"],
    });

    writePreferences({ ...DEFAULT_PREFERENCES, theme: "dark", pageSize: 100 });
    expect(readPreferences()).toMatchObject({ theme: "dark", pageSize: 100 });
  });

  it("migrates the persisted v1 default without discarding other preferences", () => {
    localStorage.setItem(
      "eacl-datahike-demo.preferences.v1",
      JSON.stringify({
        ...DEFAULT_PREFERENCES,
        subjectId: "super-user",
        pageSize: 100,
        theme: "dark",
        expanded: ["resource-type:server"],
      }),
    );

    expect(readPreferences()).toEqual({
      ...DEFAULT_PREFERENCES,
      subjectId: "user-1",
      pageSize: 100,
      theme: "dark",
      expanded: ["resource-type:server"],
    });
  });

  it("preserves an explicit super-user selection written by v2", () => {
    writePreferences({ ...DEFAULT_PREFERENCES, subjectId: "super-user" });
    expect(readPreferences()).toMatchObject({ subjectId: "super-user" });
  });
});
