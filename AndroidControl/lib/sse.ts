const deviceClients = new Map<string, Set<() => void>>()
const frameClients  = new Map<string, Set<(b64: string) => void>>()

export function subscribeDevice(deviceId: string, fn: () => void): () => void {
  if (!deviceClients.has(deviceId)) deviceClients.set(deviceId, new Set())
  deviceClients.get(deviceId)!.add(fn)
  return () => deviceClients.get(deviceId)?.delete(fn)
}

export function notifyDeviceUpdate(deviceId: string) {
  deviceClients.get(deviceId)?.forEach(fn => fn())
}

export function subscribeFrame(deviceId: string, fn: (b64: string) => void): () => void {
  if (!frameClients.has(deviceId)) frameClients.set(deviceId, new Set())
  frameClients.get(deviceId)!.add(fn)
  return () => frameClients.get(deviceId)?.delete(fn)
}

export function broadcastFrame(deviceId: string, b64: string) {
  frameClients.get(deviceId)?.forEach(fn => fn(b64))
}
