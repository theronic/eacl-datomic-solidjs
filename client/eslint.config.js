import eslint from "@eslint/js";
import solid from "eslint-plugin-solid/configs/typescript";
import tseslint from "typescript-eslint";

export default tseslint.config(
  { ignores: ["dist", "playwright-report", "test-results"] },
  eslint.configs.recommended,
  ...tseslint.configs.recommended,
  solid,
  {
    files: ["**/*.{ts,tsx}"],
    rules: {
      "@typescript-eslint/no-explicit-any": "off",
    },
  },
);
