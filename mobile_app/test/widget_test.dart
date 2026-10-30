import 'package:flutter_test/flutter_test.dart';
import 'package:mobile_app/main.dart';

void main() {
  testWidgets('BetterWhisperApp smoke test', (WidgetTester tester) async {
    await tester.pumpWidget(const BetterWhisperApp());
    expect(find.text('Better Whisper'), findsOneWidget);
  });
}
