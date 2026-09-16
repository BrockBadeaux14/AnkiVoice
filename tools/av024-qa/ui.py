"""UI-tree-only controls for the dedicated AV024 AVD."""
import importlib.util
from pathlib import Path
import sys
spec = importlib.util.spec_from_file_location('av004', Path(__file__).parents[1] / 'av004-probe/run.py')
av = importlib.util.module_from_spec(spec)
spec.loader.exec_module(av)
av.OUT = Path(__file__).resolve().parents[2] / 'build/av024/ui'
av.OUT.mkdir(exist_ok=True, parents=True)
if av.adb('emu', 'avd', 'name').decode().splitlines()[0] != 'AnkiVoice_AV024':
    raise RuntimeError('Dedicated AV024 emulator required')
if __name__ == '__main__':
    if len(sys.argv) > 1:
        av.tap(sys.argv[1])
    else:
        for n in av.ui().iter('node'):
            if n.get('text') or n.get('content-desc'):
                print(n.get('text'), n.get('content-desc'), n.get('bounds'))
