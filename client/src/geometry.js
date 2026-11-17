// Circle geometry shared by the live circle view and the compare page,
// so the two can never disagree about where a beat sits.
//
// One turn is one bar. Angle 0 is twelve o'clock and angles run
// clockwise, in radians. Coordinates are SVG's: centre at 0,0, y down.

const TAU = 2 * Math.PI

// A grouping vector ([3, 3] is 6/8, [2, 2, 3] is 7/8) as angles: a
// spoke where each group starts, a tick on every step.
export function meterAngles (grouping) {
  const steps = grouping.reduce((a, b) => a + b, 0)
  const ticks = Array.from({ length: steps }, (_, i) => TAU * i / steps)
  const spokes = []
  let at = 0
  for (const g of grouping) {
    spokes.push(TAU * at / steps)
    at += g
  }
  return { spokes, ticks, steps }
}

export function polar (angle, r) {
  return { x: r * Math.sin(angle), y: -r * Math.cos(angle) }
}

export function polygonPoints (angles, r) {
  return angles.map(a => polar(a, r))
}
