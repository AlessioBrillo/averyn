import { describe, expect, it } from 'vitest'
import type { SynapseFeatures, SynapseWindow } from './api'
import { featureSeries, panRange, windowBand, zoomRange } from './synapseSeries'

const features = (over: Partial<SynapseFeatures> = {}): SynapseFeatures => ({
  frames: 4,
  lostFrames: 0,
  unknownVersionFrames: 0,
  statusRecords: 0,
  elapsedMs: [0, 1000, 2000, 3000],
  heartRateBpm: [60, 61, null, 63],
  ppgQuality: [0.9, 0.9, 0.9, 0.9],
  motionArtifact: [0, 0, 0, 0],
  motionLevel: [0, 0, 0, 0],
  batteryPercent: [90, 90, 90, 90],
  stress: [null, null, null, null],
  rrLastMs: [null, null, null, null],
  rmssdMs: [null, null, null, null],
  sdnnMs: [null, null, null, null],
  gaps: [],
  ...over,
})

describe('featureSeries', () => {
  it('puts minutes on x and keeps a value the band did not give as null, never zero', () => {
    const s = featureSeries(features(), 'heartRateBpm')
    expect(s.x).toEqual([0, 1 / 60, 2 / 60, 3 / 60])
    expect(s.y).toEqual([60, 61, null, 63])
  })

  it('breaks the line across a gap instead of joining the frames on either side', () => {
    const s = featureSeries(features({ gaps: [{ tMs: 1000, durMs: 1000, reason: 'lost_frames', count: 1 }] }), 'heartRateBpm')
    expect(s.y).toEqual([60, 61, null, null, 63])
    expect(s.x).toEqual([...s.x].sort((a, b) => a - b))
    expect(new Set(s.x).size).toBe(s.x.length)
  })
})

const signalWindow = (): SynapseWindow => ({
  stream: 'ecg',
  fromMs: 0,
  toMs: 10_000,
  channels: [{ name: 'ecg', unit: 'uV' }],
  segments: [
    { t0Ms: 0, bucketMs: 1000, bucketSamples: 500, min: [[-10, 0]], max: [[1000, 5]] },
    { t0Ms: 5000, bucketMs: 1000, bucketSamples: 500, min: [[1]], max: [[2]] },
  ],
  gaps: [{ tMs: 2000, durMs: 3000, reason: 'lost_frames', count: 4 }],
})

describe('windowBand', () => {
  it('centres each bucket in seconds and keeps its minimum and maximum', () => {
    const b = windowBand(signalWindow(), 0)
    expect(b.x.slice(0, 2)).toEqual([0.5, 1.5])
    expect(b.max.slice(0, 2)).toEqual([1000, 5])
    expect(b.min.slice(0, 2)).toEqual([-10, 0])
  })

  it('ends each segment with a null so the signal is never drawn across a gap', () => {
    const b = windowBand(signalWindow(), 0)
    expect(b.x).toEqual([0.5, 1.5, 2, 5.5, 6])
    expect(b.min[2]).toBeNull()
    expect(b.max[2]).toBeNull()
    expect(b.min[4]).toBeNull()
    expect(b.x).toEqual([...b.x].sort((a, c) => a - c))
  })

  it('is empty for a channel that does not exist', () => {
    expect(windowBand(signalWindow(), 3).x).toEqual([])
  })
})

describe('zoomRange and panRange', () => {
  const total = 60_000

  it('zooms in around the middle and out again, inside the recording', () => {
    const whole = { fromMs: 0, toMs: total }
    const inner = zoomRange(whole, total, 0.25)
    expect(inner).toEqual({ fromMs: 22_500, toMs: 37_500 })
    expect(zoomRange(inner, total, 4)).toEqual(whole)
    expect(zoomRange(whole, total, 4)).toEqual(whole)
  })

  it('never zooms in below a few samples of a 500 Hz signal', () => {
    let r = { fromMs: 0, toMs: total }
    for (let i = 0; i < 20; i++) r = zoomRange(r, total, 0.25)
    expect(r.toMs - r.fromMs).toBeGreaterThanOrEqual(20)
  })

  it('pans by a share of the span and stops at both ends of the recording', () => {
    const r = { fromMs: 10_000, toMs: 20_000 }
    expect(panRange(r, total, 0.5)).toEqual({ fromMs: 15_000, toMs: 25_000 })
    expect(panRange(r, total, -5)).toEqual({ fromMs: 0, toMs: 10_000 })
    expect(panRange(r, total, 50)).toEqual({ fromMs: 50_000, toMs: 60_000 })
  })
})
