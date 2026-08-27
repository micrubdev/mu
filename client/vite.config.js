import { defineConfig } from 'vite'

export default defineConfig({
  // The Clojure process serves client/dist, so build output must land there
  // and asset URLs must be relative to it.
  //
  // emptyOutDir must stay false: Vite's emptyDir rmSync's every entry not
  // on its own skip list, and .gitignore's tracked client/dist/.gitkeep is
  // not on it -- emptyOutDir: true deletes that tracked placeholder on
  // every build. Do not flip this back without also exempting .gitkeep.
  build: { outDir: 'dist', emptyOutDir: false },
  server: {
    // In dev, vite serves the page and proxies both sockets to the JVM,
    // so the editor reloads without rebuilding.
    proxy: {
      '/hud':  { target: 'ws://127.0.0.1:7890', ws: true },
      '/repl': { target: 'ws://127.0.0.1:7890', ws: true }
    }
  }
})
