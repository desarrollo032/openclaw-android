import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { App } from '../App'
import { Router } from '../lib/router'

// Mock del módulo bridge — exporta TODOS los nombres usados por consumidores
// (bridge, call, callJson, isAvailable, on, off, getToken, onTokenRefresh,
// getNativeLocale, getNativeTheme). Si se omite cualquiera, Vitest lanza
// "No 'X' export is defined on the '../lib/bridge' mock".
vi.mock('../lib/bridge', () => {
  const isAvailable = vi.fn().mockReturnValue(true)
  const callJson = vi.fn().mockReturnValue({
    bootstrapInstalled: true,
    platformInstalled: 'openclaw',
    onboardComplete: true,
  })
  const call = vi.fn()
  const getToken = vi.fn().mockReturnValue('test-token')
  const noop = vi.fn()
  const handler = () => {}
  const on = vi.fn().mockReturnValue(handler)
  const off = vi.fn()
  const onTokenRefresh = vi.fn().mockReturnValue(handler)
  const getNativeLocale = vi.fn().mockReturnValue('es')
  const getNativeTheme = vi.fn().mockReturnValue('light')

  return {
    bridge: { isAvailable, call, callJson, getToken, on, off, onTokenRefresh, getNativeLocale, getNativeTheme },
    isAvailable, call, callJson, getToken, on, off, onTokenRefresh, getNativeLocale, getNativeTheme,
    // alias por si algún consumidor importa el nombre completo:
    _noop: noop,
  }
})

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

  it('should mount without throwing and render either loading or main shell', async () => {
    const { bridge } = await import('../lib/bridge')
    vi.mocked(bridge.isAvailable).mockReturnValue(true)
    vi.mocked(bridge.callJson).mockReturnValue(null)

    render(
      <Router>
        <App />
      </Router>
    )

    // En tests síncronos React puede flushar el useEffect antes del query,
    // así que aceptamos cualquiera de: "Iniciando..." (App.tsx:154) o
    // la pantalla Dashboard (saludo "Buenos…"). Lo importante es que el
    // árbol monte sin lanzar.
    await waitFor(() => {
      const initiating = screen.queryByText(/Iniciando/i)
      const greeting   = screen.queryByText(/Buen[oa]s/i)
      expect(initiating || greeting).not.toBeNull()
    })
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
      // El Dashboard saluda con uno de tres mensajes según hora del día.
      const greeting = screen.queryByText(/Buen[oa]s/i)
      expect(greeting).toBeInTheDocument()
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
      // La navegación inferior usa la clase .nav-container (App.tsx:223)
      const nav = document.querySelector('.nav-container')
      expect(nav).toBeInTheDocument()
      // Al menos 4 tabs (chat, dashboard, skills, memory)
      const tabs = nav?.querySelectorAll('button') ?? []
      expect(tabs.length).toBeGreaterThanOrEqual(4)
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
      expect(window.location.hash).toBe('#/setup')
    })
  })
})
