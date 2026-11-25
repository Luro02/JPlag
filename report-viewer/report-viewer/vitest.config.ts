import { defineConfig } from 'vite'
import { configDefaults } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  test: {
    exclude: [...configDefaults.exclude, 'tests/e2e/*']
  }
})
