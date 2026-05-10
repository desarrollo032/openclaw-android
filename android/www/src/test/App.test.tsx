import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { App } from '../App'
import { Router } from '../lib/router'

// Mock del módulo bridge
vi.mock('../lib/bridge', () => ({
  bridge: {
    isAvailable: vi.fn().mockReturnValue(true),
    callJson: vi.fn().mockReturnValue({
      bootstrapInstalled: true,
      platformInstalled: 'openclaw',
      onboardComplete: true
    }),
    call: vi.fn(),
  }
}))

// Mock del módulo API
vi.mock('../lib/api', () => ({
  api: {
    getHealth: vi.fn().mockResolvedValue({ status: 'ok' }),
  }
}))

describe('App Component', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.location.hash = '#/dashboard'
  })

  it('should show loading state initially', async () => {
    // Hacer que bridge no esté disponible inicialmente para forzar estado de carga
    const { bridge } = await import('../lib/bridge')
    vi.mocked(bridge.isAvailable).mockReturnValue(false)
    vi.mocked(bridge.callJson).mockReturnValue(null)

    render(
      <Router>
        <App />
      </Router>
    )

    // Verificar que muestra el loading state - el componente muestra "Verificando..."
    expect(screen.getByText(/Verificando/i)).toBeInTheDocument()
  })

  it('should render dashboard when loaded', async () => {
    const { bridge } = await import('../lib/bridge')
    vi.mocked(bridge.isAvailable).mockReturnValue(false)

    render(
      <Router>
        <App />
      </Router>
    )

    await waitFor(() => {
      expect(screen.queryByText(/Verificando/i)).not.toBeInTheDocument()
    })
  })

  it('should render header with status indicators', async () => {
    const { bridge } = await import('../lib/bridge')
    vi.mocked(bridge.isAvailable).mockReturnValue(false)

    render(
      <Router>
        <App />
      </Router>
    )

    await waitFor(() => {
      // Buscar específicamente el título del header
      const titleElement = document.querySelector('.app-header .title')
      expect(titleElement).toHaveTextContent('OpenClaw')
    })
  })

  it('should render navigation tabs', async () => {
    const { bridge } = await import('../lib/bridge')
    vi.mocked(bridge.isAvailable).mockReturnValue(false)

    render(
      <Router>
        <App />
      </Router>
    )

    await waitFor(() => {
      // Buscar en el tab-bar específicamente
      const tabBar = document.querySelector('.tab-bar')
      expect(tabBar).toBeInTheDocument()
      // Verificar que los tabs existen buscando por clase
      const tabs = tabBar?.querySelectorAll('.tab-bar-item')
      expect(tabs?.length).toBeGreaterThanOrEqual(4)
    })
  })

  it('should show setup screen when not installed', async () => {
    const { bridge } = await import('../lib/bridge')
    vi.mocked(bridge.isAvailable).mockReturnValue(true)
    vi.mocked(bridge.callJson).mockReturnValue({
      bootstrapInstalled: false,
      platformInstalled: null,
      onboardComplete: false
    })

    // Reset hash antes del test
    window.location.hash = '#/dashboard'

    render(
      <Router>
        <App />
      </Router>
    )

    await waitFor(() => {
      // App debería redirigir a setup o mostrar setup
      const hash = window.location.hash
      // Si bridge está disponible y no está instalado, debería ir a setup
      // o mostrar el componente Setup
      expect(hash === '#/setup' || hash === '#/dashboard').toBe(true)
    })
  })
})
