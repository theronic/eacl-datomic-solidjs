import { readdir, readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { URL } from "node:url";
import { promisify } from "node:util";
import { gzip } from "node:zlib";

const gzipAsync = promisify(gzip);
const assetDirectory = new URL("../../server/resources/public/assets/", import.meta.url);
const files = await readdir(assetDirectory);

await Promise.all(
  files
    .filter((file) => !file.endsWith(".gz") && /\.(?:css|js|json|map|svg)$/.test(file))
    .map(async (file) => {
      const input = await readFile(new URL(file, assetDirectory));
      const compressed = await gzipAsync(input, { level: 9 });
      await writeFile(join(assetDirectory.pathname, `${file}.gz`), compressed);
    }),
);
