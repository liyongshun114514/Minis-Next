#!/usr/bin/env python3
"""Run the queued-message regressions against REAL production Kotlin sources.

Compiles (no AGP/NDK, cached Kotlin 2.0.20 + JUnit 4.13.2 from the local
Gradle distribution) and runs:

  * QueuedMessageState.kt  — production list algebra (deliver / reattach /
    needsConsumer), exercised directly, plus a real 4-thread CAS stress;
  * LiveMessageUpdates.kt  — the targeted-patch helper the drain uses;
  * QueuedPromptInjectRaceTest, LiveMessageAtomicityTest — existing arithmetic
    and CAS regressions;
  * StreamStateWiringTest, Review0923SubAgentQueueInvariantsTest — source
    drift guards over the queue段 of ChatViewModel.kt.

This is not a device test: no ChatViewModel instance is constructed (it needs
Context + Room + a provider). The decision arithmetic and the send-funnel model
are executed; the wiring guards read the production text.

Usage:
  python3 scripts/test-queued-message-regressions.py --work-dir <empty dir>
"""
import argparse
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / 'src/android/app/src/main/java/com/openminis/app'
TEST = ROOT / 'src/android/app/src/test/java/com/openminis/app'

p = argparse.ArgumentParser()
p.add_argument('--work-dir', required=True)
p.add_argument('--baseline-ref', default=None,
               help='also compile the pre-fix QueuedMessageState replacement from this ref '
                    'and show the invariant test failing (expected nonzero)')
a = p.parse_args()

w = Path(a.work_dir).resolve()
w.mkdir(parents=True, exist_ok=True)
lib = Path('/opt/gradle-8.11.1/lib')
java = '/usr/lib/jvm/java-21-openjdk-arm64/bin/java'
jars = [lib / 'kotlin-stdlib-2.0.20.jar',
        lib / 'junit-4.13.2.jar',
        lib / 'hamcrest-core-1.3.jar',
        lib / 'kotlinx-coroutines-core-jvm-1.6.4.jar']
cp = os.pathsep.join(map(str, jars))
compiler = os.pathsep.join(str(f) for f in lib.glob('*.jar'))

production = ['ui/chat/QueuedMessageState.kt', 'ui/chat/LiveMessageUpdates.kt']
tests = ['ui/chat/QueuedMessageStateTest.kt',
         'ui/chat/QueuedPromptInjectRaceTest.kt',
         'ui/chat/LiveMessageAtomicityTest.kt',
         'ui/chat/StreamStateWiringTest.kt',
         'ui/chat/Review0923SubAgentQueueInvariantsTest.kt']

files = [MAIN / rel for rel in production]
files += [TEST / rel for rel in tests]
files += [TEST / 'ProductionSources.kt']

if a.baseline_ref:
    # A pre-fix stand-in: the two-emit shape, reproduced as the same public API
    # so the regression file compiles against it unchanged.
    legacy = w / 'QueuedMessageStateLegacy.kt'
    legacy.write_text('''package com.openminis.app.ui.chat

internal object QueuedMessageState {
    fun <M> deliver(
        current: List<M>,
        queuedIds: Set<String>,
        queuedPromptIdOf: (M) -> String?,
        idOf: (M) -> String,
        newBubbles: List<M>,
    ): List<M> {
        // Pre-fix shape: the predicate is read BEFORE the removal, and the
        // append is gated on it. False => the placeholder goes and nothing
        // comes back.
        val mayCommit = current.any { queuedPromptIdOf(it) in queuedIds }
        val removed = current.filterNot { m ->
            val pid = queuedPromptIdOf(m)
            pid != null && pid in queuedIds
        }
        if (!mayCommit) return removed
        return removed + newBubbles
    }

    fun <P, M> reattach(
        current: List<M>,
        prompts: List<P>,
        promptIdOf: (P) -> String,
        queuedPromptIdOf: (M) -> String?,
        bubbleFor: (P) -> M,
    ): List<M> = current

    fun needsConsumer(isStreaming: Boolean, queueSize: Int, kickActive: Boolean, drainFailed: Boolean) =
        false
}
''')
    files = [legacy] + [f for f in files if f.name != 'QueuedMessageState.kt']
    print('BASELINE mode: legacy QueuedMessageState stand-in; failures are expected')

cmd = [java, '-Xmx1024m', '-cp', compiler, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
       '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', cp,
       '-d', str(w / 'classes')] + list(map(str, files))
r = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
(w / 'compile.log').write_text(r.stdout)
if r.returncode:
    print(r.stdout)
    raise SystemExit(r.returncode)

classes = ['com.openminis.app.' + t.removesuffix('.kt').replace('/', '.') for t in tests]
r = subprocess.run([java, '-cp', str(w / 'classes') + os.pathsep + cp,
                    'org.junit.runner.JUnitCore'] + classes,
                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                   cwd=ROOT / 'src/android/app')
(w / 'junit.log').write_text(r.stdout)
print(r.stdout)
raise SystemExit(r.returncode)
