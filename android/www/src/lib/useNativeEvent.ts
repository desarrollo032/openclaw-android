/**
 * EventBridge hook — listen for Kotlin→WebView events (§2.8).
 * Kotlin dispatches: window.__oc.emit(type, data)
 * Which creates: CustomEvent('native:'+type, { detail: data })
 */

import { useEffect } from 'react'

export function useNativeEvent(type: string, handler: (data: unknown) => void): void {
  useEffect(() => {
    const listener = (e: Event) => {
      handler((e as CustomEvent).detail)
    }
    const nativeEvent = 'native:' + type
    const androidEvent = 'android:' + type
    window.addEventListener(nativeEvent, listener)
    window.addEventListener(androidEvent, listener)
    return () => {
      window.removeEventListener(nativeEvent, listener)
      window.removeEventListener(androidEvent, listener)
    }
  }, [type, handler])
}
