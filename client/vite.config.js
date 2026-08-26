import { defineConfig } from 'vite'

export default defineConfig({
  // The Clojure process serves client/dist, so build output must land there
  // and asset URLs must be relative to it.
  build: { outDir: 'dist', emptyOutDir: true },
  server: {
    // In dev, vite serves the page and proxies both sockets to the JVM,
    // so the editor reloads without rebuilding.
    proxy: {
      '/hud':  { target: 'ws://127.0.0.1:7890', ws: true },
      '/repl': { target: 'ws://127.0.0.1:7890', ws: true }
    }
  }
})
