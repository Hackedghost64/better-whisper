import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'flow_core.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const BetterWhisperApp());
}

class BetterWhisperApp extends StatelessWidget {
  const BetterWhisperApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Better Whisper Flow',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        brightness: Brightness.dark,
        scaffoldBackgroundColor: const Color(0xFF0D0F14),
        colorScheme: const ColorScheme.dark(
          primary: Color(0xFF3B82F6),
          secondary: Color(0xFF10B981),
          surface: Color(0xFF161B22),
        ),
        useMaterial3: true,
      ),
      home: const FlowHomeScreen(),
    );
  }
}

class FlowHomeScreen extends StatefulWidget {
  const FlowHomeScreen({super.key});

  @override
  State<FlowHomeScreen> createState() => _FlowHomeScreenState();
}

class _FlowHomeScreenState extends State<FlowHomeScreen> with SingleTickerProviderStateMixin {
  final TextEditingController _rawController = TextEditingController();
  final TextEditingController _cleanedController = TextEditingController();

  bool _removeFillers = true;
  bool _dedupStutters = true;
  bool _handleCorrections = true;
  bool _isRecording = false;
  double _latencyMs = 0.0;

  late AnimationController _pulseController;

  @override
  void initState() {
    super.initState();
    _pulseController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1200),
    )..repeat(reverse: true);

    // Initial demo text showing raw vs Wispr Flow output
    _rawController.text = "Um, I I really think that phonon 2 is, uh, faster than whisper flow on android.";
    _processText();
  }

  @override
  void dispose() {
    _pulseController.dispose();
    _rawController.dispose();
    _cleanedController.dispose();
    super.dispose();
  }

  void _processText() {
    final raw = _rawController.text;
    if (raw.isEmpty) {
      _cleanedController.text = "";
      setState(() => _latencyMs = 0.0);
      return;
    }

    final stopwatch = Stopwatch()..start();
    final cleaned = BetterWhisperFlowCore.instance.cleanText(
      raw,
      removeFillers: _removeFillers,
      deduplicateStutters: _dedupStutters,
      handleSelfCorrections: _handleCorrections,
    );
    stopwatch.stop();

    setState(() {
      _cleanedController.text = cleaned;
      _latencyMs = stopwatch.elapsedMicroseconds / 1000.0;
    });
  }

  void _onPresetTapped(String text) {
    _rawController.text = text;
    _processText();
  }

  @override
  Widget build(BuildContext context) {
    final isCoreLoaded = BetterWhisperFlowCore.instance.isLoaded;

    return Scaffold(
      appBar: AppBar(
        title: Row(
          children: [
            Container(
              padding: const EdgeInsets.all(6),
              decoration: BoxDecoration(
                color: const Color(0xFF3B82F6).withValues(alpha: 0.2),
                borderRadius: BorderRadius.circular(8),
              ),
              child: const Icon(Icons.graphic_eq, color: Color(0xFF3B82F6), size: 22),
            ),
            const SizedBox(width: 10),
            const Text(
              "Better Whisper",
              style: TextStyle(fontWeight: FontWeight.bold, fontSize: 19),
            ),
            const SizedBox(width: 6),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
              decoration: BoxDecoration(
                color: const Color(0xFF10B981).withValues(alpha: 0.2),
                borderRadius: BorderRadius.circular(4),
              ),
              child: const Text(
                "FLOW OFFLINE",
                style: TextStyle(fontSize: 10, color: Color(0xFF10B981), fontWeight: FontWeight.bold),
              ),
            ),
          ],
        ),
        backgroundColor: const Color(0xFF12161F),
        elevation: 0,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Status Banner
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
              decoration: BoxDecoration(
                color: isCoreLoaded
                    ? const Color(0xFF10B981).withValues(alpha: 0.12)
                    : const Color(0xFFEF4444).withValues(alpha: 0.12),
                borderRadius: BorderRadius.circular(10),
                border: Border.all(
                  color: isCoreLoaded
                      ? const Color(0xFF10B981).withValues(alpha: 0.3)
                      : const Color(0xFFEF4444).withValues(alpha: 0.3),
                ),
              ),
              child: Row(
                children: [
                  Icon(
                    isCoreLoaded ? Icons.check_circle : Icons.error,
                    color: isCoreLoaded ? const Color(0xFF10B981) : const Color(0xFFEF4444),
                    size: 18,
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      isCoreLoaded
                          ? "Phonon-2 Flow Core Loaded (aarch64 Native)"
                          : "Native core library initializing...",
                      style: const TextStyle(fontSize: 12.5, fontWeight: FontWeight.w500),
                    ),
                  ),
                  if (_latencyMs > 0)
                    Text(
                      "${_latencyMs.toStringAsFixed(2)} ms",
                      style: const TextStyle(
                        fontSize: 12,
                        fontWeight: FontWeight.bold,
                        color: Color(0xFF3B82F6),
                      ),
                    ),
                ],
              ),
            ),
            const SizedBox(height: 18),

            // Push-to-Talk Mic Zone
            Center(
              child: GestureDetector(
                onTapDown: (_) {
                  setState(() => _isRecording = true);
                  HapticFeedback.heavyImpact();
                },
                onTapUp: (_) {
                  setState(() => _isRecording = false);
                  HapticFeedback.lightImpact();
                  _processText();
                },
                onTapCancel: () {
                  setState(() => _isRecording = false);
                },
                child: AnimatedBuilder(
                  animation: _pulseController,
                  builder: (context, child) {
                    final scale = _isRecording ? 1.0 + (_pulseController.value * 0.12) : 1.0;
                    return Transform.scale(
                      scale: scale,
                      child: Container(
                        width: 100,
                        height: 100,
                        decoration: BoxDecoration(
                          shape: BoxShape.circle,
                          gradient: LinearGradient(
                            colors: _isRecording
                                ? [const Color(0xFFEF4444), const Color(0xFFDC2626)]
                                : [const Color(0xFF3B82F6), const Color(0xFF2563EB)],
                            begin: Alignment.topLeft,
                            end: Alignment.bottomRight,
                          ),
                          boxShadow: [
                            BoxShadow(
                              color: (_isRecording ? const Color(0xFFEF4444) : const Color(0xFF3B82F6))
                                  .withValues(alpha: 0.4),
                              blurRadius: _isRecording ? 24 : 14,
                              spreadRadius: _isRecording ? 4 : 1,
                            ),
                          ],
                        ),
                        child: Icon(
                          _isRecording ? Icons.mic : Icons.mic_none,
                          color: Colors.white,
                          size: 42,
                        ),
                      ),
                    );
                  },
                ),
              ),
            ),
            const SizedBox(height: 10),
            Center(
              child: Text(
                _isRecording ? "Listening & Buffering PCM..." : "Hold to Dictate",
                style: TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.w600,
                  color: _isRecording ? const Color(0xFFEF4444) : Colors.white70,
                ),
              ),
            ),
            const SizedBox(height: 22),

            // Live Test Presets
            const Text(
              "QUICK TEST PHRASES",
              style: TextStyle(fontSize: 11, fontWeight: FontWeight.bold, color: Colors.white38),
            ),
            const SizedBox(height: 8),
            SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: Row(
                children: [
                  _buildPresetChip("Stutter + Filler", "Um, I I think that, uh, this works!"),
                  _buildPresetChip("Self Correction", "Call Alice at 2 PM no wait 3 PM."),
                  _buildPresetChip("Scratch That", "Send email to Bob scratch that message Alice."),
                  _buildPresetChip("Tech Jargon", "Testing phonon 2 against whisper flow on ios."),
                ],
              ),
            ),
            const SizedBox(height: 18),

            // Raw Transcript Field
            const Text(
              "RAW ASR TRANSCRIPTION",
              style: TextStyle(fontSize: 11, fontWeight: FontWeight.bold, color: Colors.white38),
            ),
            const SizedBox(height: 6),
            TextField(
              controller: _rawController,
              maxLines: 2,
              onChanged: (_) => _processText(),
              decoration: InputDecoration(
                filled: true,
                fillColor: const Color(0xFF161B22),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(10),
                  borderSide: BorderSide.none,
                ),
                contentPadding: const EdgeInsets.all(12),
                hintText: "Speak or type raw transcript...",
              ),
              style: const TextStyle(fontSize: 14, color: Colors.white70),
            ),
            const SizedBox(height: 16),

            // Cleaned "Wispr Flow" Result Field
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                const Text(
                  "WISPR FLOW CLEANED RESULT",
                  style: TextStyle(fontSize: 11, fontWeight: FontWeight.bold, color: Color(0xFF10B981)),
                ),
                IconButton(
                  icon: const Icon(Icons.copy, size: 18, color: Colors.white60),
                  tooltip: "Copy to Clipboard",
                  onPressed: () {
                    Clipboard.setData(ClipboardData(text: _cleanedController.text));
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(content: Text("Copied to clipboard!"), duration: Duration(seconds: 1)),
                    );
                  },
                ),
              ],
            ),
            TextField(
              controller: _cleanedController,
              readOnly: true,
              maxLines: 3,
              decoration: InputDecoration(
                filled: true,
                fillColor: const Color(0xFF161B22),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(10),
                  borderSide: const BorderSide(color: Color(0xFF10B981), width: 1),
                ),
                contentPadding: const EdgeInsets.all(12),
              ),
              style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w600, color: Colors.white),
            ),
            const SizedBox(height: 18),

            // Flow Cleaner Controls
            const Text(
              "FLOW CLEANER SETTINGS",
              style: TextStyle(fontSize: 11, fontWeight: FontWeight.bold, color: Colors.white38),
            ),
            const SizedBox(height: 6),
            Card(
              color: const Color(0xFF161B22),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
              child: Column(
                children: [
                  SwitchListTile(
                    title: const Text("Remove Fillers (um, uh, you know)", style: TextStyle(fontSize: 13.5)),
                    value: _removeFillers,
                    activeThumbColor: const Color(0xFF3B82F6),
                    onChanged: (val) {
                      setState(() => _removeFillers = val);
                      _processText();
                    },
                  ),
                  const Divider(height: 1, color: Colors.white10),
                  SwitchListTile(
                    title: const Text("Deduplicate Stutters", style: TextStyle(fontSize: 13.5)),
                    value: _dedupStutters,
                    activeThumbColor: const Color(0xFF3B82F6),
                    onChanged: (val) {
                      setState(() => _dedupStutters = val);
                      _processText();
                    },
                  ),
                  const Divider(height: 1, color: Colors.white10),
                  SwitchListTile(
                    title: const Text("Verbal Self-Corrections", style: TextStyle(fontSize: 13.5)),
                    value: _handleCorrections,
                    activeThumbColor: const Color(0xFF3B82F6),
                    onChanged: (val) {
                      setState(() => _handleCorrections = val);
                      _processText();
                    },
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildPresetChip(String label, String text) {
    return Padding(
      padding: const EdgeInsets.only(right: 8.0),
      child: ActionChip(
        label: Text(label, style: const TextStyle(fontSize: 12)),
        backgroundColor: const Color(0xFF1E2633),
        side: BorderSide.none,
        onPressed: () => _onPresetTapped(text),
      ),
    );
  }
}
