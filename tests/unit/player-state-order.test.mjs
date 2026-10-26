import assert from 'node:assert/strict'
import { createPlayerStateOrder } from '../../src/playerStateOrder.ts'
const order = createPlayerStateOrder()
const current = { stateSequence: 12, playing: true, positionSeconds: 123 }
assert.equal(order.accept(current), true)
assert.equal(order.accept({ stateSequence: 10, playing: false, positionSeconds: 0 }), false)
assert.equal(order.resolve({ stateSequence: 11, playing: false, positionSeconds: 0 }), current)
assert.equal(order.accept(current), false)
const seek = { stateSequence: 13, playing: true, positionSeconds: 0 }
assert.equal(order.resolve(seek), seek)
const pause = { stateSequence: 14, playing: false, positionSeconds: 1 }
assert.equal(order.resolve(pause), pause)
const newEpisode = { stateSequence: 15, playing: true, positionSeconds: 0 }
assert.equal(order.resolve(newEpisode), newEpisode)
assert.equal(createPlayerStateOrder().accept({ playing: true }), true)
console.log('PASS: stale events/polls, duplicate, real seek, pause, new episode, legacy state')
