import { Component, StrictMode } from 'react'
import type { ErrorInfo, ReactNode } from 'react'
import { createRoot } from 'react-dom/client'
import { Capacitor } from '@capacitor/core'
import './index.css'
import App from './App.tsx'

class StartupErrorBoundary extends Component<{ children: ReactNode }, { error?: Error }> {
  state: { error?: Error } = {}

  static getDerivedStateFromError(error: Error) {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('Podmix startup render failed', error, info.componentStack)
  }

  render() {
    if (this.state.error) {
      return <pre style={{ color: '#fff', padding: 20, whiteSpace: 'pre-wrap' }}>{this.state.error.stack || this.state.error.message}</pre>
    }
    return this.props.children
  }
}

function renderApp() {
  createRoot(document.getElementById('root')!).render(
    <StrictMode>
      <StartupErrorBoundary><App /></StartupErrorBoundary>
    </StrictMode>,
  )
}

async function start() {
  if ('serviceWorker' in navigator && Capacitor.isNativePlatform()) {
    // The APK already contains versioned web assets. Finish removing any PWA
    // worker and caches from an older APK before React starts rendering.
    try {
      const registrations = await navigator.serviceWorker.getRegistrations()
      await Promise.all(registrations.map((registration) => registration.unregister()))
      if ('caches' in window) {
        const keys = await caches.keys()
        await Promise.all(keys.map((key) => caches.delete(key)))
      }
    } catch {
      // Cache cleanup must never prevent the packaged application from opening.
    }
  } else if ('serviceWorker' in navigator) {
    window.addEventListener('load', () => {
      void navigator.serviceWorker.register('/sw.js')
    })
  }
  renderApp()
}

void start()
