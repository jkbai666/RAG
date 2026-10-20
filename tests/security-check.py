"""Scan frontend assets/logs for private env and YAML credentials; never print values.

The YAML source and backend JAR are intentional server-side credential locations,
not leak targets. Do not share either after filling in real keys. --self-test uses
only temporary fixtures and does not read this machine's private configuration.
"""
import argparse
import json
import os
from pathlib import Path
import re
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
SECRET_NAMES = ('DB_PASSWORD', 'DB_ROOT_PASSWORD', 'CHAT_API_KEY', 'EMBEDDING_API_KEY')
# Exact documented examples only: do not exempt arbitrary short/test-looking keys.
EXAMPLES = {
    'local-not-a-secret',
    'replace-with-a-long-random-password',
    'replace-with-a-different-long-random-password',
    'replace-with-your-api-key', 'your-api-key', 'YOUR_API_KEY', 'replace-me',
}


class SafeCheckError(Exception):
    """Messages must contain locations/reasons only, never input values."""


def read_environment(path):
    values = {}
    if path.is_file():
        for line in path.read_text('utf-8-sig').splitlines():
            if '=' in line and not line.lstrip().startswith('#'):
                key, value = line.split('=', 1)
                values[key.strip().upper()] = value.strip()
    return values


def yaml_key_values(path):
    """Read the two scalar key fields without requiring a new YAML dependency.

    Supports the application's plain/single/double quoted scalar fields, including
    comments. Reject unsupported block/alias/tag syntax rather than silently
    missing a secret. This is deliberately not a general-purpose YAML loader.
    """
    if not path.is_file():
        raise SafeCheckError('application.yml is missing')
    field = re.compile(r'^\s*(?:chat-api-key|embedding-api-key)\s*:\s*(.*?)\s*$')
    result = []
    for line_number, line in enumerate(path.read_text('utf-8-sig').splitlines(), 1):
        match = field.match(line)
        if not match:
            continue
        raw = match[1]
        location = f'application.yml:{line_number}'
        if not raw or raw.startswith('#'):
            continue
        if raw.startswith('"'):
            try:
                value, end = json.JSONDecoder().raw_decode(raw)
            except ValueError:
                raise SafeCheckError(f'Unsupported quoted credential syntax at {location}') from None
            tail = raw[end:].strip()
            if not isinstance(value, str) or (tail and not tail.startswith('#')):
                raise SafeCheckError(f'Unsupported credential syntax at {location}')
        elif raw.startswith("'"):
            quoted = re.fullmatch(r"'((?:[^']|'')*)'\s*(?:#.*)?", raw)
            if not quoted:
                raise SafeCheckError(f'Unsupported quoted credential syntax at {location}')
            value = quoted[1].replace("''", "'")
        else:
            value = re.split(r'\s+#', raw, maxsplit=1)[0].rstrip()
            if value[0] in '|>&*!{[':
                raise SafeCheckError(f'Unsupported credential syntax at {location}; use a quoted scalar')
            if value.lower() in ('null', '~'):
                continue
        result.append(value)
    return result


def private_value(value, environment):
    # Spring placeholders are templates, not literal credentials. Resolve any
    # known local/process value or a literal default before deciding to scan it.
    placeholder = re.fullmatch(r'\$\{([A-Za-z_][A-Za-z0-9_]*)(?::([^{}]*))?\}', value)
    if placeholder:
        value = environment.get(placeholder[1], os.environ.get(placeholder[1], placeholder[2] or ''))
    return value if value and value not in EXAMPLES else None


def collect_secrets(root):
    environment = read_environment(root / '.local/app.env')
    candidates = [environment[name] for name in SECRET_NAMES if environment.get(name)]
    candidates.extend(yaml_key_values(root / 'backend/src/main/resources/application.yml'))
    return {value for candidate in candidates if (value := private_value(candidate, environment))}


def scan(root):
    # Also match common JSON-escaped forms (e.g. keys in browser bundles/logs).
    needles = set()
    for secret in collect_secrets(root):
        needles.add(secret.encode('utf-8'))
        needles.add(json.dumps(secret, ensure_ascii=True)[1:-1].encode('utf-8'))
    checked = 0
    for folder in (root / 'frontend-chat/dist', root / 'frontend-docs/dist', root / '.local/logs'):
        for file in sorted(folder.rglob('*')):
            if not file.is_file():
                continue
            content = file.read_bytes()
            if any(needle in content for needle in needles):
                # Do not include a filename that could itself contain a secret.
                raise SafeCheckError('Credential leak detected in a built-asset/log file (contents withheld)')
            checked += 1
    if not checked:
        raise SafeCheckError('No built-asset/log files found; build the frontends before checking')
    return checked


class SecurityCheckTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='rag-security-check-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.yaml = self.root / 'backend/src/main/resources/application.yml'
        self.yaml.parent.mkdir(parents=True)
        self.yaml.write_text('rag:\n  chat-api-key: ""\n  embedding-api-key: \'\'\n', encoding='utf-8')
        self.env = self.root / '.local/app.env'
        self.env.parent.mkdir(parents=True)
        self.env.write_text('DB_PASSWORD=replace-with-a-long-random-password\n'
                            'DB_ROOT_PASSWORD=replace-with-a-different-long-random-password\n'
                            'CHAT_API_KEY=local-not-a-secret\n', encoding='utf-8')
        self.asset = self.root / 'frontend-chat/dist/main.js'
        self.asset.parent.mkdir(parents=True)
        self.asset.write_text('local-not-a-secret replace-with-a-long-random-password', encoding='utf-8')

    def test_templates_and_legacy_example_are_not_secrets(self):
        self.yaml.write_text('rag:\n  chat-api-key: ${CHAT_API_KEY:}\n'
                             '  embedding-api-key: ${UNSET_CLOUD_KEY:local-not-a-secret}\n', encoding='utf-8')
        self.assertEqual(scan(self.root), 1)

    def test_yaml_values_are_read_without_requiring_app_env(self):
        self.env.unlink()
        self.yaml.write_text('rag:\n  chat-api-key: "fixture-secret#part" # comment\n'
                             "  embedding-api-key: 'fixture''embedding'\n", encoding='utf-8')
        self.assertEqual(collect_secrets(self.root), {'fixture-secret#part', "fixture'embedding"})

    def test_each_credential_source_detects_leaks_without_echo(self):
        for source in ('DB_PASSWORD', 'DB_ROOT_PASSWORD', 'CHAT_API_KEY', 'EMBEDDING_API_KEY', 'yaml-chat', 'yaml-embedding'):
            with self.subTest(source=source):
                secret = 'fixture-confidential-' + source
                self.env.write_text(f'{source}={secret}\n', encoding='utf-8')
                self.yaml.write_text('rag:\n  chat-api-key: "' + (secret if source == 'yaml-chat' else '') + '"\n'
                                     '  embedding-api-key: "' + (secret if source == 'yaml-embedding' else '') + '"\n', encoding='utf-8')
                self.asset.write_text(secret, encoding='utf-8')
                with self.assertRaises(SafeCheckError) as error:
                    scan(self.root)
                self.assertNotIn(secret, str(error.exception))

    def test_logs_are_scanned_and_sources_are_not_leak_targets(self):
        self.yaml.write_text('rag:\n  chat-api-key: fixture-server-key\n', encoding='utf-8')
        self.assertEqual(scan(self.root), 1)
        log = self.root / '.local/logs/backend.out.log'
        log.parent.mkdir(parents=True)
        log.write_text('fixture-server-key', encoding='utf-8')
        with self.assertRaises(SafeCheckError):
            scan(self.root)

    def test_unsupported_yaml_fails_closed_without_echo(self):
        self.yaml.write_text('rag:\n  chat-api-key: &fixture-secret alias\n', encoding='utf-8')
        with self.assertRaises(SafeCheckError) as error:
            scan(self.root)
        self.assertNotIn('fixture-secret', str(error.exception))

    def test_missing_assets_are_not_a_vacuous_pass(self):
        self.asset.unlink()
        with self.assertRaises(SafeCheckError):
            scan(self.root)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--self-test', action='store_true', help='Run isolated scanner regression fixtures only')
    args = parser.parse_args()
    if args.self_test:
        unittest.main(argv=['security-check.py'])
    else:
        try:
            count = scan(ROOT)
        except SafeCheckError as error:
            print(f'FAIL: {error}')
            raise SystemExit(1) from None
        except (OSError, UnicodeError):
            print('FAIL: could not read a credential source or scan target (details withheld)')
            raise SystemExit(1) from None
        print(f'PASS: no configured private credentials in {count} built-asset/log files.')
