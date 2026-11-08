import { useEffect, useMemo, useState } from 'react'
import { ApiError, getSynapse, getSynapseWindow } from './api'
import type { SynapseGap, SynapseSummary, SynapseWindow } from './api'
import { ACCENT, axisOptions, Chart } from './Analysis'
import type { Prepare } from './Analysis'
import { featureSeries, panRange, windowBand, zoomRange } from './synapseSeries'
import type { FeatureField, Range } from './synapseSeries'
import { formatDuration } from './format'
import { t } from './i18n'

/** Buckets asked per segment: about one per pixel of a wide chart. */
const WINDOW_POINTS = 1200

function streamLabel(stream: string): string {
  switch (stream) {
    case 'ecg':
      return t('ECG (band)')
    case 'ppg':
      return t('Pulse wave (band)')
    case 'acc':
      return t('Accelerometer')
    case 'gyro':
      return t('Gyroscope')
    case 'mag':
      return t('Magnetometer')
    default:
      return t('Power')
  }
}

function gapReason(gap: SynapseGap): string {
  switch (gap.reason) {
    case 'lost_frames':
      return t('Frames lost on the link')
    case 'device_dropped':
      return t('Samples dropped by the band')
    default:
      return t('The band restarted')
  }
}

/** The gaps of a stream or of the derived values, as a table: a chart alone says nothing to a screen reader. */
function GapsTable({ gaps }: { gaps: SynapseGap[] }) {
  if (gaps.length === 0) return <p className="small">{t('No gaps: the band sent everything the phone received.')}</p>
  return (
    <div className="table-scroll">
      <table>
        <caption>{t('Gaps in the data')}</caption>
        <thead>
          <tr>
            <th scope="col">{t('From')}</th>
            <th scope="col">{t('Length')}</th>
            <th scope="col">{t('Reason')}</th>
            <th scope="col">{t('Count')}</th>
          </tr>
        </thead>
        <tbody>
          {gaps.map((g, i) => (
            <tr key={`${g.tMs}-${i}`}>
              <td>{formatDuration(g.tMs)}</td>
              <td>{formatDuration(g.durMs)}</td>
              <td>{gapReason(g)}</td>
              <td>{g.count}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function FeatureChart({
  summary,
  field,
  label,
  unit,
}: {
  summary: SynapseSummary
  field: FeatureField
  label: string
  unit: string
}) {
  const features = summary.features
  const prepare = useMemo<Prepare>(
    () => (colors) => {
      const series = features ? featureSeries(features, field) : { x: [], y: [] }
      return {
        data: [series.x, series.y],
        options: {
          width: 600,
          height: 200,
          legend: { show: false },
          cursor: { drag: { x: false, y: false } },
          axes: [axisOptions(colors, 'min'), axisOptions(colors, unit)],
          series: [{}, { spanGaps: false, stroke: ACCENT, width: 2 }],
        },
      }
    },
    [features, field, unit],
  )
  if (!features) return null
  return (
    <>
      <h5>{label}</h5>
      <Chart prepare={prepare} label={label} />
    </>
  )
}

/** A band for one channel of a raw signal: the minimum and the maximum of every bucket, broken at every gap. */
function SignalChart({ window, channel }: { window: SynapseWindow; channel: number }) {
  const unit = window.channels[channel]?.unit ?? ''
  const prepare = useMemo<Prepare>(
    () => (colors) => {
      const band = windowBand(window, channel)
      return {
        data: [band.x, band.max, band.min],
        options: {
          width: 600,
          height: 200,
          legend: { show: false },
          cursor: { drag: { x: false, y: false } },
          axes: [axisOptions(colors, 's'), axisOptions(colors, unit)],
          bands: [{ series: [1, 2], fill: 'rgba(228, 87, 46, 0.25)' }],
          series: [
            {},
            { spanGaps: false, stroke: ACCENT, width: 1 },
            { spanGaps: false, stroke: ACCENT, width: 1 },
          ],
        },
      }
    },
    [window, channel, unit],
  )
  return (
    <Chart
      prepare={prepare}
      label={t('{signal}, {channel}, in {unit}', {
        signal: streamLabel(window.stream),
        channel: window.channels[channel]?.name ?? '',
        unit,
      })}
    />
  )
}

/**
 * What a Synapse band recorded during the activity (ADR-0030): the values it derived and, with the user's consent, its
 * raw signals. Everything is imported data and says so. The page only draws what the server gives it: units, gaps and
 * the choice of how many buckets to send are the server's; a gap is a break in the line and a row in a table, never a
 * zero and never a straight line across it.
 */
export function SynapseSignals({ activityId }: { activityId: string }) {
  const [summary, setSummary] = useState<SynapseSummary | null>(null)
  const [failed, setFailed] = useState(false)
  const [stream, setStream] = useState<string | null>(null)
  const [channel, setChannel] = useState(0)
  const [range, setRange] = useState<Range | null>(null)
  const [loadedWindow, setLoadedWindow] = useState<SynapseWindow | null>(null)
  const [windowFailed, setWindowFailed] = useState(false)

  useEffect(() => {
    let cancelled = false
    getSynapse(activityId).then(
      (s) => {
        if (cancelled) return
        setSummary(s)
        const first = s?.raw?.streams[0]
        if (s?.raw && first && s.raw.durationMs > 0) {
          setStream(first.stream)
          setRange({ fromMs: 0, toMs: s.raw.durationMs })
        }
      },
      (e: unknown) => {
        if (!cancelled && !(e instanceof ApiError && e.status === 401)) setFailed(true)
      },
    )
    return () => {
      cancelled = true
    }
  }, [activityId])

  useEffect(() => {
    if (!stream || !range) return
    let cancelled = false
    setWindowFailed(false)
    getSynapseWindow(activityId, stream, range.fromMs, range.toMs, WINDOW_POINTS).then(
      (w) => {
        if (!cancelled) setLoadedWindow(w)
      },
      () => {
        if (!cancelled) setWindowFailed(true)
      },
    )
    return () => {
      cancelled = true
    }
  }, [activityId, stream, range])

  if (failed) return <p role="alert">{t('Could not load the band signals. Try again.')}</p>
  if (!summary) return null

  const raw = summary.raw
  const total = raw?.durationMs ?? 0
  const current = raw?.streams.find((s) => s.stream === stream)

  return (
    <section aria-label={t('Band signals')}>
      <h3>
        {t('Band signals')} <small>· {t('via Synapse')}</small>
      </h3>
      <p className="small">
        {t(
          'These signals come from a Synapse band, not from Averyn: they are shown as the band measured them. They are wellness signals, not a medical record and not a diagnosis.',
        )}
      </p>

      {summary.features && (
        <>
          <h4>{t('Derived values')}</h4>
          <FeatureChart summary={summary} field="heartRateBpm" label={t('Heart rate (band)')} unit="bpm" />
          <FeatureChart summary={summary} field="ppgQuality" label={t('Signal quality')} unit="0–1" />
          <FeatureChart summary={summary} field="motionArtifact" label={t('Motion artifact')} unit="0–1" />
          <p className="small">
            {t('{frames} readings, {lost} lost on the link.', {
              frames: summary.features.frames,
              lost: summary.features.lostFrames,
            })}
          </p>
          <GapsTable gaps={summary.features.gaps} />
        </>
      )}

      {raw && stream && range && (
        <>
          <h4>{t('Raw signals')}</h4>
          <p>
            <label>
              {t('Signal')}{' '}
              <select
                value={stream}
                onChange={(e) => {
                  setStream(e.target.value)
                  setChannel(0)
                  setLoadedWindow(null)
                }}
              >
                {raw.streams.map((s) => (
                  <option key={s.stream} value={s.stream}>
                    {streamLabel(s.stream)}
                  </option>
                ))}
              </select>
            </label>{' '}
            {current && current.channels.length > 1 && (
              <label>
                {t('Channel')}{' '}
                <select value={channel} onChange={(e) => setChannel(Number(e.target.value))}>
                  {current.channels.map((c, i) => (
                    <option key={c.name} value={i}>
                      {c.name} ({c.unit})
                    </option>
                  ))}
                </select>
              </label>
            )}
          </p>
          <p>
            <button onClick={() => setRange(zoomRange(range, total, 0.25))}>{t('Zoom in')}</button>{' '}
            <button onClick={() => setRange(zoomRange(range, total, 4))}>{t('Zoom out')}</button>{' '}
            <button onClick={() => setRange(panRange(range, total, -0.5))}>{t('Earlier')}</button>{' '}
            <button onClick={() => setRange(panRange(range, total, 0.5))}>{t('Later')}</button>{' '}
            <button onClick={() => setRange({ fromMs: 0, toMs: total })}>{t('Whole recording')}</button>
          </p>
          <p className="small" role="status">
            {t('Showing {from} to {to} of {total}.', {
              from: formatDuration(range.fromMs),
              to: formatDuration(range.toMs),
              total: formatDuration(total),
            })}
          </p>
          {windowFailed && <p role="alert">{t('Could not load that part of the signal. Try again.')}</p>}
          {loadedWindow && loadedWindow.stream === stream && <SignalChart window={loadedWindow} channel={channel} />}

          <div className="table-scroll">
            <table>
              <caption>{t('What the phone received, per signal')}</caption>
              <thead>
                <tr>
                  <th scope="col">{t('Signal')}</th>
                  <th scope="col">{t('Frames')}</th>
                  <th scope="col">{t('Lost frames')}</th>
                  <th scope="col">{t('Samples dropped by the band')}</th>
                  <th scope="col">{t('Restarts')}</th>
                </tr>
              </thead>
              <tbody>
                {raw.streams.map((s) => (
                  <tr key={s.stream}>
                    <th scope="row">{streamLabel(s.stream)}</th>
                    <td>{s.frames}</td>
                    <td>{s.lostFrames}</td>
                    <td>{s.deviceDropped}</td>
                    <td>{s.restarts}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {current && <GapsTable gaps={current.gaps} />}
        </>
      )}
    </section>
  )
}
