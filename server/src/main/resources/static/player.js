// Thin client player (ADR 0008). One <audio> element, kept from page to page: navigation is
// boosted by htmx and the player footer has hx-preserve. The position reaches the server every
// 15 s and on pause; at the end the episode is marked played and the next one of the queue starts.
(() => {
  const SYNC_MS = 15000;
  const SPEEDS = [1, 1.2, 1.5, 1.8, 2, 0.8];
  let current = null; // { id, audio, title, podcast, position }
  let lastSaved = null;

  const byId = (id) => document.getElementById(id);
  const speedLabel = (rate) => (document.documentElement.lang === 'fr' ? String(rate).replace('.', ',') : String(rate)) + '×';

  // Like htmx requests, they carry HX-Request: the server's protection against CSRF (ADR 0005).
  const send = (method, url, body, keepalive = false) => fetch(url, {
    method,
    keepalive,
    credentials: 'same-origin',
    headers: { 'HX-Request': 'true', 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  });

  // The lists showing episodes and the queue reload themselves on these events.
  const refresh = () => {
    if (!window.htmx) return;
    htmx.trigger(document.body, 'queue-changed');
    htmx.trigger(document.body, 'episodes-changed');
  };

  const fromDataset = (data) => ({
    id: data.play || data.episode,
    audio: data.audio,
    title: data.title,
    podcast: data.podcast,
    position: Number(data.position || 0),
  });

  function save(keepalive = false) {
    const audio = byId('audio');
    if (!current || !audio || !audio.src || audio.ended) return;
    const position = Math.floor(audio.currentTime);
    if (position === lastSaved) return;
    lastSaved = position;
    send('PUT', `/episodes/${current.id}/progress`, { positionSeconds: position }, keepalive);
  }

  /** Opens [episode] at its saved position; [play] starts it and takes it out of the queue. */
  function load(episode, play) {
    const audio = byId('audio');
    const player = byId('player');
    if (!audio || !player) return;
    if (current && current.id !== episode.id) save();
    current = episode;
    lastSaved = episode.position;
    player.hidden = false;
    byId('player-title').textContent = episode.title;
    byId('player-podcast').textContent = episode.podcast;
    audio.src = episode.audio;
    audio.addEventListener('loadedmetadata', () => {
      if (episode.position > 0) audio.currentTime = episode.position;
    }, { once: true });
    if (!play) return;
    audio.play().catch(() => {});
    send('DELETE', `/queue/${episode.id}`).then((response) => { if (response.ok) refresh(); });
  }

  /** Plays the first episode of the queue; false when the queue is empty. */
  async function playNext() {
    const response = await fetch('/queue', { credentials: 'same-origin', headers: { Accept: 'application/hal+json' } });
    if (!response.ok) return false;
    const first = (await response.json())._embedded.episodes[0];
    if (!first) return false;
    load({
      id: first.id,
      audio: first._links.audio.href,
      title: first.title,
      podcast: first._embedded.podcast.title,
      position: first.status === 'PLAYED' ? 0 : first.positionSeconds,
    }, true);
    return true;
  }

  document.addEventListener('click', (event) => {
    const button = event.target.closest('[data-play], [data-play-all], [data-skip], [data-speed], [data-next]');
    if (!button) return;
    event.preventDefault();
    const audio = byId('audio');
    if (button.dataset.play) {
      if (current && current.id === button.dataset.play && audio.src) {
        if (audio.paused) audio.play(); else audio.pause();
      } else {
        load(fromDataset(button.dataset), true);
      }
    } else if (button.dataset.playAll) {
      // A playlist (ADR 0010): the others first in the queue, in order, then the first one plays.
      const ids = button.dataset.playAll.split(',');
      (async () => {
        for (const [index, id] of ids.slice(1).entries()) await send('POST', '/queue', { episodeId: id, position: index });
        load(fromDataset(button.dataset), true);
      })();
    } else if (button.dataset.skip) {
      audio.currentTime = Math.max(0, audio.currentTime + Number(button.dataset.skip));
    } else if ('speed' in button.dataset) {
      const next = SPEEDS[(SPEEDS.indexOf(audio.playbackRate) + 1) % SPEEDS.length];
      audio.playbackRate = next;
      button.textContent = speedLabel(next);
    } else if ('next' in button.dataset) {
      save();
      playNext();
    }
  });

  function wire() {
    const audio = byId('audio');
    if (!audio || audio.dataset.wired) return;
    audio.dataset.wired = 'true';
    audio.addEventListener('pause', () => save());
    audio.addEventListener('seeked', () => { if (audio.paused) save(); });
    audio.addEventListener('ended', async () => {
      if (!current) return;
      const finished = current.id;
      lastSaved = null;
      await send('POST', `/episodes/${finished}/played`);
      refresh();
      await playNext();
    });
    // Home primes the player with the episode to resume.
    const player = byId('player');
    if (player.dataset.episode && !current) load(fromDataset(player.dataset), false);
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', wire); else wire();
  setInterval(() => {
    const audio = byId('audio');
    if (audio && !audio.paused) save();
  }, SYNC_MS);
  // Leaving the page or the tab: last chance to send the position.
  document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden') save(true); });
})();
