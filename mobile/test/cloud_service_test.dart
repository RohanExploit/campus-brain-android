import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

import 'package:company_brain/llm/cloud_service.dart';

void main() {
  group('CloudConfig.fromJson', () {
    test('parses a well-formed config', () {
      final config = CloudConfig.fromJson(
        jsonEncode({'groq_api_key': 'gsk_test', 'groq_model': 'groq/compound-mini'}),
      );

      expect(config, isNotNull);
      expect(config!.apiKey, 'gsk_test');
      expect(config.model, 'groq/compound-mini');
    });

    test('returns null for malformed JSON', () {
      expect(CloudConfig.fromJson('{not valid json'), isNull);
    });

    test('returns null when groq_api_key is missing', () {
      expect(
        CloudConfig.fromJson(jsonEncode({'groq_model': 'groq/compound-mini'})),
        isNull,
      );
    });

    test('returns null when groq_model is missing', () {
      expect(
        CloudConfig.fromJson(jsonEncode({'groq_api_key': 'gsk_test'})),
        isNull,
      );
    });

    test('returns null when groq_api_key is empty', () {
      expect(
        CloudConfig.fromJson(
          jsonEncode({'groq_api_key': '', 'groq_model': 'groq/compound-mini'}),
        ),
        isNull,
      );
    });

    test('returns null when the JSON is not an object', () {
      expect(CloudConfig.fromJson(jsonEncode(['not', 'an', 'object'])), isNull);
    });

    test('returns null for an empty string', () {
      expect(CloudConfig.fromJson(''), isNull);
    });
  });

  group('CloudConfig.load', () {
    test('returns null when no config file exists anywhere (hermetic test env)', () async {
      // No path_provider platform channel is wired up in a plain
      // `flutter test` run, so both candidate directories resolve to
      // nothing and load() must degrade to null, not throw.
      final config = await CloudConfig.load();
      expect(config, isNull);
    });
  });

  group('CloudService.completeWithConfig', () {
    const config = CloudConfig(apiKey: 'gsk_test_key', model: 'groq/compound-mini');

    test('posts the expected request shape', () async {
      Uri? capturedUri;
      Map<String, String>? capturedHeaders;
      Map<String, dynamic>? capturedBody;

      final client = MockClient((request) async {
        capturedUri = request.url;
        capturedHeaders = request.headers;
        capturedBody = jsonDecode(request.body) as Map<String, dynamic>;
        return http.Response(
          jsonEncode({
            'choices': [
              {
                'message': {'content': 'Rs 1500/-'},
              },
            ],
          }),
          200,
        );
      });

      final service = CloudService(client: client);
      final answer = await service.completeWithConfig('What is the fee?', config);

      expect(answer, 'Rs 1500/-');
      expect(
        capturedUri,
        Uri.parse('https://api.groq.com/openai/v1/chat/completions'),
      );
      expect(capturedHeaders?['Authorization'], 'Bearer gsk_test_key');
      expect(capturedHeaders?['Content-Type'], 'application/json');
      expect(capturedBody?['model'], 'groq/compound-mini');
      expect(capturedBody?['messages'], [
        {'role': 'user', 'content': 'What is the fee?'},
      ]);
    });

    test('trims whitespace from the returned content', () async {
      final client = MockClient((request) async {
        return http.Response(
          jsonEncode({
            'choices': [
              {
                'message': {'content': '  Rs 1500/-  \n'},
              },
            ],
          }),
          200,
        );
      });

      final service = CloudService(client: client);
      final answer = await service.completeWithConfig('What is the fee?', config);

      expect(answer, 'Rs 1500/-');
    });

    test('throws CloudUnavailableException on a non-200 response', () async {
      final client = MockClient((request) async {
        return http.Response('server error', 500);
      });

      final service = CloudService(client: client);

      expect(
        () => service.completeWithConfig('What is the fee?', config),
        throwsA(isA<CloudUnavailableException>()),
      );
    });

    test('throws CloudUnavailableException when content is empty', () async {
      final client = MockClient((request) async {
        return http.Response(
          jsonEncode({
            'choices': [
              {
                'message': {'content': ''},
              },
            ],
          }),
          200,
        );
      });

      final service = CloudService(client: client);

      expect(
        () => service.completeWithConfig('What is the fee?', config),
        throwsA(isA<CloudUnavailableException>()),
      );
    });

    test('throws CloudUnavailableException when content is missing', () async {
      final client = MockClient((request) async {
        return http.Response(
          jsonEncode({
            'choices': [
              {'message': <String, dynamic>{}},
            ],
          }),
          200,
        );
      });

      final service = CloudService(client: client);

      expect(
        () => service.completeWithConfig('What is the fee?', config),
        throwsA(isA<CloudUnavailableException>()),
      );
    });

    test('throws CloudUnavailableException when choices is missing entirely', () async {
      final client = MockClient((request) async {
        return http.Response(jsonEncode({}), 200);
      });

      final service = CloudService(client: client);

      expect(
        () => service.completeWithConfig('What is the fee?', config),
        throwsA(isA<CloudUnavailableException>()),
      );
    });

    test('throws CloudUnavailableException on a network failure, never fabricates an answer',
        () async {
      final client = MockClient((request) async {
        throw const SocketExceptionStub();
      });

      final service = CloudService(client: client);

      expect(
        () => service.completeWithConfig('What is the fee?', config),
        throwsA(isA<CloudUnavailableException>()),
      );
    });
  });
}

/// A minimal stand-in for `dart:io`'s `SocketException` so the
/// network-failure test doesn't need to construct a real socket error --
/// `completeWithConfig` catches any thrown object from the client, not a
/// specific exception type, so any `Exception` demonstrates the behaviour.
class SocketExceptionStub implements Exception {
  const SocketExceptionStub();

  @override
  String toString() => 'SocketExceptionStub: connection failed';
}
