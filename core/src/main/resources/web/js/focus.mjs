// Context for the reader: Damage is Adam's own window manager for his own glasses, phone and PC on his own network.
// Build S1-1, package P7 (INTERFACES.md W-07): one focus model for arrows, Enter and Back, the same on a desk, a phone
// and (later) the TV's remote. The order is the description's (the page draws in that order); an arrow moves to the
// nearest focusable box in its direction, so a grid moves by rows and columns and a list by items. Pure.

const DIRS = {
  ArrowDown: [0, 1],
  ArrowUp: [0, -1],
  ArrowRight: [1, 0],
  ArrowLeft: [-1, 0],
};

/**
 * The index to focus after `key` from `current` among `boxes` ({x, y, w, h}, in order), or `current` when nothing
 * lies that way. With no current focus, any arrow takes the first box.
 */
export function nextFocus(boxes, current, key) {
  const d = DIRS[key];
  if (!d || boxes.length === 0) return current;
  if (current < 0 || current >= boxes.length) return 0;
  const c = boxes[current];
  const cx = c.x + c.w / 2;
  const cy = c.y + c.h / 2;
  let best = current;
  let bestScore = Infinity;
  boxes.forEach((b, i) => {
    if (i === current) return;
    const bx = b.x + b.w / 2;
    const by = b.y + b.h / 2;
    const along = (bx - cx) * d[0] + (by - cy) * d[1];
    // strictly in the arrow's direction, past the current box's half-extent so a row's neighbour is not "below"
    const half = d[0] !== 0 ? c.w / 2 : c.h / 2;
    if (along <= half * 0.5) return;
    const across = Math.abs((bx - cx) * d[1] + (by - cy) * d[0]);
    const score = along + across * 2;
    if (score < bestScore || (score === bestScore && i < best)) {
      bestScore = score;
      best = i;
    }
  });
  return best;
}
