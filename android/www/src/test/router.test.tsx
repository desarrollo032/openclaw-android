import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { Router, useRoute, Route, navigate } from '../lib/router'

// Componente de prueba que usa el router
function TestComponent() {
  const { path, navigate: nav } = useRoute()
  return (
    <div>
      <span data-testid="current-path">{path}</span>
      <button data-testid="nav-dashboard" onClick={() => nav('/dashboard')}>
        Dashboard
      </button>
      <button data-testid="nav-chat" onClick={() => nav('/chat')}>
        Chat
      </button>
    </div>
  )
}

function RouteTestComponent() {
  return (
    <div>
      <Route path="/dashboard">
        <span data-testid="dashboard-route">Dashboard Route</span>
      </Route>
      <Route path="/chat">
        <span data-testid="chat-route">Chat Route</span>
      </Route>
    </div>
  )
}

describe('Router', () => {
  beforeEach(() => {
    // Reset hash
    window.location.hash = ''
  })

  it('should provide default path as /dashboard', () => {
    render(
      <Router>
        <TestComponent />
      </Router>
    )

    expect(screen.getByTestId('current-path')).toHaveTextContent('/dashboard')
  })

  it('should update path when navigate is called', async () => {
    render(
      <Router>
        <TestComponent />
      </Router>
    )

    fireEvent.click(screen.getByTestId('nav-chat'))

    await waitFor(() => {
      expect(screen.getByTestId('current-path')).toHaveTextContent('/chat')
    })
  })

  it('should respond to hashchange events', async () => {
    render(
      <Router>
        <TestComponent />
      </Router>
    )

    window.location.hash = '#/settings'

    await waitFor(() => {
      expect(screen.getByTestId('current-path')).toHaveTextContent('/settings')
    })
  })

  it('should render matching routes', async () => {
    window.location.hash = '#/dashboard'

    render(
      <Router>
        <RouteTestComponent />
      </Router>
    )

    expect(screen.getByTestId('dashboard-route')).toBeInTheDocument()
    expect(screen.queryByTestId('chat-route')).not.toBeInTheDocument()
  })

  it('should match nested routes', async () => {
    window.location.hash = '#/settings/storage'

    render(
      <Router>
        <Route path="/settings">
          <span data-testid="settings-route">Settings Parent</span>
        </Route>
      </Router>
    )

    expect(screen.getByTestId('settings-route')).toBeInTheDocument()
  })
})

describe('navigate helper', () => {
  it('should change window location hash', () => {
    navigate('/test-path')
    expect(window.location.hash).toBe('#/test-path')
  })
})
