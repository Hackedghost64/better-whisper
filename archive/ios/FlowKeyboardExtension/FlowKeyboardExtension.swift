import UIKit
import AVFoundation

class FlowKeyboardExtension: UIInputViewController {

    private var audioEngine = AVAudioEngine()
    private var isRecording = false
    private var micButton: UIButton!
    private var tempAudioURL: URL?

    override func viewDidLoad() {
        super.viewDidLoad()
        setupUI()
    }

    private func setupUI() {
        view.backgroundColor = UIColor(white: 0.1, alpha: 1.0)

        micButton = UIButton(type: .system)
        micButton.setTitle("Hold to Speak", for: .normal)
        micButton.setTitleColor(.white, for: .normal)
        micButton.titleLabel?.font = UIFont.systemFont(ofSize: 18, weight: .semibold)
        micButton.backgroundColor = UIColor(red: 0.15, green: 0.5, blue: 1.0, alpha: 1.0)
        micButton.layer.cornerRadius = 24
        micButton.translatesAutoresizingMaskIntoConstraints = false

        // Push-to-talk gesture
        micButton.addTarget(self, action: #selector(didPressMicDown), for: .touchDown)
        micButton.addTarget(self, action: #selector(didReleaseMic), for: [.touchUpInside, .touchUpOutside])

        view.addSubview(micButton)

        NSLayoutConstraint.activate([
            micButton.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            micButton.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            micButton.widthAnchor.constraint(equalToConstant: 200),
            micButton.heightAnchor.constraint(equalToConstant: 54)
        ])
    }

    @objc private func didPressMicDown() {
        guard !isRecording else { return }
        isRecording = true
        micButton.setTitle("Listening...", for: .normal)
        micButton.backgroundColor = UIColor.systemRed

        // Start local 16kHz audio capture
        startAudioRecording()
    }

    @objc private func didReleaseMic() {
        guard isRecording else { return }
        isRecording = false
        micButton.setTitle("Transcribing...", for: .normal)
        micButton.backgroundColor = UIColor.systemGray

        stopAudioRecordingAndInject()
    }

    private func startAudioRecording() {
        // Record PCM file to temporary container
        let tempDir = FileManager.default.temporaryDirectory
        tempAudioURL = tempDir.appendingPathComponent("input_dictation.wav")
        // Start engine tap...
    }

    private func stopAudioRecordingAndInject() {
        guard let audioURL = tempAudioURL else {
            resetMicButton()
            return
        }

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else { return }

            // Extract context before cursor from the target application (e.g. WhatsApp, Slack, Notes)
            let contextBefore = self.textDocumentProxy.documentContextBeforeInput

            do {
                let cleanedText = try PhononBridge.shared.transcribeAndClean(
                    audioURL: audioURL,
                    contextBeforeCursor: contextBefore
                )

                DispatchQueue.main.async {
                    // Direct system-wide text injection
                    self.textDocumentProxy.insertText(cleanedText)
                    self.resetMicButton()
                }
            } catch {
                DispatchQueue.main.async {
                    self.resetMicButton()
                }
            }
        }
    }

    private func resetMicButton() {
        micButton.setTitle("Hold to Speak", for: .normal)
        micButton.backgroundColor = UIColor(red: 0.15, green: 0.5, blue: 1.0, alpha: 1.0)
    }
}
