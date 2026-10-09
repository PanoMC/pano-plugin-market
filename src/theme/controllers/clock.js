// `market/clock`: one shared `now` (epoch ms) ticking every second while it has subscribers; 0 on the server (14 §4.7).
import { defineController } from '@panomc/plugin-kit/controller';

export default defineController({
  name: 'clock',
  version: 1,
  state: () => ({ now: 0 }),
  // `start` never runs on the server, so the value stays 0 there
  start: ({ host, set }) => {
    set({ now: host.now() });
    const timer = setInterval(() => set({ now: host.now() }), 1000);

    return () => clearInterval(timer);
  },
});
