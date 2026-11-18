#!/usr/bin/env python3
"""Build with the Android SDK and a JDK, using no downloaded dependencies."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--release', action='store_true', help='Build without Android debugging enabled')
parser.add_argument('--output', type=Path, help='Destination APK path')
args = parser.parse_args()

root = Path(__file__).resolve().parent
output = args.output.resolve() if args.output else root.parent / 'ClipDivide.apk'
output.parent.mkdir(parents=True, exist_ok=True)
sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk')))
java = Path(os.environ.get('JAVA_HOME', '/Applications/Android Studio.app/Contents/jbr/Contents/Home'))
tools = sdk / 'build-tools/36.0.0'
android = sdk / 'platforms/android-37.0/android.jar'
build = root / 'build'
build.mkdir(exist_ok=True)
classes = build / 'classes'
shutil.rmtree(classes, ignore_errors=True)
classes.mkdir(exist_ok=True)
generated = build / 'generated'
generated.mkdir(exist_ok=True)

def run(*args):
    subprocess.run([str(a) for a in args], check=True, env={**os.environ, 'JAVA_HOME': str(java)})

manifest = build / 'AndroidManifest.xml'
manifest.write_text((root / 'app/src/main/AndroidManifest.xml').read_text().replace('<manifest ', '<manifest package="app.clipdivide" '))

run(tools / 'aapt2', 'compile', '--dir', root / 'app/src/main/res', '-o', build / 'resources.zip')
run(tools / 'aapt2', 'link', '-o', build / 'base.apk', '-I', android,
    '--manifest', manifest, '--min-sdk-version', '29', '--target-sdk-version', '36', '--version-code', '3', '--version-name', '1.2', '--java', generated, *([] if args.release else ['--debug-mode']), '--auto-add-overlay', build / 'resources.zip')
run(java / 'bin/javac', '-encoding', 'UTF-8', '-source', '17', '-target', '17',
    '-classpath', android, '-d', classes, *sorted((root / 'app/src/main/java').rglob('*.java')), *sorted(generated.rglob('*.java')))
run(tools / 'd8', '--lib', android, '--min-api', '29', '--output', build, *sorted(classes.rglob('*.class')))
shutil.copyfile(build / 'base.apk', build / 'unsigned.apk')
with zipfile.ZipFile(build / 'unsigned.apk', 'a') as apk:
    apk.write(build / 'classes.dex', 'classes.dex')
run(tools / 'zipalign', '-f', '4', build / 'unsigned.apk', build / 'aligned.apk')
key = Path(os.environ.get('CLIPDIVIDE_KEYSTORE', str(root / '.signing/clipdivide.jks')))
key.parent.mkdir(parents=True, exist_ok=True)
if not key.exists():
    run(java / 'bin/keytool', '-genkeypair', '-keystore', key, '-storepass', 'android',
        '-keypass', 'android', '-alias', 'clipdivide', '-keyalg', 'RSA', '-keysize', '2048',
        '-validity', '10000', '-dname', 'CN=ClipDivide local testing')
run(tools / 'apksigner', 'sign', '--ks', key, '--ks-key-alias', 'clipdivide',
    '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', output, build / 'aligned.apk')
run(tools / 'apksigner', 'verify', output)
print(output)
