// One shared `now` (epoch ms) ticking every second while it has subscribers; 0 on the server (14 §4.7).
import { readable } from 'svelte/store';

export const now = readable(0, (set) => {
  if (typeof window === 'undefined') return undefined;

  set(Date.now());
  const timer = setInterval(() => set(Date.now()), 1000);

  return () => clearInterval(timer);
});
