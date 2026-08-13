import { formatInteger, formatMilliseconds } from "../src/format";

describe("human-readable number formatting", () => {
  it("groups whole numbers", () => {
    expect(formatInteger(1_000_048)).toBe("1,000,048");
    expect(formatInteger(53_055)).toBe("53,055");
  });

  it("groups milliseconds while retaining one decimal place", () => {
    expect(formatMilliseconds(14_379.3)).toBe("14,379.3");
    expect(formatMilliseconds(3)).toBe("3.0");
  });
});
