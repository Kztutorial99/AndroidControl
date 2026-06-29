const clients = new Map<string, Set<() => void>>()

export function subscribeDevice(deviceId: string, fn: () => void): () => void {
  if (!clients.has(deviceId)) clients.set(deviceId, new Set())
  clients.get(deviceId)!.add(fn)
  return () => clients.get(deviceId)?.delete(fn)
}

export function notifyDeviceUpdate(deviceId: string) {
  clients.get(deviceId)?.forEach(fn => fn())
}
