import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// In development, API calls go to the Spring Boot backend on :8081
export default defineConfig({
  plugins: [react()],
  server: { port: 3000, proxy: { '/api': 'http://localhost:8081', '/actuator': 'http://localhost:8081' } },
})
