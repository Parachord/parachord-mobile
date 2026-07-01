import AVFoundation
import UIKit

/// #322 diagnostics — logs every AVAudioSession event (+ app foreground/background
/// transitions and the session state at each) so we can see, on a real device,
/// exactly what interrupts Apple Music / Spotify when the app backgrounds. The
/// simulator can't exercise background audio, so this is how we get the truth.
///
/// READ-ONLY: it only installs observers and logs — it changes no behavior.
///
/// Capture: Console.app → select the device → filter on `PCAUDIO`. Reproduce
/// (play Apple Music → lock/background; play Spotify → lock/background; then
/// re-open Parachord) and send the `PCAUDIO` lines.
enum AudioSessionDiagnostics {
    private static var installed = false

    static func install() {
        guard !installed else { return }
        installed = true
        let nc = NotificationCenter.default

        nc.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: nil) { note in
            let info = note.userInfo
            let typeRaw = info?[AVAudioSessionInterruptionTypeKey] as? UInt ?? 99
            let type = AVAudioSession.InterruptionType(rawValue: typeRaw)
            var msg = "INTERRUPTION type=" + (type == .began ? "began" : type == .ended ? "ended" : "?(\(typeRaw))")
            if let optRaw = info?[AVAudioSessionInterruptionOptionKey] as? UInt {
                let shouldResume = AVAudioSession.InterruptionOptions(rawValue: optRaw).contains(.shouldResume)
                msg += " options=" + (shouldResume ? "shouldResume" : "none")
            }
            if #available(iOS 14.5, *), let reasonRaw = info?[AVAudioSessionInterruptionReasonKey] as? UInt {
                // 0=default, 1=appWasSuspended, 2=builtInMicMuted, 3=routeDisconnected(≈)
                msg += " reason=\(reasonRaw)"
            }
            log(msg)
        }

        nc.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: nil) { note in
            let raw = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt ?? 99
            log("ROUTE_CHANGE reason=\(raw)")
        }

        nc.addObserver(forName: AVAudioSession.silenceSecondaryAudioHintNotification, object: nil, queue: nil) { note in
            let raw = note.userInfo?[AVAudioSessionSilenceSecondaryAudioHintTypeKey] as? UInt ?? 99
            log("SECONDARY_AUDIO_HINT type=\(raw)")   // 0=end (others resumed), 1=begin (others silenced)
        }

        nc.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: nil, queue: nil) { _ in
            log("MEDIA_SERVICES_RESET")
        }

        nc.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: nil) { _ in
            logState("DID_ENTER_BACKGROUND")
        }
        nc.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: nil) { _ in
            logState("WILL_ENTER_FOREGROUND")
        }
        nc.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: nil) { _ in
            logState("DID_BECOME_ACTIVE")
        }
        nc.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: nil) { _ in
            logState("WILL_RESIGN_ACTIVE")
        }

        logState("INSTALLED")
    }

    /// Log the current session state — the tag ties it to the triggering event
    /// (engine change, background/foreground). Also called from the coordinator
    /// on each engine switch.
    static func logState(_ tag: String) {
        let s = AVAudioSession.sharedInstance()
        log("\(tag) category=\(s.category.rawValue) options=\(s.categoryOptions.rawValue) otherAudioPlaying=\(s.isOtherAudioPlaying) secondaryHint=\(s.secondaryAudioShouldBeSilencedHint)")
    }

    private static func log(_ m: String) { NSLog("PCAUDIO: \(m)") }
}
