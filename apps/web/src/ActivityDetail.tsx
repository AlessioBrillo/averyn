import { useEffect, useRef, useState } from 'react'
import type { GeoJSONSourceSpecification, Map as MapLibreMap } from 'maplibre-gl'
import { Analysis } from './Analysis'
import { ApiError, deleteActivity, getActivity, getAnalysis, getTrack } from './api'
import type { ActivityAnalysis, ActivitySummary, Track } from './api'
import { formatDistance, formatDuration, formatQuality, formatRate } from './format'

type Loaded = { activity: ActivitySummary; track: Track }

/**
 * The analysis of the activity that is open. It carries the id of the activity it belongs to: the state of a
 * previous activity must never show up under the next one.
 */
type AnalysisState =
  | { id: string; value: ActivityAnalysis }
  | { id: string; error: 'busy' | 'unavailable' }

/** `onUnauthorized` must be stable (useCallback): it is an effect dependency. */
export function ActivityDetail({
  id,
  mapStyleUrl,
  onUnauthorized,
  onDeleted,
}: {
  id: string
  mapStyleUrl: string
  onUnauthorized: () => void
  onDeleted: () => void
}) {
  const [loaded, setLoaded] = useState<Loaded | null>(null)
  const [error, setError] = useState<'notFound' | 'failed' | null>(null)
  const [deleteFailed, setDeleteFailed] = useState(false)
  // The analysis is a separate call: an activity without an analysis is still an activity.
  const [analysisState, setAnalysisState] = useState<AnalysisState | null>(null)
  const mapElement = useRef<HTMLDivElement>(null)

  useEffect(() => {
    let current = true
    Promise.all([getActivity(id), getTrack(id)])
      .then(([activity, track]) => current && setLoaded({ activity, track }))
      .catch((e: unknown) => {
        if (!current) return
        if (e instanceof ApiError && e.status === 401) onUnauthorized()
        else setError(e instanceof ApiError && e.status === 404 ? 'notFound' : 'failed')
      })
    return () => {
      current = false
    }
  }, [id, onUnauthorized])

  useEffect(() => {
    let current = true
    // The analysis of an activity that was stored before the analysis existed is computed by the server when it
    // is read, and the server can be busy: a 503 means "come back", not "there is no analysis".
    const load = (attempt: number) => {
      getAnalysis(id).then(
        (value) => current && setAnalysisState({ id, value }),
        (e: unknown) => {
          if (!current) return
          if (e instanceof ApiError && e.status === 401) {
            onUnauthorized()
            return
          }
          if (e instanceof ApiError && e.status === 503 && attempt < 2) {
            window.setTimeout(() => current && load(attempt + 1), 2000)
            return
          }
          setAnalysisState({
            id,
            error: e instanceof ApiError && e.status === 503 ? 'busy' : 'unavailable',
          })
        },
      )
    }
    load(0)
    return () => {
      current = false
    }
  }, [id, onUnauthorized])

  const analysis = analysisState?.id === id && 'value' in analysisState ? analysisState.value : null
  const analysisError = analysisState?.id === id && 'error' in analysisState ? analysisState.error : null

  const line = loaded?.track.geometry
  useEffect(() => {
    if (!loaded || !line || !mapElement.current) return
    let map: MapLibreMap | undefined
    let disposed = false
    const lons = line.coordinates.flatMap((segment) => segment.map((point) => point[0]))
    const lats = line.coordinates.flatMap((segment) => segment.map((point) => point[1]))
    // maplibre-gl needs a browser (WebGL): load it on demand, which also keeps it out of the first bundle.
    // Its worker is a separate module that a bundler does not emit by itself: ship it as one file and point at it.
    void Promise.all([import('maplibre-gl'), import('maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url')]).then(
      ([maplibregl, worker]) => {
      maplibregl.setWorkerUrl(worker.default)
      if (disposed || !mapElement.current) return
      const created = new maplibregl.Map({
        container: mapElement.current,
        style: mapStyleUrl,
        bounds: [
          [Math.min(...lons), Math.min(...lats)],
          [Math.max(...lons), Math.max(...lats)],
        ],
        fitBoundsOptions: { padding: 40 },
      })
      map = created
      // As soon as the style is in, not on `load`: that waits for every tile and can come late (or never) on a slow
      // or failing tile host, and the track must not depend on the basemap.
      const addTrack = () => {
        if (created.getSource('track') || !created.isStyleLoaded()) return
        created.addSource('track', { type: 'geojson', data: loaded.track as GeoJSONSourceSpecification['data'] })
        created.addLayer({
          id: 'track',
          type: 'line',
          source: 'track',
          layout: { 'line-join': 'round', 'line-cap': 'round' },
          paint: { 'line-color': '#e4572e', 'line-width': 4 },
        })
      }
      created.on('styledata', addTrack)
      // A basemap that cannot be fetched (tile host down, blocked, offline) must not take the track with it:
      // fall back to an empty style once.
      let fellBack = false
      created.on('error', () => {
        if (fellBack || created.isStyleLoaded()) return
        fellBack = true
        created.setStyle({ version: 8, sources: {}, layers: [] })
      })
      },
    )
    return () => {
      disposed = true
      map?.remove()
    }
  }, [loaded, line, mapStyleUrl])

  function remove() {
    if (!window.confirm('Delete this activity and its recorded data for good? This cannot be undone.')) return
    setDeleteFailed(false)
    deleteActivity(id).then(onDeleted, (e: unknown) => {
      if (e instanceof ApiError && e.status === 401) onUnauthorized()
      else setDeleteFailed(true)
    })
  }

  if (error === 'notFound') return <p role="alert">Activity not found.</p>
  if (error) return <p role="alert">Could not load this activity.</p>
  if (!loaded) return <p>Loading…</p>

  const { activity } = loaded
  const rate = formatRate(activity.sport, activity)
  const metrics: [string, string][] = [
    ['Distance', formatDistance(activity.distanceM)],
    ['Moving time', formatDuration(activity.movingMs)],
    ['Elapsed time', formatDuration(activity.elapsedMs)],
    ['Paused', formatDuration(activity.pausedMs)],
    ['Stationary', formatDuration(activity.stationaryMs)],
    [rate.label, rate.value],
    ['GPS quality', formatQuality(activity.qualityGrade)],
    ['Unreadable records', String(activity.droppedRecords)],
    ...(activity.avgHrBpm > 0
      ? [['Heart rate', `${activity.avgHrBpm} bpm avg · ${activity.maxHrBpm} bpm max`] as [string, string]]
      : [] as [string, string][]),
  ]

  return (
    <>
      <h2>
        {new Date(activity.startedAt).toLocaleString()} · {activity.sport.toLowerCase()}
      </h2>
      {activity.source !== 'device' && (
        <p role="status">Imported from a {activity.source.toUpperCase()} file. The file is kept as it was uploaded.</p>
      )}
      {activity.finalState === 'FAILED' && (
        <p role="status">Recording ended unexpectedly; this is what was captured before it stopped.</p>
      )}
      {line ? <div ref={mapElement} className="map" /> : <p>No track: fewer than two usable GPS points.</p>}
      <dl className="metrics">
        {metrics.map(([label, value]) => (
          <div key={label}>
            <dt>{label}</dt>
            <dd>{value}</dd>
          </div>
        ))}
      </dl>
      {analysis && <Analysis analysis={analysis} sport={activity.sport} />}
      {analysisError === 'busy' && (
        <p role="status">
          The server is busy: the analysis of this activity is being prepared. Reload in a moment.
        </p>
      )}
      {analysisError === 'unavailable' && (
        <p role="status">The analysis of this activity is not available. The numbers above are still there.</p>
      )}
      <p className="small">
        Algorithms: {activity.metricsAlgorithmVersion}, {activity.qualityAlgorithmVersion}
      </p>
      {deleteFailed && <p role="alert">Could not delete this activity. Try again.</p>}
      <p>
        <button onClick={remove}>Delete activity</button>
      </p>
    </>
  )
}
