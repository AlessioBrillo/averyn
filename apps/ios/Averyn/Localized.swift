import Foundation

/// A text of the app in the language of the phone (issue #69). The English text is the key in `Localizable.xcstrings`;
/// a text without a translation is shown in English. `%@` is filled from `args`, in order.
///
/// A text that SwiftUI shows from a literal (`Text("Pause")`, `Button("Stop")`) is looked up by SwiftUI itself; this is
/// for the texts that are built first, as a `String`, or that carry numbers.
func tr(_ key: String, _ args: String...) -> String {
    let format = NSLocalizedString(key, comment: "")
    if args.isEmpty { return format }
    let objects: [CVarArg] = args.map { NSString(string: $0) }
    return String(format: format, arguments: objects)
}
