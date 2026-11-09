/** A view two panes can share: zoom in device px per image px, and the image point at the center. */
export interface SharedView {
  fit: boolean
  zoom: number
  cx: number
  cy: number
}

/** Compare mode: panes publish their view and follow each other's (synchronized zoom and pan). */
export class ViewLink {
  private readonly listeners = new Map<string, (view: SharedView, settled: boolean) => void>()
  last: SharedView | null = null

  subscribe(id: string, listener: (view: SharedView, settled: boolean) => void) {
    this.listeners.set(id, listener)
    return () => void this.listeners.delete(id)
  }

  publish(from: string, view: SharedView, settled: boolean) {
    this.last = view
    for (const [id, listener] of this.listeners) if (id !== from) listener(view, settled)
  }
}
