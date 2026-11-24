import type { Mode } from '../api/types';

/**
 * Which side of the recorded traffic a mode filters on: the service's destination port for
 * TCP/UDP server modes, the source's port for TCP client mode.
 */
export function endpointSide(mode: Mode): { protocol: 'TCP' | 'UDP'; role: 'dst' | 'src' } {
  return { protocol: mode === 'UDP_SERVER' ? 'UDP' : 'TCP', role: mode === 'TCP_CLIENT' ? 'src' : 'dst' };
}
