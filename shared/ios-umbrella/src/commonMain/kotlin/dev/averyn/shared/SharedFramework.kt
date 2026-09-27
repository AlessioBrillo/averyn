package dev.averyn.shared

// A pure re-export module has no code of its own, but Kotlin/Native's framework-link task is
// skipped as NO-SOURCE when a target has zero source files — which then makes Xcode's
// FrameworkCopy step fail because no Shared.framework was ever produced. This constant exists
// only to give the target a source file so the framework actually gets built.
internal const val SHARED_FRAMEWORK_MARKER = "averyn-shared"
