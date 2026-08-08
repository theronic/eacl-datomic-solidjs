import { DEFAULT_PREFERENCES, readPreferences, writePreferences } from "../src/preferences";

describe("local preferences", () => {
  it("falls back when storage throws or JSON is corrupt", () => {
    const throwing = {
      getItem: () => {
        throw new DOMException("blocked");
      },
    } as unknown as Storage;
    expect(readPreferences(throwing)).toEqual(DEFAULT_PREFERENCES);
    localStorage.setItem("eacl-solidjs.preferences.v1", "not-json");
    expect(readPreferences()).toEqual(DEFAULT_PREFERENCES);
  });

  it("normalizes unsafe stored values and persists valid settings", () => {
    localStorage.setItem(
      "eacl-solidjs.preferences.v1",
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
});
