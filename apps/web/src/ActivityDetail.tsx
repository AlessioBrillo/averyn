import { useEffect, useRef, useState } from 'react'
import type { GeoJSONSourceSpecification, Map as MapLibreMap } from 'maplibre-gl'
import { ApiError, deleteActivity, getActivity, getTrack } from './api'
import type { ActivitySummary, Track } from './api'
import { formatDistance, formatDuration, formatRate } from './format'

type Loaded = { activity: ActivitySummary; track: Track }

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

  const line = loaded?.track.geometry
  useEffect(() => {
    if (!loaded || !line || !mapElement.current) return
    let map: MapLibreMap | undefined
    let disposed = false
    const lons = line.coordinates.map((c) => c[0])
    const lats = line.coordinates.map((c) => c[1])
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
    [rate.label, rate.value],
    ['GPS quality', activity.qualityGrade.toLowerCase()],
    ['Unreadable records', String(activity.droppedRecords)],
  ]

  return (
    <>
      <h2>
        {new Date(activity.startedAt).toLocaleString()} · {activity.sport.toLowerCase()}
      </h2>
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
