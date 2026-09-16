#!/usr/bin/env python3
"""One counted automated call trial. No operator attestations or injected speech."""
import argparse, json, os, pathlib, subprocess, time
p=argparse.ArgumentParser()
p.add_argument('phase',choices=['playback','capture']); p.add_argument('--serial',default='emulator-5588'); p.add_argument('--output',type=pathlib.Path,required=True)
a=p.parse_args(); a.output.mkdir(parents=True,exist_ok=True)
adb=os.environ.get('ADB',str(pathlib.Path.home()/'Library/Android/sdk/platform-tools/adb'))
def call(*args): return subprocess.run([adb,'-s',a.serial,*args],text=True,capture_output=True,check=True).stdout
def save(name,text): (a.output/name).write_text(text)
call('shell','am','force-stop','org.ankivoice.av042')
call('logcat','-c')
record={'phase':a.phase,'started_epoch_ms':int(time.time()*1000),'commands':[],'operator_attestation':None}
call('shell','am','start','-n','org.ankivoice.av042/.ProbeActivity','--es','case','call_'+a.phase,'--ei','index','1','--ez','auto','true')
marker='playback_started' if a.phase=='playback' else 'recorder_started'
deadline=time.monotonic()+40
triggered=False
try:
    while time.monotonic()<deadline:
        log=call('logcat','-d','-s','AV042:I','*:S')
        if 'event='+marker in log:
            # Ensure call overlaps a still-active phase; use a brief settling wait for capture.
            if a.phase=='capture': time.sleep(0.6)
            record['trigger_epoch_ms']=int(time.time()*1000)
            record['trigger_marker']=marker
            record['commands'].append(call('emu','gsm','call','5550042'));triggered=True
            # Allow Telecom's asynchronous incoming-call filtering and ringer handling to finish.
            time.sleep(3)
            save('telecom.txt',call('shell','dumpsys','telecom'))
            save('audio.txt',call('shell','dumpsys','audio'))
            save('events.txt',call('logcat','-d','-s','AV042:I','*:S'))
            save('system-events.txt',call('logcat','-d','-s','Telecom:I','AS.AudioService:I','AS.MediaFocusControl:I','*:S'))
            break
        if 'event=closing' in log: record['error']='trial_closed_before_trigger';break
        time.sleep(.1)
    else: record['error']='trigger_deadline'
finally:
    if triggered: record['commands'].append(call('emu','gsm','cancel','5550042'))
    time.sleep(.5)
    save('ledger.json',call('exec-out','run-as','org.ankivoice.av042','cat','files/ledger.json'))
    save('controller.json',json.dumps(record,indent=2)+'\n')
print(json.dumps(record))
