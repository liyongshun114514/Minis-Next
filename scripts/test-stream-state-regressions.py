#!/usr/bin/env python3
"""Compile real Kotlin parser/flow/tracker + JUnit regressions, without AGP/NDK.
Android service, registry and resource dependencies are inert TEST STUBS; actual
tracker state machine is production source. No device/GPU acceptance is claimed.
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
p.add_argument('--baseline', action='store_true', help='compile pre-fix parser from --baseline-ref (expected failures)')
p.add_argument('--baseline-ref', default='9068b21e1ecd12e087f0bb9976ca202015f14598')
a = p.parse_args()
w = Path(a.work_dir).resolve(); w.mkdir(parents=True, exist_ok=True)
lib = Path('/opt/gradle-8.11.1/lib')
java = '/usr/lib/jvm/java-21-openjdk-arm64/bin/java'
jars = [lib/'kotlin-stdlib-2.0.20.jar', lib/'junit-4.13.2.jar', lib/'hamcrest-core-1.3.jar', lib/'kotlinx-coroutines-core-jvm-1.6.4.jar']
# JVM Android stubs are used only to satisfy APIs: every state transition below
# executes the repository's SessionActivityTracker, not an extracted imitation.
stubs = {
'AndroidContext.kt': '''package android.content
open class Context { val applicationContext get() = this; fun getString(id: Int, vararg args: Any): String = "test" }
''',
'AndroidClock.kt': '''package android.os
object SystemClock { @JvmStatic fun elapsedRealtime(): Long = System.nanoTime()/1000000 }
''',
'AndroidLog.kt': '''package android.util
object Log { fun d(t: String, s: String)=0; fun w(t: String, s: String)=0 }
''',
'Registry.kt': '''package com.openminis.app.agent.jobs
import kotlinx.coroutines.flow.MutableStateFlow
object AgentJobRegistry { val parentsWithAgentWork = MutableStateFlow<Set<String>>(emptySet()); fun hasAgentWork(s: String) = s in parentsWithAgentWork.value }
''',
'Service.kt': '''package com.openminis.app.service
import android.content.Context
object AgentForegroundService { var isAlive=false; fun startService(c: Context, n: Int, s: String)=true; fun stopService(c: Context) {} }
''',
'Scheduled.kt': '''package com.openminis.app.scheduled
class ScheduledWait
class ScheduledFiring
class ScheduledTaskManager(c: android.content.Context) { fun disableAllCreatedBy(s: String, reason: String) {} }
''',
'Resources.kt': '''package com.openminis.app
object R { object string { const val notif_one_task_running=1; const val notif_n_tasks_running=2; const val notif_scheduled_waiting=3; const val notif_in_session=4 } }
''',
'Outcome.kt': '''package com.openminis.app.notification
enum class BackgroundTurnOutcome { FAILED, INTERRUPTED, COMPLETED; companion object { fun decide(streamError: Boolean, turnError: Boolean, canResume: Boolean, userStopped: Boolean) = if(streamError || turnError) FAILED else if(canResume || userStopped) INTERRUPTED else COMPLETED } }
''',
'ModelDeps.kt': '''package com.openminis.app.data.model
class LLMUsage
class LLMMediaAttachment
''',
'Json.kt': '''package org.json
class JSONObject
''',
}
files=[]
for name, text in stubs.items():
    f=w/name; f.write_text(text); files.append(f)
production=['provider/openai/ThinkPrefixStreamParser.kt', 'provider/ThinkingPrefixFlow.kt', 'data/model/LLMStreamChunk.kt', 'service/SessionActivityTracker.kt', 'service/ToolOutcome.kt']
if not a.baseline: production += ['ui/chat/LiveMessageUpdates.kt']
for rel in production:
    src=MAIN/rel
    if a.baseline:
        # New adapter is necessary to exercise old parser behavior, and new owner
        # API cannot compile against old tracker. Baseline run is parser-only.
        if rel.startswith('service/'): continue
        if rel.endswith('ThinkingPrefixFlow.kt'): text=src.read_text().replace('ThinkPrefixStreamParser(preserveBodyWhitespace = true)', 'ThinkPrefixStreamParser()')
        else: text=subprocess.check_output(['git','show',f'{a.baseline_ref}:{src.relative_to(ROOT)}'], cwd=ROOT, text=True)
        dest=w/src.name; dest.write_text(text); files.append(dest)
    else: files.append(src)
tests=['provider/ThinkPrefixStreamParserTest.kt', 'provider/ThinkingPrefixFlowTest.kt']
if not a.baseline:
    tests += ['service/SessionRunOwnershipTest.kt', 'ui/chat/QueuedPromptInjectRaceTest.kt', 'ui/chat/LiveMessageAtomicityTest.kt', 'ui/chat/StreamStateWiringTest.kt', 'browser/HeadlessGpuCaptureWiringTest.kt', 'ui/chat/ScheduledPrefillWiringTest.kt']
    files += [TEST/'ProductionSources.kt']
files += [TEST/t for t in tests]
cp=os.pathsep.join(map(str,jars))
compiler=os.pathsep.join(str(f) for f in lib.glob('*.jar'))
cmd=[java,'-Xmx1536m','-cp',compiler,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-d',str(w/'classes')]+list(map(str, files))
r=subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
(w/'compile.log').write_text(r.stdout)
if r.returncode: print(r.stdout); raise SystemExit(r.returncode)
classes=['com.openminis.app.'+t.removesuffix('.kt').replace('/','.') for t in tests]
r=subprocess.run([java,'-cp',str(w/'classes')+os.pathsep+cp,'org.junit.runner.JUnitCore']+classes, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, cwd=ROOT/'src/android/app')
(w/'junit.log').write_text(r.stdout); print(r.stdout); raise SystemExit(r.returncode)
