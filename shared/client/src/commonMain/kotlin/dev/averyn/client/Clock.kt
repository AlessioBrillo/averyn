package dev.averyn.client

/**
 * The wall-clock time of the platform, in epoch milliseconds.
 *
 * The client needs it for two things only: when a copy of the server was read, and when the server asked to
 * be called back later. It is never used to order the activities (the server does that).
 */
internal expect fun currentTimeMillis(): Long
