import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    include: ["test-e2e/**/*.e2e.test.ts"],
    testTimeout: 900_000,
    hookTimeout: 600_000,
    fileParallelism: false,
  },
});
