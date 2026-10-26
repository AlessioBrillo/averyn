@preconcurrency import CoreLocation
@preconcurrency import HealthKit
@preconcurrency import Shared

/// Saves a finished activity to Health (HealthKit), at the user's request (TDD-0014): the workout with its route,
/// the heart rate that was recorded and the distance. Averyn only writes; it reads nothing from Health.
///
/// HealthKit has no upsert: the workout carries the id of the activity as its external id, and a workout that
/// already has it is not written again.
final class HealthKitExporter: @unchecked Sendable {
    enum Outcome {
        case saved
        case alreadySaved
    }

    enum Failure: Error {
        /// Health is not available on this device (some iPads).
        case unavailable
        /// The user did not allow Averyn to write a workout.
        case denied
        /// The activity is not finished, or has no route.
        case nothingToSave
        case notSaved
    }

    private let healthStore = HKHealthStore()

    static var isAvailable: Bool { HKHealthStore.isHealthDataAvailable() }

    /// The types Averyn asks to write. Nothing is asked to be read.
    private var typesToShare: Set<HKSampleType> {
        [
            HKObjectType.workoutType(),
            HKSeriesType.workoutRoute(),
            HKQuantityType(.heartRate),
            HKQuantityType(.distanceWalkingRunning),
            HKQuantityType(.distanceCycling),
        ]
    }

    func save(store: ActivityStore, activityId: String) async throws -> Outcome {
        guard HKHealthStore.isHealthDataAvailable() else { throw Failure.unavailable }
        guard let workout = store.workoutExport(activityId: activityId) else { throw Failure.nothingToSave }

        try await healthStore.requestAuthorization(toShare: typesToShare, read: [])
        guard healthStore.authorizationStatus(for: HKObjectType.workoutType()) == .sharingAuthorized else {
            throw Failure.denied
        }
        if try await alreadySaved(workout.activityId) { return .alreadySaved }

        let start = Date(timeIntervalSince1970: Double(workout.startMs) / 1000)
        let end = Date(timeIntervalSince1970: Double(workout.endMs) / 1000)
        let configuration = HKWorkoutConfiguration()
        configuration.activityType = activityType(workout.sport)
        configuration.locationType = .outdoor
        let builder = HKWorkoutBuilder(healthStore: healthStore, configuration: configuration, device: .local())

        try await builder.beginCollection(at: start)
        var samples: [HKSample] = []
        if workout.distanceM > 0 {
            samples.append(
                HKQuantitySample(
                    type: distanceType(workout.sport),
                    quantity: HKQuantity(unit: .meter(), doubleValue: workout.distanceM),
                    start: start,
                    end: end
                )
            )
        }
        let beatsPerMinute = HKUnit.count().unitDivided(by: .minute())
        for reading in workout.heartRate {
            let time = Date(timeIntervalSince1970: Double(reading.timeMs) / 1000)
            guard time >= start, time <= end else { continue }
            samples.append(
                HKQuantitySample(
                    type: HKQuantityType(.heartRate),
                    quantity: HKQuantity(unit: beatsPerMinute, doubleValue: Double(reading.bpm)),
                    start: time,
                    end: time
                )
            )
        }
        if !samples.isEmpty { try await builder.addSamples(samples) }
        try await builder.addMetadata([HKMetadataKeyExternalUUID: workout.activityId])
        try await builder.endCollection(at: end)
        guard let saved = try await builder.finishWorkout() else { throw Failure.notSaved }

        let route = HKWorkoutRouteBuilder(healthStore: healthStore, device: .local())
        let locations = workout.route.map { point in
            CLLocation(
                coordinate: CLLocationCoordinate2D(latitude: point.latitude, longitude: point.longitude),
                altitude: point.altitudeM?.doubleValue ?? 0,
                horizontalAccuracy: point.accuracyM?.doubleValue ?? 10,
                // A negative vertical accuracy says the altitude is not valid.
                verticalAccuracy: point.altitudeM == nil ? -1 : 10,
                timestamp: Date(timeIntervalSince1970: Double(point.timeMs) / 1000)
            )
        }
        try await route.insertRouteData(locations)
        _ = try await route.finishRoute(with: saved, metadata: nil)
        return .saved
    }

    private func alreadySaved(_ activityId: String) async throws -> Bool {
        let predicate = HKQuery.predicateForObjects(
            withMetadataKey: HKMetadataKeyExternalUUID,
            allowedValues: [activityId]
        )
        let query = HKSampleQueryDescriptor(predicates: [.workout(predicate)], sortDescriptors: [], limit: 1)
        return try await !query.result(for: healthStore).isEmpty
    }

    private func activityType(_ sport: Sport) -> HKWorkoutActivityType {
        switch sport {
        case .ride: return .cycling
        case .walk: return .walking
        case .hike: return .hiking
        default: return .running
        }
    }

    private func distanceType(_ sport: Sport) -> HKQuantityType {
        sport == .ride ? HKQuantityType(.distanceCycling) : HKQuantityType(.distanceWalkingRunning)
    }
}
